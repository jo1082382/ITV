package com.example.itv.domain.jev

/** Test policy, independently versioned from the existing 30-second rest mission. */
data class JevSettings(
    val enabled: Boolean = false,
    val collectionConsent: Boolean = false,
    val actualIntervention: Boolean = false,
    val testerId: String = "T01",
    val instagram: Boolean = true,
    val youtube: Boolean = true,
    val continuousEnabled: Boolean = true,
    val continuousSeconds: Int = 900,
    val returnsEnabled: Boolean = true,
    val returnsThreshold: Int = 3,
    val bedtimeEnabled: Boolean = true,
    val bedtimeDelayMinutes: Int = 0,
    val bedtimeWindowMinutes: Int = 480,
    val probabilityThreshold: Double = 0.8,
    val reevaluateSeconds: Int = 300,
    val additionalViewingSeconds: Int = 300,
    val resultTtlSeconds: Int = 30,
    val laterSeconds: Int = 300,
    val dailyLimit: Int = 20,
    val personalizedEnabled: Boolean = true,
    val revision: Long = 1
) {
    fun validated(): JevSettings = apply {
        require(Regex("T0[1-7]").matches(testerId)) { "참가자는 T01~T07입니다." }
        require(continuousSeconds in 1..86400) { "시청 기준은 1~86400초입니다." }
        require(returnsThreshold in 1..1440) { "재진입 기준은 1~1440회입니다." }
        require(bedtimeWindowMinutes in 1..1440 && bedtimeDelayMinutes in 0 until bedtimeWindowMinutes) { "취침 지연은 적용 시간보다 작아야 합니다." }
        require(probabilityThreshold.isFinite() && probabilityThreshold in 0.0..1.0) { "확률은 0~100%입니다." }
        require(reevaluateSeconds in 1..86400 && additionalViewingSeconds in 1..86400) { "재평가 간격과 추가 시청은 1~86400초입니다." }
        require(resultTtlSeconds in 1..300 && laterSeconds in 1..86400) { "응답 유효시간은 1~300초, 보류는 1~86400초입니다." }
        require(dailyLimit in 1..40) { "기기 일일 한도는 1~40회입니다. 서버 전체 한도는 별도로 적용됩니다." }
    }
    fun targets(pkg: String) = (instagram && pkg == INSTAGRAM) || (youtube && pkg == YOUTUBE)
    companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val YOUTUBE = "com.google.android.youtube"
    }
}

data class JevContext(
    val sessionId: String,
    val packageName: String,
    val sessionMs: Long,
    val continuousMs: Long,
    val returns10m: Int,
    val returnsObservedSeconds: Int,
    val bedtimePassedMinutes: Int?,
    val observedAt: Long,
    val quality: String = "VALID",
    val sessionVsAvgRatio: Double? = null,
    val todayVs7dRatio: Double? = null,
    val effectiveSpm1m: Double? = null,
    val swipeAccel1m: Double? = null,
    val fastSwipeStreak: Int = 0
)

object JevCandidatePolicy {
    fun isSwipeOverheated(c: JevContext): Boolean {
        val spm = c.effectiveSpm1m ?: 0.0
        val accel = c.swipeAccel1m ?: 0.0
        return (c.fastSwipeStreak >= 3 && spm >= 10.0) || (spm >= 12.0 && accel >= 3.0)
    }

    fun reasons(c: JevContext, s: JevSettings): List<String> = buildList {
        if (!s.targets(c.packageName) || c.quality != "VALID") return@buildList
        if (s.continuousEnabled && c.continuousMs >= s.continuousSeconds * 1000L) add("CONTINUOUS")
        if (s.returnsEnabled && c.returns10m >= s.returnsThreshold) add("REPEATED_RETURNS")
        if (s.bedtimeEnabled && c.bedtimePassedMinutes != null &&
            c.bedtimePassedMinutes >= s.bedtimeDelayMinutes && c.continuousMs > 0) add("BEDTIME")
        if (s.personalizedEnabled && s.continuousEnabled) {
            val minPersonalMs = minOf(180_000L, s.continuousSeconds * 1000L)
            if (c.continuousMs >= minPersonalMs && (c.sessionVsAvgRatio ?: 0.0) >= 1.25 && "CONTINUOUS" !in this) {
                add("PERSONAL_SESSION_EXCEEDED")
            }
            val minSwipeMs = minOf(120_000L, s.continuousSeconds * 1000L)
            if (c.continuousMs >= minSwipeMs && isSwipeOverheated(c)) {
                add("RAPID_SWIPE_OVERHEAT")
            }
        }
    }

    /**
     * Computes the effective intervention probability threshold by applying bounded
     * personalization and swipe-dynamics adjustments when personalizedEnabled is true.
     */
    fun effectiveThreshold(s: JevSettings, c: JevContext? = null): Double {
        if (c == null || !s.personalizedEnabled) return s.probabilityThreshold
        val hasAnyPersonalSignal = c.sessionVsAvgRatio != null || c.todayVs7dRatio != null ||
            c.effectiveSpm1m != null || c.swipeAccel1m != null || c.fastSwipeStreak > 0
        if (!hasAnyPersonalSignal) return s.probabilityThreshold

        var delta = 0.0
        if ((c.todayVs7dRatio ?: 0.0) >= 1.30) delta -= 0.05
        if ((c.sessionVsAvgRatio ?: 0.0) >= 1.25) delta -= 0.05
        if (isSwipeOverheated(c)) delta -= 0.05

        if (delta == 0.0 &&
            c.sessionVsAvgRatio != null && c.sessionVsAvgRatio < 0.70 &&
            (c.todayVs7dRatio == null || c.todayVs7dRatio < 0.85) &&
            (c.effectiveSpm1m == null || c.effectiveSpm1m < 6.0) &&
            c.bedtimePassedMinutes == null
        ) {
            delta += 0.05
        }
        val clampedDelta = delta.coerceIn(-0.15, 0.10)
        val minBound = minOf(s.probabilityThreshold, 0.30)
        val maxBound = maxOf(s.probabilityThreshold, 0.95)
        return (kotlin.math.round((s.probabilityThreshold + clampedDelta).coerceIn(minBound, maxBound) * 1000.0) / 1000.0)
    }

