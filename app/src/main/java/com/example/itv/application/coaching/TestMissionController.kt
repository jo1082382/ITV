package com.example.itv.application.coaching

import com.example.itv.domain.coaching.*

/** Coordinates existing test mission; owns no Android objects or Views. */
internal class TestMissionController(private val journal: CoachingJournal,
    private val history: MissionHistory, private val scheduler: MissionScheduler) {
    val state = TestRestMission()
    fun begin(id: String, now: Long, isJev: Boolean = false, requireFacedown: Boolean = false, isStrong: Boolean = false) {
        val startMs = TestCoachingPolicy.missionStartMs(isJev)
        val restMs = TestCoachingPolicy.restMs(isJev)
        journal.structured(id,"MISSION_REQUESTED")
        state.begin(id, now, startMs, restMs, requireFacedown, isStrong); history.begin(id)
        scheduler.cancelStartTimeout(); scheduler.scheduleStartTimeout(startMs)
        val typeDesc = if (requireFacedown) "스마트폰 바닥에 엎어두기" else "화면을 연속 ${restMs / 1000L}초 끄기"
        journal.add(id, "휴식 미션 시작 · $typeDesc (${restMs / 1000L}초)")
    }
    fun refreshStartDeadline(now: Long, isJev: Boolean = false) {
        if (state.id == null || state.started || state.pending) return
        val startMs = TestCoachingPolicy.missionStartMs(isJev)
        state.startDeadline = now + startMs
        scheduler.cancelStartTimeout()
        scheduler.scheduleStartTimeout(startMs)
    }
    fun failStart() {
        val id = state.id ?: return
        if (state.started || state.pending) return
        val startSec = state.startWindowMs / 1000L
        journal.structured(id,"START_NOT_OBSERVED")
        history.finish(id, "failed")
        journal.add(id, "미션 실패 · 쉬기 선택 후 ${startSec}초 안에 화면 꺼짐 미관측")
        scheduler.cancelStartTimeout(); state.failStart()
    }
    fun screenOff(now: Long) {
        if (state.id == null || state.pending || state.offAt != null) return
        if (state.isLate(now)) { failStart(); return }
        if (state.screenOff(now)) {
            val startSec = state.startWindowMs / 1000L
            journal.structured(state.id!!,"REST_STARTED")
            scheduler.cancelStartTimeout()
            journal.add(state.id!!, "휴식 시작 · ${startSec}초 안에 화면 꺼짐 관측")
        }
    }
    fun screenOn(now: Long, isFacedownObserved: Boolean = true): com.example.itv.domain.coaching.TestMissionWake {
        val id = state.id ?: return com.example.itv.domain.coaching.TestMissionWake.NONE
        val restSec = state.requiredRestMs / 1000L
        val (result, duration) = state.screenOn(now, isFacedownObserved)
        when (result) {
            com.example.itv.domain.coaching.TestMissionWake.SUCCESS -> {
                journal.structured(id,"REST_COMPLETED")
                history.finish(id, "success")
                val successDesc = if (state.requireFacedown) "스마트폰 엎어두기 ${restSec}초 완료" else "화면 꺼짐 ${restSec}초 이상 관측"
                journal.add(id, "휴식 미션 완료 · $successDesc")
            }
            com.example.itv.domain.coaching.TestMissionWake.INCOMPLETE -> {
                val reason = if (state.requireFacedown && !isFacedownObserved) "스마트폰 엎어두기 미유지" else "${duration / 1000}초 후 화면 켜짐"
                journal.add(id, "휴식 미완료 · $reason / 다시 ${restSec}초 필요")
            }
            com.example.itv.domain.coaching.TestMissionWake.NONE -> Unit
        }
        return result
    }
    fun completionDisplayed() {
        val id = state.id ?: return
        state.displayed(); journal.add(id, "미션 완료 안내 표시")
    }
    fun stop() {
        state.id?.let { journal.structured(it,"INTERRUPTED"); history.finish(it, "interrupted") }
        scheduler.cancelStartTimeout()
        state.id?.let { journal.add(it, if (state.pending) "서비스 종료 · 완료 안내 대기 종료" else "서비스 종료 · 휴식 미션 중단") }
        state.clear()
    }
}
