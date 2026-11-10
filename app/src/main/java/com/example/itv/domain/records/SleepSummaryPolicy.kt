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
     * 사용자가 직접 확인(수면 시간 선택 또는 직접 입력)한 유효 수면 기록들을 모아
     * 평소 추정 취침 시각(시작 시각 평균, 종료 시각 평균, 평균 수면 길이)을 계산합니다.
     */
    fun estimateBedtimeWindow(
        confirmations: Map<String, SleepConfirmation>,
        zone: ZoneId = ZoneId.systemDefault()
    ): EstimatedBedtimeWindow? {
        val validConfirmations = confirmations.values.filter {
            it.choice == "SLEEP" && it.startMs != null && it.endMs != null && it.startMs < it.endMs
        }
        if (validConfirmations.isEmpty()) return null

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

        val (startH, startM) = circularAverageMinute(validConfirmations.map { it.startMs!! })
        val (endH, endM) = circularAverageMinute(validConfirmations.map { it.endMs!! })
        val avgDuration = validConfirmations.map { (it.endMs!! - it.startMs!!) / 60000L }.average().toLong()

        return EstimatedBedtimeWindow(
            validCount = validConfirmations.size,
            avgStartHour = startH,
            avgStartMinute = startM,
            avgEndHour = endH,
            avgEndMinute = endM,
            avgDurationMinutes = avgDuration
        )
    }
}
