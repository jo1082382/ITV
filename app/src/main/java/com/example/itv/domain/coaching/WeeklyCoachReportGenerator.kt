package com.example.itv.domain.coaching

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Generates warm, non-judgmental weekly life-coach letters comparing the completed Mon~Sun week
 * against the preceding week, surfacing concrete improvements ("지난주 대비 나아진 점") and
 * behavioral rhythm insights ("생활 패턴 발견").
 */
internal object WeeklyCoachReportGenerator {

    private const val MINUTES_PER_MS = 60_000.0
    private const val MIN_ACTIVE_DAY_PHONE_MS = 5 * 60_000L // 5 minutes

    /**
     * Returns the Monday of the most recently completed Mon~Sun week as of [today].
     * - On Monday 00:00+, this immediately advances to the week that ended Sunday 23:59.
     * - On Tue~Sun, this remains the previous week's Monday until next Monday arrives.
     */
    fun latestCompletedWeekStart(today: LocalDate): LocalDate {
        val thisWeekMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return thisWeekMonday.minusWeeks(1)
    }

    /**
     * Returns the upcoming Monday date when the next weekly letter will arrive.
     */
    fun nextDeliveryMonday(today: LocalDate): LocalDate {
        val thisWeekMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return thisWeekMonday.plusWeeks(1)
    }

    fun letterIdForWeekStart(weekStart: LocalDate): String = "week-$weekStart"

    /**
     * Builds a weekly coach letter for the week starting on [weekStartDate] (Monday..Sunday).
     * Returns null if there is no meaningful recorded data during that week.
     */
    fun generateLetter(
        weekStartDate: LocalDate,
        snapshotsByDate: Map<LocalDate, WeeklyDaySnapshot>,
        deliveredAtMs: Long = System.currentTimeMillis(),
        isRead: Boolean = false,
        isPreview: Boolean = false,
    ): WeeklyCoachLetter? {
        val monday = weekStartDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sunday = monday.plusDays(6)
        val deliveredMonday = monday.plusWeeks(1)

        val currentDays = (0L..6L).mapNotNull { offset ->
            snapshotsByDate[monday.plusDays(offset)]
        }.filter { isActiveDay(it) }

        if (currentDays.isEmpty()) return null

        val prevMonday = monday.minusWeeks(1)
        val prevDays = (0L..6L).mapNotNull { offset ->
            snapshotsByDate[prevMonday.plusDays(offset)]
        }.filter { isActiveDay(it) }

        val currentAgg = aggregateWeek(currentDays)
        val prevAgg = if (prevDays.isNotEmpty()) aggregateWeek(prevDays) else null

        val improvements = buildImprovements(currentDays, currentAgg, prevAgg)
        val discoveries = buildDiscoveries(currentDays, currentAgg)
        val (tipTitle, tipBody) = buildCoachTip(currentAgg, discoveries, prevAgg)

        val savedWeeklyMin = if (prevAgg != null && prevAgg.avgDailyPhoneMin > currentAgg.avgDailyPhoneMin + 5.0) {
            ((prevAgg.avgDailyPhoneMin - currentAgg.avgDailyPhoneMin) * currentDays.size).roundToInt()
        } else {
            null
        }
        val reclaimedExample = savedWeeklyMin?.let(::formatReclaimedTimeExample)

        val weekOfMonth = ((monday.dayOfMonth - 1) / 7) + 1
        val title = "${monday.monthValue}월 ${weekOfMonth}주차 코치 편지"
        val previewTeaser = improvements.firstOrNull()?.let { "${it.title} (${it.badgeText})" }
            ?: discoveries.firstOrNull()?.title
            ?: "지난 한 주 생활 패턴이 도착했어요"

        val greeting = buildGreeting(currentAgg, prevAgg, improvements, discoveries, savedWeeklyMin)

        return WeeklyCoachLetter(
            id = letterIdForWeekStart(monday),
            weekStartDate = monday,
            weekEndDate = sunday,
            deliveredMondayDate = deliveredMonday,
            title = title,
            previewTeaser = previewTeaser,
            greeting = greeting,
            improvements = improvements,
            discoveries = discoveries,
            coachTipTitle = tipTitle,
            coachTipBody = tipBody,
            stats = WeeklyComparisonStats(
                activeDays = currentDays.size,
                avgDailyPhoneMinutes = currentAgg.avgDailyPhoneMin.roundToInt(),
                prevAvgDailyPhoneMinutes = prevAgg?.avgDailyPhoneMin?.roundToInt(),
                avgDailyShortformMinutes = currentAgg.avgDailyShortformMin.roundToInt(),
                prevAvgDailyShortformMinutes = prevAgg?.avgDailyShortformMin?.roundToInt(),
                avgBedtimeMinute = currentAgg.avgBedtimeLinearMin?.let(::normalizeClockMinute),
                avgSleepMinutes = currentAgg.avgSleepMin?.roundToInt(),
                savedWeeklyMinutes = savedWeeklyMin,
                reclaimedTimeExample = reclaimedExample,
            ),
            deliveredAtMs = deliveredAtMs,
            isRead = isRead,
            isPreview = isPreview,
        )
    }

