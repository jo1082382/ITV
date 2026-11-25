package com.example.itv.infrastructure.storage

import android.content.Context
import com.example.itv.composition.queryNightReport
import com.example.itv.composition.queryUsageReport
import com.example.itv.composition.sleepConfirmationService
import com.example.itv.data.local.ItvDatabase
import com.example.itv.domain.records.ArchivedSleepRecord
import com.example.itv.domain.records.CalendarDayCyclePolicy
import com.example.itv.domain.records.DailyHealthAssessment
import com.example.itv.domain.records.DailyHealthContext
import com.example.itv.domain.records.DailyHealthEvaluator
import com.example.itv.domain.records.DailyHealthTier
import com.example.itv.domain.records.HourlyUsageBucket
import com.example.itv.domain.records.NightReport
import com.example.itv.domain.records.NightSegment
import com.example.itv.domain.records.PreSleepWeightedWindows
import com.example.itv.domain.records.Report
import com.example.itv.domain.records.SleepSummaryPolicy
import com.example.itv.domain.records.TimeWindowMs
import com.example.itv.domain.records.WeightedPreSleepUsage
import com.example.itv.infrastructure.coaching.UserCoachingSettingsStore
import com.example.itv.infrastructure.records.AndroidRecordSource
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore
import com.example.itv.presentation.usage.DailyStampPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

internal data class DayResolvedFacts(
    val date: LocalDate,
    val totalPhoneMs: Long,
    val shortformMs: Long,
    val shortformSwipes: Long,
    val shortformAvgSessionMs: Long?,
    val shortformMaxSessionMs: Long?,
    val shortformSpm: Double?,
    val phoneAvgSessionMs: Long?,
    val phoneMaxSessionMs: Long?,
    val preSleepUsageMinutes: Double?,
    val preSleepShortformMinutes: Double?,
    val bedtimeMinute: Int?,
    val wakeupMinute: Int?,
    val sleepDurationMinutes: Double?,
    val matchedNightSegment: NightSegment?,
    val isRestDay: Boolean
)

internal data class MonthlyHealthTally(
    val yearMonth: YearMonth,
    val goldCount: Int,
    val silverCount: Int,
    val bronzeCount: Int,
    val warningCount: Int,
    val criticalCount: Int,
    val inProgressCount: Int,
    val evaluatedFinalDays: Int,
    val averageScore: Int
) {
    val happyCount: Int get() = goldCount + silverCount
    val happyRatePercent: Int get() = if (evaluatedFinalDays > 0) (happyCount * 100) / evaluatedFinalDays else 0
}

internal data class CalendarMonthHealthBundle(
    val viewMonth: YearMonth,
    val assessments: Map<LocalDate, DailyHealthAssessment>,
    val facts: Map<LocalDate, DayResolvedFacts>,
    val tally: MonthlyHealthTally,
    val baselineBedtimeMinute: Int?,
    val nightReport: NightReport?
)

/**
 * Loads and synthesizes ITV's on-device daily archives, shortform sessions, phone sessions,
 * and sleep confirmations to produce comprehensive DailyHealthAssessments for an entire calendar month.
 */
internal object CalendarHealthLoader {

    private const val MIN_PHONE_SESSION_MS = 120_000L // 2분 미만 퀵체크 필터링
    private const val DEFAULT_CACHE_MAX_AGE_MS = 120_000L

    private data class PreloadedMonthCache(
        val viewMonth: YearMonth,
        val today: LocalDate,
        val stampedDates: Set<String>,
        val bundle: CalendarMonthHealthBundle,
        val cachedAtMs: Long,
        val isFreshPreload: Boolean = false
    )

    @Volatile
    private var preloadedMonthCache: PreloadedMonthCache? = null

    fun getCachedMonth(
        viewMonth: YearMonth,
        today: LocalDate,
        stampedDates: Set<String>,
        maxAgeMs: Long = DEFAULT_CACHE_MAX_AGE_MS
    ): CalendarMonthHealthBundle? {
        val entry = preloadedMonthCache ?: return null
        val ageMs = System.currentTimeMillis() - entry.cachedAtMs
        if (ageMs !in 0L..maxAgeMs) return null
        if (entry.viewMonth != viewMonth || entry.today != today || entry.stampedDates != stampedDates) {
            return null
        }
        return entry.bundle
    }

    fun consumeFreshPreload(
        viewMonth: YearMonth,
        today: LocalDate,
        stampedDates: Set<String>,
        maxAgeMs: Long = DEFAULT_CACHE_MAX_AGE_MS
    ): CalendarMonthHealthBundle? {
        val entry = preloadedMonthCache ?: return null
        if (!entry.isFreshPreload) return null
        val ageMs = System.currentTimeMillis() - entry.cachedAtMs
        if (ageMs !in 0L..maxAgeMs) return null
        if (entry.viewMonth != viewMonth || entry.today != today || entry.stampedDates != stampedDates) {
            return null
        }
        preloadedMonthCache = entry.copy(isFreshPreload = false)
        return entry.bundle
    }

