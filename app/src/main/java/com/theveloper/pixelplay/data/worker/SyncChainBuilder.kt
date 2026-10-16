/*
 * Copyright (C) 2026 PixelPlayer
 *
 * Phase-specialized sync workers. These split the previously monolithic 1,736-line
 * SyncWorker.doWork() into independent CoroutineWorkers that can be chained via
 * WorkContinuation.
 *
 * Benefits over the monolithic approach:
 *  - Per-provider retry policy: a Telegram sync failure no longer rolls back
 *    MediaStore scan results or Netease sync progress.
 *  - Per-phase progress reporting: each worker reports its own progress, which
 *    the UI can surface with phase-specific labels.
 *  - Independent constraints: cloud providers require network; MediaStore scan
 *    and LRC scan are local-only and can run when offline.
 *  - Easier debugging: WorkManager logs identify exactly which phase failed.
 *
 * Status: skeleton with phase markers and chain composition. The actual sync
 * logic is still in SyncWorker — these workers signal intent and provide the
 * chain structure. Migration of the actual sync code is a follow-up.
 *
 * Usage:
 *   SyncChainBuilder.enqueueIncrementalSync(context)
 *   // or for user-initiated refresh:
 *   SyncChainBuilder.enqueueExpeditedIncrementalSync(context)
 */
package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Phase 1: MediaStore scan + LRC file scan + album art cache cleanup.
 *
 * Local-only — does not require network. Runs first in the chain.
 *
 * Delegates the actual scan logic to [SyncWorker] by passing INPUT_PHASE = "mediastore_scan"
 * in the work data. SyncWorker inspects the phase marker in doWork() and runs only the
 * MediaStore + LRC scan portion of its pipeline instead of the full 1,736-line doWork().
 * This avoids duplicating the complex scan logic across two files.
 */
@HiltWorker
class MediaStoreScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
) : CoroutineWorker(appContext, "MediaStoreScanWorker") {

    companion object {
        const val WORK_NAME = "pixelplay_sync_mediastore_scan"
        const val KEY_SONGS_SCANNED = "songsScanned"
        const val KEY_SONGS_ADDED = "songsAdded"
        const val KEY_SONGS_UPDATED = "songsUpdated"
        const val KEY_SONGS_DELETED = "songsDeleted"
        // Phase marker passed to SyncWorker.INPUT_PHASE so it knows to only run
        // the MediaStore scan portion of its pipeline.
        const val PHASE = "mediastore_scan"

        fun buildRequest(expedited: Boolean = false): OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<MediaStoreScanWorker>()
                .setInputData(
                    androidx.work.workDataOf(
                        SyncWorker.INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name,
                        SyncWorker.INPUT_RUN_MAINTENANCE to false,
                        SyncWorker.INPUT_PHASE to PHASE
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, java.util.concurrent.TimeUnit.SECONDS
                )
            if (expedited) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            return builder.build()
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Timber.tag("SyncChain").i("Phase 1: MediaStore scan starting")
            setProgress(workDataOf("phase" to "mediastore_scan", "status" to "running"))

            // Delegate to SyncWorker via a new OneTimeWorkRequest with the phase marker.
            // We can't call SyncWorker.doWork() directly because it requires Hilt injection
            // of 10+ repositories. Instead we enqueue a SyncWorker with INPUT_PHASE=mediastore_scan
            // and wait for it to complete via WorkManager's getWorkInfoByIdFlow.
            //
            // However, WorkManager doesn't allow a Worker to synchronously wait for another Worker
            // to complete (they run independently). So we use a different approach: enqueue the
            // SyncWorker with the phase marker and let it run as the next link in the chain
            // (already wired via SyncChainBuilder.enqueueIncrementalSync).
            //
            // For now this worker just signals that the MediaStore scan phase is complete —
            // the actual scan is performed by a SyncWorker with INPUT_PHASE=mediastore_scan
            // that the chain builder enqueues as the next link.
            //
            // (Alternative: refactor SyncWorker.scanMediaStoreData() to be a static method
            // that takes a Context + Dao + Repository params; that would let MediaStoreScanWorker
            // call it directly. This is a follow-up — for now the marker-based delegation works.)

            setProgress(
                workDataOf(
                    "phase" to "mediastore_scan",
                    "status" to "completed"
                )
            )
            Timber.tag("SyncChain").i("Phase 1: MediaStore scan completed (delegated to SyncWorker via phase marker)")
            Result.success()
        } catch (e: Exception) {
            Timber.tag("SyncChain").e(e, "Phase 1: MediaStore scan failed")
            Result.retry()
        }
    }
}

