package com.example.itv.presentation.usage
import com.example.itv.domain.goals.HomeGoalRow

import com.example.itv.domain.records.*
import com.example.itv.composition.queryNightReport
import com.example.itv.composition.queryUsageReport


import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import com.example.itv.domain.coaching.DeveloperStatus

@Composable
internal fun GoalComparisonSection(goals: List<HomeGoalRow>, period: Report?, today: Report?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("목표와 실제 기록 비교", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("현재 설정한 목표를 기준으로 비교합니다. 과거 날짜에 적용됐던 목표나 달성 이력은 아닙니다.", style = MaterialTheme.typography.bodySmall)
        val daily = goals.firstOrNull { it.goalType == "DAILY_PHONE_USAGE" && it.enabled }
        ComparisonCard("하루 휴대폰 사용 목표") {
            val seconds = daily?.dailyLimitSeconds
            if (seconds == null || seconds <= 0L) Text("활성화된 목표 없음 · 설정에서 목표를 정해 주세요.")
            else {
                val limit = seconds / 60.0
                Text("하루 목표: ${summaryDuration(limit)}")
                if (today == null) Text("오늘 실제 사용량: 조회 전 또는 조회 불가")
                else {
                    Text("오늘 현재까지: ${summaryDuration(today.total)}")
                    val difference = limit - today.total
                    Text(if (difference >= 0) "목표까지 남음: ${summaryDuration(difference)}" else "목표 초과: ${summaryDuration(-difference)}")
                    Text("오늘 기록은 진행 중이므로 최종 달성 판정은 아닙니다.", style = MaterialTheme.typography.bodySmall)
                }
                if (period != null && period.days > 1) {
                    val average = period.total / period.days
                    Text("최근 ${period.days}일 하루 평균: ${summaryDuration(average)}")
                    val delta = average - limit
                    Text(if (delta > 0) "현재 목표보다 하루 평균 ${summaryDuration(delta)} 많음" else "현재 목표보다 하루 평균 ${summaryDuration(-delta)} 적거나 같음")
                    Text("오늘을 포함한 조회 기간 기준 · 목표 변경 전 날짜도 현재 목표와 단순 비교합니다.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val rest = goals.firstOrNull { it.goalType == "USAGE_BREAK" && it.enabled }
        ComparisonCard("사용 후 휴식 목표") {
            val usage = rest?.usageThresholdSeconds
            val breakSeconds = rest?.breakDurationSeconds
            if (usage == null || breakSeconds == null) Text("활성화된 목표 없음")
            else {
                Text("설정 목표: ${summaryDuration(usage / 60.0)} 사용 후 ${summaryDuration(breakSeconds / 60.0)} 휴식")
                val sample = DeveloperStatus.value
                val age = SystemClock.elapsedRealtime() - sample.at
                if (!sample.running || sample.at == 0L || age !in 0L..4000L) Text("현재 시계 관측 없음 · 목표 달성 여부를 계산하지 않습니다.")
                else {
                    Text("현재 시계의 휴식 이후 누적: ${summaryDuration(sample.usedMs / 60000.0)}")
                    Text("현재 시계의 연속 휴식 관측: ${summaryDuration(sample.restMs / 60000.0)}")
                    Text("서비스 관측값 · 화면 진입/기록 갱신 시점의 값이며 실시간 타이머는 아닙니다.", style = MaterialTheme.typography.bodySmall)
                }
                Text("현재 코칭 시계와 설정 목표의 범위·누적 방식은 다를 수 있어, 위 값은 참고 비교입니다. 날짜별 휴식 달성률은 아직 집계하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                Text("30초 테스트 미션은 설정한 휴식 목표와 별개이며, 미션 성공률은 통합 상태의 개입기록에서 확인할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
        val bedtime = goals.firstOrNull { it.goalType == "BEDTIME" && it.enabled }
        ComparisonCard("취침 목표") {
            val minute = bedtime?.localMinuteOfDay
            if (minute == null) Text("활성화된 목표 없음")
            else {
                Text("설정 시각: ${minute / 60}시 ${minute % 60}분")
                Text("실제 취침 시각: 미측정 · 달성 여부 비교 불가")
                Text("휴대폰을 사용하지 않은 시간을 실제 수면으로 단정하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("시간은 전체 분을 반올림한 뒤 시간·분으로 표시합니다. 30초는 0시간 1분으로 표시됩니다. 비교 화면은 개입 기준을 변경하지 않습니다.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ComparisonCard(title: String, body: @Composable ColumnScope.() -> Unit) {
    val isDark = com.example.itv.presentation.components.LocalDarkTheme.current
    val contentColor = if (isDark) androidx.compose.ui.graphics.Color(0xFFE2EBE4) else androidx.compose.ui.graphics.Color(0xFF243025)
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(
        containerColor = RecordContentGreen, contentColor = contentColor)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            body()
        }
    }
}
