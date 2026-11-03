package com.example.itv
import com.example.itv.domain.records.NightRestTracker


import org.junit.Assert.*
import org.junit.Test

class NightRestTrackerTest {
    @Test fun briefUseBridgesRest() {
        val t = NightRestTracker()
        t.screenOff(0); t.confirmedUse(10_000); t.screenOff(69_999)
        t.confirmedUse(100_000)
        val row = t.snapshot(160_000).single()
        assertEquals(0L, row.start); assertEquals(100_000L, row.end)
        assertEquals(1, row.briefWakeCount)
    }
    @Test fun exactlyOneMinuteEndsAtUseStart() {
        val t = NightRestTracker()
        t.screenOff(0); t.confirmedUse(10_000); t.screenOff(70_000)
        val rows = t.snapshot(80_000)
        assertEquals(10_000L, rows.first().end)
        assertEquals(0, rows.first().briefWakeCount)
        assertEquals(70_000L, rows.last().start)
    }
    @Test fun alarmDoesNotCount() {
        val t = NightRestTracker()
        t.screenOff(0); t.advance(10_000); t.screenOff(90_000)
        assertEquals(0, t.snapshot(100_000).single().briefWakeCount)
    }
    @Test fun repeatedResumeDoesNotRestartTimer() {
        val t = NightRestTracker()
        t.screenOff(0); t.confirmedUse(10_000); t.confirmedUse(40_000)
        assertEquals(10_000L, t.snapshot(70_000).single().end)
    }
    @Test fun pendingBriefUseIsNotCompletedRest() {
        val t = NightRestTracker()
        t.screenOff(0); t.confirmedUse(10_000)
        assertNull(t.snapshot(69_999).single().end)
    }
    @Test fun restartDoesNotBridgeUnknownPeriod() {
        val t = NightRestTracker()
        t.screenOff(0); t.deviceBoundary(10_000)
        assertTrue(t.snapshot(100_000).isEmpty())
    }
}
