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
 * 2-hour progressive pre-bedtime windows anchored to the earliest edge of the recommended bedtime range
 * (e.g., for recommended 21:00~22:00, anchor is 21:00):
 * - [outerWindow]: [anchor - 2h, anchor - 1h] (lower weight: 35%)
 * - [innerWindow]: [anchor - 1h, anchor]      (higher weight: 65%)
 * - [postStartWindow]: [anchor, actualBedtime/now] if user stayed awake past recommended start (highest weight: 100%)
 */
internal data class PreSleepWeightedWindows(
    val outerWindow: TimeWindowMs?,
    val innerWindow: TimeWindowMs?,
    val postStartWindow: TimeWindowMs? = null
)

internal data class WeightedPreSleepUsage(
    val outerMinutes: Double,
    val innerMinutes: Double,
    val postStartMinutes: Double,
    /** Equivalent minutes on 0..60 scale (60.0 == 100% weighted load across the 2h progressive window). */
    val weightedMinutes: Double,
    /** Percentage 0..100 where outer 100% = 35%, inner 100% = 65%, both 100% = 100%. */
    val weightedPercent: Int
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
    val ALL_NIGHTER_FINALIZE_CUTOFF: LocalTime = LocalTime.of(12, 0)
    const val PRE_SLEEP_WINDOW_MS: Long = 60 * 60_000L
    const val PRE_SLEEP_HOUR_MS: Long = 60 * 60_000L
    /** 권장 취침 시작 2시간 전 ~ 1시간 전 구간 가중치 (35%) */
    const val PRE_SLEEP_OUTER_HOUR_WEIGHT: Double = 0.35
    /** 권장 취침 시작 1시간 전 ~ 권장 취침 시작 구간 가중치 (65%) */
    const val PRE_SLEEP_INNER_HOUR_WEIGHT: Double = 0.65
    /** 권장 취침 시작 시각 이후 ~ 실제 취침 전 잔여 각성 구간 가중치 (100%) */
    const val PRE_SLEEP_POST_START_WEIGHT: Double = 1.00

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
     *
     * Priority:
     * 1. When actual sleep onset ([bedtimeStartWallMs], start of the non-use sleep interval) is known:
     *    strictly `[bedtimeStartWallMs - 1h, bedtimeStartWallMs]`.
     * 2. When the user explicitly marked `NOT_SLEEP`: returns `null`.
     * 3. When actual sleep has not started/been detected yet:
     *    uses `[fallbackBedtimeMinute - 1h, ...]` where [fallbackBedtimeMinute] comes from
     *    the Sleep tab's personal estimated bedtime (day-of-week -> global) or bedtime goal.
     *    If neither personal data nor goal exists, returns `null` rather than guessing 23:00.
     */
    fun resolvePreSleepWindow(
        date: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        nowWallMs: Long,
        cycleBounds: DayCycleBounds,
        bedtimeStartWallMs: Long?,
        isDateFinalized: Boolean,
        fallbackBedtimeMinute: Int? = null,
        hasExplicitNoSleep: Boolean = false
    ): TimeWindowMs? {
        val isDateToday = date == today
        val isDateYesterday = date == today.minusDays(1)

        if (bedtimeStartWallMs != null) {
            val winStart = maxOf(bedtimeStartWallMs - PRE_SLEEP_WINDOW_MS, cycleBounds.dayStartWallMs)
            val winEnd = minOf(bedtimeStartWallMs, cycleBounds.dayEndWallMs)
            return if (winEnd > winStart) TimeWindowMs(winStart, winEnd) else null
        }

        if (hasExplicitNoSleep) return null

        val refBedtimeMin = fallbackBedtimeMinute ?: return null
        val refBedtimeWallMs = bedtimeMinuteToWallMs(date, refBedtimeMin, zone)
        val winStart = maxOf(refBedtimeWallMs - PRE_SLEEP_WINDOW_MS, cycleBounds.dayStartWallMs)

        val winEnd = when {
            isDateToday -> {
                // Tonight's pre-sleep window has not started yet before (refBedtime - 1h).
                if (nowWallMs <= winStart) return null
                minOf(nowWallMs, cycleBounds.dayEndWallMs)
            }
            isDateYesterday && !isDateFinalized -> {
                // Still awake past midnight without measured sleep: continue attributing usage to yesterday up to now.
                if (nowWallMs <= winStart) return null
                nowWallMs
            }
            else -> {
                // Finalized past day without measured sleep (e.g., all-nighter): [refBedtime - 1h, dayEnd].
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
     * Resolves progressive 2-hour pre-bedtime windows anchored to the earliest edge of the
     * recommended bedtime range ([recommendedBedtimeStartMinute], e.g., 21:00 for 21:00~22:00):
     * - [PreSleepWeightedWindows.outerWindow]: `[anchor - 2h, anchor - 1h]` (35% weight)
     * - [PreSleepWeightedWindows.innerWindow]: `[anchor - 1h, anchor]` (65% weight)
     * - [PreSleepWeightedWindows.postStartWindow]: `[anchor, upperLimit]` when user stays awake past
     *   recommended start before sleeping (100% weight, preventing false "clean pre-sleep" praise
     *   if user binges after recommended bedtime).
     *
     * Strictly clipped to [cycleBounds] so it can NEVER overlap with the previous night.
     */
    fun resolveWeightedPreSleepWindows(
        date: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        nowWallMs: Long,
        cycleBounds: DayCycleBounds,
        recommendedBedtimeStartMinute: Int?,
        bedtimeStartWallMs: Long?,
        isDateFinalized: Boolean,
        fallbackBedtimeMinute: Int? = null,
        hasExplicitNoSleep: Boolean = false
    ): PreSleepWeightedWindows? {
        if (hasExplicitNoSleep) return null

        val isDateToday = date == today
        val isDateYesterday = date == today.minusDays(1)

        // Anchor priority: 권장 취침 시간대 가장 앞의 시각 -> 목표/개인 추정 취침 시각 -> 실제 비사용(취침) 시작 시각
        val anchorWallMs: Long = when {
            recommendedBedtimeStartMinute != null ->
                bedtimeMinuteToWallMs(date, recommendedBedtimeStartMinute, zone)
            fallbackBedtimeMinute != null ->
                bedtimeMinuteToWallMs(date, fallbackBedtimeMinute, zone)
            bedtimeStartWallMs != null ->
                bedtimeStartWallMs
            else -> return null
        }

        val outerRawStart = anchorWallMs - 2 * PRE_SLEEP_HOUR_MS
        val outerRawEnd = anchorWallMs - PRE_SLEEP_HOUR_MS
        val innerRawStart = outerRawEnd
        val innerRawEnd = anchorWallMs

        val upperLimit = when {
            bedtimeStartWallMs != null -> minOf(bedtimeStartWallMs, cycleBounds.dayEndWallMs)
            isDateToday -> {
                if (nowWallMs <= outerRawStart) return null
                minOf(nowWallMs, cycleBounds.dayEndWallMs)
            }
            isDateYesterday && !isDateFinalized -> {
                if (nowWallMs <= outerRawStart) return null
                nowWallMs
            }
            else -> cycleBounds.dayEndWallMs
        }

        fun clip(rawStart: Long, rawEnd: Long): TimeWindowMs? {
            val s = maxOf(rawStart, cycleBounds.dayStartWallMs)
            val e = minOf(rawEnd, upperLimit)
            return if (e > s) TimeWindowMs(s, e) else null
        }

        val outerWin = clip(outerRawStart, outerRawEnd)
        val innerWin = clip(innerRawStart, innerRawEnd)
        val postWin = if (upperLimit > anchorWallMs) clip(anchorWallMs, upperLimit) else null

        return if (outerWin != null || innerWin != null || postWin != null) {
            PreSleepWeightedWindows(
                outerWindow = outerWin,
                innerWindow = innerWin,
                postStartWindow = postWin
            )
        } else {
            null
        }
    }

    /**
     * Aggregates raw minutes from outer (-2h..-1h), inner (-1h..0h), and post-start (0h..sleep)
     * into normalized progressive-weighted pre-sleep usage:
     * - 100% (60m) in outer hour only -> 35% (21.0 weighted min)
     * - 100% (60m) in inner hour only -> 65% (39.0 weighted min)
     * - 100% (120m) across both hours -> 100% (60.0 weighted min)
     * - Usage past recommended bedtime start before sleep adds at 100% weight, capped at 100% (60.0 min).
     */
    fun combineWeightedPreSleepMinutes(
        outerMinutes: Double,
        innerMinutes: Double,
        postStartMinutes: Double = 0.0
    ): WeightedPreSleepUsage {
        val outer = outerMinutes.coerceIn(0.0, 60.0)
        val inner = innerMinutes.coerceIn(0.0, 60.0)
        val post = postStartMinutes.coerceAtLeast(0.0)
        val weightedMin = (
            outer * PRE_SLEEP_OUTER_HOUR_WEIGHT +
                inner * PRE_SLEEP_INNER_HOUR_WEIGHT +
                post * PRE_SLEEP_POST_START_WEIGHT
            ).coerceIn(0.0, 60.0)
        val pct = kotlin.math.round((weightedMin / 60.0) * 100.0).toInt().coerceIn(0, 100)
        return WeightedPreSleepUsage(
            outerMinutes = outer,
            innerMinutes = inner,
            postStartMinutes = post,
            weightedMinutes = weightedMin,
            weightedPercent = pct
        )
    }

    fun computeWeightedIntervalsUsage(
        intervals: List<Pair<Long, Long>>,
        windows: PreSleepWeightedWindows
    ): WeightedPreSleepUsage {
        val outer = windows.outerWindow?.let { computeIntervalsOverlapMinutes(intervals, it) } ?: 0.0
        val inner = windows.innerWindow?.let { computeIntervalsOverlapMinutes(intervals, it) } ?: 0.0
        val post = windows.postStartWindow?.let { computeIntervalsOverlapMinutes(intervals, it) } ?: 0.0
        return combineWeightedPreSleepMinutes(outer, inner, post)
    }

    fun computeWeightedHourlyBucketsUsage(
        buckets: List<HourlyUsageBucket>,
        windows: PreSleepWeightedWindows,
        zone: ZoneId
    ): WeightedPreSleepUsage {
        val outer = windows.outerWindow?.let { computeHourlyBucketsOverlapMs(buckets, it, zone) / 60_000.0 } ?: 0.0
        val inner = windows.innerWindow?.let { computeHourlyBucketsOverlapMs(buckets, it, zone) / 60_000.0 } ?: 0.0
        val post = windows.postStartWindow?.let { computeHourlyBucketsOverlapMs(buckets, it, zone) / 60_000.0 } ?: 0.0
        return combineWeightedPreSleepMinutes(outer, inner, post)
    }

    fun computeWeightedReportUsage(
        date: LocalDate,
        hourlyMinutes: List<Double>,
        windows: PreSleepWeightedWindows,
        zone: ZoneId
    ): WeightedPreSleepUsage {
        val outer = windows.outerWindow?.let { computeReportMinutesInWindow(date, hourlyMinutes, it, zone) } ?: 0.0
        val inner = windows.innerWindow?.let { computeReportMinutesInWindow(date, hourlyMinutes, it, zone) } ?: 0.0
        val post = windows.postStartWindow?.let { computeReportMinutesInWindow(date, hourlyMinutes, it, zone) } ?: 0.0
        return combineWeightedPreSleepMinutes(outer, inner, post)
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
