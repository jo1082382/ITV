package com.example.itv.domain.jev

import org.junit.Assert.*
import org.junit.Test

class JevPolicyTest {
    private fun context(ms:Long=900_000,returns:Int=0,bedtime:Int?=null)=
        JevContext("session",JevSettings.INSTAGRAM,ms,ms,returns,600,bedtime,0)
    @Test fun probabilityBoundaryAndNonFiniteValues() {
        val settings=JevSettings()
        assertTrue(JevCandidatePolicy.passes(0.8,settings))
        assertFalse(JevCandidatePolicy.passes(0.799999,settings))
        assertFalse(JevCandidatePolicy.passes(Double.NaN,settings))
        assertFalse(JevCandidatePolicy.passes(Double.POSITIVE_INFINITY,settings))
    }
    @Test fun candidatesUseEnabledConditionsAndActualMeasurements() {
        val settings=JevSettings()
        assertEquals(listOf("CONTINUOUS"),JevCandidatePolicy.reasons(context(),settings))
        assertTrue(JevCandidatePolicy.reasons(context(899_999),settings).isEmpty())
        assertEquals(listOf("REPEATED_RETURNS"),JevCandidatePolicy.reasons(context(1000,3),settings))
        assertTrue(JevCandidatePolicy.reasons(context().copy(quality="INSUFFICIENT"),settings).isEmpty())
        assertTrue(JevCandidatePolicy.reasons(context(1000,0,null),settings).isEmpty())
        assertEquals(listOf("BEDTIME"),JevCandidatePolicy.reasons(context(1000,0,20),settings))
        assertTrue(JevCandidatePolicy.reasons(context(),settings.copy(continuousEnabled=false,returnsEnabled=false,bedtimeEnabled=false)).isEmpty())
    }
    @Test fun responseMustMatchRevisionSessionAppAndOriginalRequestDeadline() {
        val s=JevSettings()
        val c=context()
        assertTrue(JevCandidatePolicy.resultIsCurrent(1,s,c,c,30_000))
        assertFalse(JevCandidatePolicy.resultIsCurrent(1,s,c,c,30_001))
        assertFalse(JevCandidatePolicy.resultIsCurrent(1,s.copy(revision=2),c,c,1))
        assertFalse(JevCandidatePolicy.resultIsCurrent(1,s,c,c.copy(sessionId="other"),1))
        assertFalse(JevCandidatePolicy.resultIsCurrent(1,s,c,null,1))
    }
    @Test fun displayedReactionCanContinueBeyondRequestTtlWhileContextIsCurrent() {
        val s=JevSettings(enabled=true,collectionConsent=true,actualIntervention=true)
        val c=context()
        // The teammate's offer lasts 45 seconds; the 30-second TTL only gates first display.
        assertFalse(JevCandidatePolicy.resultIsCurrent(s.revision,s,c,c,45_000))
        assertTrue(JevCandidatePolicy.canContinueDisplayed(s.revision,s,c,c))
        assertFalse(JevCandidatePolicy.canContinueDisplayed(s.revision,s,c,c.copy(sessionId="new")))
        assertFalse(JevCandidatePolicy.canContinueDisplayed(s.revision,s,c,c.copy(packageName=JevSettings.YOUTUBE)))
        assertFalse(JevCandidatePolicy.canContinueDisplayed(s.revision,s,c,c.copy(quality="INSUFFICIENT")))
        assertFalse(JevCandidatePolicy.canContinueDisplayed(s.revision,s,c,null))
    }
    @Test fun changingSettingsOrConsentCancelsReactionAndMissionRetry() {
        val s=JevSettings(enabled=true,collectionConsent=true,actualIntervention=true)
        val c=context()
        listOf(s.copy(revision=s.revision+1),s.copy(enabled=false),s.copy(collectionConsent=false),
            s.copy(actualIntervention=false),s.copy(instagram=false)).forEach {
            assertFalse(JevCandidatePolicy.canContinueDisplayed(s.revision,it,c,c))
        }
    }
    @Test fun unknownGapsNeverAddViewingOrContinuousAbsence() {
        val tracker=JevSessionTracker {"id"}
        tracker.observe(0,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        tracker.observe(1000,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        assertEquals(1000L,tracker.states.getValue(JevSettings.INSTAGRAM).totalMs)
        tracker.observe(10_000,null,null)
        tracker.observe(11_000,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        assertEquals(1000L,tracker.states.getValue(JevSettings.INSTAGRAM).totalMs)
        assertEquals(0L,tracker.states.getValue(JevSettings.INSTAGRAM).continuousMs)
    }
    @Test fun firstEntryAndInternalNavigationAreNotReturns() {
        val tracker=JevSessionTracker {"id"}
        tracker.observe(0,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        tracker.observe(1000,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        assertEquals(0,tracker.states.getValue(JevSettings.INSTAGRAM).returns.size)
        tracker.observe(2000,"other.app",null)
        tracker.observe(3000,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        assertEquals(1,tracker.states.getValue(JevSettings.INSTAGRAM).returns.size)
    }
    private fun sessionAfterAway(seconds:Int):Pair<String,String> {
        var id=0
        val tracker=JevSessionTracker {"id-${id++}"}
        tracker.observe(0,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        val before=tracker.states.getValue(JevSettings.INSTAGRAM).id
        tracker.observe(1000,"other.app",null)
        for(i in 2..seconds)tracker.observe(i*1000L,"other.app",null)
        tracker.observe((seconds+1)*1000L,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        return before to tracker.states.getValue(JevSettings.INSTAGRAM).id
    }
    @Test fun sessionBoundaryIsExactly600VerifiedSeconds() {
        val same=sessionAfterAway(599);assertEquals(same.first,same.second)
        val fresh=sessionAfterAway(600);assertNotEquals(fresh.first,fresh.second)
    }
    @Test fun restartRestoresTotalsButNotContinuousViewing() {
        val tracker=JevSessionTracker {"new"}
        tracker.restore(JevSettings.INSTAGRAM,"saved",123_000,true)
        tracker.observe(100,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        assertEquals(123_000L,tracker.states.getValue(JevSettings.INSTAGRAM).totalMs)
        assertEquals(0L,tracker.states.getValue(JevSettings.INSTAGRAM).continuousMs)
    }
    @Test(expected=IllegalArgumentException::class) fun invalidProbabilitySettingIsRejected() {
        JevSettings(probabilityThreshold=Double.NaN).validated()
    }
    @Test fun personalizedAndSwipeMetricsAdjustCandidatesAndThresholdSafely() {
        val settings=JevSettings(probabilityThreshold=0.80, personalizedEnabled=true)
        // Without personal or swipe signals, threshold and candidate reasons remain unchanged
        val plain=context(ms=300_000)
        assertEquals(0.80, JevCandidatePolicy.effectiveThreshold(settings, plain), 0.0001)
        assertTrue(JevCandidatePolicy.reasons(plain, settings).isEmpty())

        // With personal session ratio >= 1.25 and swipe overheat, candidate triggers and linear adjustments lower threshold
        val overheated=plain.copy(
            sessionVsAvgRatio=1.50,
            effectiveSpm1m=15.0,
            swipeAccel1m=4.0,
            fastSwipeStreak=4
        )
        val reasons=JevCandidatePolicy.reasons(overheated, settings)
        assertTrue("PERSONAL_SESSION_EXCEEDED" in reasons)
        assertTrue("RAPID_SWIPE_OVERHEAT" in reasons)
        val adj = JevCandidatePolicy.computeThresholdAdjustment(settings, overheated)
        assertEquals(-0.10, adj.sessionDelta, 0.001)
        assertEquals(-0.10, adj.swipeDelta, 0.001)
        assertEquals(0.0, adj.phoneShareDelta, 0.001)
        assertEquals(0.60, adj.effectiveThreshold, 0.001)
        assertTrue(JevCandidatePolicy.passes(0.61, settings, overheated))
        assertFalse(JevCandidatePolicy.passes(0.59, settings, overheated))

        // When session is very short (half average), threshold raises by +0.10 to protect early viewing
        val earlyCalm = plain.copy(sessionVsAvgRatio = 0.50, effectiveSpm1m = 4.0)
        val earlyAdj = JevCandidatePolicy.computeThresholdAdjustment(settings, earlyCalm)
        assertEquals(0.10, earlyAdj.sessionDelta, 0.001)
        assertEquals(0.90, earlyAdj.effectiveThreshold, 0.001)

        // When personalizedEnabled is false, falls back to base threshold and base candidate rules
        val disabledSettings=settings.copy(personalizedEnabled=false)
        assertEquals(0.80, JevCandidatePolicy.effectiveThreshold(disabledSettings, overheated), 0.0001)
        assertTrue(JevCandidatePolicy.reasons(overheated, disabledSettings).isEmpty())
    }

    @Test fun continuousFatigueAndInterventionResistanceAdjustment() {
        val s = JevSettings(probabilityThreshold = 0.80, personalizedEnabled = true)
        val base = context(ms = 600_000L) // 10 min continuous

        // 1. 10분 연속 시청: 피로도 보정 없음 (0%p)
        val adj10 = JevCandidatePolicy.computeThresholdAdjustment(s, base)
        assertEquals(0.0, adj10.fatigueDelta, 0.001)
        assertEquals(0.0, adj10.resistanceDelta, 0.001)

        // 2. 30분 연속 시청: 피로도 -5%p
        val base30 = base.copy(continuousMs = 1800_000L)
        val adj30 = JevCandidatePolicy.computeThresholdAdjustment(s, base30)
        assertEquals(-0.05, adj30.fatigueDelta, 0.001)
        assertTrue("CONTINUOUS_FATIGUE" in JevCandidatePolicy.reasons(base30, s))

        // 3. 45분 이상 연속 시청: 피로도 최대 -10%p
        val base45 = base.copy(continuousMs = 2700_000L)
        val adj45 = JevCandidatePolicy.computeThresholdAdjustment(s, base45)
        assertEquals(-0.10, adj45.fatigueDelta, 0.001)

        // 4. 직전 개입에서 '나중에' 선택 후 계속 시청: 저항 -5%p
        val resistedLater = base.copy(
            lastInterventionOutcome = "LATER",
            postInterventionActivity = true,
            resistanceStreak = 1
        )
        val adjResisted = JevCandidatePolicy.computeThresholdAdjustment(s, resistedLater)
        assertEquals(-0.05, adjResisted.resistanceDelta, 0.001)
        assertTrue("INTERVENTION_RESISTANCE" in JevCandidatePolicy.reasons(resistedLater, s))

        // 5. 직전 개입에서 '무응답' 무시 후 스와이프 등 시청 지속: 저항 -6%p
        val ignoredNoResponse = base.copy(
            lastInterventionOutcome = "NO_RESPONSE",
            postInterventionActivity = true,
            resistanceStreak = 1
        )
        val adjIgnored = JevCandidatePolicy.computeThresholdAdjustment(s, ignoredNoResponse)
        assertEquals(-0.06, adjIgnored.resistanceDelta, 0.001)

        // 6. 30분 연속 시청 + 나중에 연속 2회 미룸: 피로(-5%p) + 저항(-8%p) = -13%p 보정 (0.80 -> 0.67)
        val combined = base.copy(
            continuousMs = 1800_000L,
            lastInterventionOutcome = "LATER",
            postInterventionActivity = true,
            resistanceStreak = 2
        )
        val adjCombined = JevCandidatePolicy.computeThresholdAdjustment(s, combined)
        assertEquals(-0.05, adjCombined.fatigueDelta, 0.001)
        assertEquals(-0.08, adjCombined.resistanceDelta, 0.001)
        assertEquals(0.67, adjCombined.effectiveThreshold, 0.001)
    }
    @Test fun bedtimeEnvironmentThresholdAdjustment() {
        val s = JevSettings(probabilityThreshold = 0.50, personalizedEnabled = true)
        val base = JevContext("session", JevSettings.YOUTUBE, 300_000L, 300_000L, 0, 600, null, 0L)

        // 1. Approaching bedtime (-20 min), but room is bright (150 lx) and screen is bright (200/255)
        // -> User is active with lights on, so not pre-bedtime dark usage (delta >= +0.05)
        val litRoomNearBedtime = base.copy(
            bedtimeDistanceMinutes = -20,
            ambientLux = 150f,
            screenBrightness = 200,
            isNearOrOccluded = false
        )
        val litAdj = JevCandidatePolicy.computeThresholdAdjustment(s, litRoomNearBedtime)
        assertTrue("Lit room near bedtime should raise threshold", litAdj.bedtimeDelta > 0.05)
        assertEquals(0.50 + litAdj.bedtimeDelta, litAdj.effectiveThreshold, 0.001)

        // 2. Near bedtime (-20 min), but proximity sensor is 0cm (occluded/covered by hand or pillow)
        // -> False dark reading: occlusion guard cancels dark assumption (delta == 0.0)
        val occludedNearBedtime = base.copy(
            bedtimeDistanceMinutes = -20,
            ambientLux = 0f,
            screenBrightness = 50,
            isNearOrOccluded = true
        )
        val occludedAdj = JevCandidatePolicy.computeThresholdAdjustment(s, occludedNearBedtime)
        assertEquals("Occlusion cancels dark room adjustment", 0.0, occludedAdj.bedtimeDelta, 0.001)

        // 3. 2.5 hours before bedtime (-150 min), lights off (5 lx) and screen dimmed (60/255)
        // -> Pre-bedtime dark usage detected early (user watching in dark room to sleep)
        val earlyDarkBedtime = base.copy(
            bedtimeDistanceMinutes = -150,
            ambientLux = 5f,
            screenBrightness = 60,
            isNearOrOccluded = false
        )
        val earlyDarkAdj = JevCandidatePolicy.computeThresholdAdjustment(s, earlyDarkBedtime)
        assertTrue("2~3 hours before bedtime in dark room lowers threshold", earlyDarkAdj.bedtimeDelta <= -0.10)
        assertTrue("PRE_BEDTIME_DARK_USAGE reason candidate triggered", "PRE_BEDTIME_DARK_USAGE" in JevCandidatePolicy.reasons(earlyDarkBedtime, s))

        // 4. Past bedtime (+30 min), pitch dark (0 lx) and dim screen (40/255)
        // -> Maximum bedtime protection (-15%p)
        val midnightDark = base.copy(
            bedtimeDistanceMinutes = 30,
            ambientLux = 0f,
            screenBrightness = 40,
            isNearOrOccluded = false
        )
        val midnightAdj = JevCandidatePolicy.computeThresholdAdjustment(s, midnightDark)
        assertEquals(-0.15, midnightAdj.bedtimeDelta, 0.001)
        assertEquals(0.35, midnightAdj.effectiveThreshold, 0.001)

        // 5. Broad daytime afternoon (-500 min), outdoors/bright room (400 lx, screen 220)
        // -> Full daytime relaxation (+15%p)
        val daytimeActive = base.copy(
            bedtimeDistanceMinutes = -500,
            ambientLux = 400f,
            screenBrightness = 220,
            isNearOrOccluded = false
        )
        val daytimeAdj = JevCandidatePolicy.computeThresholdAdjustment(s, daytimeActive)
        assertEquals(0.15, daytimeAdj.bedtimeDelta, 0.001)
        assertEquals(0.65, daytimeAdj.effectiveThreshold, 0.001)
    }
    @Test fun resetSessionClearsAccumulatedTotalsAndStartsFreshSession() {
        var seq=0
        val tracker=JevSessionTracker {"session-${++seq}"}
        tracker.observe(0,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        tracker.observe(1000,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        assertEquals("session-1",tracker.states.getValue(JevSettings.INSTAGRAM).id)
        assertEquals(1000L,tracker.states.getValue(JevSettings.INSTAGRAM).totalMs)

        tracker.resetSession(JevSettings.INSTAGRAM)
        tracker.observe(700_000,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        tracker.observe(701_000,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        val afterReset=tracker.states.getValue(JevSettings.INSTAGRAM)
        assertNotEquals("session-1",afterReset.id)
        assertEquals(1000L,afterReset.totalMs)
        assertEquals(1000L,afterReset.continuousMs)
        assertEquals(0,afterReset.returns.size)
    }
}
