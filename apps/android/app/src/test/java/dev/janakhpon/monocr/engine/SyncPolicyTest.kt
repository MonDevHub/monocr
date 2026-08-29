package dev.janakhpon.monocr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

/**
 * Unit tests for [SyncPolicy], the decision half of [SyncWorker].
 *
 * These cover three defects that shipped together, and the reason they shipped is the
 * reason this file exists: every one of them lived inside a `CoroutineWorker`, where no
 * plain JVM test could reach it. The fix extracted the decisions — retry, request id,
 * size cap — into an object that touches no Android class, so they are now assertable
 * without an emulator.
 *
 * What is deliberately NOT claimed here, so nobody reads a green bar as more than it is:
 *
 *  - `SyncService.triggerSync` using `ExistingWorkPolicy.APPEND_OR_REPLACE` is NOT tested.
 *    The behaviour that matters — a second trigger arriving while a worker RUNS is
 *    serialised behind it instead of racing it — is WorkManager's own scheduling, and
 *    observing it needs `WorkManagerTestInitHelper` from `androidx.work:work-testing` on
 *    an instrumented or Robolectric run. Neither is on this module's test classpath.
 *  - `SyncWorker.syncMutex` serialising a sweep against an immediate sync is NOT tested.
 *    It guards two `CoroutineWorker` instances that WorkManager creates, so reaching it
 *    means constructing workers, which means Robolectric or an instrumented run.
 *  - `doWork` returning `Result.retry()` is NOT tested. [SyncPolicy.decide] is what
 *    chooses, and that is tested exhaustively below; the two-line mapping from
 *    [SyncDecision] to WorkManager's `Result` needs a real worker instance.
 *  - The `X-Request-ID` header actually reaching the wire is NOT tested. Only its
 *    construction is. `uploadToFeedbackService` builds an `HttpURLConnection` against a
 *    hardcoded production URL.
 */
class SyncPolicyTest {

    // MARK: - Request id (defect: both payloads shared one idempotency key)

    /**
     * The two suffixes are a cross-client wire contract — the iOS client emits the same
     * two strings — so these are asserted as literals rather than built from the enum.
     * A test that derived the expected value from [SyncPayload.requestIdSuffix] would
     * agree with any rename and catch nothing.
     */
    @Test
    fun `each payload gets its own request id suffix`() {
        val id = "0f8b1c22-4a71-4b2e-9d0a-1c3f5e7a9b11"

        assertEquals("$id:file", SyncPolicy.requestId(id, SyncPayload.FILE))
        assertEquals("$id:transcription", SyncPolicy.requestId(id, SyncPayload.TRANSCRIPTION))
    }

    /**
     * The actual defect, stated as a property: the file and the transcription of ONE
     * record must not collide. This is what made the header useless as an idempotency
     * key, and what would silently drop half of every dual-payload contribution the day
     * the service starts honouring it.
     */
    @Test
    fun `the two payloads of one record never share a request id`() {
        val id = "same-record"

        assertNotEquals(
            SyncPolicy.requestId(id, SyncPayload.FILE),
            SyncPolicy.requestId(id, SyncPayload.TRANSCRIPTION)
        )
    }

    /**
     * The suffix must not be one that a bare record id could already end in, or a
     * suffixed id and an unsuffixed one would be ambiguous to the service's log reader.
     * The separator is the load-bearing character here, not the word.
     */
    @Test
    fun `the request id is the record id plus a colon-delimited suffix and nothing else`() {
        val id = "abc"

        for (payload in SyncPayload.entries) {
            val requestId = SyncPolicy.requestId(id, payload)
            assertTrue("$requestId must start with the bare record id", requestId.startsWith("$id:"))
            assertEquals("exactly one separator", 1, requestId.count { it == ':' })
            assertEquals("suffix must not be empty", 2, requestId.split(":").size)
            assertTrue("suffix must not be blank", requestId.substringAfter(":").isNotBlank())
        }
    }

    /**
     * The format assumes the record id contains no colon of its own, or the header would
     * be ambiguous to split. Nothing in the type system holds that, so it is asserted
     * against the generator that actually produces the ids — `HistoryRecord.syncId` is
     * `UUID.randomUUID().toString()` — rather than against a literal.
     *
     * The first version of this test asserted `":" !in "0f8b1c22-..."` on a literal
     * declared two lines above it, referencing no production code. It could only fail if
     * someone edited the test's own string. Caught in review of this file.
     */
    @Test
    fun `a request id built from a real generated record id has exactly one separator`() {
        repeat(200) {
            val generated = UUID.randomUUID().toString()
            for (payload in SyncPayload.entries) {
                val requestId = SyncPolicy.requestId(generated, payload)
                assertEquals("ambiguous request id: $requestId", 1, requestId.count { c -> c == ':' })
                assertEquals(generated, requestId.substringBefore(":"))
            }
        }
    }

