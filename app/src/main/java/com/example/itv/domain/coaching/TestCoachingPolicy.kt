package com.example.itv.domain.coaching

/** Existing test settings, NOT finalized SDS production rules. */
internal object TestCoachingPolicy {
    // 표준 통합 시간 기준
    const val INTERRUPTION_TOLERANCE_MS = 90_000L   // 이탈 유예 시간: 90초 (1분 30초)
    const val RECOGNIZED_REST_MS = 180_000L          // 자발적 휴식 인정 시간: 180초 (3분)
    const val SESSION_TIMEOUT_MS = 900_000L          // 세션 만료 시간: 900초 (15분)

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
    fun naturalRestMs(isJev: Boolean): Long = if (isJev) RECOGNIZED_REST_MS else REST_MS

    /**
     * Calculates required rest duration scaled by session continuous usage.
     * In test mode: 15s (<10m), 20s (10m~25m), 30s (>=25m).
     * In normal/Jev mode: 30s (<15m), 180s (15m~30m), 300s (>=30m).
     */
    fun calculateRestMs(isJev: Boolean, continuousUsedMs: Long = 0L): Long {
        return if (!isJev) {
            when {
                continuousUsedMs >= 1500_000L -> 30_000L // 25분 이상 사용 시 30초
                continuousUsedMs >= 600_000L -> 20_000L  // 10분 이상 사용 시 20초
                else -> REST_MS                          // 15초
            }
        } else {
            when {
                continuousUsedMs >= 1800_000L -> 300_000L // 30분 이상 장기 사용 시 5분 휴식
                continuousUsedMs >= 900_000L -> 180_000L  // 15분 이상 중기 사용 시 3분 휴식
                continuousUsedMs >= 300_000L -> 120_000L  // 5분 이상 사용 시 2분 휴식
                else -> JEV_REST_MS                       // 기본 30초 휴식 (JEV_REST_MS)
            }
        }
    }
}
internal enum class TestMissionWake { NONE, INCOMPLETE, SUCCESS }
internal class TestRestMission {
    var id: String? = null
    var startDeadline = 0L
    var startWindowMs = TestCoachingPolicy.MISSION_START_MS
    var requiredRestMs = TestCoachingPolicy.REST_MS
    var accumulatedRestMs = 0L
    var started = false
    var offAt: Long? = null
    var pending = false
    var requireFacedown = false
    var isStrong = false
    var rewardEligible = true

    val remainingRestMs: Long
        get() = (requiredRestMs - accumulatedRestMs).coerceAtLeast(0L)

    fun begin(
        value: String,
        now: Long,
        startMs: Long = TestCoachingPolicy.MISSION_START_MS,
        restMs: Long = TestCoachingPolicy.REST_MS,
        requireFacedown: Boolean = false,
        isStrong: Boolean = false,
        rewardEligible: Boolean = true
    ) {
        id = value; offAt = null; pending = false; started = false
        startWindowMs = startMs
        requiredRestMs = restMs
        accumulatedRestMs = 0L
        startDeadline = now + startMs
        this.requireFacedown = requireFacedown
        this.isStrong = isStrong
        this.rewardEligible = rewardEligible
    }
    fun isLate(now: Long) = false // 휴식하기 전까지는 개입이 사라지지 않고 유지됨
    fun failStart() { id = null; offAt = null; startDeadline = 0L; accumulatedRestMs = 0L; requireFacedown = false; isStrong = false; rewardEligible = true }
    fun screenOff(now: Long): Boolean {
        if (id == null || pending || offAt != null) return false
        val first = !started
        started = true; offAt = now
        return first
    }
    fun screenOn(now: Long, isFacedownObserved: Boolean = true): Pair<TestMissionWake, Long> {
        val start = offAt ?: return TestMissionWake.NONE to 0L
        offAt = null
        if (id == null) return TestMissionWake.NONE to 0L
        val sessionDuration = (now - start).coerceAtLeast(0L)
        val facedownOk = !requireFacedown || isFacedownObserved
        if (facedownOk) {
            accumulatedRestMs += sessionDuration
        }
        val totalRest = accumulatedRestMs
        return if (totalRest >= requiredRestMs) {
            pending = true; TestMissionWake.SUCCESS to totalRest
        } else {
            // 아직 목표 휴식 시간이 남은 경우: 실패로 리셋하지 않고 지금까지 쉰 시간을 인정하고 유지
            TestMissionWake.INCOMPLETE to totalRest
        }
    }
    fun displayed() { pending = false; id = null; accumulatedRestMs = 0L; requireFacedown = false; isStrong = false; rewardEligible = true }
    fun clear() { id = null; pending = false; offAt = null; accumulatedRestMs = 0L; requireFacedown = false; isStrong = false; rewardEligible = true }
}
