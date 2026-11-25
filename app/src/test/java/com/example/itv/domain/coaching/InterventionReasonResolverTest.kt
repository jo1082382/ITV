package com.example.itv.domain.coaching

import com.example.itv.domain.jev.JevContext
import com.example.itv.infrastructure.observation.AmbientSnapshot
import com.example.itv.infrastructure.observation.BedtimePhase
import com.example.itv.infrastructure.observation.SessionAppCategory
import com.example.itv.infrastructure.observation.UserEnvironmentState
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class InterventionReasonResolverTest {

    @Test
    fun returnsMaxThreeReasonsWithBedtimeDarkAndShortformMinutes() {
        val jevContext = JevContext(
            sessionId = "s1",
            packageName = "com.google.android.youtube",
            sessionMs = 1320_000L,
            continuousMs = 1320_000L,
            rawShortformMs = 1320_000L, // 22분
            returns10m = 4,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = 25,
            observedAt = 0L,
            personalAvgSessionMinutes = 12.0,
            sessionVsAvgRatio = 1.83,
            swipeOverheated = true,
            effectiveSpm1m = 13.0,
            ambientLux = 5f,
            screenBrightness = 50,
            bedtimeDistanceMinutes = 25
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
            explanation = "test",
            bedtimePhase = BedtimePhase.PAST_BEDTIME,
            posture = UserPosture.LYING_DOWN
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = jevContext,
            continuousUsedMs = 1320_000L,
            ambient = ambient,
            shortformAppName = "YouTube 쇼츠",
            localTime = LocalTime.of(23, 30)
        )

        assertTrue(reasons.size <= 3)
        // 1순위: 취침 지연 + 어두운 환경
        assertTrue("Expected bedtime+dark first, got $reasons", reasons[0].contains("25분") && reasons[0].contains("어두운"))
        // 2순위: 쇼츠 분 단위 연속 시청
        assertTrue("Expected shortform 22m second, got $reasons", reasons[1].contains("YouTube 쇼츠를 22분째"))
        // 3순위: 개인 평균 세션 초과 (분 단위)
        assertTrue("Expected session exceeded third, got $reasons", reasons[2].contains("평소 평균(12분)보다 10분"))
        // 자세 관련 문구는 절대 포함되지 않아야 함
        assertTrue(reasons.none { it.contains("목에 부담") || it.contains("어깨") || it.contains("고개가") })
    }

    @Test
    fun postureIsNeverIncludedEvenWhenLyingDownInDaytime() {
        val jevContext = JevContext(
            sessionId = "s2",
            packageName = "com.instagram.android",
            sessionMs = 1200_000L,
            continuousMs = 1200_000L,
            rawShortformMs = 1200_000L, // 20분
            returns10m = 1,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = null,
            observedAt = 0L,
            personalAvgSessionMinutes = 10.0,
            sessionVsAvgRatio = 2.0,
            ambientLux = 180f,
            screenBrightness = 150
        )
        val ambient = AmbientSnapshot(
            lux = 180f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 150,
            nightModeActive = false,
            stateLabel = "주간 밝은 환경",
            explanation = "test",
            bedtimePhase = BedtimePhase.ACTIVE_HOURS,
            posture = UserPosture.LYING_DOWN
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = jevContext,
            continuousUsedMs = 1200_000L,
            ambient = ambient,
            shortformAppName = "Instagram 릴스",
            localTime = LocalTime.of(14, 0)
        )

        assertTrue("Expected Reels 20m first, got $reasons", reasons[0].contains("Instagram 릴스를 20분째"))
        assertTrue("Expected personal avg exceeded second, got $reasons", reasons[1].contains("평소 평균(10분)보다 10분"))
        assertTrue(reasons.none { it.contains("목") || it.contains("어깨") || it.contains("고개") })
    }

    @Test
    fun youtubeLongformUsesActualMinutesAndChainCount() {
        val jevContext = JevContext(
            sessionId = "s3",
            packageName = "com.google.android.youtube",
            sessionMs = 720_000L,
            continuousMs = 720_000L, // 1:3 가중치 등가 12분
            rawGeneralMs = 2160_000L, // 실제 36분
            longformWatchMs = 2160_000L,
            youtubeWatchMode = "LONGFORM",
            consecutiveLongformChainCount = 4,
            returns10m = 0,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = null,
            observedAt = 0L,
            ambientLux = 200f,
            screenBrightness = 140
        )
        val ambient = AmbientSnapshot(
            lux = 200f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 140,
            nightModeActive = false,
            stateLabel = "주간 밝은 환경",
            explanation = "test",
            bedtimePhase = BedtimePhase.ACTIVE_HOURS,
            posture = UserPosture.SITTING_OR_STANDING
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = jevContext,
            continuousUsedMs = 720_000L,
            ambient = ambient,
            shortformAppName = null,
            localTime = LocalTime.of(15, 0)
        )

        // 등가 12분이 아니라 실제 시청 시간 36분으로 안내해야 함
        assertTrue("Expected actual 36m longform reason, got $reasons", reasons[0].contains("유튜브 영상을 연속 36분째"))
        assertTrue("Expected 4 videos chained reason, got $reasons", reasons[1].contains("4편째 연달아"))
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
        // 어두운 조도 사유가 2분 연속 시청보다 먼저 나와야 함
        assertTrue("Expected dark ambient first, got $reasons", reasons[0].contains("어두운 곳"))
        assertTrue("Expected 2m watch second, got $reasons", reasons[1].contains("YouTube 쇼츠를 2분째"))
    }

    @Test
    fun bedtimeDelayWatchStateGeneratesDarkWarningReason() {
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
            userEnvironmentState = UserEnvironmentState.BEDTIME_DELAY_WATCH
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
            userEnvironmentState = UserEnvironmentState.PRE_SLEEP_REST
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = false,
            jevContext = null,
            continuousUsedMs = 120_000L,
            ambient = ambient,
            shortformAppName = "Instagram 릴스",
            localTime = LocalTime.of(0, 30)
        )

        assertTrue(reasons.any { it.contains("불을 끈 어두운 방에서") })
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
            bedtimePhase = BedtimePhase.ACTIVE_HOURS,
            userEnvironmentState = UserEnvironmentState.INDOOR_DARK_ACTIVE
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
            bedtimePassedMinutes = 600,
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
            bedtimePhase = BedtimePhase.ACTIVE_HOURS
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = jevContext,
            continuousUsedMs = 600_000L,
            ambient = ambient,
            shortformAppName = "YouTube 쇼츠",
            localTime = LocalTime.of(15, 0)
        )

        assertTrue(reasons.none { it.contains("취침") })
    }

    @Test
    fun twoHoursBeforeBedtimeWithLightsOnSuppressesBedtimePhrases() {
        val ambient = AmbientSnapshot(
            lux = 150f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 140,
            nightModeActive = false,
            stateLabel = "실내 일상 활동 상태 (실내 조명 + 일반 화면)",
            explanation = "test",
            bedtimePhase = BedtimePhase.ACTIVE_HOURS,
            bedtimeDistanceMinutes = -120,
            isCharging = true,
            isWifiConnected = true,
            isStationary = true,
            userEnvironmentState = UserEnvironmentState.INDOOR_ACTIVE
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = false,
            jevContext = null,
            continuousUsedMs = 300_000L,
            ambient = ambient,
            shortformAppName = "YouTube 쇼츠",
            localTime = LocalTime.of(21, 30)
        )

        assertTrue(reasons.none { it.contains("취침") || it.contains("늦은 밤") || it.contains("잘 시간") || it.contains("자던 시간") })
    }

    @Test
    fun approachingBedtimeWithLightsOnSuppressesBedtimePhrases() {
        val ambient = AmbientSnapshot(
            lux = 80f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 120,
            nightModeActive = false,
            stateLabel = "취침 전 일상 활동 상태 (실내 조명 켜짐)",
            explanation = "test",
            bedtimePhase = BedtimePhase.APPROACHING,
            bedtimeDistanceMinutes = -40,
            userEnvironmentState = UserEnvironmentState.INDOOR_ACTIVE
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = false,
            jevContext = null,
            continuousUsedMs = 120_000L,
            ambient = ambient,
            shortformAppName = "Instagram 릴스",
            localTime = LocalTime.of(22, 50)
        )

        assertTrue(reasons.none { it.contains("취침") || it.contains("늦은 밤") || it.contains("잘 시간") || it.contains("자던 시간") })
    }

    @Test
    fun approachingBedtimeDarkAmbientWithContinuousUsageGeneratesPreSleepReason() {
        val ambient = AmbientSnapshot(
            lux = 3f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 60,
            nightModeActive = true,
            stateLabel = "취침 지연 과몰입 상태",
            explanation = "test",
            bedtimePhase = BedtimePhase.APPROACHING,
            bedtimeDistanceMinutes = -30,
            userEnvironmentState = UserEnvironmentState.BEDTIME_DELAY_WATCH
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = false,
            jevContext = null,
            continuousUsedMs = 40_000L,
            ambient = ambient,
            shortformAppName = "YouTube 쇼츠",
            localTime = LocalTime.of(23, 0)
        )

        assertTrue(reasons.any { it.contains("어두운 방에서 밝은 화면") })
    }

    @Test
    fun mixedSessionPicksHighestWeightedContributorInsteadOfRawLongform() {
        val jevContext = JevContext(
            sessionId = "mix1",
            packageName = "com.google.android.youtube",
            sessionMs = 1140_000L,
            continuousMs = 1140_000L, // 환산 19분 (쇼츠12 + 롱폼5 + 메신저2)
            rawShortformMs = 720_000L,
            rawGeneralMs = 1260_000L,
            longformWatchMs = 900_000L,
            youtubeWatchMode = "LONGFORM",
            returns10m = 0,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = null,
            observedAt = 0L,
            ambientLux = 200f,
            screenBrightness = 150
        )
        val ambient = AmbientSnapshot(
            lux = 200f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 150,
            nightModeActive = false,
            stateLabel = "주간 밝은 환경",
            explanation = "test",
            bedtimePhase = BedtimePhase.ACTIVE_HOURS
        )
        val categories = mapOf(
            SessionAppCategory.YOUTUBE_SHORTS to 12 * 60_000L,
            SessionAppCategory.YOUTUBE_LONGFORM to 15 * 60_000L,
            SessionAppCategory.DOC_MESSENGER to 6 * 60_000L
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = jevContext,
            continuousUsedMs = 1140_000L,
            ambient = ambient,
            shortformAppName = null,
            localTime = LocalTime.of(15, 0),
            categoryDurationsMs = categories
        )

        assertTrue("Expected Shorts 12m first, got $reasons", reasons.first().contains("YouTube 쇼츠를 12분째"))
    }

    @Test
    fun evenlyMixedMultiAppSessionSummarizesTotalWithoutMisattribution() {
        val jevContext = JevContext(
            sessionId = "mix2",
            packageName = "ALL",
            sessionMs = 1200_000L,
            continuousMs = 1200_000L, // 환산 20분 (쇼츠6 + 피드5 + 게임4 + 롱폼5)
            rawShortformMs = 360_000L,
            rawGeneralMs = 1980_000L,
            returns10m = 0,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = null,
            observedAt = 0L,
            ambientLux = 200f,
            screenBrightness = 150
        )
        val categories = mapOf(
            SessionAppCategory.YOUTUBE_SHORTS to 6 * 60_000L,
            SessionAppCategory.FEED_SNS to 10 * 60_000L,
            SessionAppCategory.GAME to 8 * 60_000L,
            SessionAppCategory.YOUTUBE_LONGFORM to 15 * 60_000L
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = jevContext,
            continuousUsedMs = 1200_000L,
            ambient = null,
            shortformAppName = null,
            localTime = LocalTime.of(15, 0),
            categoryDurationsMs = categories
        )

        assertTrue("Expected multi-app 39m summary, got $reasons", reasons.first().contains("여러 앱을 쉬지 않고 연속 39분째"))
    }

    @Test
    fun strongSwipeOverheatBeatsShortWatchTimeByContributionScore() {
        val jevContext = JevContext(
            sessionId = "overheat1",
            packageName = "com.google.android.youtube",
            sessionMs = 360_000L,
            continuousMs = 360_000L, // 6분
            rawShortformMs = 360_000L,
            returns10m = 0,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = null,
            observedAt = 0L,
            effectiveSpm1m = 15.0,
            fastSwipeStreak = 4,
            swipeOverheated = true, // -10%p
            ambientLux = 200f,
            screenBrightness = 150
        )
        val categories = mapOf(
            SessionAppCategory.YOUTUBE_SHORTS to 6 * 60_000L
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = jevContext,
            continuousUsedMs = 360_000L,
            ambient = null,
            shortformAppName = "YouTube 쇼츠",
            localTime = LocalTime.of(15, 0),
            categoryDurationsMs = categories
        )

        assertTrue("Expected swipe overheat #1 over 6m watch, got $reasons", reasons[0].contains("분당 15회"))
        assertTrue("Expected Shorts 6m #2, got $reasons", reasons[1].contains("YouTube 쇼츠를 6분째"))
    }

    @Test
    fun preBedtimeDarkRoomGetsExtraWeightOverAvgExcessAndWatchTime() {
        val jevContext = JevContext(
            sessionId = "prebed1",
            packageName = "com.instagram.android",
            sessionMs = 900_000L,
            continuousMs = 900_000L, // 15분
            rawShortformMs = 900_000L,
            returns10m = 0,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = null,
            bedtimeDistanceMinutes = -40,
            observedAt = 0L,
            personalAvgSessionMinutes = 8.0,
            sessionVsAvgRatio = 1.875, // -10%p
            ambientLux = 3f,
            screenBrightness = 160
        )
        val ambient = AmbientSnapshot(
            lux = 3f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 160,
            nightModeActive = true,
            stateLabel = "취침 지연 과몰입 상태",
            explanation = "test",
            bedtimePhase = BedtimePhase.APPROACHING,
            bedtimeDistanceMinutes = -40,
            userEnvironmentState = UserEnvironmentState.BEDTIME_DELAY_WATCH
        )
        val categories = mapOf(
            SessionAppCategory.INSTA_REELS to 15 * 60_000L
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = jevContext,
            continuousUsedMs = 900_000L,
            ambient = ambient,
            shortformAppName = "Instagram 릴스",
            localTime = LocalTime.of(22, 50),
            categoryDurationsMs = categories
        )

        assertTrue("Expected pre-bedtime dark room #1, got $reasons", reasons[0].contains("취침 40분 전") && reasons[0].contains("어두운"))
    }

    @Test
    fun daytimePitchDarkRoomGetsExtraWeightOverAvgExcess() {
        val jevContext = JevContext(
            sessionId = "daydark1",
            packageName = "com.google.android.youtube",
            sessionMs = 720_000L,
            continuousMs = 720_000L, // 12분
            rawShortformMs = 720_000L,
            returns10m = 0,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = null,
            observedAt = 0L,
            personalAvgSessionMinutes = 6.0,
            sessionVsAvgRatio = 2.0, // -10%p
            ambientLux = 2f,
            screenBrightness = 180
        )
        val ambient = AmbientSnapshot(
            lux = 2f,
            proximityDistanceCm = 5f,
            proximityMaxRangeCm = 5f,
            isNear = false,
            isOccluded = false,
            screenBrightness = 180,
            nightModeActive = false,
            stateLabel = "활동 시간대 암실 시청 상태",
            explanation = "test",
            bedtimePhase = BedtimePhase.ACTIVE_HOURS,
            userEnvironmentState = UserEnvironmentState.INDOOR_DARK_ACTIVE
        )
        val categories = mapOf(
            SessionAppCategory.YOUTUBE_SHORTS to 12 * 60_000L
        )

        val reasons = InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = jevContext,
            continuousUsedMs = 720_000L,
            ambient = ambient,
            shortformAppName = "YouTube 쇼츠",
            localTime = LocalTime.of(14, 0),
            categoryDurationsMs = categories
        )

        assertTrue("Expected pitch-dark room #1, got $reasons", reasons[0].contains("어두운 곳에서 밝은 화면"))
        assertTrue("Expected avg excess #2, got $reasons", reasons[1].contains("평소 평균(6분)보다 6분"))
    }
}
