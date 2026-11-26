package com.example.itv.application.coaching

import com.example.itv.domain.coaching.*

/** Coordinates existing test mission; owns no Android objects or Views. */
internal class TestMissionController(private val journal: CoachingJournal,
    private val history: MissionHistory, private val scheduler: MissionScheduler) {
    val state = TestRestMission()
    fun begin(
        id: String,
        now: Long,
        isJev: Boolean = false,
        requireFacedown: Boolean = false,
        isStrong: Boolean = false,
        continuousUsedMs: Long = 0L,
        customRestMs: Long? = null,
        rewardEligible: Boolean = true
    ) {
        val startMs = TestCoachingPolicy.missionStartMs(isJev)
        val restMs = customRestMs ?: TestCoachingPolicy.calculateRestMs(isJev, continuousUsedMs)
        journal.structured(id,"MISSION_REQUESTED")
        state.begin(id, now, startMs, restMs, requireFacedown, isStrong, rewardEligible); history.begin(id)
        scheduler.cancelStartTimeout(); scheduler.scheduleStartTimeout(startMs)
        val typeDesc = if (requireFacedown) "스마트폰 바닥에 엎어두기" else "화면을 연속 ${restMs / 1000L}초 끄기"
        val rewardSuffix = if (rewardEligible) "" else " · 미션 미수락(보상 미지급)"
        journal.add(id, "휴식 미션 시작 · $typeDesc (${restMs / 1000L}초)$rewardSuffix")
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
    fun failResume() {
        val id = state.id ?: return
        if (state.pending) return
        val remSec = (state.remainingRestMs + 999L) / 1000L
        journal.structured(id, "RESUME_NOT_OBSERVED")
        history.finish(id, "failed")
        journal.add(id, "미션 실패 · 10초 내 화면 꺼짐 미관측 (남은 휴식: ${remSec}초)")
        scheduler.cancelStartTimeout()
        state.clear()
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
                val accSec = state.accumulatedRestMs / 1000L
                val remSec = (state.remainingRestMs + 999L) / 1000L
                val reason = if (state.requireFacedown && !isFacedownObserved) "스마트폰 엎어두기 미유지" else "${duration / 1000}초 후 화면 켜짐"
                journal.add(id, "휴식 일시정지 · $reason (${accSec}초 인정 / 남은 시간 ${remSec}초)")
            }
            com.example.itv.domain.coaching.TestMissionWake.NONE -> Unit
        }
        return result
    }
    fun completionDisplayed() {
        val id = state.id ?: return
        val rewardEligible = state.rewardEligible
        state.displayed()
        journal.add(id, if (rewardEligible) "미션 완료 안내 표시" else "자동 휴식 완료 · 미션 미수락으로 보상 미지급")
    }
    fun stop() {
        state.id?.let { journal.structured(it,"INTERRUPTED"); history.finish(it, "interrupted") }
        scheduler.cancelStartTimeout()
        state.id?.let { journal.add(it, if (state.pending) "서비스 종료 · 완료 안내 대기 종료" else "서비스 종료 · 휴식 미션 중단") }
        state.clear()
    }
}
