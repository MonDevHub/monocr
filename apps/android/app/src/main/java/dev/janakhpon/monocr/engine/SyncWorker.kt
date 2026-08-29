package dev.janakhpon.monocr.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.janakhpon.monocr.BuildConfig
import dev.janakhpon.monocr.data.HistoryDatabase
import dev.janakhpon.monocr.data.HistoryRecord
import dev.janakhpon.monocr.util.MonLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val BASE_URL = "https://ocr-feedback-service-857115062313.asia-southeast1.run.app/v1"

// From BuildConfig, set in app/build.gradle.kts out of local.properties or the
// environment. This was a 64-character literal on the line below from
// 2026-04-11 to 2026-08-16, in a public repository, directly beneath the
// production endpoint it authenticates against.
//
// Empty is a supported state: a contributor without a key builds and runs the
// app, and sync is skipped. It is not an error to report to the user.
private val API_KEY: String get() = BuildConfig.SYNC_API_KEY

/**
 * Which of the two payloads a record produces.
 *
 * The suffix is the whole point of this type. Both payloads went out under the SAME
 * `X-Request-ID` — the bare record id — and so did every retry of them.
 *
 * To be exact about the damage, because the fix is preventive rather than a repair:
 * the service reads that header only to tag its logs and echo it back
 * (`services/feedback/internal/middleware/trace.go`); it has no idempotency store, so
 * nothing is being dropped today. What was broken is the header's meaning. It
 * conventionally identifies ONE logical request, and the moment anyone adds the dedupe
 * it advertises, the transcription upload would be discarded as a replay of the file
 * upload — half of every dual-payload contribution gone, with the record still marked
 * `isSynced = true`. Two payloads under one key also make the request log unreadable
 * while diagnosing exactly that class of problem.
 *
 * These exact two suffixes are also used by the iOS client, which is making this change
 * at the same time. They are a wire contract between two independent codebases now, so
 * do not "tidy" them.
 */
internal enum class SyncPayload(val requestIdSuffix: String) {
    FILE("file"),
    TRANSCRIPTION("transcription")
}

/** What one record's upload attempt tells us about whether the worker should run again. */
internal enum class SyncOutcome {
    SUCCESS,

    /** Worth another go: a timeout, a dropped connection, a 5xx, a 429. */
    TRANSIENT_FAILURE,

    /** Retrying cannot help: the payload is too big, the request was rejected as malformed. */
    PERMANENT_FAILURE
}

/** What `doWork` should hand back to WorkManager. Kept free of WorkManager types so it is testable off-device. */
internal enum class SyncDecision { SUCCESS, RETRY, FAILURE }

/** Carries the status code out of [SyncWorker.uploadToFeedbackService] so it can be classified rather than string-matched. */
internal class SyncHttpException(val statusCode: Int, val body: String) :
    IOException("HTTP $statusCode: $body")

/**
 * The payload is larger than the service will accept, so it is pointless to send it.
 *
 * Rounded UP to whole MB deliberately. The byte cap is the service's 20 MiB minus the
 * multipart envelope reserve, so flooring it would put "19 MB" in front of a user whose
 * real ceiling is a shade under 20 — and this string is the `syncError` they read on the
 * history screen. It is the one message in this file whose whole job is to be actionable,
 * unlike the `400 "No file part"` the service would otherwise have given them.
 */
internal class PayloadTooLargeException(limit: Int) :
    IOException("Larger than the ${(limit + 1024 * 1024 - 1) / (1024 * 1024)} MB the sync service accepts")

/**
 * The decisions that used to be tangled up in `doWork`'s control flow, pulled out so a
 * plain JVM test can reach them.
 *
 * None of this touches Android, WorkManager, Room or a socket, which is deliberate: the
 * three defects fixed in this pass were all decision bugs — the wrong retry, the wrong
 * request id, the missing size check — and none of them were reachable by a test while
 * they lived inside a `CoroutineWorker`.
 */
internal object SyncPolicy {