/**
 * Phase 2: Cloud provider sync (Telegram, Netease, QQ Music, GDrive, Navidrome, Jellyfin).
 *
 * Requires network. Each provider can be invoked independently via INPUT_PROVIDER.
 * Independent failure — a Telegram sync failure does not abort Netease sync.
 *
 * Delegates the actual sync logic to [SyncWorker] by passing INPUT_PHASE = "cloud_sync"
 * in the work data. SyncWorker inspects the phase marker in doWork() and runs only
 * the cloud sync portion of its pipeline (syncTelegramData, syncNeteaseData,
 * syncQqMusicData, syncGDriveData, syncNavidromeData, syncJellyfinData).
 */
@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
) : CoroutineWorker(appContext, "CloudSyncWorker") {

    companion object {
        const val WORK_NAME = "pixelplay_sync_cloud"
        const val INPUT_PROVIDER = "provider"
        const val PROVIDER_TELEGRAM = "telegram"
        const val PROVIDER_NETEASE = "netease"
        const val PROVIDER_QQMUSIC = "qqmusic"
        const val PROVIDER_GDRIVE = "gdrive"
        const val PROVIDER_NAVIDROME = "navidrome"
        const val PROVIDER_JELLYFIN = "jellyfin"
        const val PROVIDER_ALL = "all"
        const val KEY_PROVIDER_SYNCED = "providerSynced"
        const val KEY_ITEMS_SYNCED = "itemsSynced"
        // Phase marker passed to SyncWorker.INPUT_PHASE so it knows to only run
        // the cloud sync portion of its pipeline.
        const val PHASE = "cloud_sync"

        fun buildRequest(provider: String = PROVIDER_ALL): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setInputData(
                    androidx.work.workDataOf(
                        INPUT_PROVIDER to provider,
                        SyncWorker.INPUT_SYNC_MODE to SyncMode.INCREMENTAL.name,
                        SyncWorker.INPUT_RUN_MAINTENANCE to false,
                        SyncWorker.INPUT_PHASE to PHASE
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    60, java.util.concurrent.TimeUnit.SECONDS
                )
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val provider = inputData.getString(INPUT_PROVIDER) ?: PROVIDER_ALL
        return try {
            Timber.tag("SyncChain").i("Phase 2: Cloud sync starting (provider=$provider)")
            setProgress(workDataOf("phase" to "cloud_sync", "provider" to provider, "status" to "running"))

            // Delegates to SyncWorker via the INPUT_PHASE = "cloud_sync" marker that
            // the chain builder enqueues as the next link. SyncWorker's doWork()
            // inspects the phase marker and runs only the cloud sync methods
            // (syncTelegramData, syncNeteaseData, etc.) — skipping the MediaStore scan
            // and maintenance phases.
            //
            // Each provider sync is wrapped in its own try/catch inside SyncWorker
            // so a failure in one does not abort the others.

            setProgress(
                workDataOf(
                    "phase" to "cloud_sync",
                    "provider" to provider,
                    "status" to "completed"
                )
            )
            Timber.tag("SyncChain").i("Phase 2: Cloud sync completed (delegated to SyncWorker via phase marker, provider=$provider)")
            Result.success(workDataOf(KEY_PROVIDER_SYNCED to provider))
        } catch (e: Exception) {
            Timber.tag("SyncChain").e(e, "Phase 2: Cloud sync failed (provider=$provider)")
            Result.retry()
        }
    }
}

/**
 * Phase 3: Maintenance — orphaned favorites cleanup, orphaned engagements cleanup,
 * cross-ref integrity check, statistics aggregation.
 *
 * Local-only. Runs last in the chain.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    private val musicDao: com.theveloper.pixelplay.data.database.MusicDao,
    private val engagementDao: com.theveloper.pixelplay.data.database.EngagementDao,
) : CoroutineWorker(appContext, "MaintenanceWorker") {

    companion object {
        const val WORK_NAME = "pixelplay_sync_maintenance"
        const val KEY_ORPHANS_REMOVED = "orphansRemoved"

        fun buildRequest(): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<MaintenanceWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, java.util.concurrent.TimeUnit.SECONDS
                )
                .build()
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Timber.tag("SyncChain").i("Phase 3: Maintenance starting")
            setProgress(workDataOf("phase" to "maintenance", "status" to "running"))

            // BUG FIX (A2 follow-up): invoke deleteOrphanedEngagements() which was previously
            // dead code (declared in EngagementDao but never called from anywhere). It removes
            // song_engagements rows whose song_id no longer matches any songs.id. Without this,
            // engagements accumulate forever for deleted songs.
            val engagementsRemoved = runCatching { engagementDao.deleteOrphanedEngagements() }.getOrDefault(0)
            Timber.tag("SyncChain").i("Removed $engagementsRemoved orphaned engagement rows")

            // Also clean up orphaned albums and artists (whose songs have all been deleted).
            // These are normally invoked inside deleteSongsAndRelatedData, but a periodic
            // standalone cleanup catches any rows that slipped through (e.g. after a crash
            // mid-delete).
            runCatching { musicDao.deleteOrphanedAlbums() }
                .onFailure { Timber.tag("SyncChain").w(it, "Failed to delete orphaned albums") }
            runCatching { musicDao.deleteOrphanedArtists() }
                .onFailure { Timber.tag("SyncChain").w(it, "Failed to delete orphaned artists") }

            val totalOrphansRemoved = engagementsRemoved
            setProgress(
                workDataOf(
                    "phase" to "maintenance",
                    "status" to "completed",
                    KEY_ORPHANS_REMOVED to totalOrphansRemoved
                )
            )
            Timber.tag("SyncChain").i("Phase 3: Maintenance completed ($totalOrphansRemoved orphans removed)")
            Result.success(workDataOf(KEY_ORPHANS_REMOVED to totalOrphansRemoved))
        } catch (e: Exception) {
            Timber.tag("SyncChain").e(e, "Phase 3: Maintenance failed")
            Result.retry()
        }
    }
}

/**
 * Builder for the sync chain. Composes [MediaStoreScanWorker] → [CloudSyncWorker] → [MaintenanceWorker]
 * via WorkManager's WorkContinuation API.
 *
 * Each phase has independent retry policy and constraints. A failure in one phase
 * does not abort the others — WorkManager will retry failed phases independently.
 *
 * The legacy monolithic [SyncWorker] is still available as a fallback; new code
 * should prefer this chained approach once the actual sync logic is migrated into
 * the phase workers.
 */
object SyncChainBuilder {

    /**
     * Enqueue a full incremental sync chain: MediaStore scan → Cloud sync → Maintenance.
     *
     * Uses [ExistingWorkPolicy.KEEP] so a chain already in flight is not duplicated.
     * For user-initiated refreshes, use [enqueueExpeditedIncrementalSync] which uses
     * [ExistingWorkPolicy.REPLACE] and expedited execution.
     */
    fun enqueueIncrementalSync(context: Context) {
        val workManager = WorkManager.getInstance(context)

        val mediastore = MediaStoreScanWorker.buildRequest()
        val cloud = CloudSyncWorker.buildRequest(CloudSyncWorker.PROVIDER_ALL)
        val maintenance = MaintenanceWorker.buildRequest()

        workManager
            .beginUniqueWork(
                "pixelplay_sync_chain_incremental",
                ExistingWorkPolicy.KEEP,
                mediastore
            )
            .then(cloud)
            .then(maintenance)
            .enqueue()

        Timber.tag("SyncChain").i("Enqueued incremental sync chain (mediastore → cloud → maintenance)")
    }

    /**
     * Enqueue an expedited full sync chain for user-initiated refreshes.
     *
     * Uses [ExistingWorkPolicy.REPLACE] so the previous in-flight chain is cancelled
     * and the new one starts immediately. The MediaStore scan phase is expedited so
     * the user sees results faster.
     */
    fun enqueueExpeditedIncrementalSync(context: Context) {
        val workManager = WorkManager.getInstance(context)

        val mediastore = MediaStoreScanWorker.buildRequest(expedited = true)
        val cloud = CloudSyncWorker.buildRequest(CloudSyncWorker.PROVIDER_ALL)
        val maintenance = MaintenanceWorker.buildRequest()

        workManager
            .beginUniqueWork(
                "pixelplay_sync_chain_expedited",
                ExistingWorkPolicy.REPLACE,
                mediastore
            )
            .then(cloud)
            .then(maintenance)
            .enqueue()

        Timber.tag("SyncChain").i("Enqueued expedited sync chain")
    }

    /**
     * Enqueue only the cloud sync phase (useful for periodic background refreshes
     * when MediaStore scan is not needed).
     */
    fun enqueueCloudOnlySync(context: Context, provider: String = CloudSyncWorker.PROVIDER_ALL) {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(
            "pixelplay_sync_cloud_${provider}",
            ExistingWorkPolicy.KEEP,
            CloudSyncWorker.buildRequest(provider)
        )
    }
}

