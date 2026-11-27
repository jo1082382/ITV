package com.example.itv.domain.jev

import org.junit.Assert.*
import org.junit.Test

class JevPolicyTest {
    private fun context(ms:Long=900_000,returns:Int=0,bedtime:Int?=null)=
        JevContext("session",JevSettings.INSTAGRAM,ms,ms,returns,600,bedtime,0)
    @Test fun probabilityBoundaryAndNonFiniteValues() {
        val settings=JevSettings()
        assertEquals(0.75, settings.probabilityThreshold, 0.0001)
        assertTrue(JevCandidatePolicy.passes(0.75,settings))
        assertFalse(JevCandidatePolicy.passes(0.749999,settings))
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
    @Test fun sessionBoundaryIsExactly900VerifiedSeconds() {
        val same=sessionAfterAway(899);assertEquals(same.first,same.second)
        val fresh=sessionAfterAway(900);assertNotEquals(fresh.first,fresh.second)
    }
    @Test fun screenOffFor3MinutesResetsSessionImmediately() {
        var id=0
        val tracker=JevSessionTracker {"id-${id++}"}
        tracker.observe(0,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        val before=tracker.states.getValue(JevSettings.INSTAGRAM).id
        tracker.observe(1000,"LOCKED",null)
        for(i in 2..180) tracker.observe(i*1000L,"LOCKED",null)
        tracker.observe(181_000L,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        val after=tracker.states.getValue(JevSettings.INSTAGRAM).id
        assertNotEquals(before, after)
    }
    @Test fun interruptionUnder90SecondsPreservesContinuousViewing() {
        val tracker=JevSessionTracker {"id"}
        tracker.observe(0,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        tracker.observe(1000,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        assertEquals(1000L, tracker.states.getValue(JevSettings.INSTAGRAM).continuousMs)
        for(i in 2..60) tracker.observe(i*1000L,"com.kakao.talk",null)
        tracker.observe(61_000L,JevSettings.INSTAGRAM,JevSettings.INSTAGRAM)
        assertEquals(2000L, tracker.states.getValue(JevSettings.INSTAGRAM).continuousMs)
    }
    @Test fun resetContinuousClearsContinuousViewingWhilePreservingSessionTotals() {
        val tracker = JevSessionTracker { "session-1" }
        tracker.observe(0, JevSettings.INSTAGRAM, JevSettings.INSTAGRAM)
        tracker.observe(1000, JevSettings.INSTAGRAM, JevSettings.INSTAGRAM)
        val state = tracker.states.getValue(JevSettings.INSTAGRAM)
        assertEquals(1000L, state.totalMs)
        assertEquals(1000L, state.continuousMs)

        // 미션 휴식 성공 시 resetContinuous 호출
        tracker.resetContinuous(JevSettings.INSTAGRAM)
        assertEquals(1000L, state.totalMs)
        assertEquals(0L, state.continuousMs)
        assertEquals("session-1", state.id)
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

    @Test fun continuousFatigueAndContentSwitchAdjustment() {
        val s = JevSettings(probabilityThreshold = 0.80, personalizedEnabled = true)
        val base = context(ms = 600_000L) // 10 min continuous

        // 1. 10분 연속 시청, 전환/스와이프 없음: 보정 없음 (0%p)
        val adj10 = JevCandidatePolicy.computeThresholdAdjustment(s, base)
        assertEquals(0.0, adj10.fatigueDelta, 0.001)
        assertEquals(0.0, adj10.longformSwitchDelta, 0.001)
        assertEquals(0.0, adj10.shortformSwipeCountDelta, 0.001)
        assertEquals(0.0, adj10.contentSwitchDelta, 0.001)

        // 2. 30분 연속 시청: 피로도 -2%p
        val base30 = base.copy(continuousMs = 1800_000L)
        val adj30 = JevCandidatePolicy.computeThresholdAdjustment(s, base30)
        assertEquals(-0.02, adj30.fatigueDelta, 0.001)
        assertTrue("CONTINUOUS_FATIGUE" in JevCandidatePolicy.reasons(base30, s))

        // 3. 45분 이상 연속 시청: 피로도 최대 -4%p
        val base45 = base.copy(continuousMs = 2700_000L)
        val adj45 = JevCandidatePolicy.computeThresholdAdjustment(s, base45)
        assertEquals(-0.04, adj45.fatigueDelta, 0.001)

        // 4. 유튜브 롱폼 영상 전환 횟수: 1회 0%p, 3회 -5%p, 5회 이상 -10%p
        assertEquals(0.0, JevCandidatePolicy.computeThresholdAdjustment(s, base.copy(consecutiveLongformChainCount = 1)).longformSwitchDelta, 0.001)
        val lf3 = base.copy(consecutiveLongformChainCount = 3)
        val adjLf3 = JevCandidatePolicy.computeThresholdAdjustment(s, lf3)
        assertEquals(-0.05, adjLf3.longformSwitchDelta, 0.001)
        assertEquals(-0.05, adjLf3.contentSwitchDelta, 0.001)
        assertTrue("LONGFORM_BINGE_CHAIN" in JevCandidatePolicy.reasons(lf3, s))
        val lf5 = base.copy(consecutiveLongformChainCount = 5)
        assertEquals(-0.10, JevCandidatePolicy.computeThresholdAdjustment(s, lf5).contentSwitchDelta, 0.001)

        // 5. 숏폼 누적 스와이프 수: 10회 0%p, 30회 -5%p, 50회 이상 -10%p
        assertEquals(0.0, JevCandidatePolicy.computeThresholdAdjustment(s, base.copy(shortformSwipeCount = 10)).shortformSwipeCountDelta, 0.001)
        val sf30 = base.copy(shortformSwipeCount = 30)
        val adjSf30 = JevCandidatePolicy.computeThresholdAdjustment(s, sf30)
        assertEquals(-0.05, adjSf30.shortformSwipeCountDelta, 0.001)
        assertEquals(-0.05, adjSf30.contentSwitchDelta, 0.001)
        assertTrue("SHORTFORM_SWIPE_COUNT" in JevCandidatePolicy.reasons(sf30, s))
        val sf50 = base.copy(shortformSwipeCount = 50)
        assertEquals(-0.10, JevCandidatePolicy.computeThresholdAdjustment(s, sf50).contentSwitchDelta, 0.001)

        // 6. 30분 연속 시청(-2%p) + 롱폼 3회 전환(-5%p) + 숏폼 20회(-2.5%p) -> contentSwitch -7.5%p, 총 -9.5%p (0.80 -> 0.705)
        val combined = base.copy(
            continuousMs = 1800_000L,
            consecutiveLongformChainCount = 3,
            shortformSwipeCount = 20
        )
        val adjCombined = JevCandidatePolicy.computeThresholdAdjustment(s, combined)
        assertEquals(-0.02, adjCombined.fatigueDelta, 0.001)
        assertEquals(-0.075, adjCombined.contentSwitchDelta, 0.001)
        assertEquals(0.705, adjCombined.effectiveThreshold, 0.001)
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

        // 3b. Just before bedtime (-45 min), pitch dark (0 lx) and dim screen (40/255)
        // -> Pre-bedtime dark protection remains up to -15%p
        val preBedtimeDark = base.copy(
            bedtimeDistanceMinutes = -45,
            ambientLux = 0f,
            screenBrightness = 40,
            isNearOrOccluded = false
        )
        val preBedtimeAdj = JevCandidatePolicy.computeThresholdAdjustment(s, preBedtimeDark)
        assertEquals(-0.15, preBedtimeAdj.bedtimeDelta, 0.001)
        assertEquals(0.35, preBedtimeAdj.effectiveThreshold, 0.001)

        // 4. Past bedtime (+30 min), pitch dark (0 lx) and dim screen (40/255)
        // -> Strengthened post-bedtime dark protection (-30%p)
        val midnightDark = base.copy(
            bedtimeDistanceMinutes = 30,
            ambientLux = 0f,
            screenBrightness = 40,
            isNearOrOccluded = false
        )
        val midnightAdj = JevCandidatePolicy.computeThresholdAdjustment(s, midnightDark)
        assertEquals(-0.30, midnightAdj.bedtimeDelta, 0.001)
        assertEquals(0.20, midnightAdj.effectiveThreshold, 0.001)

        // 4b. Default 75% base threshold in post-bedtime dark room (+30 min) -> 45% effective threshold
        val defaultSettings = JevSettings(personalizedEnabled = true)
        val defaultMidnightAdj = JevCandidatePolicy.computeThresholdAdjustment(defaultSettings, midnightDark)
        assertEquals(0.45, defaultMidnightAdj.effectiveThreshold, 0.001)
        assertTrue(JevCandidatePolicy.thresholdBreakdown(defaultSettings, midnightDark).contains("취침경과암실"))

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

        // 6. 권장 취침 시간대(예: 22:00~23:00, range=60m) 기반 점증 개입 확률 조정:
        //    -90분(2~1시간 전) < -30분(1~0시간 전) < +30분(권장 구간 내) < +90분(권장 종료 경과) 순으로 더 민감(더 음수)해야 함
        val recOuterDark = base.copy(
            recommendedBedtimeDistanceMinutes = -90,
            recommendedBedtimeRangeMinutes = 60,
            ambientLux = 2f,
            screenBrightness = 40,
            isNearOrOccluded = false
        )
        val recInnerDark = base.copy(
            recommendedBedtimeDistanceMinutes = -30,
            recommendedBedtimeRangeMinutes = 60,
            ambientLux = 2f,
            screenBrightness = 40,
            isNearOrOccluded = false
        )
        val recInsideDark = base.copy(
            recommendedBedtimeDistanceMinutes = 30,
            recommendedBedtimeRangeMinutes = 60,
            ambientLux = 2f,
            screenBrightness = 40,
            isNearOrOccluded = false
        )
        val recPastEndDark = base.copy(
            recommendedBedtimeDistanceMinutes = 90,
            recommendedBedtimeRangeMinutes = 60,
            ambientLux = 2f,
            screenBrightness = 40,
            isNearOrOccluded = false
        )
        val dOuter = JevCandidatePolicy.computeBedtimeEnvironmentDelta(recOuterDark)
        val dInner = JevCandidatePolicy.computeBedtimeEnvironmentDelta(recInnerDark)
        val dInside = JevCandidatePolicy.computeBedtimeEnvironmentDelta(recInsideDark)
        val dPastEnd = JevCandidatePolicy.computeBedtimeEnvironmentDelta(recPastEndDark)

        assertTrue("2~1h before rec bedtime should lower threshold ($dOuter)", dOuter < -0.04)
        assertTrue("1~0h before rec bedtime ($dInner) should lower threshold more than 2~1h before ($dOuter)", dInner < dOuter)
        assertTrue("Inside rec bedtime range ($dInside) should lower threshold more than pre-bedtime ($dInner)", dInside < dInner)
        assertTrue("Past rec bedtime end ($dPastEnd) should reach max protection (-0.30)", dPastEnd <= -0.295)
        assertTrue(JevCandidatePolicy.thresholdBreakdown(s, recPastEndDark).contains("권장취침경과암실"))

        // 7. 일반 실내 조명(30 lx)에서도 권장 취침 전 2시간/구간/경과에 따라 완만하게 기준 확률이 내려가야 함
        val recInnerIndoor = base.copy(
            recommendedBedtimeDistanceMinutes = -30,
            recommendedBedtimeRangeMinutes = 60,
            ambientLux = 30f,
            screenBrightness = 120,
            isNearOrOccluded = false
        )
        val recPastEndIndoor = base.copy(
            recommendedBedtimeDistanceMinutes = 90,
            recommendedBedtimeRangeMinutes = 60,
            ambientLux = 30f,
            screenBrightness = 120,
            isNearOrOccluded = false
        )
        val indoorInnerDelta = JevCandidatePolicy.computeBedtimeEnvironmentDelta(recInnerIndoor)
        val indoorPastDelta = JevCandidatePolicy.computeBedtimeEnvironmentDelta(recPastEndIndoor)
        assertTrue("Indoor 1h before rec bedtime should gently lower threshold ($indoorInnerDelta)", indoorInnerDelta < -0.03)
        assertTrue("Indoor past rec bedtime ($indoorPastDelta) should lower threshold more than before ($indoorInnerDelta)", indoorPastDelta < indoorInnerDelta)
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

    @Test fun dynamicReevaluationIntervalTiers() {
        // null or non-finite -> 기본 300초
        assertEquals(300, JevCandidatePolicy.calculateDynamicInterval(0.80, null))
        assertEquals(300, JevCandidatePolicy.calculateDynamicInterval(0.80, Double.NaN))

        // 1. 기준 초과 또는 일치 (gap <= 0%p): 최단 30초
        assertEquals(30, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.85))
        assertEquals(30, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.80))

        // 2. 작은 격차 (0%p < gap <= 15%p): 30초 ~ 90초 (빠른 재평가)
        assertEquals(60, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.725)) // gap 7.5%p -> 60초
        assertEquals(90, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.65))  // gap 15.0%p -> 90초

        // 3. 중간 격차 (15%p < gap <= 30%p): 90초 ~ 210초
        assertEquals(150, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.575)) // gap 22.5%p -> 150초
        assertEquals(210, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.50))  // gap 30.0%p -> 210초

        // 4. 큰 격차 (30%p < gap <= 50%p): 210초 ~ 360초
        assertEquals(285, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.40)) // gap 40.0%p -> 285초
        assertEquals(360, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.30)) // gap 50.0%p -> 360초

