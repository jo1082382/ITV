package com.example.itv.infrastructure.storage

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.itv.composition.queryNightReport
import com.example.itv.composition.queryUsageReport
import com.example.itv.infrastructure.coaching.CoachingConsentStore
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Lightweight once-daily background worker that snapshots daily phone usage, 24-hour hourly
 * distributions, top apps, shortform stats, and sleep records into the 6-month on-device archive
 * ([MonthlyUsageArchiveStore]).
 *
 * Works for BOTH guest and social-logged-in users without requiring network access or opening
 * the app / individual calendar dates, ensuring full 6-month history survives Android OS pruning.
 */
class DailyArchiveSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!CoachingConsentStore.granted(ctx)) return Result.success()
        if (!File(ctx.noBackupFilesDir, "login_completed_v1").exists()) return Result.success()

        runCatching {
            val synced = withTimeoutOrNull(10.seconds) {
                val today = LocalDate.now()
                val todayRep = runCatching { ctx.queryUsageReport(1, today) }.getOrNull()
                val nightRep = runCatching { queryNightReport(ctx, 7) }.getOrNull()
                MonthlyUsageArchiveStore.syncDailySnapshot(
                    context = ctx,
                    todayUsageReport = todayRep,
                    periodUsageReport = null,
                    nightReport = nightRep
                )
                true
            } ?: false
            if (!synced) {
                MonthlyUsageArchiveStore.backfillRecentHourlyIfNeeded(
                    context = ctx,
                    minIntervalMs = 0L
                )
            }
        }.onFailure { err ->
            Log.w(TAG, "Daily archive sync skipped: ${err.message}")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "ITV_ARCHIVE_WORKER"
        private const val UNIQUE_WORK_NAME = "itv_daily_hourly_archive_v1"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyArchiveSyncWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}
