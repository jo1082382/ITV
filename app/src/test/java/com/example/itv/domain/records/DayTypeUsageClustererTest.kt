package com.example.itv.domain.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DayTypeUsageClustererTest {

    private fun profile(dayOffset: Long, totalMinutes: Long, sessionsMinutes: List<Long>): DayUsageProfile {
        val date = LocalDate.of(2026, 11, 1).plusDays(dayOffset)
        return DayUsageProfile(
            date = date,
            totalDurationMs = totalMinutes * 60_000L,
            sessions = sessionsMinutes.map { it * 60_000L }
        )
    }

    @Test
    fun separatesBimodalWorkdayAndRestdayClusters() {
        val profiles = listOf(
            profile(0, 25, listOf(8, 10, 7)),    // Mon: 25m
            profile(1, 30, listOf(12, 18)),      // Tue: 30m
            profile(2, 20, listOf(10, 10)),      // Wed: 20m
            profile(3, 35, listOf(15, 20)),      // Thu: 35m
            profile(4, 40, listOf(10, 15, 15)),  // Fri: 40m
            profile(5, 140, listOf(30, 45, 65)), // Sat: 140m
            profile(6, 160, listOf(40, 50, 70))  // Sun: 160m
        )

        val result = DayTypeUsageClusterer.cluster(profiles)

        assertTrue("Should be confident with 7 days clear bimodal data", result.isConfident)
        assertEquals("Low cluster should contain 5 workdays", 5, result.workdayDays.size)
        assertEquals("High cluster should contain 2 rest days", 2, result.restdayDays.size)

        // Threshold between Fri (40m) and Sat (140m) -> 90m (5,400,000ms)
        assertEquals(90 * 60_000L, result.splitThresholdMs)

        // Workday daily average: (25+30+20+35+40)/5 = 30m
        assertEquals(30 * 60_000L, result.workdayAvgDailyMs)

        // Restday daily average: (140+160)/2 = 150m
        assertEquals(150 * 60_000L, result.restdayAvgDailyMs)

        // Cluster ratio = 150 / 30 = 5.0
        assertEquals(5.0, result.clusterRatio, 0.01)

        // Check classification
        assertEquals(EstimatedDayType.WORKDAY, result.classify(35 * 60_000L))
        assertEquals(EstimatedDayType.REST_DAY, result.classify(120 * 60_000L))
    }

    @Test
    fun handlesSmallDataGracefully() {
        val profiles = listOf(
            profile(0, 30, listOf(15, 15))
        )
        val result = DayTypeUsageClusterer.cluster(profiles)
        assertFalse("Single day should not be marked as confident", result.isConfident)
        assertEquals(1, result.workdayDays.size)
        assertEquals(0, result.restdayDays.size)
    }

    @Test
    fun handlesUniformUsageWhereAllDaysAreEqual() {
        val profiles = listOf(
            profile(0, 30, listOf(15, 15)),
            profile(1, 30, listOf(15, 15)),
            profile(2, 30, listOf(15, 15)),
            profile(3, 30, listOf(15, 15))
        )
        val result = DayTypeUsageClusterer.cluster(profiles)
        // Ratio is ~1.0, not confident
        assertFalse("Uniform data should not be confident", result.isConfident)
    }

    @Test
    fun clusterByCategoryExtractsTargetCategoryProperly() {
        val date0 = LocalDate.of(2026, 11, 1)
        val profiles = listOf(
            DayUsageProfile(
                date = date0,
                totalDurationMs = 180 * 60_000L,
                categoryDurationsMs = mapOf("shortform" to 20 * 60_000L, "game" to 100 * 60_000L)
            ),
            DayUsageProfile(
                date = date0.plusDays(1),
                totalDurationMs = 200 * 60_000L,
                categoryDurationsMs = mapOf("shortform" to 25 * 60_000L, "game" to 110 * 60_000L)
            ),
            DayUsageProfile(
                date = date0.plusDays(2),
                totalDurationMs = 190 * 60_000L,
                categoryDurationsMs = mapOf("shortform" to 18 * 60_000L, "game" to 95 * 60_000L)
            ),
            DayUsageProfile(
                date = date0.plusDays(3),
                totalDurationMs = 175 * 60_000L,
                categoryDurationsMs = mapOf("shortform" to 15 * 60_000L, "game" to 90 * 60_000L)
            ),
            DayUsageProfile(
                date = date0.plusDays(4),
                totalDurationMs = 450 * 60_000L,
                categoryDurationsMs = mapOf("shortform" to 120 * 60_000L, "game" to 280 * 60_000L)
            )
        )

        val gameCluster = DayTypeUsageClusterer.clusterByCategory(profiles, "game")
        assertEquals(4, gameCluster.workdayDays.size)
        assertEquals(1, gameCluster.restdayDays.size)
        assertTrue(gameCluster.isConfident)
        assertEquals(280 * 60_000L, gameCluster.restdayAvgDailyMs)
    }

    @Test
    fun sparseShortformSupportedByPhoneTotalUsageCluster() {
        // User scenario: Shortform only has 5 days (4 low days, 1 high day)
        val shortformProfiles = listOf(
            profile(0, 15, listOf(5, 10)),   // Mon
            profile(1, 20, listOf(8, 12)),   // Tue
            profile(2, 10, listOf(10)),      // Wed
            profile(3, 25, listOf(12, 13)),  // Thu
            profile(4, 90, listOf(40, 50))   // Sat
        )
        val sfResult = DayTypeUsageClusterer.cluster(shortformProfiles)
        assertEquals(4, sfResult.workdayDays.size)
        assertEquals(1, sfResult.restdayDays.size)

        // Meanwhile, Phone total usage has 28 days of solid records
        val phoneProfiles = (0..27).map { day ->
            val isWeekend = (day % 7) in 5..6
            val totalMins = if (isWeekend) 360L + (day % 3) * 30L else 120L + (day % 4) * 15L
            profile(day.toLong(), totalMins, emptyList())
        }
        val phoneResult = DayTypeUsageClusterer.cluster(phoneProfiles)
        assertTrue("Phone total usage should be confident with 28 days", phoneResult.isConfident)
        assertEquals(20, phoneResult.workdayDays.size)
        assertEquals(8, phoneResult.restdayDays.size)

        // Cross-checking: On a high-phone day (Sat, 360m), phone classifies as REST_DAY
        assertEquals(EstimatedDayType.REST_DAY, phoneResult.classify(360 * 60_000L))
        // On a regular-phone day (Mon, 120m), phone classifies as WORKDAY
        assertEquals(EstimatedDayType.WORKDAY, phoneResult.classify(120 * 60_000L))
    }

    @Test
    fun hybridClassifierAppliesWeekendPriorAndRestdayBaseline() {
        val saturday = LocalDate.of(2026, 11, 7) // Saturday
        val sfCluster = DayTypeClusterResult(
            workdayDays = emptyList(),
            restdayDays = emptyList(),
            splitThresholdMs = 45 * 60_000L,
            workdayAvgDailyMs = 20 * 60_000L,
            workdayAvgSessionMs = 6 * 60_000L,
            restdayAvgDailyMs = 90 * 60_000L,
            restdayAvgSessionMs = 18 * 60_000L,
            clusterRatio = 4.5,
            isConfident = true
        )
        val phoneCluster = DayTypeClusterResult(
            workdayDays = emptyList(),
            restdayDays = emptyList(),
            splitThresholdMs = 240 * 60_000L,
            workdayAvgDailyMs = 150 * 60_000L,
            workdayAvgSessionMs = 150 * 60_000L,
            restdayAvgDailyMs = 360 * 60_000L,
            restdayAvgSessionMs = 360 * 60_000L,
            clusterRatio = 2.4,
            isConfident = true
        )

        val decision = HybridDayTypeClassifier.classify(
            date = saturday,
            phoneTotalTodayMs = 250 * 60_000L,
            shortformTodayMs = 50 * 60_000L,
            phoneCluster = phoneCluster,
            shortformCluster = sfCluster,
            globalAvgSessionMs = 10 * 60_000L,
            globalAvgDailyMs = 40 * 60_000L
        )

        assertEquals(EstimatedDayType.REST_DAY, decision.estimatedType)
        // Rest-day session baseline (18m) should be selected for the day
        assertEquals(18 * 60_000L, decision.effectiveAvgSessionMs)
        assertTrue(decision.confidence >= 0.80)
    }

    @Test
    fun hybridClassifierAppliesWeekdayPriorAndWorkdayBaseline() {
        val tuesday = LocalDate.of(2026, 11, 10) // Tuesday
        val sfCluster = DayTypeClusterResult(
            workdayDays = emptyList(),
            restdayDays = emptyList(),
            splitThresholdMs = 45 * 60_000L,
            workdayAvgDailyMs = 20 * 60_000L,
            workdayAvgSessionMs = 6 * 60_000L,
            restdayAvgDailyMs = 90 * 60_000L,
            restdayAvgSessionMs = 18 * 60_000L,
            clusterRatio = 4.5,
            isConfident = true
        )
        val phoneCluster = DayTypeClusterResult(
            workdayDays = emptyList(),
            restdayDays = emptyList(),
            splitThresholdMs = 240 * 60_000L,
            workdayAvgDailyMs = 150 * 60_000L,
            workdayAvgSessionMs = 150 * 60_000L,
            restdayAvgDailyMs = 360 * 60_000L,
            restdayAvgSessionMs = 360 * 60_000L,
            clusterRatio = 2.4,
            isConfident = true
        )

        val decision = HybridDayTypeClassifier.classify(
            date = tuesday,
            phoneTotalTodayMs = 120 * 60_000L,
            shortformTodayMs = 15 * 60_000L,
            phoneCluster = phoneCluster,
            shortformCluster = sfCluster,
            globalAvgSessionMs = 10 * 60_000L,
            globalAvgDailyMs = 40 * 60_000L
        )

        assertEquals(EstimatedDayType.WORKDAY, decision.estimatedType)
        // Workday session baseline (6m) should be selected for early routine protection
        assertEquals(6 * 60_000L, decision.effectiveAvgSessionMs)
        assertTrue(decision.confidence >= 0.80)
    }

    @Test
    fun hybridClassifierDetectsWeekdayHolidayWithHighUsage() {
        val wednesday = LocalDate.of(2026, 11, 11) // Wednesday holiday
        val sfCluster = DayTypeClusterResult(
            workdayDays = emptyList(),
            restdayDays = emptyList(),
            splitThresholdMs = 45 * 60_000L,
            workdayAvgDailyMs = 20 * 60_000L,
            workdayAvgSessionMs = 6 * 60_000L,
            restdayAvgDailyMs = 90 * 60_000L,
            restdayAvgSessionMs = 18 * 60_000L,
            clusterRatio = 4.5,
            isConfident = true
        )
        val phoneCluster = DayTypeClusterResult(
            workdayDays = emptyList(),
            restdayDays = emptyList(),
            splitThresholdMs = 240 * 60_000L,
            workdayAvgDailyMs = 150 * 60_000L,
            workdayAvgSessionMs = 150 * 60_000L,
            restdayAvgDailyMs = 360 * 60_000L,
            restdayAvgSessionMs = 360 * 60_000L,
            clusterRatio = 2.4,
            isConfident = true
        )

        // Massive usage on Wednesday (450m phone, 150m shortform)
        val decision = HybridDayTypeClassifier.classify(
            date = wednesday,
            phoneTotalTodayMs = 450 * 60_000L,
            shortformTodayMs = 150 * 60_000L,
            phoneCluster = phoneCluster,
            shortformCluster = sfCluster,
            globalAvgSessionMs = 10 * 60_000L,
            globalAvgDailyMs = 40 * 60_000L
        )

        // Usage evidence overcomes weekday prior and adapts to REST_DAY!
        assertEquals(EstimatedDayType.REST_DAY, decision.estimatedType)
        assertEquals(18 * 60_000L, decision.effectiveAvgSessionMs)
    }
}
