package com.example.itv.application.coaching

import com.example.itv.domain.coaching.TestCoachingPolicy
import org.junit.Assert.*
import org.junit.Test

class CoachingIntegrationTest {
    private class Journal: CoachingJournal {
        val events=mutableListOf<Pair<String,String>>()
        val text=mutableListOf<String>()
        override fun add(id:String,event:String) { text.add(event) }
        override fun structured(id:String,kind:String) { events.add(id to kind) }
    }
    @Test fun teammateResponseWindowAcceptsLateChoiceOnce() {
        val journal=Journal()
        val controller=CoachingResponseController(journal)
        controller.offerId="jev-id";controller.deadline=TestCoachingPolicy.RESPONSE_MS
        assertEquals(ResponseOutcome.LATER,controller.choose("나중에",9_999))
        assertEquals(ResponseOutcome.IGNORED,controller.choose("닫기",9_999))
        assertEquals(listOf("jev-id" to "LATER"),journal.events)
    }
    @Test fun exactDeadlineIsTimeoutAndJournalUsesActualWindow() {
        val journal=Journal()
        val controller=CoachingResponseController(journal)
        controller.offerId="jev-id";controller.deadline=TestCoachingPolicy.RESPONSE_MS
        assertEquals(ResponseOutcome.EXPIRED,controller.choose("쉬기",10_000))
        assertTrue(journal.events.isEmpty())
        controller.timeout(10_000)
        assertEquals(listOf("jev-id" to "NO_RESPONSE"),journal.events)
        assertEquals("무응답 · 10초 동안 선택 없음",journal.text.single())
    }
    @Test fun missionDeadlineStartsAfterCharacterReactionAndKeepsDecisionId() {
        val journal=Journal()
        val outcomes=mutableListOf<String>()
        var scheduled=0L
        val mission=TestMissionController(journal,object:MissionHistory {
            override fun begin(id:String) {}
            override fun finish(id:String,result:String) { outcomes.add(result) }
        },object:MissionScheduler {
            override fun cancelStartTimeout() {}
            override fun scheduleStartTimeout(delayMs:Long) { scheduled=delayMs }
        })
        mission.begin("test-id",2800,isJev=false)
        assertEquals(10_000L,scheduled)
        mission.screenOff(12_800) // 10 seconds after reaction ended
        mission.screenOn(27_800) // 15 seconds rest in test mode
        assertEquals(listOf("success"),outcomes)
        assertEquals(listOf("MISSION_REQUESTED","REST_STARTED","REST_COMPLETED"),journal.events.map {it.second})
        assertTrue(journal.events.all {it.first=="test-id"})
    }
    @Test fun jevModeUsesFortyFiveSecondResponseFiveMinuteReminderAndThirtySecondRest() {
        val journal=Journal()
        val controller=CoachingResponseController(journal)
        controller.offerId="jev-id"
        val responseMs=TestCoachingPolicy.responseMs(isJev=true)
        val reminderMs=TestCoachingPolicy.reminderMs(isJev=true)
        assertEquals(45_000L,responseMs)
        assertEquals(300_000L,reminderMs)
        controller.deadline=responseMs
        controller.timeout(45_000L,responseMs,reminderMs)
        assertEquals("무응답 · 45초 동안 선택 없음",journal.text.single())
        assertEquals(345_000L,controller.retryAt)

        val outcomes=mutableListOf<String>()
        var scheduled=0L
        val mission=TestMissionController(journal,object:MissionHistory {
            override fun begin(id:String) {}
            override fun finish(id:String,result:String) { outcomes.add(result) }
        },object:MissionScheduler {
            override fun cancelStartTimeout() {}
            override fun scheduleStartTimeout(delayMs:Long) { scheduled=delayMs }
        })
        mission.begin("jev-id",2800,isJev=true)
        assertEquals(10_000L,scheduled)
        mission.screenOff(12_800) // 10s start limit
        mission.screenOn(27_800) // only 15s off -> incomplete in Jev mode (needs 30s)
        assertTrue(outcomes.isEmpty())
        mission.screenOff(30_000)
        mission.screenOn(60_000) // 30s off -> success in Jev mode
        assertEquals(listOf("success"),outcomes)
    }
    @Test fun missionStartsImmediatelyAndSupportsEarlyScreenOffDuringReaction() {
        val journal=Journal()
        val outcomes=mutableListOf<String>()
        var cancelled=false
        val mission=TestMissionController(journal,object:MissionHistory {
            override fun begin(id:String) {}
            override fun finish(id:String,result:String) { outcomes.add(result) }
        },object:MissionScheduler {
            override fun cancelStartTimeout() { cancelled=true }
            override fun scheduleStartTimeout(delayMs:Long) {}
        })
        mission.begin("early-id", 0L, isJev=true)
        assertTrue(mission.state.id != null)
        assertFalse(mission.state.started)

        // Screen turned off 1s into the 2.8s reaction
        mission.screenOff(1000L)
        assertTrue(mission.state.started)
        assertTrue(cancelled)

        // Window disappears 2.8s later and attempts to refresh deadline, but should not affect already started mission
        mission.refreshStartDeadline(2800L, isJev=true)
        assertTrue(mission.state.started)

        // 30 seconds of rest completed
        mission.screenOn(31_000L)
        assertEquals(listOf("success"), outcomes)
        assertTrue(mission.state.pending)
    }
    @Test fun missionRefreshesStartDeadlineWhenUserWaitsUntilReactionEnds() {
        val journal=Journal()
        val outcomes=mutableListOf<String>()
        var scheduled=0L
        val mission=TestMissionController(journal,object:MissionHistory {
            override fun begin(id:String) {}
            override fun finish(id:String,result:String) { outcomes.add(result) }
        },object:MissionScheduler {
            override fun cancelStartTimeout() {}
            override fun scheduleStartTimeout(delayMs:Long) { scheduled=delayMs }
        })
        mission.begin("wait-id", 0L, isJev=false)
        assertEquals(10_000L, scheduled)

        // 2.8s later, reaction finishes and window disappears
        mission.refreshStartDeadline(2800L, isJev=false)
        assertEquals(10_000L, scheduled)
        assertEquals(12_800L, mission.state.startDeadline)

        // Screen off at 12_000L (9.2s after reaction, would have been late if initial deadline wasn't refreshed)
        mission.screenOff(12_000L)
        assertTrue(mission.state.started)

        // 15s rest completed
        mission.screenOn(27_000L)
        assertEquals(listOf("success"), outcomes)
    }
    @Test fun missionFailsWhenScreenNotTurnedOffInTime() {
        val journal=Journal()
        val outcomes=mutableListOf<String>()
        val mission=TestMissionController(journal,object:MissionHistory {
            override fun begin(id:String) {}
            override fun finish(id:String,result:String) { outcomes.add(result) }
        },object:MissionScheduler {
            override fun cancelStartTimeout() {}
            override fun scheduleStartTimeout(delayMs:Long) {}
        })
        mission.begin("fail-start-id", 0L, isJev=true)
        mission.failStart()
        assertEquals(listOf("failed"), outcomes)
        assertEquals(listOf("MISSION_REQUESTED","START_NOT_OBSERVED"), journal.events.map { it.second })
    }
    @Test fun missionScreenOnBeforeRestDurationReturnsIncomplete() {
        val journal=Journal()
        val outcomes=mutableListOf<String>()
        val mission=TestMissionController(journal,object:MissionHistory {
            override fun begin(id:String) {}
            override fun finish(id:String,result:String) { outcomes.add(result) }
        },object:MissionScheduler {
            override fun cancelStartTimeout() {}
            override fun scheduleStartTimeout(delayMs:Long) {}
        })
        mission.begin("early-wake-id", 0L, isJev=true)
        mission.screenOff(5_000L)
        val wake = mission.screenOn(15_000L) // Only 10s of rest, needs 30s
        assertEquals(com.example.itv.domain.coaching.TestMissionWake.INCOMPLETE, wake)
        assertTrue(outcomes.isEmpty())
        assertFalse(mission.state.pending)
    }
    @Test fun facedownRestMissionRequiresFacedownToSucceed() {
        val journal=Journal()
        val outcomes=mutableListOf<String>()
        val mission=TestMissionController(journal,object:MissionHistory {
            override fun begin(id:String) {}
            override fun finish(id:String,result:String) { outcomes.add(result) }
        },object:MissionScheduler {
            override fun cancelStartTimeout() {}
            override fun scheduleStartTimeout(delayMs:Long) {}
        })
        mission.begin("facedown-id", 0L, isJev=false, requireFacedown=true)
        mission.screenOff(2_000L)

        // 15 seconds elapsed, but facedown was NOT observed
        val failedWake = mission.screenOn(18_000L, isFacedownObserved=false)
        assertEquals(com.example.itv.domain.coaching.TestMissionWake.INCOMPLETE, failedWake)
        assertTrue(outcomes.isEmpty())

        // Try again with facedown observed
        mission.screenOff(20_000L)
        val successWake = mission.screenOn(36_000L, isFacedownObserved=true)
        assertEquals(com.example.itv.domain.coaching.TestMissionWake.SUCCESS, successWake)
        assertEquals(listOf("success"), outcomes)
    }
    @Test fun restMissionCatalogPicksAppropriateRecommendations() {
        val strongRec = com.example.itv.domain.coaching.RestMissionCatalog.pick(
            com.example.itv.domain.coaching.UserPosture.LYING_DOWN,
            isStrong = true
        )
        assertTrue(strongRec.isFacedownRequired)
        assertTrue(strongRec.title.contains("엎어두기"))

        val lyingRec = com.example.itv.domain.coaching.RestMissionCatalog.pick(
            com.example.itv.domain.coaching.UserPosture.LYING_DOWN,
            isStrong = false
        )
        assertFalse(lyingRec.isFacedownRequired)
        assertTrue(lyingRec.targetPosture == com.example.itv.domain.coaching.UserPosture.LYING_DOWN || lyingRec.targetPosture == null)

        val sittingRec = com.example.itv.domain.coaching.RestMissionCatalog.pick(
            com.example.itv.domain.coaching.UserPosture.SITTING_OR_STANDING,
            isStrong = false
        )
        assertFalse(sittingRec.isFacedownRequired)
        assertTrue(sittingRec.targetPosture == com.example.itv.domain.coaching.UserPosture.SITTING_OR_STANDING || sittingRec.targetPosture == null)

        val tableRec = com.example.itv.domain.coaching.RestMissionCatalog.pick(
            com.example.itv.domain.coaching.UserPosture.TABLE_OR_DESK,
            isStrong = false
        )
        assertFalse(tableRec.isFacedownRequired)
        assertTrue(tableRec.targetPosture == com.example.itv.domain.coaching.UserPosture.TABLE_OR_DESK || tableRec.targetPosture == null)

        com.example.itv.domain.coaching.RestMissionCatalog.ALL_RECOMMENDATIONS.forEach { rec ->
            assertTrue("미션 '${rec.title}'에 굵게 표시할 행동 문구가 있어야 함", rec.boldPhrases.isNotEmpty())
            rec.boldPhrases.forEach { phrase ->
                assertTrue("미션 '${rec.title}'의 action에 굵은 글씨 문구('$phrase')가 포함되어야 함", rec.action.contains(phrase))
            }
        }
    }

