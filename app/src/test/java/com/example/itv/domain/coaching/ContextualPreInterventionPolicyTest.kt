package com.example.itv.domain.coaching

import org.junit.Assert.*
import org.junit.Test

class ContextualPreInterventionPolicyTest {

    @Test
    fun postBedtimeDarkTriggersGentlePreInterventionAfterThreeMinutes() {
        // 2분 사용: 아직 미달
        val tooEarly = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 2f,
            isOccluded = false,
            bedtimeDistanceMinutes = 20,
            isPastBedtime = true,
            isApproachingBedtime = false,
            continuousUsedMs = 2 * 60_000L,
            darkAccumulatedMs = 2 * 60_000L,
            alreadyTriggeredInSession = false
        )
        assertNull(tooEarly)

        // 3분 사용 + 암실 1분 이상: 취침 경과 암실 사전 개입 발동
        val triggered = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 2f,
            isOccluded = false,
            bedtimeDistanceMinutes = 20,
            isPastBedtime = true,
            isApproachingBedtime = false,
            continuousUsedMs = 3 * 60_000L,
            darkAccumulatedMs = 60_000L,
            alreadyTriggeredInSession = false
        )
        assertNotNull(triggered)
        assertEquals(PreInterventionKind.POST_BEDTIME_DARK, triggered!!.kind)
    }

    @Test
    fun preBedtimeDarkTriggersGentlePreInterventionAfterFiveMinutes() {
        val triggered = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 5f,
            isOccluded = false,
            bedtimeDistanceMinutes = -45,
            isPastBedtime = false,
            isApproachingBedtime = true,
            continuousUsedMs = 5 * 60_000L,
            darkAccumulatedMs = 2 * 60_000L,
            alreadyTriggeredInSession = false
        )
        assertNotNull(triggered)
        assertEquals(PreInterventionKind.PRE_BEDTIME_DARK, triggered!!.kind)
    }

    @Test
    fun generalDarkRoomTriggersGentlePreInterventionAfterSustainedDarkUsage() {
        val tooShortDark = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 8f,
            isOccluded = false,
            bedtimeDistanceMinutes = -400,
            isPastBedtime = false,
            isApproachingBedtime = false,
            continuousUsedMs = 10 * 60_000L,
            darkAccumulatedMs = 2 * 60_000L, // 암실 체류 2분뿐
            alreadyTriggeredInSession = false
        )
        assertNull(tooShortDark)

        val triggered = ContextualPreInterventionPolicy.evaluate(
            ambientLux = 8f,
            isOccluded = false,
            bedtimeDistanceMinutes = -400,
            isPastBedtime = false,
            isApproachingBedtime = false,
            continuousUsedMs = 8 * 60_000L,
            darkAccumulatedMs = 5 * 60_000L,
            alreadyTriggeredInSession = false
        )
        assertNotNull(triggered)
        assertEquals(PreInterventionKind.GENERAL_DARK, triggered!!.kind)
    }

    @Test
    fun occludedOrBrightOrAlreadyTriggeredNeverFires() {
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
                alreadyTriggeredInSession = false
            )
        )
        // 밝은 환경
        assertNull(
            ContextualPreInterventionPolicy.evaluate(
                ambientLux = 80f,
                isOccluded = false,
                bedtimeDistanceMinutes = 30,
                isPastBedtime = true,
                isApproachingBedtime = false,
                continuousUsedMs = 10 * 60_000L,
                darkAccumulatedMs = 10 * 60_000L,
                alreadyTriggeredInSession = false
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
                alreadyTriggeredInSession = true
            )
        )
    }

    @Test
    fun shouldPrecedeBedtimeCareOnlyOnceInPostBedtimeDarkRoom() {
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
