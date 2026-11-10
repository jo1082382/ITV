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

        // With personal session ratio >= 1.25 and swipe overheat, candidate triggers and threshold lowers by 0.15 (to 0.65)
        val overheated=plain.copy(
            sessionVsAvgRatio=1.40,
            todayVs7dRatio=1.50,
            effectiveSpm1m=13.0,
            swipeAccel1m=4.0,
            fastSwipeStreak=4
        )
        val reasons=JevCandidatePolicy.reasons(overheated, settings)
        assertTrue("PERSONAL_SESSION_EXCEEDED" in reasons)
        assertTrue("RAPID_SWIPE_OVERHEAT" in reasons)
        assertEquals(0.65, JevCandidatePolicy.effectiveThreshold(settings, overheated), 0.0001)
        assertTrue(JevCandidatePolicy.passes(0.66, settings, overheated))
        assertFalse(JevCandidatePolicy.passes(0.64, settings, overheated))

        // When personalizedEnabled is false, falls back to base threshold and base candidate rules
        val disabledSettings=settings.copy(personalizedEnabled=false)
        assertEquals(0.80, JevCandidatePolicy.effectiveThreshold(disabledSettings, overheated), 0.0001)
        assertTrue(JevCandidatePolicy.reasons(overheated, disabledSettings).isEmpty())
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
