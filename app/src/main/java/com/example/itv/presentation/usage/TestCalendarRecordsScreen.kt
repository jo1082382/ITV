package com.example.itv.presentation.usage

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import com.example.itv.composition.queryNightReport
import com.example.itv.composition.queryUsageReport
import com.example.itv.domain.records.AppHourUsage
import com.example.itv.domain.records.DailyHealthAssessment
import com.example.itv.domain.records.DailyHealthTier
import com.example.itv.domain.records.HealthAxisScore
import com.example.itv.domain.records.NightReport
import com.example.itv.domain.records.Report
import com.example.itv.infrastructure.storage.AccountDataBoundaryStore
import com.example.itv.infrastructure.storage.CalendarHealthLoader
import com.example.itv.infrastructure.storage.CalendarMonthHealthBundle
import com.example.itv.infrastructure.storage.DayResolvedFacts
import com.example.itv.infrastructure.storage.MonthlyHealthTally
import com.example.itv.infrastructure.storage.MonthlyUsageArchiveStore
import com.example.itv.presentation.components.ItvCardSurface
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.ItvInkBorder
import com.example.itv.presentation.components.ItvInkShadow
import com.example.itv.presentation.components.ItvInsetSurface
import com.example.itv.presentation.components.ItvLine
import com.example.itv.presentation.components.ItvMint
import com.example.itv.presentation.components.ItvSeasonalCalendarBanner
import com.example.itv.presentation.components.ItvSeasonalRoofTopAccent
import com.example.itv.presentation.components.ItvTactileButton
import com.example.itv.presentation.components.ItvTactileCard
import com.example.itv.presentation.components.LocalDarkTheme
import com.example.itv.presentation.components.LocalSeasonalAnimationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val CardInk: Color
    @Composable
    get() = if (LocalDarkTheme.current) Color(0xFFE2EBE4) else Color(0xFF241E19)

private val CardSubInk: Color
    @Composable
    get() = if (LocalDarkTheme.current) Color(0xFFA6B7A9) else Color(0xFF61564B)

private enum class CalendarDetailTab(val label: String) {
    COACHING("🏅 건강 리포트"),
    HOURLY_AND_APPS("📊 시간대별 사용 분포")
}

/**
 * ITV Health Calendar:
 * Evaluates each day across sleep/pre-sleep restraint, shortform escape & swipe speed,
 * phone session health, and day-type rhythm, stamping Gold/Silver/Bronze medals or
 * Yellow/Red warning triangles on the calendar.
 */
