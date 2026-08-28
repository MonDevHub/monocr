package dev.janakhpon.monocr.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.janakhpon.monocr.BuildConfig
import dev.janakhpon.monocr.data.HistoryDatabase
import dev.janakhpon.monocr.data.HistoryRecord
import dev.janakhpon.monocr.util.MonLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
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
 * Background worker to synchronize feedback and contributions to Cloudflare R2.
 * Replaces the manual polling loop to improve battery efficiency and reliability.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val dao = HistoryDatabase.getDatabase(appContext).historyDao()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (API_KEY.isBlank()) {
                MonLogger.i("No sync key configured; skipping upload. Set SYNC_API_KEY in local.properties to enable it.")
                return@withContext Result.success()
            }

            val unsynced = dao.getUnsyncedRecords()
            if (unsynced.isEmpty()) {
                return@withContext Result.success()
            }

            MonLogger.i("Starting background sync for ${unsynced.size} records...")

            for (record in unsynced) {
                syncRecord(record)
            }

            
            Result.success()
        } catch (e: Exception) {
            MonLogger.e("Background sync failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun syncRecord(record: HistoryRecord) {
        try {
            // First payload: File
            if (record.fileUri != null) {
                val uri = android.net.Uri.parse(record.fileUri)
                val bytes = applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    uploadToFeedbackService(record.fileName, record.fileType, record.syncId, record.category, bytes)
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
                uploadToFeedbackService(textFileName, "text/plain", record.syncId, record.category, record.text.toByteArray(Charsets.UTF_8))
            }

            dao.update(record.copy(isSynced = true, syncError = null, syncAttempts = record.syncAttempts + 1))
        } catch (e: Exception) {
            dao.update(record.copy(syncError = e.message ?: "Unknown Error", syncAttempts = record.syncAttempts + 1))
        }
    }

    private fun uploadToFeedbackService(fileName: String, fileType: String, recordId: String, category: String, data: ByteArray) {
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
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("X-API-Key", API_KEY)
            connection.setRequestProperty("X-Request-ID", recordId)

            BufferedOutputStream(connection.outputStream).use { os ->
                writeFormField(os, boundary, "record_id", recordId)
                writeFormField(os, boundary, "original_name", fileName)
                writeFileField(os, boundary, "file", fileName, fileType, data)
                writeUtf8(os, "--$boundary--\r\n")
                os.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw Exception("HTTP $responseCode: $errorMsg")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Write [text] as UTF-8.
     *
     * Every write here used `DataOutputStream.writeBytes`, which by specification
     * writes only the LOW BYTE of each char. Mon is U+1000-U+109F, so every
     * Mon-titled document arrived at the service with its name mangled to
     * unrelated Latin-1 bytes: `original_name` and the `filename` parameter both.
     * That is the common case for this app, not an edge one, and it is the only
     * client that did it. iOS encodes UTF-8 and web passes through
     * `encodeURIComponent`.
     */
    private fun writeUtf8(os: OutputStream, text: String) {
        os.write(text.toByteArray(Charsets.UTF_8))
    }

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
    private fun headerSafe(value: String): String =
        value.replace("\r", "").replace("\n", "").replace("\"", "'")

    private fun writeFormField(os: OutputStream, boundary: String, name: String, value: String) {
        writeUtf8(os, "--$boundary\r\n")
        writeUtf8(os, "Content-Disposition: form-data; name=\"${headerSafe(name)}\"\r\n\r\n")
        writeUtf8(os, value)
        writeUtf8(os, "\r\n")
    }

    private fun writeFileField(os: OutputStream, boundary: String, name: String, fileName: String, contentType: String, data: ByteArray) {
        writeUtf8(os, "--$boundary\r\n")
        writeUtf8(os, "Content-Disposition: form-data; name=\"${headerSafe(name)}\"; filename=\"${headerSafe(fileName)}\"\r\n")
        writeUtf8(os, "Content-Type: ${headerSafe(contentType)}\r\n\r\n")
        os.write(data)
        writeUtf8(os, "\r\n")
    }
}
