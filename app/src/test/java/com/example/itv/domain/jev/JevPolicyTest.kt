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

    @Test fun continuousFatigueAndInterventionResistanceAdjustment() {
        val s = JevSettings(probabilityThreshold = 0.80, personalizedEnabled = true)
        val base = context(ms = 600_000L) // 10 min continuous

        // 1. 10분 연속 시청: 피로도 보정 없음 (0%p)
        val adj10 = JevCandidatePolicy.computeThresholdAdjustment(s, base)
        assertEquals(0.0, adj10.fatigueDelta, 0.001)
        assertEquals(0.0, adj10.resistanceDelta, 0.001)

        // 2. 30분 연속 시청: 피로도 -2%p
        val base30 = base.copy(continuousMs = 1800_000L)
        val adj30 = JevCandidatePolicy.computeThresholdAdjustment(s, base30)
        assertEquals(-0.02, adj30.fatigueDelta, 0.001)
        assertTrue("CONTINUOUS_FATIGUE" in JevCandidatePolicy.reasons(base30, s))

        // 3. 45분 이상 연속 시청: 피로도 최대 -4%p
        val base45 = base.copy(continuousMs = 2700_000L)
        val adj45 = JevCandidatePolicy.computeThresholdAdjustment(s, base45)
        assertEquals(-0.04, adj45.fatigueDelta, 0.001)

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

        // 6. 30분 연속 시청 + 나중에 연속 2회 미룸: 피로(-2%p) + 저항(-8%p) = -10%p 보정 (0.80 -> 0.70)
        val combined = base.copy(
            continuousMs = 1800_000L,
            lastInterventionOutcome = "LATER",
            postInterventionActivity = true,
            resistanceStreak = 2
        )
        val adjCombined = JevCandidatePolicy.computeThresholdAdjustment(s, combined)
        assertEquals(-0.02, adjCombined.fatigueDelta, 0.001)
        assertEquals(-0.08, adjCombined.resistanceDelta, 0.001)
        assertEquals(0.70, adjCombined.effectiveThreshold, 0.001)
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
            lastInterventionOutcome = "LATER",
            postInterventionActivity = true,
            resistanceStreak = 2
        )

        // 1. Threshold adjustments
        val adj = JevCandidatePolicy.computeThresholdAdjustment(s, ctxAll)
        // 세션 과몰입 적용 (1.3배 -> -0.06)
        assertTrue(adj.sessionDelta < 0.0)
        // 스와이프 과열은 전체 모드에서 반드시 0.0%p
        assertEquals(0.0, adj.swipeDelta, 0.001)
        // 취침 전 암실 적용 (-15%p 부근)
        assertTrue(adj.bedtimeDelta < 0.0)
        // 연속 시청 피로도 적용 (15분 등가 도달 -> 0%p ~ -2%p)
        assertTrue(adj.fatigueDelta <= 0.0)
        // 직전 개입 저항 적용 (2회 LATER -> -0.08)
        assertEquals(-0.08, adj.resistanceDelta, 0.001)

        // 2. Candidate reasons
        val reasons = JevCandidatePolicy.reasons(ctxAll, s)
        assertTrue("CONTINUOUS" in reasons)
        assertTrue("PRE_BEDTIME_DARK_USAGE" in reasons || "BEDTIME" in reasons)
        assertTrue("INTERVENTION_RESISTANCE" in reasons)
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
}
