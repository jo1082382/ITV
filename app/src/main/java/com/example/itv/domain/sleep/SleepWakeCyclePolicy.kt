package com.example.itv.domain.sleep

import java.time.Instant
import java.time.ZoneId

/**
 * Pure domain policy determining whether a user has already completed a sleep cycle (>= 3 hours)
 * and woken up for the current night's bedtime window.
 *
 * Prevents post-wake daytime/morning usage (e.g. waking up after 6+ hours of sleep at +448m / +460m
 * after last night's bedtime) from being misclassified as "취침 +448분 지연" (bedtime procrastination).
 */
object SleepWakeCyclePolicy {

    /** Minimum continuous rest duration recognized as a real sleep cycle (matches NightReportCalculator). */
    const val MIN_SLEEP_CYCLE_MS: Long = 3L * 60 * 60 * 1000L

    /** Maximum minutes past bedtime considered active night delay (6 hours). */
    const val MAX_POST_BEDTIME_MINUTES: Int = 360

    /**
     * When we are currently past the nominal bedtime (diff >= 0), a completed sleep ending
     * at or after (anchor - 4h) belongs to this night's cycle (covers early sleepers too,
     * while excluding yesterday morning's wake-up or afternoon naps).
     */
    const val POST_BEDTIME_LOOKBACK_MS: Long = 4L * 60 * 60 * 1000L

    /**
     * When we are before the nominal bedtime (diff < 0), only a sleep ending very close to
     * tonight's anchor (within 2h before anchor) suppresses this cycle (excludes afternoon/evening naps).
     */
    const val PRE_BEDTIME_LOOKBACK_MS: Long = 2L * 60 * 60 * 1000L

    private const val CLOCK_SKEW_TOLERANCE_MS: Long = 60_000L

    data class EffectiveBedtimeDistance(
        val effectiveDiffMinutes: Int,
        val rawDiffMinutes: Int,
        val hasWokenUpForCycle: Boolean
    )

    fun circularMinuteDiff(currentMinute: Int, targetMinute: Int): Int {
        var diff = (currentMinute - targetMinute) % 1440
        if (diff > 720) diff -= 1440
        if (diff < -720) diff += 1440
        return diff
    }

    /**
     * Returns the wall-clock epoch ms of the bedtime anchor corresponding to
     * [circularMinuteDiff] at [nowWallMs] for [targetMinuteOfDay] (0..1439).
     */
    fun bedtimeAnchorWallMs(
        nowWallMs: Long,
        targetMinuteOfDay: Int,
        zone: ZoneId
    ): Long {
        val normTarget = ((targetMinuteOfDay % 1440) + 1440) % 1440
        val nowZdt = Instant.ofEpochMilli(nowWallMs).atZone(zone)
        val nowMin = nowZdt.hour * 60 + nowZdt.minute
        val rawDiff = circularMinuteDiff(nowMin, normTarget)
        val nowMinuteEpoch = nowZdt.withSecond(0).withNano(0).toInstant().toEpochMilli()
        return nowMinuteEpoch - rawDiff * 60_000L
    }

    /**
     * Returns `true` if a completed sleep cycle (>= 3 hours) ended in the current bedtime cycle
     * at or before [nowWallMs], meaning the user already slept and woke up.
     */
    fun hasCompletedSleepForBedtimeCycle(
        nowWallMs: Long,
        targetMinuteOfDay: Int,
        zone: ZoneId,
        lastSleepEndWallMs: Long?,
        lastSleepDurationMs: Long?
    ): Boolean {
        if (lastSleepEndWallMs == null) return false
        if (lastSleepEndWallMs > nowWallMs + CLOCK_SKEW_TOLERANCE_MS) return false
        if (lastSleepDurationMs != null && lastSleepDurationMs < MIN_SLEEP_CYCLE_MS) return false

        val normTarget = ((targetMinuteOfDay % 1440) + 1440) % 1440
        val nowZdt = Instant.ofEpochMilli(nowWallMs).atZone(zone)
        val nowMin = nowZdt.hour * 60 + nowZdt.minute
        val rawDiff = circularMinuteDiff(nowMin, normTarget)
        val anchorWallMs = bedtimeAnchorWallMs(nowWallMs, normTarget, zone)

        val earliestValidWakeMs = if (rawDiff >= 0) {
            anchorWallMs - POST_BEDTIME_LOOKBACK_MS
        } else {
            anchorWallMs - PRE_BEDTIME_LOOKBACK_MS
        }
        return lastSleepEndWallMs >= earliestValidWakeMs
    }

    /**
     * Resolves the effective signed minute distance to bedtime at [nowWallMs].
     * Once the user has already slept (>= 3h) and woken up for the current cycle,
     * yesterday's bedtime is finished: positive elapsed offsets (e.g. +448m) roll over
     * into the negative countdown until tonight's next bedtime (e.g. -992m).
     */
    fun resolveEffectiveBedtimeDistance(
        nowWallMs: Long,
        targetMinuteOfDay: Int,
        zone: ZoneId,
        lastSleepEndWallMs: Long?,
        lastSleepDurationMs: Long?
    ): EffectiveBedtimeDistance {
        val normTarget = ((targetMinuteOfDay % 1440) + 1440) % 1440
        val nowZdt = Instant.ofEpochMilli(nowWallMs).atZone(zone)
        val nowMin = nowZdt.hour * 60 + nowZdt.minute
        val rawDiff = circularMinuteDiff(nowMin, normTarget)
        val woken = hasCompletedSleepForBedtimeCycle(
            nowWallMs = nowWallMs,
            targetMinuteOfDay = normTarget,
            zone = zone,
            lastSleepEndWallMs = lastSleepEndWallMs,
            lastSleepDurationMs = lastSleepDurationMs
        )
        val effectiveDiff = if (woken) {
            // Yesterday's bedtime is over — count down negatively to tonight's bedtime.
            if (rawDiff >= 0) rawDiff - 1440 else rawDiff - 1440
        } else {
            rawDiff
        }
        return EffectiveBedtimeDistance(
            effectiveDiffMinutes = effectiveDiff,
            rawDiffMinutes = rawDiff,
            hasWokenUpForCycle = woken
        )
    }
}
