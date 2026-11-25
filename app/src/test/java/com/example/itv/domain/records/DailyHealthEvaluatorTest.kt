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
                baselinePhoneDailySampleDays = 14,
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
                baselinePhoneDailySampleDays = 14,
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
                baselinePhoneDailySampleDays = 14,
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
                baselinePhoneDailySampleDays = 14,
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
                baselinePhoneDailySampleDays = 14,
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
                baselinePhoneDailyMs = 180 * 60_000L,
                baselinePhoneDailySampleDays = 14
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
                baselinePhoneDailyMs = 180 * 60_000L,
                baselinePhoneDailySampleDays = 14
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
    fun `daily screen volume in axis 3 is skipped before 14 days of personal baseline`() {
        val underTwoWeeks = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                hasShortformTracking = false,
                totalPhoneMs = 540 * 60_000L, // 9h (would have triggered heavy penalty under fixed threshold)
                phoneAvgSessionMs = 16 * 60_000L,
                phoneMaxSessionMs = 40 * 60_000L,
                baselinePhoneDailyMs = 180 * 60_000L,
                baselinePhoneDailySampleDays = 13
            )
        )
        val sessionAxisUnder = underTwoWeeks.axes.first { it.id == "session" }
        assertEquals(84, sessionAxisUnder.score) // 82 baseline + 2 session bonus, 0 daily-volume penalty
        assertTrue(underTwoWeeks.cautions.none { it.contains("화면 사용량") })

        val twoWeeksHeavy = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                hasShortformTracking = false,
                totalPhoneMs = 540 * 60_000L, // 3.0x personal baseline
                phoneAvgSessionMs = 16 * 60_000L,
                phoneMaxSessionMs = 40 * 60_000L,
                baselinePhoneDailyMs = 180 * 60_000L,
                baselinePhoneDailySampleDays = 14
            )
        )
        val sessionAxisHeavy = twoWeeksHeavy.axes.first { it.id == "session" }
        assertEquals(62, sessionAxisHeavy.score) // 82 + 2 - 22
        assertTrue(twoWeeksHeavy.cautions.any { it.contains("화면 사용량") })

        val twoWeeksNormalHighUser = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                hasShortformTracking = false,
                totalPhoneMs = 360 * 60_000L, // 6h, matching their own 6h personal baseline (1.0x)
                phoneAvgSessionMs = 16 * 60_000L,
                phoneMaxSessionMs = 40 * 60_000L,
                baselinePhoneDailyMs = 360 * 60_000L,
                baselinePhoneDailySampleDays = 14
            )
        )
        val sessionAxisNormal = twoWeeksNormalHighUser.axes.first { it.id == "session" }
        assertEquals(84, sessionAxisNormal.score) // Personal baseline ratio 1.0 -> no penalty
    }

    @Test
    fun `axis 4 rhythm is excluded from score when personal baseline is missing`() {
        val result = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 20,
                baselineBedtimeMinute = 30,
                preSleepUsageMinutes = 2.0,
                hasShortformTracking = true,
                shortformTotalMs = 0L,
                totalPhoneMs = 400 * 60_000L,
                phoneAvgSessionMs = 15 * 60_000L,
                phoneMaxSessionMs = 35 * 60_000L,
                baselinePhoneDailyMs = null,
                baselinePhoneDailySampleDays = 0
            )
        )
        val rhythmAxis = result.axes.first { it.id == "rhythm" }
        assertFalse(rhythmAxis.available)
        assertEquals(0, rhythmAxis.weightPercent)
        assertEquals(0, rhythmAxis.score)
        assertTrue(result.isPartialData)
        assertEquals(3, result.evaluatedAxesCount)
        val totalWeights = result.axes.sumOf { it.weightPercent }
        assertTrue("Normalized weights should sum near 100, got $totalWeights", totalWeights in 99..101)
    }

    @Test
    fun `bedtime evaluation uses recommended bedtime safeguard and falls back to goal or skips without baseline`() {
        // 1. Slept later than usual (22:30 -> 23:15, +45m) but STILL inside recommended bedtime (23:30) -> no penalty!
        val insideRecommended = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 23 * 60 + 15,
                sleepDurationMinutes = 450.0,
                baselineBedtimeMinute = 22 * 60 + 30,
                recommendedBedtimeMinute = 23 * 60 + 30,
                preSleepUsageMinutes = 2.0
            )
        )
        val sleepAxisRec = insideRecommended.axes.first { it.id == "sleep" }
        assertTrue("Expected positive score when inside recommended bedtime, got ${sleepAxisRec.score}", sleepAxisRec.score >= 90)
        assertTrue(insideRecommended.cautions.none { it.contains("취침이") })

        // 2. No personal sleep history yet, but user has a bedtime goal (23:30) -> evaluates against goal!
        val goalFallback = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 1 * 60 + 10, // 01:10 (+100m vs 23:30 goal)
                baselineBedtimeMinute = null,
                recommendedBedtimeMinute = null,
                goalBedtimeMinute = 23 * 60 + 30
            )
        )
        assertTrue(goalFallback.cautions.any { it.contains("목표 취침") })

        // 3. Neither personal sleep history nor bedtime goal -> does NOT penalize against hardcoded 00:30
        val noReference = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 2 * 60 + 30, // 02:30 (would have been +120m severe trigger under 00:30 default!)
                sleepDurationMinutes = 430.0,
                baselineBedtimeMinute = null,
                recommendedBedtimeMinute = null,
                goalBedtimeMinute = null
            )
        )
        val sleepAxisNoRef = noReference.axes.first { it.id == "sleep" }
        assertEquals(84, sleepAxisNoRef.score) // 82 base + 2 duration, 0 arbitrary bedtime penalty
        assertTrue(noReference.cautions.isEmpty())
    }

    @Test
    fun `all-nighter sets sleep axis to 0 points and triggers warning or critical`() {
        val result = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                isAllNighter = true,
                hasShortformTracking = true,
                shortformTotalMs = 0L, // 96점
                totalPhoneMs = 120 * 60_000L,
                phoneAvgSessionMs = 10 * 60_000L,
                phoneMaxSessionMs = 20 * 60_000L,
                baselinePhoneDailyMs = 180 * 60_000L,
                baselinePhoneDailySampleDays = 14
            )
        )
        val sleepAxis = result.axes.first { it.id == "sleep" }
        assertTrue(sleepAxis.available)
        assertEquals(0, sleepAxis.score)
        assertEquals(40, sleepAxis.weightPercent)
        assertTrue(sleepAxis.summary.contains("밤샘"))
        assertTrue(result.cautions.any { it.contains("밤샘") })
        assertTrue(result.tier == DailyHealthTier.WARNING || result.tier == DailyHealthTier.CRITICAL)
    }

    @Test
    fun `circular bedtime diff handles midnight wrap-around properly`() {
        assertEquals(50, DailyHealthEvaluator.circularBedtimeDiffMinutes(20, 1410))
        assertEquals(-40, DailyHealthEvaluator.circularBedtimeDiffMinutes(1420, 20))
        assertEquals(90, DailyHealthEvaluator.circularBedtimeDiffMinutes(120, 30))
    }

    @Test
    fun `pre-sleep shortform binge never coexists with pre-sleep golden time praise or contradictory shortform praise`() {
        // 재현 사례: 취침 전 총 화면 사용량이 낮게(2분) 잡혔더라도 같은 시간대 숏폼이 37분이면
        // "취침 전 골든타임 스마트폰 절제 성공"이 잘한 점에 절대 들어가면 안 되고,
        // 숏폼 과몰입 아쉬운 점과 모순되는 숏폼 칭찬도 함께 나오지 않아야 함.
        val result = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 30,
                baselineBedtimeMinute = 30,
                sleepDurationMinutes = 420.0,
                preSleepUsageMinutes = 2.0,
                preSleepShortformMinutes = 37.0,
                hasShortformTracking = true,
                shortformTotalMs = 37 * 60_000L,
                shortformSwipes = 120L,
                shortformAvgSessionMs = 6 * 60_000L,
                shortformMaxSessionMs = 12 * 60_000L,
                shortformSpm = 2.5,
                totalPhoneMs = 200 * 60_000L,
                phoneAvgSessionMs = 10 * 60_000L,
                phoneMaxSessionMs = 25 * 60_000L
            )
        )
        assertTrue(result.cautions.any { it.contains("취침 전 1시간 숏폼 37분 과몰입") })
        assertTrue(
            "Should not praise pre-sleep restraint when pre-sleep shortform is 37m: ${result.praises}",
            result.praises.none { it.contains("취침 전 골든타임") }
        )
        assertTrue(
            "Should not praise shortform escape when pre-sleep shortform binge caution exists: ${result.praises}",
            result.praises.none { it.contains("숏폼") }
        )
        assertEquals(
            "Should not duplicate near-identical pre-sleep screen caution when shortform explains all 37m",
            1,
            result.cautions.count { it.contains("취침 전") }
        )
    }

    @Test
    fun `recommended bedtime range gives credit when sleeping inside range and weighted pre-sleep penalizes inner hour more than outer hour`() {
        // 권장 취침 시간대 22:00~23:00 안에 22:45 취침 -> 권장 취침 달성으로 감점 없이 긍정 평가
        val inRange = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 22 * 60 + 45,
                sleepDurationMinutes = 435.0,
                baselineBedtimeMinute = 22 * 60,
                recommendedBedtimeMinute = 22 * 60,
                recommendedBedtimeEndMinute = 23 * 60,
                preSleepUsageMinutes = 2.0,
                isWeightedPreSleep = true,
                preSleepWeightedPercent = 3
            )
        )
        val inRangeSleepAxis = inRange.axes.first { it.id == "sleep" }
        assertTrue("Expected positive score inside 22:00~23:00 recommended range, got ${inRangeSleepAxis.score}", inRangeSleepAxis.score >= 90)
        assertTrue(inRange.praises.any { it.contains("22:00~23:00") })

        // 동일 60분 사용이라도 2~1시간 전(가중 21분/35%)보다 1~0시간 전(가중 39분/65%)이 수면 점수가 더 낮아야 함
        val outerOnlyEval = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 22 * 60,
                baselineBedtimeMinute = 22 * 60,
                recommendedBedtimeMinute = 22 * 60,
                recommendedBedtimeEndMinute = 23 * 60,
                preSleepUsageMinutes = 21.0,
                isWeightedPreSleep = true,
                preSleepWeightedPercent = 35
            )
        )
        val innerOnlyEval = DailyHealthEvaluator.evaluate(
            DailyHealthContext(
                date = sampleDate,
                isToday = false,
                isFinalized = true,
                bedtimeMinute = 22 * 60,
                baselineBedtimeMinute = 22 * 60,
                recommendedBedtimeMinute = 22 * 60,
                recommendedBedtimeEndMinute = 23 * 60,
                preSleepUsageMinutes = 39.0,
                isWeightedPreSleep = true,
                preSleepWeightedPercent = 65
            )
        )
        val outerScore = outerOnlyEval.axes.first { it.id == "sleep" }.score
        val innerScore = innerOnlyEval.axes.first { it.id == "sleep" }.score
        assertTrue("Expected inner hour score ($innerScore) < outer hour score ($outerScore)", innerScore < outerScore)
    }
}
