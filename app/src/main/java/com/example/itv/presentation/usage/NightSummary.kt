package com.example.itv.presentation.usage

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.itv.composition.queryNightReport
import com.example.itv.domain.records.*
import com.example.itv.infrastructure.storage.MonthlyUsageArchiveStore
import com.example.itv.presentation.components.ItvCardSurface
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.ItvInkBorder
import com.example.itv.presentation.components.ItvInkShadow
import com.example.itv.presentation.components.ItvInsetSurface
import com.example.itv.presentation.components.ItvLine
import com.example.itv.presentation.components.ItvSeasonalCalendarBanner
import com.example.itv.presentation.components.ItvSeasonalRoofTopAccent
import com.example.itv.presentation.components.ItvTactileButton
import com.example.itv.presentation.components.ItvTactileCard
import com.example.itv.presentation.components.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.*
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.time.Duration.Companion.seconds

private val SLEEP_DAYS_OF_WEEK = listOf(
    DayOfWeek.MONDAY to "월",
    DayOfWeek.TUESDAY to "화",
    DayOfWeek.WEDNESDAY to "수",
    DayOfWeek.THURSDAY to "목",
    DayOfWeek.FRIDAY to "금",
    DayOfWeek.SATURDAY to "토",
    DayOfWeek.SUNDAY to "일"
)

private fun Context.findHostActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/**
 * Dedicated Sleep tab ("취침") displaying monthly sleep records with month-by-month navigation
 * matching the Calendar tab ("‹ YYYY년 M월 ›").
 * Detailed policy explanations (e.g. user-confirmation priority, averages, archive integration) live inside `?` help dialogs.
 */
