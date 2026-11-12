package com.example.itv.infrastructure.storage.shortform

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.example.itv.data.local.ItvDatabase
import com.example.itv.domain.records.Report
import com.example.itv.domain.records.ShortformApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Tracks per-app shortform session history on-device and computes personalized
 * baselines, averages, and ratios (Instagram Reels, YouTube Shorts, and Combined).
 */
internal object ShortformPersonalStatsStore {
    const val PKG_INSTAGRAM = "com.instagram.android"
    const val PKG_YOUTUBE = "com.google.android.youtube"
    private const val MIN_VALID_SESSION_MS = 5_000L
    private const val MIN_REPRESENTATIVE_SESSION_MS = 60_000L
    private const val SESSION_GRACE_MS = 15_000L
    private const val MAX_STORED_SESSIONS = 300

    enum class TargetFilter(val label: String) {
        COMBINED("통합"),
        INSTAGRAM("인스타 릴스"),
        YOUTUBE("유튜브 쇼츠")
    }

    data class SessionRecord(
        val id: String,
        val packageName: String,
        val date: String,
        val startWallMs: Long,
        val updatedWallMs: Long,
        val durationMs: Long,
        val swipes: Int
    )

    data class PersonalizedTargetMetrics(
        val filter: TargetFilter,
        // 1. Today vs 7-day & 30-day daily averages (excluding 0-usage days)
        val todayDurationMs: Long,
        val avg7dDailyMs: Long,
        val observedDays7d: Int,
        val total7dDurationMs: Long,
        /** Today's duration divided by 7-day daily average (1.0 = 100%). Null if no 7d baseline or 0 today. */
        val todayVs7dRatio: Double?,
        val avg30dDailyMs: Long,
        val observedDays30d: Int,
        val total30dDurationMs: Long,
        val todayVs30dRatio: Double?,
        /** 7-day daily average divided by 30-day daily average. */
        val avg7dVs30dRatio: Double?,

        // 2. Session length personalization (Today, 7-day, 30-day, and overall)
        val currentSessionMs: Long,
        val currentSessionLive: Boolean,
        val currentSessionSwipes: Int,
        val avgSessionMs: Long,
        val maxSessionMs: Long,
        val totalRecordedSessions: Int,
        val todaySessionCount: Int,
        val avg7dSessionMs: Long,
        val max7dSessionMs: Long,
        val sessionCount7d: Int,
        val avg7dSessionCount: Double,
        val avg30dSessionMs: Long,
        val max30dSessionMs: Long,
        val sessionCount30d: Int,
        val avg30dSessionCount: Double,
        /** Current (or most recent) session duration divided by personal average session duration. */
        val currentVsAvgSessionRatio: Double?,
        /** 7-day average session duration divided by 30-day (personal) average session duration. */
        val session7dVs30dRatio: Double?,

        // 3. Transitions (Swipes) & SPM personalization
        val todaySwipes: Long,
        val total7dSwipes: Long,
        val avg7dDailySwipes: Double,
        val todayVs7dSwipesRatio: Double?,
        val avg7dSwipesPerSession: Double,
        val avg7dSpm: Double,
        val total30dSwipes: Long,
        val avg30dDailySwipes: Double,
        val avg30dSwipesPerSession: Double,
        val avgSwipesPerSession: Double,
        val todayAvgSpm: Double,
        val personalAvgSpm: Double,

        // 4. Share of total app/phone usage (active days/hours only)
        val parentAppTodayMs: Long,
        val shortformShareOfAppRatio: Double?,
        val totalPhoneTodayMs: Long,
        val shortformShareOfPhoneRatio: Double?,
        val periodShareOfAppRatio: Double?,
        val periodShareOfPhoneRatio: Double?
    )

    data class PersonalizedSummary(
        val combined: PersonalizedTargetMetrics,
        val instagram: PersonalizedTargetMetrics,
        val youtube: PersonalizedTargetMetrics
    ) {
        fun forFilter(filter: TargetFilter): PersonalizedTargetMetrics = when (filter) {
            TargetFilter.COMBINED -> combined
            TargetFilter.INSTAGRAM -> instagram
            TargetFilter.YOUTUBE -> youtube
        }
    }

    private class ActiveRun(
        var id: String = "",
        var startWallMs: Long = 0L,
        var lastConfirmedElapsed: Long = 0L,
        var nonConfirmedSince: Long = 0L,
        var durationMs: Long = 0L,
        var swipes: Int = 0,
        var lastPersistElapsed: Long = 0L
    ) {
        fun reset() {
            id = ""
            startWallMs = 0L
            lastConfirmedElapsed = 0L
            nonConfirmedSince = 0L
            durationMs = 0L
            swipes = 0
            lastPersistElapsed = 0L
        }
    }

    private var file: AtomicFile? = null
    private var initialized = false
    private val sessions = mutableListOf<SessionRecord>()
    private val runs = mapOf(
        PKG_INSTAGRAM to ActiveRun(),
        PKG_YOUTUBE to ActiveRun()
    )
    @Volatile
    private var cached7dAvgByPkg: Map<String, Long> = emptyMap()
    @Volatile
    private var cachedPersonalAvgSpmByPkg: Map<String, Double> = emptyMap()
    @Volatile
    private var cachedTotalPhoneTodayMs: Long = 0L

