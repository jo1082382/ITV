package com.example.itv.infrastructure.cloud

import com.example.itv.domain.auth.AuthProvider
import com.example.itv.infrastructure.storage.ArchivedAppUsage
import com.example.itv.infrastructure.storage.DailyArchiveRecord
import com.example.itv.infrastructure.storage.MonthlyUsageArchiveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CloudSyncAndCustomTokenTest {

    @Test
    fun mergeDailyRecords_prefersConfirmedSleepAndMaxMetrics_andPrunesOlderThan6Months() {
        val today = LocalDate.of(2026, 11, 23)
        val local = mapOf(
            "2026-10-15" to DailyArchiveRecord(
                date = "2026-10-15",
                totalPhoneMs = 7_200_000L,
                shortformMs = 1_800_000L,
                shortformSwipes = 25L,
                bedtimeMinute = 23 * 60 + 30,
                wakeupMinute = 7 * 60,
                sleepConfirmed = false,
                hourlyPhoneMinutes = List(24) { if (it == 21) 45.0 else 0.0 },
                topApps = listOf(ArchivedAppUsage("com.instagram.android", "Instagram", 30.0))
            )
        )
        val cloud = listOf(
            DailyArchiveRecord(
                date = "2026-10-15",
                totalPhoneMs = 9_000_000L,
                shortformMs = 1_200_000L,
                shortformSwipes = 40L,
                bedtimeMinute = 23 * 60 + 10,
                wakeupMinute = 6 * 60 + 50,
                sleepDurationMinutes = 460.0,
                sleepConfirmed = true
            ),
            DailyArchiveRecord(
                date = "2026-09-01",
                totalPhoneMs = 6_000_000L,
                shortformMs = 900_000L
            ),
            // Older than 6 months -> must be pruned
            DailyArchiveRecord(
                date = "2026-04-01",
                totalPhoneMs = 5_000_000L,
                shortformMs = 1_000_000L
            )
        )

        val merged = MonthlyUsageArchiveStore.mergeDailyRecords(local, cloud, today)
        assertFalse("Record older than 6 months must be pruned", merged.containsKey("2026-04-01"))
        assertTrue("Cloud-only record within 6 months must be restored", merged.containsKey("2026-09-01"))
        val oct15 = merged["2026-10-15"]!!
        assertEquals(9_000_000L, oct15.totalPhoneMs)
        assertEquals(1_800_000L, oct15.shortformMs)
        assertEquals(40L, oct15.shortformSwipes)
        assertTrue(oct15.sleepConfirmed)
        assertEquals(23 * 60 + 10, oct15.bedtimeMinute)
        assertEquals(6 * 60 + 50, oct15.wakeupMinute)
        assertEquals(1, oct15.topApps.size)
        assertEquals(24, oct15.hourlyPhoneMinutes.size)
    }

    @Test
    fun mergeDailyRecords_freshRestoreWithoutLocal_keepsOnlyCloudWithinSixMonths() {
        val today = LocalDate.of(2026, 11, 23)
        val cloud = listOf(
            DailyArchiveRecord(
                date = "2026-06-10",
                totalPhoneMs = 4_500_000L,
                shortformMs = 600_000L,
                sleepConfirmed = true,
                bedtimeMinute = 23 * 60,
                wakeupMinute = 6 * 60 + 30
            ),
            DailyArchiveRecord(
                date = "2026-05-01",
                totalPhoneMs = 4_000_000L,
                shortformMs = 500_000L
            )
        )

        val restored = MonthlyUsageArchiveStore.mergeDailyRecords(emptyMap(), cloud, today)
        assertEquals(1, restored.size)
        assertTrue(restored.containsKey("2026-06-10"))
        assertFalse(restored.containsKey("2026-05-01"))
    }

    @Test
    fun yearMonthAndProviderValidators_matchFirestoreRulesConstraints() {
        assertTrue(CloudSyncRepository.isValidYearMonth("2026-01"))
        assertTrue(CloudSyncRepository.isValidYearMonth("2026-12"))
        assertFalse(CloudSyncRepository.isValidYearMonth("2026-13"))
        assertFalse(CloudSyncRepository.isValidYearMonth("2026-00"))
        assertFalse(CloudSyncRepository.isValidYearMonth("../users"))

        assertEquals("KAKAO", CloudSyncRepository.resolveCloudProvider(null, "kakao_12345"))
        assertEquals("NAVER", CloudSyncRepository.resolveCloudProvider(null, "naver_abcde"))
        assertEquals("GOOGLE", CloudSyncRepository.resolveCloudProvider(AuthProvider.GOOGLE, "gUid123"))
    }

    @Test
    fun calendarRewardMergeSnapshots_supportsFreshRestoreAndGuestMerge() {
        val d1 = LocalDate.of(2026, 11, 10)
        val d2 = LocalDate.of(2026, 11, 18)
        val local = com.example.itv.infrastructure.reward.CalendarRewardSnapshot(
            streakShieldCount = 1,
            bestStreak = 5,
            protectedDates = setOf(d1),
            claimedChestKeys = setOf("2026-11:BRONZE"),
            recentDayTiers = mapOf(d1 to com.example.itv.domain.records.DailyHealthTier.GOLD),
            lastAutoProtectedDate = d1,
            lastAutoProtectionSource = com.example.itv.domain.reward.StreakProtectionSource.SHIELD
        )
        val remote = com.example.itv.infrastructure.reward.CalendarRewardSnapshot(
            streakShieldCount = 5, // exceeds MAX_STREAK_SHIELDS (2), should clamp cleanly
            bestStreak = 14,
            protectedDates = setOf(d2),
            claimedChestKeys = setOf("2026-11:SILVER"),
            recentDayTiers = mapOf(d2 to com.example.itv.domain.records.DailyHealthTier.SILVER),
            lastAutoProtectedDate = d2,
            lastAutoProtectionSource = com.example.itv.domain.reward.StreakProtectionSource.POINTS
        )

        val expectedClampedShields = com.example.itv.domain.reward.CalendarStreakPolicy.MAX_STREAK_SHIELDS

        // Fresh install login (mergeWithLocal = false): takes remote authoritatively & clamps shields
        val fresh = com.example.itv.infrastructure.reward.CalendarRewardStore.mergeSnapshots(
            local = local,
            remote = remote,
            mergeWithLocal = false
        )
        assertEquals(expectedClampedShields, fresh.streakShieldCount)
        assertEquals(14, fresh.bestStreak)
        assertEquals(setOf(d2), fresh.protectedDates)
        assertEquals(setOf("2026-11:SILVER"), fresh.claimedChestKeys)
        assertEquals(d2, fresh.lastAutoProtectedDate)
        assertEquals(com.example.itv.domain.reward.StreakProtectionSource.POINTS, fresh.lastAutoProtectionSource)

        // Guest -> Social merge (mergeWithLocal = true): combines both without losing either
        val merged = com.example.itv.infrastructure.reward.CalendarRewardStore.mergeSnapshots(
            local = local,
            remote = remote,
            mergeWithLocal = true
        )
        assertEquals(expectedClampedShields, merged.streakShieldCount)
        assertEquals(14, merged.bestStreak)
        assertEquals(setOf(d1, d2), merged.protectedDates)
        assertEquals(setOf("2026-11:BRONZE", "2026-11:SILVER"), merged.claimedChestKeys)
        assertEquals(2, merged.recentDayTiers.size)
        assertEquals(d2, merged.lastAutoProtectedDate)
    }
}