    /**
     * Generates a rich sample preview letter for the developer/team Test tab when testing UI
     * before a real Monday rollover or on an emulator with sparse history.
     */
    fun generateSamplePreviewLetter(today: LocalDate = LocalDate.now()): WeeklyCoachLetter {
        val targetMonday = latestCompletedWeekStart(today)
        val prevMonday = targetMonday.minusWeeks(1)
        val sampleMap = mutableMapOf<LocalDate, WeeklyDaySnapshot>()

        // Previous week (slightly heavier late-night & shortform usage)
        for (i in 0..6) {
            val d = prevMonday.plusDays(i.toLong())
            val isRest = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
            val hourly = MutableList(24) { h ->
                when (h) {
                    in 0..1 -> 18.0
                    in 8..9 -> 14.0
                    in 12..13 -> 16.0
                    in 14..16 -> 5.0
                    in 21..23 -> if (isRest) 32.0 else 28.0
                    else -> 6.0
                }
            }
            sampleMap[d] = WeeklyDaySnapshot(
                date = d,
                totalPhoneMs = ((if (isRest) 295 else 245) * 60_000L),
                shortformMs = ((if (isRest) 88 else 64) * 60_000L),
                shortformSwipes = if (isRest) 210L else 160L,
                bedtimeMinute = if (i % 2 == 0) 24 * 60 + 45 else 23 * 60 + 25,
                wakeupMinute = 7 * 60 + 30,
                sleepDurationMinutes = 405.0,
                preSleepUsageMinutes = 42.0,
                preSleepShortformMinutes = 26.0,
                phoneSessionCount = 46,
                phoneAvgSessionMs = 5 * 60_000L,
                shortformSessionCount = 4,
                shortformAvgSessionMs = 19 * 60_000L,
                shortformMaxSessionMs = 34 * 60_000L,
                shortformSpm = 2.5,
                hourlyPhoneMinutes = hourly,
                topApps = listOf(
                    WeeklyAppStat("com.instagram.android", "Instagram", 68.0),
                    WeeklyAppStat("com.google.android.youtube", "YouTube", 54.0),
                    WeeklyAppStat("com.kakao.talk", "카카오톡", 36.0),
                ),
                isRestDay = isRest,
            )
        }

        // Completed week (noticeable, realistic improvements)
        for (i in 0..6) {
            val d = targetMonday.plusDays(i.toLong())
            val isRest = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
            val hourly = MutableList(24) { h ->
                when (h) {
                    in 0..1 -> 7.0
                    in 8..9 -> 12.0
                    in 12..13 -> 14.0
                    in 14..16 -> 4.0
                    in 22..23 -> if (isRest) 29.0 else 23.0
                    else -> 5.0
                }
            }
            sampleMap[d] = WeeklyDaySnapshot(
                date = d,
                totalPhoneMs = ((if (isRest) 250 else 198) * 60_000L),
                shortformMs = ((if (isRest) 62 else 40) * 60_000L),
                shortformSwipes = if (isRest) 130L else 85L,
                bedtimeMinute = if (i % 2 == 0) 23 * 60 + 40 else 23 * 60 + 55,
                wakeupMinute = 7 * 60 + 20,
                sleepDurationMinutes = 450.0,
                preSleepUsageMinutes = 22.0,
                preSleepShortformMinutes = 9.0,
                phoneSessionCount = 34,
                phoneAvgSessionMs = 5 * 60_000L,
                shortformSessionCount = 4,
                shortformAvgSessionMs = 11 * 60_000L,
                shortformMaxSessionMs = 21 * 60_000L,
                shortformSpm = 2.0,
                hourlyPhoneMinutes = hourly,
                topApps = listOf(
                    WeeklyAppStat("com.google.android.youtube", "YouTube", 52.0),
                    WeeklyAppStat("com.instagram.android", "Instagram", 43.0),
                    WeeklyAppStat("com.kakao.talk", "카카오톡", 34.0),
                ),
                isRestDay = isRest,
            )
        }

        return generateLetter(
            weekStartDate = targetMonday,
            snapshotsByDate = sampleMap,
            deliveredAtMs = System.currentTimeMillis(),
            isRead = false,
            isPreview = true,
        )!!
    }

    /**
     * Generates a realistic multi-week mailbox sample (1 newest unread sealed letter + 2 older
     * already-read open letters) for previewing the exact [WeeklyMailboxDialog] in the Test tab.
     */
    fun generateSampleMailboxLetters(today: LocalDate = LocalDate.now()): List<WeeklyCoachLetter> {
        val targetMonday = latestCompletedWeekStart(today)
        val nowMs = System.currentTimeMillis()
        val weekMs = 7L * 24L * 60L * 60L * 1000L
        val sampleMap = mutableMapOf<LocalDate, WeeklyDaySnapshot>()

        // Populate 4 consecutive weeks (wOffset = 3 oldest -> 0 newest completed week)
        // so all 3 generated letters have authentic week-over-week comparisons & distinct patterns.
        for (wOffset in 3 downTo 0) {
            val monday = targetMonday.minusWeeks(wOffset.toLong())
            for (i in 0..6) {
                val d = monday.plusDays(i.toLong())
                val isRest = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
                val hourly = MutableList(24) { h ->
                    when (wOffset) {
                        3 -> when (h) {
                            in 0..1 -> 22.0
                            in 8..9 -> 16.0
                            in 12..13 -> 18.0
                            in 14..16 -> 8.0
                            in 21..23 -> if (isRest) 35.0 else 31.0
                            else -> 7.0
                        }
                        2 -> when (h) {
                            in 0..1 -> 15.0
                            in 8..9 -> 13.0
                            in 12..13 -> 17.0
                            in 14..16 -> 6.0
                            in 21..23 -> if (isRest) 31.0 else 26.0
                            else -> 6.0
                        }
                        1 -> when (h) {
                            in 0..1 -> 18.0
                            in 8..9 -> 14.0
                            in 12..13 -> 16.0
                            in 14..16 -> 5.0
                            in 21..23 -> if (isRest) 32.0 else 28.0
                            else -> 6.0
                        }
                        else -> when (h) {
                            in 0..1 -> 7.0
                            in 8..9 -> 12.0
                            in 12..13 -> 14.0
                            in 14..16 -> 4.0
                            in 22..23 -> if (isRest) 29.0 else 23.0
                            else -> 5.0
                        }
                    }
                }
                val totalMin = when (wOffset) {
                    3 -> if (isRest) 315 else 268
                    2 -> if (isRest) 275 else 232
                    1 -> if (isRest) 295 else 245
                    else -> if (isRest) 250 else 198
                }
                val sfMin = when (wOffset) {
                    3 -> if (isRest) 98 else 74
                    2 -> if (isRest) 76 else 55
                    1 -> if (isRest) 88 else 64
                    else -> if (isRest) 62 else 40
                }
                val preSleepMin = when (wOffset) {
                    3 -> 48.0
                    2 -> 34.0
                    1 -> 42.0
                    else -> 22.0
                }
                val preSleepSfMin = when (wOffset) {
                    3 -> 30.0
                    2 -> 18.0
                    1 -> 26.0
                    else -> 9.0
                }
                val bedtimeMin = when (wOffset) {
                    3 -> if (i % 2 == 0) 25 * 60 + 5 else 24 * 60 + 15
                    2 -> if (i % 2 == 0) 24 * 60 + 10 else 23 * 60 + 45
                    1 -> if (i % 2 == 0) 24 * 60 + 45 else 23 * 60 + 25
                    else -> if (i % 2 == 0) 23 * 60 + 40 else 23 * 60 + 55
                }
                val sleepMin = when (wOffset) {
                    3 -> 390.0
                    2 -> 425.0
                    1 -> 405.0
                    else -> 450.0
                }
                val sessions = when (wOffset) {
                    3 -> 52
                    2 -> 40
                    1 -> 46
                    else -> 34
                }
                val sfAvgMin = when (wOffset) {
                    3 -> 22L
                    2 -> 15L
                    1 -> 19L
                    else -> 11L
                }
                val sfMaxMin = when (wOffset) {
                    3 -> 41L
                    2 -> 28L
                    1 -> 34L
                    else -> 21L
                }
                sampleMap[d] = WeeklyDaySnapshot(
                    date = d,
                    totalPhoneMs = totalMin * 60_000L,
                    shortformMs = sfMin * 60_000L,
                    shortformSwipes = (sfMin * 2.2).toLong(),
                    bedtimeMinute = bedtimeMin,
                    wakeupMinute = 7 * 60 + 20,
                    sleepDurationMinutes = sleepMin,
                    preSleepUsageMinutes = preSleepMin,
                    preSleepShortformMinutes = preSleepSfMin,
                    phoneSessionCount = sessions,
                    phoneAvgSessionMs = 5 * 60_000L,
                    shortformSessionCount = 4,
                    shortformAvgSessionMs = sfAvgMin * 60_000L,
                    shortformMaxSessionMs = sfMaxMin * 60_000L,
                    shortformSpm = if (wOffset == 0) 2.0 else 2.5,
                    hourlyPhoneMinutes = hourly,
                    topApps = listOf(
                        WeeklyAppStat("com.google.android.youtube", "YouTube", if (wOffset == 0) 52.0 else 58.0),
                        WeeklyAppStat("com.instagram.android", "Instagram", if (wOffset == 0) 43.0 else 66.0),
                        WeeklyAppStat("com.kakao.talk", "카카오톡", 34.0),
                    ),
                    isRestDay = isRest,
                )
            }
        }

        return listOf(0, 1, 2).mapNotNull { wOffset ->
            generateLetter(
                weekStartDate = targetMonday.minusWeeks(wOffset.toLong()),
                snapshotsByDate = sampleMap,
                deliveredAtMs = nowMs - wOffset * weekMs,
                isRead = wOffset > 0,
                isPreview = false,
            )
        }
    }

