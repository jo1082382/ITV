package com.example.itv.presentation.navigation
import com.example.itv.domain.goals.HomeGoalRow


import com.example.itv.presentation.usage.SleepEstimationTabContent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.itv.MainActivity
import com.example.itv.presentation.auth.AccountSettingsScreen
import com.example.itv.presentation.coaching.CoachingSettingsScreen
import com.example.itv.composition.AuthComposition
import com.example.itv.presentation.permissions.PermissionSettingsActivity
import com.example.itv.infrastructure.theme.UserThemeStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.presentation.home.HomeScreen
import com.example.itv.presentation.components.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import com.example.itv.composition.queryNightReport
import com.example.itv.composition.queryUsageReport
import com.example.itv.domain.equip.EquipCatalog
import com.example.itv.domain.equip.EquipItem
import com.example.itv.domain.equip.EquipSlot
import com.example.itv.domain.records.DailyHealthTier
import com.example.itv.infrastructure.equip.UserEquipStore
import com.example.itv.infrastructure.reward.UserRewardStore
import com.example.itv.infrastructure.storage.CalendarHealthLoader
import com.example.itv.infrastructure.storage.MonthlyUsageArchiveStore
import com.example.itv.presentation.usage.DailyHealthStampBadge
import com.example.itv.presentation.usage.DailyStampPrefs
import com.example.itv.presentation.usage.DailyStampRitualOverlay
import com.example.itv.presentation.usage.StampCharacterBitmapCache
import com.example.itv.presentation.usage.StampRitualRequest
import com.example.itv.presentation.usage.stampInkColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

private data class MainTab(val id: String, val label: String, val mark: String)
private val tabs = listOf(
    MainTab("home", "홈", "⌂"),
    MainTab("records", "취침", "☾"),
    MainTab("character", "캐릭터", "♧")
)

