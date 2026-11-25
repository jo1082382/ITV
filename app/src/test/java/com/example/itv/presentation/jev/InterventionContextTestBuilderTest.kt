package com.example.itv.presentation.jev

import com.example.itv.domain.coaching.InterventionReasonResolver
import com.example.itv.domain.jev.JevCandidatePolicy
import com.example.itv.domain.jev.JevSettings
import com.example.itv.infrastructure.observation.BedtimePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class InterventionContextTestBuilderTest {

    @Test
    fun `3단계 카테고리 사용 시간 총합과 환산 등가 시간이 정확히 합산된다`() {
        val input = CategoryMinutesInput(
            youtubeShorts = 6,
            instaReels = 4,      // 숏폼 1:1 = 10분 -> 등가 10분
            feedSns = 10,
            game = 6,            // 중가중 1:2 = 16분 -> 등가 8분
            youtubeLongform = 9,
            webGeneral = 6,
            docMessenger = 3     // 저가중 1:3 = 18분 -> 등가 6분
        )

        assertEquals(44, input.totalRawMinutes)
        assertEquals(10, input.shortformMinutes)
        assertEquals(16, input.midWeightMinutes)
        assertEquals(18, input.lowWeightMinutes)
        assertEquals(24.0, input.equivalentMinutes, 0.001)
    }

    @Test
    fun `취침 추정 시각 기준 플러스 오프셋과 암실 조도가 개입 사유와 마이너스 보정에 반영된다`() {
        val built = InterventionContextTestBuilder.build(
            minutes = CategoryMinutesInput(instaReels = 22),
            lux = 3f,
            screenBrightness = 70,
            baseBedtimeHour = 23,
            baseBedtimeMinute = 45,
            bedtimeOffsetMinutes = 35
        )

        assertEquals(LocalTime.of(0, 20), built.simulatedLocalTime)
        assertEquals(BedtimePhase.PAST_BEDTIME, built.ambient.bedtimePhase)
        assertEquals(35, built.jevContext.bedtimePassedMinutes)

        val adj = JevCandidatePolicy.computeThresholdAdjustment(JevSettings(personalizedEnabled = true), built.jevContext)
        assertTrue("취침 암실 마이너스 보정이 적용되어야 함: ${adj.bedtimeDelta}", adj.bedtimeDelta <= -0.25)

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = built.jevContext,
            continuousUsedMs = built.jevContext.continuousMs,
            ambient = built.ambient,
            shortformAppName = built.shortformAppName,
            localTime = built.simulatedLocalTime
        )
        assertTrue(reasons.first().contains("35분"))
        assertTrue(reasons.first().contains("어두운"))
        assertTrue(reasons.any { it.contains("Instagram 릴스") && it.contains("22분") })
    }

    @Test
    fun `취침 추정 시각 기준 마이너스 오프셋(일상 시간)과 밝은 조도는 취침 사유를 내지 않는다`() {
        val built = InterventionContextTestBuilder.build(
            minutes = CategoryMinutesInput(youtubeLongform = 36),
            lux = 220f,
            screenBrightness = 200,
            baseBedtimeHour = 0,
            baseBedtimeMinute = 0,
            bedtimeOffsetMinutes = -360
        )

        assertEquals(LocalTime.of(18, 0), built.simulatedLocalTime)
        assertEquals(BedtimePhase.ACTIVE_HOURS, built.ambient.bedtimePhase)
        assertNull(built.jevContext.bedtimePassedMinutes)

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = built.jevContext,
            continuousUsedMs = built.jevContext.continuousMs,
            ambient = built.ambient,
            shortformAppName = built.shortformAppName,
            localTime = built.simulatedLocalTime,
            categoryDurationsMs = built.categoryDurationsMs
        )
        assertTrue(reasons.none { it.contains("취침") || it.contains("어두운") })
        assertTrue(reasons.any { it.contains("유튜브 영상") && it.contains("36분") })
    }

    @Test
    fun `혼합 세션에서 가중치 기여도가 가장 큰 쇼츠가 롱폼보다 우선 설명된다`() {
        val built = InterventionContextTestBuilder.build(
            minutes = CategoryMinutesInput(
                youtubeShorts = 12,   // 환산 12분 (1위)
                youtubeLongform = 15, // 환산 5분
                docMessenger = 6      // 환산 2분
            ),
            foregroundOverride = InterventionTestCategory.YOUTUBE_LONGFORM,
            lux = 180f,
            screenBrightness = 150,
            baseBedtimeHour = 23,
            baseBedtimeMinute = 30,
            bedtimeOffsetMinutes = -300
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = built.jevContext,
            continuousUsedMs = built.jevContext.continuousMs,
            ambient = built.ambient,
            shortformAppName = built.shortformAppName,
            localTime = built.simulatedLocalTime,
            categoryDurationsMs = built.categoryDurationsMs
        )
        assertTrue("Expected Shorts 12m as dominant contributor, got $reasons", reasons.first().contains("YouTube 쇼츠를 12분째"))
    }
}
