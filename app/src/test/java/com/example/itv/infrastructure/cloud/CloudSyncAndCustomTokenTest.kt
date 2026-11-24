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
}
