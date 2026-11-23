package com.example.itv.domain.records

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal data class ResolvedNightSleep(
    val bedtimeMinute: Int? = null,
    val wakeupMinute: Int? = null,
    val sleepDurationMinutes: Double? = null,
    val bedtimeStartWallMs: Long? = null,
    val sleepEndWallMs: Long? = null,
    val matchedSegment: NightSegment? = null,
    val hasExplicitNoSleep: Boolean = false,
    val hasWakeupResolved: Boolean = false
)

internal data class DayCycleBounds(
    val dayStartWallMs: Long,
    val dayEndWallMs: Long
)

internal data class TimeWindowMs(
    val startMs: Long,
    val endMs: Long
)

internal data class HourlyUsageBucket(
    val date: LocalDate,
    val hour: Int,
    val durationMs: Long
)

internal data class ArchivedSleepRecord(
    val wakeDate: LocalDate,
    val bedtimeMinute: Int,
    val wakeupMinute: Int
)

internal data class MergedSleepData(
    val segments: List<NightSegment>,
    val candidates: List<NightSegment>
)

/**
 * Pure sleep-cycle day boundary & pre-sleep attribution policy for ITV's calendar scoring.
 *
 * Core invariants across EVERY date (today, yesterday, and all previous dates):
 * 1. A day D owns the sleep-cycle interval [dayStartWallMs(D), dayEndWallMs(D)), where
 *    dayStartWallMs(D) is the end of (D-1)'s sleep on morning D (or D 05:00 when unmeasured).
 *    Consecutive finalized days form a strict partition (dayEnd(D) == dayStart(D+1)):
 *    early-morning usage on D before waking up ALWAYS belongs to D-1, NEVER to D.
 * 2. If yesterday's (D) sleep has not yet started/been measured—even past midnight into today (D+1)—
 *    D stays IN_PROGRESS and continues absorbing early-morning pre-sleep usage up to now.
 * 3. Today (D+1) does NOT start calculating until yesterday (D) has both finalized and been stamped
 *    (unless yesterday had NO_DATA at all, e.g. brand-new install).
 * 4. Pre-sleep usage for date D strictly inspects D's night (D -> D+1), NEVER D's own early morning
 *    (D 00:00..04:59), eliminating any double-counting across consecutive nights.
 */
internal object CalendarDayCyclePolicy {

    val DEFAULT_NIGHT_CUTOFF: LocalTime = LocalTime.of(5, 0)
    val LATE_NIGHT_START: LocalTime = LocalTime.of(23, 0)
    val ALL_NIGHTER_FINALIZE_CUTOFF: LocalTime = LocalTime.of(12, 0)
    const val PRE_SLEEP_WINDOW_MS: Long = 60 * 60_000L

