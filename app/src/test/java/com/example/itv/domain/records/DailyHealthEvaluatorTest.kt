package com.example.itv.domain.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyHealthEvaluatorTest {

    private val sampleDate = LocalDate.of(2026, 11, 20)

    @Test
    fun `empty day returns NO_DATA`() {
        val result = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true
            )
        )
        assertEquals(DailyHealthTier.NO_DATA, result.tier)
        assertEquals(0, result.score)
    }

    @Test
    fun `tier 1 happy tongue stamp for strict pre-sleep restraint, quick shortform escape, and on-time sleep`() {
        val result = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 20,              // 00:20
                wakeupMinute = 440,              // 07:20
                sleepDurationMinutes = 420.0,    // 7h
                baselineBedtimeMinute = 30,      // 00:30 (-10m early)
                preSleepUsageMinutes = 2.0,      // Clean pre-sleep!
                preSleepShortformMinutes = 0.0,
                hasShortformTracking = true,
                shortformTotalMs = 10 * 60_000L, // 10 min
                shortformSwipes = 15L,
                shortformAvgSessionMs = 5 * 60_000L,
                shortformMaxSessionMs = 8 * 60_000L,
                shortformSpm = 1.5,
                baselineShortformDailyMs = 35 * 60_000L,
                baselineSpm = 2.0,
                totalPhoneMs = 120 * 60_000L,    // 2h
                phoneAvgSessionMs = 10 * 60_000L,
                phoneMaxSessionMs = 24 * 60_000L,
                phoneShortformRatio = 0.08,
                baselinePhoneDailyMs = 180 * 60_000L,
                isRestDay = false
            )
        )
        assertEquals(DailyHealthTier.GOLD, result.tier)
        assertEquals("해맑음", result.tier.label)
        assertTrue("Expected score >= 84, got ${result.score}", result.score >= 84)
        assertFalse(result.isPartialData)
        assertTrue(result.praises.isNotEmpty())
        assertTrue(result.cautions.isEmpty())
    }

    @Test
    fun `tier 2 smiling stamp for good day with minor slip`() {
        val result = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 50,              // 00:50 (+20m minor delay)
                sleepDurationMinutes = 410.0,
                baselineBedtimeMinute = 30,
                preSleepUsageMinutes = 10.0,
                preSleepShortformMinutes = 0.0,
                hasShortformTracking = true,
                shortformTotalMs = 20 * 60_000L,
                shortformSwipes = 30L,
                shortformAvgSessionMs = 10 * 60_000L,
                shortformMaxSessionMs = 16 * 60_000L,
                shortformSpm = 1.8,
                baselineShortformDailyMs = 30 * 60_000L,
                baselineSpm = 2.0,
                totalPhoneMs = 175 * 60_000L,
                phoneAvgSessionMs = 16 * 60_000L,
                phoneMaxSessionMs = 38 * 60_000L,
                phoneShortformRatio = 0.11,
                baselinePhoneDailyMs = 180 * 60_000L,
                isRestDay = false
            )
        )
        assertEquals(DailyHealthTier.SILVER, result.tier)
        assertEquals("웃음", result.tier.label)
        assertTrue("Expected score in 74..83, got ${result.score}", result.score in 74..83)
    }

    @Test
    fun `tier 3 sad stamp for ordinary slightly loose day`() {
        val result = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 70,              // 01:10 (+40m delay)
                sleepDurationMinutes = 370.0,
                baselineBedtimeMinute = 30,
                preSleepUsageMinutes = 20.0,
                preSleepShortformMinutes = 5.0,
                hasShortformTracking = true,
                shortformTotalMs = 32 * 60_000L,
                shortformSwipes = 55L,
                shortformAvgSessionMs = 14 * 60_000L,
                shortformMaxSessionMs = 24 * 60_000L,
                shortformSpm = 2.2,
                baselineShortformDailyMs = 30 * 60_000L,
                baselineSpm = 2.0,
                totalPhoneMs = 230 * 60_000L,
                phoneAvgSessionMs = 24 * 60_000L,
                phoneMaxSessionMs = 55 * 60_000L,
                phoneShortformRatio = 0.14,
                baselinePhoneDailyMs = 190 * 60_000L,
                isRestDay = false
            )
        )
        assertEquals(DailyHealthTier.BRONZE, result.tier)
        assertEquals("슬픔", result.tier.label)
        assertTrue("Expected score in 64..73, got ${result.score}", result.score in 64..73)
    }

    @Test
    fun `tier 4 angry stamp when single severe delay or overuse shakes rhythm`() {
        val result = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 115,             // 01:55 (+85m severe delay)
                baselineBedtimeMinute = 30,
                preSleepUsageMinutes = 25.0,
                preSleepShortformMinutes = 8.0,
                hasShortformTracking = true,
                shortformTotalMs = 30 * 60_000L,
                shortformSwipes = 45L,
                shortformAvgSessionMs = 12 * 60_000L,
                shortformMaxSessionMs = 18 * 60_000L,
                shortformSpm = 2.0,
                baselineSpm = 2.0,
                totalPhoneMs = 195 * 60_000L,
                phoneAvgSessionMs = 17 * 60_000L,
                phoneMaxSessionMs = 38 * 60_000L,
                baselinePhoneDailyMs = 190 * 60_000L,
                isRestDay = false
            )
        )
        assertEquals(DailyHealthTier.WARNING, result.tier)
        assertEquals("화남", result.tier.label)
        assertTrue(result.cautions.any { it.contains("취침") })
    }

    @Test
    fun `tier 5 sick thermometer stamp when late bedtime combines with pre-sleep shortform binge`() {
        val result = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 180,             // 03:00
                baselineBedtimeMinute = 30,      // 00:30 (+150m delay)
                preSleepUsageMinutes = 75.0,
                preSleepShortformMinutes = 45.0, // Pre-sleep binge!
                hasShortformTracking = true,
                shortformTotalMs = 120 * 60_000L,
                shortformSwipes = 600L,
                shortformAvgSessionMs = 35 * 60_000L,
                shortformMaxSessionMs = 60 * 60_000L,
                shortformSpm = 5.8,
                baselineShortformDailyMs = 40 * 60_000L,
                baselineSpm = 2.5,
                totalPhoneMs = 300 * 60_000L,
                phoneAvgSessionMs = 45 * 60_000L,
                phoneMaxSessionMs = 110 * 60_000L,
                phoneShortformRatio = 0.48,
                baselinePhoneDailyMs = 180 * 60_000L,
                isRestDay = false
            )
        )
        assertEquals(DailyHealthTier.CRITICAL, result.tier)
        assertEquals("아픔", result.tier.label)
        assertTrue("Expected score <= 51, got ${result.score}", result.score <= 51)
        assertTrue(result.cautions.size >= 2)
    }

    @Test
    fun `today defers finalization to IN_PROGRESS while computing projectedTier`() {
        val result = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = true,
                isFinalized = false,
                hasShortformTracking = true,
                shortformTotalMs = 0L,
                totalPhoneMs = 90 * 60_000L,
                phoneAvgSessionMs = 10 * 60_000L,
                phoneMaxSessionMs = 20 * 60_000L,
                baselinePhoneDailyMs = 180 * 60_000L
            )
        )
        assertEquals(DailyHealthTier.IN_PROGRESS, result.tier)
        assertEquals(DailyHealthTier.GOLD, result.projectedTier)
        assertTrue(result.headline.contains("내일 아침"))
    }

    @Test
    fun `partial data on historical day redistributes weights cleanly`() {
        val result = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate.minusDays(10),
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 20,              // 00:20
                baselineBedtimeMinute = 30,      // 00:30
                preSleepUsageMinutes = 2.0,
                hasShortformTracking = false,    // Pre-install: no shortform telemetry
                totalPhoneMs = 120 * 60_000L,
                baselinePhoneDailyMs = 180 * 60_000L
            )
        )
        assertTrue(result.isPartialData)
        assertEquals(3, result.evaluatedAxesCount)
        assertEquals(DailyHealthTier.GOLD, result.tier)
        val sfAxis = result.axes.first { it.id == "shortform" }
        assertFalse(sfAxis.available)
        assertEquals(0, sfAxis.weightPercent)
        val totalWeights = result.axes.sumOf { it.weightPercent }
        assertTrue("Normalized weights should sum near 100, got $totalWeights", totalWeights in 99..101)
    }

    @Test
    fun `circular bedtime diff handles midnight wrap-around properly`() {
        assertEquals(50, DailyHealthEvaluator.circularBedtimeDiffMinutes(20, 1410))
        assertEquals(-40, DailyHealthEvaluator.circularBedtimeDiffMinutes(1420, 20))
        assertEquals(90, DailyHealthEvaluator.circularBedtimeDiffMinutes(120, 30))
    }
}