    data class QuickPersonalMetrics(
        val avgSessionMs: Long,
        val avgSessionMinutes: Double?,
        val sessionVsAvgRatio: Double?,
        val todayDurationMs: Long,
        val todayShortformMinutes: Double?,
        val avg7dDailyMs: Long,
        val avg7dDailyMinutes: Double?,
        val todayVs7dRatio: Double?,
        val personalAvgSpm: Double?,
        val shortformShareOfPhoneRatio: Double? = null
    )

    private fun pkgOf(app: ShortformApp): String = when (app) {
        ShortformApp.INSTAGRAM -> PKG_INSTAGRAM
        ShortformApp.YOUTUBE -> PKG_YOUTUBE
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    /** Refreshes the 7-day daily average and 30-day personal SPM caches from Room on a background thread. */
    suspend fun refreshBaselineCache(context: Context) {
        val appCtx = context.applicationContext
        val allDbRows = runCatching { ItvDatabase.get(appCtx).shortformDao().all("demo-guest") }.getOrDefault(emptyList())
        val todayDate = LocalDate.now()
        val start7d = todayDate.minusDays(6)
        val start30d = todayDate.minusDays(29)
        val result7d = mutableMapOf<String, Long>()
        val resultSpm = mutableMapOf<String, Double>()
        for (pkg in listOf(PKG_INSTAGRAM, PKG_YOUTUBE)) {
            val byDate7d = linkedMapOf<LocalDate, Long>()
            val byDate30dMs = linkedMapOf<LocalDate, Long>()
            val byDate30dSwipes = linkedMapOf<LocalDate, Long>()
            for (row in allDbRows) {
                if (row.packageName != pkg) continue
                val d = runCatching { LocalDate.parse(row.localDate) }.getOrNull() ?: continue
                if (!d.isBefore(start30d) && !d.isAfter(todayDate) && (row.observed || row.durationMs > 0L)) {
                    byDate30dMs[d] = (byDate30dMs[d] ?: 0L) + row.durationMs
                    byDate30dSwipes[d] = (byDate30dSwipes[d] ?: 0L) + row.transitionCount
                }
                if (!d.isBefore(start7d) && !d.isAfter(todayDate) && (row.observed || row.durationMs > 0L)) {
                    byDate7d[d] = (byDate7d[d] ?: 0L) + row.durationMs
                }
            }
            val liveToday = ShortformDailyStore.today(appCtx, if (pkg == PKG_INSTAGRAM) "instagram" else "youtube")
            if (liveToday.ms > 0L) {
                byDate7d[todayDate] = maxOf(byDate7d[todayDate] ?: 0L, liveToday.ms)
                byDate30dMs[todayDate] = maxOf(byDate30dMs[todayDate] ?: 0L, liveToday.ms)
                byDate30dSwipes[todayDate] = maxOf(byDate30dSwipes[todayDate] ?: 0L, liveToday.transitions.toLong())
            }
            val active7dValues = byDate7d.values.filter { it >= MIN_VALID_SESSION_MS }
                .ifEmpty { byDate7d.values.filter { it > 0L } }
            if (active7dValues.isNotEmpty()) {
                result7d[pkg] = active7dValues.sum() / active7dValues.size
            }
            val active30dDates = byDate30dMs.filterValues { it >= MIN_VALID_SESSION_MS }.keys
                .ifEmpty { byDate30dMs.filterValues { it > 0L }.keys }
            val total30dMs = active30dDates.sumOf { byDate30dMs[it] ?: 0L }
            val total30dSwipes = active30dDates.sumOf { byDate30dSwipes[it] ?: 0L }
            if (total30dMs >= 10_000L && total30dSwipes > 0L) {
                resultSpm[pkg] = round2(total30dSwipes.toDouble() / (total30dMs / 60_000.0))
            }
        }
        cached7dAvgByPkg = result7d
        cachedPersonalAvgSpmByPkg = resultSpm
        val usm = appCtx.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
        val todayStartWall = todayDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = runCatching {
            usm?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, todayStartWall, System.currentTimeMillis())
        }.getOrNull()
        val phoneMs = stats?.filter { it.totalTimeInForeground > 0L }?.sumOf { it.totalTimeInForeground } ?: 0L
        if (phoneMs > 0L) {
            cachedTotalPhoneTodayMs = phoneMs
        }
    }

    private fun expireStaleRunIfNeeded(pkg: String, run: ActiveRun, nowElapsed: Long, nowWall: Long) {
        if (run.id.isEmpty()) return
        val idleElapsed = if (run.nonConfirmedSince > 0L) {
            nowElapsed - run.nonConfirmedSince
        } else {
            nowElapsed - run.lastConfirmedElapsed
        }
        // Only expire when the user has actually stopped watching for >= 15s (never cut an active run at 00:00 midnight)
        if (idleElapsed >= SESSION_GRACE_MS || idleElapsed < 0L) {
            if (run.durationMs >= MIN_VALID_SESSION_MS) {
                val endWall = (run.startWallMs + run.durationMs).coerceAtLeast(run.startWallMs)
                upsertRunToHistory(pkg, run, if (endWall > 0L) endWall else nowWall)
                persist()
            }
            run.reset()
        }
    }

