package com.example.itv.infrastructure.coaching

import android.content.Context
import android.util.AtomicFile
import com.example.itv.domain.coaching.MailboxNotice
import com.example.itv.domain.coaching.MailboxNoticeCategory
import com.example.itv.domain.coaching.WeeklyAppStat
import com.example.itv.domain.coaching.WeeklyCoachLetter
import com.example.itv.domain.coaching.WeeklyCoachReportGenerator
import com.example.itv.domain.coaching.WeeklyComparisonStats
import com.example.itv.domain.coaching.WeeklyDaySnapshot
import com.example.itv.domain.coaching.WeeklyImprovementHighlight
import com.example.itv.domain.coaching.WeeklyPatternCategory
import com.example.itv.domain.coaching.WeeklyPatternDiscovery
import com.example.itv.infrastructure.storage.AccountDataBoundaryStore
import com.example.itv.infrastructure.storage.DailyArchiveRecord
import com.example.itv.infrastructure.storage.MonthlyUsageArchiveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * On-device persistent mailbox for weekly life-coach letters delivered every Monday.
 * - Non-blocking: all disk reads/writes happen on [Dispatchers.IO] via [AtomicFile].
 * - Retains up to 6 months (~26 weeks) of weekly letters.
 * - Exposes [letters] StateFlow for zero-lag Compose observation.
 */
internal object WeeklyMailboxStore {

    internal const val FILE_NAME = "weekly_mailbox_v1.json"
    private const val MAX_RETENTION_MONTHS = 6L
    private const val MAX_MISSED_BACKFILL_WEEKS = 4

    private val mutex = Mutex()
    @Volatile
    private var loaded = false

    @Volatile
    private var lastEnsuredDay: LocalDate? = null

    private val _letters = MutableStateFlow<List<WeeklyCoachLetter>>(emptyList())
    val letters: StateFlow<List<WeeklyCoachLetter>> = _letters.asStateFlow()

    private fun mailboxFile(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    suspend fun loadIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        mutex.withLock {
            if (loaded) return@withLock
            val parsed = readFromFile(context.applicationContext)
            _letters.value = parsed.sortedByDescending { it.weekStartDate }
            loaded = true
        }
    }

    /**
     * Delivers the weekly coach letter whenever a new Monday starts (covering the completed Mon~Sun week).
     * Safe to call from WorkManager ([DailyArchiveSyncWorker]) or on foreground resume; strictly idempotent.
     *
     * @return true if one or more new letters were delivered.
     */
    suspend fun ensureMondayLettersDelivered(
        context: Context,
        today: LocalDate = LocalDate.now(),
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        if (!CoachingConsentStore.granted(appCtx)) return@withContext false
        if (!File(appCtx.noBackupFilesDir, "login_completed_v1").exists()) return@withContext false

        if (loaded && lastEnsuredDay == today) return@withContext false

        loadIfNeeded(appCtx)

        mutex.withLock {
            if (lastEnsuredDay == today) return@withLock false
            val cutoffDate = today.minusMonths(MAX_RETENTION_MONTHS)
            val existingById = _letters.value
                .filter { !it.weekStartDate.isBefore(cutoffDate) && !it.isPreview }
                .associateBy { it.id }
                .toMutableMap()

            val latestCompletedMonday = WeeklyCoachReportGenerator.latestCompletedWeekStart(today)
            val weeksToCheck = buildWeeksToDeliver(
                existingWeekStarts = existingById.values.map { it.weekStartDate }.toSet(),
                latestCompletedMonday = latestCompletedMonday,
                cutoffDate = cutoffDate,
            )
            if (weeksToCheck.isEmpty()) {
                lastEnsuredDay = today
                return@withLock false
            }

            val allSnapshots = loadSnapshotsFromArchive(appCtx)
            if (allSnapshots.isEmpty()) {
                lastEnsuredDay = today
                return@withLock false
            }

            var addedAny = false
            val nowMs = System.currentTimeMillis()
            weeksToCheck.forEach { weekStart ->
                val id = WeeklyCoachReportGenerator.letterIdForWeekStart(weekStart)
                if (id !in existingById) {
                    val generated = WeeklyCoachReportGenerator.generateLetter(
                        weekStartDate = weekStart,
                        snapshotsByDate = allSnapshots,
                        deliveredAtMs = nowMs,
                        isRead = false,
                        isPreview = false,
                    )
                    if (generated != null) {
                        existingById[id] = generated
                        addedAny = true
                    }
                }
            }

            val finalList = existingById.values
                .filter { !it.weekStartDate.isBefore(cutoffDate) }
                .sortedByDescending { it.weekStartDate }

            if (addedAny || finalList.size != _letters.value.size) {
                _letters.value = finalList
                saveToFile(appCtx, finalList)
            }
            lastEnsuredDay = today
            addedAny
        }
    }

