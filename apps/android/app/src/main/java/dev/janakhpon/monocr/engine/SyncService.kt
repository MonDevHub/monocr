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

        /** Unique name for the 15-minute sweep. */
        const val PERIODIC_WORK_NAME = "MonOcrSyncWork"

        /**
         * Unique name for the post-submission sync.
         *
         * Necessarily DIFFERENT from [PERIODIC_WORK_NAME] — WorkManager will not let a
         * `PeriodicWorkRequest` be chained, so `enqueueUniqueWork(..., APPEND_OR_REPLACE)`
         * under the sweep's name is not available even though one shared name would be
         * the tidiest way to serialise the two.
         *
         * The consequence is that a unique-work policy alone does NOT close the
         * duplicate-upload race: it stops two immediate syncs colliding, but the sweep
         * lives under this other name and WorkManager may run it concurrently. The guard
         * that covers that case is `SyncWorker.syncMutex` — see the reasoning there.
         */
        const val IMMEDIATE_WORK_NAME = "MonOcrSyncImmediateWork"

        /**
         * First retry waits this long, then doubles.
         *
         * There was no backoff configured at all, which did not matter while
         * [SyncWorker] could never return `Result.retry()` — see the retry-reachability
         * comment there. Now that it can, the interval is the thing standing between a
         * flaky connection and a tight upload loop on the user's data plan.
         */
        const val BACKOFF_SECONDS = 30L
    }

    private val workManager = WorkManager.getInstance(context)

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Start periodic background synchronization.
     * Replaces the manual polling loop.
     */
    fun start() {
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag("MonOcrSync")
            .build()

        // Enqueue unique work to avoid multiple workers running simultaneously
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
            syncRequest
        )
    }

    /**
     * Trigger an immediate sync (e.g., after user submits feedback).
     *
     * This used a plain `enqueue()`. The comment about avoiding simultaneous workers
     * sat beside the PERIODIC request above and covered only that one, so nothing
     * here was unique at all: two submissions in quick succession — a feedback from
     * [dev.janakhpon.monocr.ui.FeedbackViewModel] and a contribution from
     * [dev.janakhpon.monocr.ui.ContributeViewModel], or one impatient double-tap —
     * enqueued two independent [SyncWorker]s. Both called `getUnsyncedRecords()`
     * before either wrote `isSynced`, so both uploaded EVERY pending record.
     *
     * Be precise about the cost, because the obvious guess is wrong and will send the
     * next person looking in the wrong place. The bucket does NOT fill with duplicate
     * objects: `buildObjectKey` in `services/feedback/internal/upload/handler.go` is
     * deterministic in the record id and filename, and the upload is a plain `PutObject`,
     * so the second write overwrites the first at the same key. What it actually costs is
     * doubled mobile upload bandwidth on a metered connection, double pressure on the
     * service's rate limiter, and — the one that loses data — `syncAttempts` charged twice
     * per record against the `< 5` cap in `HistoryDao`, which retires records at twice the
     * intended rate. Web guards this with an `isSyncing` flag and iOS with the same;
     * Android was the only client with nothing.
     *
     * `APPEND_OR_REPLACE` rather than `KEEP`, and the difference is only visible while
     * a worker is already RUNNING:
     *
     *  - `KEEP` would drop the new request on the floor. The worker already running
     *    read its record list before this submission existed, so the record the user
     *    just submitted would not upload until the 15-minute sweep. Silently trading
     *    a duplicate-upload bug for a lost-promptness bug is not a fix — `triggerSync`
     *    exists precisely to make a submission land now.
     *  - `APPEND_OR_REPLACE` runs the new request AFTER the current one finishes.
     *    Serial, so the double-read that caused the duplicates cannot happen, and the
     *    fresh pass still sees the new record.
     *  - Plain `APPEND` was rejected: it inherits a CANCELLED or FAILED terminal state
     *    from the existing chain at ENQUEUE time, and [SyncWorker] does return
     *    `Result.failure()` once it exhausts its run attempts. That would wedge this work
     *    name permanently. `APPEND_OR_REPLACE` starts a new chain in exactly that case.
     *  - `REPLACE` would cancel a worker mid-upload. Never.
     *
     * Known residual, recorded rather than glossed. `APPEND_OR_REPLACE` only replaces a
     * chain whose leaf is ALREADY terminal when the new work is enqueued. If the running
     * worker fails AFTERWARDS, WorkManager's `iterativelyFailWorkAndDependents` marks the
     * appended request FAILED too, so that submission never executes. It is bounded rather
     * than lost: the record stays `isSynced = false` and the 15-minute sweep takes it, and
     * in practice the running worker's own retries re-query the table after the submission
     * landed, so they usually upload it before failing. Closing it properly means not
     * reporting failure at all, which would resurrect the "success with nothing uploaded"
     * defect this pass exists to fix — so the trade was made deliberately in favour of an
     * honest result.
     */
    fun triggerSync() {
        val immediateSyncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag("MonOcrSyncImmediate")
            .build()

        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            immediateSyncRequest
        )
    }
}