    // MARK: - Failure classification (defect: nothing distinguished retryable from not)

    @Test
    fun `an oversize payload is permanent because no retry can shrink it`() {
        assertEquals(
            SyncOutcome.PERMANENT_FAILURE,
            SyncPolicy.classify(PayloadTooLargeException(SyncPolicy.MAX_PAYLOAD_BYTES))
        )
    }

    /**
     * 5xx is the server's own admission that the request was fine.
     */
    @Test
    fun `server errors are transient`() {
        for (code in listOf(500, 502, 503, 504, 599)) {
            assertEquals(
                "HTTP $code should be retried",
                SyncOutcome.TRANSIENT_FAILURE,
                SyncPolicy.classify(SyncHttpException(code, ""))
            )
        }
    }

    /**
     * The two 4xx that mean "later" rather than "never". 429 matters concretely: the
     * service runs a rate limiter, so a sweep of a backlog is the expected way to hit it,
     * and treating it as permanent would retire a queue of perfectly good records.
     */
    @Test
    fun `request timeout and rate limiting are transient despite being 4xx`() {
        assertEquals(SyncOutcome.TRANSIENT_FAILURE, SyncPolicy.classify(SyncHttpException(408, "")))
        assertEquals(SyncOutcome.TRANSIENT_FAILURE, SyncPolicy.classify(SyncHttpException(429, "")))
    }

    /**
     * Every other 4xx is permanent: a bad API key or a rejected form will be rejected
     * identically in thirty seconds.
     *
     * 400 is in here for an unhappy reason worth recording. The service maps an
     * over-limit body onto `400 "No file part in the request"`, so before the size cap
     * existed this was the code an oversize upload came back as. The local cap is what
     * stops that case ever reaching here.
     */
    @Test
    fun `other client errors are permanent`() {
        for (code in listOf(400, 401, 403, 404, 413, 422)) {
            assertEquals(
                "HTTP $code should not be retried",
                SyncOutcome.PERMANENT_FAILURE,
                SyncPolicy.classify(SyncHttpException(code, ""))
            )
        }
    }

    /**
     * The everyday case on a phone, and the one the unreachable retry was costing:
     * a tunnel, a dropped wifi handoff, an airplane-mode toggle.
     */
    @Test
    fun `network failures are transient`() {
        val networkErrors = listOf(
            SocketTimeoutException("timeout"),
            UnknownHostException("no dns"),
            IOException("unexpected end of stream")
        )
        for (e in networkErrors) {
            assertEquals(
                "${e.javaClass.simpleName} should be retried",
                SyncOutcome.TRANSIENT_FAILURE,
                SyncPolicy.classify(e)
            )
        }
    }

    /**
     * Unknown failures default to transient, and the asymmetry is the point: guessing
     * "transient" wrongly costs two attempts under backoff, guessing "permanent" wrongly
     * silently retires a contribution the user believes they made.
     */
    @Test
    fun `an unrecognised failure defaults to transient`() {
        assertEquals(SyncOutcome.TRANSIENT_FAILURE, SyncPolicy.classify(IllegalStateException("?")))
        assertEquals(SyncOutcome.TRANSIENT_FAILURE, SyncPolicy.classify(RuntimeException()))
        // A status code outside 4xx and 5xx entirely, e.g. a proxy returning nonsense.
        assertEquals(SyncOutcome.TRANSIENT_FAILURE, SyncPolicy.classify(SyncHttpException(0, "")))
    }

    // MARK: - The worker's result (defect: success was reported when nothing uploaded)

    /**
     * The defect in one assertion. `doWork` used to reach `Result.success()` whenever
     * `syncRecord` returned, and `syncRecord` swallowed its own exceptions — so a run in
     * which every single record failed told WorkManager the job was complete. No retry,
     * no backoff, recovery deferred to the 15-minute sweep however brief the outage was.
     */
    @Test
    fun `a run where every record failed transiently must not report success`() {
        val allFailed = listOf(SyncOutcome.TRANSIENT_FAILURE, SyncOutcome.TRANSIENT_FAILURE)

        assertEquals(SyncDecision.RETRY, SyncPolicy.decide(allFailed, runAttemptCount = 0))
    }