    /**
     * Calculates the representative personal session length using the midpoint between
     * the median and the maximum ((median + max) / 2), filtering out micro-sessions under 1 minute (60s).
     * If no sessions >= 1 minute exist, falls back to available sessions or fallbackMs.
     */
    fun calculateRepresentativeSessionMs(
        sessions: List<SessionRecord>,
        fallbackMs: Long = 0L,
        minDurationMs: Long = MIN_REPRESENTATIVE_SESSION_MS
    ): Long {
        val eligible = sessions.filter { it.durationMs >= minDurationMs }
        val target = when {
            eligible.isNotEmpty() -> eligible
            sessions.isNotEmpty() -> sessions
            else -> return fallbackMs
        }
        val sorted = target.map { it.durationMs }.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
        }
        val maxVal = sorted.last()
        return (median + maxVal) / 2L
    }

    /** Fast, non-blocking lookup of personalized ratios and baselines for Jev runtime evaluation. */
    @Synchronized
    fun quickContextMetrics(
        context: Context,
        pkg: String,
        currentContinuousMs: Long
    ): QuickPersonalMetrics {
        ensureInitialized(context)
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val run = runs[pkg]
        if (run != null) {
            expireStaleRunIfNeeded(pkg, run, nowElapsed, nowWall)
        }
        val appSessions = sessions.filter { it.packageName == pkg }
        val basisSessions = if (appSessions.size >= 2) appSessions else sessions
        val avgSessionMs = calculateRepresentativeSessionMs(basisSessions)
        val avgSessionMinutes = if (avgSessionMs >= 30_000L) round2(avgSessionMs / 60_000.0) else null

        val activeRunMs = run?.takeIf {
            it.id.isNotEmpty() && nowElapsed - it.lastConfirmedElapsed in 0L until SESSION_GRACE_MS
        }?.durationMs ?: 0L
        val effectiveCurrentSessionMs = maxOf(currentContinuousMs, activeRunMs)
        val sessionVsAvgRatio = if (avgSessionMs >= 30_000L && effectiveCurrentSessionMs > 0L) {
            round2(effectiveCurrentSessionMs.toDouble() / avgSessionMs.toDouble())
        } else null

        val liveToday = runCatching {
            ShortformDailyStore.today(
                context.applicationContext,
                if (pkg == PKG_INSTAGRAM) "instagram" else "youtube"
            )
        }.getOrNull()
        val todayStartWallMs = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val elapsedSinceMidnightMs = (nowWall - todayStartWallMs).coerceAtLeast(0L)
        val currentSessionTodayMs = minOf(effectiveCurrentSessionMs, elapsedSinceMidnightMs)
        val todayMs = maxOf(liveToday?.ms ?: 0L, currentSessionTodayMs)
        val todayShortformMinutes = if (todayMs > 0L) round2(todayMs / 60_000.0) else null
        val avg7dMs = cached7dAvgByPkg[pkg] ?: 0L
        val avg7dDailyMinutes = if (avg7dMs >= 60_000L) round2(avg7dMs / 60_000.0) else null
        val todayVs7dRatio = if (avg7dMs >= 60_000L && todayMs > 0L) {
            round2(todayMs.toDouble() / avg7dMs.toDouble())
        } else null

        val fallbackSessionSpm = basisSessions.let { list ->
            val eligible = list.filter { it.durationMs >= MIN_REPRESENTATIVE_SESSION_MS }
            val target = if (eligible.isNotEmpty()) eligible else list
            val sumMs = target.sumOf { it.durationMs }
            val sumSwipes = target.sumOf { it.swipes }
            if (sumMs >= 10_000L && sumSwipes > 0) round2(sumSwipes.toDouble() / (sumMs / 60_000.0)) else null
        }
        val personalAvgSpm = cachedPersonalAvgSpmByPkg[pkg] ?: fallbackSessionSpm

        val effectivePhoneMs = maxOf(cachedTotalPhoneTodayMs, todayMs)
        val shortformShareRatio = if (effectivePhoneMs >= 60_000L && todayMs > 0L) {
            round2((todayMs.toDouble() / effectivePhoneMs.toDouble()).coerceIn(0.0, 1.0))
        } else null

        return QuickPersonalMetrics(
            avgSessionMs = avgSessionMs,
            avgSessionMinutes = avgSessionMinutes,
            sessionVsAvgRatio = sessionVsAvgRatio,
            todayDurationMs = todayMs,
            todayShortformMinutes = todayShortformMinutes,
            avg7dDailyMs = avg7dMs,
            avg7dDailyMinutes = avg7dDailyMinutes,
            todayVs7dRatio = todayVs7dRatio,
            personalAvgSpm = personalAvgSpm,
            shortformShareOfPhoneRatio = shortformShareRatio
        )
    }

    @Synchronized
    private fun ensureInitialized(context: Context) {
        if (initialized) return
        val appCtx = context.applicationContext
        file = AtomicFile(File(appCtx.noBackupFilesDir, "shortform_personal_stats_v1.json"))
        sessions.clear()
        try {
            val text = file!!.openRead().bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val arr = root.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val pkg = o.optString("pkg")
                val date = o.optString("date")
                val duration = o.optLong("durationMs")
                if (id.isNotBlank() && pkg.isNotBlank() && date.isNotBlank() && duration >= MIN_VALID_SESSION_MS) {
                    sessions.add(
                        SessionRecord(
                            id = id,
                            packageName = pkg,
                            date = date,
                            startWallMs = o.optLong("startWallMs"),
                            updatedWallMs = o.optLong("updatedWallMs"),
                            durationMs = duration,
                            swipes = o.optInt("swipes")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Fresh file or no existing file yet.
        }
        initialized = true
    }

    @Synchronized
    fun onPresence(
        context: Context,
        app: ShortformApp,
        confirmed: Boolean,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis(),
        sameAppSafe: Boolean = confirmed
    ) {
        ensureInitialized(context)
        val pkg = pkgOf(app)
        val run = runs.getValue(pkg)
        expireStaleRunIfNeeded(pkg, run, nowElapsed, nowWall)
        if (confirmed) {
            if (run.id.isEmpty()) {
                run.id = UUID.randomUUID().toString()
                run.startWallMs = nowWall
                run.lastConfirmedElapsed = nowElapsed
                run.nonConfirmedSince = 0L
                run.durationMs = 0L
                run.swipes = 0
                run.lastPersistElapsed = nowElapsed
            } else {
                val dt = nowElapsed - run.lastConfirmedElapsed
                if (dt in 1L..2000L) {
                    run.durationMs += dt
                }
                run.lastConfirmedElapsed = nowElapsed
                run.nonConfirmedSince = 0L
            }
            if (run.durationMs >= MIN_VALID_SESSION_MS && nowElapsed - run.lastPersistElapsed >= 5000L) {
                upsertRunToHistory(pkg, run, nowWall)
                run.lastPersistElapsed = nowElapsed
                persist()
            }
        } else if (sameAppSafe && run.id.isNotEmpty()) {
            run.nonConfirmedSince = 0L
        } else {
            if (run.id.isNotEmpty()) {
                if (run.nonConfirmedSince == 0L) run.nonConfirmedSince = nowElapsed
                if (nowElapsed - run.nonConfirmedSince >= SESSION_GRACE_MS) {
                    if (run.durationMs >= MIN_VALID_SESSION_MS) {
                        upsertRunToHistory(pkg, run, nowWall)
                        persist()
                    }
                    run.reset()
                }
            }
        }
    }

    @Synchronized
    fun onTransition(
        context: Context,
        app: ShortformApp,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis()
    ) {
        ensureInitialized(context)
        val pkg = pkgOf(app)
        val run = runs.getValue(pkg)
        expireStaleRunIfNeeded(pkg, run, nowElapsed, nowWall)
        if (run.id.isEmpty()) {
            run.id = UUID.randomUUID().toString()
            run.startWallMs = nowWall
            run.lastConfirmedElapsed = nowElapsed
            run.nonConfirmedSince = 0L
            run.durationMs = 0L
            run.swipes = 1
            run.lastPersistElapsed = nowElapsed
        } else {
            val dt = nowElapsed - run.lastConfirmedElapsed
            if (dt in 1L..2000L) {
                run.durationMs += dt
            }
            run.lastConfirmedElapsed = nowElapsed
            run.nonConfirmedSince = 0L
            run.swipes++
            if (run.durationMs >= MIN_VALID_SESSION_MS) {
                upsertRunToHistory(pkg, run, nowWall)
                run.lastPersistElapsed = nowElapsed
                persist()
            }
        }
    }

    @Synchronized
    fun clear(context: Context) {
        ensureInitialized(context)
        sessions.clear()
        runs.values.forEach { it.reset() }
        persist()
    }

    private fun upsertRunToHistory(pkg: String, run: ActiveRun, nowWall: Long) {
        val dateStr = Instant.ofEpochMilli(run.startWallMs.takeIf { it > 0L } ?: nowWall)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        val record = SessionRecord(
            id = run.id,
            packageName = pkg,
            date = dateStr,
            startWallMs = run.startWallMs,
            updatedWallMs = nowWall,
            durationMs = run.durationMs,
            swipes = run.swipes
        )
        val existingIdx = sessions.indexOfFirst { it.id == run.id }
        if (existingIdx >= 0) {
            sessions[existingIdx] = record
        } else {
            sessions.add(record)
        }
        val cutoffDate = LocalDate.now().minusDays(30).toString()
        sessions.removeAll { it.date < cutoffDate }
        while (sessions.size > MAX_STORED_SESSIONS) {
            sessions.removeAt(0)
        }
    }

    private fun persist() {
        val target = file ?: return
        var stream: java.io.FileOutputStream? = null
        try {
            val root = JSONObject()
            val arr = JSONArray()
            for (s in sessions) {
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("pkg", s.packageName)
                        .put("date", s.date)
                        .put("startWallMs", s.startWallMs)
                        .put("updatedWallMs", s.updatedWallMs)
                        .put("durationMs", s.durationMs)
                        .put("swipes", s.swipes)
                )
            }
            root.put("sessions", arr)
            stream = target.startWrite()
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (_: Exception) {
            stream?.let { runCatching { target.failWrite(it) } }
        }
    }

    private data class DailyAgg(
        val date: LocalDate,
        val durationMs: Long,
        val swipes: Long,
        val observed: Boolean
    )

    suspend fun loadSummary(
        context: Context,
        todayUsageReport: Report?,
        periodUsageReport: Report? = todayUsageReport
    ): PersonalizedSummary {
        val appCtx = context.applicationContext
        val allDbRows = ItvDatabase.get(appCtx).shortformDao().all("demo-guest")
        val igLiveToday = ShortformDailyStore.today(appCtx, "instagram")
        val ytLiveToday = ShortformDailyStore.today(appCtx, "youtube")

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val snapshotSessions: List<SessionRecord>
        val igActiveSession: Pair<Long, Int>?
        val ytActiveSession: Pair<Long, Int>?

        synchronized(this) {
            ensureInitialized(appCtx)
            // Expire any stale run from a previous day or > 15s ago, and flush active runs >= 5s
            for ((pkg, run) in runs) {
                expireStaleRunIfNeeded(pkg, run, nowElapsed, nowWall)
                if (run.id.isNotEmpty() && run.durationMs >= MIN_VALID_SESSION_MS &&
                    nowElapsed - run.lastConfirmedElapsed < SESSION_GRACE_MS
                ) {
                    upsertRunToHistory(pkg, run, nowWall)
                }
            }
            snapshotSessions = sessions.toList()
            igActiveSession = runs[PKG_INSTAGRAM]?.takeIf {
                it.id.isNotEmpty() && nowElapsed - it.lastConfirmedElapsed < SESSION_GRACE_MS
            }?.let { it.durationMs to it.swipes }
            ytActiveSession = runs[PKG_YOUTUBE]?.takeIf {
                it.id.isNotEmpty() && nowElapsed - it.lastConfirmedElapsed < SESSION_GRACE_MS
            }?.let { it.durationMs to it.swipes }
        }

        val todayDate = LocalDate.now()
        val start7d = todayDate.minusDays(6)
        val start30d = todayDate.minusDays(29)

        // Aggregate daily rows per package and date
        fun dailyMapForPackage(pkg: String, liveToday: ShortformDailyStore.Day): Map<LocalDate, DailyAgg> {
            val byDate = linkedMapOf<LocalDate, DailyAgg>()
            for (row in allDbRows) {
                if (row.packageName != pkg) continue
                val d = runCatching { LocalDate.parse(row.localDate) }.getOrNull() ?: continue
                val prev = byDate[d]
                byDate[d] = DailyAgg(
                    date = d,
                    durationMs = (prev?.durationMs ?: 0L) + row.durationMs,
                    swipes = (prev?.swipes ?: 0L) + row.transitionCount,
                    observed = (prev?.observed ?: false) || row.observed || row.durationMs > 0L
                )
            }
            val existingToday = byDate[todayDate]
            val mergedTodayMs = maxOf(existingToday?.durationMs ?: 0L, liveToday.ms)
            val mergedTodaySwipes = maxOf(existingToday?.swipes ?: 0L, liveToday.transitions.toLong())
            val mergedObserved = (existingToday?.observed ?: false) || liveToday.observed || mergedTodayMs > 0L
            if (mergedObserved || existingToday != null) {
                byDate[todayDate] = DailyAgg(todayDate, mergedTodayMs, mergedTodaySwipes, mergedObserved)
            }
            return byDate
        }

        val igDaily = dailyMapForPackage(PKG_INSTAGRAM, igLiveToday)
        val ytDaily = dailyMapForPackage(PKG_YOUTUBE, ytLiveToday)

        // Combined daily map across all dates present in either igDaily or ytDaily
        val combinedDaily = linkedMapOf<LocalDate, DailyAgg>()
        for (d in (igDaily.keys + ytDaily.keys).sorted()) {
            val ig = igDaily[d]
            val yt = ytDaily[d]
            combinedDaily[d] = DailyAgg(
                date = d,
                durationMs = (ig?.durationMs ?: 0L) + (yt?.durationMs ?: 0L),
                swipes = (ig?.swipes ?: 0L) + (yt?.swipes ?: 0L),
                observed = (ig?.observed ?: false) || (yt?.observed ?: false)
            )
        }

        // Total app usage today from UsageReport (only active hours > 0)
        fun appUsageTodayMs(pkg: String): Long {
            val r = todayUsageReport ?: return 0L
            val minutes = r.hourlyApps.asSequence().flatten()
                .filter { it.packageName == pkg && it.minutes > 0.0 }
                .sumOf { it.minutes }
            return (minutes * 60_000.0).toLong()
        }
        val igParentTodayMs = appUsageTodayMs(PKG_INSTAGRAM)
        val ytParentTodayMs = appUsageTodayMs(PKG_YOUTUBE)
        val totalPhoneTodayMs = ((todayUsageReport?.total ?: 0.0) * 60_000.0).toLong()

        // Period app usage & active-day phone usage (excluding 0-usage days/hours)
        fun appUsagePeriodTotalMs(pkg: String): Long {
            val r = periodUsageReport ?: return 0L
            val avgMinutesPerDay = r.hourlyApps.asSequence().flatten()
                .filter { it.packageName == pkg && it.minutes > 0.0 }
                .sumOf { it.minutes }
            return (avgMinutesPerDay * r.days.coerceAtLeast(1) * 60_000.0).toLong()
        }
        val igParentPeriodTotalMs = appUsagePeriodTotalMs(PKG_INSTAGRAM)
        val ytParentPeriodTotalMs = appUsagePeriodTotalMs(PKG_YOUTUBE)
        val periodDays = periodUsageReport?.days ?: 1
        val activePhoneDaysInPeriod = periodUsageReport?.dailyTotals?.values?.count { it >= 1.0 }
            ?.takeIf { it > 0 } ?: 1
        val totalPhonePeriodActiveMs = periodUsageReport?.let { rep ->
            val activeSum = rep.dailyTotals.values.filter { it >= 1.0 }.sum()
            val minutes = if (activeSum > 0.0) activeSum else rep.total
            (minutes * 60_000.0).toLong()
        } ?: 0L
        val avgPhonePeriodActiveDayMs = totalPhonePeriodActiveMs / activePhoneDaysInPeriod

        fun buildMetrics(
            filter: TargetFilter,
            dailyMap: Map<LocalDate, DailyAgg>,
            matchingSessions: List<SessionRecord>,
            activeSession: Pair<Long, Int>?,
            parentAppMs: Long,
            parentAppPeriodTotalMs: Long
        ): PersonalizedTargetMetrics {
            val todayAgg = dailyMap[todayDate]
            val todayMs = todayAgg?.durationMs ?: 0L
            val todaySwipes = todayAgg?.swipes ?: 0L

            // Exclude 0-usage days (and < 5s accidental noise when valid >= 5s days exist) so averages are never diluted
            val rawDays7 = dailyMap.values.filter { !it.date.isBefore(start7d) && !it.date.isAfter(todayDate) }
            val days7 = rawDays7.filter { it.durationMs >= MIN_VALID_SESSION_MS }
                .ifEmpty { rawDays7.filter { it.durationMs > 0L } }
            val observedDays7d = days7.size
            val total7dMs = days7.sumOf { it.durationMs }
            val avg7dDailyMs = if (observedDays7d > 0) total7dMs / observedDays7d else 0L
            val todayVs7dRatio = if (avg7dDailyMs > 0L && todayMs > 0L) todayMs.toDouble() / avg7dDailyMs.toDouble() else null

            val total7dSwipes = days7.sumOf { it.swipes }
            val avg7dDailySwipes = if (observedDays7d > 0) total7dSwipes.toDouble() / observedDays7d else 0.0
            val todayVs7dSwipesRatio = if (avg7dDailySwipes > 0.0 && todaySwipes > 0L) todaySwipes.toDouble() / avg7dDailySwipes else null

            val rawDays30 = dailyMap.values.filter { !it.date.isBefore(start30d) && !it.date.isAfter(todayDate) }
            val days30 = rawDays30.filter { it.durationMs >= MIN_VALID_SESSION_MS }
                .ifEmpty { rawDays30.filter { it.durationMs > 0L } }
            val observedDays30d = days30.size
            val total30dMs = days30.sumOf { it.durationMs }
            val avg30dDailyMs = if (observedDays30d > 0) total30dMs / observedDays30d else 0L
            val todayVs30dRatio = if (avg30dDailyMs > 0L && todayMs > 0L) todayMs.toDouble() / avg30dDailyMs.toDouble() else null
            val avg7dVs30dRatio = if (avg30dDailyMs > 0L && avg7dDailyMs > 0L) {
                avg7dDailyMs.toDouble() / avg30dDailyMs.toDouble()
            } else null

            // Valid sessions (>= 5s) and representative sessions (>= 60s, excluding micro-views under 1 min)
            val validSessions = matchingSessions.filter { it.durationMs >= MIN_VALID_SESSION_MS }
            val eligibleSessions = matchingSessions.filter { it.durationMs >= MIN_REPRESENTATIVE_SESSION_MS }
            val latestStored = validSessions.maxByOrNull { it.updatedWallMs }
            val currentSessionLive = activeSession != null && activeSession.first > 0L
            val currentSessionMs = when {
                activeSession != null && activeSession.first > 0L -> activeSession.first
                latestStored != null -> latestStored.durationMs
                todayMs >= MIN_VALID_SESSION_MS -> todayMs
                else -> 0L
            }
            val currentSessionSwipes = when {
                activeSession != null && activeSession.first > 0L -> activeSession.second
                latestStored != null -> latestStored.swipes
                else -> todaySwipes.toInt()
            }

            val totalRecordedSessions = when {
                eligibleSessions.isNotEmpty() -> eligibleSessions.size
                validSessions.isNotEmpty() -> validSessions.size
                todayMs >= MIN_VALID_SESSION_MS -> 1
                else -> 0
            }
            val avgSessionMs = when {
                eligibleSessions.isNotEmpty() -> calculateRepresentativeSessionMs(eligibleSessions)
                validSessions.isNotEmpty() -> calculateRepresentativeSessionMs(validSessions)
                todayMs >= MIN_VALID_SESSION_MS -> todayMs
                else -> 0L
            }
            val maxSessionMs = when {
                validSessions.isNotEmpty() -> maxOf(validSessions.maxOf { it.durationMs }, currentSessionMs)
                else -> currentSessionMs
            }
            val todayStr = todayDate.toString()
            val todaySessionCount = validSessions.count { it.date == todayStr }.let {
                if (it == 0 && todayMs >= MIN_VALID_SESSION_MS) 1 else it
            }

            // 7-day session metrics (excluding unused days/times and < 1m micro-sessions)
            val sessions7d = validSessions.filter {
                val d = runCatching { LocalDate.parse(it.date) }.getOrNull()
                d != null && !d.isBefore(start7d) && !d.isAfter(todayDate)
            }
            val eligible7d = sessions7d.filter { it.durationMs >= MIN_REPRESENTATIVE_SESSION_MS }
            val sessionCount7d = when {
                eligible7d.isNotEmpty() -> eligible7d.size
                sessions7d.isNotEmpty() -> sessions7d.size
                observedDays7d > 0 -> observedDays7d
                else -> 0
            }
            val avg7dSessionMs = when {
                eligible7d.isNotEmpty() -> calculateRepresentativeSessionMs(eligible7d, fallbackMs = avg7dDailyMs)
                sessions7d.isNotEmpty() -> calculateRepresentativeSessionMs(sessions7d, fallbackMs = avg7dDailyMs)
                observedDays7d > 0 -> avg7dDailyMs
                else -> 0L
            }
            val max7dSessionMs = when {
                sessions7d.isNotEmpty() -> sessions7d.maxOf { it.durationMs }
                else -> avg7dSessionMs
            }
            val avg7dSessionCount = if (observedDays7d > 0) {
                sessionCount7d.toDouble() / observedDays7d
            } else 0.0

            // 30-day session metrics (excluding unused days/times and < 1m micro-sessions)
            val sessions30d = validSessions.filter {
                val d = runCatching { LocalDate.parse(it.date) }.getOrNull()
                d != null && !d.isBefore(start30d) && !d.isAfter(todayDate)
            }
            val eligible30d = sessions30d.filter { it.durationMs >= MIN_REPRESENTATIVE_SESSION_MS }
            val sessionCount30d = when {
                eligible30d.isNotEmpty() -> eligible30d.size
                sessions30d.isNotEmpty() -> sessions30d.size
                observedDays30d > 0 -> observedDays30d
                else -> 0
            }
            val avg30dSessionMs = when {
                eligible30d.isNotEmpty() -> calculateRepresentativeSessionMs(eligible30d, fallbackMs = avg30dDailyMs)
                sessions30d.isNotEmpty() -> calculateRepresentativeSessionMs(sessions30d, fallbackMs = avg30dDailyMs)
                observedDays30d > 0 -> avg30dDailyMs
                else -> 0L
            }
            val max30dSessionMs = when {
                sessions30d.isNotEmpty() -> sessions30d.maxOf { it.durationMs }
                else -> avg30dSessionMs
            }
            val avg30dSessionCount = if (observedDays30d > 0) {
                sessionCount30d.toDouble() / observedDays30d
            } else 0.0

            val currentVsAvgSessionRatio = if (avgSessionMs > 0L && currentSessionMs > 0L) {
                currentSessionMs.toDouble() / avgSessionMs.toDouble()
            } else null
            val session7dVs30dRatio = if (avg30dSessionMs > 0L && avg7dSessionMs > 0L) {
                avg7dSessionMs.toDouble() / avg30dSessionMs.toDouble()
            } else null

            val avgSwipesPerSession = when {
                validSessions.isNotEmpty() -> validSessions.sumOf { it.swipes }.toDouble() / validSessions.size
                todaySwipes > 0L -> todaySwipes.toDouble()
                else -> 0.0
            }
            val avg7dSwipesPerSession = when {
                sessions7d.isNotEmpty() -> sessions7d.sumOf { it.swipes }.toDouble() / sessions7d.size
                sessionCount7d > 0 -> total7dSwipes.toDouble() / sessionCount7d
                else -> 0.0
            }
            val avg7dSpm = if (total7dMs >= 10_000L) {
                total7dSwipes.toDouble() / (total7dMs / 60_000.0)
            } else 0.0

            val total30dSwipes = days30.sumOf { it.swipes }
            val avg30dDailySwipes = if (observedDays30d > 0) total30dSwipes.toDouble() / observedDays30d else 0.0
            val avg30dSwipesPerSession = when {
                sessions30d.isNotEmpty() -> sessions30d.sumOf { it.swipes }.toDouble() / sessions30d.size
                sessionCount30d > 0 -> total30dSwipes.toDouble() / sessionCount30d
                else -> 0.0
            }

            val todayAvgSpm = if (todayMs >= 10_000L) todaySwipes.toDouble() / (todayMs / 60_000.0) else 0.0
            val personalAvgSpm = if (total30dMs >= 10_000L) {
                total30dSwipes.toDouble() / (total30dMs / 60_000.0)
            } else todayAvgSpm

            val effectiveParentMs = maxOf(parentAppMs, todayMs)
            val shortformShareOfAppRatio = if (effectiveParentMs > 0L && todayMs > 0L) {
                (todayMs.toDouble() / effectiveParentMs.toDouble()).coerceIn(0.0, 1.0)
            } else null

            val effectivePhoneMs = maxOf(totalPhoneTodayMs, todayMs)
            val shortformShareOfPhoneRatio = if (effectivePhoneMs > 0L && todayMs > 0L) {
                (todayMs.toDouble() / effectivePhoneMs.toDouble()).coerceIn(0.0, 1.0)
            } else null

            val periodShortformTotalMs = when {
                periodDays >= 30 -> total30dMs
                periodDays >= 7 -> total7dMs
                else -> todayMs
            }
            val periodShortformDailyAvgMs = when {
                periodDays >= 30 -> avg30dDailyMs
                periodDays >= 7 -> avg7dDailyMs
                else -> todayMs
            }
            val effectivePeriodAppMs = maxOf(parentAppPeriodTotalMs, periodShortformTotalMs)
            val periodShareOfAppRatio = if (effectivePeriodAppMs > 0L && periodShortformTotalMs > 0L) {
                (periodShortformTotalMs.toDouble() / effectivePeriodAppMs.toDouble()).coerceIn(0.0, 1.0)
            } else shortformShareOfAppRatio

            val effectivePeriodPhoneDailyMs = maxOf(avgPhonePeriodActiveDayMs, periodShortformDailyAvgMs)
            val periodShareOfPhoneRatio = if (effectivePeriodPhoneDailyMs > 0L && periodShortformDailyAvgMs > 0L) {
                (periodShortformDailyAvgMs.toDouble() / effectivePeriodPhoneDailyMs.toDouble()).coerceIn(0.0, 1.0)
            } else shortformShareOfPhoneRatio

            return PersonalizedTargetMetrics(
                filter = filter,
                todayDurationMs = todayMs,
                avg7dDailyMs = avg7dDailyMs,
                observedDays7d = observedDays7d,
                total7dDurationMs = total7dMs,
                todayVs7dRatio = todayVs7dRatio,
                avg30dDailyMs = avg30dDailyMs,
                observedDays30d = observedDays30d,
                total30dDurationMs = total30dMs,
                todayVs30dRatio = todayVs30dRatio,
                avg7dVs30dRatio = avg7dVs30dRatio,
                currentSessionMs = currentSessionMs,
                currentSessionLive = currentSessionLive,
                currentSessionSwipes = currentSessionSwipes,
                avgSessionMs = avgSessionMs,
                maxSessionMs = maxSessionMs,
                totalRecordedSessions = totalRecordedSessions,
                todaySessionCount = todaySessionCount,
                avg7dSessionMs = avg7dSessionMs,
                max7dSessionMs = max7dSessionMs,
                sessionCount7d = sessionCount7d,
                avg7dSessionCount = avg7dSessionCount,
                avg30dSessionMs = avg30dSessionMs,
                max30dSessionMs = max30dSessionMs,
                sessionCount30d = sessionCount30d,
                avg30dSessionCount = avg30dSessionCount,
                currentVsAvgSessionRatio = currentVsAvgSessionRatio,
                session7dVs30dRatio = session7dVs30dRatio,
                todaySwipes = todaySwipes,
                total7dSwipes = total7dSwipes,
                avg7dDailySwipes = avg7dDailySwipes,
                todayVs7dSwipesRatio = todayVs7dSwipesRatio,
                avg7dSwipesPerSession = avg7dSwipesPerSession,
                avg7dSpm = avg7dSpm,
                total30dSwipes = total30dSwipes,
                avg30dDailySwipes = avg30dDailySwipes,
                avg30dSwipesPerSession = avg30dSwipesPerSession,
                avgSwipesPerSession = avgSwipesPerSession,
                todayAvgSpm = todayAvgSpm,
                personalAvgSpm = personalAvgSpm,
                parentAppTodayMs = effectiveParentMs,
                shortformShareOfAppRatio = shortformShareOfAppRatio,
                totalPhoneTodayMs = effectivePhoneMs,
                shortformShareOfPhoneRatio = shortformShareOfPhoneRatio,
                periodShareOfAppRatio = periodShareOfAppRatio,
                periodShareOfPhoneRatio = periodShareOfPhoneRatio
            )
        }

        val igSessions = snapshotSessions.filter { it.packageName == PKG_INSTAGRAM }
        val ytSessions = snapshotSessions.filter { it.packageName == PKG_YOUTUBE }
        val combinedActive = when {
            igActiveSession != null && ytActiveSession != null ->
                if (igActiveSession.first >= ytActiveSession.first) igActiveSession else ytActiveSession
            igActiveSession != null -> igActiveSession
            else -> ytActiveSession
        }

        val igMetrics = buildMetrics(
            filter = TargetFilter.INSTAGRAM,
            dailyMap = igDaily,
            matchingSessions = igSessions,
            activeSession = igActiveSession,
            parentAppMs = igParentTodayMs,
            parentAppPeriodTotalMs = igParentPeriodTotalMs
        )
        val ytMetrics = buildMetrics(
            filter = TargetFilter.YOUTUBE,
            dailyMap = ytDaily,
            matchingSessions = ytSessions,
            activeSession = ytActiveSession,
            parentAppMs = ytParentTodayMs,
            parentAppPeriodTotalMs = ytParentPeriodTotalMs
        )
        cached7dAvgByPkg = mapOf(
            PKG_INSTAGRAM to igMetrics.avg7dDailyMs,
            PKG_YOUTUBE to ytMetrics.avg7dDailyMs
        )

        return PersonalizedSummary(
            combined = buildMetrics(
                filter = TargetFilter.COMBINED,
                dailyMap = combinedDaily,
                matchingSessions = snapshotSessions,
                activeSession = combinedActive,
                parentAppMs = igParentTodayMs + ytParentTodayMs,
                parentAppPeriodTotalMs = igParentPeriodTotalMs + ytParentPeriodTotalMs
            ),
            instagram = igMetrics,
            youtube = ytMetrics
        )
    }
}
