package com.example.itv.domain.records

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ShortformIntervalCalculatorTest {
    private val wall = Instant.parse("2026-11-02T03:00:00Z").toEpochMilli()
    private fun time(e:Long, w:Long=wall) = ShortformObservationTime(e,w,"Asia/Seoul")
    @Test fun entryHasZeroDuration() {
        val r=ShortformIntervalCalculator.evaluate(ShortformClock(),true,time(1000))
        assertEquals(0L,r.contributions.sumOf { it.ms }); assertTrue(r.changed)
    }
    @Test fun validIntervalCountsElapsed() {
        val r=ShortformIntervalCalculator.evaluate(ShortformClock(true,1000,wall),true,time(2000,wall+1000))
        assertEquals(1000L,r.contributions.sumOf { it.ms })
    }
    @Test fun gapNotCounted() {
        val r=ShortformIntervalCalculator.evaluate(ShortformClock(true,1000,wall),true,time(2501,wall+1501))
        assertEquals(0L,r.contributions.sumOf { it.ms })
    }
    @Test fun invalidObservationBreaksContinuity() {
        val r=ShortformIntervalCalculator.evaluate(ShortformClock(true,1000,wall),false,time(2000,wall+1000))
        assertTrue(r.contributions.isEmpty()); assertFalse(r.clock.valid)
    }
    @Test fun midnightSplit() {
        val midnight=Instant.parse("2026-11-02T15:00:00Z").toEpochMilli()
        val r=ShortformIntervalCalculator.evaluate(ShortformClock(true,1000,midnight-500),true,time(2000,midnight+500))
        assertEquals(500L,r.contributions.filter { it.date=="2026-11-02" }.sumOf { it.ms })
        assertEquals(500L,r.contributions.filter { it.date=="2026-11-03" }.sumOf { it.ms })
    }
    @Test fun wallJumpNotCounted() {
        val r=ShortformIntervalCalculator.evaluate(ShortformClock(true,1000,wall),true,time(2000,wall+5000))
        assertEquals(0L,r.contributions.sumOf { it.ms })
    }
}
