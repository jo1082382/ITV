package com.example.itv.presentation.usage

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.domain.records.EstimatedDayType
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore.PersonalizedSummary
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore.PersonalizedTargetMetrics
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore.TargetFilter
import com.example.itv.presentation.components.ItvLine
import java.util.Locale
import kotlin.math.roundToInt

private val InstaAccent = Color(0xFFD62976)
private val YoutubeAccent = Color(0xFFE52D27)
private val CombinedAccent = Color(0xFF2B6CB0)
private val PhoneAccent = Color(0xFF0F9D58)
private val CardInk: Color
    @Composable
    get() = if (com.example.itv.presentation.components.LocalDarkTheme.current) Color(0xFFE2EBE4) else Color(0xFF1E2433)
private val CardSubInk: Color
    @Composable
    get() = if (com.example.itv.presentation.components.LocalDarkTheme.current) Color(0xFFA6B7A9) else Color(0xFF505A74)

@Composable
internal fun ShortformPersonalUsageSection(summary: PersonalizedSummary?, days: Int = 1) {
    var selectedFilterName by rememberSaveable { mutableStateOf(TargetFilter.PHONE_TOTAL.name) }
    val selectedFilter = remember(selectedFilterName) {
        runCatching { TargetFilter.valueOf(selectedFilterName) }.getOrDefault(TargetFilter.PHONE_TOTAL)
    }
    val periodTitle = when {
        days >= 30 -> "최근 1달(30일) 스마트폰 및 숏폼 세션·사용량 분석"
        days >= 7 -> "최근 1주일(7일) 스마트폰 및 숏폼 세션·사용량 분석"
        else -> "오늘 스마트폰 및 숏폼 개인화 세션·사용량 분석"
    }
    val periodDescription = when {
        days >= 30 -> "최근 1달 내 실제 사용한 날·세션 기록을 반영한 휴대폰 전체 및 숏폼 1회 평균 세션 길이와 일평균 비율입니다. (무사용 날·시간 제외)"
        days >= 7 -> "최근 1주일 내 실제 사용한 날·세션 기록을 반영한 휴대폰 전체 및 숏폼 1회 평균 세션 길이와 1주일 일평균 비율입니다. (무사용 날·시간 제외)"
        else -> "아이콘을 눌러 오늘의 휴대폰 전체, 통합 숏폼, 릴스, 쇼츠 사용량과 1회 평균 세션 길이, 개인 평균 대비 비율을 확인하세요."
    }
    fun tabSubtitle(m: PersonalizedTargetMetrics?): String {
        if (m == null) return "—"
        return when {
            days >= 30 -> formatShortDuration(m.avg30dDailyMs)
            days >= 7 -> formatShortDuration(m.avg7dDailyMs)
            else -> formatShortDuration(m.todayDurationMs)
        }
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = RecordContentGreen, contentColor = CardInk)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(periodTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                periodDescription,
                style = MaterialTheme.typography.bodySmall,
                color = CardSubInk
            )

            // 4 Icons selector row: Phone Total, Combined, Instagram, YouTube
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ShortformTargetIconTab(
                    filter = TargetFilter.PHONE_TOTAL,
                    selected = selectedFilter == TargetFilter.PHONE_TOTAL,
                    accent = PhoneAccent,
                    subtitle = tabSubtitle(summary?.phoneTotal),
                    onClick = { selectedFilterName = TargetFilter.PHONE_TOTAL.name },
                    modifier = Modifier.weight(1f)
                ) {
                    PhonePlatformIcon()
                }
                ShortformTargetIconTab(
                    filter = TargetFilter.COMBINED,
                    selected = selectedFilter == TargetFilter.COMBINED,
                    accent = CombinedAccent,
                    subtitle = tabSubtitle(summary?.combined),
                    onClick = { selectedFilterName = TargetFilter.COMBINED.name },
                    modifier = Modifier.weight(1f)
                ) {
                    CombinedShortformIcon()
                }
                ShortformTargetIconTab(
                    filter = TargetFilter.INSTAGRAM,
                    selected = selectedFilter == TargetFilter.INSTAGRAM,
                    accent = InstaAccent,
                    subtitle = tabSubtitle(summary?.instagram),
                    onClick = { selectedFilterName = TargetFilter.INSTAGRAM.name },
                    modifier = Modifier.weight(1f)
                ) {
                    InstagramPlatformIcon()
                }
                ShortformTargetIconTab(
                    filter = TargetFilter.YOUTUBE,
                    selected = selectedFilter == TargetFilter.YOUTUBE,
                    accent = YoutubeAccent,
                    subtitle = tabSubtitle(summary?.youtube),
                    onClick = { selectedFilterName = TargetFilter.YOUTUBE.name },
                    modifier = Modifier.weight(1f)
                ) {
                    YoutubePlatformIcon()
                }
            }

            if (summary == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            } else {
                val metrics = summary.forFilter(selectedFilter)
                val accent = when (selectedFilter) {
                    TargetFilter.PHONE_TOTAL -> PhoneAccent
                    TargetFilter.INSTAGRAM -> InstaAccent
                    TargetFilter.YOUTUBE -> YoutubeAccent
                    TargetFilter.COMBINED -> CombinedAccent
                }
                PersonalizedMetricsContent(metrics, accent, days)
            }
        }
    }
}

