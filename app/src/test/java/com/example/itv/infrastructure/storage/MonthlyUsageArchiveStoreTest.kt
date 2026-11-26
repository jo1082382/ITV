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

    @Test
    fun calculateDailyHourlyBreakdowns_splitsMultiDayAndMidnightEventsAndMergesWithoutDowngrading() {
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val day1 = LocalDate.of(2026, 11, 20)
        val day2 = LocalDate.of(2026, 11, 21)
        val start = day1.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day2.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val t1Start = day1.atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val t1End = day2.atTime(0, 20).atZone(zone).toInstant().toEpochMilli()
        val t2Start = day2.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val t2End = day2.atTime(10, 45).atZone(zone).toInstant().toEpochMilli()

        val events = listOf(
            com.example.itv.domain.records.UsageObservation(
                t1Start,
                com.example.itv.domain.records.ObservationKind.ACTIVITY_RESUMED,
                "com.google.android.youtube"
            ),
            com.example.itv.domain.records.UsageObservation(
                t1End,
                com.example.itv.domain.records.ObservationKind.SCREEN_NON_INTERACTIVE,
                null
            ),
            com.example.itv.domain.records.UsageObservation(
                t2Start,
                com.example.itv.domain.records.ObservationKind.ACTIVITY_RESUMED,
                "com.instagram.android"
            ),
            com.example.itv.domain.records.UsageObservation(
                t2End,
                com.example.itv.domain.records.ObservationKind.ACTIVITY_PAUSED,
                "com.instagram.android"
            )
        )

        val breakdowns = com.example.itv.domain.records.calculateDailyHourlyBreakdowns(events, zone, start, end)
        val b1 = breakdowns[day1]!!
        val b2 = breakdowns[day2]!!

        assertEquals(30.0, b1.hourlyMinutes[23], 0.01)
        assertEquals(30.0, b1.totalMinutes, 0.01)
        assertEquals(20.0, b2.hourlyMinutes[0], 0.01)
        assertEquals(45.0, b2.hourlyMinutes[10], 0.01)
        assertEquals(65.0, b2.totalMinutes, 0.01)

        // Merge into archive: day1 already has a richer 120-min archived distribution -> must NOT be downgraded!
        val existingDay1 = DailyArchiveRecord(
            date = day1.toString(),
            totalPhoneMs = 120 * 60_000L,
            shortformMs = 0L,
            hourlyPhoneMinutes = List(24) { if (it == 20) 120.0 else 0.0 },
            topApps = listOf(ArchivedAppUsage("com.kakao.talk", "KakaoTalk", 120.0))
        )
        val map = mutableMapOf(day1.toString() to existingDay1)

        val changed = MonthlyUsageArchiveStore.mergeHourlyBreakdownsIntoMap(
            updatedDailies = map,
            breakdowns = breakdowns,
            cutoffDate = day2.minusMonths(6),
            today = day2.plusDays(1),
            boundaryDate = null,
            labelProvider = { pkg -> if (pkg.contains("instagram")) "Instagram" else "YouTube" }
        )

        assertEquals(true, changed)
        // day1 preserved its richer 120m hourly array
        assertEquals(120.0, map[day1.toString()]!!.hourlyPhoneMinutes[20], 0.01)
        // day2 got backfilled automatically with 24h distribution & top apps
        val savedDay2 = map[day2.toString()]!!
        assertEquals(24, savedDay2.hourlyPhoneMinutes.size)
        assertEquals(20.0, savedDay2.hourlyPhoneMinutes[0], 0.01)
        assertEquals(45.0, savedDay2.hourlyPhoneMinutes[10], 0.01)
        assertEquals("Instagram", savedDay2.topApps.first().label)
    }
}