    @Test
    fun `a fully successful run succeeds`() {
        assertEquals(
            SyncDecision.SUCCESS,
            SyncPolicy.decide(listOf(SyncOutcome.SUCCESS, SyncOutcome.SUCCESS), runAttemptCount = 0)
        )
    }

    /**
     * An empty outcome list means the queue was empty, which is a successful no-op.
     * Retrying it would be a scheduled wake-up to do nothing.
     */
    @Test
    fun `an empty run succeeds`() {
        assertEquals(SyncDecision.SUCCESS, SyncPolicy.decide(emptyList(), runAttemptCount = 0))
    }

    /**
     * A permanent failure is already recorded in the record's `syncError`, so the run
     * itself is done. Retrying would spend the record's `syncAttempts` budget on an
     * outcome that cannot change, and would re-read the oversize file off disk each time.
     */
    @Test
    fun `permanent failures alone do not trigger a retry`() {
        assertEquals(
            SyncDecision.SUCCESS,
            SyncPolicy.decide(listOf(SyncOutcome.PERMANENT_FAILURE), runAttemptCount = 0)
        )
        assertEquals(
            SyncDecision.SUCCESS,
            SyncPolicy.decide(
                listOf(SyncOutcome.SUCCESS, SyncOutcome.PERMANENT_FAILURE),
                runAttemptCount = 0
            )
        )
    }

    /**
     * One salvageable record in a batch is enough to earn the whole run another go — the
     * successful ones are already `isSynced` and will not be re-uploaded.
     */
    @Test
    fun `a single transient failure among successes still retries`() {
        assertEquals(
            SyncDecision.RETRY,
            SyncPolicy.decide(
                listOf(SyncOutcome.SUCCESS, SyncOutcome.TRANSIENT_FAILURE, SyncOutcome.PERMANENT_FAILURE),
                runAttemptCount = 0
            )
        )
    }

    /**
     * The retry budget, boundary by boundary. `runAttemptCount` is 0 on the first run, so
     * [SyncPolicy.MAX_RETRIES] of 2 means runs at 0 and 1 retry and the run at 2 gives up:
     * three runs total.
     *
     * Three is chosen against the `syncAttempts < 5` cap in `HistoryDao`, not in ignorance
     * of it — each run charges a failing record one attempt, so a full chain spends 3 of
     * 5 and leaves the periodic sweep two more once the network is genuinely back. The
     * last assertion is what keeps that arithmetic honest if MAX_RETRIES is ever raised.
     */
    @Test
    fun `the retry budget stops at MAX_RETRIES and cannot exhaust the record attempt cap`() {
        val transient = listOf(SyncOutcome.TRANSIENT_FAILURE)

        assertEquals(SyncDecision.RETRY, SyncPolicy.decide(transient, runAttemptCount = 0))
        assertEquals(SyncDecision.RETRY, SyncPolicy.decide(transient, runAttemptCount = 1))
        assertEquals(SyncDecision.FAILURE, SyncPolicy.decide(transient, runAttemptCount = 2))
        assertEquals(SyncDecision.FAILURE, SyncPolicy.decide(transient, runAttemptCount = 3))
        assertEquals(SyncDecision.FAILURE, SyncPolicy.decide(transient, runAttemptCount = 99))

        val runsPerChain = SyncPolicy.MAX_RETRIES + 1
        val recordAttemptCap = 5 // HistoryDao: `WHERE ... syncAttempts < 5`
        assertTrue(
            "a single retry chain ($runsPerChain runs) must leave attempts for the periodic sweep",
            runsPerChain < recordAttemptCap
        )
    }

    // MARK: - Size cap (defect: readBytes() with no limit)

