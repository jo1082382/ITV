package com.example.itv.presentation.navigation
import com.example.itv.domain.goals.HomeGoalRow


import com.example.itv.presentation.usage.UsageRecordsContent
import androidx.compose.foundation.lazy.LazyColumn
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.flow.Flow

private data class MainTab(val id: String, val label: String, val mark: String)
private val tabs = listOf(
    MainTab("home", "홈", "⌂"),
    MainTab("records", "기록", "▤"),
    MainTab("character", "캐릭터", "♧")
)

@Composable
fun ItvMainScreen(
    goals: Flow<List<HomeGoalRow>>,
    selectedTab: String,
    onSelectTab: (String) -> Unit,
    onOpenGoals: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTab = selectedTab.takeIf { id -> tabs.any { it.id == id } } ?: "home"
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    var showCoaching by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = !settingsOpen && (activeTab != "home" || showCoaching)) {
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
    HomeSettingsDrawer(settingsOpen, { settingsOpen = false }, onOpenGoals)
    }
}

@Composable
private fun TabPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
}

@Composable
private fun PageTitle(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium,
        color = ItvGreen, fontWeight = FontWeight.Bold)
    Text(description, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, ItvLine),
        colors = CardDefaults.cardColors(containerColor = ItvMint)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RecordsScreen() {
    UsageRecordsContent()
}

@Composable
private fun CharacterScreen() {
    PageTitle("캐릭터", "나의 디지털 쉼표를 함께 만들어가요.")
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = ItvMint), border = BorderStroke(1.dp, ItvLine)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PuppyMascot(Modifier.size(220.dp))
            Text("강아지 친구", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("나와 함께 쉬어가는 강아지 친구")
        }
    }
    InfoCard("성장·꾸미기 연동 준비 중", "현재 강아지 캐릭터는 기본 모습입니다. 레벨, 보상, 꾸미기 저장 기능은 아직 연결되지 않았습니다.")
}

@Composable
private fun SettingsScreen(onOpenGoals: () -> Unit, onOpenCoaching: () -> Unit) {
    val context = LocalContext.current
    PageTitle("설정", "내 목표와 앱 상태를 확인해요.")
    InfoCard("목표 관리", "하루 휴대폰 사용시간, 취침시간, 사용 후 휴식 목표를 수정할 수 있습니다.")
    Button(onClick = onOpenGoals, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp)) { Text("목표 설정 열기") }
    InfoCard("코칭 상태", "관측 기능 연결 여부와 코칭 실행 상태를 별도 화면에서 확인합니다.")
    OutlinedButton(onClick = onOpenCoaching, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp)) { Text("코칭 상태 열기") }
    InfoCard("계정", "현재 시험용 게스트(demo-guest)로 사용 중입니다. 로그인과 서버 백업은 아직 연결되지 않았습니다.")
    InfoCard("수집 동의·권한", "수집 동의, 실제 OS 권한 상태와 이 기기의 설정 내역을 확인합니다.")
    OutlinedButton(onClick = { context.startActivity(Intent(context, PermissionSettingsActivity::class.java)) },
        modifier = Modifier.fillMaxWidth()) { Text("수집 동의·권한 및 내역 열기") }
}

@Composable
private fun CoachingStatusScreen(onBack: () -> Unit) {
    TextButton(onClick = onBack) { Text("‹ 설정으로 돌아가기") }
    PageTitle("코칭 상태", "관측과 코칭의 현재 구현 상태를 안내합니다.")
    InfoCard("관측 상태 · 미연결", "실제 사용량 관측 기능은 아직 연결되지 않았습니다. 사용량 표의 미측정은 0분 사용을 의미하지 않습니다.")
    InfoCard("코칭 실행 · 미연결", "현재 코칭을 판단하거나 표시하지 않습니다. 목표 활성화만으로 코칭이 시작되지는 않습니다.")
    InfoCard("동의·OS 권한", "설정의 수집 동의·권한 및 내역에서 실제 상태를 확인하세요. 권한을 허용해도 현재 수집·코칭은 시작되지 않습니다.")
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

@Composable
private fun LazySettingsPage(onOpenGoals: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun openStatus() { context.startActivity(Intent(context, PermissionSettingsActivity::class.java)) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("설정", "내 목표와 앱 상태를 확인해요.") }
        item { InfoCard("개발용 통합 상태", "측정 상태·누적 시계·개입 대기 이유와 권한을 확인합니다.") }
        item { Button(onClick = { openStatus() }, modifier = Modifier.fillMaxWidth()) { Text("통합 상태 · 권한 · 구조 테스트") } }
        item { InfoCard("목표 관리", "하루 사용시간, 취침시간, 사용 후 휴식 목표를 수정할 수 있습니다.") }
        item { OutlinedButton(onClick = onOpenGoals, modifier = Modifier.fillMaxWidth()) { Text("목표 설정 열기") } }
        item { InfoCard("계정", "현재 시험용 게스트(demo-guest)로 사용 중입니다. 로그인과 서버 백업은 아직 연결되지 않았습니다.") }
        item { InfoCard("수집 동의·권한", "통합 상태 화면의 동의/내역 탭에서 수집 안내와 설정 내역을 확인할 수 있습니다.") }
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
private fun HomeSettingsDrawer(open: Boolean, onClose: () -> Unit, onOpenGoals: () -> Unit) {
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
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}
                ).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("설정", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = ItvGreen)
                        TextButton(onClick = onClose) { Text("닫기", color = ItvGreen) }
                    }
                    HorizontalDivider(color = ItvLine)
                    if (development) DrawerSettingItem("개발용 통합 상태", "측정·시계·개입·미션 기록") {
                        onClose()
                        context.startActivity(Intent(context, PermissionSettingsActivity::class.java).putExtra("initial_page", 0))
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
