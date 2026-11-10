package com.example.itv.domain.coaching

/** Existing test settings, NOT finalized SDS production rules. */
internal object TestCoachingPolicy {
    // 테스트 모드 (강아지 캐릭터 빠른 확인용)
    const val INTERVENTION_MS = 30_000L
    const val REMINDER_MS = 30_000L
    const val RESPONSE_MS = 10_000L
    const val MISSION_START_MS = 10_000L
    const val REST_MS = 15_000L
    const val SAFE_MS = 5_000L

    // Jev 개입 모드 (테스트 모드 꺼짐 + Jev 개입 켜짐)
    const val JEV_RESPONSE_MS = 45_000L
    const val JEV_REMINDER_MS = 300_000L
    const val JEV_MISSION_START_MS = 10_000L
    const val JEV_REST_MS = 30_000L

    fun responseMs(isJev: Boolean): Long = if (isJev) JEV_RESPONSE_MS else RESPONSE_MS
    fun reminderMs(isJev: Boolean): Long = if (isJev) JEV_REMINDER_MS else REMINDER_MS
    fun missionStartMs(isJev: Boolean): Long = if (isJev) JEV_MISSION_START_MS else MISSION_START_MS
    fun restMs(isJev: Boolean): Long = if (isJev) JEV_REST_MS else REST_MS
}
internal enum class TestMissionWake { NONE, INCOMPLETE, SUCCESS }
internal class TestRestMission {
    var id: String? = null
    var startDeadline = 0L
    var startWindowMs = TestCoachingPolicy.MISSION_START_MS
    var requiredRestMs = TestCoachingPolicy.REST_MS
    var started = false
    var offAt: Long? = null
    var pending = false
    fun begin(
        value: String,
        now: Long,
        startMs: Long = TestCoachingPolicy.MISSION_START_MS,
        restMs: Long = TestCoachingPolicy.REST_MS
    ) {
        id = value; offAt = null; pending = false; started = false
        startWindowMs = startMs
        requiredRestMs = restMs
        startDeadline = now + startMs
    }
    fun isLate(now: Long) = id != null && !started && !pending && now > startDeadline
    fun failStart() { id = null; offAt = null; startDeadline = 0L }
    fun screenOff(now: Long): Boolean {
        if (id == null || pending || offAt != null) return false
        val first = !started
        started = true; offAt = now
        return first
    }
    fun screenOn(now: Long): Pair<TestMissionWake, Long> {
        val start = offAt ?: return TestMissionWake.NONE to 0L
        offAt = null
        if (id == null) return TestMissionWake.NONE to 0L
        val duration = now - start
        return if (duration >= requiredRestMs) {
            pending = true; TestMissionWake.SUCCESS to duration
        } else TestMissionWake.INCOMPLETE to duration
    }
    fun displayed() { pending = false; id = null }
    fun clear() { id = null; pending = false; offAt = null }
}