    /**
     * The cap must sit BELOW the service's body limit, not at it.
     *
     * iOS gets this subtly wrong in the other direction: it checks the file's size on
     * disk against the same 20 MiB the service applies to the whole multipart body, so a
     * 19.9 MiB file passes locally and is rejected remotely once boundaries and the
     * `record_id` / `original_name` parts are added. Reserving the envelope is what
     * closes that band.
     */
    @Test
    fun `the payload cap leaves room for the multipart envelope inside the service limit`() {
        assertEquals(20 * 1024 * 1024, SyncPolicy.MAX_REQUEST_BODY_BYTES)
        assertTrue(
            "payload cap must be strictly below the body limit",
            SyncPolicy.MAX_PAYLOAD_BYTES < SyncPolicy.MAX_REQUEST_BODY_BYTES
        )
        // Spelled out as literals on purpose. Asserting
        // `MAX_REQUEST_BODY_BYTES - MULTIPART_ENVELOPE_RESERVE_BYTES == MAX_PAYLOAD_BYTES`
        // restates that constant's own definition and holds for every possible value of
        // either operand — a test that cannot fail. Caught in review of this file.
        assertEquals(20967424, SyncPolicy.MAX_PAYLOAD_BYTES)
        assertTrue(
            "the reserve must cover a long UTF-8 Mon filename twice over, not be a token",
            SyncPolicy.MULTIPART_ENVELOPE_RESERVE_BYTES >= 1024
        )
    }

    @Test
    fun `a payload under the cap is read whole and unchanged`() {
        val data = ByteArray(100) { (it * 7).toByte() }

        assertArrayEquals(data, SyncPolicy.readCapped(ByteArrayInputStream(data), limit = 128))
    }

    /** Off-by-one guard: a payload of exactly the cap is allowed. */
    @Test
    fun `a payload of exactly the cap is accepted`() {
        val data = ByteArray(128) { 1 }

        assertArrayEquals(data, SyncPolicy.readCapped(ByteArrayInputStream(data), limit = 128))
    }

    /** Off-by-one guard, other side: one byte over is refused. */
    @Test
    fun `a payload one byte over the cap is refused`() {
        val data = ByteArray(129) { 1 }

        assertThrows(PayloadTooLargeException::class.java) {
            SyncPolicy.readCapped(ByteArrayInputStream(data), limit = 128)
        }
    }

    @Test
    fun `an empty payload reads as empty rather than failing`() {
        assertEquals(0, SyncPolicy.readCapped(ByteArrayInputStream(ByteArray(0)), limit = 128).size)
    }

    /**
     * The real defect: `readBytes()` allocated whatever the user picked in the document
     * picker, so a video chosen by mistake OOM-killed the app before any limit could
     * apply. The check therefore has to happen DURING the read.
     *
     * This asserts it by counting bytes actually pulled off the stream. A 900 MB source
     * must not be drained — the read stops within one chunk of the limit. Nothing here
     * allocates 900 MB: the stream is generated, not stored.
     */
    @Test
    fun `an enormous payload is abandoned mid-read instead of being buffered whole`() {
        val huge = CountingEndlessStream(totalBytes = 900L * 1024 * 1024)
        val limit = 256 * 1024

        assertThrows(PayloadTooLargeException::class.java) {
            SyncPolicy.readCapped(huge, limit = limit)
        }

        // Expressed in the API's own terms rather than in readCapped's internal buffer
        // size: `limit + 1` is the smallest read that proves the payload is over the cap,
        // so it is also the most a correct implementation ever needs. Coupling this to the
        // private chunk size made the test fail on any buffer-size change and silently
        // weaken on any shrink. Caught in review of this file.
        assertTrue(
            "read ${huge.bytesRead} bytes for a $limit-byte limit; must stop at limit + 1",
            huge.bytesRead <= limit.toLong() + 1
        )
        assertTrue("the 900 MB source must not be drained", huge.bytesRead < 900L * 1024 * 1024)
    }

    /**
     * A `ContentResolver` stream is not a `ByteArrayInputStream`: it hands back short
     * reads. If the running total were tracked per-call rather than accumulated, a file
     * arriving in small reads would sail past the cap.
     */
    @Test
    fun `the cap accumulates across short reads`() {
        val data = ByteArray(300) { 9 }

        // Under the cap, reassembled correctly from 7-byte dribbles.
        assertArrayEquals(data, SyncPolicy.readCapped(DribblingStream(data, maxPerRead = 7), limit = 512))

        // Over the cap, where every individual read is far below it.
        assertThrows(PayloadTooLargeException::class.java) {
            SyncPolicy.readCapped(DribblingStream(data, maxPerRead = 7), limit = 64)
        }
    }

    /**
     * The message is the `syncError` the user reads on the history screen, and it is the
     * whole reason for the local cap: the service's own answer for this case is
     * `400 "No file part in the request"`, which points them at nothing.
     *
     * Rounded up, so the real cap of 20 MiB-minus-envelope does not present as "19 MB".
     */
    @Test
    fun `the oversize error names a whole-MB limit the user can act on`() {
        val message = PayloadTooLargeException(SyncPolicy.MAX_PAYLOAD_BYTES).message ?: ""

        assertTrue("message was: $message", message.contains("20 MB"))
    }


