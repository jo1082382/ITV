package com.example.itv.presentation.usage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.infrastructure.storage.DailyArchiveRecord
import com.example.itv.infrastructure.storage.MonthlyUsageArchiveStore
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.ItvLine
import com.example.itv.presentation.components.ItvMint
import com.example.itv.presentation.components.LocalDarkTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val CardInk: Color
    @Composable
    get() = if (LocalDarkTheme.current) Color(0xFFE2EBE4) else Color(0xFF1E2433)

private val CardSubInk: Color
    @Composable
    get() = if (LocalDarkTheme.current) Color(0xFFA6B7A9) else Color(0xFF505A74)

/**
 * Custom App Calendar section for Records tab.
 * Highlights days with available usage data brightly and dims days without data.
 * Supports date selection for detailed usage inspection and provides the foundation
 * for month-over-month improvement metrics.
 */
@Composable
fun UsageCalendarSection(
    modifier: Modifier = Modifier,
    initialDate: LocalDate = LocalDate.now(),
    todayTotalMs: Long = 0L
) {
    val context = LocalContext.current
    val isDark = LocalDarkTheme.current
    val today = remember { LocalDate.now() }

    var currentMonth by rememberSaveable { mutableStateOf(YearMonth.from(initialDate).toString()) }
    val viewMonth = remember(currentMonth) {
        runCatching { YearMonth.parse(currentMonth) }.getOrDefault(YearMonth.from(today))
    }

    var selectedDateStr by rememberSaveable { mutableStateOf<String?>(today.toString()) }
    val selectedDate = remember(selectedDateStr) {
        selectedDateStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    // Load archived daily records
    val dailyRecords = remember(currentMonth) {
        MonthlyUsageArchiveStore.getAllDailyRecords(context)
    }

    val daysInMonth = viewMonth.lengthOfMonth()
    val firstDayOfWeek = viewMonth.atDay(1).dayOfWeek.value % 7 // 0 for Sunday, 1 for Monday...

    val monthRecords = remember(viewMonth, dailyRecords, todayTotalMs) {
        val ymPrefix = viewMonth.toString()
        val map = dailyRecords.filterKeys { it.startsWith(ymPrefix) }.toMutableMap()
        if (viewMonth == YearMonth.from(today) && todayTotalMs > 0L) {
            val todayKey = today.toString()
            val existing = map[todayKey]
            map[todayKey] = (existing ?: DailyArchiveRecord(
                date = todayKey,
                totalPhoneMs = todayTotalMs,
                shortformMs = 0L
            )).copy(totalPhoneMs = maxOf(existing?.totalPhoneMs ?: 0L, todayTotalMs))
        }
        map
    }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(
            containerColor = RecordContentGreen,
            contentColor = CardInk
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Month Navigation Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${viewMonth.year}년 ${viewMonth.monthValue}월",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CardInk
                    )
                    if (viewMonth != YearMonth.from(today)) {
                        TextButton(
                            onClick = {
                                currentMonth = YearMonth.from(today).toString()
                                selectedDateStr = today.toString()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("오늘", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ItvGreen)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalIconButton(
                        onClick = { currentMonth = viewMonth.minusMonths(1).toString() },
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isDark) Color(0xFF2C392F) else Color.White
                        )
                    ) {
                        Text("‹", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CardInk)
                    }
                    FilledTonalIconButton(
                        onClick = {
                            if (viewMonth < YearMonth.from(today)) {
                                currentMonth = viewMonth.plusMonths(1).toString()
                            }
                        },
                        enabled = viewMonth < YearMonth.from(today),
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isDark) Color(0xFF2C392F) else Color.White,
                            disabledContainerColor = (if (isDark) Color(0xFF2C392F) else Color.White).copy(alpha = 0.4f)
                        )
                    ) {
                        Text("›", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CardInk)
                    }
                }
            }

            // Legend & Status Hint
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = if (isDark) Color(0xFF1B241D) else Color.White.copy(alpha = 0.65f),
                border = BorderStroke(0.8.dp, if (isDark) ItvLine.copy(alpha = 0.4f) else ItvLine)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "💡 색이 들어온 날짜는 사용 기록이 있는 날입니다",
                        style = MaterialTheme.typography.labelSmall,
                        color = CardSubInk
                    )
                }
            }

            // Day of Week Header Row (Sun to Sat)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val dayHeaders = listOf("일", "월", "화", "수", "목", "금", "토")
                dayHeaders.forEachIndexed { idx, name ->
                    val color = when (idx) {
                        0 -> Color(0xFFE53935) // Sunday Red
                        6 -> Color(0xFF1E88E5) // Saturday Blue
                        else -> CardSubInk
                    }
                    Text(
                        text = name,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }

            // Calendar Days Grid (7 columns)
            val totalSlots = firstDayOfWeek + daysInMonth
            val rowsCount = (totalSlots + 6) / 7

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (rowIndex in 0 until rowsCount) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        for (colIndex in 0 until 7) {
                            val slotIndex = rowIndex * 7 + colIndex
                            val dayNum = slotIndex - firstDayOfWeek + 1
                            if (dayNum in 1..daysInMonth) {
                                val date = viewMonth.atDay(dayNum)
                                val dateKey = date.toString()
                                val record = monthRecords[dateKey]
                                val hasData = (record != null && (record.totalPhoneMs > 0L || record.shortformMs > 0L)) ||
                                        (date == today && todayTotalMs > 0L)
                                val isToday = date == today
                                val isSelected = date == selectedDate

                                CalendarDayCell(
                                    day = dayNum,
                                    dayOfWeek = date.dayOfWeek,
                                    hasData = hasData,
                                    isToday = isToday,
                                    isSelected = isSelected,
                                    onClick = { selectedDateStr = dateKey },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Selected Date Detail Preview Card
            selectedDate?.let { date ->
                val dateKey = date.toString()
                val record = monthRecords[dateKey]
                SelectedDayDetailCard(
                    date = date,
                    record = record,
                    isToday = date == today,
                    todayTotalMs = todayTotalMs
                )
            }

            // Monthly Summary Card & Improvement Preview
            MonthlyArchiveSummaryCard(
                viewMonth = viewMonth,
                records = monthRecords.values.toList()
            )
        }
    }
}

@Composable
private fun CalendarDayCell(
    day: Int,
    dayOfWeek: DayOfWeek,
    hasData: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalDarkTheme.current

    val cellBackground = when {
        isSelected -> ItvGreen
        hasData -> if (isDark) Color(0xFF28362B) else Color.White
        else -> Color.Transparent
    }

    val textColor = when {
        isSelected -> Color.White
        hasData -> when (dayOfWeek) {
            DayOfWeek.SUNDAY -> Color(0xFFE53935)
            DayOfWeek.SATURDAY -> Color(0xFF1E88E5)
            else -> CardInk
        }
        else -> CardSubInk.copy(alpha = 0.28f) // 어둡게 표시
    }

    val border = when {
        isSelected -> BorderStroke(1.5.dp, ItvGreen)
        isToday -> BorderStroke(1.5.dp, ItvGreen)
        hasData -> BorderStroke(0.6.dp, if (isDark) ItvLine.copy(alpha = 0.35f) else ItvLine.copy(alpha = 0.6f))
        else -> null
    }

    Surface(
        modifier = modifier
            .padding(1.5.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = cellBackground,
        border = border,
        shadowElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = day.toString(),
                fontSize = 13.sp,
                fontWeight = if (hasData || isSelected) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SelectedDayDetailCard(
    date: LocalDate,
    record: DailyArchiveRecord?,
    isToday: Boolean,
    todayTotalMs: Long
) {
    val isDark = LocalDarkTheme.current
    val dayOfWeekName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREA)
    val phoneMs = if (isToday && todayTotalMs > 0L) maxOf(record?.totalPhoneMs ?: 0L, todayTotalMs) else (record?.totalPhoneMs ?: 0L)
    val sfMs = record?.shortformMs ?: 0L
    val hasUsage = phoneMs > 0L || sfMs > 0L

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isDark) Color(0xFF1E2822) else Color.White,
        border = BorderStroke(1.dp, if (isDark) ItvLine.copy(alpha = 0.5f) else ItvLine)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "📅 ${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일 (${dayOfWeekName})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CardInk
                    )
                    if (isToday) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ItvMint,
                            contentColor = ItvGreen
                        ) {
                            Text("오늘", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }

                if (hasUsage) {
                    val dayTypeLabel = if (record?.dayType == "REST_DAY" || date.dayOfWeek in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) "🌿 쉬는 날" else "💼 일과 날"
                    Text(
                        text = dayTypeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = ItvGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (hasUsage) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CalendarMetricItem(
                        icon = "📱",
                        label = "스마트폰 총 사용",
                        value = formatShortDuration(phoneMs),
                        modifier = Modifier.weight(1f)
                    )
                    CalendarMetricItem(
                        icon = "🎬",
                        label = "숏폼 시청 시간",
                        value = if (sfMs > 0L) formatShortDuration(sfMs) else "0분",
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val bedtimeStr = formatBedtimeRange(record?.bedtimeMinute, record?.wakeupMinute)
                    CalendarMetricItem(
                        icon = "🛏️",
                        label = "수면 및 취침",
                        value = bedtimeStr,
                        modifier = Modifier.weight(1f)
                    )
                    CalendarMetricItem(
                        icon = "🎯",
                        label = "개입 미션 지표",
                        value = "연동 준비 중",
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text(
                    text = "저장된 스마트폰 및 앱 사용 기록이 없는 날입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CardSubInk
                )
            }
        }
    }
}

@Composable
private fun CalendarMetricItem(
    icon: String,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val isDark = LocalDarkTheme.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (isDark) Color(0xFF253329) else Color(0xFFF7FAFC),
        border = BorderStroke(0.6.dp, if (isDark) ItvLine.copy(alpha = 0.3f) else ItvLine.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("$icon $label", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = CardSubInk)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = CardInk, maxLines = 1)
        }
    }
}