    /**
     * Resolves the sleep record belonging to the night of [date] (concluding on [date] + 1).
     * Never reads [date]'s own archive record (which stores the previous night's wake-up on [date]).
     */
    fun resolveNightSleepForDate(
        date: LocalDate,
        zone: ZoneId,
        wakeConf: SleepConfirmation?,
        segments: List<NightSegment>,
        candidates: List<NightSegment>,
        nextArchiveBedtimeMin: Int?,
        nextArchiveWakeupMin: Int?,
        nextArchiveSleepStartWallMs: Long? = null,
        nextArchiveSleepEndWallMs: Long? = null,
        nextArchiveSleepDurationMinutes: Double? = null
    ): ResolvedNightSleep {
        val nextDate = date.plusDays(1)
        val wakeSegment = segments.firstOrNull { it.wakeDate == nextDate && it.end != null }
            ?: candidates.firstOrNull { it.wakeDate == nextDate && it.end != null }

        if (wakeConf != null && wakeConf.choice == "NOT_SLEEP") {
            return ResolvedNightSleep(
                hasExplicitNoSleep = true,
                hasWakeupResolved = true
            )
        }

        if (wakeConf != null && wakeConf.choice == "SLEEP" && wakeConf.startMs != null && wakeConf.endMs != null) {
            val startZdt = Instant.ofEpochMilli(wakeConf.startMs).atZone(zone)
            val endZdt = Instant.ofEpochMilli(wakeConf.endMs).atZone(zone)
            return ResolvedNightSleep(
                bedtimeMinute = startZdt.hour * 60 + startZdt.minute,
                wakeupMinute = endZdt.hour * 60 + endZdt.minute,
                sleepDurationMinutes = (wakeConf.endMs - wakeConf.startMs).coerceAtLeast(0L) / 60_000.0,
                bedtimeStartWallMs = wakeConf.startMs,
                sleepEndWallMs = wakeConf.endMs,
                matchedSegment = wakeSegment,
                hasWakeupResolved = true
            )
        }

        if (wakeSegment != null && wakeSegment.end != null) {
            val startZdt = Instant.ofEpochMilli(wakeSegment.start).atZone(zone)
            val endZdt = Instant.ofEpochMilli(wakeSegment.end).atZone(zone)
            return ResolvedNightSleep(
                bedtimeMinute = startZdt.hour * 60 + startZdt.minute,
                wakeupMinute = endZdt.hour * 60 + endZdt.minute,
                sleepDurationMinutes = wakeSegment.minutes,
                bedtimeStartWallMs = wakeSegment.start,
                sleepEndWallMs = wakeSegment.end,
                matchedSegment = wakeSegment,
                hasWakeupResolved = true
            )
        }

        if (nextArchiveBedtimeMin != null || nextArchiveWakeupMin != null ||
            (nextArchiveSleepStartWallMs != null && nextArchiveSleepEndWallMs != null)
        ) {
            val bedWall = nextArchiveSleepStartWallMs
                ?: nextArchiveBedtimeMin?.let { bedtimeMinuteToWallMs(date, it, zone) }
            val wakeWall = nextArchiveSleepEndWallMs
                ?: nextArchiveWakeupMin?.let { wakeupMinuteToWallMs(nextDate, it, bedWall, zone) }
            val resolvedBedMin = nextArchiveBedtimeMin ?: bedWall?.let {
                val zdt = Instant.ofEpochMilli(it).atZone(zone)
                zdt.hour * 60 + zdt.minute
            }
            val resolvedWakeMin = nextArchiveWakeupMin ?: wakeWall?.let {
                val zdt = Instant.ofEpochMilli(it).atZone(zone)
                zdt.hour * 60 + zdt.minute
            }
            val durationMin = nextArchiveSleepDurationMinutes
                ?: if (bedWall != null && wakeWall != null && wakeWall > bedWall) {
                    (wakeWall - bedWall) / 60_000.0
                } else {
                    null
                }
            return ResolvedNightSleep(
                bedtimeMinute = resolvedBedMin,
                wakeupMinute = resolvedWakeMin,
                sleepDurationMinutes = durationMin,
                bedtimeStartWallMs = bedWall,
                sleepEndWallMs = wakeWall,
                matchedSegment = null,
                hasWakeupResolved = true
            )
        }

        return ResolvedNightSleep()
    }