@Composable
fun SleepEstimationTabContent(
    modifier: Modifier = Modifier,
    onSleepDataChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val isDark = LocalDarkTheme.current
    val inkColor = if (isDark) Color(0xFFE2EBE4) else Color(0xFF241E19)
    var nights by remember { mutableStateOf<NightReport?>(null) }
    var nightError by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var showOverviewHelp by remember { mutableStateOf(false) }

    val today = remember(revision) { LocalDate.now() }
    val currentMonth = remember(today) { YearMonth.from(today) }
    var selectedMonthStr by rememberSaveable { mutableStateOf(currentMonth.toString()) }
    val viewMonth = remember(selectedMonthStr, currentMonth) {
        runCatching { YearMonth.parse(selectedMonthStr) }.getOrDefault(currentMonth)
    }

    val owner = context.findHostActivity()
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

    LaunchedEffect(revision) {
        nightError = null
        try {
            val loadedNights = withContext(Dispatchers.IO) {
                val raw = queryNightReport(context, 0)
                val boundaryMs = com.example.itv.infrastructure.storage.AccountDataBoundaryStore.getFreshStartEpochMs(context)
                com.example.itv.infrastructure.storage.AccountDataBoundaryStore.filterNightReport(raw, boundaryMs)
            }
            nights = loadedNights
            withContext(Dispatchers.IO) {
                runCatching {
                    MonthlyUsageArchiveStore.syncDailySnapshot(
                        context = context,
                        todayUsageReport = null,
                        periodUsageReport = null,
                        nightReport = loadedNights
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            nightError = "화면 기록을 조회하지 못했습니다."
        }
    }

    if (showOverviewHelp) {
        SleepRulesHelpDialog(
            title = "취침 · 수면 분석 안내",
            onDismiss = { showOverviewHelp = false }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "sleep_tab_header", contentType = "sleep_tab_header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
            ) {
                Text(
                    text = "취침",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = inkColor,
                    modifier = Modifier.align(Alignment.Center)
                )
                ItvTactileButton(
                    onClick = { showOverviewHelp = true },
                    shape = CircleShape,
                    containerColor = if (isDark) Color(0xFF28362B) else Color.White,
                    borderColor = ItvInkBorder,
                    shadowColor = ItvInkShadow,
                    borderWidth = 1.6.dp,
                    shadowOffsetX = 1.dp,
                    shadowOffsetY = 2.dp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(34.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "?",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = ItvGreen
                        )
                    }
                }
            }
        }

        item(key = "sleep_month_navigator", contentType = "sleep_month_navigator") {
            val navBtnBg = if (isDark) Color(0xFF2B3B31) else Color.White
            Column(modifier = Modifier.fillMaxWidth()) {
                ItvSeasonalRoofTopAccent(modifier = Modifier.padding(horizontal = 4.dp))
                ItvTactileCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    containerColor = ItvCardSurface,
                    contentColor = inkColor,
                    borderColor = ItvInkBorder,
                    shadowColor = ItvInkShadow,
                    borderWidth = 1.8.dp,
                    shadowOffsetX = 2.dp,
                    shadowOffsetY = 3.5.dp
                ) {
                    ItvSeasonalCalendarBanner {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ItvTactileButton(
                                onClick = { selectedMonthStr = viewMonth.minusMonths(1).toString() },
                                modifier = Modifier.size(38.dp),
                                shape = CircleShape,
                                containerColor = navBtnBg,
                                borderColor = ItvInkBorder,
                                shadowColor = ItvInkShadow,
                                borderWidth = 1.5.dp,
                                shadowOffsetX = 1.dp,
                                shadowOffsetY = 2.dp
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("‹", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = inkColor)
                                }
                            }

                            Text(
                                text = "${viewMonth.year}년 ${viewMonth.monthValue}월",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = inkColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )

                            val canGoNext = viewMonth < currentMonth
                            ItvTactileButton(
                                onClick = {
                                    if (canGoNext) {
                                        selectedMonthStr = viewMonth.plusMonths(1).toString()
                                    }
                                },
                                enabled = canGoNext,
                                modifier = Modifier.size(38.dp),
                                shape = CircleShape,
                                containerColor = if (canGoNext) navBtnBg else navBtnBg.copy(alpha = 0.45f),
                                borderColor = if (canGoNext) ItvInkBorder else ItvLine,
                                shadowColor = ItvInkShadow,
                                borderWidth = 1.5.dp,
                                shadowOffsetX = 1.dp,
                                shadowOffsetY = 2.dp
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "›",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (canGoNext) inkColor else inkColor.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "sleep_estimation_body", contentType = "sleep_estimation_body") {
            NightSummary(
                report = nights,
                error = nightError,
                days = viewMonth.lengthOfMonth(),
                selectedMonth = viewMonth,
                onSleepDataChanged = {
                    revision++
                    onSleepDataChanged()
                }
            )
        }
    }
}

@Composable
internal fun NightSummary(
    report: NightReport?,
    error: String?,
    days: Int,
    selectedMonth: YearMonth? = null,
    onSleepDataChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val confirmationService = remember(context) { com.example.itv.composition.sleepConfirmationService(context) }
    var revision by remember { mutableStateOf(0) }
    var confirmations by remember { mutableStateOf<Map<String, SleepConfirmation>>(emptyMap()) }
    var archivedRecords by remember { mutableStateOf<List<ArchivedSleepRecord>>(emptyList()) }
    var summaryError by remember { mutableStateOf<String?>(null) }
    val zone = report?.zone ?: ZoneId.systemDefault()
    val summaryToday = Instant.ofEpochMilli(report?.now ?: System.currentTimeMillis())
        .atZone(zone).toLocalDate()

    LaunchedEffect(report, revision, summaryToday, days, selectedMonth) {
        try {
            val loaded = withContext(Dispatchers.IO) {
                val allArchived = MonthlyUsageArchiveStore.getAllDailyRecords(context)
                val parsedArchived = allArchived.values.mapNotNull { rec ->
                    val bed = rec.bedtimeMinute ?: return@mapNotNull null
                    val wake = rec.wakeupMinute ?: return@mapNotNull null
                    val d = runCatching { LocalDate.parse(rec.date) }.getOrNull() ?: return@mapNotNull null
                    ArchivedSleepRecord(wakeDate = d, bedtimeMinute = bed, wakeupMinute = wake)
                }
                val monthDates = selectedMonth?.let { ym ->
                    (1..ym.lengthOfMonth()).map { ym.atDay(it).toString() }
                }.orEmpty()
                val dates = (
                    (0L..maxOf(30L, days.toLong())).map { summaryToday.minusDays(it).toString() } +
                        monthDates +
                        report?.segments.orEmpty().map { it.wakeDate.toString() } +
                        allArchived.keys
                    ).distinct()
                val confs = confirmationService.readDates(dates)
                parsedArchived to confs
            }
            archivedRecords = loaded.first
            confirmations = loaded.second
            summaryError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            summaryError = "수면 확인 기록 조회 실패 · 아래 값은 갱신되지 않았을 수 있습니다"
        }
    }

    val mergedSleep = remember(report, archivedRecords, confirmations, zone) {
        CalendarDayCyclePolicy.mergeArchivedSleepData(
            liveSegments = report?.segments.orEmpty(),
            liveCandidates = report?.candidates.orEmpty(),
            archivedRecords = archivedRecords,
            confirmations = confirmations,
            zone = zone
        )
    }

    val isDark = LocalDarkTheme.current
    val cardContentColor = if (isDark) Color(0xFFE6EFE8) else Color(0xFF241E19)
    ItvTactileCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        containerColor = ItvCardSurface,
        contentColor = cardContentColor,
        borderColor = ItvInkBorder,
        shadowColor = ItvInkShadow,
        borderWidth = 2.dp,
        shadowOffsetX = 2.dp,
        shadowOffsetY = 4.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var showSleepRhythmHelp by remember { mutableStateOf(false) }
            var showBedtimeEstimateHelp by remember { mutableStateOf(false) }
            var showSleepClockHelp by remember { mutableStateOf(false) }

            val nowZdt = remember(report?.now, zone) {
                Instant.ofEpochMilli(report?.now ?: System.currentTimeMillis()).atZone(zone)
            }
            val bedtimeEstimate = remember(confirmations, mergedSleep.candidates, zone) {
                SleepSummaryPolicy.estimateBedtimeWindow(
                    confirmations = confirmations,
                    candidates = mergedSleep.candidates,
                    zone = zone
                )
            }
            val weeklySleepProfile = remember(confirmations, mergedSleep.candidates, zone) {
                SleepSummaryPolicy.estimateWeeklySleepProfile(
                    confirmations = confirmations,
                    candidates = mergedSleep.candidates,
                    zone = zone
                )
            }
            val adaptiveTarget = remember(weeklySleepProfile, nowZdt) {
                SleepSummaryPolicy.getAdaptiveBedtimeTarget(
                    weeklyProfile = weeklySleepProfile,
                    now = nowZdt
                )
            }

            // 1. 요일별 수면 리듬 & 내일 취침 예측 카드
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ItvInsetSurface,
                border = BorderStroke(1.dp, ItvLine),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "내 요일별 수면 리듬 & 취침 예측",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFF85E3B3) else Color(0xFF1B4332)
                        )
                        Spacer(Modifier.weight(1f))
                        HelpBadgeButton(isDark = isDark) { showSleepRhythmHelp = true }
                    }

                    // Adaptive Target Highlight Banner (오늘 밤 추정 취침 & 기상 기준 권장 취침)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isDark) Color(0xFF22362B) else Color(0xFFE1F3E6),
                        border = BorderStroke(1.dp, if (isDark) Color(0xFF4A735A) else Color(0xFF97CDA8)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "🌙 오늘 밤 추정 취침:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFFE2EBE4) else Color(0xFF1B4332)
                                )
                                Text(
                                    "%02d:%02d".format(adaptiveTarget.targetHour, adaptiveTarget.targetMinute),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isDark) Color(0xFF5ED38C) else Color(0xFF2D6A4F)
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "예상 기상 %02d:%02d".format(
                                        adaptiveTarget.expectedWakeHour,
                                        adaptiveTarget.expectedWakeMinute
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDark) Color(0xFFA5C4B5) else Color(0xFF52796F)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "✨ 기상 기준 권장 취침:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) Color(0xFFCDE6D6) else Color(0xFF2D5A44)
                                )
                                Text(
                                    adaptiveTarget.formatRecommendedRange(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFF85E3B3) else Color(0xFF1B4332)
                                )
                                Text(
                                    "(7~8시간 수면 기준)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDark) Color(0xFFA5C4B5) else Color(0xFF52796F)
                                )
                            }
                            Text(
                                "• ${adaptiveTarget.sourceDescription}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isDark) Color(0xFFA5C4B5) else Color(0xFF52796F)
                            )
                        }
                    }

                    // 7-day Monday ~ Sunday Horizontal Chips
                    Text(
                        "요일별 기상 시각 & 수면 패턴",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) Color(0xFFE2EBE4) else Color(0xFF243025)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SLEEP_DAYS_OF_WEEK.forEach { (dow, dowName) ->
                            val profile = weeklySleepProfile?.dayProfiles?.get(dow)
                            val isTargetTomorrow = dow == adaptiveTarget.targetWakeDayOfWeek
                            val chipBg = when {
                                isTargetTomorrow -> if (isDark) Color(0xFF2D4B3A) else Color(0xFFCBEBD5)
                                profile != null -> if (isDark) Color(0xFF24332A) else Color.White
                                else -> if (isDark) Color(0xFF1B241F) else Color(0xFFF1ECE3)
                            }
                            val chipBorder = when {
                                isTargetTomorrow -> if (isDark) Color(0xFF5ED38C) else Color(0xFF237A4B)
                                profile != null -> if (isDark) Color(0xFF40594A) else Color(0xFFCFC5B6)
                                else -> if (isDark) Color(0xFF2C3B32) else Color(0xFFDAD1C3)
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = chipBg,
                                border = BorderStroke(if (isTargetTomorrow) 1.5.dp else 1.dp, chipBorder),
                                modifier = Modifier.width(68.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            dowName,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when (dow) {
                                                DayOfWeek.SUNDAY -> Color(0xFFE53935)
                                                DayOfWeek.SATURDAY -> Color(0xFF1E88E5)
                                                else -> cardContentColor
                                            }
                                        )
                                        if (isTargetTomorrow) {
                                            Text(
                                                "내일",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isDark) Color(0xFF5ED38C) else Color(0xFF2E7D32)
                                            )
                                        }
                                    }
                                    if (profile != null) {
                                        Text(
                                            "%02d:%02d".format(profile.avgWakeHour, profile.avgWakeMinute),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isDark) Color(0xFF85E3B3) else Color(0xFF1B4332)
                                        )
                                        Text(
                                            "기상",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 9.sp,
                                            color = if (isDark) Color(0xFFA5C4B5) else Color(0xFF52796F)
                                        )
                                        Text(
                                            "${profile.avgDurationMinutes / 60}h (${profile.validCount}일)",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 9.sp,
                                            color = if (isDark) Color(0xFFA5C4B5) else Color(0xFF52796F)
                                        )
                                    } else {
                                        Text(
                                            "-",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDark) Color(0xFF6B7E72) else Color(0xFF9E9E9E)
                                        )
                                        Text(
                                            "미기록",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 9.sp,
                                            color = if (isDark) Color(0xFF6B7E72) else Color(0xFF9E9E9E)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showSleepRhythmHelp) {
                AlertDialog(
                    onDismissRequest = { showSleepRhythmHelp = false },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "?",
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFF85E3B3) else Color(0xFF1B4332)
                            )
                            Text("수면 리듬 예측 안내", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("• 오늘 밤 추정 취침: 내일 기상 요일의 평소 실제 취침 패턴을 바탕으로 계산한 시각입니다.", style = MaterialTheme.typography.bodyMedium)
                            Text("• 기상 기준 권장 취침: 내일 예상 기상 시각에서 성인 권장 수면량(7~8시간)을 역산한 권장 취침 시간대(예: 22:00~23:00)입니다.", style = MaterialTheme.typography.bodyMedium)
                            Text("• 취침 전 2시간 점증 가중: 권장 취침 시작 2시간 전부터 1시간 단위로 가중치(2~1시간 전 35%, 1~0시간 전 65%)가 커져 캘린더 점수에 반영됩니다.", style = MaterialTheme.typography.bodyMedium)
                            Text("• 장기 기록 통합 & 개입 연동: OS·아카이브 수면 기록을 통합해 권장 취침 시간대에 가까워질수록 개입 기준 확률도 자동으로 민감해집니다.", style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showSleepRhythmHelp = false }) {
                            Text("확인", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            // 2. 평소 추정 취침 시각 (전체 평균)
            if (bedtimeEstimate != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ItvInsetSurface,
                    border = BorderStroke(1.dp, ItvLine),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "평소 추정 취침 시각 (전체 평균)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFF85E3B3) else Color(0xFF1B4332)
                            )
                            Spacer(Modifier.width(6.dp))
                            HelpBadgeButton(isDark = isDark, compact = true) { showBedtimeEstimateHelp = true }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${bedtimeEstimate.validCount}일 반영",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isDark) Color(0xFFA5C4B5) else Color(0xFF52796F)
                            )
                        }
                        Text(
                            text = "평균 취침 %02d:%02d ~ 기상 %02d:%02d (평균 %d시간 %d분)".format(
                                bedtimeEstimate.avgStartHour, bedtimeEstimate.avgStartMinute,
                                bedtimeEstimate.avgEndHour, bedtimeEstimate.avgEndMinute,
                                bedtimeEstimate.avgDurationMinutes / 60L, bedtimeEstimate.avgDurationMinutes % 60L
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFF5ED38C) else Color(0xFF2D6A4F)
                        )
                    }
                }
            }

            if (showBedtimeEstimateHelp) {
                AlertDialog(
                    onDismissRequest = { showBedtimeEstimateHelp = false },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "?",
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFF85E3B3) else Color(0xFF1B4332)
                            )
                            Text("평소 취침 시각 안내", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("• 직접 확인한 수면 기록(최우선), 밤사이 자동 감지된 단일 후보, 앱 장기 아카이브 기록을 종합한 전체 평균 취침/기상 시각입니다.", style = MaterialTheme.typography.bodyMedium)
                            Text("• 평소 잠드는 시간대를 기준으로 밤에 폰을 끄고 편안히 잘 수 있도록 야간 취침 개입과 캘린더 점수 기준에 활용됩니다.", style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showBedtimeEstimateHelp = false }) {
                            Text("확인", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            // 3. 수면 기록 & 비사용 시계 섹션 (사용자 확인 우선·평균 규칙 등은 ? 아이콘 안에 정리)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        selectedMonth != null -> "${selectedMonth.year}년 ${selectedMonth.monthValue}월 수면 기록"
                        days == 1 -> "오늘의 수면 기록"
                        else -> "최근 ${days}일 수면 기록"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFF85E3B3) else Color(0xFF1B4332)
                )
                Spacer(Modifier.weight(1f))
                HelpBadgeButton(isDark = isDark) { showSleepClockHelp = true }
            }

            val segmentsByDate = remember(mergedSleep) {
                buildMap<LocalDate, NightSegment> {
                    for (seg in mergedSleep.segments) {
                        if (seg.end != null && !containsKey(seg.wakeDate)) {
                            put(seg.wakeDate, seg)
                        }
                    }
                }
            }
            val candidatesByDate = remember(mergedSleep) {
                mergedSleep.candidates.filter { it.end != null }.groupBy { it.wakeDate }
            }

            if (selectedMonth != null) {
                val monthStats = remember(selectedMonth, summaryToday, segmentsByDate, confirmations) {
                    val todayMonth = YearMonth.from(summaryToday)
                    val monthDates = when {
                        selectedMonth.isAfter(todayMonth) -> emptyList()
                        selectedMonth == todayMonth -> (1..summaryToday.dayOfMonth).map { selectedMonth.atDay(it) }
                        else -> (1..selectedMonth.lengthOfMonth()).map { selectedMonth.atDay(it) }
                    }
                    val monthMinutes = monthDates.map { date ->
                        SleepSummaryPolicy.minutes(date, segmentsByDate[date]?.minutes, confirmations)
                    }
                    Triple(
                        SleepSummaryPolicy.average(monthMinutes)?.roundToLong(),
                        monthMinutes.count { it != null },
                        monthDates.size
                    )
                }
                val (averageRounded, recordedCount, totalMonthDays) = monthStats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (averageRounded == null) {
                            "${selectedMonth.monthValue}월 평균 수면: 기록 없음"
                        } else {
                            "${selectedMonth.monthValue}월 평균 수면 ${averageRounded / 60}시간 ${averageRounded % 60}분"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${recordedCount}/${totalMonthDays}일",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDark) Color(0xFFA5C4B5) else Color(0xFF52796F)
                    )
                }
            } else if (days != 1) {
                val averageDays = if (days == 30) 30 else 7
                val recentStats = remember(averageDays, summaryToday, segmentsByDate, confirmations) {
                    val recentMinutes = (1L..averageDays.toLong()).map { offset ->
                        val date = summaryToday.minusDays(offset)
                        SleepSummaryPolicy.minutes(date, segmentsByDate[date]?.minutes, confirmations)
                    }
                    SleepSummaryPolicy.average(recentMinutes)?.roundToLong() to recentMinutes.count { it != null }
                }
                val (averageRounded, recordedCount) = recentStats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (averageRounded == null) {
                            "평균 수면: 기록 없음"
                        } else {
                            "평균 수면 ${averageRounded / 60}시간 ${averageRounded % 60}분"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${recordedCount}/${averageDays}일",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDark) Color(0xFFA5C4B5) else Color(0xFF52796F)
                    )
                }
            }

            summaryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val today = summaryToday
            val rows = remember(mergedSleep, selectedMonth, days, today) {
                mergedSleep.segments.filter {
                    it.end != null && when {
                        selectedMonth != null ->
                            YearMonth.from(it.wakeDate) == selectedMonth && !it.wakeDate.isAfter(today)
                        days == 1 -> it.wakeDate == today
                        else -> it.wakeDate >= today.minusDays(days.toLong()) && it.wakeDate < today
                    }
                }.sortedByDescending { it.wakeDate }
            }

            val showBarChart = selectedMonth != null || days != 1
            var selectedDateKey by rememberSaveable(days, selectedMonth?.toString()) { mutableStateOf<String?>(null) }
            var clockCollapsed by rememberSaveable(days, selectedMonth?.toString()) { mutableStateOf(false) }
            val selectedDate = selectedDateKey?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val selected = when {
                selectedMonth != null -> if (clockCollapsed) null else (rows.firstOrNull { it.wakeDate == selectedDate } ?: rows.firstOrNull())
                days == 1 -> rows.firstOrNull()
                else -> rows.firstOrNull { it.wakeDate == selectedDate }
            }

            when {
                error != null -> Text("기록 조회 불가 · 사용 정보 권한을 확인해 주세요.")
                report == null && rows.isEmpty() -> Text("기록 조회 중…")
                rows.isEmpty() -> Text(
                    if (selectedMonth != null) {
                        "${selectedMonth.year}년 ${selectedMonth.monthValue}월에는 표시할 완료된 수면 기록이 아직 없습니다."
                    } else {
                        "표시할 완료된 수면 기록이 아직 없습니다."
                    }
                )
                else -> {
                    if (showBarChart) {
                        val legendDirectColor = if (isDark) Color(0xFF5C7CFA) else Color(0xFF3B5BDB)
                        val legendAutoColor = if (isDark) Color(0xFF34D399) else Color(0xFF059669)
                        val legendUnselectedColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                        val chartBoxBg = ItvInsetSurface
                        val gridColor = if (isDark) Color(0xFF314539) else Color(0xFFD9D0C3)
                        val boxBorderColor = if (isDark) Color(0xFF2E4035) else Color(0xFFD6CEC0)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(Modifier.size(10.dp).background(legendDirectColor, RoundedCornerShape(2.dp)))
                                Text("직접 선택", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(Modifier.size(10.dp).background(legendAutoColor, RoundedCornerShape(2.dp)))
                                Text("자동 반영", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(Modifier.size(10.dp).background(legendUnselectedColor, RoundedCornerShape(2.dp)))
                                Text("미선택", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.width(40.dp)) {
                                Box(Modifier.height(200.dp)) {
                                    for (tick in 0..5) Text(
                                        "${10 - tick * 2}",
                                        modifier = Modifier.offset(y = (tick * 40 - 8).dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Text("시간", style = MaterialTheme.typography.labelSmall)
                            }
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    items = rows,
                                    key = { it.wakeDate.toString() },
                                    contentType = { "sleep_bar" }
                                ) { row ->
                                    val shownMinutes = SleepSummaryPolicy.minutes(row.wakeDate, row.minutes, confirmations) ?: row.minutes
                                    val conf = confirmations[row.wakeDate.toString()]
                                    val dayCandidateCount = candidatesByDate[row.wakeDate]?.size ?: 0
                                    val barColor = when {
                                        conf?.choice == "SLEEP" -> legendDirectColor
                                        conf == null && dayCandidateCount == 1 -> legendAutoColor
                                        else -> legendUnselectedColor
                                    }
                                    val isBarSelected = selected?.wakeDate == row.wakeDate
                                    Column(
                                        Modifier.clickable {
                                            selectedDateKey = row.wakeDate.toString()
                                            clockCollapsed = false
                                        },
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            Modifier
                                                .width(54.dp)
                                                .height(200.dp)
                                                .background(chartBoxBg, RoundedCornerShape(8.dp))
                                                .border(
                                                    width = if (isBarSelected) 2.dp else 1.dp,
                                                    color = if (isBarSelected) ItvGreen else boxBorderColor,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .drawWithCache {
                                                    val w = size.width
                                                    val h = size.height
                                                    onDrawBehind {
                                                        for (tick in 0..5) {
                                                            val y = h * tick / 5f
                                                            drawLine(gridColor, Offset(0f, y), Offset(w, y))
                                                        }
                                                    }
                                                },
                                            contentAlignment = Alignment.BottomCenter
                                        ) {
                                            Box(
                                                Modifier
                                                    .width(22.dp)
                                                    .height((shownMinutes / 60.0).coerceIn(0.0, 10.0).toFloat().times(20).dp)
                                                    .background(barColor, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                            )
                                        }
                                        val roundedMins = shownMinutes.roundToLong()
                                        Text(
                                            "${roundedMins / 60}시간 ${roundedMins % 60}분",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isBarSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            "${row.wakeDate.monthValue}.${row.wakeDate.dayOfMonth}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isBarSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isBarSelected) ItvGreen else cardContentColor
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (showBarChart && selected != null) {
                        TextButton(onClick = {
                            if (selectedMonth != null) clockCollapsed = true else selectedDateKey = null
                        }) { Text("비사용 시계 상세 접기") }
                    }

                    selected?.let { row ->
                        val minutes = (SleepSummaryPolicy.minutes(row.wakeDate, row.minutes, confirmations) ?: row.minutes).roundToLong()
                        Text(
                            "${row.wakeDate.monthValue}.${row.wakeDate.dayOfMonth} · 수면 ${minutes / 60}시간 ${minutes % 60}분",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val otherCandidates = remember(candidatesByDate, row) {
                            candidatesByDate[row.wakeDate].orEmpty()
                                .filter { !(it.start == row.start && it.end == row.end) }
                                .sortedBy { it.start }
                        }
                        val confirmation = confirmations[row.wakeDate.toString()]
                        val displayRow = if (confirmation?.choice == "SLEEP" && confirmation.startMs != null && confirmation.endMs != null) {
                            row.copy(
                                start = confirmation.startMs,
                                end = confirmation.endMs,
                                minutes = (confirmation.endMs - confirmation.startMs) / 60000.0,
                                briefWakeCount = (listOf(row) + otherCandidates).firstOrNull {
                                    it.start == confirmation.startMs && it.end == confirmation.endMs
                                }?.briefWakeCount ?: 0
                            )
                        } else {
                            row
                        }
                        SleepClock(
                            displayRow,
                            zone,
                            if (confirmation == null) otherCandidates else emptyList(),
                            showMain = confirmation?.choice != "NOT_SLEEP",
                            confirmed = confirmation != null
                        )
                        SleepConfirmationPanel(
                            row.wakeDate,
                            zone,
                            listOf(row) + otherCandidates,
                            onSaved = {
                                revision++
                                onSleepDataChanged()
                            }
                        )
                    }
                }
            }

            if (showSleepClockHelp) {
                SleepRulesHelpDialog(
                    title = "수면 기록 및 비사용 시계 안내",
                    onDismiss = { showSleepClockHelp = false }
                )
            }
        }
    }
}

@Composable
private fun HelpBadgeButton(
    isDark: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(if (compact) 10.dp else 12.dp),
        color = if (isDark) Color(0xFF3E5445) else Color(0xFFD8EED2),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = "?",
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = 2.dp
            ),
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color(0xFF85E3B3) else Color(0xFF1B4332),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun SleepRulesHelpDialog(
    title: String,
    onDismiss: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "?",
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFF85E3B3) else Color(0xFF1B4332)
                )
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "• 사용자 확인 우선: 자동 추정 구간보다 사용자가 [수면 시간 선택], [직접 입력], [수면 없음]으로 직접 확인한 기록을 최우선으로 반영합니다.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• 자동 수면 감지: 밤사이 3시간 이상 화면을 끄고 사용하지 않은 구간을 수면 후보로 자동 추출합니다. 막대를 누르면 24시간 비사용 시계와 수정 버튼을 볼 수 있어요.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• 월별 평균 수면 계산: 선택한 달의 기록 중 기록이 없는 '미기록' 날짜는 평균에서 제외되며, 밤새 깨어 있어 [수면 없음]으로 체크한 날은 0시간으로 반영됩니다.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• 막대 색상 구분: 파랑은 직접 선택한 기록, 초록은 후보가 1개라 자동 반영된 기록, 회색은 후보가 여러 개라 아직 선택하지 않은 날짜입니다.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "• 장기 기록 연동: Android OS는 최근 약 7~10일간의 화면 이벤트만 보관하지만, 앱이 자체 백업한 장기 아카이브(최대 6개월)와 사용자 확인 기록을 통합해 이전 달·다음 달 이동과 요일별 패턴에 함께 반영합니다.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
internal fun SleepClock(
    row: NightSegment,
    zone: ZoneId,
    others: List<NightSegment>,
    showMain: Boolean = true,
    confirmed: Boolean = false,
    showHeading: Boolean = true,
    onPick: ((NightSegment) -> Unit)? = null
) {
    var bubbleText by remember(row.start, row.end, others) { mutableStateOf<String?>(null) }
    var bubbleColor by remember { mutableStateOf(Color(0xFF35443B)) }
    val start = Instant.ofEpochMilli(row.start).atZone(zone)
    val end = Instant.ofEpochMilli(row.end!!).atZone(zone)
    val isDark = LocalDarkTheme.current
    val startMinute = start.hour * 60 + start.minute + start.second / 60f
    val sweep = (row.minutes / 1440.0 * 360).coerceIn(0.0, 360.0).toFloat()
    val startAngle = startMinute / 1440f * 360 - 90
    val midpoint = Math.toRadians((startAngle + sweep / 2).toDouble())
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
                        border = BorderStroke(2.dp, bubbleColor)
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
            val clockBorderColor = if (isDark) Color(0xFF3B5344) else Color(0xFF2A3B31)
            val otherArcSpecs = remember(others, zone) {
                others.map { candidate ->
                    val candidateStart = Instant.ofEpochMilli(candidate.start).atZone(zone)
                    val minute = candidateStart.hour * 60 + candidateStart.minute + candidateStart.second / 60f
                    val candidateStartAngle = minute / 1440f * 360f - 90f
                    val candidateSweep = (candidate.minutes / 1440.0 * 360.0).coerceIn(0.0, 360.0).toFloat()
                    candidateStartAngle to candidateSweep
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .drawWithCache {
                        val diameter = size.minDimension * 0.78f
                        val radius = diameter / 2f
                        val borderRadius = radius + 8.dp.toPx()
                        val arcTopLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                        val arcSize = Size(diameter, diameter)
                        val centerPt = Offset(size.width / 2f, size.height / 2f)
                        val tickStrokePx = 2.dp.toPx()
                        val outerR = radius - 5.dp.toPx()
                        val majorInnerR = outerR - 10.dp.toPx()
                        val minorInnerR = outerR - 4.dp.toPx()
                        val tickLines = List(24) { hour ->
                            val angle = Math.toRadians(hour * 15.0 - 90.0)
                            val c = cos(angle).toFloat()
                            val s = sin(angle).toFloat()
                            val innerR = if (hour % 6 == 0) majorInnerR else minorInnerR
                            Offset(centerPt.x + c * innerR, centerPt.y + s * innerR) to
                                Offset(centerPt.x + c * outerR, centerPt.y + s * outerR)
                        }
                        onDrawBehind {
                            drawCircle(clockBorderColor, radius = borderRadius, center = centerPt)
                            drawCircle(Color(0xFF66B978), radius = radius, center = centerPt)
                            // All candidates share the end date; draw alternatives first, main last.
                            for (i in otherArcSpecs.indices) {
                                val (cStart, cSweep) = otherArcSpecs[i]
                                drawArc(
                                    Color(0xFFF39A38),
                                    cStart,
                                    cSweep,
                                    true,
                                    topLeft = arcTopLeft,
                                    size = arcSize
                                )
                            }
                            if (showMain) {
                                drawArc(
                                    Color(0xFFE35D62),
                                    startAngle,
                                    sweep,
                                    true,
                                    topLeft = arcTopLeft,
                                    size = arcSize
                                )
                            }
                            for (i in tickLines.indices) {
                                val (p0, p1) = tickLines[i]
                                drawLine(Color.White, p0, p1, tickStrokePx)
                            }
                        }
                    }
                    .pointerInput(row.start, row.end, zone, others, showMain, confirmed, onPick) {
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
                            } else {
                                bubbleText = null
                            }
                        }
                    }
            )
            Text("00", Modifier.align(Alignment.TopCenter), style = MaterialTheme.typography.labelMedium)
            Text("06", Modifier.align(Alignment.CenterEnd), style = MaterialTheme.typography.labelMedium)
            Text("12", Modifier.align(Alignment.BottomCenter), style = MaterialTheme.typography.labelMedium)
            Text("18", Modifier.align(Alignment.CenterStart), style = MaterialTheme.typography.labelMedium)
            if (showMain) {
                Column(
                    Modifier.offset(
                        x = (cos(midpoint) * 60).toFloat().dp,
                        y = (sin(midpoint) * 60).toFloat().dp
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Zzz",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${row.briefWakeCount}회",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        if (showMain) {
            Text(
                "${start.monthValue}월 ${start.dayOfMonth}일 %02d:%02d ~ ${end.monthValue}월 ${end.dayOfMonth}일 %02d:%02d".format(
                    start.hour, start.minute, end.hour, end.minute
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}