    // These four are `val`, not `const val`, and that is not an oversight.
    //
    // Kotlin inlines a `const val` into every call site at compile time, including the
    // test source set, which compiles against this same module. So a test asserting
    // `assertEquals(20 * 1024 * 1024, MAX_REQUEST_BODY_BYTES)` compares the mutated
    // literal against itself and passes no matter what the constant is changed to — it
    // is a test that cannot fail. That was found by mutation-testing this file: raising
    // the limit to 50 MiB was the one mutation the suite did not catch. A plain `val` is
    // read through a getter at runtime, so the assertion has something real to check,
    // and the cost is one method call per upload.

    /**
     * Largest multipart body the feedback service accepts.
     *
     * There was no limit at all on this client: [readCapped]'s predecessor was a bare
     * `readBytes()`, so a 900 MB video picked out of the document picker was read
     * whole into the heap and OOM-killed the app.
     *
     * Surviving the read was no better. The service wraps every body in a
     * `http.MaxBytesReader` at this exact figure (`services/feedback/internal/config/config.go`),
     * and the overflow surfaces at its first `FormFile("file")` call. Historically that
     * was reported as `400 "No file part in the request"` — an oversize upload told the
     * user their form was malformed and never that a smaller file would work; a sibling
     * change to `internal/upload/handler.go` now distinguishes it and answers `413`.
     * Either way the client should not be sending the request: matching the number here
     * is what turns a remote rejection into an accurate local `syncError` before any
     * bytes leave the device. [SyncPolicy.classify] treats both codes as permanent.
     *
     * 20 MiB, not 20 decimal MB: it is `20 * 1024 * 1024` on the service and must stay
     * byte-identical. iOS caps at the same figure and the CLI at 500 MiB; Android was
     * the outlier with no limit at all.
     */
    val MAX_REQUEST_BODY_BYTES = 20 * 1024 * 1024

    /**
     * Headroom for the multipart envelope — boundaries, `Content-Disposition` lines and
     * the `record_id` / `original_name` fields wrapped around the bytes.
     *
     * Capping the DATA at exactly the service's BODY limit would send a file of exactly
     * the maximum size and still get it rejected, which is the confusing failure this
     * cap exists to prevent. A few hundred bytes is the real overhead; 4 KB is slack
     * for a long Mon filename, which is multi-byte in UTF-8.
     */
    val MULTIPART_ENVELOPE_RESERVE_BYTES = 4 * 1024

    /** The cap actually applied to payload bytes. */
    val MAX_PAYLOAD_BYTES = MAX_REQUEST_BODY_BYTES - MULTIPART_ENVELOPE_RESERVE_BYTES

    /**
     * How much of a server error body is worth keeping.
     *
     * Enough for a JSON error object several times over. This is not a network limit but
     * a database one — see [readTruncated] for where the string ends up.
     */
    val MAX_ERROR_BODY_BYTES = 4 * 1024

    /**
     * How many times WorkManager may retry a run that saw a transient failure, so three
     * runs in total.
     *
     * Chosen against — not in ignorance of — the `syncAttempts < 5` cap in `HistoryDao`.
     * Every run charges a failing record exactly one attempt, so a full chain spends 3 of
     * the record's 5 and cannot on its own exhaust the budget; raise this past 4 and the
     * worker's retries alone would retire a record inside a couple of minutes of backoff,
     * over what may have been one tunnel.
     *
     * What this does NOT buy, stated plainly because the first version of this comment
     * claimed it did: the 15-minute sweep runs the same worker with the same budget, so
     * its first period can spend the remaining 2 attempts in about ninety seconds. A
     * record in a sustained outage is retired in minutes, not hours. Making the cap
     * behave like a long-horizon budget needs a time-aware condition in the `HistoryDao`
     * query — "no attempt in the last hour" rather than a bare count — which is a schema
     * and query change outside this file.
     */
    val MAX_RETRIES = 2

    /**
     * `"<recordId>:file"` / `"<recordId>:transcription"`.
     *
     * The bare record id stays on the `record_id` FORM FIELD — the service builds the R2
     * object key out of that, so it must not gain a suffix. Only the header changes.
     */
    fun requestId(recordId: String, payload: SyncPayload): String =
        "$recordId:${payload.requestIdSuffix}"

