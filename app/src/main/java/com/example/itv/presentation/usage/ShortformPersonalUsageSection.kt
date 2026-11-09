package com.example.itv.presentation.usage

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore.PersonalizedSummary
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore.PersonalizedTargetMetrics
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore.TargetFilter
import com.example.itv.presentation.components.ItvLine
import java.util.Locale

private val InstaAccent = Color(0xFFD62976)
private val YoutubeAccent = Color(0xFFE52D27)
private val CombinedAccent = Color(0xFF2B6CB0)
private val CardInk = Color(0xFF1E2433)
private val CardSubInk = Color(0xFF505A74)

@Composable
internal fun ShortformPersonalUsageSection(summary: PersonalizedSummary?) {
    var selectedFilterName by rememberSaveable { mutableStateOf(TargetFilter.COMBINED.name) }
    val selectedFilter = remember(selectedFilterName) {
        runCatching { TargetFilter.valueOf(selectedFilterName) }.getOrDefault(TargetFilter.COMBINED)
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = RecordContentGreen, contentColor = CardInk)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("개인화 숏폼 사용량 · 평균 및 비율", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "아이콘을 눌러 인스타 릴스, 유튜브 쇼츠, 통합(릴스+쇼츠) 기준의 개인 평균 대비 비율을 확인하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = CardSubInk
            )

            // 3 Icons selector row: Instagram Icon, YouTube Icon, Combined Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ShortformTargetIconTab(
                    filter = TargetFilter.INSTAGRAM,
                    selected = selectedFilter == TargetFilter.INSTAGRAM,
                    accent = InstaAccent,
                    subtitle = summary?.instagram?.let { formatShortDuration(it.todayDurationMs) } ?: "—",
                    onClick = { selectedFilterName = TargetFilter.INSTAGRAM.name },
                    modifier = Modifier.weight(1f)
                ) {
                    InstagramPlatformIcon()
                }
                ShortformTargetIconTab(
                    filter = TargetFilter.YOUTUBE,
                    selected = selectedFilter == TargetFilter.YOUTUBE,
                    accent = YoutubeAccent,
                    subtitle = summary?.youtube?.let { formatShortDuration(it.todayDurationMs) } ?: "—",
                    onClick = { selectedFilterName = TargetFilter.YOUTUBE.name },
                    modifier = Modifier.weight(1f)
                ) {
                    YoutubePlatformIcon()
                }
                ShortformTargetIconTab(
                    filter = TargetFilter.COMBINED,
                    selected = selectedFilter == TargetFilter.COMBINED,
                    accent = CombinedAccent,
                    subtitle = summary?.combined?.let { formatShortDuration(it.todayDurationMs) } ?: "—",
                    onClick = { selectedFilterName = TargetFilter.COMBINED.name },
                    modifier = Modifier.weight(1f)
                ) {
                    CombinedShortformIcon()
                }
            }

            if (summary == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            } else {
                val metrics = summary.forFilter(selectedFilter)
                val accent = when (selectedFilter) {
                    TargetFilter.INSTAGRAM -> InstaAccent
                    TargetFilter.YOUTUBE -> YoutubeAccent
                    TargetFilter.COMBINED -> CombinedAccent
                }
                PersonalizedMetricsContent(metrics, accent)
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
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
        border = BorderStroke(
            width = if (selected) 2.2.dp else 1.dp,
            color = if (selected) accent else ItvLine
        ),
        shadowElevation = if (selected) 3.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            icon()
            Text(
                text = filter.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) accent else CardInk,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Text(
                text = "오늘 $subtitle",
                style = MaterialTheme.typography.labelSmall,
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
private fun PersonalizedMetricsContent(m: PersonalizedTargetMetrics, accent: Color) {
    val targetTitle = when (m.filter) {
        TargetFilter.INSTAGRAM -> "Instagram 릴스"
        TargetFilter.YOUTUBE -> "YouTube 쇼츠"
        TargetFilter.COMBINED -> "통합 숏폼 (릴스 + 쇼츠)"
    }

    // Card 1: 개인 평균 세션 길이 대비 현재 비율
    PersonalSubCard(title = "1. 개인 평균 세션 길이 대비 현재 비율 ($targetTitle)", accent = accent) {
        val sessionRatio = m.currentVsAvgSessionRatio
        val sessionStatusText = when {
            sessionRatio == null -> "아직 비교할 세션 기록이 없습니다."
            sessionRatio >= 1.3 -> "평소 평균 1회 시청 시간보다 ${formatPercent(sessionRatio - 1.0)} 더 길게 시청 중 (집중 개입 구간)"
            sessionRatio >= 0.9 -> "평소 평균 1회 시청 길이와 비슷한 수준 (${formatRatioBadge(sessionRatio)})"
            else -> "평소 평균 세션 길이보다 짧은 시청 (${formatRatioBadge(sessionRatio)})"
        }
        RatioHighlightRow(
            label = if (m.currentSessionLive) "현재 진행 세션 / 개인 평균 세션" else "직전(최근) 세션 / 개인 평균 세션",
            ratio = sessionRatio,
            accent = accent
        )
        Text(sessionStatusText, style = MaterialTheme.typography.bodySmall, color = CardSubInk)
        HorizontalDivider(color = ItvLine)
        MetricKeyValueRow(
            leftLabel = if (m.currentSessionLive) "현재 세션 시청 시간 (진행 중)" else "최근 1회 세션 시청 시간",
            leftValue = formatFullDuration(m.currentSessionMs),
            rightLabel = "개인 평균 세션 길이",
            rightValue = "${formatFullDuration(m.avgSessionMs)} (${m.totalRecordedSessions}회 평균)"
        )
        MetricKeyValueRow(
            leftLabel = "오늘 시청 세션 횟수",
            leftValue = "${m.todaySessionCount}회 (7일 평균 ${String.format(Locale.KOREA, "%.1f", m.avg7dSessionCount)}회/일)",
            rightLabel = "개인 최장 세션 기록",
            rightValue = formatFullDuration(m.maxSessionMs)
        )
    }

    // Card 2: 오늘 누적시간과 최근 7일 일평균 시청 시간 비율
    PersonalSubCard(title = "2. 오늘 누적시간 vs 최근 7일 일평균 비율 ($targetTitle)", accent = accent) {
        val dailyRatio = m.todayVs7dRatio
        val dailyStatusText = when {
            dailyRatio == null -> "최근 7일 관측 기록이 쌓이면 일평균 대비 비율이 표시됩니다."
            dailyRatio >= 1.25 -> "최근 7일 일평균보다 ${formatPercent(dailyRatio - 1.0)} 초과 시청 중 (일일 과사용 주의)"
            dailyRatio >= 0.85 -> "최근 7일 일평균 수준에 근접 (${formatRatioBadge(dailyRatio)})"
            else -> "최근 7일 일평균 대비 여유 있음 (${formatRatioBadge(dailyRatio)})"
        }
        RatioHighlightRow(
            label = "오늘 누적 시청 / 최근 7일 일평균",
            ratio = dailyRatio,
            accent = accent
        )
        Text(dailyStatusText, style = MaterialTheme.typography.bodySmall, color = CardSubInk)
        HorizontalDivider(color = ItvLine)
        MetricKeyValueRow(
            leftLabel = "오늘 누적 시청 시간",
            leftValue = formatFullDuration(m.todayDurationMs),
            rightLabel = "최근 7일 일평균 시청",
            rightValue = "${formatFullDuration(m.avg7dDailyMs)} (관측 ${m.observedDays7d}일)"
        )
        MetricKeyValueRow(
            leftLabel = "최근 7일 총 누적 시간",
            leftValue = formatFullDuration(m.total7dDurationMs),
            rightLabel = "최근 30일 일평균 (오늘 비율)",
            rightValue = "${formatFullDuration(m.avg30dDailyMs)} (${m.todayVs30dRatio?.let { formatPercent(it) } ?: "—"})"
        )
    }

    // Card 3: 스와이프(전환) 평균 및 앱 내 숏폼 시청 비중
    PersonalSubCard(title = "3. 스와이프 평균 및 숏폼 시청 비중 ($targetTitle)", accent = accent) {
        MetricKeyValueRow(
            leftLabel = "오늘 누적 전환(스와이프)",
            leftValue = "${m.todaySwipes}회",
            rightLabel = "최근 7일 일평균 스와이프",
            rightValue = "${String.format(Locale.KOREA, "%.1f", m.avg7dDailySwipes)}회 (${m.todayVs7dSwipesRatio?.let { formatRatioBadge(it) } ?: "—"})"
        )
        MetricKeyValueRow(
            leftLabel = "세션당 평균 스와이프",
            leftValue = "${String.format(Locale.KOREA, "%.1f", m.avgSwipesPerSession)}회/세션",
            rightLabel = "분당 평균 스와이프 (오늘 / 평소)",
            rightValue = "${String.format(Locale.KOREA, "%.1f", m.todayAvgSpm)} / ${String.format(Locale.KOREA, "%.1f", m.personalAvgSpm)} 회/분"
        )
        HorizontalDivider(color = ItvLine)
        val appLabel = when (m.filter) {
            TargetFilter.INSTAGRAM -> "Instagram 전체 사용 중 릴스 비중"
            TargetFilter.YOUTUBE -> "YouTube 전체 사용 중 쇼츠 비중"
            TargetFilter.COMBINED -> "인스타+유튜브 사용 중 숏폼 비중"
        }
        MetricKeyValueRow(
            leftLabel = appLabel,
            leftValue = m.shortformShareOfAppRatio?.let { formatPercent(it) } ?: "—",
            rightLabel = "오늘 휴대폰 전체 사용 중 비중",
            rightValue = m.shortformShareOfPhoneRatio?.let { formatPercent(it) } ?: "—"
        )
    }
}

@Composable
private fun PersonalSubCard(
    title: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = CardInk)
            content()
        }
    }
}

@Composable
private fun RatioHighlightRow(label: String, ratio: Double?, accent: Color) {
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
                .background( Color(0xFFE2E8F0) )
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

@Composable
private fun MetricKeyValueRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(leftLabel, style = MaterialTheme.typography.labelSmall, color = CardSubInk)
            Text(leftValue, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = CardInk)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(rightLabel, style = MaterialTheme.typography.labelSmall, color = CardSubInk)
            Text(rightValue, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = CardInk)
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
