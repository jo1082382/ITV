package com.example.itv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.itv.composition.AuthComposition
import com.example.itv.infrastructure.cloud.CloudSyncRepository
import com.example.itv.infrastructure.cloud.DailyCloudBackupWorker
import com.example.itv.infrastructure.jev.JevObservationHub
import com.example.itv.infrastructure.observation.accessibility.WritingGuard
import com.example.itv.infrastructure.observation.accessibility.WritingGuardService
import com.google.firebase.auth.FirebaseAuth
import com.kakao.sdk.common.KakaoSdk
import com.navercorp.nid.NidOAuth

class ItvApplication : Application() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startedActivities = 0
    private var resumedActivities = 0
    private val backgroundExitCheck = Runnable {
        if (resumedActivities <= 0 && WritingGuard.inAppForeground) {
            WritingGuard.inAppForeground = false
            WritingGuardService.onAppForegroundChanged(false)
            com.example.itv.presentation.coaching.CoachingMonitorService.notifyOwnAppExited()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerOwnAppForegroundTracker()
        AuthComposition.initialize(this)
        CloudSyncRepository.refreshStatus(this)
        val fbUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        if (fbUser != null && !fbUser.isAnonymous) {
            runCatching { DailyCloudBackupWorker.schedule(this) }
        }
        runCatching { com.example.itv.infrastructure.storage.DailyArchiveSyncWorker.schedule(this) }
        // 카카오 SDK 초기화 (BuildConfig에 정의된 네이티브 앱 키 사용)
        KakaoSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        // 네이버 SDK 초기화 (Client ID, Client Secret)
        NidOAuth.initialize(
            context = this,
            clientId = BuildConfig.NAVER_CLIENT_ID,
            clientSecret = BuildConfig.NAVER_CLIENT_SECRET,
            clientName = "ITV"
        )
    }

    /**
     * Tracks foreground state across ALL ITV activities (MainActivity, JevTestActivity,
     * PermissionSettingsActivity, etc.) so WritingGuardService enters zero-cost dormant mode
     * whenever the user is inside ITV and resumes external-app observation the instant they leave.
     */
    private fun registerOwnAppForegroundTracker() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }
            override fun onActivityResumed(activity: Activity) {
                resumedActivities++
                mainHandler.removeCallbacks(backgroundExitCheck)
                val wasForeground = WritingGuard.inAppForeground
                WritingGuard.inAppForeground = true
                JevObservationHub.foreground(packageName)
                if (!wasForeground) {
                    WritingGuardService.onAppForegroundChanged(true)
                }
            }
            override fun onActivityPaused(activity: Activity) {
                resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
                if (resumedActivities == 0) {
                    // Brief debounce for internal Activity transitions; longer safety net during rotation
                    // so screen rotation never flickers AccessibilityService out of dormant mode.
                    val delayMs = if (activity.isChangingConfigurations) 600L else 200L
                    mainHandler.removeCallbacks(backgroundExitCheck)
                    mainHandler.postDelayed(backgroundExitCheck, delayMs)
                }
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0 && resumedActivities == 0) {
                    if (activity.isChangingConfigurations) {
                        // Activity is being recreated for a configuration change (e.g. rotation):
                        // keep dormant mode active and rely on the 600ms safety-net debounce.
                        mainHandler.removeCallbacks(backgroundExitCheck)
                        mainHandler.postDelayed(backgroundExitCheck, 600L)
                        return
                    }
                    mainHandler.removeCallbacks(backgroundExitCheck)
                    if (WritingGuard.inAppForeground) {
                        WritingGuard.inAppForeground = false
                        WritingGuardService.onAppForegroundChanged(false)
                        com.example.itv.presentation.coaching.CoachingMonitorService.notifyOwnAppExited()
                    }
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