    /**
     * Decide whether an upload failure is worth repeating.
     *
     * Unknown exceptions are TRANSIENT on purpose. Being wrong in that direction costs
     * two extra attempts under backoff; being wrong the other way silently retires a
     * record the user believed they had contributed.
     */
    fun classify(error: Throwable): SyncOutcome = when {
        error is PayloadTooLargeException -> SyncOutcome.PERMANENT_FAILURE

        // 408 Request Timeout and 429 Too Many Requests are the two 4xx that mean
        // "later", not "never".
        error is SyncHttpException -> when {
            error.statusCode == 408 || error.statusCode == 429 -> SyncOutcome.TRANSIENT_FAILURE
            error.statusCode in 500..599 -> SyncOutcome.TRANSIENT_FAILURE
            error.statusCode in 400..499 -> SyncOutcome.PERMANENT_FAILURE
            else -> SyncOutcome.TRANSIENT_FAILURE
        }

        else -> SyncOutcome.TRANSIENT_FAILURE
    }

    /**
     * Fold one run's per-record outcomes into the worker's result.
     *
     * The old `doWork` could not express this at all. Its `if (runAttemptCount < 3)
     * Result.retry()` sat in the OUTER catch, but `syncRecord` swallowed every one of
     * its own exceptions and returned normally, so the outer catch was dead code and
     * `doWork` returned `Result.success()` having uploaded NOTHING. WorkManager was told
     * the job was done, so it never retried and the declared backoff never applied;
     * recovery waited on the 15-minute sweep no matter how brief the outage was.
     *
     * Permanent failures do NOT produce a RETRY — they are already written to the
     * record's `syncError` where the history screen shows them, and repeating them inside
     * this chain would only spend the `syncAttempts` budget on an outcome that cannot
     * change.
     *
     * That skips the in-chain retries only. The record keeps `isSynced = false`, so every
     * later sweep re-reads it — up to the cap, at ~20 MiB of disk I/O each time for the
     * case that motivated the size check. Retiring it immediately would mean writing
     * `syncAttempts` straight to the cap, which overloads a counter into a tombstone and
     * would strand a record whose failure was misclassified as permanent. The honest fix
     * is a `syncPermanentlyFailed` column, which is outside this file.
     */
    fun decide(outcomes: List<SyncOutcome>, runAttemptCount: Int): SyncDecision = when {
        outcomes.none { it == SyncOutcome.TRANSIENT_FAILURE } -> SyncDecision.SUCCESS
        runAttemptCount < MAX_RETRIES -> SyncDecision.RETRY
        else -> SyncDecision.FAILURE
    }

    /**
     * Read at most [limit] bytes, and refuse rather than allocate past it.
     *
     * `InputStream.readBytes()` grows a buffer until the stream ends, so the size of the
     * allocation was whatever the user happened to pick in the document picker. The
     * check has to happen DURING the read, not after: a length reported by the
     * ContentResolver is advisory (`UNKNOWN_LENGTH` for many providers) and asking the
     * stream how big it is means reading it.
     *
     * Overshoot is bounded by one chunk, not by the file: the chunk that crosses the
     * limit is never written to the buffer.
     */
    fun readCapped(input: InputStream, limit: Int = MAX_PAYLOAD_BYTES): ByteArray {
        // Grows geometrically but NEVER past `limit + 1`, which is the smallest read that
        // proves the payload is over the cap.
        //
        // A `ByteArrayOutputStream` was the obvious choice and the wrong one. It doubles
        // from 32 bytes, so a payload just under the 20 MiB cap ends up in a 32 MiB array;
        // during that last grow the 16 MiB and 32 MiB copies are both live, and
        // `toByteArray()` then allocates a third ~20 MiB. Roughly 2.6x the payload at
        // peak, inside the one function whose entire purpose is to stop this file
        // OOM-killing the app on a device whose heap may be 96 MB. Capping the growth
        // costs nothing and removes the over-allocation.
        //
        // Starting small rather than allocating `limit + 1` up front matters just as much
        // in the other direction: almost every real upload is a photo of a few hundred KB,
        // and those must not each reserve 20 MiB.
        var buffer = ByteArray(minOf(limit + 1, 64 * 1024))
        var total = 0
        while (true) {
            if (total == buffer.size) {
                // Already holding limit+1 bytes: there is nothing left to learn.
                if (buffer.size > limit) break
                buffer = buffer.copyOf(minOf(limit + 1, buffer.size * 2))
            }
            val read = input.read(buffer, total, buffer.size - total)
            // See readTruncated: `<= 0`, not `== -1`, so a provider stream that answers 0
            // forever ends the read instead of spinning out the worker's whole window.
            if (read <= 0) break
            total += read
        }
        if (total > limit) throw PayloadTooLargeException(limit)
        return if (total == buffer.size) buffer else buffer.copyOf(total)
    }

