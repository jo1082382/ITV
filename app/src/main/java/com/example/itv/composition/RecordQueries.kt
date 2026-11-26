package com.example.itv.composition

import android.content.Context
import java.time.*
import com.example.itv.application.records.RecordService
import com.example.itv.infrastructure.records.AndroidRecordSource
import com.example.itv.domain.records.*

internal fun queryNightReport(
    context: Context,
    days: Int,
    finalizeActiveWake: Boolean = false
): NightReport {
    val liveSleep = com.example.itv.infrastructure.sleep.SleepWakeCycleStore
        .getLastCompletedSleep(context)
        ?.let { it.sleepStartWallMs to it.sleepEndWallMs }
    return RecordService(AndroidRecordSource(context)).nightReport(
        days = days,
        now = System.currentTimeMillis(),
        zone = ZoneId.systemDefault(),
        finalizeActiveWake = finalizeActiveWake,
        fallbackLiveSleep = liveSleep
    )
}
internal fun Context.queryUsageReport(days: Int, targetDate: LocalDate? = null): Report =
    RecordService(AndroidRecordSource(this)).usageReport(days, targetDate, System.currentTimeMillis(), ZoneId.systemDefault())
