package dev.janakhpon.monocr.engine

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Service to manage background synchronization with Cloudflare R2.
 * Now delegates the heavy lifting to WorkManager for better battery efficiency.
 */
class SyncService(private val context: Context) {
    companion object {
        @Volatile
        private var instance: SyncService? = null
        fun getInstance(context: Context): SyncService {
            return instance ?: synchronized(this) {
                instance ?: SyncService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val workManager = WorkManager.getInstance(context)

    /**
     * Start periodic background synchronization.
     * Replaces the manual polling loop.
     */
    fun start() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag("MonOcrSync")
            .build()

        // Enqueue unique work to avoid multiple workers running simultaneously
        workManager.enqueueUniquePeriodicWork(
            "MonOcrSyncWork",
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
            syncRequest
        )
    }

    /**
     * Trigger an immediate sync (e.g., after user submits feedback).
     */
    fun triggerSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val immediateSyncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .addTag("MonOcrSyncImmediate")
            .build()

        workManager.enqueue(immediateSyncRequest)
    }
}
