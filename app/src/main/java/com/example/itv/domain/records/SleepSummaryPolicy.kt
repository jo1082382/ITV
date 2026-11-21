package com.example.itv.domain.records

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    /**
     * 사용자가 직접 확인(수면 시간 선택 또는 직접 입력)한 유효 수면 기록 또는
     * 비사용 기간이 길어 취침 추정 시각 후보가 하나뿐인 날의 기록을 자동으로 모아
     * 평소 추정 취침 시각(시작 시각 평균, 종료 시각 평균, 총 평균 수면 길이)을 계산합니다.
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
                // 사용자가 직접 확인한 날
                if (conf.choice == "SLEEP" && conf.startMs != null && conf.endMs != null && conf.startMs < conf.endMs) {
                    validSegments.add(conf.startMs to conf.endMs)
                }
                // conf.choice == "NOT_SLEEP"인 경우 수면 아님으로 명시했으므로 제외
            } else {
                // 사용자가 아직 선택하지 않은 날: 비사용 기간이 길어 취침 추정 시각 후보가 하나뿐인 경우는 자동으로 반영
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

        fun circularAverageMinute(epochList: List<Long>): Pair<Int, Int> {
            var sumSin = 0.0
            var sumCos = 0.0
            for (ms in epochList) {
                val localTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
                val minuteOfDay = localTime.hour * 60 + localTime.minute + (localTime.second / 60.0)
                // 1440분을 2*PI 라디안 각도로 변환
                val angle = minuteOfDay / 1440.0 * 2.0 * Math.PI
                sumSin += Math.sin(angle)
                sumCos += Math.cos(angle)
            }
            val avgAngle = (Math.atan2(sumSin, sumCos) + 2.0 * Math.PI) % (2.0 * Math.PI)
            val avgMinuteOfDay = Math.round(avgAngle / (2.0 * Math.PI) * 1440.0) % 1440L
            return (avgMinuteOfDay / 60).toInt() to (avgMinuteOfDay % 60).toInt()
        }

        val (startH, startM) = circularAverageMinute(validSegments.map { it.first })
        val (endH, endM) = circularAverageMinute(validSegments.map { it.second })
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
}
