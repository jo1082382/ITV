package com.example.itv.presentation.usage
import com.example.itv.domain.records.*
import com.example.itv.composition.queryNightReport
import com.example.itv.composition.queryUsageReport
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore


import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.repeatOnLifecycle
import com.example.itv.data.local.ItvDatabase
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.example.itv.presentation.permissions.PermissionSettingsActivity
import com.example.itv.ui.theme.ITVTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

private val ChartSurface = Color(0xFFFDFDFF)
internal val RecordContentGreen = Color(0xFFEAF4D8)
private val ChartInk = Color(0xFF1E2433)
private val ChartSecondary = Color(0xFF505A74)
private val ChartGrid = Color(0xFFE2E6F0)
private fun periodColor(days: Int): Color = when (days) {
    1 -> Color(0xFF1751D0)
    7 -> Color(0xFFE2661E)
    else -> Color(0xFF0E9E8F)
}
class UsageReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ITVTheme {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        TextButton(onClick = { finish() }) { Text("‹ 돌아가기") }
                        UsageRecordsContent(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Does not own a vertical scroll or Scaffold: safe to place inside the Records tab. */
@Composable
fun UsageRecordsContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val goalFlow = remember(context) { com.example.itv.composition.goalService(ItvDatabase.get(context)).observeHomeGoals("demo-guest") }
    val comparisonGoals by goalFlow.collectAsState(initial = emptyList())
    var todayForComparison by remember { mutableStateOf<Report?>(null) }
    var shortformSummary by remember { mutableStateOf<ShortformPersonalStatsStore.PersonalizedSummary?>(null) }
    var nights by remember { mutableStateOf<NightReport?>(null) }
    var nightError by remember { mutableStateOf<String?>(null) }
    var days by rememberSaveable { mutableIntStateOf(1) }
    var summaryOpen by rememberSaveable { mutableStateOf(false) }
    var shortformOpen by rememberSaveable { mutableStateOf(true) }
    var hourlyOpen by rememberSaveable { mutableStateOf(false) }
    var goalsOpen by rememberSaveable { mutableStateOf(false) }
    var nightOpen by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var report by remember { mutableStateOf<Report?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }

    // Runs only while this tab is composed and the Activity is resumed.
    val owner = context.hostActivity()
    LaunchedEffect(owner) {
        val host = owner ?: return@LaunchedEffect
        host.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            revision++
            while (isActive) {
                delay(60.seconds)
                revision++
            }
        }
    }
    LaunchedEffect(days) { selected = null }
    LaunchedEffect(days, revision) {
        loading = true
        if (report?.days != days) report = null
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                val period = context.queryUsageReport(days)
                val todayRep = if (days == 1) period else context.queryUsageReport(1)
                val sfSummary = runCatching { ShortformPersonalStatsStore.loadSummary(context, todayRep, period) }.getOrNull()
                Triple(period, todayRep, sfSummary)
            }
            report = result.first
            todayForComparison = result.second
            if (result.third != null) shortformSummary = result.third
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            report = null
            todayForComparison = null
            shortformSummary = withContext(Dispatchers.IO) {
                runCatching { ShortformPersonalStatsStore.loadSummary(context, null, null) }.getOrNull()
            }
            error = e.message ?: "사용량을 불러오지 못했습니다."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(revision) {
        nights = null
        nightError = null
        try {
            nights = withContext(Dispatchers.IO) { queryNightReport(context, 0) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { nightError = "화면 기록을 조회하지 못했습니다." }
    }

    // Keep drilldown state outside lazy items so off-screen disposal cannot reset it.
    val dailyDetailItems = report?.takeIf { days > 1 }?.let { rememberDailyUsageDrilldownItems(it) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {item {

        Text("기록", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

}
item(key="ai_history_open") {
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
  OutlinedButton(onClick={context.startActivity(Intent(context,AiHistoryActivity::class.java))}) {Text("AI",fontWeight=FontWeight.Bold)}
 }
}
item {

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "오늘", 7 to "1주일", 30 to "1달").forEach { (n, title) ->
                val periodColor = periodColor(n)
                FilterChip(
                    selected = days == n,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = periodColor.copy(alpha = 0.03f),
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedContainerColor = periodColor.copy(alpha = 0.18f),
                        selectedLabelColor = ChartInk
                    ),
                    border = BorderStroke(
                        if (days == n) 2.5.dp else 1.dp,
                        if (days == n) periodColor else periodColor.copy(alpha = 0.25f)
                    ),
                    onClick = { days = n },
                    label = { Text(title, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

}
item {
        Text(
            if (days == 1) "오늘 00:00부터 조회 시점까지" else "오늘을 포함한 최근 ${days}일 · 시간대별 하루 평균",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

}
item {

        if (loading && report == null) {
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text("사용량을 불러오고 있어요.")
                }
            }
        }

}
item {
        error?.let { message ->
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("사용량을 확인할 수 없어요", fontWeight = FontWeight.Bold)
                    Text(message)
                    Text("동의와 사용 정보 접근 권한을 확인해 주세요. 조회 실패는 0분 사용을 뜻하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(context, PermissionSettingsActivity::class.java))
                    }) { Text("동의·권한 설정 열기") }
                }
            }
        }

}
item(key = "summary_toggle") {
    RecordSectionToggle("🕒", "요약", periodColor(days), summaryOpen) { summaryOpen = !summaryOpen }
}
report?.let { data ->
if (summaryOpen) {
item {

            OutlinedCard(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.outlinedCardColors(containerColor = RecordContentGreen, contentColor = ChartInk)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("기간 요약", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    SummaryValue("확인된 총사용시간", summaryDuration(data.total))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (data.days > 1) {
                        SummaryValue("하루 평균", summaryDuration(data.total / data.days))
                    }
                    UsageHighlights(data)

                }
            }

}
}
}
item(key = "shortform_personal_toggle") {
    RecordSectionToggle("🎬", "숏폼 사용량", periodColor(days), shortformOpen) { shortformOpen = !shortformOpen }
}
if (shortformOpen) {
item(key = "shortform_personal_section") {
    ShortformPersonalUsageSection(shortformSummary, days)
}
}
item(key = "hourly_toggle") {
    RecordSectionToggle("📊", "시간대별 사용량", periodColor(days), hourlyOpen) { hourlyOpen = !hourlyOpen }
}
report?.let { data ->
if (hourlyOpen) {
if (days > 1) {
dailyDetailItems?.invoke(this)
} else {
item(key = "hourly_usage_chart", contentType = "hourly_chart") {
            UsageChart(data, selected) { selected = it }

}

        
            val detailHour = selected
            if (detailHour != null && detailHour in data.values.indices) {
                item(key = "detail_header") {
                    TextButton(onClick = { selected = null }) { Text("시간대별 상세 접기") }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$detailHour:00–${detailHour + 1}:00", fontWeight = FontWeight.Bold)
                        Text("${if (data.days == 1) "오늘 사용시간" else "하루 평균 사용시간"} ${hourlyDuration(data.values[detailHour])}")
                        Text(if (data.days == 1) "이 시간대에 사용한 앱" else "앱별 시간대 하루 평균", style = MaterialTheme.typography.titleMedium)
                    }
                }
                item(key = "today_shortform_hour_$detailHour") {
                    ShortformHourPanel(LocalDate.now().toString(), detailHour)
                }
                val apps = data.hourlyApps.getOrNull(detailHour).orEmpty().filter { it.minutes >= 1.0 }
                if (apps.isEmpty()) item { Text("확인된 앱 사용 내역이 없습니다.") }
                items(count = apps.size, key = { index -> "app_${apps[index].packageName}" }) { index ->
                    AppUsageCard(apps[index], periodColor(data.days))
                }
            }

}
}
}
item(key = "goals_toggle") {
    RecordSectionToggle("🚩", "목표와 비교", periodColor(days), goalsOpen) { goalsOpen = !goalsOpen }
}
if (goalsOpen) {
item(key = "goal_comparison") {
    GoalComparisonSection(comparisonGoals, report, todayForComparison)
}
}
item(key = "night_toggle") {
    RecordSectionToggle("🛏️", "취침 추정", periodColor(days), nightOpen) { nightOpen = !nightOpen }
}
if (nightOpen) {
item(key = "night_comparison") {
    NightSummary(nights, nightError, days)
}
}

    }
}