        // 5. 50%p 초과 격차: 최대 360초 고정
        assertEquals(360, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.10)) // gap 70.0%p -> 360초
    }

    @Test fun dynamicReevaluationIntervalTiersForLongform() {
        // null or non-finite -> 기본 360초
        assertEquals(360, JevCandidatePolicy.calculateDynamicInterval(0.80, null, isGeneralOrLongform = true))
        assertEquals(360, JevCandidatePolicy.calculateDynamicInterval(0.80, Double.NaN, isGeneralOrLongform = true))

        // 1. 기준 초과 또는 일치 (gap <= 0%p): 최단 등가 60초 (실사용 3분)
        assertEquals(60, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.85, isGeneralOrLongform = true))
        assertEquals(60, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.80, isGeneralOrLongform = true))

        // 2. 작은 격차 (0%p < gap <= 15%p): 등가 60초 ~ 120초 (실사용 3분 ~ 6분)
        assertEquals(90, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.725, isGeneralOrLongform = true))
        assertEquals(120, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.65, isGeneralOrLongform = true))

        // 3. 중간 격차 (15%p < gap <= 30%p): 등가 120초 ~ 240초 (실사용 6분 ~ 12분)
        assertEquals(180, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.575, isGeneralOrLongform = true))
        assertEquals(240, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.50, isGeneralOrLongform = true))

        // 4. 큰 격차 (30%p < gap <= 50%p): 등가 240초 ~ 360초 (실사용 12분 ~ 18분)
        assertEquals(300, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.40, isGeneralOrLongform = true))
        assertEquals(360, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.30, isGeneralOrLongform = true))

        // 5. 50%p 초과 격차: 최대 등가 360초 (실사용 18분) 고정
        assertEquals(360, JevCandidatePolicy.calculateDynamicInterval(0.80, 0.10, isGeneralOrLongform = true))
    }

    @Test fun youtubeLongformAccumulatesAtOneThirdRatio() {
        val tracker = JevSessionTracker { "id" }
        tracker.observe(0L, JevSettings.YOUTUBE, null)
        for (i in 1..90) {
            tracker.observe(i * 1000L, JevSettings.YOUTUBE, null)
        }
        val state = tracker.states.getValue(JevSettings.YOUTUBE)
        assertEquals(90_000L, state.rawGeneralMs)
        assertEquals(0L, state.rawShortformMs)
        // 90초 일반 롱폼 시청 -> 1:3 비율 적용으로 continuousMs는 30초(30,000ms)
        assertEquals(30_000L, state.continuousMs)
        assertEquals(30_000L, state.totalMs)
    }

    @Test fun allModeAppliesAllPersonalizedJudgmentsExceptSwipe() {
        val s = JevSettings(continuousSeconds = 600, returnsThreshold = 3, bedtimeDelayMinutes = 5)
        // continuousMs: 900,000ms (15분 등가 시간 = 45분 롱폼 실사용)
        val ctxAll = JevContext(
            sessionId = "s-all",
            packageName = "ALL",
            sessionMs = 900_000L,
            continuousMs = 900_000L,
            returns10m = 0,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = 10,
            observedAt = 1000L,
            quality = "VALID",
            sessionVsAvgRatio = 1.3, // 평소 세션 대비 1.3배
            effectiveSpm1m = 18.0,   // 스와이프가 있더라도 전체 모드에서는 무시되어야 함
            fastSwipeStreak = 5,
            swipeAccel1m = 4.0,
            swipeOverheated = true,
            ambientLux = 5f,
            screenBrightness = 20,
            bedtimeDistanceMinutes = 10,
            consecutiveLongformChainCount = 3,
            shortformSwipeCount = 20
        )

        // 1. Threshold adjustments
        val adj = JevCandidatePolicy.computeThresholdAdjustment(s, ctxAll)
        // 세션 과몰입 적용 (1.3배 -> -0.06)
        assertTrue(adj.sessionDelta < 0.0)
        // 스와이프 과열(SPM)은 전체 모드에서 0.0%p
        assertEquals(0.0, adj.swipeDelta, 0.001)
        // 취침 전 암실 적용 (-15%p 부근)
        assertTrue(adj.bedtimeDelta < 0.0)
        // 연속 시청 피로도 적용 (15분 등가 도달 -> 0%p ~ -2%p)
        assertTrue(adj.fatigueDelta <= 0.0)
        // 유튜브 롱폼 전환(3회 -> -5%p) + 숏폼 스와이프(20회 -> -2.5%p) = -7.5%p
        assertEquals(-0.05, adj.longformSwitchDelta, 0.001)
        assertEquals(-0.025, adj.shortformSwipeCountDelta, 0.001)
        assertEquals(-0.075, adj.contentSwitchDelta, 0.001)

        // 2. Candidate reasons
        val reasons = JevCandidatePolicy.reasons(ctxAll, s)
        assertTrue("CONTINUOUS" in reasons)
        assertTrue("PRE_BEDTIME_DARK_USAGE" in reasons || "BEDTIME" in reasons)
        assertTrue("LONGFORM_BINGE_CHAIN" in reasons)
        // 스와이프 과열은 전체 모드 후보 사유에서 제외
        assertFalse("RAPID_SWIPE_OVERHEAT" in reasons)
    }

    @Test fun optionBTargetsAllUserAppsWhileExcludingLaunchersAndSystem() {
        val s = JevSettings()
        assertTrue(s.targets("com.netflix.mediaclient"))
        assertTrue(s.targets("com.supercell.clashroyale"))
        assertTrue(s.targets("com.nhn.android.webtoon"))
        assertTrue(s.targets(JevSettings.YOUTUBE))
        assertTrue(s.targets(JevSettings.INSTAGRAM))
        assertTrue(s.targets("ALL"))

        // Exclusions
        assertFalse(s.targets("com.sec.android.app.launcher"))
        assertFalse(s.targets("com.google.android.apps.nexuslauncher"))
        assertFalse(s.targets("com.android.launcher3"))
        assertFalse(s.targets("com.android.systemui"))
        assertFalse(s.targets("com.android.settings"))
        assertFalse(s.targets("com.samsung.android.incallui"))
        assertFalse(s.targets("com.team.itv"))
        assertFalse(s.targets("com.example.itv"))
        assertFalse(s.targets("LOCKED"))
        assertFalse(s.targets("GUARD_BLOCKED"))

        // Disabling specific toggles
        assertFalse(s.copy(instagram = false).targets(JevSettings.INSTAGRAM))
        assertFalse(s.copy(youtube = false).targets(JevSettings.YOUTUBE))
    }

    @Test fun continuousUsageAccumulatesAcrossAppHopsInOptionB() {
        val tracker = JevSessionTracker { "session-all" }
        // 90초 YouTube 롱폼 시청
        for (i in 0..90) {
            tracker.observe(i * 1000L, JevSettings.YOUTUBE, null)
        }
        val ytContext = tracker.context(JevSettings.YOUTUBE, 90_000L, 90_000L, null)
        assertEquals(30_000L, ytContext?.continuousMs)
        assertEquals(90_000L, ytContext?.rawGeneralMs)

        // 이어서 앱 전환: 넷플릭스 90초 시청 (전환 지연 없이 연속)
        for (i in 91..180) {
            tracker.observe(i * 1000L, "com.netflix.mediaclient", null)
        }
        val netflixContext = tracker.context("com.netflix.mediaclient", 180_000L, 180_000L, null)
        // 폰 전체 통합 연속 사용 시간은 180초 일반 사용의 1/3인 60초 (60,000ms)
        assertEquals(60_000L, netflixContext?.continuousMs)
        assertEquals(180_000L, netflixContext?.rawGeneralMs)
        assertEquals(0L, netflixContext?.rawShortformMs)

        // ALL 컨텍스트도 일치
        val allContext = tracker.context("ALL", 180_000L, 180_000L, null)
        assertEquals(60_000L, allContext?.continuousMs)
        assertEquals(180_000L, allContext?.rawGeneralMs)
    }

    @Test fun generalAppExcludesSwipeOverheatFromReasonsAndThreshold() {
        val s = JevSettings(continuousSeconds = 600)
        val ctxGame = JevContext(
            sessionId = "s-game",
            packageName = "com.supercell.clashroyale",
            sessionMs = 900_000L,
            continuousMs = 900_000L,
            returns10m = 0,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = null,
            observedAt = 1000L,
            quality = "VALID",
            effectiveSpm1m = 20.0,
            fastSwipeStreak = 5,
            swipeAccel1m = 5.0,
            swipeOverheated = true
        )
        val adj = JevCandidatePolicy.computeThresholdAdjustment(s, ctxGame)
        assertEquals(0.0, adj.swipeDelta, 0.001)

        val reasons = JevCandidatePolicy.reasons(ctxGame, s)
        assertTrue("CONTINUOUS" in reasons)
        assertFalse("RAPID_SWIPE_OVERHEAT" in reasons)
    }

    @Test fun ownItvVariantsDoNotIncrementContinuousUsage() {
        val s = JevSettings()
        assertFalse(s.targets("com.team.itv"))
        assertFalse(s.targets("com.team.itv.teamtest"))
        assertFalse(s.targets("com.team.itv.qa"))
        assertFalse(s.targets("com.example.itv"))

        val tracker = JevSessionTracker { "s-itv" }
        // 숏폼 10초 시청 (1:1 -> 10초)
        for (i in 0..10) {
            tracker.observe(i * 1000L, JevSettings.YOUTUBE, JevSettings.YOUTUBE)
        }
        assertEquals(10_000L, tracker.context("ALL", 10_000L, 10_000L, null)?.continuousMs)

        // 우리 앱(.teamtest 포함) 20초 체류 -> 증가 0초, 기존 10초 보존
        for (i in 11..30) {
            tracker.observe(i * 1000L, "com.team.itv.teamtest", null)
        }
        assertEquals(10_000L, tracker.context("ALL", 30_000L, 30_000L, null)?.continuousMs)

        // 일반 앱 9초 사용 (1:3 -> +3초, 총 13초)
        for (i in 31..39) {
            tracker.observe(i * 1000L, "com.netflix.mediaclient", null)
        }
        assertEquals(13_000L, tracker.context("ALL", 39_000L, 39_000L, null)?.continuousMs)
    }

    @Test fun threeTierWeightingAccumulatesShortformMidAndLowAccurately() {
        val tracker = JevSessionTracker { "s-3tier" }
        // 1. 숏폼 10초 (1:1 -> 10초)
        for (i in 0..10) {
            tracker.observe(i * 1000L, JevSettings.YOUTUBE, JevSettings.YOUTUBE)
        }
        // 2. 인스타 피드/게임 30초 (1:2 -> 15초)
        for (i in 11..40) {
            tracker.observe(
                i * 1000L,
                JevSettings.INSTAGRAM,
                null,
                nonShortformTier = com.example.itv.domain.coaching.UsageWeightTier.MID_1_TO_2
            )
        }
        // 3. 유튜브 롱폼/일반 앱 30초 (1:3 -> 10초)
        for (i in 41..70) {
            tracker.observe(
                i * 1000L,
                "com.android.chrome",
                null,
                nonShortformTier = com.example.itv.domain.coaching.UsageWeightTier.LOW_1_TO_3
            )
        }
        val allCtx = tracker.context("ALL", 70_000L, 70_000L, null)!!
        assertEquals(10_000L, allCtx.rawShortformMs)
        assertEquals(30_000L, allCtx.rawMidWeightMs)
        assertEquals(60_000L, allCtx.rawGeneralMs) // 30s mid + 30s low
        // 총 환산: 10s + (30s / 2) + (30s / 3) = 35s
        assertEquals(35_000L, allCtx.continuousMs)
        assertEquals(35_000L, allCtx.sessionMs)
    }

    @Test fun tickJitterUpTo5SecondsDoesNotResetContinuousShortform() {
        val tracker = JevSessionTracker { "s-jitter" }
        // 정상 1초 틱 10초 누적
        for (i in 0..10) {
            tracker.observe(i * 1000L, JevSettings.YOUTUBE, JevSettings.YOUTUBE)
        }
        assertEquals(10_000L, tracker.context("ALL", 10_000L, 10_000L, null)?.continuousMs)

        // 실기기 메인 스레드 지터(2.5초 지연 틱) 발생 시에도 연속 시청이 0초로 초기화되지 않고 그대로 이어져야 함
        tracker.observe(12_500L, JevSettings.YOUTUBE, JevSettings.YOUTUBE)
        tracker.observe(13_500L, JevSettings.YOUTUBE, JevSettings.YOUTUBE)
        assertEquals(13_500L, tracker.context("ALL", 13_500L, 13_500L, null)?.continuousMs)
    }

    @Test fun fullOneToOneTierAccumulatesShortformEvenWhenConfirmedPkgIsNull() {
        val tracker = JevSessionTracker { "s-tier1" }
        tracker.observe(0L, JevSettings.YOUTUBE, JevSettings.YOUTUBE)
        tracker.observe(
            1_000L,
            JevSettings.YOUTUBE,
            null,
            nonShortformTier = com.example.itv.domain.coaching.UsageWeightTier.FULL_1_TO_1
        )
        tracker.observe(
            2_000L,
            JevSettings.YOUTUBE,
            null,
            nonShortformTier = com.example.itv.domain.coaching.UsageWeightTier.FULL_1_TO_1
        )
        val allCtx = tracker.context("ALL", 2_000L, 2_000L, null)!!
        assertEquals(2_000L, allCtx.rawShortformMs)
        assertEquals(2_000L, allCtx.continuousMs)
    }

    @Test fun restUsageClockRespectsJev180SecondNaturalRestThreshold() {
        val clock = com.example.itv.domain.coaching.RestUsageClock(com.example.itv.domain.coaching.TestCoachingPolicy.REST_MS)
        for (i in 0..10) {
            clock.observe(
                now = i * 1000L,
                using = true,
                weightTier = com.example.itv.domain.coaching.UsageWeightTier.FULL_1_TO_1,
                restThresholdMs = com.example.itv.domain.coaching.TestCoachingPolicy.RECOGNIZED_REST_MS
            )
        }
        assertEquals(10_000L, clock.usedMs)
        // Jev 모드에서 화면을 20초만 끈 경우(180초 미만) 사용량이 초기화되면 안 됨
        var resetTriggered = false
        for (i in 11..31) {
            if (clock.observe(
                    now = i * 1000L,
                    using = false,
                    weightTier = com.example.itv.domain.coaching.UsageWeightTier.LOW_1_TO_3,
                    restThresholdMs = com.example.itv.domain.coaching.TestCoachingPolicy.RECOGNIZED_REST_MS
                )
            ) {
                resetTriggered = true
            }
        }
        assertFalse(resetTriggered)
        assertEquals(10_000L, clock.usedMs)
    }

    @Test fun youtubeLongformAndInstagramFeedDoNotApplyShortformSwipePenalty() {
        val s = JevSettings(continuousSeconds = 600, personalizedEnabled = true)
        val youtubeLongform = JevContext(
            sessionId = "s-yt-lf",
            packageName = JevSettings.YOUTUBE,
            sessionMs = 900_000L,
            continuousMs = 900_000L,
            returns10m = 0,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = null,
            observedAt = 1000L,
            quality = "VALID",
            effectiveSpm1m = 18.0,
            fastSwipeStreak = 5,
            swipeAccel1m = 4.0,
            swipeOverheated = true,
            youtubeWatchMode = "LONGFORM_PORTRAIT",
            appCategory = "LONGFORM_VIDEO"
        )
        assertFalse(JevCandidatePolicy.isShortformContext(youtubeLongform))
        assertEquals(0.0, JevCandidatePolicy.computeThresholdAdjustment(s, youtubeLongform).swipeDelta, 0.001)
        assertFalse("RAPID_SWIPE_OVERHEAT" in JevCandidatePolicy.reasons(youtubeLongform, s))

        val instagramFeed = youtubeLongform.copy(
            packageName = JevSettings.INSTAGRAM,
            youtubeWatchMode = null,
            appCategory = "COMMUNITY_SNS"
        )
        assertFalse(JevCandidatePolicy.isShortformContext(instagramFeed))
        assertEquals(0.0, JevCandidatePolicy.computeThresholdAdjustment(s, instagramFeed).swipeDelta, 0.001)
        assertFalse("RAPID_SWIPE_OVERHEAT" in JevCandidatePolicy.reasons(instagramFeed, s))

        val youtubeShorts = youtubeLongform.copy(
            youtubeWatchMode = "SHORTS",
            appCategory = "SHORTFORM"
        )
        assertTrue(JevCandidatePolicy.isShortformContext(youtubeShorts))
        assertTrue(JevCandidatePolicy.computeThresholdAdjustment(s, youtubeShorts).swipeDelta < 0.0)
        assertTrue("RAPID_SWIPE_OVERHEAT" in JevCandidatePolicy.reasons(youtubeShorts, s))
    }

    @Test fun unifiedDarkLuxThresholdIs20LxAcrossPolicies() {
        val s = JevSettings(probabilityThreshold = 0.50, personalizedEnabled = true)
        val base = JevContext(
            sessionId = "s-lux",
            packageName = JevSettings.YOUTUBE,
            sessionMs = 600_000L,
            continuousMs = 600_000L,
            returns10m = 0,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = 20,
            observedAt = 1000L,
            quality = "VALID",
            bedtimeDistanceMinutes = 20,
            screenBrightness = 40,
            isNearOrOccluded = false
        )

        // 18 lx (<= 20 lx): 사전 개입 정책 & Jev 임계값 보정 모두 암실로 판정
        assertTrue(com.example.itv.domain.coaching.ContextualPreInterventionPolicy.isDarkRoom(18f, false))
        val darkAdj = JevCandidatePolicy.computeThresholdAdjustment(s, base.copy(ambientLux = 18f))
        assertTrue("18 lx should apply post-bedtime dark adjustment (< 0.0)", darkAdj.bedtimeDelta < 0.0)

        // 22 lx (> 20 lx): 사전 개입 정책 & Jev 임계값 보정 모두 암실 아님으로 일치
        assertFalse(com.example.itv.domain.coaching.ContextualPreInterventionPolicy.isDarkRoom(22f, false))
        val nonDarkAdj = JevCandidatePolicy.computeThresholdAdjustment(s, base.copy(ambientLux = 22f))
        assertEquals("22 lx should not apply dark-room penalty", 0.0, nonDarkAdj.bedtimeDelta, 0.001)
    }

    @Test fun restorePreservesPerTierTotalsAndRawTotalMs() {
        val tracker = JevSessionTracker { "s-restore" }
        // 숏폼 60초(1:1=60s) + 중가중치 120초(1:2=60s) + 저가중치 180초(1:3=60s)
        // 가중 합계 = 180초, 실사용 합계(totalRawMs) = 360초
        tracker.restore(
            pkg = "ALL",
            id = "restored-session",
            totalMs = 180_000L,
            entered = true,
            totalShortformMs = 60_000L,
            totalMidMs = 120_000L,
            totalLowMs = 180_000L
        )
        val restored = tracker.states.getValue("ALL")
        assertEquals("restored-session", restored.id)
        assertEquals(60_000L, restored.totalShortformMs)
        assertEquals(120_000L, restored.totalMidMs)
        assertEquals(180_000L, restored.totalLowMs)
        assertEquals(180_000L, restored.totalMs)
        assertEquals(360_000L, restored.totalRawMs)

        // 복구 후 일반 앱(1:3) 30초 추가 사용 시 기존 1:1/1:2/1:3 비율이 깨지지 않고 정확히 누적되어야 함
        tracker.observe(0L, "com.android.chrome", null)
        for (i in 1..30) {
            tracker.observe(i * 1000L, "com.android.chrome", null)
        }
        val afterMore = tracker.states.getValue("ALL")
        assertEquals(60_000L, afterMore.totalShortformMs)
        assertEquals(120_000L, afterMore.totalMidMs)
        assertEquals(210_000L, afterMore.totalLowMs)
        assertEquals(190_000L, afterMore.totalMs) // 60 + 60 + 70
        assertEquals(390_000L, afterMore.totalRawMs) // 60 + 120 + 210
    }

    @Test fun jevPercentFormatMatchesHudAndTestWindowWithoutHalfPercentDrift() {
        val s = JevSettings(probabilityThreshold = 0.70, personalizedEnabled = true)
        // 12회 숏폼 스와이프: -0.5%p -> 적용 기준 69.5% (기존 정수 반올림에서는 70%와 -1%p가 충돌하던 케이스)
        val ctx12 = JevContext(
            sessionId = "s-fmt",
            packageName = JevSettings.YOUTUBE,
            sessionMs = 120_000L,
            continuousMs = 120_000L,
            returns10m = 0,
            returnsObservedSeconds = 120,
            bedtimePassedMinutes = null,
            observedAt = 1000L,
            quality = "VALID",
            shortformSwipeCount = 12
        )
        val adj12 = JevCandidatePolicy.computeThresholdAdjustment(s, ctx12)
        assertEquals(-0.005, adj12.shortformSwipeCountDelta, 0.0001)
        assertEquals(0.695, adj12.effectiveThreshold, 0.0001)

        val baseTenths = JevPercentFormat.toTenthsPct(s.probabilityThreshold)
        val effTenths = JevPercentFormat.toTenthsPct(adj12.effectiveThreshold)
        val totalDeltaTenths = effTenths - baseTenths
        assertEquals("70", JevPercentFormat.formatTenths(baseTenths))
        assertEquals("69.5", JevPercentFormat.formatTenths(effTenths))
        assertEquals("-0.5%p", JevPercentFormat.formatSignedDeltaTenths(totalDeltaTenths))

        // HUD 여유 격차와 테스트 창 부족 격차가 항상 정확히 부호 반대로 일치해야 함
        val prob = 0.5434
        val probTenths = JevPercentFormat.toTenthsPct(prob)
        val hudGapTenths = effTenths - probTenths
        val testDiffTenths = probTenths - effTenths
        assertEquals("54.3", JevPercentFormat.formatTenths(probTenths))
        assertEquals("15.2", JevPercentFormat.formatTenths(hudGapTenths))
        assertEquals("-15.2%p", JevPercentFormat.formatSignedDeltaTenths(testDiffTenths))
        assertEquals(effTenths, probTenths + hudGapTenths)
    }
}
