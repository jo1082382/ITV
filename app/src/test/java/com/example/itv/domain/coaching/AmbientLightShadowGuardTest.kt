package com.example.itv.domain.coaching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientLightShadowGuardTest {

    @Test
    fun headShadowAfterSteadyBrightRoomIsFlaggedAsLikelyShadow() {
        val guard = AmbientLightShadowGuard()
        // Android TYPE_LIGHT(ON_CHANGE): 밝은 방에서 한 번만 150 lx 보고되고 10초간 변화 없음
        guard.record(nowMs = 1_000L, lux = 150f, isOccluded = false)
        // 사용자가 머리로 천장 조명을 가려 10 lx로 급락
        guard.record(nowMs = 11_000L, lux = 10f, isOccluded = false)

        val result = guard.evaluate(
            nowMs = 15_000L,
            currentLux = 10f,
            isOccluded = false,
            isBedtimeWindow = false,
            isLyingDown = false,
            isTestMode = false
        )
        assertTrue(result.isLikelyShadowInLitRoom)
        assertEquals(150f, result.recentPeakLux ?: -1f, 0.01f)
        assertTrue(result.recentBrightHoldMs >= AmbientLightShadowGuard.BRIGHT_CONFIRM_HOLD_MS)
    }

    @Test
    fun briefFlashInDarkRoomDoesNotTriggerShadowGuard() {
        val guard = AmbientLightShadowGuard()
        guard.record(nowMs = 0L, lux = 2f, isOccluded = false)
        // 0.6초 동안 순간 빛(지나가는 차량/알림 플래시)만 스침
        guard.record(nowMs = 5_000L, lux = 80f, isOccluded = false)
        guard.record(nowMs = 5_600L, lux = 2f, isOccluded = false)

        val result = guard.evaluate(
            nowMs = 10_000L,
            currentLux = 2f,
            isOccluded = false,
            isBedtimeWindow = true,
            isLyingDown = true,
            isTestMode = false
        )
        assertFalse(result.isLikelyShadowInLitRoom)
        assertTrue(result.recentBrightHoldMs < AmbientLightShadowGuard.BRIGHT_CONFIRM_HOLD_MS)
    }

    @Test
    fun realLightsOffOverridesShadowGuardWithoutRepeatLightCallbacks() {
        val guard = AmbientLightShadowGuard()
        // 밝은 거실에서 10초 사용 후 방 불을 완전히 끔 (2 lx, 이후 정적 어둠이라 추가 콜백 없음)
        guard.record(nowMs = 0L, lux = 180f, isOccluded = false)
        guard.record(nowMs = 10_000L, lux = 2f, isOccluded = false)

        // 취침/누운 자세: 44초 시점은 아직 그림자 의심, 45초 도달 시 실제 소등 확정
        assertTrue(
            guard.evaluate(
                nowMs = 10_000L + 44_000L,
                currentLux = 2f,
                isOccluded = false,
                isBedtimeWindow = true,
                isLyingDown = false
            ).isLikelyShadowInLitRoom
        )
        assertFalse(
            guard.evaluate(
                nowMs = 10_000L + 45_000L,
                currentLux = 2f,
                isOccluded = false,
                isBedtimeWindow = true,
                isLyingDown = false
            ).isLikelyShadowInLitRoom
        )

        // 일상/낮 시간대 비누움: 89초 시점은 그림자 의심, 90초 도달 시 실제 소등 확정
        assertTrue(
            guard.evaluate(
                nowMs = 10_000L + 89_000L,
                currentLux = 2f,
                isOccluded = false,
                isBedtimeWindow = false,
                isLyingDown = false
            ).isLikelyShadowInLitRoom
        )
        assertFalse(
            guard.evaluate(
                nowMs = 10_000L + 90_000L,
                currentLux = 2f,
                isOccluded = false,
                isBedtimeWindow = false,
                isLyingDown = false
            ).isLikelyShadowInLitRoom
        )

        // 테스트 모드: 4초는 보호, 5초 도달 시 즉시 실제 소등 확정
        assertTrue(
            guard.evaluate(
                nowMs = 10_000L + 4_000L,
                currentLux = 2f,
                isOccluded = false,
                isBedtimeWindow = false,
                isLyingDown = false,
                isTestMode = true
            ).isLikelyShadowInLitRoom
        )
        assertFalse(
            guard.evaluate(
                nowMs = 10_000L + 5_000L,
                currentLux = 2f,
                isOccluded = false,
                isBedtimeWindow = false,
                isLyingDown = false,
                isTestMode = true
            ).isLikelyShadowInLitRoom
        )
    }

    @Test
    fun midShadowLuxAround10NeverOverridesAsRealLightsOff() {
        val guard = AmbientLightShadowGuard()
        guard.record(nowMs = 0L, lux = 200f, isOccluded = false)
        // 머리/책상 그림자로 10 lx 유지 (완전 소등 <= 5 lx가 아님)
        guard.record(nowMs = 10_000L, lux = 10f, isOccluded = false)

        val afterTwoMinutes = guard.evaluate(
            nowMs = 130_000L,
            currentLux = 10f,
            isOccluded = false,
            isBedtimeWindow = true,
            isLyingDown = true
        )
        assertTrue(afterTwoMinutes.isLikelyShadowInLitRoom)
        assertEquals(0L, afterTwoMinutes.continuousDeepDarkMs)
    }

    @Test
    fun sensorOcclusionResetsDeepDarkTimer() {
        val guard = AmbientLightShadowGuard()
        guard.record(nowMs = 0L, lux = 120f, isOccluded = false)
        guard.record(nowMs = 10_000L, lux = 1f, isOccluded = false)
        // 40초 시점에 손/주머니로 센서 가림 발생 -> 완전 암실 타이머 리셋
        guard.record(nowMs = 50_000L, lux = 0f, isOccluded = true)
        guard.record(nowMs = 52_000L, lux = 2f, isOccluded = false)

        // 56초 시점: 리셋 후 4초밖에 지나지 않았으므로 아직 소등 미확정
        val assessment = guard.evaluate(
            nowMs = 56_000L,
            currentLux = 2f,
            isOccluded = false,
            isBedtimeWindow = true,
            isLyingDown = true
        )
        assertTrue(assessment.isLikelyShadowInLitRoom)
        assertEquals(4_000L, assessment.continuousDeepDarkMs)
    }

    @Test
    fun screenOffGapPreventsStaleBrightCarryOverAcrossLongSleep() {
        val guard = AmbientLightShadowGuard()
        // 밝은 방에서 10초 사용 후 화면 끔
        guard.record(nowMs = 0L, lux = 150f, isOccluded = false)
        guard.recordGap(nowMs = 10_000L)

        // 1) 짧은 화면 꺼짐(10초 뒤) 후 머리 그림자(10 lx)로 켜면 최근 3분 내 10초 밝음이 유지되어 보호됨
        guard.record(nowMs = 20_000L, lux = 10f, isOccluded = false)
        assertTrue(
            guard.evaluate(
                nowMs = 22_000L,
                currentLux = 10f,
                isOccluded = false,
                isBedtimeWindow = false,
                isLyingDown = false
            ).isLikelyShadowInLitRoom
        )

        // 2) 다시 화면 끄고 10분 뒤 어두운 침실에서 켰을 때 과거 밝음이 10분간 이어진 것처럼 착각하지 않음
        guard.recordGap(nowMs = 25_000L)
        guard.record(nowMs = 625_000L, lux = 2f, isOccluded = false)
        val afterLongSleep = guard.evaluate(
            nowMs = 626_000L,
            currentLux = 2f,
            isOccluded = false,
            isBedtimeWindow = true,
            isLyingDown = true
        )
        assertFalse(afterLongSleep.isLikelyShadowInLitRoom)
        assertEquals(0L, afterLongSleep.recentBrightHoldMs)
    }

    @Test
    fun ringBufferHandlesWrapAroundWithoutLosingRecentHistory() {
        val guard = AmbientLightShadowGuard()
        var t = 0L
        // 64칸 용량을 크게 초과하는 150개 샘플 삽입
        repeat(150) { idx ->
            val lux = if (idx < 140) 200f else 12f
            guard.record(nowMs = t, lux = lux, isOccluded = false)
            t += 4_000L
        }
        val assessment = guard.evaluate(
            nowMs = t,
            currentLux = 12f,
            isOccluded = false,
            isBedtimeWindow = false,
            isLyingDown = false
        )
        assertTrue(assessment.isLikelyShadowInLitRoom)
        assertEquals(200f, assessment.recentPeakLux ?: -1f, 0.01f)
    }
}
