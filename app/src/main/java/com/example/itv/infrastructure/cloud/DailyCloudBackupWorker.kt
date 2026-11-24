package com.example.itv.infrastructure.cloud

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/**
 * Periodic once-daily background backup worker for social-authenticated accounts (Google, Kakao, Naver).
 * Guest (anonymous) sessions stay strictly on-device and are skipped automatically.
 */
class DailyCloudBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val fbUser = FirebaseAuth.getInstance().currentUser
        if (fbUser == null || fbUser.isAnonymous) {
            return Result.success()
        }
        val result = CloudSyncRepository.backupAllToCloud(applicationContext)
        return if (result.isSuccess) {
            Log.i(TAG, "Daily background cloud backup succeeded.")
            Result.success()
        } else {
            Log.w(TAG, "Daily background cloud backup failed; will retry.", result.exceptionOrNull())
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ITV_CLOUD_WORKER"
        private const val UNIQUE_WORK_NAME = "itv_daily_cloud_backup_v1"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<DailyCloudBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
