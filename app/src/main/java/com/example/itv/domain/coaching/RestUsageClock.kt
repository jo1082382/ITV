package com.example.itv.domain.coaching

/** Monotonic sampled clock. Unknown gaps count as neither usage nor confirmed rest. */
internal class RestUsageClock(private val requiredRestMs: Long) {
    var usedMs = 0L
        private set
    var restMs = 0L
        private set
    var rawShortformMs = 0L
        private set
    var rawGeneralMs = 0L
        private set
    private var lastAt: Long? = null
    private var lastUsing: Boolean? = null

    fun clear() {
        usedMs = 0L; restMs = 0L; rawShortformMs = 0L; rawGeneralMs = 0L
        lastAt = null; lastUsing = null
    }

    /**
     * @param isShortform true이면 숏폼(1.0 가중치), false이면 일반 폰 사용(1:3 비율 중 1/3 가중치 적용: 3초당 1초 환산)
     * @param paused true이면 우리 앱(ITV) 확인 또는 비측정 시스템 화면 체류 중으로, 사용량과 휴식 시간을 모두 보존(일시정지)
     */
    fun observe(now: Long, using: Boolean, isShortform: Boolean = false, paused: Boolean = false): Boolean {
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
            if (isShortform) {
                rawShortformMs += delta
            } else {
                rawGeneralMs += delta
            }
            // 1:3 통합 가중치: 숏폼 사용량 + (일반 폰 전체 사용량 / 3)
            usedMs = rawShortformMs + (rawGeneralMs / 3L)
        }
        if (known && previous == false && !using) restMs += delta
        if (using) restMs = 0L
        lastAt = now; lastUsing = using
        if (restMs >= requiredRestMs) {
            clear()
            return true
        }
        return false
    }
}

