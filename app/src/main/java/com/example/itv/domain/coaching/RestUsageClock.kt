package com.example.itv.domain.coaching

/**
 * 앱/화면 몰입도에 따른 3단계 사용 시간 가중치.
 * - [FULL_1_TO_1] (1:1): 숏폼 (YouTube 쇼츠, Instagram 릴스 등)
 * - [MID_1_TO_2]  (1:2): 피드·SNS (인스타 피드/탐색, 유튜브 홈/피드, 커뮤니티/SNS) 및 게임
 * - [LOW_1_TO_3]  (1:3): 유튜브 롱폼 영상, 웹·검색, 문서·메신저, 기타 일반 앱
 */
enum class UsageWeightTier(val divisor: Long, val label: String) {
    FULL_1_TO_1(1L, "1:1"),
    MID_1_TO_2(2L, "1:2"),
    LOW_1_TO_3(3L, "1:3")
}

/** Monotonic sampled clock. Unknown gaps count as neither usage nor confirmed rest. */
internal class RestUsageClock(private val requiredRestMs: Long) {
    var usedMs = 0L
        private set
    var restMs = 0L
        private set
    var rawShortformMs = 0L
        private set
    var rawMidWeightMs = 0L
        private set
    var rawLowWeightMs = 0L
        private set
    val rawGeneralMs: Long
        get() = rawMidWeightMs + rawLowWeightMs
    private var lastAt: Long? = null
    private var lastUsing: Boolean? = null

    fun clear() {
        usedMs = 0L; restMs = 0L; rawShortformMs = 0L; rawMidWeightMs = 0L; rawLowWeightMs = 0L
        lastAt = null; lastUsing = null
    }

    /**
     * @param isShortform true이면 숏폼(1:1), false이면 일반 앱(1:3)으로 환산 (하위 호환용 오버로드)
     * @param paused true이면 우리 앱(ITV) 확인 또는 비측정 시스템 화면 체류 중으로, 사용량과 휴식 시간을 모두 보존(일시정지)
     */
    fun observe(now: Long, using: Boolean, isShortform: Boolean = false, paused: Boolean = false): Boolean {
        val tier = if (isShortform) UsageWeightTier.FULL_1_TO_1 else UsageWeightTier.LOW_1_TO_3
        return observe(now, using, tier, paused)
    }

    /**
     * @param weightTier 3단계 가중치 ([UsageWeightTier.FULL_1_TO_1]=1:1, [UsageWeightTier.MID_1_TO_2]=1:2, [UsageWeightTier.LOW_1_TO_3]=1:3)
     * @param paused true이면 우리 앱(ITV) 확인 또는 비측정 시스템 화면 체류 중으로, 사용량과 휴식 시간을 모두 보존(일시정지)
     * @param restThresholdMs 자발적 휴식 인정 기준 시간 (테스트 모드 15초 / Jev 모드 180초)
     */
    fun observe(
        now: Long,
        using: Boolean,
        weightTier: UsageWeightTier,
        paused: Boolean = false,
        restThresholdMs: Long = requiredRestMs
    ): Boolean {
        if (paused) {
            lastAt = now
            lastUsing = null
            return false
        }
        val before = lastAt
        val previous = lastUsing
        val delta = if (before == null) 0L else now - before
        val known = before != null && delta in 0L..5000L
        if (!known) restMs = 0L
        if (known && previous == true && using) {
            when (weightTier) {
                UsageWeightTier.FULL_1_TO_1 -> rawShortformMs += delta
                UsageWeightTier.MID_1_TO_2 -> rawMidWeightMs += delta
                UsageWeightTier.LOW_1_TO_3 -> rawLowWeightMs += delta
            }
            // 3단계 통합 가중치: 숏폼(1:1) + 피드/SNS·게임(1:2) + 롱폼/일반 앱(1:3)
            usedMs = rawShortformMs + (rawMidWeightMs / 2L) + (rawLowWeightMs / 3L)
        }
        if (known && previous == false && !using) restMs += delta
        if (using) restMs = 0L
        lastAt = now; lastUsing = using
        if (restMs >= restThresholdMs) {
            clear()
            return true
        }
        return false
    }
}