    // MARK: - Error body cap (a second unbounded read, found in review)

    /**
     * Server error bodies were read with an uncapped `readText()` and stored verbatim in
     * the record's `syncError` column. A captive-portal interstitial — exactly what a
     * flaky mobile connection serves — is megabytes of HTML, read whole into the heap and
     * then written into SQLite.
     */
    @Test
    fun `a short error body survives intact`() {
        val body = """{"error":"No file part in the request","request_id":"abc"}"""

        assertEquals(body, SyncPolicy.readTruncated(ByteArrayInputStream(body.toByteArray()), limit = 1024))
    }

    /** Truncated, not refused: a clipped error message is still diagnostic. */
    @Test
    fun `an enormous error body is truncated to the cap rather than throwing`() {
        val huge = CountingEndlessStream(totalBytes = 8L * 1024 * 1024)

        val text = SyncPolicy.readTruncated(huge, limit = 64)

        assertEquals(64, text.length)
        assertEquals("read ${huge.bytesRead}; must stop at the cap", 64L, huge.bytesRead)
    }

    @Test
    fun `an empty error body reads as an empty string`() {
        assertEquals("", SyncPolicy.readTruncated(ByteArrayInputStream(ByteArray(0)), limit = 64))
    }

    /** Short reads again: a socket hands back the body in pieces. */
    @Test
    fun `the error body is reassembled across short reads`() {
        val body = "upstream connect error or disconnect/reset before headers"

        assertEquals(
            body,
            SyncPolicy.readTruncated(DribblingStream(body.toByteArray(), maxPerRead = 5), limit = 1024)
        )
    }

    @Test
    fun `the error body cap is a database-sized limit, not a network-sized one`() {
        assertEquals(4 * 1024, SyncPolicy.MAX_ERROR_BODY_BYTES)
        assertTrue(
            "an error body must never be allowed to rival a payload",
            SyncPolicy.MAX_ERROR_BODY_BYTES < SyncPolicy.MAX_PAYLOAD_BYTES
        )
    }

    // MARK: - Multipart body

    /**
     * The assertion that makes `setFixedLengthStreamingMode` safe to use at all.
     *
     * A declared `Content-Length` that disagrees with the bytes written by even one makes
     * `HttpURLConnection` throw part-way through the upload, and the upload is the only
     * thing this class does. [MultipartBody.length] and [MultipartBody.write] share their
     * two envelope builders so drift is structurally impossible — this checks that the
     * sharing actually holds, on inputs chosen to break naive character counting.
     */
    @Test
    fun `the declared body length equals the bytes actually written`() {
        val cases = listOf(
            Triple("plain.png", "image/png", 0),
            Triple("plain.png", "image/png", 1),
            Triple("plain.png", "image/png", 4096),
            // Mon, three UTF-8 bytes per character: the case that made the original
            // `writeBytes` implementation mangle names, and the case a character count
            // would get wrong by twice the title's length.
            Triple("ဘာသာမန်.pdf", "application/pdf", 128),
            // Header-injection attempt: headerSafe rewrites it, so the length must be
            // measured AFTER sanitising, not before.
            Triple("x\".txt\"\r\nContent-Type: text/html\r\n\r\n", "text/plain", 64),
            Triple("no-extension", "application/octet-stream", 999)
        )

        for ((fileName, contentType, dataSize) in cases) {
            val boundary = "Boundary-1724832000000"
            val recordId = "0f8b1c22-4a71-4b2e-9d0a-1c3f5e7a9b11"
            val data = ByteArray(dataSize) { 0x41 }

            val sink = ByteArrayOutputStream()
            MultipartBody.write(sink, boundary, recordId, fileName, contentType, data)

            assertEquals(
                "declared length disagrees with written bytes for $fileName",
                sink.size(),
                MultipartBody.length(boundary, recordId, fileName, contentType, dataSize)
            )
        }
    }

