package com.example.itv.domain.records

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

internal object SleepSummaryPolicy {
    fun minutes(date: LocalDate, automatic: Double?, confirmations: Map<String, SleepConfirmation>): Double? {
        val value = confirmations[date.toString()] ?: return automatic
        return if (value.choice == "NOT_SLEEP") 0.0
        else if (value.startMs != null && value.endMs != null)
            (value.endMs - value.startMs) / 60000.0 else automatic
    }
    fun average(values: List<Double?>): Double? = values.filterNotNull().let {
        if (it.isEmpty()) null else it.average()
    }

    data class EstimatedBedtimeWindow(
        val validCount: Int,
        val avgStartHour: Int,
        val avgStartMinute: Int,
        val avgEndHour: Int,
        val avgEndMinute: Int,
        val avgDurationMinutes: Long
    )

    data class DayOfWeekSleepProfile(
        val wakeDayOfWeek: DayOfWeek,
        val validCount: Int,
        val avgWakeHour: Int,
        val avgWakeMinute: Int,
        val avgStartHour: Int,
        val avgStartMinute: Int,
        val recommendedBedtimeHour: Int,
        val recommendedBedtimeMinute: Int,
        val avgDurationMinutes: Long
    )

    data class WeeklySleepProfile(
        val globalWindow: EstimatedBedtimeWindow,
        val dayProfiles: Map<DayOfWeek, DayOfWeekSleepProfile>,
        val totalValidDays: Int
    )

    data class AdaptiveBedtimeTarget(
        val targetHour: Int,
        val targetMinute: Int,
        val recommendedHour: Int,
        val recommendedMinute: Int,
        val expectedWakeHour: Int,
        val expectedWakeMinute: Int,
        val sourceDescription: String,
        val isDayOfWeekSpecific: Boolean,
        val targetWakeDayOfWeek: DayOfWeek
    )

    private fun circularAverageMinute(epochList: List<Long>, zone: ZoneId): Pair<Int, Int> {
        var sumSin = 0.0
        var sumCos = 0.0
        for (ms in epochList) {
            val localTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
            val minuteOfDay = localTime.hour * 60 + localTime.minute + (localTime.second / 60.0)
            val angle = minuteOfDay / 1440.0 * 2.0 * Math.PI
            sumSin += Math.sin(angle)
            sumCos += Math.cos(angle)
        }
        val avgAngle = (Math.atan2(sumSin, sumCos) + 2.0 * Math.PI) % (2.0 * Math.PI)
        val avgMinuteOfDay = Math.round(avgAngle / (2.0 * Math.PI) * 1440.0) % 1440L
        return (avgMinuteOfDay / 60).toInt() to (avgMinuteOfDay % 60).toInt()
    }

    /**
     * 사용자가 직접 확인한 유효 수면 기록 또는 후보가 하나뿐인 날의 기록을 모아
     * 전체 평균 취침 시각 및 수면 길이를 계산합니다.
     */
    fun estimateBedtimeWindow(
        confirmations: Map<String, SleepConfirmation>,
        candidates: List<NightSegment> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault()
    ): EstimatedBedtimeWindow? {
        val allDates = (confirmations.keys + candidates.map { it.wakeDate.toString() }).distinct()
        val validSegments = mutableListOf<Pair<Long, Long>>() // startMs to endMs

        for (dateStr in allDates) {
            val conf = confirmations[dateStr]
            if (conf != null) {
                if (conf.choice == "SLEEP" && conf.startMs != null && conf.endMs != null && conf.startMs < conf.endMs) {
                    validSegments.add(conf.startMs to conf.endMs)
                }
            } else {
                val date = try { LocalDate.parse(dateStr) } catch (_: Exception) { null }
                if (date != null) {
                    val dayCandidates = candidates.filter { it.wakeDate == date && it.end != null && it.start < it.end }
                    if (dayCandidates.size == 1) {
                        val single = dayCandidates.first()
                        validSegments.add(single.start to single.end!!)
                    }
                }
            }
        }

        if (validSegments.isEmpty()) return null

        val (startH, startM) = circularAverageMinute(validSegments.map { it.first }, zone)
        val (endH, endM) = circularAverageMinute(validSegments.map { it.second }, zone)
        val avgDuration = validSegments.map { (it.second - it.first) / 60000L }.average().toLong()

        return EstimatedBedtimeWindow(
            validCount = validSegments.size,
            avgStartHour = startH,
            avgStartMinute = startM,
            avgEndHour = endH,
            avgEndMinute = endM,
            avgDurationMinutes = avgDuration
        )
    }

