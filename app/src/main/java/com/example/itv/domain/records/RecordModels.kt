package com.example.itv.domain.records

import java.time.*

internal data class NightSegment(val wakeDate: LocalDate, val start: Long, val end: Long?, val minutes: Double, val briefWakeCount: Int = 0)
internal data class NightReport(val days: Int, val segments: List<NightSegment>, val zone: ZoneId, val now: Long, val candidates: List<NightSegment> = segments)

internal data class AppHourUsage(val packageName: String, val label: String, val minutes: Double, val entryCount: Int = 0, val entryAverage: Double? = null)

internal data class Report(val values: List<Double>, val total: Double, val days: Int, val dates: Int, val instagramMinutes: Double, val hourlyApps: List<List<AppHourUsage>>, val dailyTotals: Map<LocalDate, Double>)

// Kept for compatibility with the existing manifest; Records now embeds the content.
