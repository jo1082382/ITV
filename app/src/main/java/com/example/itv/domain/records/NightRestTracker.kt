package com.example.itv.domain.records

/** A brief unlocked-use episode is included in rest, not subtracted from it. */
internal data class RestInterval(val start: Long, val end: Long?, val briefWakeCount: Int)

internal class NightRestTracker {
    private var restStart: Long? = null
    private var useStart: Long? = null
    private var briefCount = 0
    private val completed = mutableListOf<RestInterval>()

    fun advance(at: Long) {
        val use = useStart ?: return
        if (at - use >= 60_000L) {
            restStart?.let { completed.add(RestInterval(it, use, briefCount)) }
            restStart = null
            useStart = null
            briefCount = 0
        }
    }

    fun screenOff(at: Long) {
        advance(at)
        if (restStart != null && useStart != null) briefCount++
        useStart = null
        if (restStart == null) { restStart = at; briefCount = 0 }
    }

    fun confirmedUse(at: Long) {
        advance(at)
        if (restStart != null && useStart == null) useStart = at
    }

    fun deviceBoundary(at: Long) {
        advance(at)
        restStart = null; useStart = null; briefCount = 0
    }

    fun snapshot(now: Long): List<RestInterval> {
        advance(now)
        return completed.toList() + listOfNotNull(restStart?.let { RestInterval(it, null, briefCount) })
    }
}
