package com.example.itv.infrastructure.observation

import android.os.SystemClock
import com.example.itv.domain.records.ShortformApp
import kotlin.math.roundToInt

/**
 * Records shortform swipe (transition) timestamps per app and computes
 * swipes-per-minute (SPM) and swipe acceleration with isolated ad-skip filtering.
 */
internal object SwipeDynamicsTracker {
    /** Swipes occurring within 2.5s of the previous transition are candidates for instant ad/uninterested skip. */
    private const val AD_SKIP_MAX_MS = 2_500L
    /** Consecutive swipes within 4.5s form a fast-swipe streak. A streak >= 3 confirms rapid browsing rather than an isolated ad skip. */
    private const val FAST_STREAK_MAX_MS = 4_500L
    private const val MIN_STREAK_FOR_RAPID_BROWSING = 3

    data class SwipeEvent(
        val index: Int,
        val elapsedMs: Long,
        val wallMs: Long,
        val intervalSeconds: Double?,
        val filteredAsAdSkip: Boolean,
        val fastStreakAtSwipe: Int,
        val effectiveSpm1m: Double,
        val rawSpm1m: Double,
        val effectiveSpmPrev1m: Double,
        val effectiveAccel1m: Double,
        val effectiveAccelRatio1m: Double,
        val effectiveSpm30s: Double,
        val rawSpm30s: Double,
        val effectiveSpmPrev30s: Double,
        val effectiveAccel30s: Double,
        val effectiveSessionSpm: Double,
        val patternLabel: String
    )

    data class MetricsSnapshot(
        val app: ShortformApp,
        val active: Boolean,
        val sessionDurationSeconds: Double,
        val totalSwipesInRun: Int,
        val effectiveSwipesInRun: Int,
        val filteredAdSkipsInRun: Int,
        val currentFastStreak: Int,
        val effectiveSpm1m: Double,
        val rawSpm1m: Double,
        val effectiveSpmPrev1m: Double,
        val effectiveAccel1m: Double,
        val rawAccel1m: Double,
        val effectiveAccelRatio1m: Double,
        val effectiveSpm30s: Double,
        val rawSpm30s: Double,
        val effectiveSpmPrev30s: Double,
        val effectiveAccel30s: Double,
        val rawAccel30s: Double,
        val effectiveSessionSpm: Double,
        val rawSessionSpm: Double,
        val patternLabel: String,
        val updatedWallMs: Long,
        val events: List<SwipeEvent>
    )

    private data class RawSwipe(
        val index: Int,
        val elapsedMs: Long,
        val wallMs: Long,
        val intervalMs: Long?
    )

    private class RunState {
        var runStartElapsed = 0L
        var lastConfirmedElapsed = 0L
        var accumulatedActiveMs = 0L
        var nonConfirmedSince = 0L
        val rawSwipes = mutableListOf<RawSwipe>()
        var lastSnapshot: MetricsSnapshot? = null
    }

    private val states = mapOf(
        ShortformApp.YOUTUBE to RunState(),
        ShortformApp.INSTAGRAM to RunState()
    )

