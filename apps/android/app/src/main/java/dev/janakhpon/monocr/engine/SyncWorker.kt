package dev.janakhpon.monocr.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.janakhpon.monocr.data.HistoryDatabase
import dev.janakhpon.monocr.data.HistoryRecord
import dev.janakhpon.monocr.util.MonLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val BASE_URL = "https://ocr-feedback-service-857115062313.asia-southeast1.run.app/v1"
private const val API_KEY = "a47102547a8db8aa2fa454441b04bbb3780fcc5f66f976159994315f003d209a"

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

            DataOutputStream(connection.outputStream).use { os ->
                writeFormField(os, boundary, "record_id", recordId)
                writeFormField(os, boundary, "original_name", fileName)
                writeFileField(os, boundary, "file", fileName, fileType, data)
                os.writeBytes("--$boundary--\r\n")
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

    private fun writeFormField(os: DataOutputStream, boundary: String, name: String, value: String) {
        os.writeBytes("--$boundary\r\n")
        os.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        os.writeBytes(value)
        os.writeBytes("\r\n")
    }

    private fun writeFileField(os: DataOutputStream, boundary: String, name: String, fileName: String, contentType: String, data: ByteArray) {
        os.writeBytes("--$boundary\r\n")
        os.writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n")
        os.writeBytes("Content-Type: $contentType\r\n\r\n")
        os.write(data)
        os.writeBytes("\r\n")
    }
}