    @Test fun generalUsageTriggersOfferAtThreeTimesDuration() {
        val journal = Journal()
        val controller = CoachingResponseController(journal)
        var offerShown = false
        val effects = object : CoachingProgressEffects {
            override fun status(value: String) {}
            override fun hideOffer() { offerShown = false }
            override fun showOffer() { offerShown = true }
        }
        val coordinator = CoachingProgressCoordinator(controller, effects)
        // 일반 사용 (isShortform = false)
        // interventionMs는 30_000L. 일반 사용은 3초당 1초 환산이므로 90초 필요.
        coordinator.observe(0L, using = true, offerVisible = false, isShortform = false)
        coordinator.tick(0L, using = true, com.example.itv.domain.coaching.CoachingObservation(using = true, guardReason = null, youtubeState = 0, reelsState = 0), offerVisible = false)

        for (i in 1..89) {
            coordinator.observe(i * 1000L, using = true, offerVisible = false, isShortform = false)
            coordinator.tick(i * 1000L, using = true, com.example.itv.domain.coaching.CoachingObservation(using = true, guardReason = null, youtubeState = 0, reelsState = 0), offerVisible = false)
            assertFalse("89초(환산 29.6초)에는 아직 개입이 트리거되지 않아야 함", offerShown)
        }

        // 90초 도달 (환산 30초 >= 30,000L)
        coordinator.observe(90_000L, using = true, offerVisible = false, isShortform = false)
        coordinator.tick(90_000L, using = true, com.example.itv.domain.coaching.CoachingObservation(using = true, guardReason = null, youtubeState = 0, reelsState = 0), offerVisible = false)
        assertTrue("90초 일반 사용(1:3 비율 환산 30초) 도달 시 개입 표시되어야 함", offerShown)
    }

