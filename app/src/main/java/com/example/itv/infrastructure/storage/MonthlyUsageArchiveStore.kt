package com.example.itv.infrastructure.storage

import android.content.Context
import android.util.AtomicFile
import com.example.itv.data.local.ItvDatabase
import com.example.itv.domain.records.AppHourUsage
import com.example.itv.domain.records.NightReport
import com.example.itv.domain.records.Report
import com.example.itv.infrastructure.records.AndroidRecordSource
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

internal data class ArchivedAppUsage(
    val packageName: String,
    val label: String,
    val minutes: Double
)

internal data class DailyArchiveRecord(
    val date: String, // yyyy-MM-dd
    val totalPhoneMs: Long,
    val shortformMs: Long,
    val shortformSwipes: Long = 0L,
    // Non-use / sleep details for the night waking on `date`
    val bedtimeMinute: Int? = null,
    val wakeupMinute: Int? = null,
    val sleepStartWallMs: Long? = null,
    val sleepEndWallMs: Long? = null,
    val sleepDurationMinutes: Double? = null,
    val briefWakeCount: Int = 0,
    val sleepConfirmed: Boolean = false,
    // Pre-sleep usage for `date`'s night (`date` -> `date + 1`)
    val preSleepUsageMinutes: Double? = null,
    val preSleepShortformMinutes: Double? = null,
    // Phone session stats for `date`
    val phoneSessionCount: Int = 0,
    val phoneTotalSessionMs: Long = 0L,
    val phoneAvgSessionMs: Long? = null,
    val phoneMaxSessionMs: Long? = null,
    // Shortform session stats for `date`
    val shortformSessionCount: Int = 0,
    val shortformAvgSessionMs: Long? = null,
    val shortformMaxSessionMs: Long? = null,
    val shortformSpm: Double? = null,
    // 24-hour phone usage distribution (minutes per hour) & top apps summary
    val hourlyPhoneMinutes: List<Double> = emptyList(),
    val topApps: List<ArchivedAppUsage> = emptyList(),
    val dayType: String = "WORKDAY",
    val dayTypeReason: String? = null
)

internal data class MonthlyArchiveSummary(
    val yearMonth: String, // yyyy-MM
    val totalPhoneMs: Long,
    val phoneDailyAvgMs: Long,
    val activeDays: Int,
    val shortformTotalMs: Long,
    val shortformDailyAvgMs: Long,
    val shortformSessionAvgMs: Long,
    val workdayPhoneAvgMs: Long,
    val restdayPhoneAvgMs: Long,
    val workdayShortformAvgMs: Long,
    val restdayShortformAvgMs: Long,
    val workdayDaysCount: Int,
    val restdayDaysCount: Int,
    val avgBedtimeMinute: Int? = null,
    val avgWakeupMinute: Int? = null,
    val dailyRecords: List<DailyArchiveRecord> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val displayBedtime: String? get() = avgBedtimeMinute?.let { min ->
        val h = ((min / 60) % 24)
        val m = min % 60
        String.format(Locale.KOREA, "%02d:%02d", h, m)
    }
    val displayWakeup: String? get() = avgWakeupMinute?.let { min ->
        val h = ((min / 60) % 24)
        val m = min % 60
        String.format(Locale.KOREA, "%02d:%02d", h, m)
    }
}

internal data class ParsedArchiveBundle(
    val dailyRecords: Map<String, DailyArchiveRecord>,
    val monthlySummaries: Map<String, MonthlyArchiveSummary>
)

/**
 * Manages long-term (up to 6 months) self-measured daily and monthly usage archives on-device:
 * - Total phone usage, 24h distribution, top apps
 * - Phone sessions (count, total, avg, max)
 * - Shortform usage, swipes, sessions (count, avg, max, SPM)
 * - Non-use / sleep details (start/end wall ms, bedtime/wakeup minute, duration, brief wakes, confirmed)
 * - Pre-sleep phone & shortform usage
 * Automatically retains up to 6 months independently of Android OS UsageEvents pruning,
 * and supports complete local wipe on account deletion.
 */
internal object MonthlyUsageArchiveStore {

    internal const val FILE_NAME = "monthly_usage_archive_v1.json"
    internal const val MAX_RETENTION_MONTHS = 6L
    private const val MIN_PHONE_SESSION_MS = 120_000L
    private const val MAX_TOP_APPS_PER_DAY = 15
    private val mutex = Mutex()