    fun clearCache() {
        preloadedMonthCache = null
    }

    /**
     * Pre-warms the current month's calendar health bundle off the main thread (for example,
     * during the post-login cloud sync waiting screen) so Home displays restored data on frame 0.
     */
    suspend fun preloadCurrentMonth(context: Context): CalendarMonthHealthBundle = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val viewMonth = YearMonth.from(today)
        val todayRep = runCatching { appCtx.queryUsageReport(1, today) }.getOrNull()
        val nightRep = runCatching { queryNightReport(appCtx, 45) }.getOrNull()
        val stampedDates = DailyStampPrefs.getStampedDates(appCtx)
        val bundle = loadMonth(
            context = appCtx,
            viewMonth = viewMonth,
            todayReport = todayRep,
            existingNightReport = nightRep,
            stampedDates = stampedDates
        )
        preloadedMonthCache = PreloadedMonthCache(
            viewMonth = viewMonth,
            today = today,
            stampedDates = stampedDates,
            bundle = bundle,
            cachedAtMs = System.currentTimeMillis(),
            isFreshPreload = true
        )
        bundle
    }

    private data class YesterdayGateState(
        val hasData: Boolean,
        val isFinalized: Boolean,
        val isStamped: Boolean
    )

    private data class EvaluatedDay(
        val assessment: DailyHealthAssessment,
        val facts: DayResolvedFacts?,
        val isFinalized: Boolean
    )

    suspend fun loadMonth(
        context: Context,
        viewMonth: YearMonth,
        todayReport: Report?,
        existingNightReport: NightReport?,
        stampedDates: Set<String> = DailyStampPrefs.getStampedDates(context)
    ): CalendarMonthHealthBundle = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val zone = ZoneId.systemDefault()
        val nowWallMs = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val nowTime = LocalTime.now(zone)

        val allDailyRecords = MonthlyUsageArchiveStore.getAllDailyRecords(appCtx)
        val db = ItvDatabase.get(appCtx)

        // 1. Shortform Room daily & hourly rows (for tracking start date + totals + late-night shortform hours)
        val shortformDailyRows = runCatching { db.shortformDao().all("demo-guest") }.getOrDefault(emptyList())
        val sfDailyByDate = shortformDailyRows.groupBy { it.localDate }
        val shortformHourRows = runCatching { db.shortformHourDao().all("demo-guest") }.getOrDefault(emptyList())
        val sfHoursByDate = shortformHourRows.groupBy { it.localDate }

        // 2. Shortform personal sessions (for avg/max session length, SPM, late-night sessions)
        val storedSfSessions = ShortformPersonalStatsStore.getStoredSessionsSnapshot(appCtx)
        val sfSessionsByDate = storedSfSessions.groupBy { it.date }

        // Determine earliest date when ITV shortform tracking started
        val firstTrackedDate = (sfDailyByDate.keys + sfHoursByDate.keys + sfSessionsByDate.keys)
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .minOrNull()

        // 3. Sleep confirmations & NightReport
        val daysInMonth = viewMonth.lengthOfMonth()
        val monthDates = (1..daysInMonth).map { viewMonth.atDay(it) }
        val needsCrossMonthYesterday = viewMonth == YearMonth.from(today) && yesterday.month != viewMonth.month
        val datesNeedingSleep = if (needsCrossMonthYesterday) monthDates + yesterday else monthDates
        val boundaryMs = AccountDataBoundaryStore.getFreshStartEpochMs(appCtx)
        val boundaryDate = AccountDataBoundaryStore.getFreshStartDate(appCtx, zone)
        val boundedNightReport = AccountDataBoundaryStore.filterNightReport(existingNightReport, boundaryMs)
        val candidates = boundedNightReport?.candidates.orEmpty()
        val segments = boundedNightReport?.segments.orEmpty()

        val archivedSleepRecords = allDailyRecords.values.mapNotNull { rec ->
            val bed = rec.bedtimeMinute ?: return@mapNotNull null
            val wake = rec.wakeupMinute ?: return@mapNotNull null
            val d = runCatching { LocalDate.parse(rec.date) }.getOrNull() ?: return@mapNotNull null
            if (AccountDataBoundaryStore.isDateBeforeBoundary(d, boundaryDate)) return@mapNotNull null
            ArchivedSleepRecord(wakeDate = d, bedtimeMinute = bed, wakeupMinute = wake)
        }

        val wakeDatesToRead = (
            datesNeedingSleep.map { it.plusDays(1) } +
                datesNeedingSleep +
                archivedSleepRecords.map { it.wakeDate }
            )
            .distinct()
            .map { it.toString() }
        val sleepConfirmations = runCatching {
            sleepConfirmationService(appCtx).readDates(wakeDatesToRead)
        }.getOrDefault(emptyMap())

        // 4. Personal baselines — 취침 탭(NightSummary)과 동일한 통합 수면 프로파일(요일별 추정·권장 취침) 사용
        val mergedSleep = CalendarDayCyclePolicy.mergeArchivedSleepData(
            liveSegments = segments,
            liveCandidates = candidates,
            archivedRecords = archivedSleepRecords,
            confirmations = sleepConfirmations,
            zone = zone
        )
        val weeklySleepProfile = SleepSummaryPolicy.estimateWeeklySleepProfile(
            confirmations = sleepConfirmations,
            candidates = mergedSleep.candidates,
            zone = zone
        )
        val globalWindow = weeklySleepProfile?.globalWindow
        val globalEstimatedBedtimeMin = globalWindow?.let { it.avgStartHour * 60 + it.avgStartMinute }
        val globalRecommendedBedtimeStartMin = globalWindow?.let {
            it.recommendedBedtimeStartHour * 60 + it.recommendedBedtimeStartMinute
        }
        val globalRecommendedBedtimeEndMin = globalWindow?.let {
            it.recommendedBedtimeEndHour * 60 + it.recommendedBedtimeEndMinute
        }

        // 데이터가 모자랄 때 사용할 목표 취침 시각 (직접 지정 취침 시각 -> 활성화된 취침 목표 순)
        val goalBedtimeMin: Int? = runCatching {
            val coachingSettings = UserCoachingSettingsStore(appCtx).loadSettings()
            if (coachingSettings.customBedtimeHour != null && coachingSettings.customBedtimeMinute != null) {
                coachingSettings.customBedtimeHour * 60 + coachingSettings.customBedtimeMinute
            } else {
                val goal = db.dao().goal("demo-guest", "BEDTIME")
                val detail = goal?.goalId?.let { db.dao().bedtimeGoal(it) }
                if (goal?.enabled == true && detail != null) detail.localMinuteOfDay else null
            }
        }.getOrNull()

        val baselineBedtimeMin = globalEstimatedBedtimeMin ?: goalBedtimeMin

        val activePhoneTotals = allDailyRecords.values.map { it.totalPhoneMs }.filter { it >= 10 * 60_000L }
        val baselinePhoneDailySampleDays = activePhoneTotals.size
        val baselinePhoneDailyMs = if (activePhoneTotals.isNotEmpty()) activePhoneTotals.average().toLong() else null

        val activeSfTotals = allDailyRecords.values.map { it.shortformMs }.filter { it >= 60_000L }
        val baselineSfDailyMs = if (activeSfTotals.isNotEmpty()) activeSfTotals.average().toLong() else null

        val validSpmSessions = storedSfSessions.filter { it.durationMs >= 30_000L && it.swipes > 0 }
        val baselineSpm = if (validSpmSessions.isNotEmpty()) {
            val totalSwipes = validSpmSessions.sumOf { it.swipes }.toDouble()
            val totalMin = validSpmSessions.sumOf { it.durationMs } / 60_000.0
            if (totalMin > 0.0) totalSwipes / totalMin else null
        } else null

        // 5. Phone sessions for the visible month (+ 1 day padding on both ends for sleep-cycle boundaries)
        val monthStart = viewMonth.atDay(1)
        val monthEnd = viewMonth.atEndOfMonth().coerceAtMost(today)
        val earliestTargetDate = if (needsCrossMonthYesterday) yesterday else monthStart
        val phoneSessionsByDate = if (!monthEnd.isBefore(today.minusDays(35))) {
            val queryStartDate = earliestTargetDate.minusDays(1).coerceAtLeast(today.minusDays(36))
            val startWall = AccountDataBoundaryStore.clampStartWallMs(
                queryStartDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                boundaryMs
            )
            val endWall = monthEnd.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
                .coerceAtMost(nowWallMs)
            if (startWall < endWall) {
                val events = runCatching {
                    AndroidRecordSource(appCtx).events(startWall, endWall)
                }.getOrDefault(emptyList())
                ShortformPersonalStatsStore.parsePhoneSessions(events).groupBy { it.date }
            } else {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        fun evaluateSingleDate(
            date: LocalDate,
            yesterdayGate: YesterdayGateState?
        ): EvaluatedDay {
            if (date.isAfter(today)) {
                val emptyAssessment = DailyHealthEvaluator.evaluate(
                    DailyHealthContext(date = date, isToday = false, isFinalized = true)
                )
                return EvaluatedDay(assessment = emptyAssessment, facts = null, isFinalized = true)
            }

            val isDateToday = date == today
            val isDateYesterday = date == yesterday

            // Gate today's calculation until yesterday has both finalized and been stamped
            if (isDateToday && yesterdayGate != null &&
                CalendarDayCyclePolicy.shouldDeferTodayCalculation(
                    yesterdayHasData = yesterdayGate.hasData,
                    isYesterdayFinalized = yesterdayGate.isFinalized,
                    isYesterdayStamped = yesterdayGate.isStamped
                )
            ) {
                val deferHeadline = if (!yesterdayGate.isFinalized) {
                    "어제(${yesterday.monthValue}/${yesterday.dayOfMonth}) 수면이 측정되고 스탬프를 찍으면 오늘 기록 계산이 시작돼요."
                } else {
                    "어제(${yesterday.monthValue}/${yesterday.dayOfMonth}) 스탬프를 찍으면 오늘 기록 계산이 시작돼요."
                }
                val deferredAssessment = DailyHealthAssessment(
                    date = date,
                    tier = DailyHealthTier.NO_DATA,
                    projectedTier = DailyHealthTier.NO_DATA,
                    score = 0,
                    headline = deferHeadline,
                    praises = emptyList(),
                    cautions = emptyList(),
                    axes = emptyList(),
                    isPartialData = false,
                    evaluatedAxesCount = 0,
                    totalAxesCount = 4
                )
                return EvaluatedDay(assessment = deferredAssessment, facts = null, isFinalized = false)
            }

            val dateKey = date.toString()
            val prevDate = date.minusDays(1)
            val prevDateKey = prevDate.toString()
            val nextDate = date.plusDays(1)
            val nextDateKey = nextDate.toString()
            val archiveRec = allDailyRecords[dateKey]
            val nextArchiveRec = allDailyRecords[nextDateKey]

            // Respect fresh-start boundary after account deletion / sign-out so old OS records don't leak back in
            if (AccountDataBoundaryStore.isDateBeforeBoundary(date, boundaryDate) &&
                archiveRec == null &&
                sfDailyByDate[dateKey].isNullOrEmpty() &&
                sfSessionsByDate[dateKey].isNullOrEmpty() &&
                sleepConfirmations[nextDateKey] == null
            ) {
                val emptyAssessment = DailyHealthEvaluator.evaluate(
                    DailyHealthContext(date = date, isToday = false, isFinalized = true)
                )
                return EvaluatedDay(assessment = emptyAssessment, facts = null, isFinalized = true)
            }

            // Resolve previous night's sleep (waking on `date`) and tonight's sleep (waking on `date + 1`)
            val prevNightSleep = CalendarDayCyclePolicy.resolveNightSleepForDate(
                date = prevDate,
                zone = zone,
                wakeConf = sleepConfirmations[dateKey],
                segments = segments,
                candidates = candidates,
                nextArchiveBedtimeMin = archiveRec?.bedtimeMinute,
                nextArchiveWakeupMin = archiveRec?.wakeupMinute,
                nextArchiveSleepStartWallMs = archiveRec?.sleepStartWallMs,
                nextArchiveSleepEndWallMs = archiveRec?.sleepEndWallMs,
                nextArchiveSleepDurationMinutes = archiveRec?.sleepDurationMinutes
            )
            val nightSleep = CalendarDayCyclePolicy.resolveNightSleepForDate(
                date = date,
                zone = zone,
                wakeConf = sleepConfirmations[nextDateKey],
                segments = segments,
                candidates = candidates,
                nextArchiveBedtimeMin = nextArchiveRec?.bedtimeMinute,
                nextArchiveWakeupMin = nextArchiveRec?.wakeupMinute,
                nextArchiveSleepStartWallMs = nextArchiveRec?.sleepStartWallMs,
                nextArchiveSleepEndWallMs = nextArchiveRec?.sleepEndWallMs,
                nextArchiveSleepDurationMinutes = nextArchiveRec?.sleepDurationMinutes
            )

            val isFinalized = CalendarDayCyclePolicy.isDateFinalized(
                date = date,
                today = today,
                nowTime = nowTime,
                nightSleep = nightSleep,
                isDateStamped = dateKey in stampedDates
            )

            // Strict sleep-cycle day partition [dayStartWallMs, dayEndWallMs)
            val cycleBounds = CalendarDayCyclePolicy.resolveDayCycleBounds(
                date = date,
                today = today,
                zone = zone,
                nowWallMs = nowWallMs,
                prevNightSleep = prevNightSleep,
                nightSleep = nightSleep,
                isDateFinalized = isFinalized,
                yesterdayHasData = yesterdayGate?.hasData ?: true
            )
            val cycleWindow = TimeWindowMs(cycleBounds.dayStartWallMs, cycleBounds.dayEndWallMs)

            // Phone sessions clipped strictly to `date`'s sleep-cycle bounds
            val candidatePhoneSessions = phoneSessionsByDate[prevDate].orEmpty() +
                phoneSessionsByDate[date].orEmpty() +
                phoneSessionsByDate[nextDate].orEmpty()
            val cyclePhoneSessionDurations = candidatePhoneSessions.mapNotNull { s ->
                val sStart = maxOf(s.startWallMs, cycleBounds.dayStartWallMs)
                val sEnd = minOf(s.endWallMs, cycleBounds.dayEndWallMs)
                val dur = sEnd - sStart
                if (dur > 0L) dur else null
            }
            val cyclePhoneSessionsSumMs = cyclePhoneSessionDurations.sum()
            val validPhoneSessions = cyclePhoneSessionDurations.filter { it >= MIN_PHONE_SESSION_MS }
            val phoneAvgSessionMs = if (validPhoneSessions.isNotEmpty()) {
                validPhoneSessions.average().toLong()
            } else {
                archiveRec?.phoneAvgSessionMs
            }
            val phoneMaxSessionMs = validPhoneSessions.maxOrNull() ?: archiveRec?.phoneMaxSessionMs

            // Phone total ms (never mixes previous night's 00:00..wake usage into `date`)
            val totalPhoneMs = when {
                isDateToday -> {
                    val reportCycleMs = if (todayReport != null && todayReport.values.size == 24) {
                        (CalendarDayCyclePolicy.computeReportMinutesInWindow(
                            date = date,
                            hourlyMinutes = todayReport.values,
                            window = cycleWindow,
                            zone = zone
                        ) * 60_000.0).toLong()
                    } else {
                        0L
                    }
                    maxOf(reportCycleMs, cyclePhoneSessionsSumMs)
                }
                cyclePhoneSessionsSumMs > 0L -> cyclePhoneSessionsSumMs
                archiveRec != null && archiveRec.totalPhoneMs > 0L -> archiveRec.totalPhoneMs
                else -> 0L
            }

            // Shortform metrics clipped strictly to `date`'s sleep-cycle bounds
            val daySfDailyRows = sfDailyByDate[dateKey].orEmpty()
            val daySfHourRows = sfHoursByDate[dateKey].orEmpty()
            val nextDaySfHourRows = sfHoursByDate[nextDateKey].orEmpty()
            val candidateSfSessions = sfSessionsByDate[prevDateKey].orEmpty() +
                sfSessionsByDate[dateKey].orEmpty() +
                sfSessionsByDate[nextDateKey].orEmpty()

            val cycleSfSessions = candidateSfSessions.mapNotNull { s ->
                val rawEnd = (s.startWallMs + s.durationMs).coerceAtLeast(s.updatedWallMs)
                val sStart = maxOf(s.startWallMs, cycleBounds.dayStartWallMs)
                val sEnd = minOf(rawEnd, cycleBounds.dayEndWallMs)
                val clippedDur = sEnd - sStart
                if (clippedDur > 0L) {
                    val rawSpan = (rawEnd - s.startWallMs).coerceAtLeast(1L)
                    val ratio = (clippedDur.toDouble() / rawSpan.toDouble()).coerceIn(0.0, 1.0)
                    s.copy(
                        startWallMs = sStart,
                        updatedWallMs = sEnd,
                        durationMs = clippedDur,
                        swipes = (s.swipes * ratio).roundToInt()
                    )
                } else {
                    null
                }
            }

            val sfHourBuckets = (
                daySfHourRows.map { HourlyUsageBucket(date, it.hour, it.durationMs) } +
                    nextDaySfHourRows.map { HourlyUsageBucket(nextDate, it.hour, it.durationMs) }
                )
            val sfMsFromCycleHours = CalendarDayCyclePolicy.computeHourlyBucketsOverlapMs(
                buckets = sfHourBuckets,
                window = cycleWindow,
                zone = zone
            )
            val sfMsFromCycleSessions = cycleSfSessions.sumOf { it.durationMs }
            val hasGranularSfSources = daySfHourRows.isNotEmpty() ||
                nextDaySfHourRows.isNotEmpty() ||
                candidateSfSessions.isNotEmpty()

            val sfMsFromDaily = daySfDailyRows.sumOf { it.durationMs }
            val shortformMs = if (hasGranularSfSources) {
                maxOf(sfMsFromCycleHours, sfMsFromCycleSessions)
            } else {
                maxOf(archiveRec?.shortformMs ?: 0L, sfMsFromDaily)
            }

            val swipesFromDaily = daySfDailyRows.sumOf { it.transitionCount }
            val swipesFromCycleSessions = cycleSfSessions.sumOf { it.swipes.toLong() }
            val shortformSwipes = if (hasGranularSfSources) {
                if (shortformMs > 0L) {
                    if (swipesFromCycleSessions > 0L) swipesFromCycleSessions
                    else maxOf(archiveRec?.shortformSwipes ?: 0L, swipesFromDaily)
                } else {
                    0L
                }
            } else {
                maxOf(archiveRec?.shortformSwipes ?: 0L, swipesFromDaily)
            }

            // Was shortform tracking active on this date?
            val hasShortformTracking = shortformMs > 0L ||
                daySfDailyRows.isNotEmpty() ||
                daySfHourRows.isNotEmpty() ||
                cycleSfSessions.isNotEmpty() ||
                (firstTrackedDate != null && !date.isBefore(firstTrackedDate))

            val sfAvgSessionMs = if (cycleSfSessions.isNotEmpty()) {
                ShortformPersonalStatsStore.calculateRepresentativeSessionMs(cycleSfSessions)
                    .takeIf { it > 0L }
            } else {
                null
            } ?: archiveRec?.shortformAvgSessionMs
            val sfMaxSessionMs = cycleSfSessions.maxOfOrNull { it.durationMs }
                ?: archiveRec?.shortformMaxSessionMs

            val sfSpm = if (shortformMs >= 30_000L && shortformSwipes > 0L) {
                shortformSwipes.toDouble() / (shortformMs / 60_000.0)
            } else {
                archiveRec?.shortformSpm
            }

            // 해당 밤(다음 날 기상 요일)에 맞는 취침 탭 기준(요일별 평소 추정·권장 취침 시간대 -> 전체 평균 -> 목표값) 산출
            val daySleepProfile = weeklySleepProfile?.dayProfiles?.get(nextDate.dayOfWeek)
            val dateEstimatedBedtimeMin = daySleepProfile?.let { it.avgStartHour * 60 + it.avgStartMinute }
                ?: globalEstimatedBedtimeMin
            val dateRecommendedBedtimeStartMin = daySleepProfile?.let {
                it.recommendedBedtimeHour * 60 + it.recommendedBedtimeMinute
            } ?: globalRecommendedBedtimeStartMin
            val dateRecommendedBedtimeEndMin = daySleepProfile?.let {
                it.recommendedBedtimeEndHour * 60 + it.recommendedBedtimeEndMinute
            } ?: globalRecommendedBedtimeEndMin
            val dateBaselineSleepDurationMin = daySleepProfile?.avgDurationMinutes?.toDouble()
                ?: globalWindow?.avgDurationMinutes?.toDouble()
            val datePreSleepFallbackBedtimeMin = dateRecommendedBedtimeStartMin
                ?: goalBedtimeMin
                ?: dateEstimatedBedtimeMin

            // 확정된 과거 날에 수면(>=3h 비사용)이 전혀 없고 실제 야간 활동이 감지되었거나 사용자가 수면 아님을 확인한 경우 -> 밤샘 판정
            val nightStartWallMs = date.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
            val nightEndWallMs = nextDate.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
            val nightCheckWindow = TimeWindowMs(nightStartWallMs, nightEndWallMs)
            val nightActivityMinutes = if (candidatePhoneSessions.isNotEmpty()) {
                CalendarDayCyclePolicy.computeIntervalsOverlapMinutes(
                    intervals = candidatePhoneSessions.map { it.startWallMs to it.endWallMs },
                    window = nightCheckWindow
                )
            } else {
                0.0
            }
            val nightActivitySessionCount = candidatePhoneSessions.count { s ->
                minOf(s.endWallMs, nightEndWallMs) > maxOf(s.startWallMs, nightStartWallMs)
            }
            val isAllNighter = isFinalized &&
                !isDateToday &&
                nightSleep.bedtimeMinute == null &&
                nightSleep.sleepDurationMinutes == null &&
                (nightSleep.hasExplicitNoSleep || nightActivityMinutes >= 10.0 || nightActivitySessionCount >= 2)

            // 권장 취침 시간대의 가장 앞 시각 기준 2시간 전부터 1시간 단위 점증 가중치(2~1h 전 35%, 1~0h 전 65%, 경과 100%) 적용
            val preSleepWeightedWindows = CalendarDayCyclePolicy.resolveWeightedPreSleepWindows(
                date = date,
                today = today,
                zone = zone,
                nowWallMs = nowWallMs,
                cycleBounds = cycleBounds,
                recommendedBedtimeStartMinute = dateRecommendedBedtimeStartMin,
                bedtimeStartWallMs = nightSleep.bedtimeStartWallMs,
                isDateFinalized = isFinalized,
                fallbackBedtimeMinute = datePreSleepFallbackBedtimeMin,
                hasExplicitNoSleep = nightSleep.hasExplicitNoSleep || isAllNighter
            )

            val rawPreSleepPhoneWeighted = if (isAllNighter) {
                null
            } else {
                computeWeightedPreSleepPhoneUsage(
                    today = today,
                    zone = zone,
                    isDateToday = isDateToday,
                    isDateYesterday = isDateYesterday,
                    isDateFinalized = isFinalized,
                    windows = preSleepWeightedWindows,
                    candidateSessions = candidatePhoneSessions,
                    todayReport = todayReport
                )
            }
            val rawPreSleepUsageMin = if (isAllNighter) {
                null
            } else {
                rawPreSleepPhoneWeighted?.weightedMinutes ?: archiveRec?.preSleepUsageMinutes
            }

            val rawPreSleepSfWeighted = if (isAllNighter) {
                null
            } else {
                computeWeightedPreSleepShortformUsage(
                    zone = zone,
                    bedtimeStartWallMs = nightSleep.bedtimeStartWallMs,
                    windows = preSleepWeightedWindows,
                    sfHourBuckets = sfHourBuckets,
                    candidateSfSessions = candidateSfSessions,
                    hasShortformTracking = hasShortformTracking
                )
            }
            val preSleepShortformMin = if (isAllNighter) {
                null
            } else {
                rawPreSleepSfWeighted?.weightedMinutes ?: archiveRec?.preSleepShortformMinutes
            }?.coerceIn(0.0, 60.0)

            // 취침 전 총 화면 사용량은 물리적으로 같은 구간의 숏폼 사용량 이상이어야 함
            val preSleepUsageMin = when {
                rawPreSleepUsageMin != null && preSleepShortformMin != null ->
                    maxOf(rawPreSleepUsageMin, preSleepShortformMin).coerceIn(0.0, 60.0)
                rawPreSleepUsageMin != null -> rawPreSleepUsageMin.coerceIn(0.0, 60.0)
                preSleepShortformMin != null && preSleepShortformMin > 0.0 -> preSleepShortformMin
                else -> null
            }
            val isWeightedPreSleepComputed = rawPreSleepPhoneWeighted != null || rawPreSleepSfWeighted != null
            val preSleepWeightedPercent = preSleepUsageMin?.let {
                ((it / 60.0) * 100.0).roundToInt().coerceIn(0, 100)
            }

            val isRestDay = archiveRec?.dayType == "REST_DAY" ||
                date.dayOfWeek == DayOfWeek.SATURDAY ||
                date.dayOfWeek == DayOfWeek.SUNDAY

            val ctx = DailyHealthContext(
                date = date,
                isToday = isDateToday,
                isFinalized = isFinalized,
                bedtimeMinute = nightSleep.bedtimeMinute,
                wakeupMinute = nightSleep.wakeupMinute,
                sleepDurationMinutes = nightSleep.sleepDurationMinutes,
                baselineBedtimeMinute = dateEstimatedBedtimeMin,
                recommendedBedtimeMinute = dateRecommendedBedtimeStartMin,
                recommendedBedtimeEndMinute = dateRecommendedBedtimeEndMin,
                goalBedtimeMinute = goalBedtimeMin,
                baselineSleepDurationMinutes = dateBaselineSleepDurationMin,
                preSleepUsageMinutes = preSleepUsageMin,
                preSleepShortformMinutes = preSleepShortformMin,
                isWeightedPreSleep = isWeightedPreSleepComputed,
                preSleepWeightedPercent = preSleepWeightedPercent,
                isAllNighter = isAllNighter,
                hasShortformTracking = hasShortformTracking,
                shortformTotalMs = shortformMs,
                shortformSwipes = shortformSwipes,
                shortformAvgSessionMs = sfAvgSessionMs,
                shortformMaxSessionMs = sfMaxSessionMs,
                shortformSpm = sfSpm,
                baselineShortformDailyMs = baselineSfDailyMs,
                baselineSpm = baselineSpm,
                totalPhoneMs = totalPhoneMs,
                phoneAvgSessionMs = phoneAvgSessionMs,
                phoneMaxSessionMs = phoneMaxSessionMs,
                phoneShortformRatio = if (hasShortformTracking && totalPhoneMs > 0L) {
                    (shortformMs.toDouble() / totalPhoneMs.toDouble()).coerceIn(0.0, 1.0)
                } else null,
                baselinePhoneDailyMs = baselinePhoneDailyMs,
                baselinePhoneDailySampleDays = baselinePhoneDailySampleDays,
                isRestDay = isRestDay
            )

            val assessment = DailyHealthEvaluator.evaluate(ctx)
            val facts = DayResolvedFacts(
                date = date,
                totalPhoneMs = totalPhoneMs,
                shortformMs = shortformMs,
                shortformSwipes = shortformSwipes,
                shortformAvgSessionMs = sfAvgSessionMs,
                shortformMaxSessionMs = sfMaxSessionMs,
                shortformSpm = sfSpm,
                phoneAvgSessionMs = phoneAvgSessionMs,
                phoneMaxSessionMs = phoneMaxSessionMs,
                preSleepUsageMinutes = preSleepUsageMin,
                preSleepShortformMinutes = preSleepShortformMin,
                bedtimeMinute = nightSleep.bedtimeMinute,
                wakeupMinute = nightSleep.wakeupMinute,
                sleepDurationMinutes = nightSleep.sleepDurationMinutes,
                matchedNightSegment = nightSleep.matchedSegment,
                isRestDay = isRestDay
            )
            return EvaluatedDay(assessment = assessment, facts = facts, isFinalized = isFinalized)
        }

        // 6. Evaluate each date in the visible month (pre-evaluating cross-month yesterday when today is day 1)
        var yesterdayGate: YesterdayGateState? = if (needsCrossMonthYesterday) {
            val evaluatedYesterday = evaluateSingleDate(yesterday, yesterdayGate = null)
            YesterdayGateState(
                hasData = evaluatedYesterday.assessment.tier != DailyHealthTier.NO_DATA,
                isFinalized = evaluatedYesterday.isFinalized,
                isStamped = yesterday.toString() in stampedDates
            )
        } else {
            null
        }

        val assessments = linkedMapOf<LocalDate, DailyHealthAssessment>()
        val factsMap = linkedMapOf<LocalDate, DayResolvedFacts>()

        for (date in monthDates) {
            val evaluated = evaluateSingleDate(date, yesterdayGate = if (date == today) yesterdayGate else null)
            assessments[date] = evaluated.assessment
            if (evaluated.facts != null) {
                factsMap[date] = evaluated.facts
            }
            if (date == yesterday) {
                yesterdayGate = YesterdayGateState(
                    hasData = evaluated.assessment.tier != DailyHealthTier.NO_DATA,
                    isFinalized = evaluated.isFinalized,
                    isStamped = yesterday.toString() in stampedDates
                )
            }
        }

        // 6.5 Persist resolved sleep-cycle metrics into the 6-month on-device archive
        if (factsMap.isNotEmpty()) {
            runCatching {
                MonthlyUsageArchiveStore.upsertResolvedDayFacts(
                    context = appCtx,
                    factsMap = factsMap,
                    today = today
                )
            }
        }

        // 7. Compute monthly medal & warning tally
        val finalValues = assessments.values.filter {
            it.tier != DailyHealthTier.NO_DATA && it.tier != DailyHealthTier.IN_PROGRESS
        }
        val tally = MonthlyHealthTally(
            yearMonth = viewMonth,
            goldCount = finalValues.count { it.tier == DailyHealthTier.GOLD },
            silverCount = finalValues.count { it.tier == DailyHealthTier.SILVER },
            bronzeCount = finalValues.count { it.tier == DailyHealthTier.BRONZE },
            warningCount = finalValues.count { it.tier == DailyHealthTier.WARNING },
            criticalCount = finalValues.count { it.tier == DailyHealthTier.CRITICAL },
            inProgressCount = assessments.values.count { it.tier == DailyHealthTier.IN_PROGRESS },
            evaluatedFinalDays = finalValues.size,
            averageScore = if (finalValues.isNotEmpty()) finalValues.map { it.score }.average().toInt() else 0
        )

        val bundle = CalendarMonthHealthBundle(
            viewMonth = viewMonth,
            assessments = assessments,
            facts = factsMap,
            tally = tally,
            baselineBedtimeMinute = baselineBedtimeMin,
            nightReport = boundedNightReport
        )
        preloadedMonthCache = PreloadedMonthCache(
            viewMonth = viewMonth,
            today = today,
            stampedDates = stampedDates,
            bundle = bundle,
            cachedAtMs = System.currentTimeMillis()
        )
        bundle
    }

    private fun computeWeightedPreSleepPhoneUsage(
        today: LocalDate,
        zone: ZoneId,
        isDateToday: Boolean,
        isDateYesterday: Boolean,
        isDateFinalized: Boolean,
        windows: PreSleepWeightedWindows?,
        candidateSessions: List<ShortformPersonalStatsStore.PhoneSessionRecord>,
        todayReport: Report?
    ): WeightedPreSleepUsage? {
        val w = windows ?: return null

        if (candidateSessions.isNotEmpty()) {
            return CalendarDayCyclePolicy.computeWeightedIntervalsUsage(
                intervals = candidateSessions.map { it.startWallMs to it.endWallMs },
                windows = w
            )
        }

        // Safe fallback using ONLY hours strictly inside `windows` (NEVER today 00:00..02:59 for today!)
        if ((isDateToday || (isDateYesterday && !isDateFinalized)) &&
            todayReport != null &&
            todayReport.values.size == 24
        ) {
            return CalendarDayCyclePolicy.computeWeightedReportUsage(
                date = today,
                hourlyMinutes = todayReport.values,
                windows = w,
                zone = zone
            )
        }
        return null
    }

    private fun computeWeightedPreSleepShortformUsage(
        zone: ZoneId,
        bedtimeStartWallMs: Long?,
        windows: PreSleepWeightedWindows?,
        sfHourBuckets: List<HourlyUsageBucket>,
        candidateSfSessions: List<ShortformPersonalStatsStore.SessionRecord>,
        hasShortformTracking: Boolean
    ): WeightedPreSleepUsage? {
        if (!hasShortformTracking) return null
        val w = windows ?: return null

        if (bedtimeStartWallMs != null && candidateSfSessions.isNotEmpty()) {
            val intervals = candidateSfSessions.map { s ->
                s.startWallMs to (s.startWallMs + s.durationMs).coerceAtLeast(s.updatedWallMs)
            }
            return CalendarDayCyclePolicy.computeWeightedIntervalsUsage(intervals, w)
        }

        if (sfHourBuckets.isNotEmpty()) {
            return CalendarDayCyclePolicy.computeWeightedHourlyBucketsUsage(
                buckets = sfHourBuckets,
                windows = w,
                zone = zone
            )
        }

        if (candidateSfSessions.isNotEmpty()) {
            val intervals = candidateSfSessions.map { s ->
                s.startWallMs to (s.startWallMs + s.durationMs).coerceAtLeast(s.updatedWallMs)
            }
            return CalendarDayCyclePolicy.computeWeightedIntervalsUsage(intervals, w)
        }

        return WeightedPreSleepUsage(
            outerMinutes = 0.0,
            innerMinutes = 0.0,
            postStartMinutes = 0.0,
            weightedMinutes = 0.0,
            weightedPercent = 0
        )
    }
}