@Composable
internal fun TestCalendarRecordsScreen(
    modifier: Modifier = Modifier,
    initialSelectedDate: LocalDate? = null,
    focusRequestVersion: Int = 0,
    stampRefreshVersion: Int = 0,
    calendarReloadVersion: Int = 0,
    daySyncTick: Int = 0,
    activeRitualDate: LocalDate? = null,
    onDayCellPlaced: ((LocalDate, LayoutCoordinates) -> Unit)? = null,
    onDayCellDisposed: ((LocalDate) -> Unit)? = null,
    onLaunchStampRitual: ((StampRitualRequest) -> Unit)? = null
) {
    val context = LocalContext.current
    val isDark = LocalDarkTheme.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val seasonalAnimController = LocalSeasonalAnimationController.current
    LaunchedEffect(listState, seasonalAnimController) {
        if (seasonalAnimController == null) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> seasonalAnimController.scrollPaused = scrolling }
    }
    DisposableEffect(seasonalAnimController) {
        onDispose { seasonalAnimController?.scrollPaused = false }
    }
    val today = remember(daySyncTick) { LocalDate.now() }

    // Non-snapshot LayoutCoordinates holder updated only on layout (onPlaced) -> ZERO scroll & recomposition overhead!
    val localCellCoords = remember { mutableMapOf<LocalDate, LayoutCoordinates>() }
    val localRootCoordsRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun LayoutCoordinates?.liveBounds(): Rect? =
        this?.takeIf { it.isAttached }?.boundsInRoot()?.takeUnless { it.isEmpty }

    var currentMonthStr by rememberSaveable { mutableStateOf(YearMonth.from(initialSelectedDate ?: today).toString()) }
    val viewMonth = remember(currentMonthStr) {
        runCatching { YearMonth.parse(currentMonthStr) }.getOrDefault(YearMonth.from(today))
    }

    var selectedDateStr by rememberSaveable { mutableStateOf((initialSelectedDate ?: today).toString()) }
    val selectedDate = remember(selectedDateStr) {
        runCatching { LocalDate.parse(selectedDateStr) }.getOrDefault(today)
    }

    // Popup dialog date when the user taps a day cell on the calendar
    var detailDialogDate by remember { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(initialSelectedDate, focusRequestVersion) {
        if (initialSelectedDate != null) {
            currentMonthStr = YearMonth.from(initialSelectedDate).toString()
            selectedDateStr = initialSelectedDate.toString()
            listState.scrollToItem(0)
        }
    }

    var showGradeHelpDialog by remember { mutableStateOf(false) }

    // Track dates where the user has already slammed their morning puppy stamp
    var stampedDates by remember { mutableStateOf(DailyStampPrefs.getStampedDates(context)) }
    var slamKey by remember { mutableIntStateOf(0) }
    var localRitualRequest by remember { mutableStateOf<StampRitualRequest?>(null) }

    // Hoist stamp outline bitmap resolution ONCE above the calendar grid (eliminates 42 redundant produceState coroutines!)
    val selectedStampCharacter = remember(stampRefreshVersion) { DailyStampPrefs.getSelectedCharacter(context) }
    val sharedOutlineBitmap by produceState(
        initialValue = StampCharacterBitmapCache.getCachedMonochromeOutline(selectedStampCharacter.id),
        key1 = context,
        key2 = selectedStampCharacter.id
    ) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                StampCharacterBitmapCache.preload(context, selectedStampCharacter)
                StampCharacterBitmapCache.getMonochromeOutline(context, selectedStampCharacter)
            }
        }
    }

    LaunchedEffect(stampRefreshVersion) {
        stampedDates = DailyStampPrefs.getStampedDates(context)
    }

    fun markDateStamped(date: LocalDate) {
        stampedDates = DailyStampPrefs.markDateStamped(context, date)
        slamKey++
    }

    fun dispatchRitual(request: StampRitualRequest) {
        if (onLaunchStampRitual != null) {
            onLaunchStampRitual(request)
        } else {
            localRitualRequest = request
        }
    }

    fun launchRitual(request: StampRitualRequest) {
        val targetDate = request.date
        val existingBounds = request.sourceCellBounds ?: localCellCoords[targetDate].liveBounds()
        val root = localRootCoordsRef[0].liveBounds()
        val isOnScreen = existingBounds != null &&
            (root == null || (existingBounds.bottom > root.top + 8f && existingBounds.top < root.bottom - 8f))

        if (isOnScreen) {
            dispatchRitual(request.copy(sourceCellBounds = existingBounds))
        } else {
            scope.launch {
                val targetMonth = YearMonth.from(targetDate)
                if (viewMonth != targetMonth) {
                    currentMonthStr = targetMonth.toString()
                }
                listState.scrollToItem(0)
                var resolved: Rect? = null
                var waited = 0
                while (waited < 450) {
                    val candidate = localCellCoords[targetDate].liveBounds()
                    if (candidate != null) {
                        resolved = candidate
                        break
                    }
                    delay(20)
                    waited += 20
                }
                dispatchRitual(request.copy(sourceCellBounds = resolved ?: existingBounds))
            }
        }
    }

    // Month-level health evaluation bundle (hydrates instantaneously from post-login preload cache when available)
    val initialCachedBundle = remember(viewMonth, today, stampedDates) {
        CalendarHealthLoader.getCachedMonth(viewMonth, today, stampedDates)
    }
    var monthBundle by remember { mutableStateOf(initialCachedBundle) }
    var loadingMonth by remember { mutableStateOf(initialCachedBundle == null) }

    // Opened-dialog day hourly & app usage report (loaded lazily only when a date popup is opened)
    var dialogDayReport by remember { mutableStateOf<Report?>(null) }
    var loadingDialogDayReport by remember { mutableStateOf(false) }

    LaunchedEffect(viewMonth, today, calendarReloadVersion, stampedDates) {
        val effectiveStamped = DailyStampPrefs.getStampedDates(context)
        if (effectiveStamped != stampedDates) {
            stampedDates = effectiveStamped
        }
        val cached = CalendarHealthLoader.consumeFreshPreload(viewMonth, today, effectiveStamped)
            ?: if (calendarReloadVersion == 0) {
                CalendarHealthLoader.getCachedMonth(viewMonth, today, effectiveStamped)
            } else {
                null
            }
        if (cached != null) {
            monthBundle = cached
            loadingMonth = false
            return@LaunchedEffect
        }
        loadingMonth = true
        try {
            val todayRep = withContext(Dispatchers.IO) {
                if (viewMonth == YearMonth.from(today)) {
                    runCatching { context.queryUsageReport(1, today) }.getOrNull()
                } else {
                    null
                }
            }
            val nightRep: NightReport? = withContext(Dispatchers.IO) {
                runCatching { queryNightReport(context, 45) }.getOrNull()
            }
            monthBundle = CalendarHealthLoader.loadMonth(
                context = context,
                viewMonth = viewMonth,
                todayReport = todayRep,
                existingNightReport = nightRep,
                stampedDates = stampedDates
            )
        } catch (_: Exception) {
            if (monthBundle == null) monthBundle = null
        } finally {
            loadingMonth = false
        }
    }

    LaunchedEffect(detailDialogDate, today) {
        val target = detailDialogDate
        if (target == null) {
            dialogDayReport = null
            loadingDialogDayReport = false
            return@LaunchedEffect
        }
        loadingDialogDayReport = true
        dialogDayReport = null
        try {
            dialogDayReport = withContext(Dispatchers.IO) {
                val boundaryDate = AccountDataBoundaryStore.getFreshStartDate(context)
                val archived = MonthlyUsageArchiveStore.buildArchivedDayReport(context, target)
                if (AccountDataBoundaryStore.isDateBeforeBoundary(target, boundaryDate)) {
                    // Never pull pre-deletion Android OS UsageEvents for dates before fresh-start boundary
                    archived
                } else {
                    val live = runCatching { context.queryUsageReport(1, target) }.getOrNull()
                    if (live != null && (live.total > 0.0 || live.values.any { it > 0.0 })) {
                        runCatching { MonthlyUsageArchiveStore.upsertDayHourlyAndApps(context, target, live) }
                        live
                    } else {
                        archived ?: live
                    }
                }
            }
        } catch (_: Exception) {
            dialogDayReport = null
        } finally {
            loadingDialogDayReport = false
        }
    }

    val daysInMonth = viewMonth.lengthOfMonth()
    val firstDayOfWeek = viewMonth.atDay(1).dayOfWeek.value % 7 // 0 for Sun

    if (showGradeHelpDialog) {
        HealthStampHelpDialog(onDismiss = { showGradeHelpDialog = false })
    }

    detailDialogDate?.let { dialogDate ->
        CalendarDayDetailDialog(
            date = dialogDate,
            today = today,
            assessment = monthBundle?.assessments?.get(dialogDate),
            facts = monthBundle?.facts?.get(dialogDate),
            baselineBedtimeMin = monthBundle?.baselineBedtimeMinute,
            loadingMonth = loadingMonth,
            dayReport = dialogDayReport,
            loadingDayReport = loadingDialogDayReport,
            slamKey = slamKey,
            onReplayStampSlam = { assessment ->
                detailDialogDate = null
                slamKey++
                launchRitual(
                    StampRitualRequest(
                        date = assessment.date,
                        tier = assessment.tier,
                        score = assessment.score,
                        headline = assessment.headline,
                        isMorningAuto = false,
                        isTestPreview = false,
                        character = DailyStampPrefs.getSelectedCharacter(context),
                        sourceCellBounds = localCellCoords[assessment.date].liveBounds()
                    )
                )
            },
            onDismiss = { detailDialogDate = null }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPlaced { localRootCoordsRef[0] = it }
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header ("건강 사용 캘린더" centered across full width, `?` anchored on far right)
        item(key = "header_title", contentType = "header_title") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
            ) {
                Text(
                    text = "건강 사용 캘린더",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = CardInk,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center)
                )
                ItvTactileButton(
                    onClick = { showGradeHelpDialog = true },
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
                        Text("?", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ItvGreen)
                    }
                }
            }
        }

        // 2. Calendar Card with Roof-Top Seasonal Accent + Two-Tier Postcard Header + 5-Tier Stamps
        item(key = "calendar_card", contentType = "calendar_card") {
            Column(modifier = Modifier.fillMaxWidth()) {
                ItvSeasonalRoofTopAccent(modifier = Modifier.padding(horizontal = 4.dp))
                ItvTactileCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(),
                    shape = RoundedCornerShape(24.dp),
                    containerColor = ItvCardSurface,
                    contentColor = CardInk,
                    borderColor = ItvInkBorder,
                    shadowColor = ItvInkShadow,
                    borderWidth = 2.dp,
                    shadowOffsetX = 2.dp,
                    shadowOffsetY = 4.dp
                ) {
                    // Top Tier: Seasonal Postcard Banner hosting ‹ Year-Month ›
                    ItvSeasonalCalendarBanner {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val navBtnBg = if (isDark) Color(0xFF2B3B31) else Color.White
                            ItvTactileButton(
                                onClick = {
                                    val prev = viewMonth.minusMonths(1)
                                    currentMonthStr = prev.toString()
                                    selectedDateStr = prev.atEndOfMonth().toString()
                                },
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
                                    Text("‹", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CardInk)
                                }
                            }

                            Text(
                                text = "${viewMonth.year}년 ${viewMonth.monthValue}월",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = CardInk,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )

                            val canGoNext = viewMonth < YearMonth.from(today)
                            ItvTactileButton(
                                onClick = {
                                    if (canGoNext) {
                                        val next = viewMonth.plusMonths(1)
                                        currentMonthStr = next.toString()
                                        selectedDateStr = if (next == YearMonth.from(today)) {
                                            today.toString()
                                        } else {
                                            next.atDay(1).toString()
                                        }
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
                                        color = if (canGoNext) CardInk else CardSubInk.copy(alpha = 0.45f)
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Tier: Crisp Pure-White Calendar Grid for 100% Stamp & Date Contrast
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                    // Compact 5-Puppy Ink Stamp Legend
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = ItvInsetSurface,
                        border = BorderStroke(0.9.dp, ItvLine.copy(alpha = if (isDark) 0.55f else 0.75f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LegendStampItem(DailyHealthTier.GOLD, "해맑", sharedOutlineBitmap)
                            LegendStampItem(DailyHealthTier.SILVER, "웃음", sharedOutlineBitmap)
                            LegendStampItem(DailyHealthTier.BRONZE, "슬픔", sharedOutlineBitmap)
                            LegendStampItem(DailyHealthTier.WARNING, "화남", sharedOutlineBitmap)
                            LegendStampItem(DailyHealthTier.CRITICAL, "아픔", sharedOutlineBitmap)
                            LegendStampItem(DailyHealthTier.IN_PROGRESS, "진행", sharedOutlineBitmap)
                        }
                    }

                    // Weekdays Row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { idx, name ->
                            val color = when (idx) {
                                0 -> Color(0xFFE53935)
                                6 -> Color(0xFF1E88E5)
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

                    // Month Grid (7 columns)
                    if (loadingMonth && monthBundle == null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = ItvGreen)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "캘린더 기록을 불러오는 중...",
                                style = MaterialTheme.typography.bodySmall,
                                color = CardSubInk
                            )
                        }
                    } else {
                        val totalSlots = firstDayOfWeek + daysInMonth
                        val rowsCount = (totalSlots + 6) / 7

                        // Clean up stale day cell coords once per month switch instead of 31 per-cell DisposableEffects
                        DisposableEffect(viewMonth) {
                            onDispose {
                                val staleDates = localCellCoords.keys.filter { YearMonth.from(it) == viewMonth }
                                staleDates.forEach { d ->
                                    localCellCoords.remove(d)
                                    onDayCellDisposed?.invoke(d)
                                }
                            }
                        }

                        // Single stable click handler shared by all 31 day cells so tapping only recomposes affected cells
                        val onCalendarDayClick = remember(viewMonth, monthBundle, stampedDates, today, selectedStampCharacter) {
                            { dayNum: Int ->
                                val date = viewMonth.atDay(dayNum)
                                val assessment = monthBundle?.assessments?.get(date)
                                val tier = assessment?.tier ?: DailyHealthTier.NO_DATA
                                val isYesterdayPending = date == today.minusDays(1) &&
                                    tier != DailyHealthTier.NO_DATA &&
                                    tier != DailyHealthTier.IN_PROGRESS &&
                                    date.toString() !in stampedDates
                                selectedDateStr = date.toString()
                                if (isYesterdayPending && assessment != null) {
                                    launchRitual(
                                        StampRitualRequest(
                                            date = date,
                                            tier = assessment.tier,
                                            score = assessment.score,
                                            headline = assessment.headline,
                                            isMorningAuto = false,
                                            isTestPreview = false,
                                            character = selectedStampCharacter,
                                            sourceCellBounds = localCellCoords[date].liveBounds()
                                        )
                                    )
                                } else {
                                    detailDialogDate = date
                                }
                            }
                        }

                        // Hardware-cached layer prevents re-recording 31 Canvas stamps on every scroll frame
                        Column(
                            modifier = Modifier.graphicsLayer(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (rowIndex in 0 until rowsCount) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    for (colIndex in 0 until 7) {
                                        val slotIndex = (rowIndex * 7) + colIndex
                                        val dayNum = slotIndex - firstDayOfWeek + 1
                                        if (dayNum in 1..daysInMonth) {
                                            val date = viewMonth.atDay(dayNum)
                                            val assessment = monthBundle?.assessments?.get(date)
                                            val tier = assessment?.tier ?: DailyHealthTier.NO_DATA
                                            val hasData = tier != DailyHealthTier.NO_DATA
                                            val isYesterdayPendingStamp = date == today.minusDays(1) &&
                                                hasData &&
                                                tier != DailyHealthTier.IN_PROGRESS &&
                                                date.toString() !in stampedDates

                                            CalendarStampCell(
                                                day = dayNum,
                                                dayOfWeek = date.dayOfWeek,
                                                tier = tier,
                                                hasData = hasData,
                                                isToday = date == today,
                                                isSelected = date == selectedDate,
                                                isPendingStamp = isYesterdayPendingStamp,
                                                isLiftedForRitual = activeRitualDate == date || localRitualRequest?.date == date,
                                                preloadedOutlineBitmap = sharedOutlineBitmap,
                                                onDayClick = onCalendarDayClick,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .onPlaced { coords ->
                                                        if (localCellCoords[date] !== coords) {
                                                            localCellCoords[date] = coords
                                                            onDayCellPlaced?.invoke(date, coords)
                                                        }
                                                    }
                                            )
                                        } else {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Morning Stamp Prompt if yesterday is finalized and not yet stamped!
                    val yesterday = today.minusDays(1)
                    val yesterdayAssessment = monthBundle?.assessments?.get(yesterday)
                    val showYesterdayStampBtn = yesterdayAssessment != null &&
                        yesterdayAssessment.tier != DailyHealthTier.NO_DATA &&
                        yesterdayAssessment.tier != DailyHealthTier.IN_PROGRESS &&
                        yesterday.toString() !in stampedDates

                    if (showYesterdayStampBtn) {
                        ItvTactileButton(
                            onClick = {
                                selectedDateStr = yesterday.toString()
                                launchRitual(
                                    StampRitualRequest(
                                        date = yesterday,
                                        tier = yesterdayAssessment.tier,
                                        score = yesterdayAssessment.score,
                                        headline = yesterdayAssessment.headline,
                                        isMorningAuto = false,
                                        isTestPreview = false,
                                        character = DailyStampPrefs.getSelectedCharacter(context),
                                        sourceCellBounds = localCellCoords[yesterday].liveBounds()
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            containerColor = ItvGreen,
                            contentColor = Color.White,
                            borderColor = ItvInkBorder,
                            shadowColor = ItvInkShadow,
                            borderWidth = 1.8.dp,
                            shadowOffsetX = 1.5.dp,
                            shadowOffsetY = 3.5.dp
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 11.dp, horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "🐾 어제(${yesterday.monthValue}/${yesterday.dayOfMonth}) 강아지 도장 쾅! 찍기",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // Monthly Stamp Tally Strip
                    monthBundle?.tally?.let { tally ->
                        MonthlyStampTallyStrip(
                            tally = tally,
                            preloadedOutlineBitmap = sharedOutlineBitmap
                        )
                    }

                    // Stamp Motion Test Bar (5 expressions) so user can preview every tier's slam + reaction!
                    StampMotionTestBar(
                        preloadedOutlineBitmap = sharedOutlineBitmap,
                        onTestTier = { testTier ->
                            val previewDate = if (YearMonth.from(selectedDate) == viewMonth) {
                                selectedDate
                            } else {
                                viewMonth.atDay(1)
                            }
                            val (sampleScore, sampleHeadline) = when (testTier) {
                                DailyHealthTier.GOLD -> 90 to "취침 전 스마트폰 절제와 숏폼 탈출이 모두 완벽한 날이었어요!"
                                DailyHealthTier.SILVER -> 78 to "전반적으로 건강한 디지털 리듬을 잘 유지했어요."
                                DailyHealthTier.BRONZE -> 68 to "조금 아쉽지만 괜찮아요. 내일은 취침 전 조금만 일찍 내려놔요."
                                DailyHealthTier.WARNING -> 56 to "숏폼 연속 시청과 늦은 사용이 겹쳤어요. 잠시 쉬어갈 타이밍이에요!"
                                else -> 42 to "야간 과사용과 숏폼 몰입이 심했어요. 오늘 밤은 꼭 푹 쉬어주세요!"
                            }
                            launchRitual(
                                StampRitualRequest(
                                    date = previewDate,
                                    tier = testTier,
                                    score = sampleScore,
                                    headline = sampleHeadline,
                                    isMorningAuto = false,
                                    isTestPreview = true,
                                    character = selectedStampCharacter,
                                    sourceCellBounds = localCellCoords[previewDate].liveBounds()
                                )
                            )
                        }
                    )
                    }
                }
            }
        }
    }

    // Fallback local ritual overlay if hosted outside ItvMainScreen
    localRitualRequest?.let { req ->
        DailyStampRitualOverlay(
            request = req,
            overlayRootBounds = localRootCoordsRef[0].liveBounds(),
            resolveCellBounds = { date -> localCellCoords[date].liveBounds() },
            onStampCommitted = {
                if (!req.isTestPreview) {
                    markDateStamped(req.date)
                }
            },
            onFinished = {
                if (!req.isTestPreview) {
                    markDateStamped(req.date)
                }
                localRitualRequest = null
            }
        )
    }
    }
}

@Composable
private fun StampMotionTestBar(
    preloadedOutlineBitmap: ImageBitmap?,
    onTestTier: (DailyHealthTier) -> Unit
) {
    val isDark = LocalDarkTheme.current
    val testTiers = remember {
        listOf(
            DailyHealthTier.GOLD,
            DailyHealthTier.SILVER,
            DailyHealthTier.BRONZE,
            DailyHealthTier.WARNING,
            DailyHealthTier.CRITICAL
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ItvInsetSurface,
        border = BorderStroke(0.9.dp, ItvLine.copy(alpha = if (isDark) 0.55f else 0.75f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🐾 도장 표정 미리보기",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = CardInk
                )
                Text(
                    text = "표정을 눌러 쾅! 연출 보기",
                    fontSize = 10.sp,
                    color = CardSubInk
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                testTiers.forEach { tier ->
                    val ink = stampInkColor(tier)
                    Surface(
                        onClick = { onTestTier(tier) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = ink.copy(alpha = if (isDark) 0.16f else 0.09f),
                        border = BorderStroke(1.dp, ink.copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            DailyHealthStampBadge(
                                tier = tier,
                                size = 26.dp,
                                preloadedOutlineBitmap = preloadedOutlineBitmap
                            )
                            Text(
                                text = tier.label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ink,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendStampItem(
    tier: DailyHealthTier,
    label: String,
    preloadedOutlineBitmap: ImageBitmap?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        DailyHealthStampBadge(
            tier = tier,
            size = 19.dp,
            preloadedOutlineBitmap = preloadedOutlineBitmap
        )
        Text(text = label, fontSize = 10.sp, color = CardSubInk, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CalendarStampCell(
    day: Int,
    dayOfWeek: DayOfWeek,
    tier: DailyHealthTier,
    hasData: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    isPendingStamp: Boolean,
    isLiftedForRitual: Boolean = false,
    preloadedOutlineBitmap: ImageBitmap? = null,
    onDayClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalDarkTheme.current

    val cellBackground = when {
        isSelected -> ItvGreen
        hasData -> if (isDark) Color(0xFF26352C) else Color(0xFFF8F5EF)
        else -> Color.Transparent
    }

    val textColor = when {
        isSelected -> Color.White
        hasData -> when (dayOfWeek) {
            DayOfWeek.SUNDAY -> Color(0xFFE53935)
            DayOfWeek.SATURDAY -> Color(0xFF1E88E5)
            else -> CardInk
        }
        else -> CardSubInk.copy(alpha = 0.34f)
    }

    val border = when {
        isSelected -> BorderStroke(1.6.dp, ItvGreen)
        isPendingStamp -> BorderStroke(1.6.dp, Color(0xFFF59E0B))
        isToday -> BorderStroke(1.5.dp, ItvGreen)
        hasData -> BorderStroke(0.9.dp, if (isDark) Color(0xFF40594A) else Color(0xFFD6CEC0))
        else -> null
    }

    // Subtle organic rubber-stamp tilt (-5° to +5°) deterministic per day
    val tilt = remember(day) { ((day * 7) % 11 - 5).toFloat() }

    Surface(
        modifier = modifier
            .padding(1.dp)
            .height(68.dp)
            .graphicsLayer { alpha = if (isLiftedForRitual) 0.16f else 1f }
            .clip(RoundedCornerShape(10.dp))
            .clickable { onDayClick(day) },
        shape = RoundedCornerShape(10.dp),
        color = cellBackground,
        border = border,
        shadowElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1.dp)
        ) {
            Text(
                text = day.toString(),
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = if (hasData || isSelected) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isPendingStamp -> {
                        Text(
                            text = "🐾",
                            fontSize = 20.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    hasData -> {
                        DailyHealthStampBadge(
                            tier = tier,
                            size = 41.dp,
                            onSelectedSurface = isSelected,
                            tiltDegrees = tilt,
                            preloadedOutlineBitmap = preloadedOutlineBitmap
                        )
                    }
                    else -> {
                        Spacer(Modifier.height(41.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyStampTallyStrip(
    tally: MonthlyHealthTally,
    preloadedOutlineBitmap: ImageBitmap? = null
) {
    val isDark = LocalDarkTheme.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ItvInsetSurface,
        border = BorderStroke(0.9.dp, ItvLine.copy(alpha = if (isDark) 0.55f else 0.75f))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${tally.yearMonth.monthValue}월 강아지 스탬프 집계",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = CardInk
                )
                if (tally.evaluatedFinalDays > 0) {
                    Text(
                        text = "웃는 날 ${tally.happyRatePercent}% · 평균 ${tally.averageScore}점",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ItvGreen
                    )
                } else {
                    Text(
                        text = "확정된 날이 쌓이면 집계돼요",
                        style = MaterialTheme.typography.labelSmall,
                        color = CardSubInk
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TallyPill(DailyHealthTier.GOLD, tally.goldCount, preloadedOutlineBitmap)
                TallyPill(DailyHealthTier.SILVER, tally.silverCount, preloadedOutlineBitmap)
                TallyPill(DailyHealthTier.BRONZE, tally.bronzeCount, preloadedOutlineBitmap)
                TallyPill(DailyHealthTier.WARNING, tally.warningCount, preloadedOutlineBitmap)
                TallyPill(DailyHealthTier.CRITICAL, tally.criticalCount, preloadedOutlineBitmap)
            }
        }
    }
}

@Composable
private fun TallyPill(
    tier: DailyHealthTier,
    count: Int,
    preloadedOutlineBitmap: ImageBitmap? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        DailyHealthStampBadge(
            tier = tier,
            size = 18.dp,
            preloadedOutlineBitmap = preloadedOutlineBitmap
        )
        Text(
            text = "${count}일",
            fontSize = 11.sp,
            fontWeight = if (count > 0) FontWeight.Bold else FontWeight.Normal,
            color = if (count > 0) CardInk else CardSubInk.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun DailyHealthVerdictBanner(
    assessment: DailyHealthAssessment,
    slamKey: Int,
    onReplayStampSlam: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    val accentColor = stampInkColor(assessment.tier)

    val bannerBg = when (assessment.tier) {
        DailyHealthTier.GOLD -> if (isDark) Color(0xFF192B20) else Color(0xFFF0FDF4)
        DailyHealthTier.WARNING -> if (isDark) Color(0xFF2B2218) else Color(0xFFFFF7ED)
        DailyHealthTier.CRITICAL -> if (isDark) Color(0xFF2D1B1C) else Color(0xFFFEF2F2)
        else -> if (isDark) Color(0xFF1E2822) else Color.White
    }

    ItvTactileCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        containerColor = bannerBg,
        borderColor = ItvInkBorder,
        shadowColor = ItvInkShadow,
        borderWidth = 1.8.dp,
        shadowOffsetX = 1.5.dp,
        shadowOffsetY = 3.5.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Interactive rubber stamp: tap anytime to replay the "쾅!" slam motion!
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(enabled = assessment.tier != DailyHealthTier.IN_PROGRESS) {
                            onReplayStampSlam()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    SlammablePuppyStamp(
                        tier = assessment.tier,
                        slamKey = if (assessment.tier == DailyHealthTier.IN_PROGRESS) 0 else slamKey,
                        size = 58.dp,
                        tiltDegrees = -5f
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (assessment.tier == DailyHealthTier.IN_PROGRESS) {
                                "진행 중 · 기상 후 확정"
                            } else {
                                "${assessment.tier.label} 스탬프"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CardInk
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accentColor.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = if (assessment.tier == DailyHealthTier.IN_PROGRESS) {
                                    "현재 예상 ${assessment.projectedTier.label} (${assessment.score}점)"
                                } else {
                                    "${assessment.score}점"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (assessment.tier != DailyHealthTier.IN_PROGRESS) {
                        Text(
                            text = "도장을 터치하면 쾅! 찍기 모션을 다시 볼 수 있어요",
                            fontSize = 10.sp,
                            color = CardSubInk.copy(alpha = 0.72f)
                        )
                    }
                }
            }

            // Headline summary in its own bordered box
            if (assessment.headline.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDark) Color(0xFF151F18) else Color.White.copy(alpha = 0.92f),
                    border = BorderStroke(
                        0.9.dp,
                        accentColor.copy(alpha = if (isDark) 0.32f else 0.24f)
                    )
                ) {
                    Text(
                        text = assessment.headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = CardInk,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            if (assessment.isPartialData) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDark) Color(0xFF243028) else Color(0xFFF1F5F9),
                    border = BorderStroke(
                        0.8.dp,
                        if (isDark) ItvLine.copy(alpha = 0.45f) else Color(0xFFCBD5E1)
                    )
                ) {
                    Text(
                        text = "ℹ️ ${assessment.evaluatedAxesCount}/${assessment.totalAxesCount}개 항목 데이터로 가중치를 자동 보정해 판정했어요",
                        fontSize = 11.sp,
                        color = CardSubInk,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }

            // Praises (Restraint highlights) - boxed section + boxed sentences
            if (assessment.praises.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    color = if (isDark) Color(0xFF16271C) else Color(0xFFF2FBF5),
                    border = BorderStroke(
                        1.dp,
                        ItvGreen.copy(alpha = if (isDark) 0.4f else 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(
                            text = "잘한 점 (절제력)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ItvGreen,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        assessment.praises.forEach { praise ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(9.dp),
                                color = if (isDark) Color(0xFF1D3024) else Color.White,
                                border = BorderStroke(
                                    0.75.dp,
                                    ItvGreen.copy(alpha = if (isDark) 0.28f else 0.2f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ItvGreen)
                                    Text(
                                        text = praise,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CardInk,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Cautions (Overuse / Rhythm risks) - boxed section + boxed sentences
            if (assessment.cautions.isNotEmpty()) {
                val warnColor = if (assessment.tier == DailyHealthTier.CRITICAL) Color(0xFFDC2626) else Color(0xFFD97706)
                val warnSectionBg = when {
                    assessment.tier == DailyHealthTier.CRITICAL ->
                        if (isDark) Color(0xFF2B181A) else Color(0xFFFEF5F5)
                    else ->
                        if (isDark) Color(0xFF292014) else Color(0xFFFFFBF2)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    color = warnSectionBg,
                    border = BorderStroke(
                        1.dp,
                        warnColor.copy(alpha = if (isDark) 0.42f else 0.32f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(
                            text = "아쉬운 점 (과사용·리듬)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = warnColor,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        assessment.cautions.forEach { caution ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(9.dp),
                                color = if (isDark) Color(0xFF241D18) else Color.White,
                                border = BorderStroke(
                                    0.75.dp,
                                    warnColor.copy(alpha = if (isDark) 0.3f else 0.22f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text("!", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = warnColor)
                                    Text(
                                        text = caution,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CardInk,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyThreeFactsRow(
    facts: DayResolvedFacts,
    baselineBedtimeMin: Int?
) {
    val isDark = LocalDarkTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Sleep / Bedtime
        val bedStr = facts.bedtimeMinute?.let { formatMinuteOfDay(it) } ?: "--:--"
        val bedSub = when {
            facts.sleepDurationMinutes != null -> {
                val h = (facts.sleepDurationMinutes / 60).toInt()
                val m = (facts.sleepDurationMinutes % 60).toInt()
                "${h}시간 ${m}분"
            }
            baselineBedtimeMin != null && facts.bedtimeMinute != null -> "평소 ${formatMinuteOfDay(baselineBedtimeMin)}"
            else -> "야간 기록"
        }
        CompactFactCard(
            emoji = "🛏️",
            label = "취침 시각",
            value = bedStr,
            sub = bedSub,
            isDark = isDark,
            modifier = Modifier.weight(1f)
        )

        // 2. Shortform
        val sfVal = if (facts.shortformMs > 0L) formatShortDuration(facts.shortformMs) else "0분"
        val sfSub = when {
            facts.shortformAvgSessionMs != null && facts.shortformAvgSessionMs > 0L ->
                "세션 ${formatShortDuration(facts.shortformAvgSessionMs)}"
            facts.shortformSwipes > 0L -> "${facts.shortformSwipes}회 넘김"
            else -> "클린"
        }
        CompactFactCard(
            emoji = "🎬",
            label = "숏폼",
            value = sfVal,
            sub = sfSub,
            isDark = isDark,
            modifier = Modifier.weight(1f)
        )

        // 3. Total Phone & Session
        val phoneVal = formatShortDuration(facts.totalPhoneMs)
        val phoneSub = facts.phoneAvgSessionMs?.let { "평균 ${formatShortDuration(it)}" } ?: "총 사용"
        CompactFactCard(
            emoji = "⏱️",
            label = "스마트폰",
            value = phoneVal,
            sub = phoneSub,
            isDark = isDark,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CompactFactCard(
    emoji: String,
    label: String,
    value: String,
    sub: String,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (isDark) Color(0xFF1E2822) else Color.White,
        border = BorderStroke(0.9.dp, if (isDark) ItvLine.copy(alpha = 0.4f) else ItvLine)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "$emoji $label",
                fontSize = 11.sp,
                color = CardSubInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = CardInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                fontSize = 10.sp,
                color = CardSubInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FourAxesBreakdownCard(axes: List<HealthAxisScore>) {
    val isDark = LocalDarkTheme.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isDark) Color(0xFF1E2822) else Color.White,
        border = BorderStroke(1.dp, if (isDark) ItvLine.copy(alpha = 0.4f) else ItvLine)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "🔍 ITV 종합 맥락 분석",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = CardInk
            )

            axes.forEach { axis ->
                val scoreColor = when {
                    !axis.available -> CardSubInk.copy(alpha = 0.6f)
                    axis.score >= 80 -> ItvGreen
                    axis.score >= 55 -> Color(0xFFD97706)
                    else -> Color(0xFFDC2626)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDark) Color(0xFF172019) else Color(0xFFF7F4EE),
                    border = BorderStroke(
                        0.9.dp,
                        if (isDark) ItvLine.copy(alpha = 0.4f) else ItvLine.copy(alpha = 0.85f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
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
                                    text = axis.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (axis.available) CardInk else CardSubInk.copy(alpha = 0.6f)
                                )
                                if (axis.available) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isDark) Color(0xFF243127) else Color(0xFFEFEAE0)
                                    ) {
                                        Text(
                                            text = "비중 ${axis.weightPercent}%",
                                            fontSize = 10.sp,
                                            color = CardSubInk,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = scoreColor.copy(alpha = if (axis.available) 0.13f else 0.08f)
                            ) {
                                Text(
                                    text = if (axis.available) "${axis.score}점" else "미집계",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = scoreColor,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Progress track
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isDark) Color(0xFF2C392F) else Color(0xFFE2E8F0))
                        ) {
                            if (axis.available) {
                                val barColor = when {
                                    axis.score >= 80 -> ItvGreen
                                    axis.score >= 55 -> Color(0xFFF59E0B)
                                    else -> Color(0xFFEF4444)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction = (axis.score / 100f).coerceIn(0.03f, 1f))
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(barColor)
                                )
                            }
                        }

                        HorizontalDivider(
                            color = if (isDark) ItvLine.copy(alpha = 0.25f) else ItvLine.copy(alpha = 0.55f)
                        )

                        Text(
                            text = axis.summary,
                            fontSize = 11.5.sp,
                            color = CardSubInk,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayDetailDialog(
    date: LocalDate,
    today: LocalDate,
    assessment: DailyHealthAssessment?,
    facts: DayResolvedFacts?,
    baselineBedtimeMin: Int?,
    loadingMonth: Boolean,
    dayReport: Report?,
    loadingDayReport: Boolean,
    slamKey: Int,
    onReplayStampSlam: (DailyHealthAssessment) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    var currentTab by remember(date) { mutableStateOf(CalendarDetailTab.COACHING) }
    var selectedHour by remember(date) { mutableStateOf<Int?>(null) }
    var showAllApps by remember(date) { mutableStateOf(false) }

    val dayOfWeekName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREA)
    val isDateToday = date == today
    val isRestDay = facts?.isRestDay
        ?: (date.dayOfWeek in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
    val dayTypeLabel = if (isRestDay) "🌿 쉬는 날" else "💼 일과 날"
    val hasData = assessment != null && assessment.tier != DailyHealthTier.NO_DATA

    val context = LocalContext.current
    val dailyApps = remember(dayReport, date) {
        val fromLive = dayReport?.hourlyApps
            ?.asSequence()
            ?.flatten()
            ?.groupBy { it.packageName }
            ?.map { (pkg, list) ->
                AppHourUsage(pkg, list.first().label, list.sumOf { it.minutes })
            }
            ?.sortedByDescending { it.minutes }
            ?.filter { it.minutes >= 1.0 }
            ?.toList()
            .orEmpty()
        fromLive.ifEmpty {
            MonthlyUsageArchiveStore.getArchivedTopApps(context, date)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ItvTactileCard(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(24.dp),
            containerColor = if (isDark) Color(0xFF0E1410) else Color(0xFFEAE4DA),
            borderColor = ItvInkBorder,
            shadowColor = ItvInkShadow,
            borderWidth = 2.dp,
            shadowOffsetX = 2.5.dp,
            shadowOffsetY = 5.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header: Date + Today badge + Day type + Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${date.monthValue}월 ${date.dayOfMonth}일 ($dayOfWeekName)",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = CardInk
                            )
                            if (isDateToday) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = ItvMint,
                                    contentColor = ItvGreen
                                ) {
                                    Text(
                                        "오늘",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = dayTypeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = ItvGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = if (isDark) Color(0xFF28362B) else Color.White,
                        border = BorderStroke(1.dp, if (isDark) ItvLine.copy(alpha = 0.45f) else ItvLine),
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CardInk)
                        }
                    }
                }

                // 2-Tab Switcher: 건강 리포트 / 시간대별 사용 분포
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CalendarDetailTab.entries.forEach { tab ->
                        val isSelected = currentTab == tab
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { currentTab = tab },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) ItvGreen else (if (isDark) Color(0xFF1E2822) else Color.White),
                            border = BorderStroke(
                                width = if (isSelected) 0.dp else 1.dp,
                                color = if (isDark) ItvLine.copy(alpha = 0.4f) else ItvLine
                            )
                        ) {
                            Text(
                                text = tab.label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else CardSubInk,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp)
                            )
                        }
                    }
                }

                // Scrollable Body inside Dialog
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (currentTab) {
                        CalendarDetailTab.COACHING -> {
                            if (loadingMonth && assessment == null) {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp), color = ItvGreen)
                                }
                            } else if (assessment == null || !hasData) {
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.outlinedCardColors(containerColor = RecordContentGreen)
                                ) {
                                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = if (date.isAfter(today)) {
                                                "아직 오지 않은 날짜예요."
                                            } else {
                                                assessment?.headline?.takeIf { it.isNotBlank() }
                                                    ?: "저장된 스마트폰 및 수면 기록이 없는 날입니다."
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = CardSubInk
                                        )
                                    }
                                }
                            } else {
                                DailyHealthVerdictBanner(
                                    assessment = assessment,
                                    slamKey = slamKey,
                                    onReplayStampSlam = { onReplayStampSlam(assessment) }
                                )
                                if (facts != null) {
                                    KeyThreeFactsRow(
                                        facts = facts,
                                        baselineBedtimeMin = baselineBedtimeMin
                                    )
                                }
                                if (assessment.axes.isNotEmpty()) {
                                    FourAxesBreakdownCard(axes = assessment.axes)
                                }
                            }
                        }

                        CalendarDetailTab.HOURLY_AND_APPS -> {
                            if (loadingDayReport && dayReport == null) {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp), color = ItvGreen)
                                }
                            } else {
                                // 1. Interactive Hourly Bar Chart (tap any bar to inspect apps used in that hour!)
                                InteractiveHourlyDistributionCard(
                                    dayReport = dayReport,
                                    selectedDate = date,
                                    selectedHour = selectedHour,
                                    onSelectHour = { hour ->
                                        selectedHour = if (selectedHour == hour) null else hour
                                    }
                                )

                                // 2. Used Apps below Hourly Distribution (Top 3 by default + "자세히 보기" toggle)
                                AppUsageListCard(
                                    apps = dailyApps,
                                    selectedDate = date,
                                    showAllApps = showAllApps,
                                    onToggleShowAll = { showAllApps = !showAllApps }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveHourlyDistributionCard(
    dayReport: Report?,
    selectedDate: LocalDate,
    selectedHour: Int?,
    onSelectHour: (Int) -> Unit
) {
    val isDark = LocalDarkTheme.current
    val hourlyValues = dayReport?.values ?: List(24) { 0.0 }
    val axisMax = 60.0
    val gridColor = if (isDark) Color(0xFF33473A) else Color(0xFFD9D0C3)
    val selectionBg = ItvGreen.copy(alpha = if (isDark) 0.24f else 0.14f)

    ItvTactileCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        containerColor = ItvCardSurface,
        borderColor = ItvInkBorder,
        shadowColor = ItvInkShadow,
        borderWidth = 1.6.dp,
        shadowOffsetX = 1.5.dp,
        shadowOffsetY = 3.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "📊 ${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일 시간대별 사용 분포",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = CardInk
            )
            Text(
                text = "좌우로 밀어 24시간을 확인하고, 막대를 터치해 시간대별 앱 사용을 확인하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = CardSubInk
            )

            Row(Modifier.fillMaxWidth()) {
                // Fixed Y-axis (0 ~ 60분) matching Records tab
                Column(Modifier.width(34.dp)) {
                    Box(Modifier.height(152.dp).fillMaxWidth()) {
                        for (tick in 0..4) {
                            Text(
                                text = (axisMax * (4 - tick) / 4).roundToLong().toString(),
                                modifier = Modifier.offset(y = (-6 + tick * 38).dp),
                                fontSize = 10.sp,
                                color = CardSubInk
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }

                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(hourlyValues, key = { hour, _ -> hour }) { hour, mins ->
                        val barHeight = (if (mins >= 1.0) (mins.coerceIn(0.0, axisMax) / axisMax * 152.0) else 0.0).toFloat().dp
                        val isLateNight = hour >= 23 || hour <= 4
                        val isBarSelected = selectedHour == hour
                        val barColor = when {
                            mins < 1.0 -> if (isDark) Color(0xFF333E35) else Color(0xFFE2E8F0)
                            isLateNight && mins >= 15.0 -> Color(0xFFF59E0B)
                            else -> ItvGreen
                        }

                        Column(
                            modifier = Modifier
                                .width(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelectHour(hour) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(152.dp)
                                    .fillMaxWidth()
                                    .background(if (isBarSelected) selectionBg else Color.Transparent)
                                    .drawBehind {
                                        for (tick in 0..4) {
                                            val y = tick * 38.dp.toPx()
                                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                                        }
                                    },
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(20.dp)
                                        .height(barHeight.coerceAtLeast(if (mins > 0.0) 4.dp else 2.dp))
                                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                        .background(barColor)
                                )
                                if (mins >= 1.0) {
                                    Text(
                                        text = "${mins.roundToLong()}분",
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .offset(y = -minOf(barHeight.value + 3f, 134f).dp),
                                        fontSize = 10.sp,
                                        fontWeight = if (isBarSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = CardInk,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1
                                    )
                                }
                            }
                            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${hour}시",
                                    fontSize = 10.sp,
                                    color = if (isBarSelected) ItvGreen else CardSubInk,
                                    fontWeight = if (isBarSelected || mins >= 10.0) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            // Expanded Hourly App Breakdown (same interactive behavior as Records tab!)
            if (selectedHour != null && selectedHour in hourlyValues.indices) {
                val hourMins = hourlyValues[selectedHour]
                val hourApps = remember(dayReport, selectedHour) {
                    dayReport?.hourlyApps
                        ?.getOrNull(selectedHour)
                        .orEmpty()
                        .filter { it.minutes >= 1.0 }
                        .sortedByDescending { it.minutes }
                }

                HorizontalDivider(color = if (isDark) ItvLine.copy(alpha = 0.35f) else ItvLine)

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isDark) Color(0xFF172019) else Color(0xFFF6F2EA),
                    border = BorderStroke(0.8.dp, ItvGreen.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${selectedHour}:00 ~ ${selectedHour + 1}:00",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = CardInk
                                )
                                Text(
                                    text = "사용시간 ${hourlyDuration(hourMins)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ItvGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            TextButton(
                                onClick = { onSelectHour(selectedHour) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("접기", fontSize = 12.sp, color = ItvGreen, fontWeight = FontWeight.Bold)
                            }
                        }

                        ShortformHourPanel(selectedDate.toString(), selectedHour)

                        Text(
                            text = "이 시간대에 사용한 앱",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = CardInk
                        )

                        if (hourApps.isEmpty()) {
                            Text(
                                text = "이 시간대에 확인된 앱 사용 내역이 없습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CardSubInk
                            )
                        } else {
                            hourApps.forEach { app ->
                                AppUsageRowItem(app = app)
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "막대를 터치하면 그 시간에 사용한 앱과 사용시간이 아래에 표시됩니다.",
                    fontSize = 11.sp,
                    color = CardSubInk
                )
            }
        }
    }
}

private data class CachedAppUiMeta(
    val name: String,
    val icon: ImageBitmap?
)

private object CalendarAppUiCache {
    private val cache = ConcurrentHashMap<String, CachedAppUiMeta>()

    fun getCached(packageName: String): CachedAppUiMeta? = cache[packageName]

    fun loadOrCache(context: android.content.Context, app: AppHourUsage, iconPx: Int): CachedAppUiMeta {
        cache[app.packageName]?.let { return it }
        val pm = context.applicationContext.packageManager
        val name = try {
            val info = pm.getApplicationInfo(app.packageName, 0)
            pm.getApplicationLabel(info).toString()
                .takeIf { it.isNotBlank() && it != app.packageName }
                ?: formatAppName(app.packageName)
        } catch (_: Exception) {
            if (app.label.isNotBlank() && app.label != app.packageName) app.label
            else formatAppName(app.packageName)
        }
        val icon = try {
            val drawable = pm.getApplicationIcon(app.packageName)
            val px = iconPx.coerceIn(32, 128)
            val bmp = createBitmap(px, px, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(Canvas(bmp))
            bmp.asImageBitmap()
        } catch (_: Exception) {
            null
        }
        val meta = CachedAppUiMeta(name = name, icon = icon)
        cache[app.packageName] = meta
        return meta
    }
}

@Composable
private fun AppUsageRowItem(
    app: AppHourUsage,
    formatHours: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val iconPx = remember(density) { (32f * density).roundToInt().coerceIn(32, 128) }

    val meta by produceState(
        initialValue = CalendarAppUiCache.getCached(app.packageName),
        key1 = app.packageName
    ) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                CalendarAppUiCache.loadOrCache(context, app, iconPx)
            }
        }
    }

    val displayName = meta?.name ?: if (app.label.isNotBlank() && app.label != app.packageName) {
        app.label
    } else {
        formatAppName(app.packageName)
    }

    val durationText = if (formatHours) {
        val totalMin = app.minutes.roundToLong()
        val h = totalMin / 60L
        val m = totalMin % 60L
        when {
            h > 0L && m > 0L -> "${h}시간 ${m}분"
            h > 0L -> "${h}시간"
            else -> "${m}분"
        }
    } else {
        hourlyDuration(app.minutes)
    }

    val isDark = LocalDarkTheme.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (isDark) Color(0xFF172019) else Color(0xFFF7F4EE),
        border = BorderStroke(
            0.8.dp,
            if (isDark) ItvLine.copy(alpha = 0.35f) else ItvLine.copy(alpha = 0.75f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                val iconBitmap = meta?.icon
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = ItvMint
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("📱", fontSize = 15.sp)
                        }
                    }
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CardInk,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = durationText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ItvGreen
            )
        }
    }
}

@Composable
private fun AppUsageListCard(
    apps: List<AppHourUsage>,
    selectedDate: LocalDate,
    showAllApps: Boolean,
    onToggleShowAll: () -> Unit
) {
    val isDark = LocalDarkTheme.current
    val visibleApps = if (showAllApps) apps else apps.take(3)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isDark) Color(0xFF1E2822) else Color.White,
        border = BorderStroke(1.dp, if (isDark) ItvLine.copy(alpha = 0.4f) else ItvLine)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📱 ${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일 사용 앱",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = CardInk
                )
                if (apps.isNotEmpty()) {
                    Text(
                        text = if (showAllApps || apps.size <= 3) "전체 ${apps.size}개" else "상위 3개 / 총 ${apps.size}개",
                        fontSize = 11.sp,
                        color = CardSubInk,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (apps.isEmpty()) {
                Text(
                    "확인된 앱별 상세 사용 기록이 없습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CardSubInk
                )
            } else {
                visibleApps.forEach { app ->
                    AppUsageRowItem(app = app, formatHours = true)
                }

                if (apps.size > 3) {
                    OutlinedButton(
                        onClick = onToggleShowAll,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ItvGreen.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ItvGreen),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(
                            text = if (showAllApps) "접기 (상위 3개만 보기)" else "자세히 보기 (전체 ${apps.size}개)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthStampHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("강아지 표정 스탬프 기준", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "단순 사용 시간이 아니라, 수면 절제·숏폼 탈출력·세션 건강도·생활 리듬을 깐깐하게 종합해 5가지 강아지 표정 스탬프를 찍어드려요.",
                    style = MaterialTheme.typography.bodySmall
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("• 수면·취침 절제 (40%): 권장 취침 전 2시간 점증 가중 절제 & 수면 리듬 유지", fontSize = 12.sp)
                    Text("• 숏폼 탈출·속도 (30%): 짧은 세션 탈출 & 무의식 고속 스와이프 방지", fontSize = 12.sp)
                    Text("• 세션 건강도 (20%): 멍하니 장시간 연속 사용 방지 & 목적성 활용", fontSize = 12.sp)
                    Text("• 생활 리듬 균형 (10%): 평일·휴일 패턴에 맞춘 균형 있는 사용", fontSize = 12.sp)
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HelpTierRow(DailyHealthTier.GOLD, "해맑음 (84점+): 취침 전 절제와 숏폼 통제가 완벽한 날")
                    HelpTierRow(DailyHealthTier.SILVER, "웃음 (74~83점): 전반적으로 건강하게 잘 관리한 날")
                    HelpTierRow(DailyHealthTier.BRONZE, "슬픔 (64~73점): 무난하지만 살짝 아쉬움이 남는 날")
                    HelpTierRow(DailyHealthTier.WARNING, "화남 (52~63점): 야간 수면 지연 또는 숏폼·세션 과사용 주의")
                    HelpTierRow(DailyHealthTier.CRITICAL, "아픔 (~51점): 수면 지연과 과몰입이 겹친 심각한 날")
                }
                Text(
                    text = "※ 오늘 기록은 내일 아침 기상 후 확정되며, 4~5단계(화남·아픔) 날은 추후 휴식 미션 달성으로 표정을 회복하는 보상이 연동될 예정입니다.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("확인") }
        }
    )
}

@Composable
private fun HelpTierRow(tier: DailyHealthTier, desc: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DailyHealthStampBadge(tier = tier, size = 22.dp)
        Text(text = desc, fontSize = 12.sp)
    }
}

private fun formatAppName(pkg: String): String {
    return when {
        pkg.contains("youtube") -> "YouTube"
        pkg.contains("instagram") -> "Instagram"
        pkg.contains("kakao") -> "카카오톡"
        pkg.contains("chrome") -> "Chrome"
        pkg.contains("sec.android.app.sbrowser") -> "삼성 인터넷"
        pkg.contains("netflix") -> "Netflix"
        else -> pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
    }
}

private fun formatMinuteOfDay(min: Int): String {
    val norm = ((min % 1440) + 1440) % 1440
    val h = norm / 60
    val m = norm % 60
    return String.format(Locale.KOREA, "%02d:%02d", h, m)
}

private fun formatShortDuration(ms: Long): String {
    if (ms <= 0L) return "0분"
    val totalMin = ms / 60_000L
    val h = totalMin / 60L
    val m = totalMin % 60L
    return when {
        h > 0L && m > 0L -> "${h}시간 ${m}분"
        h > 0L -> "${h}시간"
        else -> "${m}분"
    }
}