    /**
     * Converts a bedtime minute-of-day (0..1439) for the night of [nightDate] into epoch ms:
     * 18:00..23:59 -> on [nightDate]; 00:00..17:59 -> on [nightDate] + 1.
     */
    fun bedtimeMinuteToWallMs(nightDate: LocalDate, bedtimeMinute: Int, zone: ZoneId): Long {
        val norm = ((bedtimeMinute % 1440) + 1440) % 1440
        val targetDate = if (norm >= 18 * 60) nightDate else nightDate.plusDays(1)
        return targetDate.atTime(norm / 60, norm % 60).atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * Converts a wake-up minute-of-day (0..1439) on [wakeDate] into epoch ms,
     * ensuring it is strictly after [bedtimeStartWallMs] when provided.
     */
    fun wakeupMinuteToWallMs(
        wakeDate: LocalDate,
        wakeupMinute: Int,
        bedtimeStartWallMs: Long?,
        zone: ZoneId
    ): Long? {
        val norm = ((wakeupMinute % 1440) + 1440) % 1440
        val candidate = wakeDate.atTime(norm / 60, norm % 60).atZone(zone).toInstant().toEpochMilli()
        return if (bedtimeStartWallMs == null || candidate > bedtimeStartWallMs) candidate else null
    }

    /**
     * Finalization rule:
     * - Today is always IN_PROGRESS (finalizes after tonight's sleep).
     * - Yesterday stays IN_PROGRESS while sleep for yesterday's night has not been measured yet
     *   (even past midnight into early morning today!), finalizing once wake-up is detected/confirmed,
     *   already stamped, or at the noon all-nighter fallback so the calendar never permanently deadlocks.
     * - Older dates (< yesterday) are always finalized.
     */
    fun isDateFinalized(
        date: LocalDate,
        today: LocalDate,
        nowTime: LocalTime,
        nightSleep: ResolvedNightSleep,
        isDateStamped: Boolean
    ): Boolean {
        return when {
            date == today -> false
            date == today.minusDays(1) ->
                isDateStamped || nightSleep.hasWakeupResolved || nowTime >= ALL_NIGHTER_FINALIZE_CUTOFF
            else -> true
        }
    }

    /**
     * Today's calculation MUST NOT start until yesterday's score has finalized AND been stamped
     * (unless yesterday had NO_DATA at all, e.g. brand-new install).
     */
    fun shouldDeferTodayCalculation(
        yesterdayHasData: Boolean,
        isYesterdayFinalized: Boolean,
        isYesterdayStamped: Boolean
    ): Boolean {
        if (!yesterdayHasData) return false
        return !isYesterdayFinalized || !isYesterdayStamped
    }

    /**
     * Computes the exact non-overlapping sleep-cycle bounds [dayStartWallMs, dayEndWallMs) for [date].
     */
    fun resolveDayCycleBounds(
        date: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        nowWallMs: Long,
        prevNightSleep: ResolvedNightSleep,
        nightSleep: ResolvedNightSleep,
        isDateFinalized: Boolean,
        yesterdayHasData: Boolean = true
    ): DayCycleBounds {
        val isDateToday = date == today
        val isDateYesterday = date == today.minusDays(1)

        val dayStartWallMs = when {
            prevNightSleep.sleepEndWallMs != null -> prevNightSleep.sleepEndWallMs
            isDateToday && !yesterdayHasData -> date.atStartOfDay(zone).toInstant().toEpochMilli()
            else -> date.atTime(DEFAULT_NIGHT_CUTOFF).atZone(zone).toInstant().toEpochMilli()
        }

        val rawEndWallMs = when {
            isDateToday -> nowWallMs
            isDateYesterday && !isDateFinalized -> nowWallMs
            else -> nightSleep.sleepEndWallMs
                ?: date.plusDays(1).atTime(DEFAULT_NIGHT_CUTOFF).atZone(zone).toInstant().toEpochMilli()
        }

        val dayEndWallMs = rawEndWallMs.coerceAtLeast(dayStartWallMs)
        return DayCycleBounds(dayStartWallMs = dayStartWallMs, dayEndWallMs = dayEndWallMs)
    }

    /**
     * Computes the exact pre-sleep window for the night of [date] ([date] -> [date] + 1),
     * strictly bounded inside [cycleBounds] so it can NEVER include [date]'s own early morning (00:00..04:59)
     * or overlap with (date - 1)'s pre-sleep window.
     */
    fun resolvePreSleepWindow(
        date: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        nowWallMs: Long,
        cycleBounds: DayCycleBounds,
        bedtimeStartWallMs: Long?,
        isDateFinalized: Boolean
    ): TimeWindowMs? {
        val isDateToday = date == today
        val isDateYesterday = date == today.minusDays(1)

        if (bedtimeStartWallMs != null) {
            val winStart = maxOf(bedtimeStartWallMs - PRE_SLEEP_WINDOW_MS, cycleBounds.dayStartWallMs)
            val winEnd = minOf(bedtimeStartWallMs, cycleBounds.dayEndWallMs)
            return if (winEnd > winStart) TimeWindowMs(winStart, winEnd) else null
        }

        val lateNightStart = date.atTime(LATE_NIGHT_START).atZone(zone).toInstant().toEpochMilli()
        val winStart = maxOf(lateNightStart, cycleBounds.dayStartWallMs)

        val winEnd = when {
            isDateToday -> {
                // Tonight's pre-sleep window has not started yet before 23:00.
                if (nowWallMs <= winStart) return null
                minOf(nowWallMs, cycleBounds.dayEndWallMs)
            }
            isDateYesterday && !isDateFinalized -> {
                // Still awake past midnight without measured sleep: continue attributing usage to yesterday up to now.
                nowWallMs
            }
            else -> {
                // Finalized past day without measured sleep: [23:00 of date, dayEnd (05:00 of date+1 or next wake)].
                cycleBounds.dayEndWallMs
            }
        }

        return if (winEnd > winStart) TimeWindowMs(winStart, winEnd) else null
    }

    /**
     * Computes total overlap in minutes between [intervals] ([startMs, endMs]) and [window].
     */
    fun computeIntervalsOverlapMinutes(
        intervals: List<Pair<Long, Long>>,
        window: TimeWindowMs
    ): Double {
        var overlapMs = 0L
        for ((start, end) in intervals) {
            val s = maxOf(start, window.startMs)
            val e = minOf(end, window.endMs)
            if (e > s) overlapMs += (e - s)
        }
        return overlapMs / 60_000.0
    }

    /**
     * Computes proportional overlap in milliseconds between hourly usage buckets and [window].
     */
    fun computeHourlyBucketsOverlapMs(
        buckets: List<HourlyUsageBucket>,
        window: TimeWindowMs,
        zone: ZoneId
    ): Long {
        if (buckets.isEmpty() || window.endMs <= window.startMs) return 0L
        var totalMs = 0.0
        for (bucket in buckets) {
            if (bucket.hour !in 0..23 || bucket.durationMs <= 0L) continue
            val bucketStart = bucket.date.atTime(bucket.hour, 0).atZone(zone).toInstant().toEpochMilli()
            val bucketEnd = bucketStart + 3_600_000L
            val overlap = minOf(bucketEnd, window.endMs) - maxOf(bucketStart, window.startMs)
            if (overlap > 0L) {
                val fraction = (overlap.toDouble() / 3_600_000.0).coerceIn(0.0, 1.0)
                totalMs += bucket.durationMs * fraction
            }
        }
        return totalMs.toLong()
    }

    /**
     * Sums minutes from a 24-hour calendar report for [date] strictly within [window],
     * excluding early-morning hours that belonged to the previous day's sleep cycle.
     */
    fun computeReportMinutesInWindow(
        date: LocalDate,
        hourlyMinutes: List<Double>,
        window: TimeWindowMs,
        zone: ZoneId
    ): Double {
        if (hourlyMinutes.size != 24 || window.endMs <= window.startMs) return 0.0
        var totalMinutes = 0.0
        for (hour in 0..23) {
            val mins = hourlyMinutes[hour]
            if (mins <= 0.0) continue
            val bucketStart = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            val bucketEnd = bucketStart + 3_600_000L
            val overlap = minOf(bucketEnd, window.endMs) - maxOf(bucketStart, window.startMs)
            if (overlap > 0L) {
                val fraction = (overlap.toDouble() / 3_600_000.0).coerceIn(0.0, 1.0)
                totalMinutes += mins * fraction
            }
        }
        return totalMinutes
    }

    /**
     * Merges live OS UsageEvents sleep segments/candidates with long-term archived sleep records
     * and user-confirmed sleep records so sleep pattern summaries and 30-day charts retain full history
     * even after Android OS prunes UsageEvents older than ~7-10 days.
     */
    fun mergeArchivedSleepData(
        liveSegments: List<NightSegment>,
        liveCandidates: List<NightSegment>,
        archivedRecords: List<ArchivedSleepRecord>,
        confirmations: Map<String, SleepConfirmation>,
        zone: ZoneId
    ): MergedSleepData {
        val mergedSegments = liveSegments.toMutableList()
        val mergedCandidates = liveCandidates.toMutableList()

        val liveDates = (liveSegments.map { it.wakeDate } + liveCandidates.map { it.wakeDate }).toMutableSet()

        // 1. Add user-confirmed sleep records whose wakeDate is no longer in live OS events
        for ((dateKey, conf) in confirmations) {
            if (conf.choice != "SLEEP" || conf.startMs == null || conf.endMs == null || conf.endMs <= conf.startMs) continue
            val wakeDate = runCatching { LocalDate.parse(dateKey) }.getOrNull() ?: continue
            if (wakeDate in liveDates) continue
            val durationMin = (conf.endMs - conf.startMs) / 60_000.0
            val seg = NightSegment(
                wakeDate = wakeDate,
                start = conf.startMs,
                end = conf.endMs,
                minutes = durationMin,
                briefWakeCount = 0
            )
            mergedSegments.add(seg)
            mergedCandidates.add(seg)
            liveDates.add(wakeDate)
        }

        // 2. Backfill older dates from MonthlyUsageArchiveStore (skipping live dates & explicit NOT_SLEEP)
        for (rec in archivedRecords) {
            if (rec.wakeDate in liveDates) continue
            if (confirmations[rec.wakeDate.toString()]?.choice == "NOT_SLEEP") continue
            val nightDate = rec.wakeDate.minusDays(1)
            val startMs = bedtimeMinuteToWallMs(nightDate, rec.bedtimeMinute, zone)
            val endMs = wakeupMinuteToWallMs(rec.wakeDate, rec.wakeupMinute, startMs, zone) ?: continue
            val durationMin = (endMs - startMs) / 60_000.0
            if (durationMin < 60.0 || durationMin > 18.0 * 60.0) continue
            val seg = NightSegment(
                wakeDate = rec.wakeDate,
                start = startMs,
                end = endMs,
                minutes = durationMin,
                briefWakeCount = 0
            )
            mergedSegments.add(seg)
            mergedCandidates.add(seg)
            liveDates.add(rec.wakeDate)
        }

        return MergedSleepData(
            segments = mergedSegments.sortedByDescending { it.wakeDate },
            candidates = mergedCandidates.sortedByDescending { it.wakeDate }
        )
    }
}