    /**
     * 요일별 기상 패턴(비사용 종료 시각)과 수면 기록을 분석하여
     * 각 요일별 기상 시각, 실제 취침 시각, 기상 시간 기반 권장 취침 시각 프로파일을 도출합니다.
     */
    fun estimateWeeklySleepProfile(
        confirmations: Map<String, SleepConfirmation>,
        candidates: List<NightSegment> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault()
    ): WeeklySleepProfile? {
        val globalWindow = estimateBedtimeWindow(confirmations, candidates, zone) ?: return null

        val allDates = (confirmations.keys + candidates.map { it.wakeDate.toString() }).distinct()
        val segmentsByWakeDay = mutableMapOf<DayOfWeek, MutableList<Pair<Long, Long>>>()

        for (dateStr in allDates) {
            val conf = confirmations[dateStr]
            var segment: Pair<Long, Long>? = null
            var targetWakeDate: LocalDate? = null

            if (conf != null) {
                if (conf.choice == "SLEEP" && conf.startMs != null && conf.endMs != null && conf.startMs < conf.endMs) {
                    segment = conf.startMs to conf.endMs
                    targetWakeDate = try { LocalDate.parse(dateStr) } catch (_: Exception) {
                        Instant.ofEpochMilli(conf.endMs).atZone(zone).toLocalDate()
                    }
                }
            } else {
                val date = try { LocalDate.parse(dateStr) } catch (_: Exception) { null }
                if (date != null) {
                    val dayCandidates = candidates.filter { it.wakeDate == date && it.end != null && it.start < it.end }
                    if (dayCandidates.size == 1) {
                        val single = dayCandidates.first()
                        segment = single.start to single.end!!
                        targetWakeDate = date
                    }
                }
            }

            if (segment != null && targetWakeDate != null) {
                val dow = targetWakeDate.dayOfWeek
                segmentsByWakeDay.getOrPut(dow) { mutableListOf() }.add(segment)
            }
        }

        val dayProfiles = mutableMapOf<DayOfWeek, DayOfWeekSleepProfile>()

        for ((dow, segs) in segmentsByWakeDay) {
            if (segs.isEmpty()) continue
            val (startH, startM) = circularAverageMinute(segs.map { it.first }, zone)
            val (endH, endM) = circularAverageMinute(segs.map { it.second }, zone)
            val avgDuration = segs.map { (it.second - it.first) / 60000L }.average().toLong()

            // 기상 시각에서 적정 수면 시간(개인 평균 7.0~8.5시간)을 역산하여 건강 권장 취침 시각 계산
            val effectiveSleepMins = avgDuration.coerceIn(420L, 510L)
            val wakeMinuteOfDay = endH * 60 + endM
            val recBedtimeMinuteOfDay = (wakeMinuteOfDay - effectiveSleepMins + 1440L * 2) % 1440L
            val recBedtimeH = (recBedtimeMinuteOfDay / 60).toInt()
            val recBedtimeM = (recBedtimeMinuteOfDay % 60).toInt()

            dayProfiles[dow] = DayOfWeekSleepProfile(
                wakeDayOfWeek = dow,
                validCount = segs.size,
                avgWakeHour = endH,
                avgWakeMinute = endM,
                avgStartHour = startH,
                avgStartMinute = startM,
                recommendedBedtimeHour = recBedtimeH,
                recommendedBedtimeMinute = recBedtimeM,
                avgDurationMinutes = avgDuration
            )
        }

        return WeeklySleepProfile(
            globalWindow = globalWindow,
            dayProfiles = dayProfiles,
            totalValidDays = globalWindow.validCount
        )
    }