    @Test fun youtubeLongformWatchingHoldsOfferUntilTransition() {
        val journal = Journal()
        val controller = CoachingResponseController(journal)
        var offerShown = false
        val statuses = mutableListOf<String>()
        val effects = object : CoachingProgressEffects {
            override fun status(value: String) { statuses.add(value) }
            override fun hideOffer() { offerShown = false }
            override fun showOffer() { offerShown = true }
        }
        val coordinator = CoachingProgressCoordinator(controller, effects)
        coordinator.observe(0L, using = true, offerVisible = false, isShortform = true)
        coordinator.tick(
            0L,
            using = true,
            com.example.itv.domain.coaching.CoachingObservation(using = true, guardReason = null, youtubeState = 0, reelsState = 0, youtubeLongformWatching = true),
            offerVisible = false
        )

        for (i in 1..35) {
            coordinator.observe(i * 1000L, using = true, offerVisible = false, isShortform = true)
            coordinator.tick(
                i * 1000L,
                using = true,
                com.example.itv.domain.coaching.CoachingObservation(using = true, guardReason = null, youtubeState = 0, reelsState = 0, youtubeLongformWatching = true),
                offerVisible = false
            )
        }

        // 30초 기준을 넘었더라도 롱폼 영상 시청 중(youtubeLongformWatching == true)에는 즉시 개입하지 않고 전환 대기 상태여야 함
        assertFalse("롱폼 영상 시청 중에는 중간에 개입이 표시되지 않아야 함", offerShown)
        assertTrue("롱폼 종료/전환 대기 플래그(waitingTransition)가 활성화되어야 함", coordinator.waitingTransition)
        assertTrue(statuses.last().contains("롱폼 영상 종료/전환을 기다리는 중"))
    }