    private fun isActiveDay(day: WeeklyDaySnapshot): Boolean {
        return day.totalPhoneMs >= MIN_ACTIVE_DAY_PHONE_MS ||
            day.shortformMs > 0L ||
            day.sleepDurationMinutes != null ||
            day.hourlyPhoneMinutes.any { it > 0.0 }
    }

    private data class WeekAggregate(
        val activeDays: Int,
        val avgDailyPhoneMin: Double,
        val avgDailyShortformMin: Double,
        val avgShortformSessionMin: Double?,
        val avgShortformSpm: Double?,
        val avgPhoneSessionsPerDay: Double?,
        val avgPreSleepPhoneMin: Double?,
        val avgPreSleepShortformMin: Double?,
        val avgBedtimeLinearMin: Double?,
        val bedtimeStdDevMin: Double?,
        val avgSleepMin: Double?,
        val hourlyTotals: DoubleArray,
        val hasHourly: Boolean,
        val workdayAvgPhoneMin: Double?,
        val restdayAvgPhoneMin: Double?,
        val workdayAvgShortformMin: Double?,
        val restdayAvgShortformMin: Double?,
        val topApps: List<WeeklyAppStat>,
    )

    private fun aggregateWeek(days: List<WeeklyDaySnapshot>): WeekAggregate {
        val avgPhone = days.map { it.totalPhoneMs / MINUTES_PER_MS }.average()
        val avgShortform = days.map { it.shortformMs / MINUTES_PER_MS }.average()

        val sfSessionMins = days.mapNotNull { it.shortformAvgSessionMs?.takeIf { ms -> ms > 0L }?.let { ms -> ms / MINUTES_PER_MS } }
        val avgSfSession = sfSessionMins.takeIf { it.isNotEmpty() }?.average()

        val spmValues = days.mapNotNull { it.shortformSpm?.takeIf { v -> v > 0.0 } }
        val avgSpm = spmValues.takeIf { it.isNotEmpty() }?.average()

        val phoneSessionCounts = days.map { it.phoneSessionCount }.filter { it > 0 }
        val avgPhoneSessions = phoneSessionCounts.takeIf { it.isNotEmpty() }?.average()

        val preSleepPhone = days.mapNotNull { it.preSleepUsageMinutes?.takeIf { v -> v >= 0.0 } }
        val avgPreSleepPhone = preSleepPhone.takeIf { it.isNotEmpty() }?.average()

        val preSleepSf = days.mapNotNull { it.preSleepShortformMinutes?.takeIf { v -> v >= 0.0 } }
        val avgPreSleepSf = preSleepSf.takeIf { it.isNotEmpty() }?.average()

        // Linearize bedtime around midnight (e.g. 01:00 -> 25:00 = 1500m, 23:30 -> 1410m)
        val bedtimes = days.mapNotNull { it.bedtimeMinute?.let(::toLinearBedtimeMinute) }
        val avgBedtime = bedtimes.takeIf { it.isNotEmpty() }?.average()
        val bedtimeStd = if (bedtimes.size >= 3 && avgBedtime != null) {
            sqrt(bedtimes.map { (it - avgBedtime) * (it - avgBedtime) }.average())
        } else {
            null
        }

        val sleepMins = days.mapNotNull { it.sleepDurationMinutes?.takeIf { m -> m in 120.0..960.0 } }
        val avgSleep = sleepMins.takeIf { it.isNotEmpty() }?.average()

        val hourlyTotals = DoubleArray(24)
        var hasHourly = false
        days.forEach { d ->
            if (d.hourlyPhoneMinutes.size == 24) {
                d.hourlyPhoneMinutes.forEachIndexed { h, mins ->
                    if (mins > 0.0) {
                        hourlyTotals[h] += mins
                        hasHourly = true
                    }
                }
            }
        }

        val workdays = days.filter { !it.isRestDay }
        val restdays = days.filter { it.isRestDay }

        val appAccum = LinkedHashMap<String, Pair<String, Double>>()
        days.forEach { d ->
            d.topApps.forEach { app ->
                if (app.minutes > 0.0) {
                    val prev = appAccum[app.packageName]
                    appAccum[app.packageName] = (prev?.first ?: app.label) to ((prev?.second ?: 0.0) + app.minutes)
                }
            }
        }
        val topApps = appAccum.entries
            .map { (pkg, pair) -> WeeklyAppStat(pkg, pair.first, pair.second) }
            .sortedByDescending { it.minutes }
            .take(5)

        return WeekAggregate(
            activeDays = days.size,
            avgDailyPhoneMin = avgPhone,
            avgDailyShortformMin = avgShortform,
            avgShortformSessionMin = avgSfSession,
            avgShortformSpm = avgSpm,
            avgPhoneSessionsPerDay = avgPhoneSessions,
            avgPreSleepPhoneMin = avgPreSleepPhone,
            avgPreSleepShortformMin = avgPreSleepSf,
            avgBedtimeLinearMin = avgBedtime,
            bedtimeStdDevMin = bedtimeStd,
            avgSleepMin = avgSleep,
            hourlyTotals = hourlyTotals,
            hasHourly = hasHourly,
            workdayAvgPhoneMin = workdays.takeIf { it.isNotEmpty() }?.map { it.totalPhoneMs / MINUTES_PER_MS }?.average(),
            restdayAvgPhoneMin = restdays.takeIf { it.isNotEmpty() }?.map { it.totalPhoneMs / MINUTES_PER_MS }?.average(),
            workdayAvgShortformMin = workdays.takeIf { it.isNotEmpty() }?.map { it.shortformMs / MINUTES_PER_MS }?.average(),
            restdayAvgShortformMin = restdays.takeIf { it.isNotEmpty() }?.map { it.shortformMs / MINUTES_PER_MS }?.average(),
            topApps = topApps,
        )
    }