    /**
     * Read at most [limit] bytes as UTF-8, truncating rather than refusing.
     *
     * For server error bodies, which travel from here into `SyncHttpException`, into
     * `e.message`, into the record's `syncError` column and finally onto the history
     * screen. That path was uncapped: a captive-portal interstitial — precisely what a
     * flaky mobile connection serves up — could be megabytes of HTML read whole into the
     * heap and written verbatim into SQLite.
     *
     * Truncating is right here where refusing is right for payloads: a clipped error
     * message is still diagnostic, and there is no second chance to read the stream.
     */
    fun readTruncated(input: InputStream, limit: Int = MAX_ERROR_BODY_BYTES): String {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = input.read(buffer, total, limit - total)
            // `<= 0` rather than `== -1`. A conforming InputStream only returns 0 when
            // asked for 0 bytes, which cannot happen here — but this reads a socket the
            // app does not control, and a stream that answers 0 forever would spin until
            // WorkManager killed the worker at its 10-minute ceiling. Found the hard way:
            // a mutant of this loop hung a test run for ten minutes at 100% CPU.
            if (read <= 0) break
            total += read
        }
        return String(buffer, 0, total, Charsets.UTF_8)
    }
}

/**
 * Background worker to synchronize feedback and contributions to Cloudflare R2.
 * Replaces the manual polling loop to improve battery efficiency and reliability.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        /**
         * One sync at a time in this process, whichever work request asked for it.
         *
         * The unique-work policy in [SyncService.triggerSync] serialises immediate syncs
         * against each OTHER, and that is where the duplicate uploads were coming from.
         * It cannot serialise them against the 15-minute sweep: the sweep is enqueued
         * under a different unique name, so WorkManager is free to run both at once, and
         * merging the two names is not an option — a `PeriodicWorkRequest` cannot be
         * chained, so `enqueueUniqueWork(..., APPEND_OR_REPLACE)` on the sweep's name
         * would be rejected outright.
         *
         * So the same guard the other two clients already have. Web uses an `isSyncing`
         * flag; iOS the same, inside an `actor` so the check-then-set cannot interleave.
         * A [Mutex] is this platform's version of it, and it is stricter than either:
         * both of those DROP a colliding request, whereas this one queues it, so the
         * loser re-reads `getUnsyncedRecords()` after the winner has committed
         * `isSynced` and picks up whatever is genuinely still pending. The query is
         * inside the lock for exactly that reason — moving it out restores the
         * double-read this exists to prevent.
         *
         * `withLock` suspends rather than blocking, so a waiting worker parks its
         * coroutine and hands the thread back instead of holding one idle.
         */
        private val syncMutex = Mutex()
    }

    private val dao = HistoryDatabase.getDatabase(appContext).historyDao()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (API_KEY.isBlank()) {
                MonLogger.i("No sync key configured; skipping upload. Set SYNC_API_KEY in local.properties to enable it.")
                return@withContext Result.success()
            }

            syncMutex.withLock { syncPendingRecords() }
        } catch (e: CancellationException) {
            // WorkManager stopped us — a constraint was lost, the 10-minute execution
            // window expired, or the work was cancelled. Cancellation has to propagate:
            // reporting it as a sync failure below would also swallow it for the
            // coroutine that owns this worker.
            //
            // This clause exists because of `syncMutex`. Waiting for the lock is a
            // suspension point, and therefore a cancellation point, that the previous
            // straight-line version of this method did not have — so the pre-existing
            // `catch (e: Exception)` was harmless there and is not here.
            throw e
        } catch (e: Exception) {
            // Reached only by something outside a single record's upload — the DAO query
            // itself, most likely. Per-record errors are classified in syncRecord now.
            //
            // This bound was a bare `runAttemptCount < 3`, an independent budget that
            // happened to allow four runs where the record path allowed three. Sharing
            // MAX_RETRIES is the point: two retry budgets that drift apart are how the
            // unreachable-retry defect went unnoticed in the first place.
            MonLogger.e("Background sync failed", e)
            if (runAttemptCount < SyncPolicy.MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private suspend fun syncPendingRecords(): Result {
        val unsynced = dao.getUnsyncedRecords()
        if (unsynced.isEmpty()) {
            return Result.success()
        }

        MonLogger.i("Starting background sync for ${unsynced.size} records...")

        // Collected rather than short-circuited: one record with a permanently bad
        // file URI must not stop the rest of the queue from uploading.
        val outcomes = unsynced.map { syncRecord(it) }

        return when (SyncPolicy.decide(outcomes, runAttemptCount)) {
            SyncDecision.SUCCESS -> Result.success()
            SyncDecision.RETRY -> {
                MonLogger.w("Sync had transient failures; retrying (attempt $runAttemptCount).")
                Result.retry()
            }
            SyncDecision.FAILURE -> {
                MonLogger.e("Sync still failing after ${SyncPolicy.MAX_RETRIES} retries; leaving it to the periodic sweep.")
                Result.failure()
            }
        }
    }

    private suspend fun syncRecord(record: HistoryRecord): SyncOutcome {
        try {
            // First payload: File
            if (record.fileUri != null) {
                val uri = android.net.Uri.parse(record.fileUri)
                val bytes = applicationContext.contentResolver.openInputStream(uri)?.use {
                    SyncPolicy.readCapped(it)
                }
                if (bytes != null) {
                    uploadToFeedbackService(record.fileName, record.fileType, record.syncId, record.category, bytes, SyncPayload.FILE)
                } else {
                    // File URI is no longer accessible (deleted / permission revoked).
                    // Log and continue — we still want to upload the text transcription.
                    MonLogger.w("File URI inaccessible for record ${record.syncId}, skipping file payload.")
                }
            }

            // Dual payload: Text transcription
            val isJustTextBlob = record.fileName == "Text Contribution"
            if (!isJustTextBlob && record.text.isNotBlank() && record.text != "(Image only)") {
                val nameWithoutExt = record.fileName.substringBeforeLast(".")
                val textFileName = "$nameWithoutExt-transcription.txt"
                uploadToFeedbackService(textFileName, "text/plain", record.syncId, record.category, record.text.toByteArray(Charsets.UTF_8), SyncPayload.TRANSCRIPTION)
            }

            dao.update(record.copy(isSynced = true, syncError = null, syncAttempts = record.syncAttempts + 1))
            return SyncOutcome.SUCCESS
        } catch (e: Exception) {
            // Exactly one increment per record per run, on either path. The `syncAttempts
            // < 5` cap in HistoryDao is what finally retires a record, so counting an
            // attempt twice here would quietly cut the record's real budget in half.
            dao.update(record.copy(syncError = e.message ?: "Unknown Error", syncAttempts = record.syncAttempts + 1))
            return SyncPolicy.classify(e)
        }
    }

    private fun uploadToFeedbackService(
        fileName: String,
        fileType: String,
        recordId: String,
        category: String,
        data: ByteArray,
        payload: SyncPayload
    ) {
        // Second gate, for the transcription payload: those bytes come out of the
        // database rather than through readCapped, so this is the only thing standing
        // between a pathological OCR result and a rejected request.
        if (data.size > SyncPolicy.MAX_PAYLOAD_BYTES) {
            throw PayloadTooLargeException(SyncPolicy.MAX_PAYLOAD_BYTES)
        }

        val endpoint = when (category.lowercase()) {
            "contribution", "contribute" -> "$BASE_URL/contribution"
            else -> "$BASE_URL/feedback"
        }

        val boundary = "Boundary-" + System.currentTimeMillis()
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 15_000  // 15s — fail fast on unreachable server
            connection.readTimeout    = 30_000  // 30s — allow time for upload to complete
            connection.requestMethod = "POST"
            connection.doOutput = true

            // Without this, HttpURLConnection buffers the ENTIRE request body in memory to
            // work out `Content-Length` — a third live copy of a payload that may be
            // 20 MiB, after readCapped's and the multipart envelope's. Fixed-length
            // streaming writes straight through to the socket instead. The length is
            // computed rather than measured, so it has to be kept in step with the writes
            // below; `multipartBodyLength` is the single place that arithmetic lives.
            connection.setFixedLengthStreamingMode(
                MultipartBody.length(boundary, recordId, fileName, fileType, data.size)
            )
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("X-API-Key", API_KEY)
            connection.setRequestProperty("X-Request-ID", SyncPolicy.requestId(recordId, payload))

            BufferedOutputStream(connection.outputStream).use { os ->
                MultipartBody.write(os, boundary, recordId, fileName, fileType, data)
                os.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorMsg = connection.errorStream?.use { SyncPolicy.readTruncated(it) } ?: ""
                throw SyncHttpException(responseCode, errorMsg)
            }
        } finally {
            connection.disconnect()
        }
    }

}

