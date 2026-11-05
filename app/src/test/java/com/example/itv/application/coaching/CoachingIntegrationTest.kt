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
        assertEquals(ResponseOutcome.LATER,controller.choose("나중에",44_999))
        assertEquals(ResponseOutcome.IGNORED,controller.choose("닫기",44_999))
        assertEquals(listOf("jev-id" to "LATER"),journal.events)
    }
    @Test fun exactDeadlineIsTimeoutAndJournalUsesActualWindow() {
        val journal=Journal()
        val controller=CoachingResponseController(journal)
        controller.offerId="jev-id";controller.deadline=TestCoachingPolicy.RESPONSE_MS
        assertEquals(ResponseOutcome.EXPIRED,controller.choose("쉬기",45_000))
        assertTrue(journal.events.isEmpty())
        controller.timeout(45_000)
        assertEquals(listOf("jev-id" to "NO_RESPONSE"),journal.events)
        assertEquals("무응답 · 45초 동안 선택 없음",journal.text.single())
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
        mission.begin("jev-id",2800)
        assertEquals(15_000L,scheduled)
        mission.screenOff(17_800) // exactly 15 seconds after the reaction ended
        mission.screenOn(47_800)
        assertEquals(listOf("success"),outcomes)
        assertEquals(listOf("MISSION_REQUESTED","REST_STARTED","REST_COMPLETED"),journal.events.map {it.second})
        assertTrue(journal.events.all {it.first=="jev-id"})
    }
}