    private fun buildImprovements(
        currentDays: List<WeeklyDaySnapshot>,
        cur: WeekAggregate,
        prev: WeekAggregate?,
    ): List<WeeklyImprovementHighlight> {
        val list = mutableListOf<Pair<Double, WeeklyImprovementHighlight>>()

        if (prev != null) {
            // 1. Total phone usage decreased
            val phoneDiff = prev.avgDailyPhoneMin - cur.avgDailyPhoneMin
            if (phoneDiff >= 10.0) {
                val weeklySaved = (phoneDiff * cur.activeDays).roundToInt()
                val lifeEquiv = formatReclaimedTimeExample(weeklySaved)
                list += (phoneDiff * 1.4) to WeeklyImprovementHighlight(
                    id = "win_phone_total",
                    title = "폰 사용 시간 감소",
                    badgeText = "-${formatMinutesShort(phoneDiff.roundToInt())}/일",
                    detail = "지난주 하루 평균 ${formatMinutesShort(prev.avgDailyPhoneMin.roundToInt())}에서 " +
                        "이번 주 ${formatMinutesShort(cur.avgDailyPhoneMin.roundToInt())}로 줄었어요. " +
                        "한 주 동안 ${formatMinutesShort(weeklySaved)}을 되찾았어요 ($lifeEquiv 분량)!"
                )
            }

            // 2. Shortform daily duration decreased
            val sfDiff = prev.avgDailyShortformMin - cur.avgDailyShortformMin
            if (sfDiff >= 6.0 && prev.avgDailyShortformMin >= 10.0) {
                val weeklySfSaved = (sfDiff * cur.activeDays).roundToInt()
                list += (sfDiff * 1.9) to WeeklyImprovementHighlight(
                    id = "win_shortform_total",
                    title = "숏폼 시청 감소",
                    badgeText = "-${formatMinutesShort(sfDiff.roundToInt())}/일",
                    detail = "일평균 숏폼 시청이 ${formatMinutesShort(prev.avgDailyShortformMin.roundToInt())} → " +
                        "${formatMinutesShort(cur.avgDailyShortformMin.roundToInt())}로 감소해, " +
                        "한 주간 약 ${formatMinutesShort(weeklySfSaved)}의 여유를 만들었어요."
                )
            }

            // 3. Shortform session length got shorter ("stopping power")
            if (cur.avgShortformSessionMin != null && prev.avgShortformSessionMin != null) {
                val sessDiff = prev.avgShortformSessionMin - cur.avgShortformSessionMin
                if (sessDiff >= 2.5) {
                    list += (sessDiff * 4.0) to WeeklyImprovementHighlight(
                        id = "win_shortform_session",
                        title = "숏폼 끊는 힘 향상",
                        badgeText = "세션 -${sessDiff.roundToInt()}분",
                        detail = "한 번 숏폼을 켤 때 평균 시청 길이가 ${prev.avgShortformSessionMin.roundToInt()}분에서 " +
                            "${cur.avgShortformSessionMin.roundToInt()}분으로 짧아졌어요. 끌려가지 않고 스스로 멈춘 횟수가 늘었다는 뜻이에요."
                    )
                }
            }

            // 4. Pre-sleep shortform or pre-sleep phone decreased
            if (cur.avgPreSleepShortformMin != null && prev.avgPreSleepShortformMin != null) {
                val preSfDiff = prev.avgPreSleepShortformMin - cur.avgPreSleepShortformMin
                if (preSfDiff >= 4.0) {
                    list += (preSfDiff * 3.2) to WeeklyImprovementHighlight(
                        id = "win_presleep_sf",
                        title = "취침 전 숏폼 감소",
                        badgeText = "-${preSfDiff.roundToInt()}분",
                        detail = "취침 직전 숏폼 시간이 평균 ${prev.avgPreSleepShortformMin.roundToInt()}분 → " +
                            "${cur.avgPreSleepShortformMin.roundToInt()}분으로 줄어 눈과 뇌가 더 편안하게 밤을 맞았어요."
                    )
                }
            } else if (cur.avgPreSleepPhoneMin != null && prev.avgPreSleepPhoneMin != null) {
                val prePhoneDiff = prev.avgPreSleepPhoneMin - cur.avgPreSleepPhoneMin
                if (prePhoneDiff >= 6.0) {
                    list += (prePhoneDiff * 2.5) to WeeklyImprovementHighlight(
                        id = "win_presleep_phone",
                        title = "취침 전 폰 절제",
                        badgeText = "-${prePhoneDiff.roundToInt()}분",
                        detail = "취침 전 1시간 폰 사용이 지난주 ${prev.avgPreSleepPhoneMin.roundToInt()}분에서 " +
                            "이번 주 ${cur.avgPreSleepPhoneMin.roundToInt()}분으로 차분해졌어요."
                    )
                }
            }

            // 5. Bedtime regularity improved
            if (cur.bedtimeStdDevMin != null && prev.bedtimeStdDevMin != null) {
                val stdDiff = prev.bedtimeStdDevMin - cur.bedtimeStdDevMin
                if (stdDiff >= 12.0) {
                    list += (stdDiff * 1.6) to WeeklyImprovementHighlight(
                        id = "win_bedtime_regularity",
                        title = "수면 리듬 안정",
                        badgeText = "편차 -${stdDiff.roundToInt()}분",
                        detail = "취침 시각 흔들림이 지난주보다 약 ${stdDiff.roundToInt()}분 줄어들며 " +
                            "몸이 기억하는 밤 리듬이 한결 일정해졌어요."
                    )
                }
            }

            // 6. Earlier bedtime (if previously sleeping after midnight)
            if (cur.avgBedtimeLinearMin != null && prev.avgBedtimeLinearMin != null) {
                val earlierDiff = prev.avgBedtimeLinearMin - cur.avgBedtimeLinearMin
                if (earlierDiff >= 15.0 && prev.avgBedtimeLinearMin >= 23 * 60 + 30) {
                    list += (earlierDiff * 1.5) to WeeklyImprovementHighlight(
                        id = "win_earlier_bedtime",
                        title = "취침 시각 앞당김",
                        badgeText = "${earlierDiff.roundToInt()}분 일찍",
                        detail = "평균 취침 시각이 ${formatClockMinute(normalizeClockMinute(prev.avgBedtimeLinearMin))} → " +
                            "${formatClockMinute(normalizeClockMinute(cur.avgBedtimeLinearMin))}로 당겨졌어요."
                    )
                }
            }

            // 7. Sleep duration moved closer to healthy 7.5h (450m)
            if (cur.avgSleepMin != null && prev.avgSleepMin != null) {
                val sleepGain = cur.avgSleepMin - prev.avgSleepMin
                if (sleepGain >= 20.0 && prev.avgSleepMin < 435.0 && cur.avgSleepMin <= 540.0) {
                    list += (sleepGain * 1.3) to WeeklyImprovementHighlight(
                        id = "win_sleep_duration",
                        title = "수면 시간 증가",
                        badgeText = "+${sleepGain.roundToInt()}분",
                        detail = "평균 수면 시간이 ${formatMinutesShort(prev.avgSleepMin.roundToInt())}에서 " +
                            "${formatMinutesShort(cur.avgSleepMin.roundToInt())}로 회복되었어요."
                    )
                }
            }

            // 8. Habitual phone pickups decreased
            if (cur.avgPhoneSessionsPerDay != null && prev.avgPhoneSessionsPerDay != null) {
                val pickupDiff = prev.avgPhoneSessionsPerDay - cur.avgPhoneSessionsPerDay
                if (pickupDiff >= 4.0) {
                    list += (pickupDiff * 3.0) to WeeklyImprovementHighlight(
                        id = "win_phone_pickups",
                        title = "폰 확인 횟수 감소",
                        badgeText = "-${pickupDiff.roundToInt()}회/일",
                        detail = "하루 평균 폰을 켜는 횟수가 ${prev.avgPhoneSessionsPerDay.roundToInt()}회 → " +
                            "${cur.avgPhoneSessionsPerDay.roundToInt()}회로 줄어 집중 흐름이 덜 끊겼어요."
                    )
                }
            }
        }

        val sorted = list.sortedByDescending { it.first }.map { it.second }.take(3).toMutableList()

        // Supportive fallback when this is the user's very first week OR a busier/heavier week
        if (sorted.isEmpty()) {
            val lightestDay = currentDays
                .filter { it.totalPhoneMs >= MIN_ACTIVE_DAY_PHONE_MS }
                .minByOrNull { it.totalPhoneMs }
            if (prev == null) {
                sorted += WeeklyImprovementHighlight(
                    id = "win_first_week",
                    title = "첫 리듬 지도 완성",
                    badgeText = "${cur.activeDays}일 기록",
                    detail = "이번 주 ${cur.activeDays}일간의 기록이 기준점이 되었어요. " +
                        "다음 주 월요일부터는 이번 주와 비교해 어떤 점이 좋아졌는지 콕 짚어 알려드릴게요!"
                )
            } else if (lightestDay != null) {
                val dow = koreanDayOfWeek(lightestDay.date.dayOfWeek)
                val lightMin = (lightestDay.totalPhoneMs / MINUTES_PER_MS).roundToInt()
                sorted += WeeklyImprovementHighlight(
                    id = "win_best_day",
                    title = "${dow}요일 균형 유지",
                    badgeText = formatMinutesShort(lightMin),
                    detail = "전체 사용량은 지난주보다 조금 늘었지만, ${lightestDay.date.monthValue}월 ${lightestDay.date.dayOfMonth}일(${dow})에는 " +
                        "하루 ${formatMinutesShort(lightMin)}로 가장 담백하게 보냈어요. 이번 주도 그 감각을 천천히 넓혀봐요."
                )
            } else {
                sorted += WeeklyImprovementHighlight(
                    id = "win_consistency",
                    title = "한 주 기록 완료",
                    badgeText = "${cur.activeDays}일 관찰",
                    detail = "스스로의 패턴을 들여다보는 것만으로도 변화의 절반은 시작된 셈이에요. 아래 생활 패턴 발견을 가볍게 살펴봐요."
                )
            }
        }

        return sorted
    }

