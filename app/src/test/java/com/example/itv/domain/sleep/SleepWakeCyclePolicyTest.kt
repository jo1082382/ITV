package com.example.itv.domain.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SleepWakeCyclePolicyTest {

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private fun wallMs(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `waking up after 6h sleep suppresses +448m and +460m bedtime alerts and rolls over to tonight`() {
        val nightDate = LocalDate.of(2026, 11, 25)
        val wakeDate = LocalDate.of(2026, 11, 26)
        val bedtimeMin = 23 * 60 + 30 // 23:30

        val sleepStart = wallMs(nightDate, 23, 50)
        val sleepEnd = wallMs(wakeDate, 6, 58) // 7h 08m sleep
        val sleepDuration = sleepEnd - sleepStart

        // At 06:58 (raw +448m past 23:30)
        val atWake = SleepWakeCyclePolicy.resolveEffectiveBedtimeDistance(
            nowWallMs = wallMs(wakeDate, 6, 58),
            targetMinuteOfDay = bedtimeMin,
            zone = zone,
            lastSleepEndWallMs = sleepEnd,
            lastSleepDurationMs = sleepDuration
        )
        assertTrue("Should detect wake-up at 06:58 (+448m)", atWake.hasWokenUpForCycle)
        assertEquals(448, atWake.rawDiffMinutes)
        assertEquals(-992, atWake.effectiveDiffMinutes)

        // At 07:10 (raw +460m past 23:30)
        val laterMorning = SleepWakeCyclePolicy.resolveEffectiveBedtimeDistance(
            nowWallMs = wallMs(wakeDate, 7, 10),
            targetMinuteOfDay = bedtimeMin,
            zone = zone,
            lastSleepEndWallMs = sleepEnd,
            lastSleepDurationMs = sleepDuration
        )
        assertTrue("Should stay reset at 07:10 (+460m)", laterMorning.hasWokenUpForCycle)
        assertEquals(460, laterMorning.rawDiffMinutes)
        assertEquals(-980, laterMorning.effectiveDiffMinutes)

        // Same night at 22:30 (1h before tonight's 23:30 bedtime) -> wake-up from 06:58 must NOT suppress tonight!
        val tonightApproaching = SleepWakeCyclePolicy.resolveEffectiveBedtimeDistance(
            nowWallMs = wallMs(wakeDate, 22, 30),
            targetMinuteOfDay = bedtimeMin,
            zone = zone,
            lastSleepEndWallMs = sleepEnd,
            lastSleepDurationMs = sleepDuration
        )
        assertFalse("Tonight's approaching bedtime must NOT be suppressed", tonightApproaching.hasWokenUpForCycle)
        assertEquals(-60, tonightApproaching.effectiveDiffMinutes)

        // Same night at 23:55 (25m past tonight's 23:30 bedtime, not yet asleep) -> must still trigger!
        val tonightPast = SleepWakeCyclePolicy.resolveEffectiveBedtimeDistance(
            nowWallMs = wallMs(wakeDate, 23, 55),
            targetMinuteOfDay = bedtimeMin,
            zone = zone,
            lastSleepEndWallMs = sleepEnd,
            lastSleepDurationMs = sleepDuration
        )
        assertFalse("Tonight's post-bedtime must NOT be suppressed before tonight's sleep", tonightPast.hasWokenUpForCycle)
        assertEquals(25, tonightPast.effectiveDiffMinutes)
    }

    @Test
    fun `short break under 3 hours during late night does NOT suppress bedtime`() {
        val earlyMorning = LocalDate.of(2026, 11, 26)
        val bedtimeMin = 23 * 60 + 30

        val shortRestStart = wallMs(earlyMorning, 0, 30)
        val shortRestEnd = wallMs(earlyMorning, 1, 0) // 30m < 3h

        val res = SleepWakeCyclePolicy.resolveEffectiveBedtimeDistance(
            nowWallMs = wallMs(earlyMorning, 1, 15),
            targetMinuteOfDay = bedtimeMin,
            zone = zone,
            lastSleepEndWallMs = shortRestEnd,
            lastSleepDurationMs = shortRestEnd - shortRestStart
        )
        assertFalse(res.hasWokenUpForCycle)
        assertEquals(105, res.effectiveDiffMinutes)
    }

    @Test
    fun `afternoon nap does NOT suppress tonight bedtime window`() {
        val day = LocalDate.of(2026, 11, 26)
        val bedtimeMin = 23 * 60 + 30
        val napStart = wallMs(day, 14, 0)
        val napEnd = wallMs(day, 17, 30) // 3.5h afternoon nap

        val approaching = SleepWakeCyclePolicy.resolveEffectiveBedtimeDistance(
            nowWallMs = wallMs(day, 22, 45),
            targetMinuteOfDay = bedtimeMin,
            zone = zone,
            lastSleepEndWallMs = napEnd,
            lastSleepDurationMs = napEnd - napStart
        )
        assertFalse("Afternoon nap must not suppress tonight's bedtime", approaching.hasWokenUpForCycle)
        assertEquals(-45, approaching.effectiveDiffMinutes)

        val past = SleepWakeCyclePolicy.resolveEffectiveBedtimeDistance(
            nowWallMs = wallMs(day, 23, 50),
            targetMinuteOfDay = bedtimeMin,
            zone = zone,
            lastSleepEndWallMs = napEnd,
            lastSleepDurationMs = napEnd - napStart
        )
        assertFalse("Afternoon nap must not suppress tonight's post-bedtime", past.hasWokenUpForCycle)
        assertEquals(20, past.effectiveDiffMinutes)
    }

    @Test
    fun `early sleeper waking up at 04_30 still resets midnight bedtime cycle`() {
        val nightDate = LocalDate.of(2026, 11, 25)
        val wakeDate = LocalDate.of(2026, 11, 26)
        val bedtimeMin = 0 // 00:00

        val sleepStart = wallMs(nightDate, 21, 30)
        val sleepEnd = wallMs(wakeDate, 4, 30) // 7h sleep

        val res = SleepWakeCyclePolicy.resolveEffectiveBedtimeDistance(
            nowWallMs = wallMs(wakeDate, 4, 45),
            targetMinuteOfDay = bedtimeMin,
            zone = zone,
            lastSleepEndWallMs = sleepEnd,
            lastSleepDurationMs = sleepEnd - sleepStart
        )
        assertTrue(res.hasWokenUpForCycle)
        assertTrue("Effective diff should be negative countdown to next night", res.effectiveDiffMinutes < -60)
    }
}