    @Test fun missionAccumulatesRestAcrossPrematureWakeAndCompletesWhenTotalMet() {
        val journal = Journal()
        val outcomes = mutableListOf<String>()
        val mission = TestMissionController(journal, object : MissionHistory {
            override fun begin(id: String) {}
            override fun finish(id: String, result: String) { outcomes.add(result) }
        }, object : MissionScheduler {
            override fun cancelStartTimeout() {}
            override fun scheduleStartTimeout(delayMs: Long) {}
        })
        // 15초(15,000ms) 목표 휴식 미션 시작
        mission.begin("accum-id", 0L, isJev = false, customRestMs = 15_000L)
        assertEquals(15_000L, mission.state.requiredRestMs)

        // 1초에 화면 끄기 -> 7초에 의도치 않게 화면 켜짐 (6초 휴식 인정)
        mission.screenOff(1000L)
        val wake1 = mission.screenOn(7000L)
        assertEquals(com.example.itv.domain.coaching.TestMissionWake.INCOMPLETE, wake1)
        assertEquals(6000L, mission.state.accumulatedRestMs)
        assertEquals(9000L, mission.state.remainingRestMs)
        assertTrue(outcomes.isEmpty()) // 아직 실패나 성공 아님

        // 10초에 다시 화면 끄고 휴식 이어하기 -> 19초에 화면 켜짐 (9초 추가 휴식 -> 총 15초 달성)
        mission.screenOff(10_000L)
        val wake2 = mission.screenOn(19_000L)
        assertEquals(com.example.itv.domain.coaching.TestMissionWake.SUCCESS, wake2)
        assertEquals(15_000L, mission.state.accumulatedRestMs)
        assertEquals(0L, mission.state.remainingRestMs)
        assertEquals(listOf("success"), outcomes)
        assertTrue(mission.state.pending)
    }