    private fun buildDiscoveries(
        currentDays: List<WeeklyDaySnapshot>,
        cur: WeekAggregate,
    ): List<WeeklyPatternDiscovery> {
        val discoveries = mutableListOf<WeeklyPatternDiscovery>()

        // 1. Peak 2-hour block ("마의 시간대") & calmest daytime focus block ("황금 집중 시간대")
        if (cur.hasHourly) {
            val totalHourly = cur.hourlyTotals.sum().coerceAtLeast(1.0)
            var bestPeakStart = 0
            var bestPeakSum = -1.0
            for (h in 0..23) {
                val sum2h = cur.hourlyTotals[h] + cur.hourlyTotals[(h + 1) % 24]
                if (sum2h > bestPeakSum) {
                    bestPeakSum = sum2h
                    bestPeakStart = h
                }
            }
            val peakEnd = (bestPeakStart + 2) % 24
            val peakSharePct = ((bestPeakSum / totalHourly) * 100.0).roundToInt().coerceIn(1, 100)

            // Calmest 2-hour daytime block between 09:00 and 19:00 (excluding sleep)
            var calmStart = 14
            var calmSum = Double.MAX_VALUE
            for (h in 9..17) {
                val sum2h = cur.hourlyTotals[h] + cur.hourlyTotals[h + 1]
                if (sum2h < calmSum) {
                    calmSum = sum2h
                    calmStart = h
                }
            }
            val calmEnd = calmStart + 2
            val peakLabel = "${formatHourKorean(bestPeakStart)}~${formatHourKorean(peakEnd)}"
            val calmLabel = "${formatHourKorean(calmStart)}~${formatHourKorean(calmEnd)}"

            val isLateNightPeak = bestPeakStart >= 22 || bestPeakStart <= 2
            discoveries += WeeklyPatternDiscovery(
                id = "pattern_time_rhythm",
                category = WeeklyPatternCategory.TIME_RHYTHM,
                title = if (isLateNightPeak) "밤 시간대 집중" else "최다 사용 시간대",
                highlightTag = "${peakSharePct}%",
                detail = "이번 주 전체 폰 사용의 약 ${peakSharePct}%가 $peakLabel 사이에 몰렸어요. " +
                    "반대로 낮 시간 중 $calmLabel 는 폰을 가장 덜 본 '황금 집중 시간대'였어요."
            )
        }

        // 2. Shortform habit / binge session / swipe velocity
        if (cur.avgDailyShortformMin >= 5.0 || cur.avgShortformSessionMin != null) {
            val avgSfMin = cur.avgDailyShortformMin.roundToInt()
            val avgSess = cur.avgShortformSessionMin?.roundToInt()
            val spm = cur.avgShortformSpm
            val detailText = buildString {
                append("이번 주 숏폼은 하루 평균 ${formatMinutesShort(avgSfMin)} 시청했어요.")
                if (avgSess != null && avgSess >= 1) {
                    append(" 한 번 켜면 평균 ${avgSess}분 정도 머무는 흐름이었어요.")
                }
                if (spm != null && spm >= 2.2) {
                    append(" 분당 약 ${String.format(Locale.KOREA, "%.1f", spm)}회로 빠르게 넘기는 편이라 피곤할 때 습관적으로 보게 되는 경향이 보여요.")
                } else if (spm != null && spm > 0.0) {
                    append(" 분당 약 ${String.format(Locale.KOREA, "%.1f", spm)}회 속도로 비교적 차분하게 시청했어요.")
                }
            }
            val title = when {
                avgSess != null && avgSess >= 18 -> "숏폼 연속 시청 관성"
                spm != null && spm >= 2.4 -> "빠른 숏폼 스와이프"
                else -> "숏폼 안정적 유지"
            }
            discoveries += WeeklyPatternDiscovery(
                id = "pattern_shortform",
                category = WeeklyPatternCategory.SHORTFORM_HABIT,
                title = title,
                highlightTag = avgSess?.let { "${it}분/회" } ?: formatMinutesShort(avgSfMin),
                detail = detailText,
            )
        }

        // 3. Sleep & pre-sleep connection
        if (cur.avgBedtimeLinearMin != null || cur.avgSleepMin != null) {
            val bedClock = cur.avgBedtimeLinearMin?.let { formatClockMinute(normalizeClockMinute(it)) }
            val sleepStr = cur.avgSleepMin?.let { formatMinutesShort(it.roundToInt()) }
            val preSf = cur.avgPreSleepShortformMin?.roundToInt()
            val prePhone = cur.avgPreSleepPhoneMin?.roundToInt()

            // Compare nights with lighter vs heavier pre-sleep usage if enough data exists
            val nightsWithBoth = currentDays.filter { it.bedtimeMinute != null && it.preSleepUsageMinutes != null }
            val correlationNote = if (nightsWithBoth.size >= 4) {
                val sortedByPre = nightsWithBoth.sortedBy { it.preSleepUsageMinutes!! }
                val lowHalf = sortedByPre.take(sortedByPre.size / 2)
                val highHalf = sortedByPre.takeLast(sortedByPre.size / 2)
                val lowBed = lowHalf.map { toLinearBedtimeMinute(it.bedtimeMinute!!) }.average()
                val highBed = highHalf.map { toLinearBedtimeMinute(it.bedtimeMinute!!) }.average()
                val diffMin = (highBed - lowBed).roundToInt()
                if (diffMin >= 18) {
                    " 특히 잠들기 전 폰을 덜 본 날은 평균 ${diffMin}분 더 일찍 잠들었어요."
                } else null
            } else null

            val title = when {
                bedClock != null && cur.avgBedtimeLinearMin >= 24 * 60 + 30 -> "새벽 취침 경향"
                bedClock != null && (cur.bedtimeStdDevMin ?: 0.0) <= 30.0 -> "규칙적인 수면 리듬"
                sleepStr != null -> "주간 평균 수면"
                else -> "밤 수면 리듬"
            }
            val detail = buildString {
                if (bedClock != null && sleepStr != null) {
                    append("평균 취침 시각은 $bedClock, 평균 수면 시간은 $sleepStr 이었어요.")
                } else if (bedClock != null) {
                    append("평균 취침 시각은 $bedClock 전후였어요.")
                } else if (sleepStr != null) {
                    append("평균 수면 시간은 $sleepStr 이었어요.")
                }
                if (preSf != null && preSf >= 10) {
                    append(" 잠들기 전 숏폼에 평균 ${preSf}분 머물렀어요.")
                } else if (prePhone != null && prePhone >= 15) {
                    append(" 잠들기 직전 폰 사용은 평균 ${prePhone}분이었어요.")
                }
                if (correlationNote != null) append(correlationNote)
            }
            discoveries += WeeklyPatternDiscovery(
                id = "pattern_sleep",
                category = WeeklyPatternCategory.SLEEP_CONNECTION,
                title = title,
                highlightTag = bedClock ?: sleepStr,
                detail = detail.trim(),
            )
        }

        // 4. Workday vs Rest day comparison
        if (cur.workdayAvgPhoneMin != null && cur.restdayAvgPhoneMin != null) {
            val diff = cur.restdayAvgPhoneMin - cur.workdayAvgPhoneMin
            if (abs(diff) >= 25.0) {
                val restHigher = diff > 0
                val diffMin = abs(diff).roundToInt()
                discoveries += WeeklyPatternDiscovery(
                    id = "pattern_day_type",
                    category = WeeklyPatternCategory.DAY_TYPE_BALANCE,
                    title = if (restHigher) "쉬는 날 사용 증가" else "평일 사용 더 많음",
                    highlightTag = if (restHigher) "휴일 +${formatMinutesShort(diffMin)}" else "평일 +${formatMinutesShort(diffMin)}",
                    detail = "일과 날은 하루 평균 ${formatMinutesShort(cur.workdayAvgPhoneMin.roundToInt())}, " +
                        "쉬는 날은 ${formatMinutesShort(cur.restdayAvgPhoneMin.roundToInt())} 사용했어요. " +
                        if (restHigher) {
                            "평일에 참았던 피로가 주말에 한꺼번에 풀리는 자연스러운 흐름이에요."
                        } else {
                            "평일 중 틈틈이 확인하거나 퇴근 후 긴장을 푸느라 사용이 늘어난 것으로 보여요."
                        }
                )
            }
        }

        // 5. Top apps summary (if we still have room or want concrete context)
        if (cur.topApps.isNotEmpty() && discoveries.size < 4) {
            val top3 = cur.topApps.take(3)
            val appSummary = top3.joinToString(" · ") { app ->
                "${app.label}(${formatMinutesShort(app.minutes.roundToInt())})"
            }
            discoveries += WeeklyPatternDiscovery(
                id = "pattern_top_apps",
                category = WeeklyPatternCategory.TOP_APPS,
                title = "최다 사용 · ${top3.first().label}",
                highlightTag = "TOP ${top3.size}",
                detail = "주간 누적 기준 $appSummary 순으로 시간을 가장 많이 보냈어요."
            )
        }

        return discoveries.take(4)
    }

