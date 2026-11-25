package com.example.itv.domain.coaching

import org.junit.Assert.*
import org.junit.Test

class ContextualPreInterventionPolicyTest {

    @Test
    fun postBedtimeTriggersInBothDarkAndLitRooms() {
        // 암실 + 1분 사용: 아직 미달 (2분 필요)
        val tooEarlyDark = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 2f,
            isOccluded = false,
            bedtimeDistanceMinutes = 20,
            isPastBedtime = true,
            isApproachingBedtime = false,
            continuousUsedMs = 60_000L,
            darkAccumulatedMs = 60_000L,
            alreadyTriggeredInSession = false,
            screenBrightness = 40
        )
        assertNull(tooEarlyDark)

        // 암실 + 2분 사용 + 암실 1분 이상: 취침 초과 사전 개입 발동
        val triggeredDark = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 2f,
            isOccluded = false,
            bedtimeDistanceMinutes = 20,
            isPastBedtime = true,
            isApproachingBedtime = false,
            continuousUsedMs = 2 * 60_000L,
            darkAccumulatedMs = 60_000L,
            alreadyTriggeredInSession = false,
            screenBrightness = 40
        )
        assertNotNull(triggeredDark)
        assertEquals(PreInterventionKind.POST_BEDTIME, triggeredDark!!.kind)
        assertTrue(triggeredDark.bubbleContent.badges.any { it.text.contains("취침 +20분") })
        assertTrue(triggeredDark.bubbleContent.badges.any { it.text.contains("암실") })
        assertTrue(triggeredDark.bubbleContent.headline.length <= 16)

        // 밝은 방(조명 켜짐)에서도 추정/추천 취침 시각 이후 3분 이상이면 사전 개입 발동
        val triggeredLit = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 120f,
            isOccluded = false,
            bedtimeDistanceMinutes = 25,
            isPastBedtime = true,
            isApproachingBedtime = false,
            continuousUsedMs = 3 * 60_000L,
            darkAccumulatedMs = 0L,
            alreadyTriggeredInSession = false,
            screenBrightness = 150
        )
        assertNotNull(triggeredLit)
        assertEquals(PreInterventionKind.POST_BEDTIME, triggeredLit!!.kind)
        assertEquals(1, triggeredLit.bubbleContent.badges.size)
        assertEquals("⏰ 취침 +25분", triggeredLit.bubbleContent.badges.first().text)
    }

    @Test
    fun darkRoomWithBrightScreenTriggersEyeStrainPreIntervention() {
        val triggered = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 8f,
            isOccluded = false,
            bedtimeDistanceMinutes = -300,
            isPastBedtime = false,
            isApproachingBedtime = false,
            continuousUsedMs = 3 * 60_000L,
            darkAccumulatedMs = 90_000L,
            alreadyTriggeredInSession = false,
            screenBrightness = 140 // > 75 (밝은 화면)
        )
        assertNotNull(triggered)
        assertEquals(PreInterventionKind.DARK_BRIGHT_SCREEN, triggered!!.kind)
        assertEquals("⚠️ 암실·밝은 화면", triggered.bubbleContent.badges.first().text)
        assertTrue(triggered.bubbleContent.headline.length <= 16)
    }

    @Test
    fun preBedtimePrepWindowTriggersWithinOneHourInBothLitAndDarkRooms() {
        // 취침 90분 전(1시간 밖) + 밝은 방 -> 발동 안 함
        val tooFarFromBedtime = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 100f,
            isOccluded = false,
            bedtimeDistanceMinutes = -90,
            isPastBedtime = false,
            isApproachingBedtime = false,
            continuousUsedMs = 10 * 60_000L,
            darkAccumulatedMs = 0L,
            alreadyTriggeredInSession = false,
            screenBrightness = 120
        )
        assertNull(tooFarFromBedtime)

        // 취침 40분 전 + 밝은 방 + 5분 사용 -> 취침 준비 시간 사전 개입 발동
        val triggeredLit = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 100f,
            isOccluded = false,
            bedtimeDistanceMinutes = -40,
            isPastBedtime = false,
            isApproachingBedtime = true,
            continuousUsedMs = 5 * 60_000L,
            darkAccumulatedMs = 0L,
            alreadyTriggeredInSession = false,
            screenBrightness = 120
        )
        assertNotNull(triggeredLit)
        assertEquals(PreInterventionKind.PRE_BEDTIME_PREP, triggeredLit!!.kind)
        assertEquals("🛌 취침 40분 전", triggeredLit.bubbleContent.badges.first().text)

        // 취침 35분 전 + 어두운 방 + 어두운 화면 + 4분 사용 + 암실 90초 -> 취침 준비 + 암실 배지 발동
        val triggeredDark = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 5f,
            isOccluded = false,
            bedtimeDistanceMinutes = -35,
            isPastBedtime = false,
            isApproachingBedtime = true,
            continuousUsedMs = 4 * 60_000L,
            darkAccumulatedMs = 90_000L,
            alreadyTriggeredInSession = false,
            screenBrightness = 40
        )
        assertNotNull(triggeredDark)
        assertEquals(PreInterventionKind.PRE_BEDTIME_PREP, triggeredDark!!.kind)
        assertTrue(triggeredDark.bubbleContent.badges.any { it.text.contains("암실") })
    }

    @Test
    fun darkRoomWithDimScreenTriggersAfterSustainedDarkUsage() {
        val tooShortDark = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 8f,
            isOccluded = false,
            bedtimeDistanceMinutes = -400,
            isPastBedtime = false,
            isApproachingBedtime = false,
            continuousUsedMs = 10 * 60_000L,
            darkAccumulatedMs = 2 * 60_000L, // 암실 체류 2분뿐 (3분 필요)
            alreadyTriggeredInSession = false,
            screenBrightness = 35 // <= 75 (어두운 화면)
        )
        assertNull(tooShortDark)

        val triggered = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 8f,
            isOccluded = false,
            bedtimeDistanceMinutes = -400,
            isPastBedtime = false,
            isApproachingBedtime = false,
            continuousUsedMs = 6 * 60_000L,
            darkAccumulatedMs = 3 * 60_000L,
            alreadyTriggeredInSession = false,
            screenBrightness = 35
        )
        assertNotNull(triggered)
        assertEquals(PreInterventionKind.DARK_DIM_SCREEN, triggered!!.kind)
        assertEquals("🌙 암실·어두운 화면", triggered.bubbleContent.badges.first().text)
    }

    @Test
    fun occludedOrNormalDaytimeOrAlreadyTriggeredNeverFires() {
        // 센서 가림
        assertNull(
            ContextualPreInterventionPolicy.evaluate(
                ambientLux = 0f,
                isOccluded = true,
                bedtimeDistanceMinutes = 30,
                isPastBedtime = true,
                isApproachingBedtime = false,
                continuousUsedMs = 10 * 60_000L,
                darkAccumulatedMs = 10 * 60_000L,
                alreadyTriggeredInSession = false,
                screenBrightness = 120
            )
        )
        // 일상 시간대 밝은 환경
        assertNull(
            ContextualPreInterventionPolicy.evaluate(
                ambientLux = 150f,
                isOccluded = false,
                bedtimeDistanceMinutes = -300,
                isPastBedtime = false,
                isApproachingBedtime = false,
                continuousUsedMs = 15 * 60_000L,
                darkAccumulatedMs = 0L,
                alreadyTriggeredInSession = false,
                screenBrightness = 180
            )
        )
        // 이미 현재 세션에서 사전 개입 발동됨
        assertNull(
            ContextualPreInterventionPolicy.evaluate(
                ambientLux = 2f,
                isOccluded = false,
                bedtimeDistanceMinutes = 30,
                isPastBedtime = true,
                isApproachingBedtime = false,
                continuousUsedMs = 10 * 60_000L,
                darkAccumulatedMs = 10 * 60_000L,
                alreadyTriggeredInSession = true,
                screenBrightness = 40
            )
        )
    }

    @Test
    fun shouldPrecedeBedtimeCareOnlyOnceWhenPastBedtime() {
        assertTrue(
            ContextualPreInterventionPolicy.shouldPrecedeBedtimeCare(
                isPastBedtime = true,
                ambientLux = 3f,
                isOccluded = false,
                alreadyTriggeredInSession = false
            )
        )
        assertFalse(
            ContextualPreInterventionPolicy.shouldPrecedeBedtimeCare(
                isPastBedtime = true,
                ambientLux = 3f,
                isOccluded = false,
                alreadyTriggeredInSession = true
            )
        )
        assertFalse(
            ContextualPreInterventionPolicy.shouldPrecedeBedtimeCare(
                isPastBedtime = false,
                ambientLux = 3f,
                isOccluded = false,
                alreadyTriggeredInSession = false
            )
        )
    }

    @Test
    fun gentleOfferSpeechAlwaysPreservesContextualReasonAcrossAllTones() {
        val reason = "취침 시간이 지났어요. 어두운 방에서 눈이 피로해지고 있어요 🌙"
        CoachingTone.entries.forEach { tone ->
            val candidates = CoachingSpeechCatalog.getCandidates(
                situation = SpeechSituation.GENTLE_OFFER,
                tone = tone,
                reason = reason
            )
            assertTrue("Candidates for $tone should not be empty", candidates.isNotEmpty())
            candidates.forEach { line ->
                assertTrue("Expected contextual reason in $tone speech: $line", line.contains(reason))
            }
        }
    }
}
