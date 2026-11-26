package com.example.itv.domain.reward

import com.example.itv.domain.records.DailyHealthTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CalendarRewardPolicyTest {

    @Test
    fun monthlyCounterUnlocksAt5_12_20AndTracksClaimedChests() {
        val ym = YearMonth.of(2026, 11)

        // 4개(해맑음 2 + 웃음 2): 모두 잠금
        val c4 = CalendarStreakPolicy.evaluateMonthlyCounter(ym, goldCount = 2, silverCount = 2, claimedTiers = emptySet())
        assertEquals(4, c4.happyCount)
        assertEquals(0, c4.claimableCount)
        assertTrue(c4.chests.all { it.status == ChestClaimStatus.LOCKED })
        assertEquals(1, c4.chests.first { it.tier == MonthlyChestTier.BRONZE }.remainingToUnlock)

        // 12개(해맑음 7 + 웃음 5), 동 상자는 이미 수령
        val c12 = CalendarStreakPolicy.evaluateMonthlyCounter(
            ym,
            goldCount = 7,
            silverCount = 5,
            claimedTiers = setOf(MonthlyChestTier.BRONZE)
        )
        assertEquals(12, c12.happyCount)
        assertEquals(ChestClaimStatus.CLAIMED, c12.chests[0].status)
        assertEquals(ChestClaimStatus.UNLOCKED_CLAIMABLE, c12.chests[1].status)
        assertEquals(ChestClaimStatus.LOCKED, c12.chests[2].status)
        assertEquals(8, c12.chests[2].remainingToUnlock)
        assertEquals(1, c12.claimableCount)

        // 20개 달성: 금 상자까지 모두 열림
        val c20 = CalendarStreakPolicy.evaluateMonthlyCounter(
            ym,
            goldCount = 12,
            silverCount = 8,
            claimedTiers = setOf(MonthlyChestTier.BRONZE, MonthlyChestTier.SILVER)
        )
        assertEquals(20, c20.happyCount)
        assertEquals(ChestClaimStatus.UNLOCKED_CLAIMABLE, c20.chests[2].status)
        assertEquals(1f, c20.overallProgress, 0.001f)
    }

    @Test
    fun streakCountsConsecutivePositiveDaysAcrossMonthBoundaryAndInProgressToday() {
        val today = LocalDate.of(2026, 11, 3)
        val dayTiers = mapOf(
            LocalDate.of(2026, 10, 30) to DailyHealthTier.GOLD,
            LocalDate.of(2026, 10, 31) to DailyHealthTier.SILVER,
            LocalDate.of(2026, 11, 1) to DailyHealthTier.GOLD,
            LocalDate.of(2026, 11, 2) to DailyHealthTier.SILVER,
            LocalDate.of(2026, 11, 3) to DailyHealthTier.IN_PROGRESS
        )

        val summary = CalendarStreakPolicy.evaluateStreak(
            dayTiers = dayTiers,
            today = today,
            protectedDates = emptySet(),
            availableShields = 1,
            persistedBestStreak = 2,
            todayProjectedTier = DailyHealthTier.GOLD
        )

        assertEquals(4, summary.currentStreak)
        assertEquals(4, summary.bestStreak)
        assertEquals(7, summary.nextMilestone)
        assertEquals(3, summary.daysToNextMilestone)
        assertNull(summary.autoShieldCandidateDate)
        assertTrue(summary.isTodayProjectedPositive)
    }

    @Test
    fun streakAutoShieldCandidateTriggersWhenPriorStreakExistsAndPreservesOnceProtected() {
        val today = LocalDate.of(2026, 11, 10)
        val missedDay = LocalDate.of(2026, 11, 9)
        val dayTiers = mapOf(
            LocalDate.of(2026, 11, 6) to DailyHealthTier.GOLD,
            LocalDate.of(2026, 11, 7) to DailyHealthTier.SILVER,
            LocalDate.of(2026, 11, 8) to DailyHealthTier.GOLD,
            missedDay to DailyHealthTier.BRONZE, // 하루 삐끗 (슬픔)
            today to DailyHealthTier.IN_PROGRESS
        )

        // 보호권 보유 중: 직전 3일 스트릭이 있으므로 11/9가 보호권(SHIELD) 자동 방어 후보로 잡힘
        val beforeProtect = CalendarStreakPolicy.evaluateStreak(
            dayTiers = dayTiers,
            today = today,
            protectedDates = emptySet(),
            availableShields = 1,
            availablePoints = 0L
        )
        assertEquals(missedDay, beforeProtect.autoShieldCandidateDate)
        assertEquals(StreakProtectionSource.SHIELD, beforeProtect.autoProtectionSource)
        assertEquals(0, beforeProtect.currentStreak)

        // 보호권은 없지만 포인트가 150P 이상이면 포인트(POINTS) 자동 방어 후보로 잡힘
        val pointsFallback = CalendarStreakPolicy.evaluateStreak(
            dayTiers = dayTiers,
            today = today,
            protectedDates = emptySet(),
            availableShields = 0,
            availablePoints = 200L
        )
        assertEquals(missedDay, pointsFallback.autoShieldCandidateDate)
        assertEquals(StreakProtectionSource.POINTS, pointsFallback.autoProtectionSource)

        // 보호권 적용 후: 11/9가 protectedDates에 들어가면서 기존 3일 스트릭이 끊기지 않고 유지됨
        val afterProtect = CalendarStreakPolicy.evaluateStreak(
            dayTiers = dayTiers,
            today = today,
            protectedDates = setOf(missedDay),
            availableShields = 0,
            availablePoints = 0L
        )
        assertNull(afterProtect.autoShieldCandidateDate)
        assertNull(afterProtect.autoProtectionSource)
        assertEquals(3, afterProtect.currentStreak)
        assertEquals(setOf(missedDay), afterProtect.activeShieldProtectedDates)
    }

    @Test
    fun streakDoesNotWasteShieldOrPointsWhenNoPriorStreakExists() {
        val today = LocalDate.of(2026, 11, 10)
        val dayTiers = mapOf(
            LocalDate.of(2026, 11, 8) to DailyHealthTier.WARNING,
            LocalDate.of(2026, 11, 9) to DailyHealthTier.BRONZE,
            today to DailyHealthTier.IN_PROGRESS
        )

        val summary = CalendarStreakPolicy.evaluateStreak(
            dayTiers = dayTiers,
            today = today,
            protectedDates = emptySet(),
            availableShields = 2,
            availablePoints = 500L
        )
        assertEquals(0, summary.currentStreak)
        assertNull(summary.autoShieldCandidateDate)
        assertNull(summary.autoProtectionSource)
    }
}
