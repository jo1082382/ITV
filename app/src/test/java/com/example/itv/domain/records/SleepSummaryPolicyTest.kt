package com.example.itv.domain.records

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SleepSummaryPolicyTest {

    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `estimateBedtimeWindow returns null when empty`() {
        val result = SleepSummaryPolicy.estimateBedtimeWindow(
            confirmations = emptyMap(),
            candidates = emptyList(),
            zone = zone
        )
        assertNull(result)
    }

    @Test
    fun `estimateBedtimeWindow automatically reflects single candidate when unconfirmed`() {
        val date1 = LocalDate.of(2026, 11, 20)
        // 23:00 to 07:00 next day (8 hours = 480 mins)
        val start1 = ZonedDateTime.of(2026, 11, 19, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end1 = ZonedDateTime.of(2026, 11, 20, 7, 0, 0, 0, zone).toInstant().toEpochMilli()

        val singleCandidate = NightSegment(
            wakeDate = date1,
            start = start1,
            end = end1,
            minutes = 480.0,
            briefWakeCount = 0
        )

        val result = SleepSummaryPolicy.estimateBedtimeWindow(
            confirmations = emptyMap(),
            candidates = listOf(singleCandidate),
            zone = zone
        )

        assertNotNull(result)
        assertEquals(1, result?.validCount)
        assertEquals(23, result?.avgStartHour)
        assertEquals(0, result?.avgStartMinute)
        assertEquals(7, result?.avgEndHour)
        assertEquals(0, result?.avgEndMinute)
        assertEquals(480L, result?.avgDurationMinutes)
    }

    @Test
    fun `estimateBedtimeWindow ignores multiple candidates when unconfirmed`() {
        val date1 = LocalDate.of(2026, 11, 20)
        val start1 = ZonedDateTime.of(2026, 11, 19, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end1 = ZonedDateTime.of(2026, 11, 20, 3, 0, 0, 0, zone).toInstant().toEpochMilli()

        val start2 = ZonedDateTime.of(2026, 11, 20, 4, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end2 = ZonedDateTime.of(2026, 11, 20, 8, 0, 0, 0, zone).toInstant().toEpochMilli()

        val cand1 = NightSegment(wakeDate = date1, start = start1, end = end1, minutes = 240.0, briefWakeCount = 0)
        val cand2 = NightSegment(wakeDate = date1, start = start2, end = end2, minutes = 240.0, briefWakeCount = 0)

        val result = SleepSummaryPolicy.estimateBedtimeWindow(
            confirmations = emptyMap(),
            candidates = listOf(cand1, cand2),
            zone = zone
        )

        assertNull(result)
    }

    @Test
    fun `estimateBedtimeWindow uses user confirmation when available`() {
        val date1 = LocalDate.of(2026, 11, 20)
        val start1 = ZonedDateTime.of(2026, 11, 19, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end1 = ZonedDateTime.of(2026, 11, 20, 6, 0, 0, 0, zone).toInstant().toEpochMilli()

        val cand1 = NightSegment(wakeDate = date1, start = start1, end = end1, minutes = 480.0, briefWakeCount = 0)

        // User confirmed a different custom time: 23:30 ~ 07:30
        val userStart = ZonedDateTime.of(2026, 11, 19, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val userEnd = ZonedDateTime.of(2026, 11, 20, 7, 30, 0, 0, zone).toInstant().toEpochMilli()

        val confirmations = mapOf(
            date1.toString() to SleepConfirmation(
                date = date1.toString(),
                zoneId = zone.id,
                choice = "SLEEP",
                startMs = userStart,
                endMs = userEnd,
                origin = "USER_PICK",
                updatedAt = System.currentTimeMillis()
            )
        )

        val result = SleepSummaryPolicy.estimateBedtimeWindow(
            confirmations = confirmations,
            candidates = listOf(cand1),
            zone = zone
        )

        assertNotNull(result)
        assertEquals(1, result?.validCount)
        assertEquals(23, result?.avgStartHour)
        assertEquals(30, result?.avgStartMinute)
        assertEquals(7, result?.avgEndHour)
        assertEquals(30, result?.avgEndMinute)
        assertEquals(480L, result?.avgDurationMinutes)
    }

    @Test
    fun `estimateBedtimeWindow excludes NOT_SLEEP confirmation even with single candidate`() {
        val date1 = LocalDate.of(2026, 11, 20)
        val start1 = ZonedDateTime.of(2026, 11, 19, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end1 = ZonedDateTime.of(2026, 11, 20, 7, 0, 0, 0, zone).toInstant().toEpochMilli()

        val cand1 = NightSegment(wakeDate = date1, start = start1, end = end1, minutes = 480.0, briefWakeCount = 0)

        val confirmations = mapOf(
            date1.toString() to SleepConfirmation(
                date = date1.toString(),
                zoneId = zone.id,
                choice = "NOT_SLEEP",
                startMs = null,
                endMs = null,
                origin = "USER_PICK",
                updatedAt = System.currentTimeMillis()
            )
        )

        val result = SleepSummaryPolicy.estimateBedtimeWindow(
            confirmations = confirmations,
            candidates = listOf(cand1),
            zone = zone
        )

        assertNull(result)
    }

    @Test
    fun `resolveTargetWakeDayOfWeek correctly targets next morning after noon and same morning before noon`() {
        // 월요일 22:00 -> 화요일 아침 기상 목표
        val mondayNight = ZonedDateTime.of(2026, 11, 16, 22, 0, 0, 0, zone) // Monday
        assertEquals(java.time.DayOfWeek.TUESDAY, SleepSummaryPolicy.resolveTargetWakeDayOfWeek(mondayNight))

        // 화요일 02:00 (월요일 밤 늦게까지 깨어있는 심야) -> 화요일 아침 기상 목표
        val tuesdayLateNight = ZonedDateTime.of(2026, 11, 17, 2, 0, 0, 0, zone) // Tuesday 02:00
        assertEquals(java.time.DayOfWeek.TUESDAY, SleepSummaryPolicy.resolveTargetWakeDayOfWeek(tuesdayLateNight))

        // 화요일 23:00 -> 수요일 아침 기상 목표
        val tuesdayNight = ZonedDateTime.of(2026, 11, 17, 23, 0, 0, 0, zone) // Tuesday 23:00
        assertEquals(java.time.DayOfWeek.WEDNESDAY, SleepSummaryPolicy.resolveTargetWakeDayOfWeek(tuesdayNight))
    }

    @Test
    fun `estimateWeeklySleepProfile and getAdaptiveBedtimeTarget adapt to Tuesday early and Wednesday late wake`() {
        // 기록 1: 화요일 기상 (월요일 23:00 취침 ~ 화요일 07:00 기상)
        val tuesdayDate = LocalDate.of(2026, 11, 17) // Tuesday
        val startTue = ZonedDateTime.of(2026, 11, 16, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val endTue = ZonedDateTime.of(2026, 11, 17, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        val candTue = NightSegment(wakeDate = tuesdayDate, start = startTue, end = endTue, minutes = 480.0, briefWakeCount = 0)

        // 기록 2: 수요일 기상 (화요일 심야 03:00 취침 ~ 수요일 13:00 기상)
        val wednesdayDate = LocalDate.of(2026, 11, 18) // Wednesday
        val startWed = ZonedDateTime.of(2026, 11, 18, 3, 0, 0, 0, zone).toInstant().toEpochMilli()
        val endWed = ZonedDateTime.of(2026, 11, 18, 13, 0, 0, 0, zone).toInstant().toEpochMilli()
        val candWed = NightSegment(wakeDate = wednesdayDate, start = startWed, end = endWed, minutes = 600.0, briefWakeCount = 0)

        val profile = SleepSummaryPolicy.estimateWeeklySleepProfile(
            confirmations = emptyMap(),
            candidates = listOf(candTue, candWed),
            zone = zone
        )

        assertNotNull(profile)
        assertEquals(2, profile?.totalValidDays)

        val tueProfile = profile?.dayProfiles?.get(java.time.DayOfWeek.TUESDAY)
        assertNotNull(tueProfile)
        assertEquals(7, tueProfile?.avgWakeHour)
        assertEquals(23, tueProfile?.avgStartHour)

        val wedProfile = profile?.dayProfiles?.get(java.time.DayOfWeek.WEDNESDAY)
        assertNotNull(wedProfile)
        assertEquals(13, wedProfile?.avgWakeHour)
        assertEquals(3, wedProfile?.avgStartHour)

        // 시나리오 1: 월요일 밤 22:00 -> 내일(화요일) 07:00 기상 패턴에 맞춰 23:00 취침을 타겟팅
        val monday22 = ZonedDateTime.of(2026, 11, 16, 22, 0, 0, 0, zone)
        val targetMon = SleepSummaryPolicy.getAdaptiveBedtimeTarget(profile, monday22)
        assertEquals(23, targetMon.targetHour)
        assertEquals(0, targetMon.targetMinute)
        assertEquals(7, targetMon.expectedWakeHour)
        assertTrue(targetMon.isDayOfWeekSpecific)
        assertTrue(targetMon.sourceDescription.contains("내일(화)"))

        // 시나리오 2: 화요일 밤 22:00 -> 내일(수요일) 13:00 기상 패턴에 맞춰 03:00 취침을 타겟팅
        val tuesday22 = ZonedDateTime.of(2026, 11, 17, 22, 0, 0, 0, zone)
        val targetTue = SleepSummaryPolicy.getAdaptiveBedtimeTarget(profile, tuesday22)
        assertEquals(3, targetTue.targetHour)
        assertEquals(0, targetTue.targetMinute)
        assertEquals(13, targetTue.expectedWakeHour)
        assertTrue(targetTue.isDayOfWeekSpecific)
        assertTrue(targetTue.sourceDescription.contains("내일(수)"))

        // 시나리오 3: 일요일 밤 22:00 -> 월요일 기상 기록이 없으므로 전체 평균으로 자동 fallback
        val sunday22 = ZonedDateTime.of(2026, 11, 15, 22, 0, 0, 0, zone)
        val targetSun = SleepSummaryPolicy.getAdaptiveBedtimeTarget(profile, sunday22)
        assertFalse(targetSun.isDayOfWeekSpecific)
        assertTrue(targetSun.sourceDescription.contains("전체 2일 평균"))
    }
}