    /**
     * 현재 시점(또는 특정 시점)을 기준으로, 다가오는 기상 목표 요일을 판별합니다.
     * 정오(12:00) 이후: 오늘 밤에 잠들어 '내일 아침'에 일어나므로 today.plus(1)
     * 정오 이전(00:00~11:59): 전날 밤 늦게까지 깨어있다 '오늘 아침/낮'에 일어나므로 today
     */
    fun resolveTargetWakeDayOfWeek(now: ZonedDateTime): DayOfWeek {
        return if (now.hour >= 12) {
            now.toLocalDate().dayOfWeek.plus(1)
        } else {
            now.toLocalDate().dayOfWeek
        }
    }

    /**
     * 주간 수면 프로파일과 현재 시점을 바탕으로 최적의 동적 적응형 취침 목표 시각을 산출합니다.
     * 내일(또는 오늘) 기상 요일의 기록이 있으면 요일별 맞춤 패턴을 적용하고,
     * 없거나 부족하면 전체 평균으로 안전하게 fallback 합니다.
     */
    fun getAdaptiveBedtimeTarget(
        weeklyProfile: WeeklySleepProfile?,
        now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())
    ): AdaptiveBedtimeTarget {
        val targetDow = resolveTargetWakeDayOfWeek(now)
        val dowKorean = when (targetDow) {
            DayOfWeek.MONDAY -> "월"
            DayOfWeek.TUESDAY -> "화"
            DayOfWeek.WEDNESDAY -> "수"
            DayOfWeek.THURSDAY -> "목"
            DayOfWeek.FRIDAY -> "금"
            DayOfWeek.SATURDAY -> "토"
            DayOfWeek.SUNDAY -> "일"
        }

        val dayProfile = weeklyProfile?.dayProfiles?.get(targetDow)

        // 해당 기상 요일의 기록이 1건 이상 있으면 요일별 맞춤 취침 패턴 우선 적용
        if (dayProfile != null && dayProfile.validCount >= 1) {
            val wakeTimeStr = "%02d:%02d".format(dayProfile.avgWakeHour, dayProfile.avgWakeMinute)
            return AdaptiveBedtimeTarget(
                targetHour = dayProfile.avgStartHour,
                targetMinute = dayProfile.avgStartMinute,
                recommendedHour = dayProfile.recommendedBedtimeHour,
                recommendedMinute = dayProfile.recommendedBedtimeMinute,
                expectedWakeHour = dayProfile.avgWakeHour,
                expectedWakeMinute = dayProfile.avgWakeMinute,
                sourceDescription = "내일(${dowKorean}) 기상 패턴(${wakeTimeStr}) 기반 추정 (${dayProfile.validCount}일 기록)",
                isDayOfWeekSpecific = true,
                targetWakeDayOfWeek = targetDow
            )
        }

        // 특정 요일 기록이 없는 경우 전체 평균으로 fallback
        if (weeklyProfile != null) {
            val global = weeklyProfile.globalWindow
            val wakeTimeStr = "%02d:%02d".format(global.avgEndHour, global.avgEndMinute)
            val effSleepMins = global.avgDurationMinutes.coerceIn(420L, 510L)
            val wakeMinuteOfDay = global.avgEndHour * 60 + global.avgEndMinute
            val recBedtimeMinuteOfDay = (wakeMinuteOfDay - effSleepMins + 1440L * 2) % 1440L
            return AdaptiveBedtimeTarget(
                targetHour = global.avgStartHour,
                targetMinute = global.avgStartMinute,
                recommendedHour = (recBedtimeMinuteOfDay / 60).toInt(),
                recommendedMinute = (recBedtimeMinuteOfDay % 60).toInt(),
                expectedWakeHour = global.avgEndHour,
                expectedWakeMinute = global.avgEndMinute,
                sourceDescription = "수면 추정/확인 기록 (${wakeTimeStr} 기상, 전체 ${global.validCount}일 평균)",
                isDayOfWeekSpecific = false,
                targetWakeDayOfWeek = targetDow
            )
        }

        // 데이터가 전혀 없는 경우 기본값
        return AdaptiveBedtimeTarget(
            targetHour = 23,
            targetMinute = 30,
            recommendedHour = 23,
            recommendedMinute = 30,
            expectedWakeHour = 7,
            expectedWakeMinute = 30,
            sourceDescription = "기본 기준 시각 (23:30)",
            isDayOfWeekSpecific = false,
            targetWakeDayOfWeek = targetDow
        )
    }
}