    @Test fun missionFailsWhenResumeTimeoutOccurs() {
        val journal = Journal()
        val outcomes = mutableListOf<String>()
        val mission = TestMissionController(journal, object : MissionHistory {
            override fun begin(id: String) {}
            override fun finish(id: String, result: String) { outcomes.add(result) }
        }, object : MissionScheduler {
            override fun cancelStartTimeout() {}
            override fun scheduleStartTimeout(delayMs: Long) {}
        })
        mission.begin("fail-resume-id", 0L, isJev = false, customRestMs = 15_000L)
        mission.screenOff(1000L)
        val wake = mission.screenOn(6000L) // 5초 휴식 후 켜짐
        assertEquals(com.example.itv.domain.coaching.TestMissionWake.INCOMPLETE, wake)
        assertEquals(5000L, mission.state.accumulatedRestMs)

        // 10초 동안 화면 끄기 미수행으로 재개 타임아웃 발생
        mission.failResume()
        assertEquals(listOf("failed"), outcomes)
        assertNull(mission.state.id)
        assertEquals(0L, mission.state.accumulatedRestMs)
    }

    @Test fun autoStartedStrongMissionBlocksBypassAndWithholdsRewardOnCompletion() {
        val journal = Journal()
        val outcomes = mutableListOf<String>()
        val mission = TestMissionController(journal, object : MissionHistory {
            override fun begin(id: String) {}
            override fun finish(id: String, result: String) { outcomes.add(result) }
        }, object : MissionScheduler {
            override fun cancelStartTimeout() {}
            override fun scheduleStartTimeout(delayMs: Long) {}
        })

        // 강한 개입 표시 중 미션을 수락하지 않고 화면만 껐을 때 (rewardEligible = false)
        mission.begin(
            id = "strong-auto-1",
            now = 0L,
            isJev = false,
            requireFacedown = true,
            isStrong = true,
            customRestMs = 15_000L,
            rewardEligible = false
        )
        assertFalse(mission.state.rewardEligible)
        assertTrue(mission.state.isStrong)
        assertTrue(journal.text.any { it.contains("미션 미수락(보상 미지급)") })

        // 잠깐 2초 껐다 켜면(엎어둠 미유지) INCOMPLETE 반환 -> 강한 개입 재표시 대상 유지
        mission.screenOff(1_000L)
        val bypassWake = mission.screenOn(3_000L, isFacedownObserved = false)
        assertEquals(com.example.itv.domain.coaching.TestMissionWake.INCOMPLETE, bypassWake)
        assertEquals(0L, mission.state.accumulatedRestMs)
        assertEquals(15_000L, mission.state.remainingRestMs)
        assertFalse(mission.state.rewardEligible)

        // 이후 실제로 엎어두고 15초 휴식 완료 시 SUCCESS, 단 rewardEligible은 여전히 false
        mission.screenOff(5_000L)
        val fullWake = mission.screenOn(20_000L, isFacedownObserved = true)
        assertEquals(com.example.itv.domain.coaching.TestMissionWake.SUCCESS, fullWake)
        assertTrue(mission.state.pending)
        assertFalse(mission.state.rewardEligible)
        assertEquals(listOf("success"), outcomes)

        // 보상 팝업 없이 완료 정리 시 보상 미지급 로그 기록 후 다음 미션을 위해 rewardEligible 복원
        mission.completionDisplayed()
        assertTrue(journal.text.any { it.contains("보상 미지급") })
        assertNull(mission.state.id)
        assertFalse(mission.state.pending)
        assertTrue(mission.state.rewardEligible)
    }

    @Test fun explicitlyAcceptedStrongMissionRemainsRewardEligible() {
        val journal = Journal()
        val mission = TestMissionController(journal, object : MissionHistory {
            override fun begin(id: String) {}
            override fun finish(id: String, result: String) {}
        }, object : MissionScheduler {
            override fun cancelStartTimeout() {}
            override fun scheduleStartTimeout(delayMs: Long) {}
        })

        mission.begin(
            id = "strong-accepted-1",
            now = 0L,
            isJev = false,
            requireFacedown = true,
            isStrong = true,
            customRestMs = 15_000L,
            rewardEligible = true
        )
        assertTrue(mission.state.rewardEligible)

        mission.screenOff(1_000L)
        val wake = mission.screenOn(16_000L, isFacedownObserved = true)
        assertEquals(com.example.itv.domain.coaching.TestMissionWake.SUCCESS, wake)
        assertTrue(mission.state.rewardEligible)
        mission.completionDisplayed()
        assertTrue(journal.text.any { it.contains("미션 완료 안내 표시") })
    }
}