@Composable
private fun ShortformTargetIconTab(
    filter: TargetFilter,
    selected: Boolean,
    accent: Color,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    val isDark = com.example.itv.presentation.components.LocalDarkTheme.current
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            if (isDark) Color(0xFF2C392F) else Color.White
        } else {
            if (isDark) Color(0xFF1B241D).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.55f)
        },
        border = BorderStroke(
            width = if (selected) 2.2.dp else 1.dp,
            color = if (selected) accent else (if (isDark) ItvLine.copy(alpha = 0.5f) else ItvLine)
        ),
        shadowElevation = if (selected) 3.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            icon()
            Text(
                text = filter.label.replace(" ", "\n"),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) accent else CardInk,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = CardSubInk,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun InstagramPlatformIcon() {
    val context = LocalContext.current
    val installedBitmap = remember(context) {
        loadInstalledAppBitmap(context, ShortformPersonalStatsStore.PKG_INSTAGRAM)
    }
    if (installedBitmap != null) {
        Image(
            bitmap = installedBitmap.asImageBitmap(),
            contentDescription = "인스타 릴스 아이콘",
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
        )
    } else {
        Canvas(Modifier.size(42.dp)) {
            val brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF)),
                start = Offset(0f, size.height),
                end = Offset(size.width, 0f)
            )
            drawRoundRect(brush = brush, cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()))
            val inset = size.width * 0.22f
            val boxSize = size.width - inset * 2f
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(inset, inset),
                size = Size(boxSize, boxSize),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 2.2.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = size.width * 0.13f,
                center = center,
                style = Stroke(width = 2.2.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = 1.8.dp.toPx(),
                center = Offset(size.width * 0.66f, size.height * 0.34f)
            )
        }
    }
}

@Composable
private fun YoutubePlatformIcon() {
    val context = LocalContext.current
    val installedBitmap = remember(context) {
        loadInstalledAppBitmap(context, ShortformPersonalStatsStore.PKG_YOUTUBE)
    }
    if (installedBitmap != null) {
        Image(
            bitmap = installedBitmap.asImageBitmap(),
            contentDescription = "유튜브 쇼츠 아이콘",
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
        )
    } else {
        Canvas(Modifier.size(42.dp)) {
            drawRoundRect(
                color = YoutubeAccent,
                cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
            )
            val path = Path().apply {
                moveTo(size.width * 0.38f, size.height * 0.29f)
                lineTo(size.width * 0.68f, size.height * 0.50f)
                lineTo(size.width * 0.38f, size.height * 0.71f)
                close()
            }
            drawPath(path = path, color = Color.White)
        }
    }
}

