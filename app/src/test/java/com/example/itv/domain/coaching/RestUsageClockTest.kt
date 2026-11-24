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

    @Test
    fun pausedOwnAppPreservesUsageWithoutIncrementingOrResetting() {
        val clock = RestUsageClock(requiredRestMs = 15_000L)
        // 숏폼 10초 사용
        clock.observe(now = 0L, using = true, isShortform = true)
        for (i in 1..10) {
            clock.observe(now = i * 1000L, using = true, isShortform = true)
        }
        assertEquals(10_000L, clock.usedMs)

        // 우리 앱(ITV) 20초 체류 (paused = true): 사용량도 늘지 않고, 휴식으로 초기화되지도 않아야 함
        for (i in 11..30) {
            val reset = clock.observe(now = i * 1000L, using = true, isShortform = false, paused = true)
            assertFalse(reset)
        }
        assertEquals(10_000L, clock.usedMs)
        assertEquals(0L, clock.restMs)

        // 일반 앱으로 복귀해 9초 사용 -> 1:3 비율로 +3초(총 13초)만 증가해야 함
        clock.observe(now = 31_000L, using = true, isShortform = false, paused = false)
        for (i in 32..40) {
            clock.observe(now = i * 1000L, using = true, isShortform = false, paused = false)
        }
        assertEquals(13_000L, clock.usedMs)
    }

    @Test
    fun threeTierWeightingAccumulatesShortformFeedAndGeneralAccurately() {
        val clock = RestUsageClock(requiredRestMs = 15_000L)
        // 1. 숏폼 10초 (1:1 -> 10초)
        clock.observe(now = 0L, using = true, weightTier = UsageWeightTier.FULL_1_TO_1)
        for (i in 1..10) {
            clock.observe(now = i * 1000L, using = true, weightTier = UsageWeightTier.FULL_1_TO_1)
        }
        // 2. 피드/SNS·게임 30초 (1:2 -> 15초)
        for (i in 11..40) {
            clock.observe(now = i * 1000L, using = true, weightTier = UsageWeightTier.MID_1_TO_2)
        }
        // 3. 롱폼/웹/일반 앱 30초 (1:3 -> 10초)
        for (i in 41..70) {
            clock.observe(now = i * 1000L, using = true, weightTier = UsageWeightTier.LOW_1_TO_3)
        }
        assertEquals(10_000L, clock.rawShortformMs)
        assertEquals(30_000L, clock.rawMidWeightMs)
        assertEquals(30_000L, clock.rawLowWeightMs)
        assertEquals(60_000L, clock.rawGeneralMs)
        assertEquals(35_000L, clock.usedMs)
    }
}