    fun passes(probability: Double, s: JevSettings, c: JevContext? = null): Boolean {
        val threshold = effectiveThreshold(s, c)
        return probability.isFinite() && probability in 0.0..1.0 && probability >= threshold
    }
    /** A displayed offer/reaction is no longer limited by the pre-display response TTL. */
    fun canContinueDisplayed(requestRevision: Long, settings: JevSettings, requested: JevContext,
                             current: JevContext?) =
        settings.enabled && settings.collectionConsent && settings.actualIntervention &&
        requestRevision == settings.revision && current?.quality == "VALID" &&
        current.sessionId == requested.sessionId && current.packageName == requested.packageName &&
        settings.targets(current.packageName)
    fun resultIsCurrent(requestRevision: Long, settings: JevSettings, requested: JevContext,
                        current: JevContext?, responseAgeMs: Long) =
        requestRevision == settings.revision && current?.quality == "VALID" &&
        current.sessionId == requested.sessionId && current.packageName == requested.packageName &&
        responseAgeMs in 0..settings.resultTtlSeconds * 1000L
}

/** No interpolation across unknown observations. A new session requires 600s verified continuous app absence. */
class JevSessionTracker(private val newId: () -> String) {
    data class AppState(
        var id: String, var totalMs: Long = 0, var continuousMs: Long = 0,
        var awayMs: Long = 0, var entered: Boolean = false,
        var nonConfirmedMs: Long = 0,
        /** Continuous viewing of the most recent run; kept after reset for test inspection only. */
        var lastRunContinuousMs: Long = 0,
        val returns: MutableList<Long> = mutableListOf()
    )
    val states = linkedMapOf<String, AppState>()
    private var previousAt: Long? = null
    private var previousPkg: String? = null
    private var previousConfirmed: String? = null
    private var coverageStart: Long? = null

    fun observe(now: Long, pkg: String?, confirmedPkg: String?) {
        val before = previousAt
        val delta = if (before == null) 0 else now - before
        val contiguous = before != null && delta in 1..1500
        if (coverageStart == null || !contiguous || pkg == null) coverageStart = now
        val inspectingItv = pkg == "com.example.itv"
        for (app in listOf(JevSettings.INSTAGRAM, JevSettings.YOUTUBE)) {
            val state = states.getOrPut(app) { AppState(newId()) }
            if (pkg == app && previousPkg != app) {
                val verifiedAway = state.awayMs + if (contiguous && previousPkg != null && previousPkg != app) delta else 0L
                if (verifiedAway >= 600_000L) {
                    state.id = newId(); state.totalMs = 0
                }
                if (state.entered && previousPkg != null) state.returns.add(now)
                state.entered = true
            }
            if (pkg != null && pkg != app && previousPkg != null && previousPkg != app && contiguous) state.awayMs += delta
            else if (pkg == app || pkg == null || !contiguous) state.awayMs = 0
            if (confirmedPkg == app && contiguous) {
                if (previousConfirmed == app || state.continuousMs > 0L) {
                    state.continuousMs += delta; state.totalMs += delta
                }
                state.nonConfirmedMs = 0L
            } else if (confirmedPkg == app) {
                state.nonConfirmedMs = 0L
            } else if (inspectingItv) {
                // Preserve continuousMs while checking ITV app
            } else if (pkg == app && contiguous) {
                // Continuous usage within the same target app (e.g. YouTube Home or Instagram Feed/Stories):
                // do not reset continuousMs or advance nonConfirmedMs.
                state.nonConfirmedMs = 0L
            } else if (pkg == "LOCKED" || !contiguous) {
                // Screen turned off/locked or non-contiguous gap (> 1.5s): reset continuous viewing
                state.continuousMs = 0L; state.nonConfirmedMs = 0L
            } else {
                // In another app or after leaving the target app: only reset after 15s of rest/non-viewing
                state.nonConfirmedMs += delta
                if (state.nonConfirmedMs >= 15_000L) {
                    state.continuousMs = 0L
                }
            }
            if (state.continuousMs > 0L) state.lastRunContinuousMs = state.continuousMs
            state.returns.removeAll { now - it >= 600_000L || it > now }
        }
        previousAt = now; previousPkg = pkg
        if (!inspectingItv) previousConfirmed = confirmedPkg
    }
    fun context(pkg: String, now: Long, wall: Long, bedtime: Int?): JevContext? {
        val state = states[pkg] ?: return null
        return JevContext(state.id,pkg,state.totalMs,state.continuousMs,state.returns.size,
            ((now - (coverageStart ?: now)) / 1000).coerceIn(0,600).toInt(),bedtime,wall)
    }
    /** Restore totals only. Never restore continuity or absence from a wall-clock difference. */
    fun restore(pkg: String, id: String, totalMs: Long, entered: Boolean) {
        states[pkg] = AppState(id,totalMs.coerceAtLeast(0),entered=entered)
    }
}