/**
 * The `multipart/form-data` body this client sends, as bytes and as a byte count.
 *
 * Lifted out of [SyncWorker] for one concrete reason: `setFixedLengthStreamingMode`
 * needs the body's exact length BEFORE the body is written, and a length that disagrees
 * with the bytes by even one makes `HttpURLConnection` throw mid-upload. So [length] and
 * [write] are built from the same two string builders — [envelopeBefore] and
 * [envelopeAfter] — which makes drift between them structurally impossible rather than
 * merely unlikely. `MultipartBodyTests` on the iOS side is the same extraction.
 */
internal object MultipartBody {

    /**
     * Strip what would let a value break out of the header it sits in.
     *
     * The filename is user-controlled and was interpolated straight into
     * `Content-Disposition: ...; filename="$fileName"`. A document named
     * `x".txt"\r\nContent-Type: text/html\r\n\r\n...` injected arbitrary
     * multipart headers, or an entire extra part, into the request this app makes
     * with its own API key. iOS carried the same hole.
     *
     * CR and LF go because they end a header; the double quote goes because it
     * ends the quoted string. Everything else survives, so Mon titles are kept
     * intact rather than reduced to underscores.
     */
    fun headerSafe(value: String): String =
        value.replace("\r", "").replace("\n", "").replace("\"", "'")