@Composable
private fun SummaryValue(title: String, value: String) {
    // Vertical values avoid collisions with long durations and large system fonts.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun UsageChart(data: Report, selected: Int?, onSelect: (Int) -> Unit) {
    val barColor = periodColor(data.days)
    val selectionColor = barColor.copy(alpha = 0.10f)
    val axisMax = 60.0
    // A fixed light plot keeps the chart ink/marks legible even under a dark app theme.
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, ChartGrid),
        colors = CardDefaults.cardColors(containerColor = RecordContentGreen, contentColor = ChartInk)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("시간대별 사용량", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (data.days == 1) "오늘 사용시간 · 분" else "시간대별 하루 평균 · 분",
                style = MaterialTheme.typography.bodySmall, color = ChartSecondary)
            Text("좌우로 밀어 전체 시간대를 확인하고, 막대를 터치해 보세요.",
                style = MaterialTheme.typography.bodySmall, color = ChartSecondary)
            Row(Modifier.fillMaxWidth()) {
                // Y-axis stays visible while the 24 hourly bars scroll horizontally.
                Column(Modifier.width(44.dp)) {
                    Box(Modifier.height(180.dp).fillMaxWidth()) {
                        for (tick in 0..4) {
                            Text(
                                text = (axisMax * (4 - tick) / 4).roundToLong().toString(),
                                modifier = Modifier.offset(y = (-8 + tick * 45).dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = ChartSecondary
                            )
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
                LazyRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(data.values, key = { hour, _ -> hour }) { hour, value ->
                        val height = (if (value >= 1.0) value.coerceIn(0.0, axisMax) / axisMax * 180 else 0.0).toFloat().dp
                        Column(
                            Modifier.width(72.dp).clickable { onSelect(hour) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier.height(180.dp).fillMaxWidth()
                                    .background(if (selected == hour) selectionColor else ChartSurface)
                                    .drawBehind {
                                        for (tick in 0..4) {
                                            val y = tick * 45.dp.toPx()
                                            drawLine(ChartGrid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                                        }
                                    },
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(Modifier.width(24.dp).height(height).background(barColor,
                                    RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)))
                                if (value >= 1.0) {
                                    Text("${value.roundToLong()}분",
                                        modifier = Modifier.align(Alignment.BottomCenter)
                                            .offset(y = -minOf(height.value + 4f, 158f).dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ChartInk, textAlign = TextAlign.Center)
                                }
                            }
                            Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                                Text("${hour}시", style = MaterialTheme.typography.labelSmall,
                                    color = ChartSecondary, fontWeight = if (selected == hour) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
            if (selected == null) Text("막대를 터치하면 아래에 상세 사용시간이 표시됩니다.",
                style = MaterialTheme.typography.bodySmall, color = ChartSecondary)


        }
    }
}

internal fun Context.hostActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.hostActivity() else null
    else -> null
}


// Round the complete duration first, so 59.5 minutes carries into the next hour.
internal fun summaryDuration(minutes: Double): String {
    val roundedMinutes = minutes.roundToLong()
    val hours = roundedMinutes / 60
    val remainingMinutes = roundedMinutes % 60
    return "${hours}시간 ${remainingMinutes}분"
}

private data class AppDisplayInfo(val name: String, val icon: Bitmap?)

@Composable
internal fun AppUsageCard(app: AppHourUsage, accent: Color, iconOnly: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val display by produceState<AppDisplayInfo?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val name = try {
                val info = pm.getApplicationInfo(app.packageName, 0)
                pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() && it != app.packageName }
                    ?: "이름 없는 앱"
            } catch (_: Exception) {
                if (app.label != app.packageName && app.label.isNotBlank()) app.label
                else "앱 정보 확인 불가"
            }
            val icon = try {
                val drawable = try { pm.getApplicationIcon(app.packageName) }
                    catch (_: Exception) { pm.defaultActivityIcon }
                val pixels = (40 * context.resources.displayMetrics.density).toInt().coerceIn(40, 192)
                createBitmap(pixels, pixels).also { bitmap ->
                    drawable.setBounds(0, 0, pixels, pixels)
                    drawable.draw(android.graphics.Canvas(bitmap))
                }
            } catch (_: Exception) { null }
            AppDisplayInfo(name, icon)
        }
    }
    if (iconOnly) {
        val icon = display?.icon
        if (icon != null) Image(icon.asImageBitmap(), contentDescription = display?.name,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        else Surface(Modifier.size(40.dp), shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = 0.10f)) {
            Box(contentAlignment = Alignment.Center) { Text("앱") }
        }
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = RecordContentGreen,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val icon = display?.icon
            if (icon != null) {
                Image(bitmap = icon.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            } else {
                Surface(Modifier.size(40.dp), color = accent.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(10.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("앱", color = ChartInk) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(display?.name ?: "앱 정보 불러오는 중", style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, color = ChartInk,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${hourlyDuration(app.minutes)}  ·  ${entryLabel(app)}", style = MaterialTheme.typography.bodySmall,
                    color = ChartSecondary)
            }
        }
    }
}

private fun timeGroup(hour: Int): String = when (hour) {
    in 0..5 -> "새벽"
    in 6..11 -> "오전"
    in 12..17 -> "오후"
    else -> "저녁"
}

private fun busyHourGroups(values: List<Double>): List<List<Int>> {
    val peak = values.maxOrNull() ?: 0.0
    if (peak < 1.0) return emptyList()
    val thresholdHours = values.indices.filter { values[it] >= 40.0 }
    val hours = thresholdHours.ifEmpty { values.indices.filter { kotlin.math.abs(values[it] - peak) < 0.000001 } }
    val groups = mutableListOf<MutableList<Int>>()
    for (hour in hours) {
        val previous = groups.lastOrNull()?.lastOrNull()
        if (previous != null && hour == previous + 1 && timeGroup(hour) == timeGroup(previous)) groups.last().add(hour)
        else groups.add(mutableListOf(hour))
    }
    return groups
}

@Composable
private fun UsageHighlights(data: Report) {
    val accent = periodColor(data.days)
    HorizontalDivider()
    Text(if (data.days == 1) "많이 사용한 시간대" else "많이 사용한 시간대 · 하루 평균",
        fontWeight = FontWeight.SemiBold)
    val groups = busyHourGroups(data.values)
    if (groups.isEmpty()) Text("1분 이상 확인된 사용 내역이 없습니다.")
    groups.forEach { hours ->
        val totals = mutableMapOf<String, Double>()
        hours.forEach { hour -> data.hourlyApps.getOrNull(hour).orEmpty().forEach {
            totals[it.packageName] = (totals[it.packageName] ?: 0.0) + it.minutes
        } }
        val top = totals.maxByOrNull { it.value }
        val range = if (hours.size == 1) "${hours.first()}시" else "${hours.first()}시~${hours.last()}시"
        Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.20f))) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${timeGroup(hours.first())}($range)", Modifier.weight(1f))
                top?.let { AppUsageCard(AppHourUsage(it.key, "", it.value), accent, iconOnly = true) }
            }
        }
    }
    if (data.days > 1) {
        val sorted = data.dailyTotals.entries.sortedBy { it.key }
        val most = sorted.maxByOrNull { it.value }
        val least = sorted.minByOrNull { it.value }
        fun dayLabel(entry: Map.Entry<LocalDate, Double>?): String = entry?.let {
            "${it.key.monthValue}월 ${it.key.dayOfMonth}일 · ${summaryDuration(it.value)}"
        } ?: "확인된 기록 없음"
        Text("가장 많이 사용한 날", style = MaterialTheme.typography.labelLarge)
        Text(dayLabel(most))
        Text("가장 적게 사용한 날", style = MaterialTheme.typography.labelLarge)
        Text(dayLabel(least))
        Text("날짜 비교는 사용 구간이 확인된 날 기준이며, 오늘은 현재까지의 기록입니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun entryLabel(app: AppHourUsage): String {
    val average = app.entryAverage
    return if (average == null) "진입 ${app.entryCount}회"
    else "진입 평균 ${String.format(Locale.KOREA, "%.1f", average)}회/일"
}

@Composable
private fun RecordSectionToggle(icon: String, title: String, periodTint: Color, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = periodTint.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(icon, style = MaterialTheme.typography.headlineSmall)
            Text(title, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (expanded) "접기 ∧" else "펼치기 ∨", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Hour-bin labels use minutes consistently with the chart axis. */
internal fun hourlyDuration(minutes: Double): String = "${minutes.roundToLong()}분"
