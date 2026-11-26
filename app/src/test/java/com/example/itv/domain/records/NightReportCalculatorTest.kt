package com.example.itv.domain.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class NightReportCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private fun wallMs(date: LocalDate, hour: Int, minute: Int = 0, second: Int = 0): Long =
        date.atTime(hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `opening ITV within 15 seconds of waking up from 6h sleep immediately resolves yesterday for stamping`() {
        val yesterday = LocalDate.of(2026, 11, 25)
        val today = LocalDate.of(2026, 11, 26)

        val sleepStart = wallMs(today, 0, 30, 0)
        val wakeAt = wallMs(today, 6, 58, 0)
        val openItvAt = wallMs(today, 6, 58, 15) // only 15s after unlock (< 60s buffer!)

        val events = listOf(
            UsageObservation(sleepStart, ObservationKind.SCREEN_NON_INTERACTIVE, null),
            UsageObservation(wakeAt, ObservationKind.SCREEN_INTERACTIVE, null),
            UsageObservation(wakeAt, ObservationKind.KEYGUARD_HIDDEN, null),
            UsageObservation(openItvAt, ObservationKind.ACTIVITY_RESUMED, "com.example.itv")
        )

        // Conservative background query still waits 60s
        val backgroundReport = calculateNightReport(events, days = 7, zone = zone, now = openItvAt, finalizeActiveWake = false)
        assertTrue(backgroundReport.segments.none { it.wakeDate == today })

        // Foreground ITV query immediately finalizes the 6h+ sleep so the morning stamp triggers right away!
        val foregroundReport = calculateNightReport(events, days = 7, zone = zone, now = openItvAt, finalizeActiveWake = true)
        val seg = foregroundReport.segments.firstOrNull { it.wakeDate == today }
        assertTrue("Expected immediate wake segment for today", seg != null)
        assertEquals(sleepStart, seg!!.start)
        assertEquals(wakeAt, seg.end)

        val resolved = CalendarDayCyclePolicy.resolveNightSleepForDate(
            date = yesterday,
            zone = zone,
            wakeConf = null,
            segments = foregroundReport.segments,
            candidates = foregroundReport.candidates,
            nextArchiveBedtimeMin = null,
            nextArchiveWakeupMin = null
        )
        assertTrue("Yesterday should finalize immediately upon opening ITV", CalendarDayCyclePolicy.isDateFinalized(
            date = yesterday,
            today = today,
            nowTime = LocalTime.of(6, 58),
            nightSleep = resolved,
            isDateStamped = false
        ))
    }

    @Test
    fun `fingerprint unlock where KEYGUARD_HIDDEN precedes SCREEN_INTERACTIVE is still detected`() {
        val today = LocalDate.of(2026, 11, 26)
        val sleepStart = wallMs(today, 0, 0, 0)
        val keyguardHiddenAt = wallMs(today, 6, 30, 0)
        val screenOnAt = keyguardHiddenAt + 50L
        val now = wallMs(today, 6, 32, 0)

        val events = listOf(
            UsageObservation(sleepStart, ObservationKind.SCREEN_NON_INTERACTIVE, null),
            UsageObservation(keyguardHiddenAt, ObservationKind.KEYGUARD_HIDDEN, null),
            UsageObservation(screenOnAt, ObservationKind.SCREEN_INTERACTIVE, null)
        )

        val report = calculateNightReport(events, days = 3, zone = zone, now = now)
        val seg = report.segments.firstOrNull { it.wakeDate == today }
        assertTrue("Expected sleep segment when KEYGUARD_HIDDEN precedes SCREEN_INTERACTIVE", seg != null)
        assertEquals(sleepStart, seg!!.start)
        assertEquals(screenOnAt, seg.end)
    }

    @Test
    fun `no lock screen user app activity wakes sleep while lockscreen alarm does not`() {
        val today = LocalDate.of(2026, 11, 26)
        val sleepStart = wallMs(today, 0, 0, 0)
        val alarmAt = wallMs(today, 5, 0, 0)
        val alarmOffAt = wallMs(today, 5, 1, 30)
        val realWakeAt = wallMs(today, 7, 0, 0)
        val now = wallMs(today, 7, 5, 0)

        val events = listOf(
            UsageObservation(sleepStart, ObservationKind.SCREEN_NON_INTERACTIVE, null),
            // Alarm rings for 90s with no KEYGUARD_SHOWN -> must NOT split sleep!
            UsageObservation(alarmAt, ObservationKind.SCREEN_INTERACTIVE, null),
            UsageObservation(alarmAt + 500L, ObservationKind.ACTIVITY_RESUMED, "com.sec.android.app.clockpackage"),
            UsageObservation(alarmOffAt, ObservationKind.SCREEN_NON_INTERACTIVE, null),
            // Real wake-up without lock screen: user opens KakaoTalk
            UsageObservation(realWakeAt, ObservationKind.SCREEN_INTERACTIVE, null),
            UsageObservation(realWakeAt + 1_000L, ObservationKind.ACTIVITY_RESUMED, "com.kakao.talk")
        )

        val report = calculateNightReport(events, days = 3, zone = zone, now = now)
        val seg = report.segments.firstOrNull { it.wakeDate == today }
        assertTrue("Expected full sleep segment ending at real user app wake-up", seg != null)
        assertEquals(sleepStart, seg!!.start)
        assertEquals(realWakeAt + 1_000L, seg.end)
    }

    @Test
    fun `fallback live sleep fills today only when UsageEvents missed sleep`() {
        val today = LocalDate.of(2026, 11, 26)
        val fbStart = wallMs(today, 0, 20, 0)
        val fbEnd = wallMs(today, 6, 50, 0)
        val now = wallMs(today, 6, 51, 0)

        val report = calculateNightReport(
            events = emptyList(),
            days = 3,
            zone = zone,
            now = now,
            finalizeActiveWake = true,
            fallbackLiveSleep = fbStart to fbEnd
        )
        val seg = report.segments.firstOrNull { it.wakeDate == today }
        assertTrue("Expected fallback live sleep segment for today", seg != null)
        assertEquals(fbStart, seg!!.start)
        assertEquals(fbEnd, seg.end)
        assertFalse(report.candidates.isEmpty())
    }
}
