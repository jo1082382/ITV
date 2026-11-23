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
    private const val MIN_PHONE_REPRESENTATIVE_SESSION_MS = 120_000L // 2분 미만 퀵체크(카톡 단답, 메모/알림 확인) 필터링
    private const val SESSION_GRACE_MS = 15_000L
    internal const val MAX_RETENTION_MONTHS = 6L
    private const val MAX_STORED_SESSIONS = 6_000

    enum class TargetFilter(val label: String) {
        PHONE_TOTAL("휴대폰 전체"),
        COMBINED("통합 숏폼"),
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

        // 2-B. Phone total session & shortform ratio metrics (휴대폰 전체 세션 및 숏폼 배율 환산)
        val phoneCurrentSessionMs: Long = 0L,
        val phoneCurrentWeightedSessionMs: Long = 0L,
        val phoneCurrentShortformRatio: Double? = null,
        val phoneTodayAvgSessionMs: Long = 0L,
        val phoneTodayAvgWeightedSessionMs: Long = 0L,
        val phoneTodayShortformRatio: Double? = null,
        val phoneTodaySessionsCount: Int = 0,
        val phoneAvgSessionMs: Long = 0L,
        val phoneAvgWeightedSessionMs: Long = 0L,
        val phoneMaxSessionMs: Long = 0L,
        val phoneTotalSessions: Int = 0,
        val phoneAvg7dSessionMs: Long = 0L,
        val phoneAvg7dWeightedSessionMs: Long = 0L,
        val phoneMax7dSessionMs: Long = 0L,
        val phoneSessionCount7d: Int = 0,
        val phoneAvg7dShortformRatio: Double? = null,
        val phoneAvg30dSessionMs: Long = 0L,
        val phoneAvg30dWeightedSessionMs: Long = 0L,
        val phoneMax30dSessionMs: Long = 0L,
        val phoneSessionCount30d: Int = 0,
        val phoneAvg30dShortformRatio: Double? = null,

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
        val periodShareOfPhoneRatio: Double?,

        // 5. Bimodal day clustering (일과 날 vs 쉬는 날 시청 패턴)
        // 5-A. 숏폼 사용량 기준
        val workdayAvgDailyMs: Long = 0L,
        val workdayAvgSessionMs: Long = 0L,
        val restdayAvgDailyMs: Long = 0L,
        val restdayAvgSessionMs: Long = 0L,
        val daySplitThresholdMs: Long = 0L,
        val isBimodalConfident: Boolean = false,
        val bimodalRatio: Double = 1.0,
        val estimatedWorkdayCount: Int = 0,
        val estimatedRestdayCount: Int = 0,

        // 5-B. 휴대폰 총 사용량 기준 (기기 전체 일별 총 사용시간 군집화)
        val phoneWorkdayAvgDailyMs: Long = 0L,
        val phoneRestdayAvgDailyMs: Long = 0L,
        val phoneSplitThresholdMs: Long = 0L,
        val isPhoneBimodalConfident: Boolean = false,
        val phoneBimodalRatio: Double = 1.0,
        val phoneEstimatedWorkdayCount: Int = 0,
        val phoneEstimatedRestdayCount: Int = 0,
        val todayEstimatedDayTypeByPhone: com.example.itv.domain.records.EstimatedDayType? = null,

        // 5-C. Hybrid Calendar + Usage Day-type determination (오늘 일과/휴일 종합 판정)
        val hybridEstimatedDayType: com.example.itv.domain.records.EstimatedDayType? = null,
        val hybridDayTypeReason: String? = null,
        val effectiveSessionMsForToday: Long = 0L
    )

    data class PersonalizedSummary(
        val combined: PersonalizedTargetMetrics,
        val instagram: PersonalizedTargetMetrics,
        val youtube: PersonalizedTargetMetrics,
        val phoneTotal: PersonalizedTargetMetrics? = null
    ) {
        fun forFilter(filter: TargetFilter): PersonalizedTargetMetrics = when (filter) {
            TargetFilter.PHONE_TOTAL -> phoneTotal ?: combined
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
    @Volatile
    private var cachedBimodalClusters: com.example.itv.domain.records.DayTypeClusterResult? = null
    @Volatile
    private var cachedPhoneBimodalClusters: com.example.itv.domain.records.DayTypeClusterResult? = null
    @Volatile
    var cachedPhone30dWeightedAvgSessionMs: Long = 0L
        private set
    @Volatile
    var cachedPhone30dRawAvgSessionMs: Long = 0L
        private set
    @Volatile
    var cachedPhone7dWeightedAvgSessionMs: Long = 0L
        private set
    @Volatile
    var cachedPhone7dRawAvgSessionMs: Long = 0L
        private set
    @Volatile
    var cachedPhoneAvgShortformRatio: Double? = null
        private set

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
        val shortformShareOfPhoneRatio: Double? = null,
        val workdayAvgSessionMs: Long? = null,
        val restdayAvgSessionMs: Long? = null,
        val daySplitThresholdMs: Long? = null,
        val isBimodalConfident: Boolean = false,
        val phoneWorkdayAvgDailyMs: Long? = null,
        val phoneRestdayAvgDailyMs: Long? = null,
        val phoneSplitThresholdMs: Long? = null,
        val isPhoneBimodalConfident: Boolean = false,
        val estimatedDayType: com.example.itv.domain.records.EstimatedDayType? = null,
        val effectiveAvgSessionMs: Long? = null,
        val dayTypeReason: String? = null
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

        // Build combined daily profiles across all packages for bimodal day clustering
        val combinedDaily30d = linkedMapOf<LocalDate, Long>()
        for (row in allDbRows) {
            val d = runCatching { LocalDate.parse(row.localDate) }.getOrNull() ?: continue
            if (!d.isBefore(start30d) && !d.isAfter(todayDate) && (row.observed || row.durationMs > 0L)) {
                combinedDaily30d[d] = (combinedDaily30d[d] ?: 0L) + row.durationMs
            }
        }
        val liveInsta = runCatching { ShortformDailyStore.today(appCtx, "instagram").ms }.getOrDefault(0L)
        val liveYt = runCatching { ShortformDailyStore.today(appCtx, "youtube").ms }.getOrDefault(0L)
        if (liveInsta + liveYt > 0L) {
            combinedDaily30d[todayDate] = maxOf(combinedDaily30d[todayDate] ?: 0L, liveInsta + liveYt)
        }
        val sessionsByDate = synchronized(this) { sessions.groupBy { it.date } }
        val clusterProfiles = combinedDaily30d.map { (date, dailyMs) ->
            val daySessions = (sessionsByDate[date.toString()] ?: emptyList()).map { it.durationMs }
            com.example.itv.domain.records.DayUsageProfile(
                date = date,
                totalDurationMs = dailyMs,
                sessions = daySessions
            )
        }
        cachedBimodalClusters = com.example.itv.domain.records.DayTypeUsageClusterer.cluster(clusterProfiles)

        val usm = appCtx.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
        val zone = ZoneId.systemDefault()
        val nowWall = System.currentTimeMillis()
        val start30dWall = start30d.atStartOfDay(zone).toInstant().toEpochMilli()
        val allDailyStats = runCatching {
            usm?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start30dWall, nowWall)
        }.getOrNull()

        val phoneDailyMap = linkedMapOf<LocalDate, Long>()
        allDailyStats?.forEach { stat ->
            if (stat.totalTimeInForeground > 0L) {
                val d = Instant.ofEpochMilli(stat.firstTimeStamp).atZone(zone).toLocalDate()
                if (!d.isBefore(start30d) && !d.isAfter(todayDate)) {
                    phoneDailyMap[d] = (phoneDailyMap[d] ?: 0L) + stat.totalTimeInForeground
                }
            }
        }
        val todayStartWall = todayDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val todayStats = runCatching {
            usm?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, todayStartWall, nowWall)
        }.getOrNull()
        val phoneMs = todayStats?.filter { it.totalTimeInForeground > 0L }?.sumOf { it.totalTimeInForeground } ?: 0L
        if (phoneMs > 0L) {
            cachedTotalPhoneTodayMs = phoneMs
            phoneDailyMap[todayDate] = maxOf(phoneDailyMap[todayDate] ?: 0L, phoneMs)
        }
        val phoneClusterProfiles = phoneDailyMap.filter { it.value >= 60_000L }.map { (date, dailyMs) ->
            com.example.itv.domain.records.DayUsageProfile(
                date = date,
                totalDurationMs = dailyMs
            )
        }
        cachedPhoneBimodalClusters = com.example.itv.domain.records.DayTypeUsageClusterer.cluster(phoneClusterProfiles)

        val phoneEvents = runCatching {
            com.example.itv.infrastructure.records.AndroidRecordSource(appCtx).events(start30dWall, nowWall)
        }.getOrDefault(emptyList())
        val parsedPhoneSessions = parsePhoneSessions(phoneEvents)
        if (parsedPhoneSessions.isNotEmpty()) {
            val phone30d = parsedPhoneSessions.filter { !it.date.isBefore(start30d) && !it.date.isAfter(todayDate) }
            val phone7d = parsedPhoneSessions.filter { !it.date.isBefore(start7d) && !it.date.isAfter(todayDate) }
            val repPhone30d = phone30d.filter { it.durationMs >= MIN_PHONE_REPRESENTATIVE_SESSION_MS }.ifEmpty { phone30d }
            val repPhone7d = phone7d.filter { it.durationMs >= MIN_PHONE_REPRESENTATIVE_SESSION_MS }.ifEmpty { phone7d }
            if (phone30d.isNotEmpty()) {
                cachedPhone30dWeightedAvgSessionMs = calculateRepresentativeSessionLengths(repPhone30d.map { it.weightedDurationMs }, fallbackMs = 180_000L, minDurationMs = MIN_PHONE_REPRESENTATIVE_SESSION_MS)
                cachedPhone30dRawAvgSessionMs = calculateRepresentativeSessionLengths(repPhone30d.map { it.durationMs }, fallbackMs = 300_000L, minDurationMs = MIN_PHONE_REPRESENTATIVE_SESSION_MS)
                cachedPhoneAvgShortformRatio = repPhone30d.map { it.shortformRatio }.average()
            }
            if (phone7d.isNotEmpty()) {
                cachedPhone7dWeightedAvgSessionMs = calculateRepresentativeSessionLengths(repPhone7d.map { it.weightedDurationMs }, fallbackMs = cachedPhone30dWeightedAvgSessionMs, minDurationMs = MIN_PHONE_REPRESENTATIVE_SESSION_MS)
                cachedPhone7dRawAvgSessionMs = calculateRepresentativeSessionLengths(repPhone7d.map { it.durationMs }, fallbackMs = cachedPhone30dRawAvgSessionMs, minDurationMs = MIN_PHONE_REPRESENTATIVE_SESSION_MS)
            }
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

    data class PhoneSessionRecord(
        val date: LocalDate,
        val startWallMs: Long,
        val endWallMs: Long,
        val durationMs: Long,
        val shortformMs: Long,
        val generalMs: Long,
        val weightedDurationMs: Long = shortformMs + (generalMs / 3L)
    ) {
        val shortformRatio: Double
            get() = if (durationMs > 0L) (shortformMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0) else 0.0
    }

    fun parsePhoneSessions(events: List<com.example.itv.domain.records.UsageObservation>): List<PhoneSessionRecord> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedBy { it.timeStamp }
        val result = mutableListOf<PhoneSessionRecord>()
        var sessionStart: Long? = null
        var lastActive: Long? = null
        var activePkg: String? = null
        var sfMs = 0L
        var genMs = 0L

        fun closeSession(endAt: Long) {
            val start = sessionStart ?: return
            val rawDur = (endAt - start).coerceAtLeast(0L)
            if (rawDur >= MIN_VALID_SESSION_MS) {
                val totalAppMs = sfMs + genMs
                val actualSf = if (totalAppMs > 0L) ((rawDur * (sfMs.toDouble() / totalAppMs)).toLong()).coerceIn(0L, rawDur) else sfMs.coerceAtMost(rawDur)
                val actualGen = (rawDur - actualSf).coerceAtLeast(0L)
                val weighted = actualSf + (actualGen / 3L)
                val date = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
                result.add(PhoneSessionRecord(date, start, endAt, rawDur, actualSf, actualGen, weighted))
            }
            sessionStart = null
            lastActive = null
            activePkg = null
            sfMs = 0L
            genMs = 0L
        }

        for (e in sorted) {
            val t = e.timeStamp
            when (e.eventType) {
                com.example.itv.domain.records.ObservationKind.SCREEN_INTERACTIVE,
                com.example.itv.domain.records.ObservationKind.KEYGUARD_HIDDEN -> {
                    if (sessionStart == null) {
                        sessionStart = t
                        lastActive = t
                    }
                }
                com.example.itv.domain.records.ObservationKind.SCREEN_NON_INTERACTIVE,
                com.example.itv.domain.records.ObservationKind.KEYGUARD_SHOWN,
                com.example.itv.domain.records.ObservationKind.DEVICE_SHUTDOWN -> {
                    if (sessionStart != null) {
                        val prev = lastActive ?: sessionStart!!
                        val dt = (t - prev).coerceAtLeast(0L)
                        if (dt in 1L..60_000L && activePkg != null) {
                            if (activePkg == PKG_INSTAGRAM || activePkg == PKG_YOUTUBE) sfMs += dt else genMs += dt
                        }
                        closeSession(t)
                    }
                }
                com.example.itv.domain.records.ObservationKind.ACTIVITY_RESUMED -> {
                    if (sessionStart == null) {
                        sessionStart = t
                        lastActive = t
                    } else {
                        val prev = lastActive ?: sessionStart!!
                        val dt = (t - prev).coerceAtLeast(0L)
                        if (dt in 1L..60_000L && activePkg != null) {
                            if (activePkg == PKG_INSTAGRAM || activePkg == PKG_YOUTUBE) sfMs += dt else genMs += dt
                        }
                        lastActive = t
                    }
                    activePkg = e.packageName
                }
                com.example.itv.domain.records.ObservationKind.ACTIVITY_PAUSED -> {
                    if (sessionStart != null) {
                        val prev = lastActive ?: sessionStart!!
                        val dt = (t - prev).coerceAtLeast(0L)
                        if (dt in 1L..60_000L && activePkg != null) {
                            if (activePkg == PKG_INSTAGRAM || activePkg == PKG_YOUTUBE) sfMs += dt else genMs += dt
                        }
                        lastActive = t
                    }
                }
                else -> {}
            }
            val activeTime = lastActive
            if (sessionStart != null && activeTime != null && (t - activeTime) >= 30_000L) {
                closeSession(activeTime)
            }
        }
        val activeTime = lastActive
        if (sessionStart != null && activeTime != null) {
            closeSession(activeTime)
        }
        return result
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
    ): Long = calculateRepresentativeSessionLengths(sessions.map { it.durationMs }, fallbackMs, minDurationMs)

    fun calculateRepresentativeSessionLengths(
        durations: List<Long>,
        fallbackMs: Long = 0L,
        minDurationMs: Long = MIN_REPRESENTATIVE_SESSION_MS
    ): Long {
        val eligible = durations.filter { it >= minDurationMs }
        val target = when {
            eligible.isNotEmpty() -> eligible
            durations.isNotEmpty() -> durations
            else -> return fallbackMs
        }
        val sorted = target.sorted()
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
        val shortformAvgSessionMs = calculateRepresentativeSessionMs(basisSessions)

        if (cachedPhone30dRawAvgSessionMs == 0L) {
            val appCtx = context.applicationContext
            val zone = ZoneId.systemDefault()
            val nowWallMs = System.currentTimeMillis()
            val start30dWallMs = LocalDate.now().minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
            val events = runCatching {
                com.example.itv.infrastructure.records.AndroidRecordSource(appCtx).events(start30dWallMs, nowWallMs)
            }.getOrDefault(emptyList())
            val parsed = parsePhoneSessions(events)
            if (parsed.isNotEmpty()) {
                val p30 = parsed.filter { !it.date.isBefore(LocalDate.now().minusDays(29)) && !it.date.isAfter(LocalDate.now()) }
                val repP30 = p30.filter { it.durationMs >= MIN_PHONE_REPRESENTATIVE_SESSION_MS }.ifEmpty { p30 }
                if (p30.isNotEmpty()) {
                    cachedPhone30dRawAvgSessionMs = calculateRepresentativeSessionLengths(repP30.map { it.durationMs }, fallbackMs = 0L, minDurationMs = MIN_PHONE_REPRESENTATIVE_SESSION_MS)
                    cachedPhone30dWeightedAvgSessionMs = calculateRepresentativeSessionLengths(repP30.map { it.weightedDurationMs }, fallbackMs = 0L, minDurationMs = MIN_PHONE_REPRESENTATIVE_SESSION_MS)
                }
            }
        }

        val phoneRawAvgMs = cachedPhone30dRawAvgSessionMs
        val phoneWeightedAvgMs = cachedPhone30dWeightedAvgSessionMs
        val phoneBaselineAvgMs = if (phoneRawAvgMs >= 30_000L) phoneRawAvgMs else (if (phoneWeightedAvgMs >= 30_000L) phoneWeightedAvgMs else shortformAvgSessionMs)
        val avgSessionMs = phoneBaselineAvgMs

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

        val bimodal = cachedBimodalClusters
        val workdayAvgSessionMs = bimodal?.takeIf { it.isConfident }?.workdayAvgSessionMs
        val restdayAvgSessionMs = bimodal?.takeIf { it.isConfident }?.restdayAvgSessionMs
        val splitThresholdMs = bimodal?.splitThresholdMs
        val isConfident = bimodal?.isConfident ?: false

        val phoneBimodal = cachedPhoneBimodalClusters
        val phoneWorkdayAvgDailyMs = phoneBimodal?.workdayAvgDailyMs
        val phoneRestdayAvgDailyMs = phoneBimodal?.restdayAvgDailyMs
        val phoneSplitThresholdMs = phoneBimodal?.splitThresholdMs
        val isPhoneConfident = phoneBimodal?.isConfident ?: false

        val todayDate = LocalDate.now()
        val hybridDecision = com.example.itv.domain.records.HybridDayTypeClassifier.classify(
            date = todayDate,
            phoneTotalTodayMs = effectivePhoneMs,
            shortformTodayMs = todayMs,
            phoneCluster = phoneBimodal,
            shortformCluster = bimodal,
            globalAvgSessionMs = avgSessionMs,
            globalAvgDailyMs = avg7dMs
        )
        val dynamicSessionBaselineMs = if (phoneBaselineAvgMs >= 30_000L) {
            phoneBaselineAvgMs
        } else {
            hybridDecision.effectiveAvgSessionMs.takeIf { it >= 30_000L } ?: avgSessionMs
        }
        val dynamicAvgSessionMinutes = if (dynamicSessionBaselineMs >= 30_000L) round2(dynamicSessionBaselineMs / 60_000.0) else null
        val dynamicSessionVsAvgRatio = if (dynamicSessionBaselineMs >= 30_000L && effectiveCurrentSessionMs > 0L) {
            round2(effectiveCurrentSessionMs.toDouble() / dynamicSessionBaselineMs.toDouble())
        } else sessionVsAvgRatio

        return QuickPersonalMetrics(
            avgSessionMs = dynamicSessionBaselineMs,
            avgSessionMinutes = dynamicAvgSessionMinutes,
            sessionVsAvgRatio = dynamicSessionVsAvgRatio,
            todayDurationMs = todayMs,
            todayShortformMinutes = todayShortformMinutes,
            avg7dDailyMs = avg7dMs,
            avg7dDailyMinutes = avg7dDailyMinutes,
            todayVs7dRatio = todayVs7dRatio,
            personalAvgSpm = personalAvgSpm,
            shortformShareOfPhoneRatio = shortformShareRatio,
            workdayAvgSessionMs = workdayAvgSessionMs,
            restdayAvgSessionMs = restdayAvgSessionMs,
            daySplitThresholdMs = splitThresholdMs,
            isBimodalConfident = isConfident,
            phoneWorkdayAvgDailyMs = phoneWorkdayAvgDailyMs,
            phoneRestdayAvgDailyMs = phoneRestdayAvgDailyMs,
            phoneSplitThresholdMs = phoneSplitThresholdMs,
            isPhoneBimodalConfident = isPhoneConfident,
            estimatedDayType = hybridDecision.estimatedType,
            effectiveAvgSessionMs = dynamicSessionBaselineMs,
            dayTypeReason = hybridDecision.reason
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
        val cutoffDate = LocalDate.now().minusMonths(MAX_RETENTION_MONTHS).toString()
        val beforeCount = sessions.size
        sessions.removeAll { it.date < cutoffDate }
        while (sessions.size > MAX_STORED_SESSIONS) {
            sessions.removeAt(0)
        }
        initialized = true
        if (sessions.size != beforeCount) {
            persist()
        }
    }

    @Synchronized
    fun getStoredSessionsSnapshot(context: Context): List<SessionRecord> {
        ensureInitialized(context)
        return sessions.toList()
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

    @Synchronized
    fun deleteAllAndReset(context: Context) {
        val appCtx = context.applicationContext
        sessions.clear()
        runs.values.forEach { it.reset() }
        runCatching { file?.delete() }
        val base = File(appCtx.noBackupFilesDir, "shortform_personal_stats_v1.json")
        runCatching { base.delete() }
        runCatching { File(base.path + ".bak").delete() }
        file = null
        initialized = false
    }

    @Synchronized
    fun restoreOrMergeFromCloud(
        context: Context,
        cloudSessions: List<SessionRecord>,
        mergeWithLocal: Boolean,
        today: LocalDate = LocalDate.now()
    ) {
        ensureInitialized(context)
        val cutoffDate = today.minusMonths(MAX_RETENTION_MONTHS).toString()
        val byId = LinkedHashMap<String, SessionRecord>()
        if (mergeWithLocal) {
            sessions.filter { it.date >= cutoffDate }.forEach { byId[it.id] = it }
        }
        cloudSessions.filter { it.date >= cutoffDate && it.id.isNotBlank() }.forEach { remote ->
            val existing = byId[remote.id]
            byId[remote.id] = if (existing == null || remote.durationMs >= existing.durationMs) remote else existing
        }
        val merged = byId.values
            .sortedBy { it.startWallMs }
            .let { list -> if (list.size > MAX_STORED_SESSIONS) list.takeLast(MAX_STORED_SESSIONS) else list }
        sessions.clear()
        sessions.addAll(merged)
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
        val cutoffDate = LocalDate.now().minusMonths(MAX_RETENTION_MONTHS).toString()
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

        // 30-day device-level phone total usage bimodal clustering
        val phoneDailyMap = linkedMapOf<LocalDate, Long>()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
        val zone = ZoneId.systemDefault()
        val start30dWall = start30d.atStartOfDay(zone).toInstant().toEpochMilli()
        val allDailyStats = runCatching {
            usm?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start30dWall, nowWall)
        }.getOrNull()
        allDailyStats?.forEach { stat ->
            if (stat.totalTimeInForeground > 0L) {
                val d = Instant.ofEpochMilli(stat.firstTimeStamp).atZone(zone).toLocalDate()
                if (!d.isBefore(start30d) && !d.isAfter(todayDate)) {
                    phoneDailyMap[d] = (phoneDailyMap[d] ?: 0L) + stat.totalTimeInForeground
                }
            }
        }
        periodUsageReport?.dailyTotals?.forEach { (date, minutes) ->
            val ms = (minutes * 60_000.0).toLong()
            if (ms > 0L && !date.isBefore(start30d) && !date.isAfter(todayDate)) {
                phoneDailyMap[date] = maxOf(phoneDailyMap[date] ?: 0L, ms)
            }
        }
        if (totalPhoneTodayMs > 0L) {
            phoneDailyMap[todayDate] = maxOf(phoneDailyMap[todayDate] ?: 0L, totalPhoneTodayMs)
        }
        val phoneClusterProfiles = phoneDailyMap.filter { it.value >= 60_000L }.map { (date, dailyMs) ->
            com.example.itv.domain.records.DayUsageProfile(
                date = date,
                totalDurationMs = dailyMs
            )
        }
        val phoneClusterResult = if (phoneClusterProfiles.size >= 2) {
            com.example.itv.domain.records.DayTypeUsageClusterer.cluster(phoneClusterProfiles)
        } else {
            cachedPhoneBimodalClusters ?: com.example.itv.domain.records.DayTypeUsageClusterer.cluster(phoneClusterProfiles)
        }
        cachedPhoneBimodalClusters = phoneClusterResult

        val phoneEvents = runCatching {
            com.example.itv.infrastructure.records.AndroidRecordSource(appCtx).events(start30dWall, nowWall)
        }.getOrDefault(emptyList())
        val parsedPhoneSessions = parsePhoneSessions(phoneEvents)
        val phone30d = parsedPhoneSessions.filter { !it.date.isBefore(start30d) && !it.date.isAfter(todayDate) }
        val phone7d = parsedPhoneSessions.filter { !it.date.isBefore(start7d) && !it.date.isAfter(todayDate) }
        val repPhone30d = phone30d.filter { it.durationMs >= MIN_PHONE_REPRESENTATIVE_SESSION_MS }.ifEmpty { phone30d }
        val repPhone7d = phone7d.filter { it.durationMs >= MIN_PHONE_REPRESENTATIVE_SESSION_MS }.ifEmpty { phone7d }

        val phoneAvg7dSessionMs = calculateRepresentativeSessionLengths(repPhone7d.map { it.durationMs }, fallbackMs = 0L, minDurationMs = MIN_PHONE_REPRESENTATIVE_SESSION_MS)
        val phoneAvg7dWeightedSessionMs = calculateRepresentativeSessionLengths(repPhone7d.map { it.weightedDurationMs }, fallbackMs = 0L, minDurationMs = MIN_PHONE_REPRESENTATIVE_SESSION_MS)
        val phoneMax7dSessionMs = phone7d.maxOfOrNull { it.durationMs } ?: 0L
        val phoneSessionCount7d = repPhone7d.size
        val phoneAvg7dShortformRatio = if (repPhone7d.isNotEmpty()) repPhone7d.map { it.shortformRatio }.average() else null

        val phoneAvg30dSessionMs = calculateRepresentativeSessionLengths(repPhone30d.map { it.durationMs }, fallbackMs = 0L, minDurationMs = MIN_PHONE_REPRESENTATIVE_SESSION_MS)
        val phoneAvg30dWeightedSessionMs = calculateRepresentativeSessionLengths(repPhone30d.map { it.weightedDurationMs }, fallbackMs = 0L, minDurationMs = MIN_PHONE_REPRESENTATIVE_SESSION_MS)
        val phoneMax30dSessionMs = phone30d.maxOfOrNull { it.durationMs } ?: 0L
        val phoneSessionCount30d = repPhone30d.size
        val phoneAvg30dShortformRatio = if (repPhone30d.isNotEmpty()) repPhone30d.map { it.shortformRatio }.average() else null

        if (phoneAvg30dSessionMs > 0L) {
            cachedPhone30dRawAvgSessionMs = phoneAvg30dSessionMs
        }
        if (phoneAvg30dWeightedSessionMs > 0L) {
            cachedPhone30dWeightedAvgSessionMs = phoneAvg30dWeightedSessionMs
        }

        val todayPhoneSessions = parsedPhoneSessions.filter { it.date == todayDate }
        val repTodayPhoneSessions = todayPhoneSessions.filter { it.durationMs >= MIN_PHONE_REPRESENTATIVE_SESSION_MS }.ifEmpty { todayPhoneSessions }
        val latestTodayPhone = todayPhoneSessions.maxByOrNull { it.endWallMs }
        val phoneCurrentSessionMs = latestTodayPhone?.durationMs ?: 0L
        val phoneCurrentWeightedSessionMs = latestTodayPhone?.weightedDurationMs ?: 0L
        val phoneCurrentShortformRatio = latestTodayPhone?.shortformRatio

        val phoneTodayMs = maxOf(phoneDailyMap[todayDate] ?: 0L, totalPhoneTodayMs)
        val phoneTodayAvgSessionMs = if (repTodayPhoneSessions.isNotEmpty()) {
            repTodayPhoneSessions.map { it.durationMs }.average().toLong()
        } else {
            phoneCurrentSessionMs
        }
        val phoneTodayAvgWeightedSessionMs = if (repTodayPhoneSessions.isNotEmpty()) {
            repTodayPhoneSessions.map { it.weightedDurationMs }.average().toLong()
        } else {
            phoneCurrentWeightedSessionMs
        }
        val todayShortformTotalMs = combinedDaily[todayDate]?.durationMs ?: 0L
        val phoneTodayShortformRatio = when {
            phoneTodayMs > 0L && todayShortformTotalMs > 0L ->
                (todayShortformTotalMs.toDouble() / phoneTodayMs.toDouble()).coerceIn(0.0, 1.0)
            repTodayPhoneSessions.isNotEmpty() && repTodayPhoneSessions.sumOf { it.durationMs } > 0L ->
                (repTodayPhoneSessions.sumOf { it.shortformMs }.toDouble() / repTodayPhoneSessions.sumOf { it.durationMs }.toDouble()).coerceIn(0.0, 1.0)
            else -> phoneCurrentShortformRatio
        }
        val phoneTodaySessionsCount = repTodayPhoneSessions.size.coerceAtLeast(if (phoneTodayMs > 0L) 1 else 0)

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

            val dayProfiles = days30.map { dayAgg ->
                val dateStr = dayAgg.date.toString()
                val daySessions = sessions30d.filter { it.date == dateStr }.map { it.durationMs }
                com.example.itv.domain.records.DayUsageProfile(
                    date = dayAgg.date,
                    totalDurationMs = dayAgg.durationMs,
                    sessions = daySessions
                )
            }
            val bimodalCluster = com.example.itv.domain.records.DayTypeUsageClusterer.cluster(dayProfiles)

            val hybridDecision = com.example.itv.domain.records.HybridDayTypeClassifier.classify(
                date = todayDate,
                phoneTotalTodayMs = effectivePhoneMs,
                shortformTodayMs = todayMs,
                phoneCluster = phoneClusterResult,
                shortformCluster = bimodalCluster,
                globalAvgSessionMs = avgSessionMs,
                globalAvgDailyMs = avg7dDailyMs
            )

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
                phoneCurrentSessionMs = phoneCurrentSessionMs,
                phoneCurrentWeightedSessionMs = phoneCurrentWeightedSessionMs,
                phoneCurrentShortformRatio = phoneCurrentShortformRatio,
                phoneTodayAvgSessionMs = phoneTodayAvgSessionMs,
                phoneTodayAvgWeightedSessionMs = phoneTodayAvgWeightedSessionMs,
                phoneTodayShortformRatio = phoneTodayShortformRatio,
                phoneTodaySessionsCount = phoneTodaySessionsCount,
                phoneAvgSessionMs = phoneAvg30dSessionMs,
                phoneAvgWeightedSessionMs = phoneAvg30dWeightedSessionMs,
                phoneMaxSessionMs = phoneMax30dSessionMs,
                phoneTotalSessions = phoneSessionCount30d,
                phoneAvg7dSessionMs = phoneAvg7dSessionMs,
                phoneAvg7dWeightedSessionMs = phoneAvg7dWeightedSessionMs,
                phoneMax7dSessionMs = phoneMax7dSessionMs,
                phoneSessionCount7d = phoneSessionCount7d,
                phoneAvg7dShortformRatio = phoneAvg7dShortformRatio,
                phoneAvg30dSessionMs = phoneAvg30dSessionMs,
                phoneAvg30dWeightedSessionMs = phoneAvg30dWeightedSessionMs,
                phoneMax30dSessionMs = phoneMax30dSessionMs,
                phoneSessionCount30d = phoneSessionCount30d,
                phoneAvg30dShortformRatio = phoneAvg30dShortformRatio,
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
                periodShareOfPhoneRatio = periodShareOfPhoneRatio,
                workdayAvgDailyMs = bimodalCluster.workdayAvgDailyMs,
                workdayAvgSessionMs = bimodalCluster.workdayAvgSessionMs,
                restdayAvgDailyMs = bimodalCluster.restdayAvgDailyMs,
                restdayAvgSessionMs = bimodalCluster.restdayAvgSessionMs,
                daySplitThresholdMs = bimodalCluster.splitThresholdMs,
                isBimodalConfident = bimodalCluster.isConfident,
                bimodalRatio = bimodalCluster.clusterRatio,
                estimatedWorkdayCount = bimodalCluster.workdayDays.size,
                estimatedRestdayCount = bimodalCluster.restdayDays.size,
                phoneWorkdayAvgDailyMs = phoneClusterResult.workdayAvgDailyMs,
                phoneRestdayAvgDailyMs = phoneClusterResult.restdayAvgDailyMs,
                phoneSplitThresholdMs = phoneClusterResult.splitThresholdMs,
                isPhoneBimodalConfident = phoneClusterResult.isConfident,
                phoneBimodalRatio = phoneClusterResult.clusterRatio,
                phoneEstimatedWorkdayCount = phoneClusterResult.workdayDays.size,
                phoneEstimatedRestdayCount = phoneClusterResult.restdayDays.size,
                todayEstimatedDayTypeByPhone = if (effectivePhoneMs > 0L) phoneClusterResult.classify(effectivePhoneMs) else null,
                hybridEstimatedDayType = hybridDecision.estimatedType,
                hybridDayTypeReason = hybridDecision.reason,
                effectiveSessionMsForToday = hybridDecision.effectiveAvgSessionMs
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

        val phoneDailyAggMap = phoneDailyMap.mapValues { (d, ms) ->
            DailyAgg(d, ms, swipes = 0L, observed = ms > 0L)
        }
        val rawPhoneDays7 = phoneDailyAggMap.values.filter { !it.date.isBefore(start7d) && !it.date.isAfter(todayDate) }
        val phoneDays7 = rawPhoneDays7.filter { it.durationMs >= MIN_VALID_SESSION_MS }.ifEmpty { rawPhoneDays7.filter { it.durationMs > 0L } }
        val phoneObservedDays7d = phoneDays7.size.coerceAtLeast(1)
        val phoneTotal7dMs = phoneDays7.sumOf { it.durationMs }
        val phoneAvg7dDailyMs = if (phoneObservedDays7d > 0) phoneTotal7dMs / phoneObservedDays7d else 0L
        val phoneTodayVs7dRatio = if (phoneAvg7dDailyMs > 0L && phoneTodayMs > 0L) phoneTodayMs.toDouble() / phoneAvg7dDailyMs.toDouble() else null

        val rawPhoneDays30 = phoneDailyAggMap.values.filter { !it.date.isBefore(start30d) && !it.date.isAfter(todayDate) }
        val phoneDays30 = rawPhoneDays30.filter { it.durationMs >= MIN_VALID_SESSION_MS }.ifEmpty { rawPhoneDays30.filter { it.durationMs > 0L } }
        val phoneObservedDays30d = phoneDays30.size.coerceAtLeast(1)
        val phoneTotal30dMs = phoneDays30.sumOf { it.durationMs }
        val phoneAvg30dDailyMs = if (phoneObservedDays30d > 0) phoneTotal30dMs / phoneObservedDays30d else 0L
        val phoneTodayVs30dRatio = if (phoneAvg30dDailyMs > 0L && phoneTodayMs > 0L) phoneTodayMs.toDouble() / phoneAvg30dDailyMs.toDouble() else null
        val phoneAvg7dVs30dRatio = if (phoneAvg30dDailyMs > 0L && phoneAvg7dDailyMs > 0L) phoneAvg7dDailyMs.toDouble() / phoneAvg30dDailyMs.toDouble() else null

        val phoneEffectiveCurrentSessionMs = if (phoneCurrentSessionMs > 0L) phoneCurrentSessionMs else phoneTodayMs
        val phoneEffectiveAvgSessionMs = if (phoneAvg30dSessionMs > 0L) phoneAvg30dSessionMs else phoneAvg30dDailyMs
        val phoneEffectiveMaxSessionMs = maxOf(phoneMax30dSessionMs, phoneEffectiveCurrentSessionMs)
        val phoneCurrentVsAvgSessionRatio = if (phoneEffectiveAvgSessionMs > 0L && phoneEffectiveCurrentSessionMs > 0L) {
            phoneEffectiveCurrentSessionMs.toDouble() / phoneEffectiveAvgSessionMs.toDouble()
        } else null
        val phoneSession7dVs30dRatio = if (phoneAvg30dSessionMs > 0L && phoneAvg7dSessionMs > 0L) {
            phoneAvg7dSessionMs.toDouble() / phoneAvg30dSessionMs.toDouble()
        } else null

        val combinedMetrics = buildMetrics(
            filter = TargetFilter.COMBINED,
            dailyMap = combinedDaily,
            matchingSessions = snapshotSessions,
            activeSession = combinedActive,
            parentAppMs = igParentTodayMs + ytParentTodayMs,
            parentAppPeriodTotalMs = igParentPeriodTotalMs + ytParentPeriodTotalMs
        )

        val phoneTotalMetrics = PersonalizedTargetMetrics(
            filter = TargetFilter.PHONE_TOTAL,
            todayDurationMs = phoneTodayMs,
            avg7dDailyMs = phoneAvg7dDailyMs,
            observedDays7d = phoneObservedDays7d,
            total7dDurationMs = phoneTotal7dMs,
            todayVs7dRatio = phoneTodayVs7dRatio,
            avg30dDailyMs = phoneAvg30dDailyMs,
            observedDays30d = phoneObservedDays30d,
            total30dDurationMs = phoneTotal30dMs,
            todayVs30dRatio = phoneTodayVs30dRatio,
            avg7dVs30dRatio = phoneAvg7dVs30dRatio,
            currentSessionMs = phoneEffectiveCurrentSessionMs,
            currentSessionLive = true,
            currentSessionSwipes = 0,
            avgSessionMs = phoneEffectiveAvgSessionMs,
            maxSessionMs = phoneEffectiveMaxSessionMs,
            totalRecordedSessions = phoneSessionCount30d,
            todaySessionCount = phoneTodaySessionsCount,
            avg7dSessionMs = if (phoneAvg7dSessionMs > 0L) phoneAvg7dSessionMs else phoneAvg7dDailyMs,
            max7dSessionMs = phoneMax7dSessionMs,
            sessionCount7d = phoneSessionCount7d,
            avg7dSessionCount = if (phoneObservedDays7d > 0) phoneSessionCount7d.toDouble() / phoneObservedDays7d else 0.0,
            avg30dSessionMs = phoneEffectiveAvgSessionMs,
            max30dSessionMs = phoneEffectiveMaxSessionMs,
            sessionCount30d = phoneSessionCount30d,
            avg30dSessionCount = if (phoneObservedDays30d > 0) phoneSessionCount30d.toDouble() / phoneObservedDays30d else 0.0,
            currentVsAvgSessionRatio = phoneCurrentVsAvgSessionRatio,
            session7dVs30dRatio = phoneSession7dVs30dRatio,
            phoneCurrentSessionMs = phoneCurrentSessionMs,
            phoneCurrentWeightedSessionMs = phoneCurrentWeightedSessionMs,
            phoneCurrentShortformRatio = phoneCurrentShortformRatio,
            phoneTodayAvgSessionMs = phoneTodayAvgSessionMs,
            phoneTodayAvgWeightedSessionMs = phoneTodayAvgWeightedSessionMs,
            phoneTodayShortformRatio = phoneTodayShortformRatio,
            phoneTodaySessionsCount = phoneTodaySessionsCount,
            phoneAvgSessionMs = phoneAvg30dSessionMs,
            phoneAvgWeightedSessionMs = phoneAvg30dWeightedSessionMs,
            phoneMaxSessionMs = phoneMax30dSessionMs,
            phoneTotalSessions = phoneSessionCount30d,
            phoneAvg7dSessionMs = phoneAvg7dSessionMs,
            phoneAvg7dWeightedSessionMs = phoneAvg7dWeightedSessionMs,
            phoneMax7dSessionMs = phoneMax7dSessionMs,
            phoneSessionCount7d = phoneSessionCount7d,
            phoneAvg7dShortformRatio = phoneAvg7dShortformRatio,
            phoneAvg30dSessionMs = phoneAvg30dSessionMs,
            phoneAvg30dWeightedSessionMs = phoneAvg30dWeightedSessionMs,
            phoneMax30dSessionMs = phoneMax30dSessionMs,
            phoneSessionCount30d = phoneSessionCount30d,
            phoneAvg30dShortformRatio = phoneAvg30dShortformRatio,
            todaySwipes = 0L,
            total7dSwipes = 0L,
            avg7dDailySwipes = 0.0,
            todayVs7dSwipesRatio = null,
            avg7dSwipesPerSession = 0.0,
            avg7dSpm = 0.0,
            total30dSwipes = 0L,
            avg30dDailySwipes = 0.0,
            avg30dSwipesPerSession = 0.0,
            avgSwipesPerSession = 0.0,
            todayAvgSpm = 0.0,
            personalAvgSpm = 0.0,
            parentAppTodayMs = phoneTodayMs,
            shortformShareOfAppRatio = null,
            totalPhoneTodayMs = phoneTodayMs,
            shortformShareOfPhoneRatio = phoneTodayShortformRatio ?: phoneCurrentShortformRatio,
            periodShareOfAppRatio = null,
            periodShareOfPhoneRatio = phoneAvg30dShortformRatio,
            workdayAvgDailyMs = combinedMetrics.workdayAvgDailyMs,
            workdayAvgSessionMs = combinedMetrics.workdayAvgSessionMs,
            restdayAvgDailyMs = combinedMetrics.restdayAvgDailyMs,
            restdayAvgSessionMs = combinedMetrics.restdayAvgSessionMs,
            daySplitThresholdMs = combinedMetrics.daySplitThresholdMs,
            isBimodalConfident = combinedMetrics.isBimodalConfident,
            bimodalRatio = combinedMetrics.bimodalRatio,
            estimatedWorkdayCount = combinedMetrics.estimatedWorkdayCount,
            estimatedRestdayCount = combinedMetrics.estimatedRestdayCount,
            phoneWorkdayAvgDailyMs = phoneClusterResult.workdayAvgDailyMs,
            phoneRestdayAvgDailyMs = phoneClusterResult.restdayAvgDailyMs,
            phoneSplitThresholdMs = phoneClusterResult.splitThresholdMs,
            isPhoneBimodalConfident = phoneClusterResult.isConfident,
            phoneBimodalRatio = phoneClusterResult.clusterRatio,
            phoneEstimatedWorkdayCount = phoneClusterResult.workdayDays.size,
            phoneEstimatedRestdayCount = phoneClusterResult.restdayDays.size,
            todayEstimatedDayTypeByPhone = if (phoneTodayMs > 0L) phoneClusterResult.classify(phoneTodayMs) else null,
            hybridEstimatedDayType = combinedMetrics.hybridEstimatedDayType,
            hybridDayTypeReason = combinedMetrics.hybridDayTypeReason,
            effectiveSessionMsForToday = combinedMetrics.effectiveSessionMsForToday
        )

        return PersonalizedSummary(
            combined = combinedMetrics,
            instagram = igMetrics,
            youtube = ytMetrics,
            phoneTotal = phoneTotalMetrics
        )
    }
}
