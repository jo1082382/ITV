package com.example.itv.presentation.home
import com.example.itv.domain.goals.HomeGoalRow


import java.util.Locale

internal fun durationLabel(seconds: Long): String {
    if (seconds < 0) return "잘못된 값"
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remaining = seconds % 60
    val parts = mutableListOf<String>()
    if (hours > 0) parts.add("${hours}시간")
    if (minutes > 0) parts.add("${minutes}분")
    if (remaining > 0) parts.add("${remaining}초")
    return parts.joinToString(" ").ifEmpty { "0분" }
}

internal fun goalTitle(type: String): String = when (type) {
    "DAILY_PHONE_USAGE" -> "하루 휴대폰 사용시간"
    "BEDTIME" -> "취침시간"
    "USAGE_BREAK" -> "일정 시간 사용 후 휴식"
    else -> "추가 목표 ($type)"
}

internal fun goalDescription(row: HomeGoalRow): String = when (row.goalType) {
    "DAILY_PHONE_USAGE" -> row.dailyLimitSeconds?.let {
        "하루 목표: ${durationLabel(it)} / 휴대폰 전체 사용량 기준"
    } ?: "상세 설정을 확인할 수 없습니다."
    "BEDTIME" -> row.localMinuteOfDay?.takeIf { it in 0..1439 }?.let {
        "목표 시각: %02d:%02d (기기 현지 시각)".format(Locale.ROOT, it / 60, it % 60)
    } ?: "상세 설정을 확인할 수 없습니다."
    "USAGE_BREAK" -> {
        val use = row.usageThresholdSeconds
        val rest = row.breakDurationSeconds
        if (use == null || rest == null) "상세 설정을 확인할 수 없습니다."
        else {
            val metric = when (row.usageMetric) {
                "PHONE_USAGE" -> "휴대폰 전체 사용량"
                "TARGET_APP_USAGE" -> "대상 앱 사용량"
                "CONFIRMED_SHORTFORM" -> "확인된 숏폼 시청량"
                else -> "확인 필요 (${row.usageMetric})"
            }
            val scope = when (row.scopePackageName) {
                "com.instagram.android" -> "Instagram"
                null -> if (row.usageMetric == "PHONE_USAGE") "휴대폰 전체" else "대상 앱 미지정"
                else -> row.scopePackageName
            }
            val policy = if (row.accumulationPolicy == "CURRENT_SESSION") "현재 세션 기준" else "누적 방식 확인 필요"
            "${durationLabel(use)} 사용 후 ${durationLabel(rest)} 휴식\n대상: $scope / $metric / $policy"
        }
    }
    else -> "현재 홈에서 상세 표시를 지원하지 않는 목표입니다."
}
