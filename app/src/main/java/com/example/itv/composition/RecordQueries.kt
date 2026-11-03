package com.example.itv.composition

import android.content.Context
import java.time.*
import com.example.itv.application.records.RecordService
import com.example.itv.infrastructure.records.AndroidRecordSource
import com.example.itv.domain.records.*

internal fun queryNightReport(context: Context, days: Int): NightReport =
    RecordService(AndroidRecordSource(context)).nightReport(days, System.currentTimeMillis(), ZoneId.systemDefault())
internal fun Context.queryUsageReport(days: Int, targetDate: LocalDate? = null): Report =
    RecordService(AndroidRecordSource(this)).usageReport(days, targetDate, System.currentTimeMillis(), ZoneId.systemDefault())
