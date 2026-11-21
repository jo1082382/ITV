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
        // 어두운 조도 사유(우선순위 85)가 2분 연속 시청(우선순위 80)보다 먼저 나와야 함
        assertTrue(reasons[0].contains("주변이 어두운데"))
        assertTrue(reasons[1].contains("2분 넘게"))
    }

    @Test
    fun bedtimeDelayWatchStateGeneratesMelatoninWarningReason() {
        val ambient = AmbientSnapshot(
            lux = 5f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 180,
            nightModeActive = false,
            stateLabel = "취침 지연 과몰입 상태 (어두운 조명 + 밝은 화면 응시)",
            explanation = "test",
            userEnvironmentState = com.example.itv.infrastructure.observation.UserEnvironmentState.BEDTIME_DELAY_WATCH
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = false,
            jevContext = null,
            continuousUsedMs = 120_000L,
            ambient = ambient,
            shortformAppName = "YouTube 쇼츠",
            localTime = LocalTime.of(23, 45)
        )

        assertTrue(reasons.any { it.contains("취침 시간인데 어두운 방에서 밝은 화면") })
    }

    @Test
    fun preSleepRestStateGeneratesGentleSleepReason() {
        val ambient = AmbientSnapshot(
            lux = 3f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 35,
            nightModeActive = true,
            stateLabel = "취침 전 수면 준비 상태",
            explanation = "test",
            userEnvironmentState = com.example.itv.infrastructure.observation.UserEnvironmentState.PRE_SLEEP_REST
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = false,
            jevContext = null,
            continuousUsedMs = 120_000L,
            ambient = ambient,
            shortformAppName = "Instagram 릴스",
            localTime = LocalTime.of(0, 30)
        )

        assertTrue(reasons.any { it.contains("불을 끄고 누워서 보고 있네") })
    }

    @Test
    fun daytimeDarkAmbientUsesContextualExplanationNotBedtime() {
        val ambient = AmbientSnapshot(
            lux = 4f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 150,
            nightModeActive = false,
            stateLabel = "활동 시간대 암실 시청 상태",
            explanation = "test",
            bedtimePhase = com.example.itv.infrastructure.observation.BedtimePhase.ACTIVE_HOURS,
            userEnvironmentState = com.example.itv.infrastructure.observation.UserEnvironmentState.INDOOR_DARK_ACTIVE
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = false,
            jevContext = null,
            continuousUsedMs = 120_000L,
            ambient = ambient,
            shortformAppName = "YouTube 쇼츠",
            localTime = LocalTime.of(14, 30)
        )

        assertTrue(reasons.any { it.contains("낮 시간인데 어두운 곳에서") })
        // 낮 시간이므로 취침 관련 문구가 절대 나오지 않아야 함
        assertTrue(reasons.none { it.contains("취침") || it.contains("잘 시간") })
    }

    @Test
    fun daytimeSuppressesBedtimePassedReason() {
        val jevContext = JevContext(
            sessionId = "s1",
            packageName = "com.google.android.youtube",
            sessionMs = 600_000L,
            continuousMs = 600_000L,
            returns10m = 1,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = 600, // 낮 시간 경과치
            observedAt = 0L
        )
        val ambient = AmbientSnapshot(
            lux = 200f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 120,
            nightModeActive = false,
            stateLabel = "주간/밝은 실내 활동 상태",
            explanation = "test",
            bedtimePhase = com.example.itv.infrastructure.observation.BedtimePhase.ACTIVE_HOURS
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = jevContext,
            continuousUsedMs = 600_000L,
            ambient = ambient,
            shortformAppName = "YouTube 쇼츠",
            localTime = LocalTime.of(15, 0)
        )

        // 낮 시간이므로 "취침 목표보다 X분이나 지났어"가 나오지 않아야 함
        assertTrue(reasons.none { it.contains("취침 목표") })
    }
}
