package com.example.itv.application.records

import com.example.itv.domain.records.*
import java.time.*

/** Orchestrates reads; does not access Android, Compose or Room. */
internal class RecordService(private val source: RecordSource) {
    fun nightReport(days: Int, now: Long, zone: ZoneId): NightReport {
        source.requireUsagePermission()
        return calculateNightReport(source.events(0L, now), days, zone, now)
    }
    fun usageReport(days: Int, targetDate: LocalDate?, now: Long, zone: ZoneId): Report {
        source.requireCollectionConsent()
        source.requireUsagePermission()
        val today = targetDate ?: Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = today.minusDays(days.toLong() - 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = if (targetDate == null) now else minOf(now, targetDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        return calculateUsageReport(source.events(start, end), days, zone, start, end, source::appLabel)
    }
}