    /**
     * The bare record id must reach the wire as the `record_id` FORM FIELD even though the
     * header is suffixed — the service builds the R2 object key from this field, so a
     * suffix here would change where every object lands.
     */
    @Test
    fun `the record_id form field carries the bare id, not the suffixed request id`() {
        val recordId = "0f8b1c22-4a71-4b2e-9d0a-1c3f5e7a9b11"
        val sink = ByteArrayOutputStream()

        MultipartBody.write(sink, "Boundary-1", recordId, "a.png", "image/png", ByteArray(4))
        val wire = sink.toByteArray().toString(Charsets.UTF_8)

        assertTrue(
            "record_id part must hold the bare id",
            wire.contains("name=\"record_id\"\r\n\r\n$recordId\r\n")
        )
        assertFalse("no request-id suffix belongs in the form data", wire.contains("$recordId:file"))
    }

    /** Mon filenames are the common case for this app, not an edge one. */
    @Test
    fun `a Mon filename is encoded as UTF-8 on the wire`() {
        val monName = "ဘာသာမန်.pdf"
        val sink = ByteArrayOutputStream()

        MultipartBody.write(sink, "Boundary-1", "rec", monName, "application/pdf", ByteArray(0))
        val bytes = sink.toByteArray()

        assertTrue("the UTF-8 bytes of the Mon name must appear verbatim",
            bytes.toString(Charsets.UTF_8).contains(monName))
        // Latin-1 low-byte truncation, the original defect, would have written one byte per
        // char instead of three.
        assertTrue(bytes.size > monName.length + 100)
    }

    @Test
    fun `headerSafe removes what would end a header or a quoted string`() {
        assertEquals("x'.txt'Content-Type: text/html",
            MultipartBody.headerSafe("x\".txt\"\r\nContent-Type: text/html"))
        // Everything else survives, so Mon titles are not reduced to underscores.
        assertEquals("ဘာသာမန် (၂၀၂၆).pdf", MultipartBody.headerSafe("ဘာသာမန် (၂၀၂၆).pdf"))
    }

    // MARK: - Test doubles

    /** Yields [totalBytes] bytes and records how many were actually consumed. */
    private class CountingEndlessStream(private val totalBytes: Long) : InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            if (bytesRead >= totalBytes) return -1
            bytesRead++
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val remaining = totalBytes - bytesRead
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            b.fill(0, off, off + n)
            bytesRead += n
            return n
        }
    }

    /** Returns at most [maxPerRead] bytes per call, the way a real content stream does. */
    private class DribblingStream(
        private val data: ByteArray,
        private val maxPerRead: Int
    ) : InputStream() {
        private var pos = 0

        override fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(maxPerRead, len, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    /**
     * The DEFAULT limit, not a limit the test supplies.
     *
     * Every other cap test passes `limit` explicitly, which leaves the production
     * call unguarded: `syncRecord` calls `readCapped(it)` and takes the default, so
     * replacing that default with `Int.MAX_VALUE` removed the cap entirely and the
     * whole suite stayed green. Found by mutation, and it is the same shape as the
     * defects this pass exists to fix — a guard whose test does not cover the place
     * it is applied.
     *
     * A stream one byte over the service's own payload budget must be refused
     * without the caller naming a number.
     */
    @Test
    fun `the default limit is the payload cap, not unbounded`() {
        val overBudget = SyncPolicy.MAX_PAYLOAD_BYTES + 1
        // A stream that never allocates the whole thing: `readCapped` must abandon it
        // rather than buffering 20 MiB to discover it is too big.
        val stream = object : InputStream() {
            var served = 0L
            override fun read(): Int = if (served++ < overBudget) 0x41 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= overBudget) return -1
                val n = minOf(len.toLong(), overBudget - served).toInt()
                java.util.Arrays.fill(b, off, off + n, 0x41.toByte())
                served += n
                return n
            }
        }

        try {
            SyncPolicy.readCapped(stream)
            fail("a payload over MAX_PAYLOAD_BYTES must be refused on the default limit")
        } catch (e: PayloadTooLargeException) {
            // The message has to name a number the user can act on.
            assertTrue(
                "the limit should be reported in whole MB, got: ${e.message}",
                (e.message ?: "").contains("MB")
            )
        }
    }

    /**
     * Same gap on the error-body reader, which production also calls on its default.
     */
    @Test
    fun `the default error-body limit is the error cap, not unbounded`() {
        val huge = SyncPolicy.MAX_ERROR_BODY_BYTES * 4
        val body = SyncPolicy.readTruncated("x".repeat(huge).byteInputStream())
        assertEquals(
            "the error body must be truncated on the default limit",
            SyncPolicy.MAX_ERROR_BODY_BYTES, body.length
        )
    }
}