@Composable
private fun MonthlyArchiveSummaryCard(
    viewMonth: YearMonth,
    records: List<DailyArchiveRecord>
) {
    val isDark = LocalDarkTheme.current
    val validRecords = records.filter { it.totalPhoneMs > 0L || it.shortformMs > 0L }
    val activeDays = validRecords.size
    val totalPhoneMs = validRecords.sumOf { it.totalPhoneMs }
    val totalShortformMs = validRecords.sumOf { it.shortformMs }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isDark) Color(0xFF1B241D) else Color.White.copy(alpha = 0.85f),
        border = BorderStroke(0.8.dp, if (isDark) ItvLine.copy(alpha = 0.4f) else ItvLine)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📊 ${viewMonth.monthValue}월 누적 요약 & 개선 지표",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = CardInk
                )
                Text(
                    "기록된 일수 ${activeDays}일",
                    style = MaterialTheme.typography.labelSmall,
                    color = ItvGreen,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "총 사용: ${formatShortDuration(totalPhoneMs)} (일평균 ${if (activeDays > 0) formatShortDuration(totalPhoneMs / activeDays) else "0분"})",
                    style = MaterialTheme.typography.labelSmall,
                    color = CardSubInk
                )
                Text(
                    "숏폼: ${formatShortDuration(totalShortformMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF2B6CB0),
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                "• 추후 이 캘린더에서 날짜별 미션 성공률, 전월 대비 개선율(숏폼 사용 시간 감소율, 취침 규칙성 지표)을 한눈에 비교할 수 있도록 확장될 예정입니다.",
                style = MaterialTheme.typography.labelSmall,
                color = CardSubInk.copy(alpha = 0.75f),
                fontSize = 10.sp
            )
        }
    }
}

private fun formatBedtimeRange(bedtimeMin: Int?, wakeupMin: Int?): String {
    if (bedtimeMin == null && wakeupMin == null) return "취침 기록 없음"
    val bed = bedtimeMin?.let { String.format(Locale.KOREA, "%02d:%02d", (it / 60) % 24, it % 60) } ?: "--:--"
    val wake = wakeupMin?.let { String.format(Locale.KOREA, "%02d:%02d", (it / 60) % 24, it % 60) } ?: "--:--"
    return "$bed ~ $wake"
}

private fun formatShortDuration(ms: Long): String {
    if (ms <= 0L) return "0분"
    val totalMins = (ms / 60_000L).coerceAtLeast(1L)
    val h = totalMins / 60L
    val m = totalMins % 60L
    return when {
        h > 0L && m > 0L -> "${h}시간 ${m}분"
        h > 0L -> "${h}시간"
        else -> "${m}분"
    }
}
