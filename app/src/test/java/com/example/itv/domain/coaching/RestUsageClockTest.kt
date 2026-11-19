package com.example.itv.domain.coaching

import org.junit.Assert.*
import org.junit.Test

class RestUsageClockTest {

    @Test
    fun pureShortformAccumulatesFullWeight() {
        val clock = RestUsageClock(requiredRestMs = 15_000L)
        clock.observe(now = 0L, using = true, isShortform = true)
        for (i in 1..30) {
            clock.observe(now = i * 1000L, using = true, isShortform = true)
        }
        assertEquals(30_000L, clock.rawShortformMs)
        assertEquals(0L, clock.rawGeneralMs)
        assertEquals(30_000L, clock.usedMs)
    }

    @Test
    fun pureGeneralUsageAccumulatesOneThirdWeight() {
        val clock = RestUsageClock(requiredRestMs = 15_000L)
        clock.observe(now = 0L, using = true, isShortform = false)
        for (i in 1..90) {
            clock.observe(now = i * 1000L, using = true, isShortform = false)
        }
        assertEquals(0L, clock.rawShortformMs)
        assertEquals(90_000L, clock.rawGeneralMs)
        // 90초 일반 사용 -> 1:3 비율 적용 시 30초 누적
        assertEquals(30_000L, clock.usedMs)
    }

    @Test
    fun mixedShortformAndGeneralUsageAccumulateTogether() {
        val clock = RestUsageClock(requiredRestMs = 15_000L)
        // 1. 숏폼 15초 사용 (15초 가중치)
        clock.observe(now = 0L, using = true, isShortform = true)
        for (i in 1..15) {
            clock.observe(now = i * 1000L, using = true, isShortform = true)
        }
        assertEquals(15_000L, clock.rawShortformMs)
        assertEquals(15_000L, clock.usedMs)

        // 2. 다른 앱(일반 사용) 45초 사용 (45초 / 3 = 15초 환산 가중치)
        for (i in 16..60) {
            clock.observe(now = i * 1000L, using = true, isShortform = false)
        }
        assertEquals(15_000L, clock.rawShortformMs)
        assertEquals(45_000L, clock.rawGeneralMs)
        // 15초 + (45초 / 3) = 30초
        assertEquals(30_000L, clock.usedMs)
    }

    @Test
    fun sufficientRestResetsClock() {
        val clock = RestUsageClock(requiredRestMs = 15_000L)
        clock.observe(now = 0L, using = true, isShortform = true)
        for (i in 1..10) {
            clock.observe(now = i * 1000L, using = true, isShortform = true)
        }
        assertEquals(10_000L, clock.usedMs)

        // 15초 휴식 시작
        clock.observe(now = 11_000L, using = false, isShortform = false)
        for (i in 12..25) {
            val reset = clock.observe(now = i * 1000L, using = false, isShortform = false)
            assertFalse(reset)
        }
        // 15초 도달
        val reset = clock.observe(now = 26_000L, using = false, isShortform = false)
        assertTrue(reset)
        assertEquals(0L, clock.usedMs)
        assertEquals(0L, clock.rawShortformMs)
        assertEquals(0L, clock.rawGeneralMs)
    }

    @Test
    fun insufficientRestPreservesAccumulatedUsage() {
        val clock = RestUsageClock(requiredRestMs = 15_000L)
        clock.observe(now = 0L, using = true, isShortform = true)
        for (i in 1..10) {
            clock.observe(now = i * 1000L, using = true, isShortform = true)
        }
        assertEquals(10_000L, clock.usedMs)

        // 5초 휴식 후 다시 사용
        for (i in 11..15) {
            clock.observe(now = i * 1000L, using = false, isShortform = false)
        }
        clock.observe(now = 16_000L, using = true, isShortform = true)
        // 휴식 시간은 리셋되고 이전 사용량(10초)은 보존됨
        assertEquals(0L, clock.restMs)
        assertEquals(10_000L, clock.usedMs)
    }
}