    private fun buildCoachTip(
        cur: WeekAggregate,
        discoveries: List<WeeklyPatternDiscovery>,
        prev: WeekAggregate?,
    ): Pair<String, String> {
        val lateNightPeak = discoveries.any {
            it.id == "pattern_time_rhythm" && it.title.contains("밤 시간대")
        }
        val highPreSleepSf = (cur.avgPreSleepShortformMin ?: 0.0) >= 12.0
        val longShortformSession = (cur.avgShortformSessionMin ?: 0.0) >= 16.0
        val restRebound = discoveries.any {
            it.id == "pattern_day_type" && it.title.contains("쉬는 날")
        }

        return when {
            highPreSleepSf || lateNightPeak ->
                "취침 15분 전 폰 내려놓기" to
                    "밤 시간대 사용을 한 번에 줄이려 하기보다, 침대에 눕기 전 딱 15분만 충전기에 먼저 올려두는 것으로 시작해 봐요. 내일 아침 컨디션이 가장 먼저 달라져요."

            longShortformSession ->
                "숏폼 시작 전 10분 약속" to
                    "숏폼을 아예 안 보는 것보다 한 번 볼 때 길이를 줄이는 게 훨씬 쉬워요. 코치 알림이 울릴 때 영상 하나만 마저 보고 닫아보는 연습을 해볼까요?"

            restRebound ->
                "주말 아침 30분 폰 쉬기" to
                    "쉬는 날 아침에 눈뜨자마자 폰을 잡으면 하루 전체 사용량이 확 늘어나는 경향이 있어요. 주말 첫 30분만 스트레칭이나 물 한 잔으로 열어봐요."

            prev != null && cur.avgDailyPhoneMin < prev.avgDailyPhoneMin ->
                "지금의 좋은 리듬 이어가기" to
                    "지난주보다 이미 멋진 변화를 만들었어요! 무리해서 목표를 더 높이기보다, 이번 주에 가장 편했던 하루 패턴을 한 번만 더 반복해 봐요."

            else ->
                "하루 20분 폰 없는 시간" to
                    "거창한 절제보다, 산책이나 식사 시간 딱 20분만 폰을 뒤집어 두는 작은 성공이 일주일 전체 리듬을 부드럽게 바꿔줘요."
        }
    }

