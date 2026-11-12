package com.example.itv.presentation.navigation
import com.example.itv.domain.goals.HomeGoalRow


import com.example.itv.presentation.usage.UsageRecordsContent
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.example.itv.MainActivity
import com.example.itv.presentation.auth.AccountSettingsScreen
import com.example.itv.presentation.coaching.CoachingSettingsScreen
import com.example.itv.composition.AuthComposition
import com.example.itv.presentation.permissions.PermissionSettingsActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.example.itv.presentation.home.HomeScreen
import com.example.itv.presentation.components.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

private data class MainTab(val id: String, val label: String, val mark: String)
private val tabs = listOf(
    MainTab("home", "홈", "⌂"),
    MainTab("records", "기록", "▤"),
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
    // Outer MainActivity Scaffold already handles system bar insets.
    Box(modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (activeTab == "home") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
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
                                    "records" -> RecordsTabIcon(if (selected) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant)
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
            "home" -> CenteredHomePage(Modifier.padding(padding))
            "records" -> Box(Modifier.fillMaxSize().padding(padding)) { RecordsScreen() }
            "character" -> TabPage(Modifier.padding(padding)) { CharacterScreen() }


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
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_FORCE_ONBOARDING, true)
                }
                context.startActivity(intent)
            },
            onSignedOut = {
                showAccountSettings = false
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_FORCE_ONBOARDING, true)
                }
                context.startActivity(intent)
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
    }
}

@Composable
private fun TabPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
}

@Composable
private fun RecordsScreen() {
    UsageRecordsContent()
}

@Composable
private fun CharacterScreen() {
    Text("캐릭터", style = MaterialTheme.typography.headlineMedium,
        color = ItvGreen, fontWeight = FontWeight.Bold)
    Text("나의 디지털 쉼표를 함께 만들어가요.", style = MaterialTheme.typography.bodyMedium)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = ItvMint), border = BorderStroke(1.dp, ItvLine)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PuppyMascot(Modifier.size(220.dp))
            Text("강아지 친구", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("나와 함께 쉬어가는 강아지 친구")
        }
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, ItvLine),
        colors = CardDefaults.cardColors(containerColor = ItvMint)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("성장·꾸미기 연동 준비 중", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("현재 강아지 캐릭터는 기본 모습입니다. 레벨, 보상, 꾸미기 저장 기능은 아직 연결되지 않았습니다.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RecordsTabIcon(color: Color) {
    Canvas(Modifier.size(28.dp)) {
        val stroke = 1.6.dp.toPx()
        drawLine(color, Offset(size.width * .12f, size.height * .12f),
            Offset(size.width * .12f, size.height * .88f), stroke)
        drawLine(color, Offset(size.width * .12f, size.height * .88f),
            Offset(size.width * .94f, size.height * .88f), stroke)
        repeat(3) { index ->
            val barHeight = when (index) { 0 -> .30f; 1 -> .52f; else -> .68f }
            drawRoundRect(color,
                topLeft = Offset(size.width * (.25f + index * .23f), size.height * (.82f - barHeight)),
                size = Size(size.width * .14f, size.height * barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}

// The home gets a finite viewport; scroll only when content is taller than it.
@Composable
private fun CenteredHomePage(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .heightIn(min = viewportHeight).padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            HomeScreen()
        }
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
                    DrawerSettingItem("권한", "수집 동의와 필요한 권한 확인") {
                        onClose()
                        context.startActivity(Intent(context, PermissionSettingsActivity::class.java).putExtra("initial_page", 1).putExtra("permissions_only", true))
                    }
                    DrawerSettingItem("접근성", "Android 접근성 설정 열기") {
                        onClose()
                        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            .onFailure { android.widget.Toast.makeText(context, "접근성 설정을 열지 못했습니다.", android.widget.Toast.LENGTH_SHORT).show() }
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
