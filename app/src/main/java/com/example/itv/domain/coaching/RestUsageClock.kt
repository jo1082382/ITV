package com.example.itv.domain.coaching

/** Monotonic sampled clock. Unknown gaps count as neither usage nor confirmed rest. */
internal class RestUsageClock(private val requiredRestMs: Long) {
    var usedMs = 0L
        private set
    var restMs = 0L
        private set
    private var lastAt: Long? = null
    private var lastUsing: Boolean? = null
    fun clear() {
        usedMs = 0L; restMs = 0L; lastAt = null; lastUsing = null
    }
    /** Returns true once when a sufficiently observed rest completes. */
    fun observe(now: Long, using: Boolean): Boolean {
        val before = lastAt
        val previous = lastUsing
        val delta = if (before == null) 0L else now - before
        val known = before != null && delta in 0L..5000L
        if (!known) restMs = 0L
        if (known && previous == true && using) usedMs += delta
        if (known && previous == false && !using) restMs += delta
        if (using) restMs = 0L
        lastAt = now; lastUsing = using
        if (restMs >= requiredRestMs) {
            usedMs = 0L; restMs = 0L
            return true
        }
        return false
    }
}