    @Volatile
    private var cachedSummaries: Map<String, MonthlyArchiveSummary> = emptyMap()
    @Volatile
    private var cachedDailyRecords: Map<String, DailyArchiveRecord> = emptyMap()
    private var initialized = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (initialized) return
        val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
        if (!file.baseFile.exists()) {
            initialized = true
            return
        }
        runCatching {
            val text = file.openRead().bufferedReader().use { it.readText() }
            val parsed = parseArchiveJson(text)
            cachedDailyRecords = parsed.dailyRecords
            cachedSummaries = parsed.monthlySummaries
        }
        initialized = true
    }

    /**
     * Returns a list of past month keys (e.g. ["2026-10", "2026-09", ...]) for the past 1 to 6 months.
     */
    fun getAvailablePastMonthKeys(today: LocalDate = LocalDate.now()): List<String> {
        val currentMonth = YearMonth.from(today)
        return (1..MAX_RETENTION_MONTHS).map { i ->
            currentMonth.minusMonths(i).toString()
        }
    }

    /**
     * Retrieves the monthly summary for a given month ("yyyy-MM"), or null if no data is archived.
     */
    fun getMonthlySummary(context: Context, yearMonth: String): MonthlyArchiveSummary? {
        ensureLoaded(context)
        return cachedSummaries[yearMonth]
    }

    /**
     * Retrieves all archived daily records as a date-keyed map ("yyyy-MM-dd" to DailyArchiveRecord).
     */
    fun getAllDailyRecords(context: Context): Map<String, DailyArchiveRecord> {
        ensureLoaded(context)
        return cachedDailyRecords
    }

    /**
     * Retrieves a single day's archive record ("yyyy-MM-dd"), or null if unavailable.
     */
    fun getDailyRecord(context: Context, date: String): DailyArchiveRecord? {
        ensureLoaded(context)
        return cachedDailyRecords[date]
    }

    /**
     * Reconstructs a day's Report from archived 24-hour distribution when OS UsageEvents are purged.
     */
    fun buildArchivedDayReport(context: Context, date: LocalDate): Report? {
        val rec = getDailyRecord(context, date.toString()) ?: return null
        val hourly = if (rec.hourlyPhoneMinutes.size == 24) {
            rec.hourlyPhoneMinutes
        } else {
            List(24) { 0.0 }
        }
        val hasHourly = hourly.any { it > 0.0 }
        if (!hasHourly && rec.topApps.isEmpty() && rec.totalPhoneMs <= 0L) return null
        val totalMin = maxOf(hourly.sum(), rec.totalPhoneMs / 60_000.0)
        return Report(
            values = hourly,
            total = totalMin,
            days = 1,
            dates = if (totalMin > 0.0) 1 else 0,
            instagramMinutes = 0.0,
            hourlyApps = List(24) { emptyList() },
            dailyTotals = if (totalMin > 0.0) mapOf(date to totalMin) else emptyMap()
        )
    }

    /**
     * Retrieves archived daily top apps for a date as [AppHourUsage] items.
     */
    fun getArchivedTopApps(context: Context, date: LocalDate): List<AppHourUsage> {
        val rec = getDailyRecord(context, date.toString()) ?: return emptyList()
        return rec.topApps.map { AppHourUsage(it.packageName, it.label, it.minutes) }
    }

    /**
     * Completely clears in-memory caches and deletes the on-device archive file (for account deletion).
     */
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appCtx = context.applicationContext
            cachedDailyRecords = emptyMap()
            cachedSummaries = emptyMap()
            initialized = false
            val base = File(appCtx.noBackupFilesDir, FILE_NAME)
            runCatching { AtomicFile(base).delete() }
            runCatching { base.delete() }
            runCatching { File(base.path + ".bak").delete() }
        }
    }

    /**
     * Restores or merges 6-month daily archive records retrieved from Cloud Firestore.
     * - [mergeWithLocal] = true (Guest -> Social linking): merges each date choosing the richer/higher record.
     * - [mergeWithLocal] = false (Fresh sign-in / new device): replaces local archive with cloud records.
     */
    suspend fun restoreOrMergeFromCloud(
        context: Context,
        cloudDailies: List<DailyArchiveRecord>,
        mergeWithLocal: Boolean,
        today: LocalDate = LocalDate.now()
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded(context)
            val appCtx = context.applicationContext
            val mergedMap = mergeDailyRecords(
                local = if (mergeWithLocal) cachedDailyRecords else emptyMap(),
                cloud = cloudDailies,
                today = today
            )
            commitUpdatedDailiesLocked(appCtx, mergedMap, today)
        }
    }

    internal fun mergeDailyRecords(
        local: Map<String, DailyArchiveRecord>,
        cloud: List<DailyArchiveRecord>,
        today: LocalDate = LocalDate.now()
    ): Map<String, DailyArchiveRecord> {
        val result = local.toMutableMap()
        cloud.forEach { remote ->
            if (remote.date.isBlank()) return@forEach
            val existing = result[remote.date]
            result[remote.date] = if (existing == null) {
                remote
            } else {
                val useConfirmedSleepFromRemote = remote.sleepConfirmed && !existing.sleepConfirmed
                val useConfirmedSleepFromLocal = existing.sleepConfirmed && !remote.sleepConfirmed
                existing.copy(
                    totalPhoneMs = maxOf(existing.totalPhoneMs, remote.totalPhoneMs),
                    shortformMs = maxOf(existing.shortformMs, remote.shortformMs),
                    shortformSwipes = maxOf(existing.shortformSwipes, remote.shortformSwipes),
                    bedtimeMinute = when {
                        useConfirmedSleepFromLocal -> existing.bedtimeMinute
                        useConfirmedSleepFromRemote -> remote.bedtimeMinute
                        else -> remote.bedtimeMinute ?: existing.bedtimeMinute
                    },
                    wakeupMinute = when {
                        useConfirmedSleepFromLocal -> existing.wakeupMinute
                        useConfirmedSleepFromRemote -> remote.wakeupMinute
                        else -> remote.wakeupMinute ?: existing.wakeupMinute
                    },
                    sleepStartWallMs = when {
                        useConfirmedSleepFromLocal -> existing.sleepStartWallMs
                        useConfirmedSleepFromRemote -> remote.sleepStartWallMs
                        else -> remote.sleepStartWallMs ?: existing.sleepStartWallMs
                    },
                    sleepEndWallMs = when {
                        useConfirmedSleepFromLocal -> existing.sleepEndWallMs
                        useConfirmedSleepFromRemote -> remote.sleepEndWallMs
                        else -> remote.sleepEndWallMs ?: existing.sleepEndWallMs
                    },
                    sleepDurationMinutes = when {
                        useConfirmedSleepFromLocal -> existing.sleepDurationMinutes
                        useConfirmedSleepFromRemote -> remote.sleepDurationMinutes
                        else -> remote.sleepDurationMinutes ?: existing.sleepDurationMinutes
                    },
                    briefWakeCount = maxOf(existing.briefWakeCount, remote.briefWakeCount),
                    sleepConfirmed = existing.sleepConfirmed || remote.sleepConfirmed,
                    preSleepUsageMinutes = existing.preSleepUsageMinutes ?: remote.preSleepUsageMinutes,
                    preSleepShortformMinutes = existing.preSleepShortformMinutes ?: remote.preSleepShortformMinutes,
                    phoneSessionCount = maxOf(existing.phoneSessionCount, remote.phoneSessionCount),
                    phoneTotalSessionMs = maxOf(existing.phoneTotalSessionMs, remote.phoneTotalSessionMs),
                    phoneAvgSessionMs = existing.phoneAvgSessionMs ?: remote.phoneAvgSessionMs,
                    phoneMaxSessionMs = maxOfNullable(existing.phoneMaxSessionMs, remote.phoneMaxSessionMs),
                    shortformSessionCount = maxOf(existing.shortformSessionCount, remote.shortformSessionCount),
                    shortformAvgSessionMs = existing.shortformAvgSessionMs ?: remote.shortformAvgSessionMs,
                    shortformMaxSessionMs = maxOfNullable(existing.shortformMaxSessionMs, remote.shortformMaxSessionMs),
                    shortformSpm = existing.shortformSpm ?: remote.shortformSpm,
                    hourlyPhoneMinutes = if (existing.hourlyPhoneMinutes.size == 24 && existing.hourlyPhoneMinutes.any { it > 0.0 }) {
                        existing.hourlyPhoneMinutes
                    } else {
                        remote.hourlyPhoneMinutes
                    },
                    topApps = existing.topApps.ifEmpty { remote.topApps },
                    dayType = existing.dayType.ifBlank { remote.dayType },
                    dayTypeReason = existing.dayTypeReason ?: remote.dayTypeReason
                )
            }
        }
        return pruneToSixMonths(result, today)
    }

    internal fun serializeDailyRecordsArrayJson(dailies: List<DailyArchiveRecord>): String {
        val wrapped = serializeArchiveJson(dailies, emptyList())
        return JSONObject(wrapped).optJSONArray("dailyRecords")?.toString() ?: "[]"
    }

    internal fun parseDailyRecordsArrayJson(arrayJson: String): List<DailyArchiveRecord> {
        if (arrayJson.isBlank()) return emptyList()
        val wrapped = JSONObject().put("dailyRecords", JSONArray(arrayJson)).toString()
        return parseArchiveJson(wrapped).dailyRecords.values.sortedBy { it.date }
    }

    /**
     * Persists sleep-cycle-accurate resolved facts from [CalendarHealthLoader] into the 6-month archive
     * so session averages, pre-sleep metrics, and sleep durations survive OS event pruning.
     */
    suspend fun upsertResolvedDayFacts(
        context: Context,
        factsMap: Map<LocalDate, DayResolvedFacts>,
        today: LocalDate = LocalDate.now()
    ) = withContext(Dispatchers.IO) {
        if (factsMap.isEmpty()) return@withContext
        mutex.withLock {
            ensureLoaded(context)
            val appCtx = context.applicationContext
            val cutoffDate = today.minusMonths(MAX_RETENTION_MONTHS)
            val updatedDailies = cachedDailyRecords.toMutableMap()
            var changed = false

            factsMap.forEach { (date, facts) ->
                if (date.isBefore(cutoffDate) || date.isAfter(today)) return@forEach
                val dateKey = date.toString()
                val existing = updatedDailies[dateKey]
                val nextDayType = if (facts.isRestDay) "REST_DAY" else "WORKDAY"
                val merged = (existing ?: DailyArchiveRecord(
                    date = dateKey,
                    totalPhoneMs = facts.totalPhoneMs,
                    shortformMs = facts.shortformMs,
                    shortformSwipes = facts.shortformSwipes,
                    dayType = nextDayType
                )).copy(
                    totalPhoneMs = maxOf(existing?.totalPhoneMs ?: 0L, facts.totalPhoneMs),
                    shortformMs = maxOf(existing?.shortformMs ?: 0L, facts.shortformMs),
                    shortformSwipes = maxOf(existing?.shortformSwipes ?: 0L, facts.shortformSwipes),
                    phoneAvgSessionMs = facts.phoneAvgSessionMs ?: existing?.phoneAvgSessionMs,
                    phoneMaxSessionMs = facts.phoneMaxSessionMs ?: existing?.phoneMaxSessionMs,
                    shortformAvgSessionMs = facts.shortformAvgSessionMs ?: existing?.shortformAvgSessionMs,
                    shortformMaxSessionMs = facts.shortformMaxSessionMs ?: existing?.shortformMaxSessionMs,
                    shortformSpm = facts.shortformSpm ?: existing?.shortformSpm,
                    preSleepUsageMinutes = facts.preSleepUsageMinutes ?: existing?.preSleepUsageMinutes,
                    preSleepShortformMinutes = facts.preSleepShortformMinutes ?: existing?.preSleepShortformMinutes,
                    dayType = nextDayType
                )
                if (merged != existing) {
                    updatedDailies[dateKey] = merged
                    changed = true
                }

                // `facts.bedtimeMinute`/`wakeupMinute`/`sleepDurationMinutes` represent `date`'s night waking on `date + 1`.
                if (facts.bedtimeMinute != null || facts.wakeupMinute != null || facts.matchedNightSegment != null) {
                    val wakeDate = date.plusDays(1)
                    if (!wakeDate.isBefore(cutoffDate) && !wakeDate.isAfter(today)) {
                        val wakeKey = wakeDate.toString()
                        val wakeExisting = updatedDailies[wakeKey]
                        val seg = facts.matchedNightSegment
                        val wakeIsWeekend = wakeDate.dayOfWeek.value in 6..7
                        val wakeMerged = (wakeExisting ?: DailyArchiveRecord(
                            date = wakeKey,
                            totalPhoneMs = 0L,
                            shortformMs = 0L,
                            dayType = if (wakeIsWeekend) "REST_DAY" else "WORKDAY"
                        )).let { base ->
                            if (base.sleepConfirmed) {
                                base
                            } else {
                                base.copy(
                                    bedtimeMinute = facts.bedtimeMinute ?: base.bedtimeMinute,
                                    wakeupMinute = facts.wakeupMinute ?: base.wakeupMinute,
                                    sleepStartWallMs = seg?.start ?: base.sleepStartWallMs,
                                    sleepEndWallMs = seg?.end ?: base.sleepEndWallMs,
                                    sleepDurationMinutes = facts.sleepDurationMinutes ?: seg?.minutes ?: base.sleepDurationMinutes,
                                    briefWakeCount = seg?.briefWakeCount ?: base.briefWakeCount
                                )
                            }
                        }
                        if (wakeMerged != wakeExisting) {
                            updatedDailies[wakeKey] = wakeMerged
                            changed = true
                        }
                    }
                }
            }

            if (changed) {
                commitUpdatedDailiesLocked(appCtx, updatedDailies, today)
            }
        }
    }

    /**
     * Persists a single day's 24-hour usage distribution and top used apps for 6-month retention.
     */
    suspend fun upsertDayHourlyAndApps(
        context: Context,
        date: LocalDate,
        report: Report,
        today: LocalDate = LocalDate.now()
    ) = withContext(Dispatchers.IO) {
        val cutoffDate = today.minusMonths(MAX_RETENTION_MONTHS)
        if (date.isBefore(cutoffDate) || date.isAfter(today)) return@withContext
        val hasHourly = report.values.size == 24 && report.values.any { it > 0.0 }
        val topApps = extractTopApps(report)
        if (!hasHourly && topApps.isEmpty() && report.total <= 0.0) return@withContext

        mutex.withLock {
            ensureLoaded(context)
            val appCtx = context.applicationContext
            val dateKey = date.toString()
            val updatedDailies = cachedDailyRecords.toMutableMap()
            val existing = updatedDailies[dateKey]
            val reportMs = (report.total * 60_000.0).toLong()
            val merged = (existing ?: DailyArchiveRecord(
                date = dateKey,
                totalPhoneMs = reportMs,
                shortformMs = 0L,
                dayType = if (date.dayOfWeek.value in 6..7) "REST_DAY" else "WORKDAY"
            )).copy(
                totalPhoneMs = maxOf(existing?.totalPhoneMs ?: 0L, reportMs),
                hourlyPhoneMinutes = if (hasHourly) report.values else existing?.hourlyPhoneMinutes.orEmpty(),
                topApps = if (topApps.isNotEmpty()) topApps else existing?.topApps.orEmpty()
            )
            if (merged != existing) {
                updatedDailies[dateKey] = merged
                commitUpdatedDailiesLocked(appCtx, updatedDailies, today)
            }
        }
    }

    /**
     * Synchronizes and archives all self-measured daily data for long-term (up to 6 months) retention.
     * Integrates OS usage stats, phone sessions, shortform sessions & Room history,
     * sleep estimates & confirmations, and hourly/app snapshots.
     */
    suspend fun syncDailySnapshot(
        context: Context,
        todayUsageReport: Report?,
        periodUsageReport: Report?,
        nightReport: NightReport?
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded(context)
            val appCtx = context.applicationContext
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val cutoffDate = today.minusMonths(MAX_RETENTION_MONTHS)
            val cutoffStr = cutoffDate.toString()
            val boundaryMs = AccountDataBoundaryStore.getFreshStartEpochMs(appCtx)
            val boundaryDate = AccountDataBoundaryStore.getFreshStartDate(appCtx, zone)

            val updatedDailies = cachedDailyRecords.toMutableMap()

            // 1. Initial / Supplemental Seeding from OS UsageStats if past days are missing
            val usm = appCtx.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            val startSeed = today.minusDays(30)
            val startSeedMs = AccountDataBoundaryStore.clampStartWallMs(
                startSeed.atStartOfDay(zone).toInstant().toEpochMilli(),
                boundaryMs
            )
            val nowMs = System.currentTimeMillis()
            val osStats = if (startSeedMs < nowMs) {
                runCatching {
                    usm?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, startSeedMs, nowMs)
                }.getOrNull()
            } else {
                null
            }

            osStats?.forEach { stat ->
                if (stat.totalTimeInForeground > 0L) {
                    val d = Instant.ofEpochMilli(stat.firstTimeStamp).atZone(zone).toLocalDate()
                    if (!d.isBefore(cutoffDate) && !d.isAfter(today) &&
                        !AccountDataBoundaryStore.isDateBeforeBoundary(d, boundaryDate)
                    ) {
                        val dateKey = d.toString()
                        val existing = updatedDailies[dateKey]
                        val mergedPhoneMs = maxOf(existing?.totalPhoneMs ?: 0L, stat.totalTimeInForeground)
                        updatedDailies[dateKey] = (existing ?: DailyArchiveRecord(
                            date = dateKey,
                            totalPhoneMs = mergedPhoneMs,
                            shortformMs = 0L,
                            dayType = if (d.dayOfWeek.value in 6..7) "REST_DAY" else "WORKDAY"
                        )).copy(totalPhoneMs = mergedPhoneMs)
                    }
                }
            }

            // 2. Incorporate periodUsageReport daily totals
            periodUsageReport?.dailyTotals?.forEach { (d, minutes) ->
                if (!d.isBefore(cutoffDate) && !d.isAfter(today) &&
                    !AccountDataBoundaryStore.isDateBeforeBoundary(d, boundaryDate)
                ) {
                    val dateKey = d.toString()
                    val ms = (minutes * 60_000.0).toLong()
                    val existing = updatedDailies[dateKey]
                    val mergedPhoneMs = maxOf(existing?.totalPhoneMs ?: 0L, ms)
                    updatedDailies[dateKey] = (existing ?: DailyArchiveRecord(
                        date = dateKey,
                        totalPhoneMs = mergedPhoneMs,
                        shortformMs = 0L,
                        dayType = if (d.dayOfWeek.value in 6..7) "REST_DAY" else "WORKDAY"
                    )).copy(totalPhoneMs = mergedPhoneMs)
                }
            }

            // 3. Incorporate Room Shortform Records & prune > 6 months
            val db = ItvDatabase.get(appCtx)
            runCatching { db.shortformDao().deleteOlderThan("demo-guest", cutoffStr) }
            runCatching { db.shortformHourDao().deleteOlderThan("demo-guest", cutoffStr) }
            runCatching { db.sleepConfirmationDao().deleteOlderThan("demo-guest", cutoffStr) }

            val shortformRows = runCatching { db.shortformDao().all("demo-guest") }.getOrDefault(emptyList())
            val sfByDate = shortformRows.groupBy { it.localDate }
            sfByDate.forEach { (dateKey, rows) ->
                val d = runCatching { LocalDate.parse(dateKey) }.getOrNull() ?: return@forEach
                if (!d.isBefore(cutoffDate) && !d.isAfter(today)) {
                    val totalMs = rows.sumOf { it.durationMs }
                    val totalSwipes = rows.sumOf { it.transitionCount }.toLong()
                    val existing = updatedDailies[dateKey]
                    updatedDailies[dateKey] = (existing ?: DailyArchiveRecord(
                        date = dateKey,
                        totalPhoneMs = totalMs,
                        shortformMs = totalMs,
                        shortformSwipes = totalSwipes,
                        dayType = if (d.dayOfWeek.value in 6..7) "REST_DAY" else "WORKDAY"
                    )).copy(
                        shortformMs = maxOf(existing?.shortformMs ?: 0L, totalMs),
                        shortformSwipes = maxOf(existing?.shortformSwipes ?: 0L, totalSwipes)
                    )
                }
            }

            // 4. Incorporate Shortform Personal Session Stats (count, avg, max, SPM)
            val storedSfSessions = runCatching {
                ShortformPersonalStatsStore.getStoredSessionsSnapshot(appCtx)
            }.getOrDefault(emptyList())
            storedSfSessions.groupBy { it.date }.forEach { (dateKey, daySessions) ->
                val d = runCatching { LocalDate.parse(dateKey) }.getOrNull() ?: return@forEach
                if (!d.isBefore(cutoffDate) && !d.isAfter(today) && daySessions.isNotEmpty()) {
                    val totalSfMs = daySessions.sumOf { it.durationMs }
                    val totalSwipes = daySessions.sumOf { it.swipes.toLong() }
                    val avgSessionMs = ShortformPersonalStatsStore.calculateRepresentativeSessionMs(daySessions)
                    val maxSessionMs = daySessions.maxOfOrNull { it.durationMs }
                    val spm = if (totalSfMs >= 30_000L && totalSwipes > 0L) {
                        totalSwipes.toDouble() / (totalSfMs / 60_000.0)
                    } else null
                    val existing = updatedDailies[dateKey]
                    updatedDailies[dateKey] = (existing ?: DailyArchiveRecord(
                        date = dateKey,
                        totalPhoneMs = totalSfMs,
                        shortformMs = totalSfMs,
                        shortformSwipes = totalSwipes,
                        dayType = if (d.dayOfWeek.value in 6..7) "REST_DAY" else "WORKDAY"
                    )).copy(
                        shortformMs = maxOf(existing?.shortformMs ?: 0L, totalSfMs),
                        shortformSwipes = maxOf(existing?.shortformSwipes ?: 0L, totalSwipes),
                        shortformSessionCount = maxOf(existing?.shortformSessionCount ?: 0, daySessions.size),
                        shortformAvgSessionMs = avgSessionMs.takeIf { it > 0L } ?: existing?.shortformAvgSessionMs,
                        shortformMaxSessionMs = maxOfNullable(existing?.shortformMaxSessionMs, maxSessionMs),
                        shortformSpm = spm ?: existing?.shortformSpm
                    )
                }
            }

            // 5. Incorporate Phone Session Stats (count, total session time, avg, max) from recent OS UsageEvents
            val phoneEventsStart = AccountDataBoundaryStore.clampStartWallMs(
                today.minusDays(35).atStartOfDay(zone).toInstant().toEpochMilli(),
                boundaryMs
            )
            val phoneSessionsByDate = if (phoneEventsStart < nowMs) {
                runCatching {
                    val events = AndroidRecordSource(appCtx).events(phoneEventsStart, nowMs)
                    ShortformPersonalStatsStore.parsePhoneSessions(events).groupBy { it.date }
                }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }

            phoneSessionsByDate.forEach { (d, sessions) ->
                if (!d.isBefore(cutoffDate) && !d.isAfter(today) &&
                    !AccountDataBoundaryStore.isDateBeforeBoundary(d, boundaryDate) &&
                    sessions.isNotEmpty()
                ) {
                    val dateKey = d.toString()
                    val totalSessionMs = sessions.sumOf { it.durationMs }
                    val validSessions = sessions.filter { it.durationMs >= MIN_PHONE_SESSION_MS }
                    val avgSessionMs = if (validSessions.isNotEmpty()) {
                        validSessions.map { it.durationMs }.average().toLong()
                    } else null
                    val maxSessionMs = validSessions.maxOfOrNull { it.durationMs }
                    val existing = updatedDailies[dateKey]
                    updatedDailies[dateKey] = (existing ?: DailyArchiveRecord(
                        date = dateKey,
                        totalPhoneMs = totalSessionMs,
                        shortformMs = 0L,
                        dayType = if (d.dayOfWeek.value in 6..7) "REST_DAY" else "WORKDAY"
                    )).copy(
                        totalPhoneMs = maxOf(existing?.totalPhoneMs ?: 0L, totalSessionMs),
                        phoneSessionCount = maxOf(existing?.phoneSessionCount ?: 0, validSessions.size),
                        phoneTotalSessionMs = maxOf(existing?.phoneTotalSessionMs ?: 0L, totalSessionMs),
                        phoneAvgSessionMs = avgSessionMs ?: existing?.phoneAvgSessionMs,
                        phoneMaxSessionMs = maxOfNullable(existing?.phoneMaxSessionMs, maxSessionMs)
                    )
                }
            }

            // 6. Incorporate NightReport Sleep / Non-Use Observations (respecting fresh-start boundary)
            val filteredNightReport = AccountDataBoundaryStore.filterNightReport(nightReport, boundaryMs)
            filteredNightReport?.segments?.forEach { seg ->
                val wakeDate = seg.wakeDate
                if (!wakeDate.isBefore(cutoffDate) && !wakeDate.isAfter(today)) {
                    val dateKey = wakeDate.toString()
                    val startZdt = Instant.ofEpochMilli(seg.start).atZone(zone)
                    val bedtimeMin = startZdt.hour * 60 + startZdt.minute
                    val wakeupMin = seg.end?.let { endMs ->
                        val endZdt = Instant.ofEpochMilli(endMs).atZone(zone)
                        endZdt.hour * 60 + endZdt.minute
                    }
                    val existing = updatedDailies[dateKey]
                    if (existing == null || !existing.sleepConfirmed) {
                        updatedDailies[dateKey] = (existing ?: DailyArchiveRecord(
                            date = dateKey,
                            totalPhoneMs = 0L,
                            shortformMs = 0L,
                            dayType = if (wakeDate.dayOfWeek.value in 6..7) "REST_DAY" else "WORKDAY"
                        )).copy(
                            bedtimeMinute = bedtimeMin,
                            wakeupMinute = wakeupMin ?: existing?.wakeupMinute,
                            sleepStartWallMs = seg.start,
                            sleepEndWallMs = seg.end ?: existing?.sleepEndWallMs,
                            sleepDurationMinutes = seg.minutes,
                            briefWakeCount = seg.briefWakeCount
                        )
                    }
                }
            }

            // 7. Incorporate User-Confirmed Sleep Records from Room (highest priority)
            val confirmedSleeps = runCatching {
                db.sleepConfirmationDao().all("demo-guest")
            }.getOrDefault(emptyList())
            confirmedSleeps.forEach { conf ->
                val d = runCatching { LocalDate.parse(conf.recordDate) }.getOrNull() ?: return@forEach
                if (!d.isBefore(cutoffDate) && !d.isAfter(today) &&
                    conf.choice == "SLEEP" && conf.startMs != null && conf.endMs != null
                ) {
                    val dateKey = conf.recordDate
                    val startZdt = Instant.ofEpochMilli(conf.startMs).atZone(zone)
                    val endZdt = Instant.ofEpochMilli(conf.endMs).atZone(zone)
                    val bedMin = startZdt.hour * 60 + startZdt.minute
                    val wakeMin = endZdt.hour * 60 + endZdt.minute
                    val durMin = (conf.endMs - conf.startMs).coerceAtLeast(0L) / 60_000.0
                    val existing = updatedDailies[dateKey]
                    updatedDailies[dateKey] = (existing ?: DailyArchiveRecord(
                        date = dateKey,
                        totalPhoneMs = 0L,
                        shortformMs = 0L,
                        dayType = if (d.dayOfWeek.value in 6..7) "REST_DAY" else "WORKDAY"
                    )).copy(
                        bedtimeMinute = bedMin,
                        wakeupMinute = wakeMin,
                        sleepStartWallMs = conf.startMs,
                        sleepEndWallMs = conf.endMs,
                        sleepDurationMinutes = durMin,
                        sleepConfirmed = true
                    )
                }
            }

            // 8. Update Today's snapshot (total + 24h distribution + top apps)
            val todayKey = today.toString()
            val todayPhoneMs = ((todayUsageReport?.total ?: 0.0) * 60_000.0).toLong()
            val todayHourly = todayUsageReport?.values?.takeIf { it.size == 24 && it.any { m -> m > 0.0 } }
            val todayTopApps = todayUsageReport?.let { extractTopApps(it) }.orEmpty()
            val todayExisting = updatedDailies[todayKey]
            if (todayPhoneMs > 0L || todayExisting != null) {
                val isWeekend = today.dayOfWeek.value in 6..7
                updatedDailies[todayKey] = (todayExisting ?: DailyArchiveRecord(
                    date = todayKey,
                    totalPhoneMs = todayPhoneMs,
                    shortformMs = 0L,
                    dayType = if (isWeekend) "REST_DAY" else "WORKDAY"
                )).copy(
                    totalPhoneMs = maxOf(todayExisting?.totalPhoneMs ?: 0L, todayPhoneMs),
                    hourlyPhoneMinutes = todayHourly ?: todayExisting?.hourlyPhoneMinutes.orEmpty(),
                    topApps = if (todayTopApps.isNotEmpty()) todayTopApps else todayExisting?.topApps.orEmpty()
                )
            }

            commitUpdatedDailiesLocked(appCtx, updatedDailies, today)
        }
    }

    private fun commitUpdatedDailiesLocked(
        appCtx: Context,
        updatedDailies: Map<String, DailyArchiveRecord>,
        today: LocalDate
    ) {
        val validDailies = pruneToSixMonths(updatedDailies, today)
        val updatedSummaries = buildMonthlySummaries(validDailies)
        cachedDailyRecords = validDailies
        cachedSummaries = updatedSummaries
        saveToFile(appCtx, validDailies.values.sortedBy { it.date }, updatedSummaries.values.sortedBy { it.yearMonth })
    }

    internal fun pruneToSixMonths(
        dailies: Map<String, DailyArchiveRecord>,
        today: LocalDate = LocalDate.now()
    ): Map<String, DailyArchiveRecord> {
        val cutoffDate = today.minusMonths(MAX_RETENTION_MONTHS)
        return dailies.filter { (dateKey, _) ->
            val d = runCatching { LocalDate.parse(dateKey) }.getOrNull()
            d != null && !d.isBefore(cutoffDate)
        }
    }

    internal fun buildMonthlySummaries(
        validDailies: Map<String, DailyArchiveRecord>,
        nowWallMs: Long = System.currentTimeMillis()
    ): Map<String, MonthlyArchiveSummary> {
        val monthGroups = validDailies.values.groupBy {
            runCatching { YearMonth.from(LocalDate.parse(it.date)).toString() }.getOrDefault("")
        }.filter { it.key.isNotEmpty() }

        val updatedSummaries = mutableMapOf<String, MonthlyArchiveSummary>()
        monthGroups.forEach { (yearMonth, monthRecords) ->
            val activeDailies = monthRecords.filter { it.totalPhoneMs > 0L }
            val activeDays = activeDailies.size
            val totalPhone = activeDailies.sumOf { it.totalPhoneMs }
            val phoneDailyAvg = if (activeDays > 0) totalPhone / activeDays else 0L

            val sfDailies = activeDailies.filter { it.shortformMs > 0L }
            val totalSf = sfDailies.sumOf { it.shortformMs }
            val sfDailyAvg = if (activeDays > 0) totalSf / activeDays else 0L

            val sfSessionAvgs = monthRecords.mapNotNull { it.shortformAvgSessionMs }.filter { it > 0L }
            val sfSessionAvg = when {
                sfSessionAvgs.isNotEmpty() -> sfSessionAvgs.average().toLong()
                sfDailies.isNotEmpty() -> totalSf / sfDailies.size
                else -> 0L
            }

            // Workday vs Restday Breakdown
            val workdays = activeDailies.filter { it.dayType == "WORKDAY" }
            val restdays = activeDailies.filter { it.dayType == "REST_DAY" }

            val workdayPhoneAvg = if (workdays.isNotEmpty()) workdays.sumOf { it.totalPhoneMs } / workdays.size else 0L
            val restdayPhoneAvg = if (restdays.isNotEmpty()) restdays.sumOf { it.totalPhoneMs } / restdays.size else workdayPhoneAvg

            val workdaySfAvg = if (workdays.isNotEmpty()) workdays.sumOf { it.shortformMs } / workdays.size else 0L
            val restdaySfAvg = if (restdays.isNotEmpty()) restdays.sumOf { it.shortformMs } / restdays.size else workdaySfAvg

            // Bedtime and Wakeup averages
            val bedtimeList = monthRecords.mapNotNull { it.bedtimeMinute }
            val avgBedtime = if (bedtimeList.isNotEmpty()) bedtimeList.average().toInt() else null

            val wakeupList = monthRecords.mapNotNull { it.wakeupMinute }
            val avgWakeup = if (wakeupList.isNotEmpty()) wakeupList.average().toInt() else null

            updatedSummaries[yearMonth] = MonthlyArchiveSummary(
                yearMonth = yearMonth,
                totalPhoneMs = totalPhone,
                phoneDailyAvgMs = phoneDailyAvg,
                activeDays = activeDays,
                shortformTotalMs = totalSf,
                shortformDailyAvgMs = sfDailyAvg,
                shortformSessionAvgMs = sfSessionAvg,
                workdayPhoneAvgMs = workdayPhoneAvg,
                restdayPhoneAvgMs = restdayPhoneAvg,
                workdayShortformAvgMs = workdaySfAvg,
                restdayShortformAvgMs = restdaySfAvg,
                workdayDaysCount = workdays.size,
                restdayDaysCount = restdays.size,
                avgBedtimeMinute = avgBedtime,
                avgWakeupMinute = avgWakeup,
                dailyRecords = monthRecords.sortedBy { it.date },
                updatedAt = nowWallMs
            )
        }
        return updatedSummaries
    }

    internal fun parseArchiveJson(text: String): ParsedArchiveBundle {
        val root = JSONObject(text)
        val dailyMap = mutableMapOf<String, DailyArchiveRecord>()
        val dailyArr = root.optJSONArray("dailyRecords") ?: JSONArray()
        for (i in 0 until dailyArr.length()) {
            val o = dailyArr.optJSONObject(i) ?: continue
            val hourlyArr = o.optJSONArray("hourlyPhoneMinutes")
            val hourlyList = if (hourlyArr != null && hourlyArr.length() == 24) {
                List(24) { idx -> hourlyArr.optDouble(idx, 0.0) }
            } else {
                emptyList()
            }
            val appsArr = o.optJSONArray("topApps")
            val topAppsList = if (appsArr != null) {
                (0 until appsArr.length()).mapNotNull { idx ->
                    val ao = appsArr.optJSONObject(idx) ?: return@mapNotNull null
                    val pkg = ao.optString("pkg")
                    if (pkg.isEmpty()) return@mapNotNull null
                    ArchivedAppUsage(
                        packageName = pkg,
                        label = ao.optString("label", pkg),
                        minutes = ao.optDouble("minutes", 0.0)
                    )
                }
            } else {
                emptyList()
            }

            val rec = DailyArchiveRecord(
                date = o.optString("date"),
                totalPhoneMs = o.optLong("totalPhoneMs"),
                shortformMs = o.optLong("shortformMs"),
                shortformSwipes = o.optLong("shortformSwipes"),
                bedtimeMinute = if (o.has("bedtimeMinute")) o.optInt("bedtimeMinute") else null,
                wakeupMinute = if (o.has("wakeupMinute")) o.optInt("wakeupMinute") else null,
                sleepStartWallMs = if (o.has("sleepStartWallMs")) o.optLong("sleepStartWallMs") else null,
                sleepEndWallMs = if (o.has("sleepEndWallMs")) o.optLong("sleepEndWallMs") else null,
                sleepDurationMinutes = if (o.has("sleepDurationMinutes")) o.optDouble("sleepDurationMinutes") else null,
                briefWakeCount = o.optInt("briefWakeCount", 0),
                sleepConfirmed = o.optBoolean("sleepConfirmed", false),
                preSleepUsageMinutes = if (o.has("preSleepUsageMinutes")) o.optDouble("preSleepUsageMinutes") else null,
                preSleepShortformMinutes = if (o.has("preSleepShortformMinutes")) o.optDouble("preSleepShortformMinutes") else null,
                phoneSessionCount = o.optInt("phoneSessionCount", 0),
                phoneTotalSessionMs = o.optLong("phoneTotalSessionMs", 0L),
                phoneAvgSessionMs = if (o.has("phoneAvgSessionMs")) o.optLong("phoneAvgSessionMs") else null,
                phoneMaxSessionMs = if (o.has("phoneMaxSessionMs")) o.optLong("phoneMaxSessionMs") else null,
                shortformSessionCount = o.optInt("shortformSessionCount", 0),
                shortformAvgSessionMs = if (o.has("shortformAvgSessionMs")) o.optLong("shortformAvgSessionMs") else null,
                shortformMaxSessionMs = if (o.has("shortformMaxSessionMs")) o.optLong("shortformMaxSessionMs") else null,
                shortformSpm = if (o.has("shortformSpm")) o.optDouble("shortformSpm") else null,
                hourlyPhoneMinutes = hourlyList,
                topApps = topAppsList,
                dayType = o.optString("dayType", "WORKDAY"),
                dayTypeReason = o.optString("dayTypeReason").takeIf { it.isNotEmpty() }
            )
            if (rec.date.isNotEmpty()) dailyMap[rec.date] = rec
        }

        val summaryMap = mutableMapOf<String, MonthlyArchiveSummary>()
        val monthArr = root.optJSONArray("monthlySummaries") ?: JSONArray()
        for (i in 0 until monthArr.length()) {
            val o = monthArr.optJSONObject(i) ?: continue
            val ym = o.optString("yearMonth")
            if (ym.isEmpty()) continue
            val monthDailies = dailyMap.values.filter { it.date.startsWith(ym) }.sortedBy { it.date }
            summaryMap[ym] = MonthlyArchiveSummary(
                yearMonth = ym,
                totalPhoneMs = o.optLong("totalPhoneMs"),
                phoneDailyAvgMs = o.optLong("phoneDailyAvgMs"),
                activeDays = o.optInt("activeDays"),
                shortformTotalMs = o.optLong("shortformTotalMs"),
                shortformDailyAvgMs = o.optLong("shortformDailyAvgMs"),
                shortformSessionAvgMs = o.optLong("shortformSessionAvgMs"),
                workdayPhoneAvgMs = o.optLong("workdayPhoneAvgMs"),
                restdayPhoneAvgMs = o.optLong("restdayPhoneAvgMs"),
                workdayShortformAvgMs = o.optLong("workdayShortformAvgMs"),
                restdayShortformAvgMs = o.optLong("restdayShortformAvgMs"),
                workdayDaysCount = o.optInt("workdayDaysCount"),
                restdayDaysCount = o.optInt("restdayDaysCount"),
                avgBedtimeMinute = if (o.has("avgBedtimeMinute")) o.optInt("avgBedtimeMinute") else null,
                avgWakeupMinute = if (o.has("avgWakeupMinute")) o.optInt("avgWakeupMinute") else null,
                dailyRecords = monthDailies,
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
        return ParsedArchiveBundle(dailyRecords = dailyMap, monthlySummaries = summaryMap)
    }

    internal fun serializeArchiveJson(
        dailies: List<DailyArchiveRecord>,
        summaries: List<MonthlyArchiveSummary>
    ): String {
        val root = JSONObject()

        val dailyArr = JSONArray()
        dailies.forEach { d ->
            val o = JSONObject()
            o.put("date", d.date)
            o.put("totalPhoneMs", d.totalPhoneMs)
            o.put("shortformMs", d.shortformMs)
            o.put("shortformSwipes", d.shortformSwipes)
            d.bedtimeMinute?.let { o.put("bedtimeMinute", it) }
            d.wakeupMinute?.let { o.put("wakeupMinute", it) }
            d.sleepStartWallMs?.let { o.put("sleepStartWallMs", it) }
            d.sleepEndWallMs?.let { o.put("sleepEndWallMs", it) }
            d.sleepDurationMinutes?.let { o.put("sleepDurationMinutes", it) }
            if (d.briefWakeCount > 0) o.put("briefWakeCount", d.briefWakeCount)
            if (d.sleepConfirmed) o.put("sleepConfirmed", true)
            d.preSleepUsageMinutes?.let { o.put("preSleepUsageMinutes", it) }
            d.preSleepShortformMinutes?.let { o.put("preSleepShortformMinutes", it) }
            if (d.phoneSessionCount > 0) o.put("phoneSessionCount", d.phoneSessionCount)
            if (d.phoneTotalSessionMs > 0L) o.put("phoneTotalSessionMs", d.phoneTotalSessionMs)
            d.phoneAvgSessionMs?.let { o.put("phoneAvgSessionMs", it) }
            d.phoneMaxSessionMs?.let { o.put("phoneMaxSessionMs", it) }
            if (d.shortformSessionCount > 0) o.put("shortformSessionCount", d.shortformSessionCount)
            d.shortformAvgSessionMs?.let { o.put("shortformAvgSessionMs", it) }
            d.shortformMaxSessionMs?.let { o.put("shortformMaxSessionMs", it) }
            d.shortformSpm?.let { o.put("shortformSpm", it) }
            if (d.hourlyPhoneMinutes.size == 24) {
                val ha = JSONArray()
                d.hourlyPhoneMinutes.forEach { ha.put(it) }
                o.put("hourlyPhoneMinutes", ha)
            }
            if (d.topApps.isNotEmpty()) {
                val aa = JSONArray()
                d.topApps.take(MAX_TOP_APPS_PER_DAY).forEach { app ->
                    aa.put(
                        JSONObject()
                            .put("pkg", app.packageName)
                            .put("label", app.label)
                            .put("minutes", app.minutes)
                    )
                }
                o.put("topApps", aa)
            }
            o.put("dayType", d.dayType)
            d.dayTypeReason?.let { o.put("dayTypeReason", it) }
            dailyArr.put(o)
        }
        root.put("dailyRecords", dailyArr)

        val monthArr = JSONArray()
        summaries.forEach { m ->
            val o = JSONObject()
            o.put("yearMonth", m.yearMonth)
            o.put("totalPhoneMs", m.totalPhoneMs)
            o.put("phoneDailyAvgMs", m.phoneDailyAvgMs)
            o.put("activeDays", m.activeDays)
            o.put("shortformTotalMs", m.shortformTotalMs)
            o.put("shortformDailyAvgMs", m.shortformDailyAvgMs)
            o.put("shortformSessionAvgMs", m.shortformSessionAvgMs)
            o.put("workdayPhoneAvgMs", m.workdayPhoneAvgMs)
            o.put("restdayPhoneAvgMs", m.restdayPhoneAvgMs)
            o.put("workdayShortformAvgMs", m.workdayShortformAvgMs)
            o.put("restdayShortformAvgMs", m.restdayShortformAvgMs)
            o.put("workdayDaysCount", m.workdayDaysCount)
            o.put("restdayDaysCount", m.restdayDaysCount)
            m.avgBedtimeMinute?.let { o.put("avgBedtimeMinute", it) }
            m.avgWakeupMinute?.let { o.put("avgWakeupMinute", it) }
            o.put("updatedAt", m.updatedAt)
            monthArr.put(o)
        }
        root.put("monthlySummaries", monthArr)
        return root.toString()
    }

    private fun saveToFile(
        context: Context,
        dailies: List<DailyArchiveRecord>,
        summaries: List<MonthlyArchiveSummary>
    ) {
        val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
        val serialized = serializeArchiveJson(dailies, summaries)
        runCatching {
            val fos = file.startWrite()
            try {
                fos.bufferedWriter().use { it.write(serialized) }
                file.finishWrite(fos)
            } catch (e: Exception) {
                file.failWrite(fos)
                throw e
            }
        }
    }

    private fun extractTopApps(report: Report): List<ArchivedAppUsage> {
        return report.hourlyApps
            .asSequence()
            .flatten()
            .groupBy { it.packageName }
            .map { (pkg, list) ->
                ArchivedAppUsage(
                    packageName = pkg,
                    label = list.first().label,
                    minutes = list.sumOf { it.minutes }
                )
            }
            .filter { it.minutes >= 1.0 }
            .sortedByDescending { it.minutes }
            .take(MAX_TOP_APPS_PER_DAY)
            .toList()
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}