@Composable
private fun CombinedShortformIcon() {
    Canvas(Modifier.size(42.dp)) {
        val bgBrush = Brush.linearGradient(
            colors = listOf(Color(0xFF1E3A8A), Color(0xFF7E22CE), Color(0xFFE11D48)),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )
        drawRoundRect(brush = bgBrush, cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()))
        // Left overlapping card (Instagram Reels motif)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.92f),
            topLeft = Offset(size.width * 0.17f, size.height * 0.22f),
            size = Size(size.width * 0.38f, size.height * 0.48f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = 1.8.dp.toPx())
        )
        // Right overlapping card (YouTube Shorts motif)
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(size.width * 0.43f, size.height * 0.30f),
            size = Size(size.width * 0.40f, size.height * 0.48f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        // Play triangle inside the front card
        val play = Path().apply {
            moveTo(size.width * 0.57f, size.height * 0.44f)
            lineTo(size.width * 0.72f, size.height * 0.54f)
            lineTo(size.width * 0.57f, size.height * 0.64f)
            close()
        }
        drawPath(path = play, color = Color(0xFFE11D48))
        // Plus dot on left card
        drawLine(
            color = Color.White,
            start = Offset(size.width * 0.28f, size.height * 0.46f),
            end = Offset(size.width * 0.40f, size.height * 0.46f),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(size.width * 0.34f, size.height * 0.40f),
            end = Offset(size.width * 0.34f, size.height * 0.52f),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun PhonePlatformIcon() {
    Canvas(Modifier.size(42.dp)) {
        val bgBrush = Brush.linearGradient(
            colors = listOf(Color(0xFF0F9D58), Color(0xFF1E8E3E), Color(0xFF137333)),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )
        drawRoundRect(brush = bgBrush, cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()))
        val phoneWidth = size.width * 0.44f
        val phoneHeight = size.height * 0.68f
        val left = (size.width - phoneWidth) / 2f
        val top = (size.height - phoneHeight) / 2f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(phoneWidth, phoneHeight),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = 1.8.dp.toPx())
        )
        drawLine(
            color = Color.White,
            start = Offset(left + phoneWidth * 0.35f, top + phoneHeight * 0.14f),
            end = Offset(left + phoneWidth * 0.65f, top + phoneHeight * 0.14f),
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(left + phoneWidth * 0.30f, top + phoneHeight * 0.86f),
            end = Offset(left + phoneWidth * 0.70f, top + phoneHeight * 0.86f),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun loadInstalledAppBitmap(context: android.content.Context, packageName: String): Bitmap? =
    runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val px = (42 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { bmp ->
            drawable.setBounds(0, 0, px, px)
            drawable.draw(android.graphics.Canvas(bmp))
        }
    }.getOrNull()

@Composable
private fun PersonalizedMetricsContent(m: PersonalizedTargetMetrics, accent: Color, days: Int) {
    val targetTitle = when (m.filter) {
        TargetFilter.PHONE_TOTAL -> "휴대폰 전체 사용"
        TargetFilter.INSTAGRAM -> "Instagram 릴스"
        TargetFilter.YOUTUBE -> "YouTube 쇼츠"
        TargetFilter.COMBINED -> "통합 숏폼 (릴스 + 쇼츠)"
    }
    var showDetailDialog by remember { mutableStateOf(false) }
    val isDark = com.example.itv.presentation.components.LocalDarkTheme.current
    val periodLabel = when {
        days >= 30 -> "최근 1달(30일)"
        days >= 7 -> "최근 1주일(7일)"
        else -> "오늘"
    }

    // 핵심 요약 카드 (한 눈에 보기)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (isDark) Color(0xFF253027) else Color.White.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, if (isDark) accent.copy(alpha = 0.40f) else accent.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 헤더: 타겟명 + ? 도움말 아이콘
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.size(8.dp).background(accent, RoundedCornerShape(4.dp)))
                    Text(
                        "$targetTitle · $periodLabel",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CardInk,
                        maxLines = 1
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = accent.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
                    modifier = Modifier.clickable { showDetailDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("?", fontWeight = FontWeight.ExtraBold, color = accent, style = MaterialTheme.typography.labelSmall)
                        Text("안내", style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 1. 비율 게이지 (오늘 vs 1주일 또는 1주일 vs 1달)
            val ratio = when {
                days >= 30 -> m.session7dVs30dRatio ?: m.avg7dVs30dRatio
                days >= 7 -> m.todayVs7dRatio ?: m.currentVsAvgSessionRatio
                else -> m.currentVsAvgSessionRatio ?: m.todayVs7dRatio
            }
            val ratioLabel = when {
                days >= 30 -> "최근 1주일 / 1달 평균 세션 비율"
                days >= 7 -> "오늘 / 1주일 일평균 비율"
                else -> "오늘 세션 / 평소 평균 세션 비율"
            }
            RatioHighlightRow(label = ratioLabel, ratio = ratio, accent = accent)

            HorizontalDivider(color = ItvLine)

            // 2. 핵심 지표 한눈에 보기 (2x2 그리드로 배치하여 글자 잘림 완전 방지)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Row 1: 사용 시간 & 1회 평균 세션
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val (usageMain, usageSub) = when {
                        days >= 30 -> formatShortDuration(m.avg30dDailyMs) to "1달 일평균 (총 ${formatShortDuration(m.total30dDurationMs)})"
                        days >= 7 -> formatShortDuration(m.avg7dDailyMs) to "1주 일평균 (총 ${formatShortDuration(m.total7dDurationMs)})"
                        else -> formatShortDuration(m.todayDurationMs) to "오늘 사용 (일평균 ${formatShortDuration(m.avg7dDailyMs)})"
                    }
                    QuickMetricTile(
                        label = "⏱️ 사용 시간",
                        mainValue = usageMain,
                        subValue = usageSub,
                        accent = accent,
                        modifier = Modifier.weight(1f)
                    )

                    val todayAvgSession = if (m.filter == TargetFilter.PHONE_TOTAL && m.phoneTodayAvgSessionMs > 0L) {
                        m.phoneTodayAvgSessionMs
                    } else if (m.todaySessionCount > 0) {
                        m.todayDurationMs / m.todaySessionCount
                    } else {
                        m.currentSessionMs
                    }
                    val sessionUnit = if (m.filter == TargetFilter.PHONE_TOTAL) "사용" else "시청"
                    val (sessionMain, sessionSub) = when {
                        days >= 30 -> formatShortDuration(m.avg30dSessionMs) to "평균 세션 (${m.sessionCount30d}회 $sessionUnit)"
                        days >= 7 -> formatShortDuration(m.avg7dSessionMs) to "평균 세션 (${m.sessionCount7d}회 $sessionUnit)"
                        else -> formatShortDuration(todayAvgSession) to "평균 세션 (오늘 ${m.todaySessionCount}회 $sessionUnit)"
                    }
                    QuickMetricTile(
                        label = "🔄 1회 평균 세션",
                        mainValue = sessionMain,
                        subValue = sessionSub,
                        accent = accent,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Row 2: 숏폼 특성 & 오늘 생활 패턴
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val (featureMain, featureSub) = if (m.filter == TargetFilter.PHONE_TOTAL) {
                        val sfRatio = when {
                            days >= 30 -> m.phoneAvg30dShortformRatio
                            days >= 7 -> m.phoneAvg7dShortformRatio
                            else -> m.phoneTodayShortformRatio
                        }
                        val pct = sfRatio?.let { "${(it * 100).roundToInt()}%" } ?: "0%"
                        "비중 $pct" to "환산 세션 ${formatShortDuration(if (days >= 30) m.phoneAvg30dWeightedSessionMs else if (days >= 7) m.phoneAvg7dWeightedSessionMs else m.phoneTodayAvgWeightedSessionMs)}"
                    } else {
                        val spm = if (days == 1) m.todayAvgSpm else if (days >= 30) m.personalAvgSpm else m.avg7dSpm
                        "분당 ${String.format(Locale.KOREA, "%.1f", spm)}회" to "총 ${if (days >= 30) m.total30dSwipes else if (days >= 7) m.total7dSwipes else m.todaySwipes}회 스와이프"
                    }
                    QuickMetricTile(
                        label = if (m.filter == TargetFilter.PHONE_TOTAL) "📱 숏폼 비중" else "⚡ 스와이프 속도",
                        mainValue = featureMain,
                        subValue = featureSub,
                        accent = accent,
                        modifier = Modifier.weight(1f)
                    )

                    val (dayMain, daySub) = when (m.todayEstimatedDayTypeByPhone) {
                        EstimatedDayType.REST_DAY -> "쉬는 날 모드" to "여유 세션 적용"
                        EstimatedDayType.WORKDAY -> "일과 날 모드" to "집중 개입 적용"
                        null -> if (m.totalPhoneTodayMs > 0L) "활동 집계 중" to formatShortDuration(m.totalPhoneTodayMs) else "패턴 학습 중" to "데이터 수집"
                    }
                    QuickMetricTile(
                        label = "🌿 생활 패턴",
                        mainValue = dayMain,
                        subValue = daySub,
                        accent = accent,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    // 상세 분석 모달 다이얼로그 (? 아이콘 클릭 시: 구구절절하지 않고 이해하기 쉬운 핵심 설명)
    if (showDetailDialog) {
        AlertDialog(
            onDismissRequest = { showDetailDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("?", fontWeight = FontWeight.Bold, color = accent)
                    Text("$targetTitle 지표 안내", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "• 무사용일 제외: 폰이나 앱을 전혀 사용하지 않은 날은 제외하고 실제 사용한 날만 계산해 현실적인 평균을 보여줍니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "• 세션 필터링: 알림 확인이나 카톡 단답 등 2분 미만의 짧은 확인은 세션 평균에서 제외하여 실제 체감 사용 지표를 정확하게 보여줍니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "• 체감 세션: 몰입도가 높은 숏폼 시간은 1배, 일반 앱 사용 시간은 1/3배로 피로도를 환산합니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "• 일과/쉬는 날 모드: 평소 사용량을 학습하여 쉬는 날에는 여유 시간을, 일과 날에는 조기 집중 개입을 적용합니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetailDialog = false }) {
                    Text("확인", fontWeight = FontWeight.Bold, color = accent)
                }
            }
        )
    }
}

@Composable
private fun QuickMetricTile(
    label: String,
    mainValue: String,
    subValue: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val isDark = com.example.itv.presentation.components.LocalDarkTheme.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (isDark) Color(0xFF1E2822) else Color(0xFFF7FAFC),
        border = BorderStroke(0.8.dp, if (isDark) accent.copy(alpha = 0.25f) else accent.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                mainValue,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = CardInk,
                maxLines = 1
            )
            Text(
                subValue,
                style = MaterialTheme.typography.labelSmall,
                color = CardSubInk,
                maxLines = 2,
                softWrap = true
            )
        }
    }
}

@Composable
private fun RatioHighlightRow(label: String, ratio: Double?, accent: Color) {
    val isDark = com.example.itv.presentation.components.LocalDarkTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = CardInk)
            Text(
                text = ratio?.let { "${formatPercent(it)} (${String.format(Locale.KOREA, "%.2f", it)}배)" } ?: "데이터 수집 중",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (ratio != null && ratio >= 1.2) Color(0xFFD63E3E) else accent
            )
        }
        val progress = ((ratio ?: 0.0) / 2.0).coerceIn(0.0, 1.0).toFloat()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background( if (isDark) Color(0xFF38463B) else Color(0xFFE2E8F0) )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(if (ratio != null && ratio >= 1.2) Color(0xFFD63E3E) else accent)
            )
            // 100% (1.0x = 50% width of 0..2.0x bar) baseline marker
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(CardInk.copy(alpha = 0.45f)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0%", style = MaterialTheme.typography.labelSmall, color = CardSubInk)
            Text("평소 평균 (100%)", style = MaterialTheme.typography.labelSmall, color = CardSubInk)
            Text("200%+", style = MaterialTheme.typography.labelSmall, color = CardSubInk)
        }
    }
}

private fun formatFullDuration(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return when {
        hours > 0L -> "${hours}시간 ${minutes}분"
        minutes > 0L -> "${minutes}분 ${seconds}초"
        else -> "${seconds}초"
    }
}

private fun formatShortDuration(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return when {
        hours > 0L -> "${hours}시간 ${minutes}분"
        minutes > 0L -> "${minutes}분"
        else -> "${seconds}초"
    }
}

private fun formatPercent(ratio: Double): String =
    String.format(Locale.KOREA, "%.1f%%", ratio * 100.0)

private fun formatRatioBadge(ratio: Double): String =
    "${formatPercent(ratio)} · ${String.format(Locale.KOREA, "%.2f", ratio)}배"
