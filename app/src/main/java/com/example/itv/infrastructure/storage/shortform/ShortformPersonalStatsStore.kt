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
        // 1. Today vs 7-day & 30-day daily averages
        val todayDurationMs: Long,
        val avg7dDailyMs: Long,
        val observedDays7d: Int,
        val total7dDurationMs: Long,
        /** Today's duration divided by 7-day daily average (1.0 = 100%). Null if no 7d baseline. */
        val todayVs7dRatio: Double?,
        val avg30dDailyMs: Long,
        val observedDays30d: Int,
        val total30dDurationMs: Long,
        val todayVs30dRatio: Double?,

        // 2. Session length personalization (Current/Recent session vs Personal average session length)
        val currentSessionMs: Long,
        val currentSessionLive: Boolean,
        val currentSessionSwipes: Int,
        val avgSessionMs: Long,
        val maxSessionMs: Long,
        val totalRecordedSessions: Int,
        val todaySessionCount: Int,
        val avg7dSessionCount: Double,
        /** Current (or most recent) session duration divided by personal average session duration. */
        val currentVsAvgSessionRatio: Double?,

        // 3. Transitions (Swipes) & SPM personalization
        val todaySwipes: Long,
        val avg7dDailySwipes: Double,
        val todayVs7dSwipesRatio: Double?,
        val avgSwipesPerSession: Double,
        val todayAvgSpm: Double,
        val personalAvgSpm: Double,

        // 4. Share of total app/phone usage
        val parentAppTodayMs: Long,
        val shortformShareOfAppRatio: Double?,
        val totalPhoneTodayMs: Long,
        val shortformShareOfPhoneRatio: Double?
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

    data class QuickPersonalMetrics(
        val avgSessionMs: Long,
        val sessionVsAvgRatio: Double?,
        val todayDurationMs: Long,
        val avg7dDailyMs: Long,
        val todayVs7dRatio: Double?
    )

    private fun pkgOf(app: ShortformApp): String = when (app) {
        ShortformApp.INSTAGRAM -> PKG_INSTAGRAM
        ShortformApp.YOUTUBE -> PKG_YOUTUBE
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    /** Refreshes the 7-day daily average cache from Room on a background thread. */
    suspend fun refreshBaselineCache(context: Context) {
        val appCtx = context.applicationContext
        val allDbRows = runCatching { ItvDatabase.get(appCtx).shortformDao().all("demo-guest") }.getOrDefault(emptyList())
        val todayDate = LocalDate.now()
        val start7d = todayDate.minusDays(6)
        val result = mutableMapOf<String, Long>()
        for (pkg in listOf(PKG_INSTAGRAM, PKG_YOUTUBE)) {
            val byDate = linkedMapOf<LocalDate, Long>()
            for (row in allDbRows) {
                if (row.packageName != pkg) continue
                val d = runCatching { LocalDate.parse(row.localDate) }.getOrNull() ?: continue
                if (!d.isBefore(start7d) && !d.isAfter(todayDate) && (row.observed || row.durationMs > 0L)) {
                    byDate[d] = (byDate[d] ?: 0L) + row.durationMs
                }
            }
            val liveToday = ShortformDailyStore.today(appCtx, if (pkg == PKG_INSTAGRAM) "instagram" else "youtube")
            if (liveToday.observed || liveToday.ms > 0L) {
                byDate[todayDate] = maxOf(byDate[todayDate] ?: 0L, liveToday.ms)
            }
            if (byDate.isNotEmpty()) {
                result[pkg] = byDate.values.sum() / byDate.size
            }
        }
        cached7dAvgByPkg = result
    }

    /** Fast, non-blocking lookup of personalized ratios for Jev runtime evaluation. */
    @Synchronized
    fun quickContextMetrics(
        context: Context,
        pkg: String,
        currentSessionMs: Long,
        currentContinuousMs: Long
    ): QuickPersonalMetrics {
        ensureInitialized(context)
        val appSessions = sessions.filter { it.packageName == pkg }
        val basisSessions = if (appSessions.size >= 2) appSessions else sessions
        val avgSessionMs = if (basisSessions.isNotEmpty()) {
            basisSessions.sumOf { it.durationMs } / basisSessions.size
        } else 0L

        val activeRunMs = runs[pkg]?.durationMs ?: 0L
        val effectiveCurrentSessionMs = maxOf(currentContinuousMs, activeRunMs, currentSessionMs)
        val sessionVsAvgRatio = if (avgSessionMs >= 30_000L && effectiveCurrentSessionMs > 0L) {
            round2(effectiveCurrentSessionMs.toDouble() / avgSessionMs.toDouble())
        } else null

        val liveToday = runCatching {
            ShortformDailyStore.today(
                context.applicationContext,
                if (pkg == PKG_INSTAGRAM) "instagram" else "youtube"
            )
        }.getOrNull()
        val todayMs = maxOf(liveToday?.ms ?: 0L, effectiveCurrentSessionMs)
        val avg7dMs = cached7dAvgByPkg[pkg] ?: 0L
        val todayVs7dRatio = if (avg7dMs >= 60_000L && todayMs > 0L) {
            round2(todayMs.toDouble() / avg7dMs.toDouble())
        } else null

        return QuickPersonalMetrics(
            avgSessionMs = avgSessionMs,
            sessionVsAvgRatio = sessionVsAvgRatio,
            todayDurationMs = todayMs,
            avg7dDailyMs = avg7dMs,
            todayVs7dRatio = todayVs7dRatio
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

    suspend fun loadSummary(context: Context, todayUsageReport: Report?): PersonalizedSummary {
        val appCtx = context.applicationContext
        val allDbRows = ItvDatabase.get(appCtx).shortformDao().all("demo-guest")
        val igLiveToday = ShortformDailyStore.today(appCtx, "instagram")
        val ytLiveToday = ShortformDailyStore.today(appCtx, "youtube")

        val nowElapsed = SystemClock.elapsedRealtime()
        val snapshotSessions: List<SessionRecord>
        val igActiveSession: Pair<Long, Int>?
        val ytActiveSession: Pair<Long, Int>?

        synchronized(this) {
            ensureInitialized(appCtx)
            // Flush any active run >= 5s so snapshot includes latest in-memory progress
            for ((pkg, run) in runs) {
                if (run.id.isNotEmpty() && run.durationMs >= MIN_VALID_SESSION_MS &&
                    nowElapsed - run.lastConfirmedElapsed < SESSION_GRACE_MS
                ) {
                    upsertRunToHistory(pkg, run, System.currentTimeMillis())
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

        // Total app usage today from UsageReport
        fun appUsageTodayMs(pkg: String): Long {
            val r = todayUsageReport ?: return 0L
            val minutes = r.hourlyApps.flatten().filter { it.packageName == pkg }.sumOf { it.minutes }
            return (minutes * 60_000.0).toLong()
        }
        val igParentTodayMs = appUsageTodayMs(PKG_INSTAGRAM)
        val ytParentTodayMs = appUsageTodayMs(PKG_YOUTUBE)
        val totalPhoneTodayMs = ((todayUsageReport?.total ?: 0.0) * 60_000.0).toLong()

        fun buildMetrics(
            filter: TargetFilter,
            dailyMap: Map<LocalDate, DailyAgg>,
            matchingSessions: List<SessionRecord>,
            activeSession: Pair<Long, Int>?,
            parentAppMs: Long
        ): PersonalizedTargetMetrics {
            val todayAgg = dailyMap[todayDate]
            val todayMs = todayAgg?.durationMs ?: 0L
            val todaySwipes = todayAgg?.swipes ?: 0L

            val days7 = dailyMap.values.filter { !it.date.isBefore(start7d) && !it.date.isAfter(todayDate) && (it.observed || it.durationMs > 0L) }
            val observedDays7d = days7.size
            val total7dMs = days7.sumOf { it.durationMs }
            val avg7dDailyMs = if (observedDays7d > 0) total7dMs / observedDays7d else 0L
            val todayVs7dRatio = if (avg7dDailyMs > 0L) todayMs.toDouble() / avg7dDailyMs.toDouble() else null

            val total7dSwipes = days7.sumOf { it.swipes }
            val avg7dDailySwipes = if (observedDays7d > 0) total7dSwipes.toDouble() / observedDays7d else 0.0
            val todayVs7dSwipesRatio = if (avg7dDailySwipes > 0.0) todaySwipes.toDouble() / avg7dDailySwipes else null

            val days30 = dailyMap.values.filter { !it.date.isBefore(start30d) && !it.date.isAfter(todayDate) && (it.observed || it.durationMs > 0L) }
            val observedDays30d = days30.size
            val total30dMs = days30.sumOf { it.durationMs }
            val avg30dDailyMs = if (observedDays30d > 0) total30dMs / observedDays30d else 0L
            val todayVs30dRatio = if (avg30dDailyMs > 0L) todayMs.toDouble() / avg30dDailyMs.toDouble() else null

            // Session metrics
            val latestStored = matchingSessions.maxByOrNull { it.updatedWallMs }
            val currentSessionLive = activeSession != null && activeSession.first > 0L
            val currentSessionMs = when {
                activeSession != null && activeSession.first > 0L -> activeSession.first
                latestStored != null -> latestStored.durationMs
                todayMs > 0L -> todayMs
                else -> 0L
            }
            val currentSessionSwipes = when {
                activeSession != null && activeSession.first > 0L -> activeSession.second
                latestStored != null -> latestStored.swipes
                else -> todaySwipes.toInt()
            }

            val totalRecordedSessions = when {
                matchingSessions.isNotEmpty() -> matchingSessions.size
                todayMs > 0L -> 1
                else -> 0
            }
            val avgSessionMs = when {
                matchingSessions.isNotEmpty() -> matchingSessions.sumOf { it.durationMs } / matchingSessions.size
                todayMs > 0L -> todayMs
                else -> 0L
            }
            val maxSessionMs = when {
                matchingSessions.isNotEmpty() -> maxOf(matchingSessions.maxOf { it.durationMs }, currentSessionMs)
                else -> currentSessionMs
            }
            val todayStr = todayDate.toString()
            val todaySessionCount = matchingSessions.count { it.date == todayStr }.let {
                if (it == 0 && todayMs > 0L) 1 else it
            }
            val sessions7d = matchingSessions.filter {
                val d = runCatching { LocalDate.parse(it.date) }.getOrNull()
                d != null && !d.isBefore(start7d) && !d.isAfter(todayDate)
            }
            val avg7dSessionCount = if (observedDays7d > 0) {
                val count7d = sessions7d.size.coerceAtLeast(if (total7dMs > 0L) observedDays7d else 0)
                count7d.toDouble() / observedDays7d
            } else 0.0

            val currentVsAvgSessionRatio = if (avgSessionMs > 0L && currentSessionMs > 0L) {
                currentSessionMs.toDouble() / avgSessionMs.toDouble()
            } else null

            val avgSwipesPerSession = when {
                matchingSessions.isNotEmpty() -> matchingSessions.sumOf { it.swipes }.toDouble() / matchingSessions.size
                todaySwipes > 0L -> todaySwipes.toDouble()
                else -> 0.0
            }

            val todayAvgSpm = if (todayMs >= 10_000L) todaySwipes.toDouble() / (todayMs / 60_000.0) else 0.0
            val personalAvgSpm = if (total30dMs >= 10_000L) {
                val swipes30d = days30.sumOf { it.swipes }
                swipes30d.toDouble() / (total30dMs / 60_000.0)
            } else todayAvgSpm

            val effectiveParentMs = maxOf(parentAppMs, todayMs)
            val shortformShareOfAppRatio = if (effectiveParentMs > 0L && todayMs > 0L) {
                (todayMs.toDouble() / effectiveParentMs.toDouble()).coerceIn(0.0, 1.0)
            } else null

            val effectivePhoneMs = maxOf(totalPhoneTodayMs, todayMs)
            val shortformShareOfPhoneRatio = if (effectivePhoneMs > 0L && todayMs > 0L) {
                (todayMs.toDouble() / effectivePhoneMs.toDouble()).coerceIn(0.0, 1.0)
            } else null

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
                currentSessionMs = currentSessionMs,
                currentSessionLive = currentSessionLive,
                currentSessionSwipes = currentSessionSwipes,
                avgSessionMs = avgSessionMs,
                maxSessionMs = maxSessionMs,
                totalRecordedSessions = totalRecordedSessions,
                todaySessionCount = todaySessionCount,
                avg7dSessionCount = avg7dSessionCount,
                currentVsAvgSessionRatio = currentVsAvgSessionRatio,
                todaySwipes = todaySwipes,
                avg7dDailySwipes = avg7dDailySwipes,
                todayVs7dSwipesRatio = todayVs7dSwipesRatio,
                avgSwipesPerSession = avgSwipesPerSession,
                todayAvgSpm = todayAvgSpm,
                personalAvgSpm = personalAvgSpm,
                parentAppTodayMs = effectiveParentMs,
                shortformShareOfAppRatio = shortformShareOfAppRatio,
                totalPhoneTodayMs = effectivePhoneMs,
                shortformShareOfPhoneRatio = shortformShareOfPhoneRatio
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
            parentAppMs = igParentTodayMs
        )
        val ytMetrics = buildMetrics(
            filter = TargetFilter.YOUTUBE,
            dailyMap = ytDaily,
            matchingSessions = ytSessions,
            activeSession = ytActiveSession,
            parentAppMs = ytParentTodayMs
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
                parentAppMs = igParentTodayMs + ytParentTodayMs
            ),
            instagram = igMetrics,
            youtube = ytMetrics
        )
    }
}
