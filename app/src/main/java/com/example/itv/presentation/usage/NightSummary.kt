package com.example.itv.presentation.usage
import com.example.itv.domain.records.*
import com.example.itv.composition.queryNightReport
import com.example.itv.composition.queryUsageReport


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import java.time.*
import kotlin.math.roundToLong


@Composable
internal fun NightSummary(report: NightReport?, error: String?, days: Int) {
    val context = LocalContext.current
    val confirmationService = remember(context) { com.example.itv.composition.sleepConfirmationService(context) }
    var revision by remember { mutableStateOf(0) }
    var confirmations by remember { mutableStateOf<Map<String, SleepConfirmation>>(emptyMap()) }
    var summaryError by remember { mutableStateOf<String?>(null) }
    val summaryToday = Instant.ofEpochMilli(report?.now ?: System.currentTimeMillis())
        .atZone(report?.zone ?: ZoneId.systemDefault()).toLocalDate()
    LaunchedEffect(report, revision, summaryToday) {
        try {
            val dates = ((0L..maxOf(7L, days.toLong())).map { summaryToday.minusDays(it).toString() } +
                report?.segments.orEmpty().map { it.wakeDate.toString() }).distinct()
            confirmations = withContext(Dispatchers.IO) { confirmationService.readDates(dates) }
            summaryError = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { summaryError = "수면 확인 기록 조회 실패 · 아래 값은 갱신되지 않았을 수 있습니다" }
    }
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(
        containerColor = RecordContentGreen, contentColor = Color(0xFF243025))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("수면 길이 · 사용자 확인 우선", style = MaterialTheme.typography.titleLarge)
            val bedtimeEstimate = remember(confirmations, report?.zone) {
                SleepSummaryPolicy.estimateBedtimeWindow(confirmations, report?.zone ?: ZoneId.systemDefault())
            }
            if (bedtimeEstimate != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC8DEC0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("평소 추정 취침 시각", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF1B4332))
                            Spacer(Modifier.weight(1f))
                            Text("확인된 기록 ${bedtimeEstimate.validCount}일", style = MaterialTheme.typography.labelSmall, color = Color(0xFF52796F))
                        }
                        Text(
                            text = "평균 취침 %02d:%02d ~ 기상 %02d:%02d (평균 %d시간 %d분)".format(
                                bedtimeEstimate.avgStartHour, bedtimeEstimate.avgStartMinute,
                                bedtimeEstimate.avgEndHour, bedtimeEstimate.avgEndMinute,
                                bedtimeEstimate.avgDurationMinutes / 60L, bedtimeEstimate.avgDurationMinutes % 60L
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2D6A4F)
                        )
                        Text(
                            text = "사용자가 직접 선택하거나 수정한 유효 수면 기록의 평균입니다. 평소 잠드는 시간 파악 및 누워서 시청하는 구간 추정에 활용됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF52796F)
                        )
                    }
                }
            }
            if (days != 1) {
            val averageDays = if (days == 30) 30 else 7
            val recentMinutes = (1L..averageDays.toLong()).map { offset ->
                val date = summaryToday.minusDays(offset)
                SleepSummaryPolicy.minutes(date,
                    report?.segments?.firstOrNull { it.wakeDate == date && it.end != null }?.minutes,
                    confirmations)
            }
            val average = SleepSummaryPolicy.average(recentMinutes)
            val averageRounded = average?.roundToLong()
            Text(if (averageRounded == null) "최근 ${averageDays}일 평균 수면: 기록 없음"
                else "최근 ${averageDays}일 평균 수면: ${averageRounded / 60}시간 ${averageRounded % 60}분",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("오늘 제외 · 기록 ${recentMinutes.count { it != null }}/${averageDays}일 · 미기록 제외 · 수면 없음은 0시간",
                style = MaterialTheme.typography.bodySmall)
            }
            summaryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val today = Instant.ofEpochMilli(report?.now ?: System.currentTimeMillis())
                .atZone(report?.zone ?: ZoneId.systemDefault()).toLocalDate()
            val rows = report?.segments.orEmpty().filter {
                it.end != null && if (days == 1) it.wakeDate == today
                else it.wakeDate >= today.minusDays(days.toLong()) && it.wakeDate < today
            }.sortedByDescending { it.wakeDate }
            var selectedDateKey by rememberSaveable(days) { mutableStateOf<String?>(null) }
            val selectedDate = selectedDateKey?.let { LocalDate.parse(it) }
            val selected = if (days == 1) rows.firstOrNull()
                else rows.firstOrNull { it.wakeDate == selectedDate }
            when {
                error != null -> Text("기록 조회 불가 · 사용 정보 권한을 확인해 주세요.")
                report == null -> Text("기록 조회 중…")
                rows.isEmpty() -> Text("표시할 완료된 유효 기록이 없습니다.")
                else -> {
                    if (days != 1) {
                    Text("오늘 제외 최근 ${days}일 · 유효 기록 ${rows.size}개 · 막대를 누르면 시계 표시", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.width(40.dp)) {
                            Box(Modifier.height(200.dp)) {
                                for (tick in 0..5) Text("${10-tick*2}",
                                    modifier = Modifier.offset(y = (tick*40-8).dp),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Text("시간", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rows.forEach { row ->
                                val shownMinutes = SleepSummaryPolicy.minutes(row.wakeDate, row.minutes, confirmations) ?: row.minutes
                                Column(Modifier.clickable { selectedDateKey = row.wakeDate.toString() }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.width(54.dp).height(200.dp).background(Color.White),
                                        contentAlignment = Alignment.BottomCenter) {
                                        Canvas(Modifier.fillMaxSize()) {
                                            for(tick in 0..5) {
                                                val y = size.height * tick / 5f
                                                drawLine(Color(0xFFE2E5EA), Offset(0f,y), Offset(size.width,y))
                                            }
                                        }
                                        Box(Modifier.width(22.dp)
                                            .height((shownMinutes / 60.0).coerceIn(0.0,10.0).toFloat().times(20).dp)
                                            .background(Color(0xFF4263EB)))
                                    }
                                    Text("${shownMinutes.roundToLong() / 60}시간 ${shownMinutes.roundToLong() % 60}분",
                                        style = MaterialTheme.typography.labelSmall)
                                    Text("${row.wakeDate.monthValue}.${row.wakeDate.dayOfMonth}",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    }
                    if (days != 1 && selectedDateKey != null) {
                        TextButton(onClick = { selectedDateKey = null }) { Text("비사용 시계 상세 접기") }
                    }
                    selected?.let { row ->
                        val minutes = (SleepSummaryPolicy.minutes(row.wakeDate, row.minutes, confirmations) ?: row.minutes).roundToLong()
                        Text("${row.wakeDate.monthValue}.${row.wakeDate.dayOfMonth} · 수면 ${minutes / 60}시간 ${minutes % 60}분",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val otherCandidates = report!!.candidates.filter {
                            it.wakeDate == row.wakeDate && it.end != null &&
                                !(it.start == row.start && it.end == row.end)
                        }.sortedBy { it.start }
                        val confirmation = confirmations[row.wakeDate.toString()]
                        val displayRow = if (confirmation?.choice == "SLEEP" && confirmation.startMs != null && confirmation.endMs != null)
                            row.copy(start = confirmation.startMs, end = confirmation.endMs,
                                minutes = (confirmation.endMs - confirmation.startMs) / 60000.0,
                                briefWakeCount = (listOf(row) + otherCandidates).firstOrNull {
                                    it.start == confirmation.startMs && it.end == confirmation.endMs
                                }?.briefWakeCount ?: 0)
                            else row
                        SleepClock(displayRow, report.zone, if (confirmation == null) otherCandidates else emptyList(),
                            showMain = confirmation?.choice != "NOT_SLEEP",
                            confirmed = confirmation != null)
                        SleepConfirmationPanel(row.wakeDate, report.zone,
                            listOf(row) + otherCandidates, onSaved = { revision++ })

                    }
                }
            }
            Text("화면 꺼짐부터 다음 1분 이상 사용 시작까지 3시간 이상인 완료 구간 기준 · 1분 미만 사용 후 화면을 끄면 같은 구간으로 연결(잠깐 사용한 시간 포함) · 잠금 해제 없는 화면 켜짐은 횟수 제외 · 실제 수면 측정 아님 · 날짜는 1분 이상 사용이 확인된 사용 시작일(비사용 종료일) 기준 · 자정·정오에 구간을 자르지 않음 · 10시간 초과 막대는 상단 제한",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SleepClock(row: NightSegment, zone: ZoneId, others: List<NightSegment>,
    showMain: Boolean = true, confirmed: Boolean = false, showHeading: Boolean = true, onPick: ((NightSegment)->Unit)? = null) {
    var bubbleText by remember(row.start, row.end, others) { mutableStateOf<String?>(null) }
    var bubbleColor by remember { mutableStateOf(Color(0xFF35443B)) }
    val start = Instant.ofEpochMilli(row.start).atZone(zone)
    val end = Instant.ofEpochMilli(row.end!!).atZone(zone)
    val startMinute = start.hour * 60 + start.minute + start.second / 60f
    val sweep = (row.minutes / 1440.0 * 360).coerceIn(0.0, 360.0).toFloat()
    val startAngle = startMinute / 1440f * 360 - 90
    val midpoint = Math.toRadians((startAngle + sweep / 2).toDouble())
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeading) Text("24시간 비사용 시계", style = MaterialTheme.typography.titleMedium)
        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
            bubbleText?.let { message ->
                Popup(
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(0, -24),
                    onDismissRequest = { bubbleText = null },
                    properties = PopupProperties(
                        focusable = false,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true
                    )
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = bubbleColor,
                        shadowElevation = 6.dp,
                        border = androidx.compose.foundation.BorderStroke(2.dp, bubbleColor)
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            Canvas(Modifier.fillMaxSize().pointerInput(row.start, row.end, zone, others, showMain, confirmed, onPick) {
                detectTapGestures { point ->
                    val dx = point.x - size.width / 2f
                    val dy = point.y - size.height / 2f
                    val radius = minOf(size.width, size.height) * 0.78f / 2f
                    val angle = ((Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0)
                    fun contains(segment: NightSegment): Boolean {
                        val local = Instant.ofEpochMilli(segment.start).atZone(zone)
                        val minute = local.hour * 60 + local.minute + local.second / 60f
                        val beginning = minute / 1440.0 * 360.0 - 90.0
                        val extent = (segment.minutes / 1440.0 * 360.0).coerceIn(0.0, 360.0)
                        return (angle - beginning + 720.0) % 360.0 <= extent
                    }
                    if (dx * dx + dy * dy <= radius * radius) {
                        val picked = if (showMain && contains(row)) row
                            else others.lastOrNull { contains(it) }
                        if (onPick != null) {
                            picked?.let { onPick(it) }
                            return@detectTapGestures
                        }
                        when {
                            showMain && contains(row) -> {
                                bubbleText = if (confirmed) "내가 선택한 주 수면" else "가장 긴 자동 수면 후보"
                                bubbleColor = Color(0xFFE35D62)
                            }
                            others.any { contains(it) } -> {
                                bubbleText = "같은 날짜의 다른 수면 후보"
                                bubbleColor = Color(0xFFF39A38)
                            }
                            else -> {
                                bubbleText = if (confirmed) "주 수면 외 시간 · 사용시간 아님" else "수면 후보 밖 시간 · 사용시간 아님"
                                bubbleColor = Color(0xFF66B978)
                            }
                        }
                    } else bubbleText = null
                }
            }) {
                val diameter = size.minDimension * 0.78f
                val left = (size.width-diameter)/2
                val top = (size.height-diameter)/2
                drawCircle(Color(0xFF35443B), radius = diameter/2 + 8.dp.toPx())
                drawCircle(Color(0xFF66B978), radius = diameter/2)
                // All candidates share the end date; draw alternatives first, main last.
                others.forEach { candidate ->
                    val candidateStart = Instant.ofEpochMilli(candidate.start).atZone(zone)
                    val minute = candidateStart.hour * 60 + candidateStart.minute + candidateStart.second / 60f
                    val candidateSweep = (candidate.minutes / 1440.0 * 360).coerceIn(0.0, 360.0).toFloat()
                    drawArc(Color(0xFFF39A38), minute / 1440f * 360 - 90, candidateSweep, true,
                        topLeft = Offset(left, top), size = Size(diameter, diameter))
                }
                if (showMain) drawArc(Color(0xFFE35D62), startAngle, sweep, true,
                    topLeft = Offset(left,top), size = Size(diameter,diameter))
                for (hour in 0 until 24) {
                    val angle = Math.toRadians(hour*15.0-90)
                    val outer = diameter/2-5.dp.toPx()
                    val inner = outer - if(hour%6==0) 10.dp.toPx() else 4.dp.toPx()
                    drawLine(Color.White,
                        Offset(center.x+cos(angle).toFloat()*inner, center.y+sin(angle).toFloat()*inner),
                        Offset(center.x+cos(angle).toFloat()*outer, center.y+sin(angle).toFloat()*outer), 2.dp.toPx())
                }
            }
            Text("00", Modifier.align(Alignment.TopCenter), style = MaterialTheme.typography.labelMedium)
            Text("06", Modifier.align(Alignment.CenterEnd), style = MaterialTheme.typography.labelMedium)
            Text("12", Modifier.align(Alignment.BottomCenter), style = MaterialTheme.typography.labelMedium)
            Text("18", Modifier.align(Alignment.CenterStart), style = MaterialTheme.typography.labelMedium)
            if (showMain) Column(Modifier.offset(x = (cos(midpoint)*60).toFloat().dp,
                y = (sin(midpoint)*60).toFloat().dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Zzz", color = Color(0xFF341519), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text("${row.briefWakeCount}회", color = Color(0xFF341519),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showMain) Text("${start.monthValue}월 ${start.dayOfMonth}일 %02d:%02d ~ ${end.monthValue}월 ${end.dayOfMonth}일 %02d:%02d".format(start.hour, start.minute, end.hour, end.minute),
            textAlign = TextAlign.Center)

    }
}

private fun nightRangeLabel(row: NightSegment, zone: ZoneId): String {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("M월 d일 HH:mm")
    val start = Instant.ofEpochMilli(row.start).atZone(zone).format(formatter)
    val end = row.end?.let { Instant.ofEpochMilli(it).atZone(zone).format(formatter) } ?: "진행 중"
    return "$start ~ $end"
}

private fun nightLengthLabel(row: NightSegment): String {
    val minutes = row.minutes.toLong()
    return "${minutes / 60}시간 ${minutes % 60}분"
}
