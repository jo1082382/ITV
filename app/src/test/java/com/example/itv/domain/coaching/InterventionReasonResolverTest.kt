package com.example.itv.domain.coaching

import com.example.itv.domain.jev.JevContext
import com.example.itv.infrastructure.observation.AmbientSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class InterventionReasonResolverTest {

    @Test
    fun returnsMaxThreeReasons() {
        val jevContext = JevContext(
            sessionId = "s1",
            packageName = "com.google.android.youtube",
            sessionMs = 600_000L,
            continuousMs = 600_000L,
            returns10m = 4,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = 25,
            observedAt = 0L,
            swipeOverheated = true,
            sessionVsAvgRatio = 2.0
        )
        val ambient = AmbientSnapshot(
            lux = 5f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 50,
            nightModeActive = true,
            stateLabel = "취침 전 상태 (어두운 조도 + 어두운 화면)",
            explanation = "test"
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = jevContext,
            continuousUsedMs = 600_000L,
            ambient = ambient,
            shortformAppName = "YouTube 쇼츠",
            localTime = LocalTime.of(23, 30)
        )

        assertTrue(reasons.size <= 3)
        // 가장 큰 이유인 취침 목표 초과가 1위에 위치해야 함
        assertTrue(reasons[0].contains("취침 목표보다 25분이나 지났어"))
    }

    @Test
    fun testModeContinuousThirtySeconds() {
        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = false,
            jevContext = null,
            continuousUsedMs = 30_000L,
            ambient = null,
            shortformAppName = "Instagram 릴스",
            localTime = LocalTime.of(15, 0)
        )

        assertTrue(reasons.isNotEmpty())
        assertTrue(reasons.size <= 3)
        assertTrue(reasons[0].contains("30초 동안"))
    }

    @Test
    fun darkAmbientPriorityOverContinuousMinutes() {
        val ambient = AmbientSnapshot(
            lux = 3f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 40,
            nightModeActive = true,
            stateLabel = "취침 전 상태",
            explanation = "test"
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = false,
            jevContext = null,
            continuousUsedMs = 120_000L,
            ambient = ambient,
            shortformAppName = "YouTube 쇼츠",
            localTime = LocalTime.of(14, 0)
        )

        assertTrue(reasons.size <= 3)
        // 어두운 조도 사유(우선순위 90)가 2분 연속 시청(우선순위 80)보다 먼저 나와야 함
        assertTrue(reasons[0].contains("주변이 어두운데"))
        assertTrue(reasons[1].contains("2분 넘게"))
    }
}
