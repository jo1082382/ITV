package com.example.itv.infrastructure.storage

import android.content.Context
import android.util.AtomicFile
import com.example.itv.data.local.ItvDatabase
import com.example.itv.infrastructure.cloud.CloudSyncRepository
import com.example.itv.infrastructure.cloud.DailyCloudBackupWorker
import com.example.itv.infrastructure.coaching.UserCoachingSettingsStore
import com.example.itv.infrastructure.equip.UserEquipStore
import com.example.itv.infrastructure.reward.CalendarRewardStore
import com.example.itv.infrastructure.reward.UserRewardStore
import com.example.itv.infrastructure.sleep.BedtimeMemoStore
import com.example.itv.infrastructure.storage.shortform.ReelsUsageStore
import com.example.itv.infrastructure.storage.shortform.ShortformDailyStore
import com.example.itv.infrastructure.theme.UserThemeStore
import com.example.itv.presentation.coaching.CoachingAutoStart
import com.example.itv.presentation.usage.DailyStampPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Authoritative on-device data wiper invoked on account deletion and sign-out.
 * - [wipeAllLocalUserData]: full permanent wipe (DB, archives, sessions, consent, onboarding, auth prefs)
 *   + sets [AccountDataBoundaryStore] fresh-start cutoff so re-registration never pulls old OS UsageEvents.
 * - [wipeForSignOut]: wipes all user-specific measurement/progress/settings data on the device so a
 *   subsequent login never mixes accounts, while preserving device-level permission intro & last-provider hint,
 *   and sets [AccountDataBoundaryStore] fresh-start cutoff until cloud restore populates the next account.
 */
internal object LocalDataWiper {

    internal val SIGN_OUT_FILES_TO_DELETE = listOf(
        MonthlyUsageArchiveStore.FILE_NAME,
        "shortform_personal_stats_v1.json",
        "reels_usage_v1.json",
        "shortform_daily_v1.json",
        "itv_user_session.json",
        "login_completed_v1"
    )

    internal val NO_BACKUP_FILES_TO_DELETE = SIGN_OUT_FILES_TO_DELETE + listOf(
        "jev_test_token_v1",
        "consent_guest_v1.json",
        "permission_intro_v2_seen"
    )

    internal val SIGN_OUT_PREF_NAMES_TO_CLEAR = listOf(
        "itv_puppy_stamps_v1",
        "itv_user_reward_store_v1",
        CalendarRewardStore.PREF_NAME,
        "itv_user_equip_store_v1",
        "user_coaching_settings_prefs",
        "itv_bedtime_memo",
        "itv_intervention_journal_v1",
        "itv_mission_counts_v1",
        "itv_theme_prefs_v1",
        CloudSyncRepository.PREF_NAME
    )

    internal val SHARED_PREF_NAMES_TO_CLEAR = SIGN_OUT_PREF_NAMES_TO_CLEAR + listOf(
        "coaching_test",
        "jev_test_settings_v1",
        "itv_auth_prefs"
    )

    suspend fun wipeAllLocalUserData(context: Context) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        wipeCoreUserData(
            appCtx = appCtx,
            prefNames = SHARED_PREF_NAMES_TO_CLEAR,
            fileNames = NO_BACKUP_FILES_TO_DELETE,
            deleteDevTestingDb = true
        )
        // Mark fresh-start boundary so re-registration doesn't pull deleted account's OS UsageEvents
        runCatching { AccountDataBoundaryStore.markFreshStart(appCtx) }
        runCatching { CoachingAutoStart.sync(appCtx) }
    }

    suspend fun wipeForSignOut(context: Context) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        wipeCoreUserData(
            appCtx = appCtx,
            prefNames = SIGN_OUT_PREF_NAMES_TO_CLEAR,
            fileNames = SIGN_OUT_FILES_TO_DELETE,
            deleteDevTestingDb = false
        )
        // Isolate next login on this device from the signed-out account's OS UsageEvents
        runCatching { AccountDataBoundaryStore.markFreshStart(appCtx) }
        runCatching { CoachingAutoStart.sync(appCtx) }
    }

    private suspend fun wipeCoreUserData(
        appCtx: Context,
        prefNames: List<String>,
        fileNames: List<String>,
        deleteDevTestingDb: Boolean
    ) {
        // 1. Pause/reset in-memory shortform trackers & delete shortform session files
        withTimeoutOrNull(2_500L) {
            runCatching { ShortformDailyStore.resetForAccountDeletion(appCtx) }
        }

        // 2. Wipe 6-month usage/sleep/session archive, preloaded calendar cache & reels aggregate store
        runCatching { CalendarHealthLoader.clearCache() }
        withTimeoutOrNull(2_000L) {
            runCatching { MonthlyUsageArchiveStore.clearAll(appCtx) }
        }
        runCatching { ReelsUsageStore.clearAll(appCtx) }

        // 3. Clear all Room tables (goals, settings, owners, shortform daily/hourly, sleep confirmations, interventions, receipts)
        withTimeoutOrNull(3_000L) {
            runCatching { ItvDatabase.get(appCtx).clearAllTables() }
        }
        if (deleteDevTestingDb) {
            runCatching { appCtx.deleteDatabase("jev-testing.db") }
        }

        // 4. Reset in-memory StateFlow / singleton preference stores
        runCatching { DailyStampPrefs.clear(appCtx) }
        runCatching { com.example.itv.infrastructure.sleep.SleepWakeCycleStore.clear(appCtx) }
        runCatching { UserRewardStore.reset(appCtx) }
        runCatching { CalendarRewardStore.reset(appCtx) }
        runCatching { UserEquipStore.reset(appCtx) }
        runCatching { UserCoachingSettingsStore.getInstance(appCtx).reset() }
        runCatching { BedtimeMemoStore.getInstance(appCtx).clear() }
        runCatching { UserThemeStore.reset(appCtx) }
        runCatching { CloudSyncRepository.clearLocalStatus(appCtx) }
        runCatching { DailyCloudBackupWorker.cancel(appCtx) }

        // 5. Synchronously clear target SharedPreferences files
        prefNames.forEach { prefName ->
            runCatching {
                appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }
        }

        // 6. Delete target app-owned files (and AtomicFile .bak siblings) inside noBackupFilesDir
        deleteFilesInDirectory(appCtx.noBackupFilesDir, fileNames)
    }

    internal fun deleteFilesInDirectory(dir: File, fileNames: List<String>) {
        fileNames.forEach { name ->
            val base = File(dir, name)
            runCatching { AtomicFile(base).delete() }
            runCatching { if (base.exists()) base.delete() }
            val bak = File(base.path + ".bak")
            runCatching { if (bak.exists()) bak.delete() }
        }
    }
}
