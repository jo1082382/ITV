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
}