    private fun buildGreeting(
        cur: WeekAggregate,
        prev: WeekAggregate?,
        improvements: List<WeeklyImprovementHighlight>,
        discoveries: List<WeeklyPatternDiscovery>,
        savedWeeklyMin: Int?,
    ): String {
        val lateNightPattern = discoveries.any {
            it.id == "pattern_time_rhythm" && it.title.contains("밤 시간대")
        } || (cur.avgPreSleepShortformMin ?: 0.0) >= 12.0
        val restReboundPattern = discoveries.any {
            it.id == "pattern_day_type" && it.title.contains("쉬는 날")
        }
        val shortformHabitPattern = discoveries.any {
            it.id == "pattern_shortform" && (it.title.contains("관성") || it.title.contains("스와이프"))
        }
        val hadRealWin = prev != null && improvements.any {
            it.id != "win_best_day" && it.id != "win_consistency" && it.id != "win_first_week"
        }

        return when {
            prev == null ->
                "반가워요, 첫 주간 편지가 우편함에 도착했어요. 잘했다 못했다 점수를 매기기보다, 지난 한 주 내 하루가 어떤 숨결로 흘렀는지 다정하게 들여다보려 해요. 완벽하지 않아도 괜찮아요. 내 리듬을 천천히 알아가는 것만으로도 이미 멋진 시작이니까요."

            savedWeeklyMin != null && savedWeeklyMin >= 30 ->
                "새로운 한 주가 밝았어요. 지난주에는 바쁜 일상 속에서도 스스로를 위해 스마트폰을 내려놓은 소중한 순간들이 참 많았어요. 작은 선택들이 모여 어느새 마음에 넉넉한 여백이 생겼네요. 이번 주도 너무 애쓰지 말고, 지금의 편안한 보폭 그대로 걸어가 봐요."

            hadRealWin ->
                "월요일 아침, 지난 한 주도 정말 수고 많았어요. 바쁜 나날 속에서도 지난주보다 한결 차분해진 나만의 습관들이 곳곳에서 반짝이고 있었어요. 스스로를 조금씩 돌보고 있다는 멋진 신호예요. 따뜻한 차 한 잔 하듯 가볍게 한 주를 열어봐요."

            lateNightPattern ->
                "새로운 월요일이 시작됐어요. 지난주는 하루의 피로를 밤늦게까지 화면 너머로 달래느라 잠드는 시간이 조금 늦어졌던 것 같아요. 지친 하루 끝에 기대고 싶었던 마음은 너무 자연스러운 거니까 자책하지 않아도 괜찮아요. 이번 주는 잠들기 전 눈과 마음을 조금만 더 일찍 쉬게 해 줄까요?"

            restReboundPattern ->
                "월요일 아침이에요, 지난 한 주도 묵묵히 잘 버텨냈어요. 평일 동안 쌓인 피로가 쉬는 날에 한꺼번에 밀려오면서, 화면 속에서 잠시 숨을 돌리고 싶었던 한 주였을 거예요. 충분히 열심히 달렸기에 자연스러운 흐름이에요. 이번 주는 나만의 휴식을 조금 더 가볍고 개운하게 채워봐요."

            shortformHabitPattern ->
                "새로운 한 주가 밝았어요. 지난주는 머리가 복잡하거나 잠깐 멍해질 때마다 짧은 영상으로 마음을 환기하고 싶었던 순간들이 자주 있었네요. 그만큼 마음의 쉼표가 필요했던 한 주였을 거예요. 이번 주는 화면 밖에서도 잠시 숨 고를 틈을 함께 만들어 봐요."

            else ->
                "새로운 월요일이 밝았어요. 지난주는 유난히 바쁘거나 마음이 분주해서 스마트폰을 자주 찾게 되는 날이 있었을지도 몰라요. 늘 완벽하게 조절할 수는 없으니 너무 마음 쓰지 않아도 괜찮아요. 지난주의 피로는 여기 살짝 내려두고, 이번 주는 한결 가벼운 마음으로 출발해 봐요."
        }
    }