    /**
     * Everything up to the first byte of the file's content.
     *
     * [recordId] is the BARE record id, deliberately: the service derives the R2 object
     * key from this field, so the per-payload `X-Request-ID` suffix must not appear here.
     */
    private fun envelopeBefore(
        boundary: String,
        recordId: String,
        fileName: String,
        contentType: String
    ): String = buildString {
        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"record_id\"\r\n\r\n")
        append(recordId).append("\r\n")

        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"original_name\"\r\n\r\n")
        append(fileName).append("\r\n")

        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"")
            .append(headerSafe(fileName)).append("\"\r\n")
        append("Content-Type: ").append(headerSafe(contentType)).append("\r\n\r\n")
    }

    /** Everything after the last byte of the file's content. */
    private fun envelopeAfter(boundary: String): String = "\r\n--$boundary--\r\n"

    /**
     * UTF-8, not Latin-1.
     *
     * Every write here used `DataOutputStream.writeBytes`, which by specification
     * writes only the LOW BYTE of each char. Mon is U+1000-U+109F, so every
     * Mon-titled document arrived at the service with its name mangled to
     * unrelated Latin-1 bytes: `original_name` and the `filename` parameter both.
     * That is the common case for this app, not an edge one, and it is the only
     * client that did it. iOS encodes UTF-8 and web passes through
     * `encodeURIComponent`.
     *
     * It is also why [length] cannot count characters: a Mon filename is three bytes
     * per char, and a `Content-Length` short by twice the title's length would abort
     * every upload from this app.
     */
    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** Exact number of bytes [write] will produce for the same arguments. */
    fun length(
        boundary: String,
        recordId: String,
        fileName: String,
        contentType: String,
        dataSize: Int
    ): Int =
        bytes(envelopeBefore(boundary, recordId, fileName, contentType)).size +
            dataSize +
            bytes(envelopeAfter(boundary)).size

    fun write(
        os: OutputStream,
        boundary: String,
        recordId: String,
        fileName: String,
        contentType: String,
        data: ByteArray
    ) {
        os.write(bytes(envelopeBefore(boundary, recordId, fileName, contentType)))
        os.write(data)
        os.write(bytes(envelopeAfter(boundary)))
    }
}
