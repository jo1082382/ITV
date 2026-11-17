package com.example.itv.infrastructure.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MonthlyUsageArchiveStoreTest {

    @Test
    fun getAvailablePastMonthKeys_returnsExactly6PastMonths() {
        val testDate = LocalDate.of(2026, 11, 15)
        val keys = MonthlyUsageArchiveStore.getAvailablePastMonthKeys(testDate)

        assertEquals(6, keys.size)
        assertEquals("2026-10", keys[0])
        assertEquals("2026-09", keys[1])
        assertEquals("2026-08", keys[2])
        assertEquals("2026-07", keys[3])
        assertEquals("2026-06", keys[4])
        assertEquals("2026-05", keys[5])
    }

    @Test
    fun monthlyArchiveSummary_bedtimeAndWakeupFormatting() {
        val summaryWithTimes = MonthlyArchiveSummary(
            yearMonth = "2026-10",
            totalPhoneMs = 36000000L,
            phoneDailyAvgMs = 1200000L,
            activeDays = 30,
            shortformTotalMs = 18000000L,
            shortformDailyAvgMs = 600000L,
            shortformSessionAvgMs = 300000L,
            workdayPhoneAvgMs = 1000000L,
            restdayPhoneAvgMs = 1800000L,
            workdayShortformAvgMs = 500000L,
            restdayShortformAvgMs = 900000L,
            workdayDaysCount = 22,
            restdayDaysCount = 8,
            avgBedtimeMinute = 23 * 60 + 45, // 23:45
            avgWakeupMinute = 7 * 60 + 30    // 07:30
        )

        assertEquals("23:45", summaryWithTimes.displayBedtime)
        assertEquals("07:30", summaryWithTimes.displayWakeup)
        assertEquals(22, summaryWithTimes.workdayDaysCount)
        assertEquals(8, summaryWithTimes.restdayDaysCount)

        val summaryWithoutTimes = summaryWithTimes.copy(
            avgBedtimeMinute = null,
            avgWakeupMinute = null
        )
        assertNull(summaryWithoutTimes.displayBedtime)
        assertNull(summaryWithoutTimes.displayWakeup)
    }

    @Test
    fun dailyArchiveRecord_creationAndDayTypes() {
        val workdayRecord = DailyArchiveRecord(
            date = "2026-10-15",
            totalPhoneMs = 7200000L,
            shortformMs = 1800000L,
            shortformSwipes = 45L,
            dayType = "WORKDAY",
            dayTypeReason = "목요일 / 평균 대비 사용량 보통"
        )

        val restdayRecord = DailyArchiveRecord(
            date = "2026-10-18",
            totalPhoneMs = 14400000L,
            shortformMs = 5400000L,
            shortformSwipes = 120L,
            dayType = "REST_DAY",
            dayTypeReason = "일요일 / 사용량 급증"
        )

        assertEquals("WORKDAY", workdayRecord.dayType)
        assertEquals("REST_DAY", restdayRecord.dayType)
        assertNotNull(workdayRecord.dayTypeReason)
        assertEquals(45L, workdayRecord.shortformSwipes)
    }
}