@Composable
fun ItvMainScreen(
    @Suppress("UNUSED_PARAMETER") goals: Flow<List<HomeGoalRow>>,
    selectedTab: String,
    onSelectTab: (String) -> Unit,
    onOpenGoals: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTab = selectedTab.takeIf { id -> tabs.any { it.id == id } } ?: "home"
    val rootContext = LocalContext.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    var showAccountSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showAccountSettings) { showAccountSettings = false }
    var showCoachingSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showCoachingSettings) { showCoachingSettings = false }
    var showCoaching by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = !settingsOpen && !showAccountSettings && !showCoachingSettings && (activeTab != "home" || showCoaching)) {
        if (showCoaching) showCoaching = false else onSelectTab("home")
    }

    // Full-screen "도장 쾅!" stamp ritual state & once-per-day morning auto-launch for yesterday
    var activeStampRitual by remember { mutableStateOf<StampRitualRequest?>(null) }
    var calendarFocusDate by remember { mutableStateOf<LocalDate?>(null) }
    var calendarFocusVersion by remember { mutableIntStateOf(0) }
    var stampRefreshVersion by remember { mutableIntStateOf(0) }
    var calendarReloadVersion by remember { mutableIntStateOf(0) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var morningCheckTick by remember { mutableIntStateOf(0) }
    var checkingMorningRitual by remember { mutableStateOf(false) }

    // Root coordinates + live calendar day cell coordinates stored via onPlaced (0 scroll callbacks!)
    var overlayRootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val dayCellCoords = remember { mutableMapOf<LocalDate, LayoutCoordinates>() }
    fun LayoutCoordinates?.liveBounds(): Rect? =
        this?.takeIf { it.isAttached }?.boundsInRoot()?.takeUnless { it.isEmpty }

    // Trigger morning stamp check on BOTH cold start and returning from background on a new day
    val rootLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(rootLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
                morningCheckTick++
            }
        }
        rootLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { rootLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // If the user confirms sleep in another tab and returns to Home, re-check once without cancelling an active check
    LaunchedEffect(activeTab) {
        if (activeTab == "home" && resumeTick > 0 && !checkingMorningRitual && activeStampRitual == null) {
            if (!DailyStampPrefs.hasAutoRitualShownForToday(rootContext, LocalDate.now())) {
                morningCheckTick++
            }
        }
    }

    LaunchedEffect(morningCheckTick) {
        if (morningCheckTick == 0) return@LaunchedEffect
        if (activeStampRitual != null) return@LaunchedEffect

        val today = LocalDate.now()
        if (DailyStampPrefs.hasAutoRitualShownForToday(rootContext, today)) return@LaunchedEffect
        val yesterday = today.minusDays(1)
        if (DailyStampPrefs.isDateStamped(rootContext, yesterday)) {
            DailyStampPrefs.markAutoRitualShownForToday(rootContext, today)
            return@LaunchedEffect
        }

        checkingMorningRitual = true
        try {
            val selectedChar = DailyStampPrefs.getSelectedCharacter(rootContext)
            val preloadJob = launch(Dispatchers.Default) {
                StampCharacterBitmapCache.preload(rootContext, selectedChar)
            }

        val assessment = runCatching {
            withContext(Dispatchers.IO) {
                val todayRep = runCatching { rootContext.queryUsageReport(1, today) }.getOrNull()
                val periodRep = runCatching { rootContext.queryUsageReport(7) }.getOrNull()
                val nightRep = runCatching { queryNightReport(rootContext, 45) }.getOrNull()
                runCatching {
                    MonthlyUsageArchiveStore.syncDailySnapshot(
                        context = rootContext,
                        todayUsageReport = todayRep,
                        periodUsageReport = periodRep,
                        nightReport = nightRep
                    )
                }
                val bundle = CalendarHealthLoader.loadMonth(
                    context = rootContext,
                    viewMonth = YearMonth.from(yesterday),
                    todayReport = if (YearMonth.from(yesterday) == YearMonth.from(today)) todayRep else null,
                    existingNightReport = nightRep
                )
                bundle.assessments[yesterday]
            }
        }.getOrNull()

        if (assessment != null &&
            assessment.tier != DailyHealthTier.NO_DATA &&
            assessment.tier != DailyHealthTier.IN_PROGRESS
        ) {
            // Focus yesterday on the Home calendar so the cell zooms out straight from yesterday's slot
            settingsOpen = false
            showAccountSettings = false
            showCoachingSettings = false
            showCoaching = false
            calendarFocusDate = yesterday
            calendarFocusVersion++
            calendarReloadVersion++
            onSelectTab("home")

            // Wait for yesterday's calendar cell to be laid out and attached on screen
            var cellBounds: Rect? = null
            var waitedMs = 0
            while (waitedMs < 4500) {
                val candidate = dayCellCoords[yesterday].liveBounds()
                val root = overlayRootCoords.liveBounds()
                if (candidate != null &&
                    (root == null || (candidate.bottom > root.top && candidate.top < root.bottom))
                ) {
                    cellBounds = candidate
                    break
                }
                delay(30)
                waitedMs += 30
            }
            preloadJob.join()
            // Brief visual beat so the user clearly sees yesterday's tile on the calendar right before it lifts out
            delay(380)

            if (activeStampRitual == null && !DailyStampPrefs.hasAutoRitualShownForToday(rootContext, today)) {
                DailyStampPrefs.markAutoRitualShownForToday(rootContext, today)
                val latestBounds = dayCellCoords[yesterday].liveBounds() ?: cellBounds
                activeStampRitual = StampRitualRequest(
                    date = yesterday,
                    tier = assessment.tier,
                    score = assessment.score,
                    headline = assessment.headline,
                    isMorningAuto = true,
                    isTestPreview = false,
                    character = selectedChar,
                    sourceCellBounds = latestBounds
                )
            }
        }
        } finally {
            checkingMorningRitual = false
        }
    }

    // Outer MainActivity Scaffold already handles system bar insets.
    Box(
        modifier
            .fillMaxSize()
            .onPlaced { overlayRootCoords = it }
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (activeTab == "home") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isDark = LocalDarkTheme.current
                    val context = LocalContext.current
                    Surface(
                        onClick = { UserThemeStore.toggleDarkMode(context) },
                        modifier = Modifier.size(64.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = ItvMint,
                        contentColor = if (isDark) Color(0xFFFFD54F) else ItvGreen,
                        border = BorderStroke(1.dp, ItvLine)
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                if (isDark) "☀️" else "🌙",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                if (isDark) "라이트" else "다크",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFFFFD54F) else ItvGreen
                            )
                        }
                    }

                    // 홈 화면 중앙: 실시간 플로팅 HUD 키기 버튼 (야간모드 및 설정 버튼과 동일한 64dp 크기)
                    var isHudShowing by remember {
                        mutableStateOf(com.example.itv.presentation.coaching.LongformDiagnosticHud.isShowing)
                    }
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                isHudShowing = com.example.itv.presentation.coaching.LongformDiagnosticHud.isShowing
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    Surface(
                        onClick = {
                            if (isHudShowing) {
                                com.example.itv.presentation.coaching.LongformDiagnosticHud.hide()
                                isHudShowing = false
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                    Toast.makeText(context, "다른 앱 위에 표시 권한을 허용해주세요.", Toast.LENGTH_SHORT).show()
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } else {
                                    val success = com.example.itv.presentation.coaching.LongformDiagnosticHud.show(context)
                                    if (success) {
                                        isHudShowing = true
                                        Toast.makeText(context, "실시간 플로팅 HUD가 활성화되었습니다.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(64.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = if (isHudShowing) ItvGreen.copy(alpha = 0.15f) else ItvMint,
                        contentColor = if (isHudShowing) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = BorderStroke(if (isHudShowing) 1.5.dp else 1.dp, if (isHudShowing) ItvGreen else ItvLine)
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            PuppyFaceMascot(Modifier.size(28.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (isHudShowing) "HUD ON" else "HUD",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isHudShowing) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        onClick = { settingsOpen = true },
                        modifier = Modifier.size(64.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = ItvMint,
                        contentColor = ItvGreen,
                        border = BorderStroke(1.dp, ItvLine)
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("⚙", style = MaterialTheme.typography.headlineSmall,
                                color = ItvGreen)
                            Text("설정", style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold, color = ItvGreen)
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabs.forEach { tab ->
                        val selected = activeTab == tab.id
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(if (selected) 1.5.dp else 1.dp,
                                if (selected) ItvGreen else ItvLine)
                        ) {
                            Column(
                                Modifier.fillMaxWidth().clickable(role = Role.Tab) {
                                    showCoaching = false
                                    onSelectTab(tab.id)
                                }.padding(horizontal = 2.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                when (tab.id) {
                                    "records" -> SleepTabIcon(if (selected) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                                    "character" -> PuppyFaceMascot(Modifier.size(28.dp))
                                    else -> Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                        Text(tab.mark, style = MaterialTheme.typography.titleLarge,
                                            color = if (selected) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(tab.label, style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center, maxLines = 1,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        // Each tab owns its scroll position; bottom navigation never scrolls away.
        when (activeTab) {
            "home" -> Box(Modifier.fillMaxSize().padding(padding)) {
                HomeScreen(
                    initialSelectedDate = calendarFocusDate,
                    focusRequestVersion = calendarFocusVersion,
                    stampRefreshVersion = stampRefreshVersion,
                    calendarReloadVersion = calendarReloadVersion,
                    daySyncTick = resumeTick,
                    activeRitualDate = activeStampRitual?.date,
                    onDayCellPlaced = { date, coords -> dayCellCoords[date] = coords },
                    onDayCellDisposed = { date -> dayCellCoords.remove(date) },
                    onLaunchStampRitual = { ritual ->
                        val resolvedBounds = ritual.sourceCellBounds ?: dayCellCoords[ritual.date].liveBounds()
                        activeStampRitual = ritual.copy(sourceCellBounds = resolvedBounds)
                    }
                )
            }
            "records" -> Box(Modifier.fillMaxSize().padding(padding)) {
                SleepTabScreen(onSleepDataChanged = { calendarReloadVersion++ })
            }
            "character" -> Box(Modifier.fillMaxSize().padding(padding)) {
                CharacterScreen(
                    calendarReloadVersion = calendarReloadVersion,
                    stampRefreshVersion = stampRefreshVersion,
                    daySyncTick = resumeTick
                )
            }
        }
    }
    HomeSettingsDrawer(
        open = settingsOpen,
        onClose = { settingsOpen = false },
        onOpenGoals = onOpenGoals,
        onOpenAccountSettings = { showAccountSettings = true },
        onOpenCoachingSettings = { showCoachingSettings = true }
    )

    if (showCoachingSettings) {
        CoachingSettingsScreen(
            onNavigateBack = { showCoachingSettings = false }
        )
    }

    if (showAccountSettings) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        AccountSettingsScreen(
            onNavigateBack = { showAccountSettings = false },
            onAccountDeleted = {
                showAccountSettings = false
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(MainActivity.EXTRA_FORCE_ONBOARDING, true)
                }
                context.startActivity(intent)
            },
            onSignedOut = {
                showAccountSettings = false
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(intent)
            },
            onAccountLinked = {
                showAccountSettings = false
                settingsOpen = false
                stampRefreshVersion++
                calendarReloadVersion++
                onSelectTab("home")
            },
            onGoogleSignInRequested = { callback ->
                coroutineScope.launch {
                    try {
                        val client = AuthComposition.googleAuthClient(context)
                        val tokenRes = client.getGoogleIdToken()
                        tokenRes.onSuccess { callback(it) }
                            .onFailure { android.widget.Toast.makeText(context, "Google 인증: ${it.message}", android.widget.Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Google 인증 오류: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Full-screen cinematic stamp ritual overlay (above Scaffold & bottom navigation bar)
    activeStampRitual?.let { ritual ->
        DailyStampRitualOverlay(
            request = ritual,
            overlayRootBounds = overlayRootCoords.liveBounds(),
            resolveCellBounds = { date -> dayCellCoords[date].liveBounds() },
            onStampCommitted = {
                if (!ritual.isTestPreview) {
                    DailyStampPrefs.markDateStamped(rootContext, ritual.date)
                    stampRefreshVersion++
                }
            },
            onFinished = {
                if (!ritual.isTestPreview) {
                    DailyStampPrefs.markDateStamped(rootContext, ritual.date)
                    stampRefreshVersion++
                }
                activeStampRitual = null
            }
        )
    }
    }
}

@Composable
private fun SleepTabScreen(onSleepDataChanged: () -> Unit) {
    SleepEstimationTabContent(onSleepDataChanged = onSleepDataChanged)
}

internal fun DailyHealthTier?.toPuppyMoodOrNull(): PuppyMood? = when (this) {
    DailyHealthTier.GOLD -> PuppyMood.BRIGHT
    DailyHealthTier.SILVER -> PuppyMood.SMILE
    DailyHealthTier.BRONZE -> PuppyMood.SAD
    DailyHealthTier.WARNING -> PuppyMood.ANGRY
    DailyHealthTier.CRITICAL -> PuppyMood.SICK
    else -> null
}

internal fun PuppyMood.toHealthTier(): DailyHealthTier = when (this) {
    PuppyMood.BRIGHT -> DailyHealthTier.GOLD
    PuppyMood.SMILE -> DailyHealthTier.SILVER
    PuppyMood.SAD -> DailyHealthTier.BRONZE
    PuppyMood.ANGRY -> DailyHealthTier.WARNING
    PuppyMood.SICK -> DailyHealthTier.CRITICAL
}

@Composable
private fun CharacterScreen(
    calendarReloadVersion: Int,
    stampRefreshVersion: Int,
    daySyncTick: Int
) {
    val context = LocalContext.current
    val rewardFlow = remember { UserRewardStore.rewardState }
    val currentReward by rewardFlow.collectAsState()
    val userReward = currentReward ?: remember { UserRewardStore.getReward(context) }

    val equipFlow = remember { UserEquipStore.equipState }
    val currentEquip by equipFlow.collectAsState()
    val equippedItems = if (currentEquip.isNotEmpty()) currentEquip else remember { UserEquipStore.getEquipped(context) }

    // 어제 캘린더 등급을 불러와 캐릭터의 기본 표정으로 연동
    val initialYesterdayTier = remember {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val stampedDates = DailyStampPrefs.getStampedDates(context)
        CalendarHealthLoader.getCachedMonth(
            viewMonth = YearMonth.from(yesterday),
            today = today,
            stampedDates = stampedDates
        )?.assessments?.get(yesterday)?.tier
    }
    var yesterdayTier by remember { mutableStateOf(initialYesterdayTier) }

    LaunchedEffect(calendarReloadVersion, stampRefreshVersion, daySyncTick) {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val yesterdayMonth = YearMonth.from(yesterday)
        val stampedDates = DailyStampPrefs.getStampedDates(context)
        val cached = CalendarHealthLoader.getCachedMonth(
            viewMonth = yesterdayMonth,
            today = today,
            stampedDates = stampedDates
        )
        val resolvedTier = cached?.assessments?.get(yesterday)?.tier ?: runCatching {
            withContext(Dispatchers.IO) {
                val todayRep = if (yesterdayMonth == YearMonth.from(today)) {
                    runCatching { context.queryUsageReport(1, today) }.getOrNull()
                } else {
                    null
                }
                val nightRep = runCatching { queryNightReport(context, 45) }.getOrNull()
                CalendarHealthLoader.loadMonth(
                    context = context,
                    viewMonth = yesterdayMonth,
                    todayReport = todayRep,
                    existingNightReport = nightRep,
                    stampedDates = stampedDates
                ).assessments[yesterday]?.tier
            }
        }.getOrNull()
        yesterdayTier = resolvedTier
    }

    val yesterdayMood = yesterdayTier.toPuppyMoodOrNull()
    var previewMoodName by rememberSaveable { mutableStateOf<String?>(null) }
    var moodReplayKey by remember { mutableIntStateOf(0) }
    val previewMood = previewMoodName?.let { name -> PuppyMood.entries.firstOrNull { it.name == name } }
    val activeMood = previewMood ?: yesterdayMood ?: PuppyMood.SMILE

    var selectedSlot by remember { mutableStateOf(EquipSlot.HAT) }
    val slotItems = remember(selectedSlot) { EquipCatalog.getItemsForSlot(selectedSlot) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 상단: 캐릭터 피팅룸, 5단계 표정 선택 바, 레벨/경험치 영역
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 캐릭터 + 5개 코디 장착 슬롯 (코디 슬롯 안쪽 안전 영역에 캐릭터를 배치해 점프·자세 변경 시에도 겹침 방지)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val slotSize = 54.dp
                val topEdgePadding = 2.dp
                val sideEdgePadding = 14.dp
                val safeTop = topEdgePadding + slotSize + 6.dp
                val safeSide = sideEdgePadding + slotSize + 6.dp
                val safeWidth = (maxWidth - safeSide * 2f).coerceAtLeast(152.dp)
                val safeHeight = (maxHeight - safeTop - 4.dp).coerceAtLeast(136.dp)
                val midSlotTop = (safeTop + safeHeight * 0.22f - slotSize / 2f).coerceAtLeast(safeTop)
                val botSlotTop = (safeTop + safeHeight * 0.72f - slotSize / 2f).coerceAtLeast(midSlotTop + slotSize + 10.dp)

                // 중앙 안전 무대: 5가지 표정/자세와 4초 모션이 코디 슬롯 영역 밖으로 겹치지 않도록 배치
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = safeTop)
                        .size(width = safeWidth, height = safeHeight),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    EquippedPuppyMascot(
                        equippedItems = equippedItems,
                        mood = activeMood,
                        replayKey = moodReplayKey,
                        showGroundShadow = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                moodReplayKey++
                            }
                    )
                }

                // 1. 모자 (상단 중앙)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topEdgePadding)
                ) {
                    EquipSlotCard(
                        slot = EquipSlot.HAT,
                        equippedItem = equippedItems[EquipSlot.HAT],
                        isSelected = selectedSlot == EquipSlot.HAT,
                        onClick = { selectedSlot = EquipSlot.HAT }
                    )
                }

                // 2. 안경 (상단 좌측)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = sideEdgePadding, top = midSlotTop)
                ) {
                    EquipSlotCard(
                        slot = EquipSlot.GLASSES,
                        equippedItem = equippedItems[EquipSlot.GLASSES],
                        isSelected = selectedSlot == EquipSlot.GLASSES,
                        onClick = { selectedSlot = EquipSlot.GLASSES }
                    )
                }

                // 3. 코와 입 (상단 우측)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = sideEdgePadding, top = midSlotTop)
                ) {
                    EquipSlotCard(
                        slot = EquipSlot.NOSE_MOUTH,
                        equippedItem = equippedItems[EquipSlot.NOSE_MOUTH],
                        isSelected = selectedSlot == EquipSlot.NOSE_MOUTH,
                        onClick = { selectedSlot = EquipSlot.NOSE_MOUTH }
                    )
                }

                // 4. 옷 (하단 좌측)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = sideEdgePadding, top = botSlotTop)
                ) {
                    EquipSlotCard(
                        slot = EquipSlot.CLOTHES,
                        equippedItem = equippedItems[EquipSlot.CLOTHES],
                        isSelected = selectedSlot == EquipSlot.CLOTHES,
                        onClick = { selectedSlot = EquipSlot.CLOTHES }
                    )
                }

                // 5. 신발 (하단 우측)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = sideEdgePadding, top = botSlotTop)
                ) {
                    EquipSlotCard(
                        slot = EquipSlot.SHOES,
                        equippedItem = equippedItems[EquipSlot.SHOES],
                        isSelected = selectedSlot == EquipSlot.SHOES,
                        onClick = { selectedSlot = EquipSlot.SHOES }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // 캐릭터와 경험치 사이: 5가지 표정 선택 바 (어제 캘린더 표정 표시 + 표정별 4초 모션 확인)
            PuppyMoodSelectorBar(
                selectedMood = activeMood,
                yesterdayMood = yesterdayMood,
                onSelectMood = { selected ->
                    previewMoodName = if (selected == yesterdayMood) null else selected.name
                    moodReplayKey++
                }
            )

            Spacer(Modifier.height(8.dp))

            // 캐릭터 레벨 및 경험치 게이지
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = ItvMint,
                border = BorderStroke(1.dp, ItvLine),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ItvGreen
                    ) {
                        Text(
                            text = "Lv.${userReward.currentLevel}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "경험치",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${userReward.currentLevelExp} / ${userReward.nextLevelExpRequired} EXP (${(userReward.progressPercent * 100).toInt()}%)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = ItvGreen
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { userReward.progressPercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = ItvGreen,
                            trackColor = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        // 하단: 한 화면에 약 3줄 정도만 보이고 스크롤하여 내려볼 수 있는 5X8 (40칸) 인벤토리
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(245.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, ItvLine),
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 인벤토리 상단 헤더
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ItvMint
                        ) {
                            Text(
                                text = selectedSlot.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = ItvGreen,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "보관함",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ItvGreen
                        )
                        Text(
                            text = "(${slotItems.size}/40)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 현재 슬롯에 장착된 아이템이 있으면 [착용 해제] 버튼 노출
                        val equippedInThisSlot = equippedItems[selectedSlot]
                        if (equippedInThisSlot != null) {
                            Surface(
                                onClick = {
                                    UserEquipStore.unequip(context, selectedSlot)
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = "착용 해제",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ItvMint.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, ItvLine)
                        ) {
                            Text(
                                text = "${userReward.currentPoints} P 보유",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = ItvGreen,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))

                // 5열 x 8행 = 40칸 스크롤 그리드 (key 지정 및 뷰 레이아웃 최적화로 60fps 부드러운 스크롤 제공)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(
                        count = 40,
                        key = { index -> "equip_slot_${selectedSlot.name}_$index" }
                    ) { index ->
                        val item = slotItems.getOrNull(index)
                        if (item != null) {
                            val isEquipped = equippedItems[selectedSlot]?.id == item.id
                            Surface(
                                onClick = {
                                    UserEquipStore.toggleEquip(context, item)
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isEquipped) ItvMint else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    if (isEquipped) 2.dp else 1.2.dp,
                                    if (isEquipped) ItvGreen else ItvLine
                                ),
                                shadowElevation = if (isEquipped) 3.dp else 0.5.dp,
                                modifier = Modifier.aspectRatio(1f)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Image(
                                            painter = painterResource(item.iconRes),
                                            contentDescription = item.name,
                                            modifier = Modifier.size(28.dp),
                                            contentScale = ContentScale.Fit
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = item.name,
                                            fontSize = 9.sp,
                                            fontWeight = if (isEquipped) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isEquipped) ItvGreen else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }

                                    if (isEquipped) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = ItvGreen,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(2.dp)
                                        ) {
                                            Text(
                                                text = "착용",
                                                fontSize = 7.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // 빈 보관함 칸: 불필요한 터치 리플/인터랙션 생성 방지 및 가벼운 Box 기반 렌더링으로 최적화
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, ItvLine.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
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
private fun PuppyMoodSelectorBar(
    selectedMood: PuppyMood,
    yesterdayMood: PuppyMood?,
    onSelectMood: (PuppyMood) -> Unit
) {
    val isDark = LocalDarkTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PuppyMood.entries.forEach { mood ->
            val tier = mood.toHealthTier()
            val accent = stampInkColor(tier)
            val isSelected = mood == selectedMood
            val isYesterday = mood == yesterdayMood
            val bgColor = when {
                isSelected -> accent.copy(alpha = if (isDark) 0.24f else 0.14f)
                else -> MaterialTheme.colorScheme.surface
            }
            val borderColor = when {
                isSelected -> accent
                isYesterday -> accent.copy(alpha = 0.55f)
                else -> ItvLine
            }

            Surface(
                onClick = { onSelectMood(mood) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = bgColor,
                border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
                shadowElevation = if (isSelected) 2.dp else 0.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        DailyHealthStampBadge(
                            tier = tier,
                            size = 22.dp
                        )
                        Text(
                            text = mood.label,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (isYesterday) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = accent,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 3.dp)
                        ) {
                            Text(
                                text = "어제",
                                fontSize = 7.sp,
                                lineHeight = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EquipSlotCard(
    slot: EquipSlot,
    equippedItem: EquipItem?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(54.dp, 54.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) ItvMint else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (isSelected) 2.dp else 1.2.dp,
            if (isSelected) ItvGreen else (if (equippedItem != null) ItvGreen.copy(alpha = 0.6f) else ItvLine)
        ),
        shadowElevation = if (isSelected) 3.dp else 1.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (equippedItem != null) {
                Image(
                    painter = painterResource(equippedItem.iconRes),
                    contentDescription = equippedItem.name,
                    modifier = Modifier.size(34.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = slot.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SleepTabIcon(color: Color) {
    Canvas(Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.7.dp.toPx()

        // Clean vector crescent moon
        val moonPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.56f, h * 0.16f)
            cubicTo(
                w * 0.25f, h * 0.18f,
                w * 0.14f, h * 0.52f,
                w * 0.34f, h * 0.74f
            )
            cubicTo(
                w * 0.52f, h * 0.90f,
                w * 0.80f, h * 0.82f,
                w * 0.84f, h * 0.62f
            )
            cubicTo(
                w * 0.64f, h * 0.68f,
                w * 0.44f, h * 0.54f,
                w * 0.46f, h * 0.34f
            )
            cubicTo(
                w * 0.47f, h * 0.26f,
                w * 0.51f, h * 0.20f,
                w * 0.56f, h * 0.16f
            )
            close()
        }
        drawPath(
            path = moonPath,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )

        // Two subtle stars inside the crescent opening
        val s1x = w * 0.74f
        val s1y = h * 0.26f
        val s1r = w * 0.08f
        drawLine(color, Offset(s1x - s1r, s1y), Offset(s1x + s1r, s1y), stroke * 0.85f)
        drawLine(color, Offset(s1x, s1y - s1r), Offset(s1x, s1y + s1r), stroke * 0.85f)

        drawCircle(color, radius = stroke * 0.65f, center = Offset(w * 0.66f, h * 0.43f))
    }
}


@Composable
private fun HomeSettingsDrawer(
    open: Boolean,
    onClose: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenAccountSettings: () -> Unit,
    onOpenCoachingSettings: () -> Unit
) {
    val context = LocalContext.current
    val development = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    AnimatedVisibility(visible = open, enter = fadeIn(tween(240)), exit = fadeOut(tween(240))) {
        Surface(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose
        ), color = Color.Black.copy(alpha = 0.18f)) {}
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        val panelWidth = minOf(maxWidth * 0.84f, 340.dp)
        AnimatedVisibility(visible = open,
            enter = slideInHorizontally(tween(280)) { it },
            exit = slideOutHorizontally(tween(240)) { it }) {
            Surface(Modifier.width(panelWidth).fillMaxHeight(),
                shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp),
                color = ItvMint, border = BorderStroke(1.dp, ItvLine), shadowElevation = 4.dp) {
                Column(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) {}.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("설정", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = ItvGreen)
                        TextButton(onClick = onClose) { Text("닫기", color = ItvGreen) }
                    }
                    HorizontalDivider(color = ItvLine)
                    DrawerSettingItem("계정 관리", "로그인 상태 확인, 소셜 연동, 계정 삭제") {
                        onClose()
                        onOpenAccountSettings()
                    }
                    DrawerSettingItem("코칭 설정", "개입 방식, 강도, 캐릭터 말투 설정") {
                        onClose()
                        onOpenCoachingSettings()
                    }
                    val isDarkThemeActive = LocalDarkTheme.current
                    DrawerSettingItem(
                        if (isDarkThemeActive) "화면 테마: 다크 모드 (☀️)" else "화면 테마: 라이트 모드 (🌙)",
                        if (isDarkThemeActive) "현재 어두운 화면 사용 중 · 탭하여 라이트 모드로 전환" else "현재 밝은 화면 사용 중 · 탭하여 다크 모드로 전환"
                    ) {
                        UserThemeStore.toggleDarkMode(context)
                    }
                    if (com.example.itv.infrastructure.jev.JevFeature.available()) DrawerSettingItem("Jev 공동 테스트", "임계값·수동 요청·판단 기록·평가") {
                        onClose()
                        context.startActivity(Intent(context, com.example.itv.presentation.jev.JevTestActivity::class.java))
                    }
                    if (development || com.example.itv.infrastructure.jev.JevFeature.available()) {
                        DrawerSettingItem("초기 화면(온보딩) 테스트", "권한 안내 및 로그인 창 다시 확인 (기록 유지)") {
                            onClose()
                            val intent = Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra(MainActivity.EXTRA_FORCE_ONBOARDING, true)
                            }
                            context.startActivity(intent)
                        }
                    }
                    DrawerSettingItem("목표 설정", "사용시간·취침·휴식 목표") { onClose(); onOpenGoals() }
                    DrawerSettingItem("권한", "수집 동의·접근성 및 필요한 권한 확인") {
                        onClose()
                        context.startActivity(Intent(context, PermissionSettingsActivity::class.java).putExtra("initial_page", 1).putExtra("permissions_only", true))
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerSettingItem(title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, ItvLine)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ItvGreen)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = ItvGreen)
        }
    }
}