    @Synchronized
    fun onPresence(
        app: ShortformApp,
        confirmed: Boolean,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis(),
        sameAppSafe: Boolean = confirmed
    ) {
        val s = states.getValue(app)
        if (confirmed) {
            if (s.runStartElapsed == 0L) {
                s.runStartElapsed = nowElapsed
                s.accumulatedActiveMs = 0L
                s.rawSwipes.clear()
            } else if (s.lastConfirmedElapsed > 0L) {
                val dt = nowElapsed - s.lastConfirmedElapsed
                if (dt in 1L..2000L) {
                    s.accumulatedActiveMs += dt
                }
            }
            s.lastConfirmedElapsed = nowElapsed
            s.nonConfirmedSince = 0L
            s.lastSnapshot = buildSnapshot(app, s, nowElapsed, nowWall, active = true)
        } else if (sameAppSafe && s.runStartElapsed > 0L) {
            // Navigating within the same app (e.g., YouTube Home or Instagram Feed/Stories):
            // preserve the current run without advancing the 15s reset timer.
            s.nonConfirmedSince = 0L
        } else {
            if (s.runStartElapsed > 0L) {
                if (s.nonConfirmedSince == 0L) s.nonConfirmedSince = nowElapsed
                if (nowElapsed - s.nonConfirmedSince >= 15_000L) {
                    val frozenAt = s.lastConfirmedElapsed.takeIf { it > 0L } ?: nowElapsed
                    s.lastSnapshot = buildSnapshot(app, s, frozenAt, nowWall, active = false)
                    s.runStartElapsed = 0L
                    s.lastConfirmedElapsed = 0L
                    s.accumulatedActiveMs = 0L
                    s.nonConfirmedSince = 0L
                    s.rawSwipes.clear()
                }
            }
        }
    }

    @Synchronized
    fun onTransition(
        app: ShortformApp,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis()
    ) {
        val s = states.getValue(app)
        if (s.runStartElapsed == 0L) {
            s.runStartElapsed = nowElapsed
            s.lastConfirmedElapsed = nowElapsed
            s.accumulatedActiveMs = 0L
            s.rawSwipes.clear()
        } else {
            if (s.lastConfirmedElapsed > 0L) {
                val dt = nowElapsed - s.lastConfirmedElapsed
                if (dt in 1L..2000L) {
                    s.accumulatedActiveMs += dt
                }
            }
            s.lastConfirmedElapsed = nowElapsed
        }
        s.nonConfirmedSince = 0L

        val prevElapsed = s.rawSwipes.lastOrNull()?.elapsedMs ?: s.runStartElapsed
        val intervalMs = if (s.rawSwipes.isEmpty() && nowElapsed == s.runStartElapsed) null
        else (nowElapsed - prevElapsed).coerceAtLeast(0L)

        val nextIndex = (s.rawSwipes.lastOrNull()?.index ?: 0) + 1
        s.rawSwipes.add(
            RawSwipe(
                index = nextIndex,
                elapsedMs = nowElapsed,
                wallMs = nowWall,
                intervalMs = intervalMs
            )
        )
        // Keep last 10 minutes of swipes
        s.rawSwipes.removeAll { nowElapsed - it.elapsedMs > 600_000L }

        s.lastSnapshot = buildSnapshot(app, s, nowElapsed, nowWall, active = true)
    }

    @Synchronized
    fun snapshot(
        app: ShortformApp,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis()
    ): MetricsSnapshot {
        val s = states.getValue(app)
        return if (s.runStartElapsed > 0L && (s.lastConfirmedElapsed > 0L && nowElapsed - s.lastConfirmedElapsed < 15_000L)) {
            val refElapsed = s.lastConfirmedElapsed.coerceAtLeast(s.rawSwipes.lastOrNull()?.elapsedMs ?: nowElapsed)
            buildSnapshot(app, s, refElapsed, nowWall, active = nowElapsed - s.lastConfirmedElapsed <= 2000L)
        } else {
            s.lastSnapshot ?: buildSnapshot(app, s, nowElapsed, nowWall, active = false)
        }
    }

    @Synchronized
    fun reset(app: ShortformApp? = null) {
        val targets = app?.let { listOf(it) } ?: listOf(ShortformApp.YOUTUBE, ShortformApp.INSTAGRAM)
        for (t in targets) {
            val s = states.getValue(t)
            s.runStartElapsed = 0L
            s.lastConfirmedElapsed = 0L
            s.accumulatedActiveMs = 0L
            s.nonConfirmedSince = 0L
            s.rawSwipes.clear()
            s.lastSnapshot = null
        }
    }

