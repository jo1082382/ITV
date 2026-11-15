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
    }
}