    /**
     * Determines which completed Monday weeks need a new letter delivered:
     * - First-ever mailbox user: checks only [latestCompletedMonday] so they receive 1 fresh letter, not a flood.
     * - Existing mailbox user who missed opening the app for 1..4 Mondays: fills any missed Mondays in between.
     */
    internal fun buildWeeksToDeliver(
        existingWeekStarts: Set<LocalDate>,
        latestCompletedMonday: LocalDate,
        cutoffDate: LocalDate,
    ): List<LocalDate> {
        if (latestCompletedMonday.isBefore(cutoffDate)) return emptyList()
        if (latestCompletedMonday in existingWeekStarts) return emptyList()

        val mostRecentExisting = existingWeekStarts.maxOrNull()
        if (mostRecentExisting == null) {
            return listOf(latestCompletedMonday)
        }

        val candidates = mutableListOf<LocalDate>()
        var cursor = latestCompletedMonday
        var steps = 0
        while (cursor.isAfter(mostRecentExisting) &&
            !cursor.isBefore(cutoffDate) &&
            steps < MAX_MISSED_BACKFILL_WEEKS
        ) {
            candidates += cursor
            cursor = cursor.minusWeeks(1)
            steps++
        }
        return candidates.sorted()
    }

    suspend fun markAsRead(context: Context, letterId: String) {
        val appCtx = context.applicationContext
        val current = _letters.value
        val target = current.firstOrNull { it.id == letterId } ?: return
        if (target.isRead) return

        // Optimistic immediate UI update
        val optimistic = current.map { if (it.id == letterId) it.copy(isRead = true) else it }
        _letters.value = optimistic
        WeeklyMailboxNotificationCoordinator.markNotifiedAndCancelTray(appCtx, letterId)

        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = _letters.value.map { if (it.id == letterId) it.copy(isRead = true) else it }
                _letters.value = updated
                saveToFile(appCtx, updated)
            }
        }
    }

    /**
     * Builds a non-persistent preview letter from the user's real archived data (for the Test tab),
     * falling back to a rich sample preview letter if the device doesn't have enough history yet.
     */
    suspend fun buildPreviewLetterForTestTab(
        context: Context,
        preferRealData: Boolean,
        today: LocalDate = LocalDate.now(),
    ): WeeklyCoachLetter = withContext(Dispatchers.IO) {
        if (preferRealData) {
            val snapshots = loadSnapshotsFromArchive(context.applicationContext)
            val latestMonday = WeeklyCoachReportGenerator.latestCompletedWeekStart(today)
            val realLetter = WeeklyCoachReportGenerator.generateLetter(
                weekStartDate = latestMonday,
                snapshotsByDate = snapshots,
                deliveredAtMs = System.currentTimeMillis(),
                isRead = false,
                isPreview = true,
            ) ?: WeeklyCoachReportGenerator.generateLetter(
                // If user only has data from this current ongoing week, allow previewing this week so far
                weekStartDate = latestMonday.plusWeeks(1),
                snapshotsByDate = snapshots,
                deliveredAtMs = System.currentTimeMillis(),
                isRead = false,
                isPreview = true,
            )
            if (realLetter != null) return@withContext realLetter
        }
        WeeklyCoachReportGenerator.generateSamplePreviewLetter(today)
    }

    /**
     * Builds a non-persistent mailbox list for previewing the exact production [WeeklyMailboxDialog]
     * inside the Test tab without modifying real stored letters or read states.
     *
     * - [preferRealData] == true: builds up to 4 recent real weekly letters from the device's
     *   archived history (newest marked unread to preview unsealing; earlier weeks marked read
     *   to preview opened envelopes), falling back to the sample mailbox if no real data exists.
     * - [preferRealData] == false: returns the 3-week sample mailbox (1 unread + 2 read).
     */
    suspend fun buildPreviewMailboxForTestTab(
        context: Context,
        preferRealData: Boolean,
        today: LocalDate = LocalDate.now(),
    ): List<WeeklyCoachLetter> = withContext(Dispatchers.IO) {
        if (!preferRealData) {
            return@withContext WeeklyCoachReportGenerator.generateSampleMailboxLetters(today)
        }

        val snapshots = loadSnapshotsFromArchive(context.applicationContext)
        val latestMonday = WeeklyCoachReportGenerator.latestCompletedWeekStart(today)
        val nowMs = System.currentTimeMillis()
        val weekMs = 7L * 24L * 60L * 60L * 1000L

        val candidateMondays = listOf(
            latestMonday,
            latestMonday.minusWeeks(1),
            latestMonday.minusWeeks(2),
            latestMonday.minusWeeks(3),
        )
        val generated = candidateMondays.mapIndexedNotNull { idx, monday ->
            WeeklyCoachReportGenerator.generateLetter(
                weekStartDate = monday,
                snapshotsByDate = snapshots,
                deliveredAtMs = nowMs - idx * weekMs,
                isRead = false,
                isPreview = false,
            )
        }.toMutableList()

        // If no completed week exists yet, allow previewing the ongoing current week so far
        if (generated.isEmpty()) {
            WeeklyCoachReportGenerator.generateLetter(
                weekStartDate = latestMonday.plusWeeks(1),
                snapshotsByDate = snapshots,
                deliveredAtMs = nowMs,
                isRead = false,
                isPreview = false,
            )?.let(generated::add)
        }

        if (generated.isEmpty()) {
            return@withContext WeeklyCoachReportGenerator.generateSampleMailboxLetters(today)
        }

        // Mark newest letter unread (sealed) and older letters read (open) so both states are testable
        generated.mapIndexed { idx, letter ->
            letter.copy(isRead = idx > 0)
        }
    }

    /**
     * Sample notices for previewing the "공지" tab inside the Test tab (non-persistent).
     * Mirrors the exact schema ready for future server-backed updates, events, surveys, and CS notices.
     */
    fun buildSampleNoticesForTestTab(today: LocalDate = LocalDate.now()): List<MailboxNotice> = listOf(
        MailboxNotice(
            id = "notice-sample-update",
            category = MailboxNoticeCategory.UPDATE,
            title = "우편함이 개인·공지 탭으로 새로워졌어요",
            body = "이제 매주 월요일 도착하는 주간 코치 편지는 [개인] 탭에서, 앱 업데이트·이벤트·설문 보상은 [공지] 탭에서 깔끔하게 나누어 확인할 수 있어요.\n\n" +
                "주간 편지 도착 알림도 메신저·금융·통화 등 방해되면 안 되는 순간에는 울리지 않고, 여유로운 타이밍에만 조용히 전해드릴게요.",
            publishedDate = today,
            isPinned = true,
            isRead = false,
        ),
        MailboxNotice(
            id = "notice-sample-survey",
            category = MailboxNoticeCategory.SURVEY,
            title = "[미리보기] 사용자 의견 설문 및 보상 안내",
            body = "앞으로 진행될 이벤트나 사용자 설문조사 안내가 이곳 공지 탭으로 발송될 예정이에요.\n\n" +
                "설문에 참여해 주시면 감사 보상이 개인 우편함으로 지급되는 구조로 확장할 수 있어요.",
            publishedDate = today.minusDays(3),
            isPinned = false,
            isRead = true,
        ),
    )

    /**
     * Compact metadata exported into CloudSyncRepository's `goalsJson` (< 400 bytes)
     * so reinstall + login restores delivered weekly letters & read states cleanly.
     */
    fun exportCloudMetadata(context: Context): JSONObject {
        if (!loaded) {
            val parsed = readFromFile(context.applicationContext)
            if (!loaded) {
                _letters.value = parsed.sortedByDescending { it.weekStartDate }
                loaded = true
            }
        }
        val list = _letters.value.filter { !it.isPreview }.take(26)
        return JSONObject().apply {
            put("deliveredWeeks", JSONArray(list.map { it.weekStartDate.toString() }))
            put("readWeeks", JSONArray(list.filter { it.isRead }.map { it.weekStartDate.toString() }))
        }
    }

    /**
     * Rebuilds delivered weekly letters from the (already restored) 6-month archive and restores
     * read/unread status after a fresh reinstall or Guest -> Social account link.
     */
    suspend fun restoreFromCloudMetadata(
        context: Context,
        metadata: JSONObject?,
        mergeWithLocal: Boolean,
        today: LocalDate = LocalDate.now(),
    ) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        loadIfNeeded(appCtx)
        if (metadata == null) return@withContext

        val cutoffDate = today.minusMonths(MAX_RETENTION_MONTHS)
        val deliveredArr = metadata.optJSONArray("deliveredWeeks")
        val readArr = metadata.optJSONArray("readWeeks")

        val remoteDelivered = mutableSetOf<LocalDate>()
        if (deliveredArr != null) {
            for (i in 0 until deliveredArr.length()) {
                runCatching { LocalDate.parse(deliveredArr.optString(i)) }.getOrNull()
                    ?.takeIf { !it.isBefore(cutoffDate) }
                    ?.let(remoteDelivered::add)
            }
        }
        val remoteRead = mutableSetOf<LocalDate>()
        if (readArr != null) {
            for (i in 0 until readArr.length()) {
                runCatching { LocalDate.parse(readArr.optString(i)) }.getOrNull()
                    ?.takeIf { !it.isBefore(cutoffDate) }
                    ?.let(remoteRead::add)
            }
        }
        if (remoteDelivered.isEmpty() && remoteRead.isEmpty()) return@withContext

        mutex.withLock {
            val snapshots = loadSnapshotsFromArchive(appCtx)
            val baseMap = if (mergeWithLocal) {
                _letters.value.filter { !it.isPreview && !it.weekStartDate.isBefore(cutoffDate) }
                    .associateBy { it.id }
                    .toMutableMap()
            } else {
                mutableMapOf()
            }

            val allWeeksToRestore = (remoteDelivered + remoteRead).sorted()
            val nowMs = System.currentTimeMillis()
            allWeeksToRestore.forEach { weekStart ->
                val id = WeeklyCoachReportGenerator.letterIdForWeekStart(weekStart)
                val existing = baseMap[id]
                val shouldBeRead = (weekStart in remoteRead) || (existing?.isRead == true)
                if (existing != null) {
                    baseMap[id] = existing.copy(isRead = shouldBeRead)
                } else {
                    val rebuilt = WeeklyCoachReportGenerator.generateLetter(
                        weekStartDate = weekStart,
                        snapshotsByDate = snapshots,
                        deliveredAtMs = nowMs,
                        isRead = shouldBeRead,
                        isPreview = false,
                    )
                    if (rebuilt != null) {
                        baseMap[id] = rebuilt
                    }
                }
            }

            val finalList = baseMap.values
                .filter { !it.weekStartDate.isBefore(cutoffDate) }
                .sortedByDescending { it.weekStartDate }
            _letters.value = finalList
            lastEnsuredDay = null
            saveToFile(appCtx, finalList)
            WeeklyMailboxNotificationCoordinator.markRestoredLettersAsNotified(
                appCtx,
                finalList.map { it.id },
            )
        }
    }

    suspend fun reset(context: Context) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        mutex.withLock {
            _letters.value = emptyList()
            loaded = true
            lastEnsuredDay = null
            val f = mailboxFile(appCtx)
            runCatching { AtomicFile(f).delete() }
            runCatching { if (f.exists()) f.delete() }
            runCatching { File(f.path + ".bak").delete() }
            WeeklyMailboxNotificationCoordinator.reset(appCtx)
        }
    }

    private fun loadSnapshotsFromArchive(appCtx: Context): Map<LocalDate, WeeklyDaySnapshot> {
        val boundaryDate = AccountDataBoundaryStore.getFreshStartDate(appCtx)
        val rawMap = MonthlyUsageArchiveStore.getAllDailyRecords(appCtx)
        val result = HashMap<LocalDate, WeeklyDaySnapshot>(rawMap.size)
        rawMap.forEach { (dateStr, rec) ->
            val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@forEach
            if (AccountDataBoundaryStore.isDateBeforeBoundary(date, boundaryDate)) return@forEach
            result[date] = rec.toWeeklyDaySnapshot(date)
        }
        return result
    }

    private fun DailyArchiveRecord.toWeeklyDaySnapshot(date: LocalDate): WeeklyDaySnapshot =
        WeeklyDaySnapshot(
            date = date,
            totalPhoneMs = totalPhoneMs,
            shortformMs = shortformMs,
            shortformSwipes = shortformSwipes,
            bedtimeMinute = bedtimeMinute,
            wakeupMinute = wakeupMinute,
            sleepDurationMinutes = sleepDurationMinutes,
            preSleepUsageMinutes = preSleepUsageMinutes,
            preSleepShortformMinutes = preSleepShortformMinutes,
            phoneSessionCount = phoneSessionCount,
            phoneAvgSessionMs = phoneAvgSessionMs,
            shortformSessionCount = shortformSessionCount,
            shortformAvgSessionMs = shortformAvgSessionMs,
            shortformMaxSessionMs = shortformMaxSessionMs,
            shortformSpm = shortformSpm,
            hourlyPhoneMinutes = hourlyPhoneMinutes,
            topApps = topApps.map { WeeklyAppStat(it.packageName, it.label, it.minutes) },
            isRestDay = dayType == "REST_DAY",
        )

    private fun readFromFile(appCtx: Context): List<WeeklyCoachLetter> {
        val file = mailboxFile(appCtx)
        if (!file.exists()) return emptyList()
        return runCatching {
            val text = AtomicFile(file).readFully().toString(Charsets.UTF_8)
            parseMailboxJson(text)
        }.getOrDefault(emptyList())
    }

    private fun saveToFile(appCtx: Context, letters: List<WeeklyCoachLetter>) {
        val atomic = AtomicFile(mailboxFile(appCtx))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(serializeMailboxJson(letters).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (_: Exception) {
            if (stream != null) atomic.failWrite(stream)
        }
    }

    internal fun serializeMailboxJson(letters: List<WeeklyCoachLetter>): String {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        val arr = JSONArray()
        letters.forEach { letter ->
            arr.put(
                JSONObject().apply {
                    put("id", letter.id)
                    put("weekStartDate", letter.weekStartDate.toString())
                    put("weekEndDate", letter.weekEndDate.toString())
                    put("deliveredMondayDate", letter.deliveredMondayDate.toString())
                    put("title", letter.title)
                    put("previewTeaser", letter.previewTeaser)
                    put("greeting", letter.greeting)
                    put("coachTipTitle", letter.coachTipTitle)
                    put("coachTipBody", letter.coachTipBody)
                    put("deliveredAtMs", letter.deliveredAtMs)
                    put("isRead", letter.isRead)
                    put(
                        "improvements",
                        JSONArray().apply {
                            letter.improvements.forEach { imp ->
                                put(
                                    JSONObject().apply {
                                        put("id", imp.id)
                                        put("title", imp.title)
                                        put("badgeText", imp.badgeText)
                                        put("detail", imp.detail)
                                    }
                                )
                            }
                        }
                    )
                    put(
                        "discoveries",
                        JSONArray().apply {
                            letter.discoveries.forEach { disc ->
                                put(
                                    JSONObject().apply {
                                        put("id", disc.id)
                                        put("category", disc.category.name)
                                        put("title", disc.title)
                                        put("detail", disc.detail)
                                        if (disc.highlightTag != null) put("highlightTag", disc.highlightTag)
                                    }
                                )
                            }
                        }
                    )
                    put(
                        "stats",
                        JSONObject().apply {
                            put("activeDays", letter.stats.activeDays)
                            put("avgDailyPhoneMinutes", letter.stats.avgDailyPhoneMinutes)
                            if (letter.stats.prevAvgDailyPhoneMinutes != null) {
                                put("prevAvgDailyPhoneMinutes", letter.stats.prevAvgDailyPhoneMinutes)
                            }
                            put("avgDailyShortformMinutes", letter.stats.avgDailyShortformMinutes)
                            if (letter.stats.prevAvgDailyShortformMinutes != null) {
                                put("prevAvgDailyShortformMinutes", letter.stats.prevAvgDailyShortformMinutes)
                            }
                            if (letter.stats.avgBedtimeMinute != null) {
                                put("avgBedtimeMinute", letter.stats.avgBedtimeMinute)
                            }
                            if (letter.stats.avgSleepMinutes != null) {
                                put("avgSleepMinutes", letter.stats.avgSleepMinutes)
                            }
                            if (letter.stats.savedWeeklyMinutes != null) {
                                put("savedWeeklyMinutes", letter.stats.savedWeeklyMinutes)
                            }
                            if (letter.stats.reclaimedTimeExample != null) {
                                put("reclaimedTimeExample", letter.stats.reclaimedTimeExample)
                            }
                        }
                    )
                }
            )
        }
        root.put("letters", arr)
        return root.toString()
    }

    internal fun parseMailboxJson(text: String): List<WeeklyCoachLetter> {
        if (text.isBlank()) return emptyList()
        val root = JSONObject(text)
        val arr = root.optJSONArray("letters") ?: return emptyList()
        val result = ArrayList<WeeklyCoachLetter>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val weekStart = runCatching { LocalDate.parse(obj.optString("weekStartDate")) }.getOrNull() ?: continue
            val weekEnd = runCatching { LocalDate.parse(obj.optString("weekEndDate")) }.getOrNull()
                ?: weekStart.plusDays(6)
            val deliveredMonday = runCatching { LocalDate.parse(obj.optString("deliveredMondayDate")) }.getOrNull()
                ?: weekStart.plusWeeks(1)

            val improvements = mutableListOf<WeeklyImprovementHighlight>()
            val impArr = obj.optJSONArray("improvements")
            if (impArr != null) {
                for (j in 0 until impArr.length()) {
                    val o = impArr.optJSONObject(j) ?: continue
                    improvements += WeeklyImprovementHighlight(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        badgeText = o.optString("badgeText"),
                        detail = o.optString("detail"),
                    )
                }
            }

            val discoveries = mutableListOf<WeeklyPatternDiscovery>()
            val discArr = obj.optJSONArray("discoveries")
            if (discArr != null) {
                for (j in 0 until discArr.length()) {
                    val o = discArr.optJSONObject(j) ?: continue
                    val cat = runCatching { WeeklyPatternCategory.valueOf(o.optString("category")) }
                        .getOrDefault(WeeklyPatternCategory.TIME_RHYTHM)
                    discoveries += WeeklyPatternDiscovery(
                        id = o.optString("id"),
                        category = cat,
                        title = o.optString("title"),
                        detail = o.optString("detail"),
                        highlightTag = o.optString("highlightTag").takeIf { it.isNotBlank() },
                    )
                }
            }

            val sObj = obj.optJSONObject("stats")
            val stats = WeeklyComparisonStats(
                activeDays = sObj?.optInt("activeDays", 0) ?: 0,
                avgDailyPhoneMinutes = sObj?.optInt("avgDailyPhoneMinutes", 0) ?: 0,
                prevAvgDailyPhoneMinutes = if (sObj != null && sObj.has("prevAvgDailyPhoneMinutes")) sObj.optInt("prevAvgDailyPhoneMinutes") else null,
                avgDailyShortformMinutes = sObj?.optInt("avgDailyShortformMinutes", 0) ?: 0,
                prevAvgDailyShortformMinutes = if (sObj != null && sObj.has("prevAvgDailyShortformMinutes")) sObj.optInt("prevAvgDailyShortformMinutes") else null,
                avgBedtimeMinute = if (sObj != null && sObj.has("avgBedtimeMinute")) sObj.optInt("avgBedtimeMinute") else null,
                avgSleepMinutes = if (sObj != null && sObj.has("avgSleepMinutes")) sObj.optInt("avgSleepMinutes") else null,
                savedWeeklyMinutes = if (sObj != null && sObj.has("savedWeeklyMinutes")) sObj.optInt("savedWeeklyMinutes") else null,
                reclaimedTimeExample = sObj?.optString("reclaimedTimeExample")?.takeIf { it.isNotBlank() },
            )

            result += WeeklyCoachLetter(
                id = obj.optString("id").ifBlank { WeeklyCoachReportGenerator.letterIdForWeekStart(weekStart) },
                weekStartDate = weekStart,
                weekEndDate = weekEnd,
                deliveredMondayDate = deliveredMonday,
                title = obj.optString("title"),
                previewTeaser = obj.optString("previewTeaser"),
                greeting = obj.optString("greeting"),
                improvements = improvements,
                discoveries = discoveries,
                coachTipTitle = obj.optString("coachTipTitle"),
                coachTipBody = obj.optString("coachTipBody"),
                stats = stats,
                deliveredAtMs = obj.optLong("deliveredAtMs", 0L),
                isRead = obj.optBoolean("isRead", false),
                isPreview = false,
            )
        }
        return result
    }
}