    internal fun formatReclaimedTimeExample(savedMinutes: Int): String = when {
        savedMinutes >= 180 -> "영화 ${(savedMinutes / 110).coerceAtLeast(1)}편 이상"
        savedMinutes >= 100 -> "영화 1편 또는 여유로운 산책 3번"
        savedMinutes >= 60 -> "여유로운 산책 2번 또는 책 1챕터"
        savedMinutes >= 30 -> "가벼운 산책 1번 또는 스트레칭 2번"
        else -> "따뜻한 차 한 잔의 여유"
    }

    internal fun formatMinutesShort(totalMinutes: Int): String {
        val safe = totalMinutes.coerceAtLeast(0)
        val h = safe / 60
        val m = safe % 60
        return when {
            h > 0 && m > 0 -> "${h}시간 ${m}분"
            h > 0 -> "${h}시간"
            else -> "${m}분"
        }
    }

    private fun toLinearBedtimeMinute(clockMinute: Int): Double {
        val norm = ((clockMinute % 1440) + 1440) % 1440
        return if (norm < 12 * 60) (norm + 1440).toDouble() else norm.toDouble()
    }

    private fun normalizeClockMinute(linearMinute: Double): Int {
        val rounded = linearMinute.roundToInt()
        return ((rounded % 1440) + 1440) % 1440
    }

    internal fun formatClockMinute(clockMinute: Int): String {
        val norm = ((clockMinute % 1440) + 1440) % 1440
        val h = norm / 60
        val m = norm % 60
        val prefix = when (h) {
            in 0..5 -> "새벽"
            in 6..11 -> "오전"
            in 12..17 -> "오후"
            else -> "밤"
        }
        val h12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return if (m == 0) "$prefix ${h12}시" else "$prefix ${h12}시 ${m}분"
    }

    private fun formatHourKorean(hour24: Int): String {
        val h = ((hour24 % 24) + 24) % 24
        return when (h) {
            0 -> "자정"
            in 1..5 -> "새벽 ${h}시"
            in 6..11 -> "오전 ${h}시"
            12 -> "낮 12시"
            in 13..17 -> "오후 ${h - 12}시"
            in 18..21 -> "저녁 ${h - 12}시"
            else -> "밤 ${h - 12}시"
        }
    }

    private fun koreanDayOfWeek(dow: DayOfWeek): String = when (dow) {
        DayOfWeek.MONDAY -> "월"
        DayOfWeek.TUESDAY -> "화"
        DayOfWeek.WEDNESDAY -> "수"
        DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"
        DayOfWeek.SATURDAY -> "토"
        DayOfWeek.SUNDAY -> "일"
    }
}