    /**
     * Marks each swipe in [swipes] as filtered (isolated ad/instant skip) or effective,
     * and computes the fast-swipe streak at each swipe.
     *
     * Rule:
     * - A swipe with intervalMs <= AD_SKIP_MAX_MS (2.5s) is a candidate for ad/instant skip.
     * - Consecutive swipes with intervalMs <= FAST_STREAK_MAX_MS (4.5s) form a fast-swipe cluster.
     * - If the fast-swipe cluster length is < 3 (i.e. 1 or 2 quick skips surrounded by normal viewing),
     *   any swipe in that cluster with intervalMs <= AD_SKIP_MAX_MS is treated as an isolated ad skip (filtered = true).
     * - Once the fast-swipe cluster reaches >= 3 consecutive fast swipes, all swipes in that cluster are treated
     *   as genuine rapid browsing (filtered = false).
     */
    private fun analyzeSwipes(swipes: List<RawSwipe>): Pair<BooleanArray, IntArray> {
        val n = swipes.size
        val filtered = BooleanArray(n)
        val streaks = IntArray(n)
        var i = 0
        while (i < n) {
            val iv = swipes[i].intervalMs
            val isFast = iv != null && iv <= FAST_STREAK_MAX_MS
            if (!isFast) {
                streaks[i] = 0
                filtered[i] = false
                i++
            } else {
                var j = i
                var streak = 0
                while (j < n) {
                    val jIv = swipes[j].intervalMs
                    if (jIv != null && jIv <= FAST_STREAK_MAX_MS) {
                        streak++
                        streaks[j] = streak
                        j++
                    } else {
                        break
                    }
                }
                val clusterSize = j - i
                if (clusterSize < MIN_STREAK_FOR_RAPID_BROWSING) {
                    for (k in i until j) {
                        val kIv = swipes[k].intervalMs
                        filtered[k] = kIv != null && kIv <= AD_SKIP_MAX_MS
                    }
                } else {
                    for (k in i until j) {
                        filtered[k] = false
                    }
                }
                i = j
            }
        }
        return filtered to streaks
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0
    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0

    private fun buildSnapshot(
        app: ShortformApp,
        s: RunState,
        refElapsed: Long,
        nowWall: Long,
        active: Boolean
    ): MetricsSnapshot {
        val swipes = s.rawSwipes
        val (filteredArr, streakArr) = analyzeSwipes(swipes)

        fun computeAt(atIndexInclusive: Int, atElapsed: Long, runStart: Long, accActiveMs: Long): MetricsCalc {
            var raw1m = 0
            var rawPrev1m = 0
            var raw30s = 0
            var rawPrev30s = 0
            var eff1m = 0
            var effPrev1m = 0
            var eff30s = 0
            var effPrev30s = 0
            var totalRaw = 0
            var totalEff = 0

            for (idx in 0..atIndexInclusive) {
                val item = swipes[idx]
                val age = atElapsed - item.elapsedMs
                if (age < 0L) continue
                totalRaw++
                val isEff = !filteredArr[idx]
                if (isEff) totalEff++

                if (age in 0L..60_000L) {
                    raw1m++
                    if (isEff) eff1m++
                } else if (age in 60_001L..120_000L) {
                    rawPrev1m++
                    if (isEff) effPrev1m++
                }

                if (age in 0L..30_000L) {
                    raw30s++
                    if (isEff) eff30s++
                } else if (age in 30_001L..60_000L) {
                    rawPrev30s++
                    if (isEff) effPrev30s++
                }
            }

            val rawSpm1m = raw1m.toDouble()
            val rawSpmPrev1m = rawPrev1m.toDouble()
            val rawAccel1m = rawSpm1m - rawSpmPrev1m
            val rawSpm30s = raw30s * 2.0
            val rawSpmPrev30s = rawPrev30s * 2.0
            val rawAccel30s = rawSpm30s - rawSpmPrev30s

            val effSpm1m = eff1m.toDouble()
            val effSpmPrev1m = effPrev1m.toDouble()
            val effAccel1m = effSpm1m - effSpmPrev1m
            val effSpm30s = eff30s * 2.0
            val effSpmPrev30s = effPrev30s * 2.0
            val effAccel30s = effSpm30s - effSpmPrev30s

            val elapsedSinceStartSec = if (runStart > 0L) ((atElapsed - runStart).coerceAtLeast(0L) / 1000.0) else 0.0
            val durationSec = if (atIndexInclusive == swipes.lastIndex) {
                (accActiveMs / 1000.0).coerceAtLeast(elapsedSinceStartSec)
            } else {
                elapsedSinceStartSec
            }

            val rawSessionSpm = if (durationSec >= 5.0) (totalRaw / (durationSec / 60.0)) else rawSpm1m
            val effSessionSpm = if (durationSec >= 5.0) (totalEff / (durationSec / 60.0)) else effSpm1m
            val effAccelRatio1m = when {
                effSpmPrev1m > 0.0 -> effSpm1m / effSpmPrev1m
                effSessionSpm > 0.0 -> effSpm1m / effSessionSpm
                else -> 1.0
            }
            val currentStreak = if (atIndexInclusive >= 0) streakArr[atIndexInclusive] else 0
            val wasJustFiltered = atIndexInclusive >= 0 && filteredArr[atIndexInclusive]

            val pattern = classifyPattern(
                totalRaw = totalRaw,
                totalEff = totalEff,
                durationSec = durationSec,
                effSpm1m = effSpm1m,
                effSpm30s = effSpm30s,
                effAccel1m = effAccel1m,
                effAccel30s = effAccel30s,
                fastStreak = currentStreak,
                wasJustFiltered = wasJustFiltered
            )

            return MetricsCalc(
                durationSec = durationSec,
                totalRaw = totalRaw,
                totalEff = totalEff,
                currentStreak = currentStreak,
                rawSpm1m = rawSpm1m,
                 effSpm1m = effSpm1m,
                effSpmPrev1m = effSpmPrev1m,
                rawAccel1m = rawAccel1m,
                effAccel1m = effAccel1m,
                effAccelRatio1m = effAccelRatio1m,
                rawSpm30s = rawSpm30s,
                effSpm30s = effSpm30s,
                effSpmPrev30s = effSpmPrev30s,
                rawAccel30s = rawAccel30s,
                effAccel30s = effAccel30s,
                rawSessionSpm = rawSessionSpm,
                effSessionSpm = effSessionSpm,
                pattern = pattern
            )
        }

        val currentCalc = computeAt(swipes.lastIndex, refElapsed, s.runStartElapsed, s.accumulatedActiveMs)

        // Build newest-first event list (up to last 60 swipes)
        val events = ArrayList<SwipeEvent>(swipes.size.coerceAtMost(60))
        val startIdx = (swipes.size - 60).coerceAtLeast(0)
        for (idx in swipes.lastIndex downTo startIdx) {
            val raw = swipes[idx]
            val c = computeAt(idx, raw.elapsedMs, s.runStartElapsed, s.accumulatedActiveMs)
            events.add(
                SwipeEvent(
                    index = raw.index,
                    elapsedMs = raw.elapsedMs,
                    wallMs = raw.wallMs,
                    intervalSeconds = raw.intervalMs?.let { round1(it / 1000.0) },
                    filteredAsAdSkip = filteredArr[idx],
                    fastStreakAtSwipe = streakArr[idx],
                    effectiveSpm1m = round1(c.effSpm1m),
                    rawSpm1m = round1(c.rawSpm1m),
                    effectiveSpmPrev1m = round1(c.effSpmPrev1m),
                    effectiveAccel1m = round1(c.effAccel1m),
                    effectiveAccelRatio1m = round2(c.effAccelRatio1m),
                    effectiveSpm30s = round1(c.effSpm30s),
                    rawSpm30s = round1(c.rawSpm30s),
                    effectiveSpmPrev30s = round1(c.effSpmPrev30s),
                    effectiveAccel30s = round1(c.effAccel30s),
                    effectiveSessionSpm = round1(c.effSessionSpm),
                    patternLabel = c.pattern
                )
            )
        }

        return MetricsSnapshot(
            app = app,
            active = active,
            sessionDurationSeconds = round1(currentCalc.durationSec),
            totalSwipesInRun = currentCalc.totalRaw,
            effectiveSwipesInRun = currentCalc.totalEff,
            filteredAdSkipsInRun = (currentCalc.totalRaw - currentCalc.totalEff).coerceAtLeast(0),
            currentFastStreak = currentCalc.currentStreak,
            effectiveSpm1m = round1(currentCalc.effSpm1m),
            rawSpm1m = round1(currentCalc.rawSpm1m),
            effectiveSpmPrev1m = round1(currentCalc.effSpmPrev1m),
            effectiveAccel1m = round1(currentCalc.effAccel1m),
            rawAccel1m = round1(currentCalc.rawAccel1m),
            effectiveAccelRatio1m = round2(currentCalc.effAccelRatio1m),
            effectiveSpm30s = round1(currentCalc.effSpm30s),
            rawSpm30s = round1(currentCalc.rawSpm30s),
            effectiveSpmPrev30s = round1(currentCalc.effSpmPrev30s),
            effectiveAccel30s = round1(currentCalc.effAccel30s),
            rawAccel30s = round1(currentCalc.rawAccel30s),
            effectiveSessionSpm = round1(currentCalc.effSessionSpm),
            rawSessionSpm = round1(currentCalc.rawSessionSpm),
            patternLabel = currentCalc.pattern,
            updatedWallMs = nowWall,
            events = events
        )
    }

    private data class MetricsCalc(
        val durationSec: Double,
        val totalRaw: Int,
        val totalEff: Int,
        val currentStreak: Int,
        val rawSpm1m: Double,
        val effSpm1m: Double,
        val effSpmPrev1m: Double,
        val rawAccel1m: Double,
        val effAccel1m: Double,
        val effAccelRatio1m: Double,
        val rawSpm30s: Double,
        val effSpm30s: Double,
        val effSpmPrev30s: Double,
        val rawAccel30s: Double,
        val effAccel30s: Double,
        val rawSessionSpm: Double,
        val effSessionSpm: Double,
        val pattern: String
    )

    private fun classifyPattern(
        totalRaw: Int,
        totalEff: Int,
        durationSec: Double,
        effSpm1m: Double,
        effSpm30s: Double,
        effAccel1m: Double,
        effAccel30s: Double,
        fastStreak: Int,
        wasJustFiltered: Boolean
    ): String = when {
        totalRaw == 0 && durationSec < 1.0 -> "관측 대기"
        fastStreak >= MIN_STREAK_FOR_RAPID_BROWSING && effSpm30s >= 10.0 && (effAccel30s >= 4.0 || effAccel1m >= 3.0) ->
            "급가속 탐색 (연속 고속 스킵 ${fastStreak}회)"
        fastStreak >= MIN_STREAK_FOR_RAPID_BROWSING || effSpm1m >= 10.0 || effSpm30s >= 12.0 ->
            "고속 연속 스와이프 (빠른 탐색)"
        wasJustFiltered && totalEff < totalRaw ->
            "광고/단발 즉시 스킵 보정됨 (정상 시청 유지)"
        effAccel30s >= 4.0 || effAccel1m >= 3.0 ->
            "스와이프 가속 중 (속도 상승)"
        effAccel30s <= -4.0 || effAccel1m <= -3.0 ->
            "스와이프 감속 (영상 집중 시청)"
        effSpm1m >= 4.0 ->
            "일반 탐색 시청"
        else ->
            "저속/집중 시청"
    }
}
