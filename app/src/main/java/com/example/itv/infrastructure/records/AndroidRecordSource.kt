package com.example.itv.infrastructure.records

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import org.json.JSONObject
import java.io.File
import com.example.itv.domain.records.*

internal class AndroidRecordSource(context: Context): RecordSource {
    private val context = context.applicationContext
    override fun requireCollectionConsent() {
        val consent = com.example.itv.infrastructure.coaching.CoachingConsentStore.granted(context)
        require(consent) { "설정에서 수집 안내를 확인하고 동의해 주세요." }
    }
    @Suppress("DEPRECATION")
    override fun requireUsagePermission() {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        require(ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED) { "사용 정보 접근 권한을 허용한 뒤 다시 열어주세요." }
    }
    override fun events(from: Long, to: Long): List<UsageObservation> {
        val events = (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryEvents(from, to)
        val event = UsageEvents.Event()
        val rows = mutableListOf<UsageObservation>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> ObservationKind.ACTIVITY_RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> ObservationKind.ACTIVITY_PAUSED
                UsageEvents.Event.SCREEN_INTERACTIVE -> ObservationKind.SCREEN_INTERACTIVE
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ObservationKind.SCREEN_NON_INTERACTIVE
                UsageEvents.Event.KEYGUARD_SHOWN -> ObservationKind.KEYGUARD_SHOWN
                UsageEvents.Event.KEYGUARD_HIDDEN -> ObservationKind.KEYGUARD_HIDDEN
                UsageEvents.Event.DEVICE_STARTUP -> ObservationKind.DEVICE_STARTUP
                UsageEvents.Event.DEVICE_SHUTDOWN -> ObservationKind.DEVICE_SHUTDOWN
                else -> ObservationKind.OTHER
            }
            rows.add(UsageObservation(event.timeStamp, kind, event.packageName))
        }
        return rows
    }
    override fun appLabel(packageName: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) { if (packageName == "com.instagram.android") "Instagram" else packageName }
}
