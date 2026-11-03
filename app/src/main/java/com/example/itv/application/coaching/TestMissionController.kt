package com.example.itv.application.coaching

import com.example.itv.domain.coaching.*

/** Coordinates existing test mission; owns no Android objects or Views. */
internal class TestMissionController(private val journal: CoachingJournal,
    private val history: MissionHistory, private val scheduler: MissionScheduler) {
    val state = TestRestMission()
    fun begin(id: String, now: Long) {
        state.begin(id, now); history.begin(id)
        scheduler.cancelStartTimeout(); scheduler.scheduleStartTimeout(TestCoachingPolicy.MISSION_START_MS)
        journal.add(id, "휴식 미션 시작 · 화면을 연속 30초 끄기")
    }
    fun failStart() {
        val id = state.id ?: return
        if (state.started || state.pending) return
        history.finish(id, "failed")
        journal.add(id, "미션 실패 · 쉬기 선택 후 15초 안에 화면 꺼짐 미관측")
        scheduler.cancelStartTimeout(); state.failStart()
    }
    fun screenOff(now: Long) {
        if (state.id == null || state.pending || state.offAt != null) return
        if (state.isLate(now)) { failStart(); return }
        if (state.screenOff(now)) {
            scheduler.cancelStartTimeout()
            journal.add(state.id!!, "휴식 시작 · 15초 안에 화면 꺼짐 관측")
        }
    }
    fun screenOn(now: Long) {
        val id = state.id ?: return
        val (result, duration) = state.screenOn(now)
        when (result) {
            TestMissionWake.SUCCESS -> {
                history.finish(id, "success")
                journal.add(id, "휴식 미션 완료 · 화면 꺼짐 30초 이상 관측")
            }
            TestMissionWake.INCOMPLETE -> journal.add(id, "휴식 미완료 · ${duration / 1000}초 후 화면 켜짐 / 다시 30초 필요")
            TestMissionWake.NONE -> Unit
        }
    }
    fun completionDisplayed() {
        val id = state.id ?: return
        state.displayed(); journal.add(id, "미션 완료 안내 표시")
    }
    fun stop() {
        state.id?.let { history.finish(it, "interrupted") }
        scheduler.cancelStartTimeout()
        state.id?.let { journal.add(it, if (state.pending) "서비스 종료 · 완료 안내 대기 종료" else "서비스 종료 · 휴식 미션 중단") }
        state.clear()
    }
}
