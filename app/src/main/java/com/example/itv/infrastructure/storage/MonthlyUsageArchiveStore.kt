package com.example.itv.infrastructure.storage

import android.content.Context
import android.util.AtomicFile
import com.example.itv.data.local.ItvDatabase
import com.example.itv.domain.records.NightReport
import com.example.itv.domain.records.Report
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

internal data class DailyArchiveRecord(
    val date: String, // yyyy-MM-dd
    val totalPhoneMs: Long,
    val shortformMs: Long,
    val shortformSwipes: Long = 0L,
    val bedtimeMinute: Int? = null,
    val wakeupMinute: Int? = null,
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

/**
 * Manages long-term (up to 6 months) daily and monthly usage archives on-device.
 * Automatically bootstraps from OS UsageStats on initial install, then retains
 * and rolls up monthly metrics independently of OS pruning.
 */
internal object MonthlyUsageArchiveStore {

    private const val FILE_NAME = "monthly_usage_archive_v1.json"
    private const val MAX_RETENTION_MONTHS = 6L
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
            val root = JSONObject(text)

            val dailyMap = mutableMapOf<String, DailyArchiveRecord>()
            val dailyArr = root.optJSONArray("dailyRecords") ?: JSONArray()
            for (i in 0 until dailyArr.length()) {
                val o = dailyArr.optJSONObject(i) ?: continue
                val rec = DailyArchiveRecord(
                    date = o.optString("date"),
                    totalPhoneMs = o.optLong("totalPhoneMs"),
                    shortformMs = o.optLong("shortformMs"),
                    shortformSwipes = o.optLong("shortformSwipes"),
                    bedtimeMinute = if (o.has("bedtimeMinute")) o.optInt("bedtimeMinute") else null,
                    wakeupMinute = if (o.has("wakeupMinute")) o.optInt("wakeupMinute") else null,
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
            cachedDailyRecords = dailyMap
            cachedSummaries = summaryMap
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
     * Synchronizes and archives daily data for long-term (up to 6 months) retention.
     * Integrates OS usage stats, Room shortform history, and bedtime observations.
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

            val updatedDailies = cachedDailyRecords.toMutableMap()

            // 1. Initial / Supplemental Seeding from OS UsageStats if past days are missing
            val usm = appCtx.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            val startSeed = today.minusDays(30)
            val startSeedMs = startSeed.atStartOfDay(zone).toInstant().toEpochMilli()
            val nowMs = System.currentTimeMillis()
            val osStats = runCatching {
                usm?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, startSeedMs, nowMs)
            }.getOrNull()

            osStats?.forEach { stat ->
                if (stat.totalTimeInForeground > 0L) {
                    val d = Instant.ofEpochMilli(stat.firstTimeStamp).atZone(zone).toLocalDate()
                    if (!d.isBefore(cutoffDate) && !d.isAfter(today)) {
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
                if (!d.isBefore(cutoffDate) && !d.isAfter(today)) {
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

            // 3. Incorporate Room Shortform Records
            val db = ItvDatabase.get(appCtx)
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

            // 4. Incorporate NightReport Sleep / Wakeup observations
            nightReport?.segments?.forEach { seg ->
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
                    if (existing != null) {
                        updatedDailies[dateKey] = existing.copy(
                            bedtimeMinute = existing.bedtimeMinute ?: bedtimeMin,
                            wakeupMinute = existing.wakeupMinute ?: wakeupMin
                        )
                    }
                }
            }

            // 5. Update Today's snapshot
            val todayKey = today.toString()
            val todayPhoneMs = ((todayUsageReport?.total ?: 0.0) * 60_000.0).toLong()
            val todayExisting = updatedDailies[todayKey]
            if (todayPhoneMs > 0L || todayExisting != null) {
                val isWeekend = today.dayOfWeek.value in 6..7
                updatedDailies[todayKey] = (todayExisting ?: DailyArchiveRecord(
                    date = todayKey,
                    totalPhoneMs = todayPhoneMs,
                    shortformMs = 0L,
                    dayType = if (isWeekend) "REST_DAY" else "WORKDAY"
                )).copy(
                    totalPhoneMs = maxOf(todayExisting?.totalPhoneMs ?: 0L, todayPhoneMs)
                )
            }

            // 6. Prune records older than 6 months
            val validDailies = updatedDailies.filter { (dateKey, _) ->
                val d = runCatching { LocalDate.parse(dateKey) }.getOrNull()
                d != null && !d.isBefore(cutoffDate)
            }

            // 7. Rollup Monthly Summaries for all months present in records
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

                // Workday vs Restday Breakdown
                val workdays = activeDailies.filter { it.dayType == "WORKDAY" }
                val restdays = activeDailies.filter { it.dayType == "REST_DAY" }

                val workdayPhoneAvg = if (workdays.isNotEmpty()) workdays.sumOf { it.totalPhoneMs } / workdays.size else 0L
                val restdayPhoneAvg = if (restdays.isNotEmpty()) restdays.sumOf { it.totalPhoneMs } / restdays.size else workdayPhoneAvg

                val workdaySfAvg = if (workdays.isNotEmpty()) workdays.sumOf { it.shortformMs } / workdays.size else 0L
                val restdaySfAvg = if (restdays.isNotEmpty()) restdays.sumOf { it.shortformMs } / restdays.size else workdaySfAvg

                // Bedtime and Wakeup averages
                val bedtimeList = activeDailies.mapNotNull { it.bedtimeMinute }
                val avgBedtime = if (bedtimeList.isNotEmpty()) bedtimeList.average().toInt() else null

                val wakeupList = activeDailies.mapNotNull { it.wakeupMinute }
                val avgWakeup = if (wakeupList.isNotEmpty()) wakeupList.average().toInt() else null

                updatedSummaries[yearMonth] = MonthlyArchiveSummary(
                    yearMonth = yearMonth,
                    totalPhoneMs = totalPhone,
                    phoneDailyAvgMs = phoneDailyAvg,
                    activeDays = activeDays,
                    shortformTotalMs = totalSf,
                    shortformDailyAvgMs = sfDailyAvg,
                    shortformSessionAvgMs = if (sfDailies.isNotEmpty()) totalSf / sfDailies.size else 0L,
                    workdayPhoneAvgMs = workdayPhoneAvg,
                    restdayPhoneAvgMs = restdayPhoneAvg,
                    workdayShortformAvgMs = workdaySfAvg,
                    restdayShortformAvgMs = restdaySfAvg,
                    workdayDaysCount = workdays.size,
                    restdayDaysCount = restdays.size,
                    avgBedtimeMinute = avgBedtime,
                    avgWakeupMinute = avgWakeup,
                    dailyRecords = monthRecords.sortedBy { it.date },
                    updatedAt = System.currentTimeMillis()
                )
            }

            cachedDailyRecords = validDailies
            cachedSummaries = updatedSummaries

            // 8. Persist to AtomicFile
            saveToFile(appCtx, validDailies.values.toList(), updatedSummaries.values.toList())
        }
    }

    private fun saveToFile(
        context: Context,
        dailies: List<DailyArchiveRecord>,
        summaries: List<MonthlyArchiveSummary>
    ) {
        val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
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

        runCatching {
            val fos = file.startWrite()
            try {
                fos.bufferedWriter().use { it.write(root.toString()) }
                file.finishWrite(fos)
            } catch (e: Exception) {
                file.failWrite(fos)
                throw e
            }
        }
    }
}
