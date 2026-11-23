package com.example.itv.domain.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CalendarDayCyclePolicyTest {

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private fun wallMs(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `unstarted sleep past midnight rolls early morning into yesterday and defers today`() {
        val day22 = LocalDate.of(2026, 11, 22)
        val day23 = LocalDate.of(2026, 11, 23)
        val nowWall = wallMs(day23, 1, 30)

        val day21Sleep = ResolvedNightSleep(
            bedtimeStartWallMs = wallMs(day22, 0, 30),
            sleepEndWallMs = wallMs(day22, 8, 0),
            hasWakeupResolved = true
        )
        val day22Sleep = ResolvedNightSleep() // Sleep has NOT started/been measured yet

        val is22Finalized = CalendarDayCyclePolicy.isDateFinalized(
            date = day22,
            today = day23,
            nowTime = LocalTime.of(1, 30),
            nightSleep = day22Sleep,
            isDateStamped = false
        )
        assertFalse("22nd should stay IN_PROGRESS when sleep has not started", is22Finalized)

        val defer23 = CalendarDayCyclePolicy.shouldDeferTodayCalculation(
            yesterdayHasData = true,
            isYesterdayFinalized = is22Finalized,
            isYesterdayStamped = false
        )
        assertTrue("23rd must NOT start calculating before 22nd is finalized and stamped", defer23)

        val bounds22 = CalendarDayCyclePolicy.resolveDayCycleBounds(
            date = day22,
            today = day23,
            zone = zone,
            nowWallMs = nowWall,
            prevNightSleep = day21Sleep,
            nightSleep = day22Sleep,
            isDateFinalized = is22Finalized
        )
        assertEquals(wallMs(day22, 8, 0), bounds22.dayStartWallMs)
        assertEquals(nowWall, bounds22.dayEndWallMs)

        val preSleep22 = CalendarDayCyclePolicy.resolvePreSleepWindow(
            date = day22,
            today = day23,
            zone = zone,
            nowWallMs = nowWall,
            cycleBounds = bounds22,
            bedtimeStartWallMs = null,
            isDateFinalized = is22Finalized
        )
        assertNotNull(preSleep22)
        assertEquals(wallMs(day22, 23, 0), preSleep22!!.startMs)
        assertEquals(nowWall, preSleep22.endMs)

        // Session on 23rd 00:15..01:00 (45 min) is attributed to 22nd's pre-sleep usage
        val sessions = listOf(wallMs(day23, 0, 15) to wallMs(day23, 1, 0))
        val preSleepMin22 = CalendarDayCyclePolicy.computeIntervalsOverlapMinutes(sessions, preSleep22)
        assertEquals(45.0, preSleepMin22, 0.001)
    }

    @Test
    fun `unstarted sleep past 05_00 AM still stays in yesterday until measured or noon fallback`() {
        val day22 = LocalDate.of(2026, 11, 22)
        val day23 = LocalDate.of(2026, 11, 23)
        val noSleep = ResolvedNightSleep()

        assertFalse(
            "06:00 AM with no sleep measured yet must still continue 22nd",
            CalendarDayCyclePolicy.isDateFinalized(day22, day23, LocalTime.of(6, 0), noSleep, isDateStamped = false)
        )
        assertTrue(
            "12:00 noon fallback prevents permanent deadlock on 24h all-nighter",
            CalendarDayCyclePolicy.isDateFinalized(day22, day23, LocalTime.of(12, 0), noSleep, isDateStamped = false)
        )
    }

    @Test
    fun `today waits until yesterday is BOTH finalized AND stamped, then excludes 23rd early morning`() {
        val day22 = LocalDate.of(2026, 11, 22)
        val day23 = LocalDate.of(2026, 11, 23)
        val nowMorning = wallMs(day23, 9, 30)

        val day22Sleep = CalendarDayCyclePolicy.resolveNightSleepForDate(
            date = day22,
            zone = zone,
            wakeConf = null,
            segments = listOf(
                NightSegment(
                    wakeDate = day23,
                    start = wallMs(day23, 2, 30),
                    end = wallMs(day23, 9, 0),
                    minutes = 390.0
                )
            ),
            candidates = emptyList(),
            nextArchiveBedtimeMin = null,
            nextArchiveWakeupMin = null
        )

        val is22Finalized = CalendarDayCyclePolicy.isDateFinalized(
            date = day22,
            today = day23,
            nowTime = LocalTime.of(9, 30),
            nightSleep = day22Sleep,
            isDateStamped = false
        )
        assertTrue("22nd finalizes once wake-up on 23rd is measured", is22Finalized)

        // Before stamping 22nd: 23rd STILL must not calculate!
        assertTrue(
            "23rd must still wait until 22nd is stamped",
            CalendarDayCyclePolicy.shouldDeferTodayCalculation(
                yesterdayHasData = true,
                isYesterdayFinalized = true,
                isYesterdayStamped = false
            )
        )

        // After stamping 22nd: 23rd starts cleanly from 23rd 09:00 wake-up!
        assertFalse(
            CalendarDayCyclePolicy.shouldDeferTodayCalculation(
                yesterdayHasData = true,
                isYesterdayFinalized = true,
                isYesterdayStamped = true
            )
        )

        val bounds23 = CalendarDayCyclePolicy.resolveDayCycleBounds(
            date = day23,
            today = day23,
            zone = zone,
            nowWallMs = nowMorning,
            prevNightSleep = day22Sleep,
            nightSleep = ResolvedNightSleep(),
            isDateFinalized = false
        )
        assertEquals(wallMs(day23, 9, 0), bounds23.dayStartWallMs)
        assertEquals(nowMorning, bounds23.dayEndWallMs)

        // 23rd's daytime report MUST exclude 00:00..02:59 (which belonged to 22nd's night!)
        val reportHourly = MutableList(24) { 0.0 }
        reportHourly[0] = 40.0 // 23rd 00:00..00:59 (22nd's night!)
        reportHourly[1] = 50.0 // 23rd 01:00..01:59 (22nd's night!)
        reportHourly[9] = 20.0 // 23rd 09:00..09:59 (half inside [09:00, 09:30])
        val day23ReportMin = CalendarDayCyclePolicy.computeReportMinutesInWindow(
            date = day23,
            hourlyMinutes = reportHourly,
            window = TimeWindowMs(bounds23.dayStartWallMs, bounds23.dayEndWallMs),
            zone = zone
        )
        assertEquals(10.0, day23ReportMin, 0.001)

        // Before 23:00 on 23rd, 23rd's pre-sleep window has not started (null, never 00:00..04:59!)
        val preSleep23Morning = CalendarDayCyclePolicy.resolvePreSleepWindow(
            date = day23,
            today = day23,
            zone = zone,
            nowWallMs = nowMorning,
            cycleBounds = bounds23,
            bedtimeStartWallMs = null,
            isDateFinalized = false
        )
        assertNull("23rd pre-sleep window must be null before 23:00", preSleep23Morning)
    }

    @Test
    fun `previous dates never double-count early morning usage across 22nd, 23rd, and 24th`() {
        val day21 = LocalDate.of(2026, 11, 21)
        val day22 = LocalDate.of(2026, 11, 22)
        val day23 = LocalDate.of(2026, 11, 23)
        val day24 = LocalDate.of(2026, 11, 24)
        val today = LocalDate.of(2026, 11, 25)
        val nowWall = wallMs(today, 10, 0)

        // Scenario:
        // - 21st night: no sleep detected
        // - 22nd night: user stayed awake into 23rd 00:30..02:00 (90 min), NO sleep detected on 23rd morning!
        // - 23rd night: user stayed awake into 24th 00:30..01:30 (60 min), NO sleep detected on 24th morning!
        val noSleep = ResolvedNightSleep()

        val bounds22 = CalendarDayCyclePolicy.resolveDayCycleBounds(
            date = day22, today = today, zone = zone, nowWallMs = nowWall,
            prevNightSleep = noSleep, nightSleep = noSleep, isDateFinalized = true
        )
        val bounds23 = CalendarDayCyclePolicy.resolveDayCycleBounds(
            date = day23, today = today, zone = zone, nowWallMs = nowWall,
            prevNightSleep = noSleep, nightSleep = noSleep, isDateFinalized = true
        )

        // Partition invariant: 22nd ends exactly where 23rd begins (23rd 05:00)
        assertEquals(bounds22.dayEndWallMs, bounds23.dayStartWallMs)
        assertEquals(wallMs(day23, 5, 0), bounds23.dayStartWallMs)
        assertEquals(wallMs(day24, 5, 0), bounds23.dayEndWallMs)

        val preSleep22 = CalendarDayCyclePolicy.resolvePreSleepWindow(
            date = day22, today = today, zone = zone, nowWallMs = nowWall,
            cycleBounds = bounds22, bedtimeStartWallMs = null, isDateFinalized = true
        )!!
        val preSleep23 = CalendarDayCyclePolicy.resolvePreSleepWindow(
            date = day23, today = today, zone = zone, nowWallMs = nowWall,
            cycleBounds = bounds23, bedtimeStartWallMs = null, isDateFinalized = true
        )!!

        // Combined sessions across 22nd, 23rd, 24th early mornings
        val allSessions = listOf(
            wallMs(day22, 1, 0) to wallMs(day22, 2, 0),   // 60m on 22nd early morning -> belongs ONLY to 21st!
            wallMs(day23, 0, 30) to wallMs(day23, 2, 0),  // 90m on 23rd early morning -> belongs ONLY to 22nd!
            wallMs(day24, 0, 30) to wallMs(day24, 1, 30)  // 60m on 24th early morning -> belongs ONLY to 23rd!
        )

        val preSleepMin22 = CalendarDayCyclePolicy.computeIntervalsOverlapMinutes(allSessions, preSleep22)
        val preSleepMin23 = CalendarDayCyclePolicy.computeIntervalsOverlapMinutes(allSessions, preSleep23)

        assertEquals("22nd gets ONLY 23rd early morning (90m), NOT 22nd early morning", 90.0, preSleepMin22, 0.001)
        assertEquals("23rd gets ONLY 24th early morning (60m), NEVER double-counting 23rd early morning!", 60.0, preSleepMin23, 0.001)

        // Verify hourly shortform buckets also never double-count
        val sfBuckets = listOf(
            HourlyUsageBucket(day22, 1, 30 * 60_000L), // 22nd 01:00 -> 21st's night
            HourlyUsageBucket(day23, 1, 25 * 60_000L), // 23rd 01:00 -> 22nd's night
            HourlyUsageBucket(day24, 1, 15 * 60_000L)  // 24th 01:00 -> 23rd's night
        )
        val sfPre22 = CalendarDayCyclePolicy.computeHourlyBucketsOverlapMs(sfBuckets, preSleep22, zone)
        val sfPre23 = CalendarDayCyclePolicy.computeHourlyBucketsOverlapMs(sfBuckets, preSleep23, zone)
        assertEquals(25 * 60_000L, sfPre22)
        assertEquals(15 * 60_000L, sfPre23)

        // Also check 21st gets the 22nd 01:00 bucket cleanly
        val bounds21 = CalendarDayCyclePolicy.resolveDayCycleBounds(
            date = day21, today = today, zone = zone, nowWallMs = nowWall,
            prevNightSleep = noSleep, nightSleep = noSleep, isDateFinalized = true
        )
        val preSleep21 = CalendarDayCyclePolicy.resolvePreSleepWindow(
            date = day21, today = today, zone = zone, nowWallMs = nowWall,
            cycleBounds = bounds21, bedtimeStartWallMs = null, isDateFinalized = true
        )!!
        assertEquals(30 * 60_000L, CalendarDayCyclePolicy.computeHourlyBucketsOverlapMs(sfBuckets, preSleep21, zone))
    }

    @Test
    fun `explicit NOT_SLEEP confirmation does not fall back to automatic wake segment`() {
        val day22 = LocalDate.of(2026, 11, 22)
        val day23 = LocalDate.of(2026, 11, 23)

        val resolved = CalendarDayCyclePolicy.resolveNightSleepForDate(
            date = day22,
            zone = zone,
            wakeConf = SleepConfirmation(
                date = day23.toString(),
                zoneId = zone.id,
                choice = "NOT_SLEEP",
                startMs = null,
                endMs = null,
                origin = "user",
                updatedAt = wallMs(day23, 9, 0)
            ),
            segments = listOf(
                NightSegment(day23, wallMs(day23, 2, 0), wallMs(day23, 8, 0), 360.0)
            ),
            candidates = emptyList(),
            nextArchiveBedtimeMin = 120,
            nextArchiveWakeupMin = 480
        )

        assertTrue(resolved.hasExplicitNoSleep)
        assertTrue(resolved.hasWakeupResolved)
        assertNull(resolved.bedtimeStartWallMs)
        assertNull(resolved.sleepEndWallMs)
    }

    @Test
    fun `brand new install with empty yesterday does not block today`() {
        assertFalse(
            CalendarDayCyclePolicy.shouldDeferTodayCalculation(
                yesterdayHasData = false,
                isYesterdayFinalized = false,
                isYesterdayStamped = false
            )
        )
    }

    @Test
    fun `mergeArchivedSleepData backfills pre-retention archive dates without duplicating live or NOT_SLEEP dates`() {
        val nov10 = LocalDate.of(2026, 11, 10)
        val nov11 = LocalDate.of(2026, 11, 11)
        val nov14 = LocalDate.of(2026, 11, 14)

        val liveSeg = NightSegment(
            wakeDate = nov14,
            start = wallMs(nov14, 1, 0),
            end = wallMs(nov14, 8, 0),
            minutes = 420.0,
            briefWakeCount = 2
        )

        val merged = CalendarDayCyclePolicy.mergeArchivedSleepData(
            liveSegments = listOf(liveSeg),
            liveCandidates = listOf(liveSeg),
            archivedRecords = listOf(
                ArchivedSleepRecord(wakeDate = nov10, bedtimeMinute = 23 * 60 + 30, wakeupMinute = 7 * 60),
                ArchivedSleepRecord(wakeDate = nov11, bedtimeMinute = 1 * 60, wakeupMinute = 8 * 60),
                ArchivedSleepRecord(wakeDate = nov14, bedtimeMinute = 0 * 60 + 30, wakeupMinute = 7 * 60 + 30)
            ),
            confirmations = mapOf(
                nov11.toString() to SleepConfirmation(
                    date = nov11.toString(),
                    zoneId = zone.id,
                    choice = "NOT_SLEEP",
                    startMs = null,
                    endMs = null,
                    origin = "USER",
                    updatedAt = wallMs(nov11, 9, 0)
                )
            ),
            zone = zone
        )

        // Nov 14 keeps the live OS segment (briefWakeCount = 2), Nov 11 is skipped (NOT_SLEEP), Nov 10 is backfilled!
        assertEquals(2, merged.segments.size)
        val seg14 = merged.segments.first { it.wakeDate == nov14 }
        assertEquals(2, seg14.briefWakeCount)

        val seg10 = merged.segments.first { it.wakeDate == nov10 }
        assertEquals(wallMs(nov10.minusDays(1), 23, 30), seg10.start)
        assertEquals(wallMs(nov10, 7, 0), seg10.end)
        assertEquals(450.0, seg10.minutes, 0.001)

        // Weekly profile & bedtime estimate now reflect BOTH Nov 10 (archived) and Nov 14 (live)!
        val estimate = SleepSummaryPolicy.estimateBedtimeWindow(
            confirmations = emptyMap(),
            candidates = merged.candidates,
            zone = zone
        )
        assertNotNull(estimate)
        assertEquals(2, estimate!!.validCount)
    }
}
