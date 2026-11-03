package com.example.itv.presentation.usage
import com.example.itv.domain.records.*
import com.example.itv.composition.queryNightReport
import com.example.itv.composition.queryUsageReport


import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
internal fun rememberDailyUsageDrilldownItems(period: Report): LazyListScope.() -> Unit {
    val context = LocalContext.current
    var dateKey by rememberSaveable(period.days) { mutableStateOf<String?>(null) }
    val date = dateKey?.let { LocalDate.parse(it) }
    var hour by rememberSaveable(period.days, dateKey) { mutableStateOf<Int?>(null) }
    var dayReport by remember(dateKey, period.days) { mutableStateOf<Report?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(date, period) {
        error = null
        val chosen = date ?: run { loading = false; return@LaunchedEffect }
        loading = true
        try { dayReport = withContext(Dispatchers.IO) { context.queryUsageReport(1, chosen) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = "선택한 날짜의 기록을 조회하지 못했습니다." }
        finally { loading = false }
    }
    return {
        item(key = "daily_totals_chart", contentType = "daily_totals_chart") {
        OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = RecordContentGreen)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("날짜별 총 사용시간", style = MaterialTheme.typography.titleMedium)
                Text("최근 ${period.days}일 · 오늘 포함 · 막대를 누르면 해당 날짜의 시간대별 기록 표시",
                    style = MaterialTheme.typography.bodySmall)
                val rows = period.dailyTotals.entries.sortedByDescending { it.key }
                if (rows.isEmpty()) Text("확인된 사용 기록이 없습니다.")
                else Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.width(40.dp)) {
                        Box(Modifier.height(192.dp)) {
                            for(tick in 0..4) Text("${24-tick*6}", Modifier.offset(y = (tick*48-8).dp), style = MaterialTheme.typography.labelSmall)
                        }
                        Text("시간", style = MaterialTheme.typography.labelSmall)
                    }
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rows, key = { it.key.toString() }) { entry ->
                            Column(Modifier.clickable { dateKey = entry.key.toString() }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.width(60.dp).height(192.dp).background(if(date==entry.key) Color(0xFFE7ECFF) else Color.White), contentAlignment = Alignment.BottomCenter) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        for(tick in 0..4) {
                                            val y = size.height*tick/4f
                                            drawLine(Color(0xFFE2E5EA), Offset(0f,y), Offset(size.width,y))
                                        }
                                    }
                                    Box(Modifier.width(24.dp).height((entry.value/60.0).coerceIn(0.0,24.0).toFloat().times(8).dp).background(Color(0xFF4263EB)))
                                }
                                Text("${entry.key.monthValue}.${entry.key.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Text("기록이 없는 날을 0시간으로 채우지 않습니다. 누락과 미사용을 구분할 수 없습니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
        }
        if (loading) item(key = "day_detail_loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item(key = "day_detail_error") { Text(message) } }
        if (dateKey != null) {
            item(key = "day_detail_close") {
                TextButton(onClick = { dateKey = null; hour = null; dayReport = null }) { Text("선택 날짜 시간대별 상세 접기") }
            }
        }
        dayReport?.let { data ->
            item(key = "day_detail_title") {
                Text("${date?.monthValue}.${date?.dayOfMonth} · 총 사용시간" + " ${summaryDuration(data.total)}", style = MaterialTheme.typography.titleMedium)
            }
            item(key = "day_hourly_chart", contentType = "hourly_chart") {
                UsageChart(data, hour) { hour = it }
            }
            val selected = hour
            if (selected != null) {
                item(key = "day_hour_detail_title") {
                    Text("${selected}시 ~ ${selected+1}시 · ${hourlyDuration(data.values[selected])}")
                }
                val apps = data.hourlyApps[selected].filter { it.minutes >= 1.0 }
                if(apps.isEmpty()) item(key = "day_hour_apps_empty") { Text("확인된 앱 기록이 없습니다.") }
                items(apps, key = { "day_hour_app_${it.packageName}" }, contentType = { "app_usage" }) {
                    AppUsageCard(it, Color(0xFF4263EB))
                }
            }
        }
    }
}
