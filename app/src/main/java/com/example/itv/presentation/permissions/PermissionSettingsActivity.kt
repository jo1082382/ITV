package com.example.itv.presentation.permissions

import java.util.Locale



import androidx.activity.result.contract.ActivityResultContracts
import com.example.itv.domain.records.ShortformApp
import com.example.itv.infrastructure.observation.SwipeDynamicsTracker
import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe
import com.example.itv.presentation.coaching.ReelsProbeActivity
import com.example.itv.presentation.coaching.YoutubeProbeActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.lazy.LazyColumn
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.itv.domain.support.FaqCategory
import com.example.itv.domain.support.SupportTab
import com.example.itv.presentation.support.SupportCenterQuickLinkCard
import com.example.itv.presentation.support.SupportCenterScreen
import com.example.itv.ui.theme.ITVTheme

// Consent is intentionally NOT backed up or restored. OS grants are always read live.
class PermissionSettingsActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private var showJournalDelete by mutableStateOf(false)
    private var selectedPage by mutableIntStateOf(0)
    private var showAccessibilityDisclosure by mutableStateOf(false)
    private var showSupportCenter by mutableStateOf(false)
    private val guardConnected get() = settingsService.state.value.data.guardConnected
    private val notifications get() = settingsService.state.value.data.notifications
    private val consent get() = settingsService.state.value.data.consent
    private val usage get() = settingsService.state.value.data.usage
    private val overlay get() = settingsService.state.value.data.overlay
    private val settingsService: com.example.itv.application.permissions.PermissionSettingsService by lazy {
        com.example.itv.composition.permissionSettingsService(this, lifecycleScope)
    }
    private var settingsInitialized = false

    @Composable
    private fun StandalonePermissions() {
        BackHandler(enabled = showSupportCenter) { showSupportCenter = false }
        if (showSupportCenter) {
            SupportCenterScreen(
                onNavigateBack = { showSupportCenter = false },
                initialTab = SupportTab.FAQ,
                initialFaqCategory = FaqCategory.PERMISSIONS,
            )
            return
        }
        val screenState by settingsService.state.collectAsState()
        val notice = screenState.error
        val guardConnected = screenState.data.guardConnected
        val notifications = screenState.data.notifications
        val monitorVisible = screenState.data.monitorVisible
        val consent = screenState.data.consent
        val usage = screenState.data.usage
        val overlay = screenState.data.overlay

        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (screenState.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { finish() }) { Text("‹ 돌아가기") }
                Text("권한", style = MaterialTheme.typography.headlineMedium)
                Text("항목을 누르면 설명과 설정 버튼을 볼 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            colors = CardDefaults.outlinedCardColors(containerColor = com.example.itv.presentation.components.ItvMint)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                                        drawCircle(if (consent) androidx.compose.ui.graphics.Color(0xFF24984D) else androidx.compose.ui.graphics.Color(0xFFD63E3E))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text("사용정보 수집 동의", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Switch(
                                        checked = consent,
                                        enabled = !screenState.loading,
                                        onCheckedChange = { updateCollectionConsent(it) }
                                    )
                                }
                                Text(
                                    "앱 사용시간 확인 및 휴식 코칭을 위해 기기 내에서 숏폼 사용량을 측정하는 것에 동의합니다.\n(화면 본문·입력 문자·화면 이미지는 일체 수집/저장/전송하지 않습니다)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    item { FoldPermission("사용 정보 접근", usage,
                        "전체·앱별 사용시간을 조회하는 권한입니다. 허용되지 않으면 사용량을 조회할 수 없습니다.") { openSystem(false) } }
                    item { FoldPermission("다른 앱 위에 표시", overlay,
                        "다른 앱을 사용하는 중 강아지 개입과 휴식 완료 창을 표시하는 권한입니다.") { openSystem(true) } }
                    item { FoldPermission("접근성 서비스", guardConnected,
                        "릴스·쇼츠 화면과 전환을 판별하고, 메시지나 댓글 작성 중에는 개입을 보류하는 권한입니다. (화면 내용·입력 문자는 일체 읽거나 저장하지 않습니다)") { openAccessibility(standalone = true) } }
                    item { FoldPermission("휴식 제안 알림", notifications,
                        "휴식 제안 알림을 표시합니다. 앱 알림 허용과 제안 알림 채널 상태를 함께 확인합니다.") { openNotifications() } }
                    item { FoldPermission("감시 상태 알림", monitorVisible,
                        "관측 서비스의 실행 상태를 알리는 채널입니다. 이 채널을 숨기는 것만으로 관측이 중지되지는 않습니다.") {
                        try { startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, com.example.itv.composition.CoachingAccess.MONITOR)) }
                        catch (_: Exception) { settingsService.showMessage("기기 설정에서 ITV의 알림 채널을 확인하세요.") }
                    } }
                    item { notice?.let { Text(it) } }
                    item(key = "permissions_support_help") {
                        SupportCenterQuickLinkCard(
                            title = "권한 설정이 잘 안 되시나요?",
                            subtitle = "접근성·오버레이·배터리 절전 해결 FAQ와 1:1 문의",
                            actionLabel = "FAQ 보기",
                            onClick = { showSupportCenter = true },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun FoldPermission(title: String, allowed: Boolean?, explanation: String, action: () -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(
            containerColor = com.example.itv.presentation.components.ItvMint)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                        drawCircle(when(allowed) {
                            true -> androidx.compose.ui.graphics.Color(0xFF24984D)
                            false -> androidx.compose.ui.graphics.Color(0xFFD63E3E)
                            null -> androidx.compose.ui.graphics.Color(0xFF888888)
                        })
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(when(allowed) { true -> "허용"; false -> "미허용"; null -> "확인 중" },
                        style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(if(expanded) "∧" else "∨")
                }
                if (expanded) {
                    Text(explanation, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = action) { Text("설정 열기") }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) selectedPage = intent.getIntExtra("initial_page", 0).coerceIn(0, 5)
        settingsService.initialize()
        settingsInitialized = true
        setContent {
            val screenState by settingsService.state.collectAsState()
            val notice = screenState.error
            val guardConnected = screenState.data.guardConnected
            val notifications = screenState.data.notifications
            val autoEnabled = screenState.data.autoEnabled
            val consent = screenState.data.consent
            val usage = screenState.data.usage
            val overlay = screenState.data.overlay
            val history = screenState.data.history
            ITVTheme {
                if (showAccessibilityDisclosure) {
                    AlertDialog(
                        onDismissRequest = { showAccessibilityDisclosure = false },
                        title = { Text("작성 중 방해하지 않기 · 접근성 안내") },
                        text = {
                            Text(
                                "목적: 작성 중 개입을 보류하고, 릴스·쇼츠 화면과 전환을 구조로 판별하여 사용량 기록 및 전환 시점 개입에 사용합니다.\n\n" +
                                "확인 항목: 전면 앱 식별 정보, 키보드 창 유무, 입력란의 표시 여부와 포커스 등 화면 구조입니다. 기기 안에서 일시적으로 검사합니다.\n\n" +
                                "메시지 본문·입력 문자·화면 이미지는 읽거나 저장·전송하지 않습니다. 다만 Android 접근성 권한 자체는 화면 내용에 접근할 수 있는 강한 권한입니다.\n\n" +
                                "허용은 선택 사항이며 Android 접근성 설정에서 직접 켜거나 끌 수 있습니다. 허용하지 않으면 현재 테스트의 개입은 보류되고, 홈·목표 설정은 계속 사용할 수 있습니다.",
                                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showAccessibilityDisclosure = false
                                launchAccessibilitySystemSettings()
                            }) { Text("확인하고 접근성 설정 열기") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAccessibilityDisclosure = false }) { Text("나중에") }
                        }
                    )
                }
                if (intent.getBooleanExtra("permissions_only", false)) {
                    StandalonePermissions()
                    return@ITVTheme
                }
                if (showJournalDelete) AlertDialog(
                    onDismissRequest = { showJournalDelete = false },
                    title = { Text("개입 결과 기록을 삭제할까요?") },
                    text = { Text("최근 개입 이벤트만 삭제합니다. 미션 누적 통계·사용량 통계·진행 중인 미션은 유지됩니다.") },
                    confirmButton = { TextButton(onClick = {
                        settingsService.clearJournal()
                        showJournalDelete = false
                    }) { Text("삭제") } },
                    dismissButton = { TextButton(onClick = { showJournalDelete = false }) { Text("취소") } }
                )
                LaunchedEffect(selectedPage) {
                    while (true) {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            settingsService.pollDiagnostics(selectedPage == 5)
                        }
                        delay((if (selectedPage == 0 || selectedPage == 3) 1 else if (selectedPage == 5) 5 else 60).seconds)
                    }
                }
                val diagnostics = screenState.diagnostics
                val journalRows = diagnostics.journal
                val missionCounts = diagnostics.missionCounts
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                        if (screenState.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = { finish() }) { Text("‹ 설정으로 돌아가기") }
                        Text("개발용 통합 상태", style = MaterialTheme.typography.headlineSmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { openAccessibility() }, modifier = Modifier.weight(1f)) { Text("접근성 설정") }
                            OutlinedButton(onClick = { selectedPage = 2 }, modifier = Modifier.weight(1f)) { Text("구조 테스트") }
                        }
                        PrimaryScrollableTabRow(selectedTabIndex = selectedPage, edgePadding = 0.dp) {
                            listOf("상태", "권한", "테스트", "스와이프 테스트", "동의", "개입기록").forEachIndexed { index, label ->
                                Tab(selected = selectedPage == index, onClick = { selectedPage = index }, text = { Text(label) })
                            }
                        }
                        // A separate scroll position per tab; navigation remains fixed above it.
                        key(selectedPage) {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                notice?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                                when (selectedPage) {
                                    0 -> {
                                        val sample = diagnostics.sample
                                        val now = diagnostics.now
                                        val fresh = sample.at > 0L && now - sample.at <= 4000L
                                        val active = sample.running && fresh
                                        val connectedFresh = diagnostics.connected && now - diagnostics.checkedAt <= 3000L
                                        val ys = diagnostics.youtubeState
                                        val rs = diagnostics.reelsState
                                        val currentScreen = when {
                                            !connectedFresh -> "판별 불가 · 접근성 연결/관측 확인 필요"
                                            ys == 1 -> "확인된 YouTube 쇼츠"
                                            rs == 1 -> "확인된 Instagram 릴스"
                                            ys == -1 || rs == -1 -> "숏폼 여부 판별 불가"
                                            else -> "확인된 릴스·쇼츠 아님"
                                        }
item {
                                        DevCard("현재 측정 상태") {
                                            Text("코칭 시계: " + if (active) "실행 관측됨" else "중지 또는 최신 관측 없음")
                                            Text("접근성: " + if (connectedFresh) "연결 · 최근 관측 있음" else "미연결 또는 관측 지연")
                                            Text("현재 화면: $currentScreen")
                                            Text("이 화면을 열면 전면 앱은 ITV로 바뀝니다. 이전 숏폼을 계속 보고 있다고 표시하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                                        }

}
item {
                                        DevCard("누적 시계 · 서비스 관측값") {
                                            if (sample.at == 0L) Text("아직 코칭 시계 관측값이 없습니다.")
                                            else {
                                                Text("휴식 이후 누적: ${duration(sample.usedMs)} / ${duration(sample.interventionMs)}")
                                                Text("확인된 휴식: ${duration(sample.restMs)} / ${duration(sample.requiredRestMs)}")
                                                Text("나중에 남은 시간: ${duration(sample.retryRemainingMs)}")
                                                Text("마지막 갱신: ${((now - sample.at).coerceAtLeast(0L) / 1000)}초 전" + if (!active) " · 마지막 값, 현재 진행값 아님" else "")
                                            }
                                        }

}
item {
                                        DevCard("개입 대기 / 보류 이유") {
                                            Text(if (active) sample.decision else "서비스 중지 또는 관측 지연 · 마지막 상태: ${sample.decision}")
                                            Text("작성 보호: " + (diagnostics.guardReason ?: "보호 조건 미감지"))
                                        }

}
item {
                                        DevCard("권한 요약") {
                                            Text("수집 동의 ${permissionText(consent)} · 사용정보 ${permissionText(usage)}")
                                            Text("오버레이 ${permissionText(overlay)} · 제안 알림 ${permissionText(notifications)}")
                                            Text("접근성 연결 ${permissionText(guardConnected)} · 테스트 모드 ${if (autoEnabled) "켜짐" else "꺼짐"}")
                                            TextButton(onClick = { selectedPage = 1 }) { Text("권한 관리 열기") }
                                        }

}
                                    }
                                    1 -> {item {

                                        DevCard("테스트 모드") {
                                            Row {
                                                Text(if (autoEnabled) "켜짐 (빠른 확인 수치)" else "꺼짐 (Jev 개입 설정 사용)", Modifier.weight(1f))
                                                Switch(checked = autoEnabled, enabled = !screenState.loading, onCheckedChange = {
                                                    settingsService.setAutoEnabled(it)
                                                })
                                            }
                                            Text("테스트 모드를 켜면 30초 시청 개입 · 무응답 10초 · 나중에 30초 뒤 재판단 · 10초 내 15초 휴식으로 빠르게 동작합니다. 테스트 모드를 끄고 Jev 개입을 켜면 무응답 45초 · 나중에 5분 · 10초 내 30초 휴식으로 동작합니다.")
                                        }

}
item {
                                        DevCard("접근성 · 작성 보호/숏폼 판별") {
                                            Text(if (guardConnected) "서비스 연결됨" else "서비스 연결 확인 필요")
                                            OutlinedButton(onClick = { openAccessibility() }) { Text("안내 및 접근성 설정") }
                                        }

}
item {
                                        PermissionCard("사용 정보 접근", usage, "전체·앱 사용량 조회", consent) { openSystem(false) }

}
item {
                                        PermissionCard("다른 앱 위에 표시", overlay, "캐릭터 개입 표시", consent) { openSystem(true) }

}
item {
                                        PermissionCard("휴식 제안 알림", notifications, "제안 알림 권한 및 채널", consent) { openNotifications() }

}
item {
                                        DevCard("감시 상태 알림") {
                                            Text("감시 채널을 숨겨도 자동 관측 자체를 끄는 것은 아닙니다.")
                                            OutlinedButton(onClick = {
                                                try { startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                                    .putExtra(Settings.EXTRA_CHANNEL_ID, com.example.itv.composition.CoachingAccess.MONITOR)) }
                                                catch (_: Exception) { settingsService.showMessage("기기 설정에서 ITV의 알림 채널을 확인하세요.") }
                                            }) { Text("감시 알림 채널 설정") }
                                        }

}
item {
                                        OutlinedButton(onClick = { refresh() }) { Text("권한 재확인") }

}
                                    }
                                    2 -> {item {
                                        DevCard("테스트 모드") {
                                            Row {
                                                Text(if (autoEnabled) "켜짐 (30초 개입 · 10초 무응답 · 30초 재판단 · 15초 휴식)" else "꺼짐 (Jev 개입 시 45초 무응답 · 5분 재판단 · 30초 휴식)", Modifier.weight(1f))
                                                Switch(checked = autoEnabled, enabled = !screenState.loading, onCheckedChange = {
                                                    settingsService.setAutoEnabled(it)
                                                })
                                            }
                                        }

                                        AmbientLightBrightnessTestCard()

                                        Text("캐릭터 · 구조 테스트", style = MaterialTheme.typography.titleLarge)
                                        Button(onClick = {
                                            startActivity(Intent(this@PermissionSettingsActivity,
                                                com.example.itv.presentation.character.CompanionPreviewActivity::class.java))
                                        }, modifier = Modifier.fillMaxWidth()) { Text("캐릭터 상호작용 미리보기") }

}
item {
                                        Text("기존 테스트 화면을 그대로 엽니다. 현재 개입 시간·판단 규칙은 바꾸지 않습니다.")

}
item {
                                        Button(onClick = { openProbe(ReelsProbeActivity::class.java) }, modifier = Modifier.fillMaxWidth()) { Text("Instagram 릴스 구조 테스트") }

}
item {
                                        Button(onClick = { openProbe(YoutubeProbeActivity::class.java) }, modifier = Modifier.fillMaxWidth()) { Text("YouTube 쇼츠 구조 테스트") }

}
item {
                                        Text("일반 넘김, 빠른 넘김, 댓글, 앱 이탈·복귀, 광고를 확인하세요. 전환 횟수는 고유 영상 수가 아닙니다.")

}
                                    }
                                    3 -> {
                                        item {
                                            SwipeDynamicsDevSection()
                                        }
                                    }
                                    5 -> {
                                        item {
                                            DevCard("휴식 미션 성공률 · 누적") {
                                                Text("미션 부여: ${missionCounts.assigned}회")
                                                Text("미션 성공: ${missionCounts.success}회")
                                                val rate = if (missionCounts.assigned == 0L) "—" else
                                                    String.format(Locale.KOREA, "%.1f%%", missionCounts.success * 100.0 / missionCounts.assigned)
                                                Text("성공률: $rate", style = MaterialTheme.typography.titleLarge)
                                                Text("실패 ${missionCounts.failed}회 · 관측 중단 ${missionCounts.interrupted}회")
                                                val pending = (missionCounts.assigned - missionCounts.success - missionCounts.failed - missionCounts.interrupted).coerceAtLeast(0L)
                                                Text("진행 중/결과 미확정 ${pending}회")
                                                Text("성공률 = 성공 ÷ 전체 부여 × 100. 진행 중·관측 중단도 부여 횟수에 포함됩니다.")
                                                Text("테스트 모드: 10초 내 화면 끄기 시작, 연속 15초 꺼짐 시 완료 (무응답 10초 · 나중에 30초)")
                                                Text("Jev 개입 모드: 10초 내 화면 끄기 시작, 연속 30초 꺼짐 시 완료 (무응답 45초 · 나중에 5분)")
                                                Text("이 수정본 적용 이후부터 누적합니다. 아래 최근 이벤트 삭제와는 별도로 보존합니다.", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        item {
                                            Text("개입 결과 · 최근 200건", style = MaterialTheme.typography.titleLarge)
                                            Text("표시 성공·선택·무응답·휴식 미션을 시간순으로 확인합니다. 같은 제안 ID로 연결됩니다.")
                                            Text("무응답: 테스트 모드 10초 / Jev 개입 모드 45초 동안 선택 없음. 화면 꺼짐/보호로 먼저 닫히면 표시 중단으로 구분합니다.")
                                        }
                                        item {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(onClick = { settingsService.refreshDiagnostics(true) }, enabled = !screenState.loading) { Text("새로고침") }
                                                TextButton(onClick = { showJournalDelete = true }) { Text("개입 기록 삭제") }
                                            }
                                        }
                                        if (journalRows.isEmpty()) item { Text("아직 기록이 없습니다.") }
                                        items(journalRows.size) { index ->
                                            DevCard("결과 ${journalRows.size - index}") { Text(journalRows[index]) }
                                        }
                                    }
                                    4 -> {item {

                                        DevCard("사용정보 수집 동의 · 안내 v1") {
                                            Text("목적: 앱 사용시간 확인 및 휴식 코칭. 앱 식별자·사용 이벤트 시각·시간, 화면 켜짐·잠금 여부를 기기에서 처리합니다. 현재 서버로 전송하지 않습니다.")
                                            Text("접근성은 화면 구조·키보드·입력란 상태와 숏폼 구조/전환을 확인합니다. 메시지 본문·입력 문자·화면 이미지는 읽거나 저장·전송하지 않습니다.")
                                            Text("짧은 화면 꺼짐은 누적 시계를 일시정지하며, 충분한 휴식 관측 시 초기화합니다. 개입 필요 시 확인된 릴스·쇼츠는 다음 전환을 기다립니다.")
                                            Row {
                                                Text(if (consent) "수집 동의함" else "미동의", Modifier.weight(1f))
                                                Switch(checked = consent, enabled = !screenState.loading, onCheckedChange = { updateCollectionConsent(it) })
                                            }
                                            Text("동의 철회와 기록 삭제는 별개입니다. Android 권한은 자동 해제되지 않으며 시스템 설정에서 해제할 수 있습니다. 자동 보관기간 정리·휴식 미션은 아직 구현하지 않았습니다.")
                                        }

}
item {
                                        Text("설정 내역 · 최근 100건", style = MaterialTheme.typography.titleMedium)

}
item {
                                        Text("권한 내역 시각은 실제 변경 시각이 아니라 앱에서 확인한 시각입니다.")

}
item {
                                        if (history.isEmpty()) Text("기록된 내역이 없습니다.")

}
items(history.size) { index -> Text(history[index], style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private fun openAccessibility(standalone: Boolean = false) {
        if (guardConnected) {
            launchAccessibilitySystemSettings()
            return
        }
        if (!consent) {
            if (standalone) {
                settingsService.showMessage("먼저 수집 안내를 확인하고 동의해 주세요.")
            } else {
                settingsService.showMessage("동의/내역 탭에서 수집 안내를 확인하고 동의해 주세요.")
                selectedPage = 4
            }
            return
        }
        showAccessibilityDisclosure = true
    }

    private fun launchAccessibilitySystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            settingsService.showMessage("기기 설정 → 접근성 → 설치된 앱 → ITV에서 변경하세요.")
        }
    }
    private fun openProbe(target: Class<*>) {
        try { startActivity(Intent(this, target)) }
        catch (_: Exception) { settingsService.showMessage("테스트 화면을 열지 못했습니다. Manifest의 Activity 등록을 확인하세요.") }
    }

    override fun onResume() {
        super.onResume()
        if (settingsInitialized) refresh()
    }
    private fun updateCollectionConsent(value: Boolean) {
        settingsService.updateCollectionConsent(value)
    }
    private fun refresh() { settingsService.refresh() }
    private fun openNotifications() {
        if (!consent && notifications != true) {
            settingsService.showMessage("먼저 수집 안내를 확인하고 동의해 주세요."); return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Exception) { settingsService.showMessage("기기 설정에서 ITV의 알림 허용 및 알림 채널을 확인하세요.") }
        }
    }
    private fun openSystem(isOverlay: Boolean) {
        val granted = if (isOverlay) overlay else usage
        if (!consent && granted != true) {
            settingsService.showMessage("먼저 수집 안내를 확인하고 동의해 주세요.")
            return
        }
        val action = if (isOverlay) Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            else Settings.ACTION_USAGE_ACCESS_SETTINGS
        val packageUri = "package:$packageName".toUri()
        // Some system Settings apps ignore the package URI and still show a list.
        try {
            // Pass the ITV package for both special-access settings screens.
            startActivity(Intent(action, packageUri))
            return
        } catch (_: Exception) {
            // Fall back only when launching the package-specific route fails.
        }
        try {
            startActivity(Intent(action))
            android.widget.Toast.makeText(this,
                "이 기기에서는 목록에서 ITV를 선택해 주세요.", android.widget.Toast.LENGTH_LONG).show()
            return
        } catch (_: Exception) {
            // Last resort: the app information page.
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            android.widget.Toast.makeText(this,
                "권한 전용 화면을 열지 못했습니다. 특별한 앱 접근에서 ITV를 확인해 주세요.",
                android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            settingsService.showMessage("시스템 화면을 열 수 없습니다. 기기 설정에서 ITV의 특별한 앱 접근 권한을 확인하세요.")
        }
    }

}

@Composable
private fun PermissionCard(title: String, granted: Boolean?, detail: String, consent: Boolean, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(when (granted) { true -> "허용됨"; false -> "미허용"; null -> "확인 실패" })
            Text(detail)
            Button(onClick = onOpen, enabled = consent || granted == true) {
                Text(if (granted == true) "시스템 설정에서 변경·해제" else "시스템 허용 화면 열기")
            }
        }
    }
}

private fun permissionText(value: Boolean?): String = when (value) {
    true -> "허용/확인"; false -> "미허용/미확인"; null -> "확인 실패"
}
private fun duration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L)
    return "${seconds / 60}분 ${seconds % 60}초"
}
@Composable
private fun DevCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun signedVal(v: Double): String = if (v > 0.0) "+${"%.1f".format(v)}" else "%.1f".format(v)

@Composable
private fun SwipeDynamicsDevSection() {
    var selectedApp by remember { mutableStateOf(ShortformApp.YOUTUBE) }
    var refreshTick by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1.seconds)
            refreshTick = android.os.SystemClock.elapsedRealtime()
        }
    }

    val snap = remember(selectedApp, refreshTick) { SwipeDynamicsTracker.snapshot(selectedApp) }
    val updatedAt = Instant.ofEpochMilli(snap.updatedWallMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DevCard("스와이프 속도 · 가속도 감별 테스트") {
            Text("유튜브 쇼츠나 인스타그램 릴스를 시청·스와이프한 뒤 돌아오면 분당 스와이프 수(SPM)와 스와이프 가속도 기록을 확인할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedApp == ShortformApp.YOUTUBE,
                    onClick = { selectedApp = ShortformApp.YOUTUBE },
                    label = { Text("YouTube 쇼츠") }
                )
                FilterChip(
                    selected = selectedApp == ShortformApp.INSTAGRAM,
                    onClick = { selectedApp = ShortformApp.INSTAGRAM },
                    label = { Text("Instagram 릴스") }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { refreshTick = android.os.SystemClock.elapsedRealtime() },
                    modifier = Modifier.weight(1f)
                ) { Text("기록 즉시 갱신") }
                OutlinedButton(
                    onClick = {
                        if (selectedApp == ShortformApp.YOUTUBE) YoutubeProbe.reset() else ReelsProbe.reset()
                        SwipeDynamicsTracker.reset(selectedApp)
                        refreshTick = android.os.SystemClock.elapsedRealtime()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("현재 앱 기록 초기화") }
            }
        }

        DevCard("현재 감별 요약 (${if (selectedApp == ShortformApp.YOUTUBE) "YouTube 쇼츠" else "Instagram 릴스"})") {
            Text("• 패턴 감별 결과: ${snap.patternLabel}", style = MaterialTheme.typography.titleMedium)
            Text("• 측정 상태: ${if (snap.active) "실시간 관측 중" else "직전 시청 세션 보존 기록"} (갱신: $updatedAt · 세션 ${"%.1f".format(snap.sessionDurationSeconds)}초)", style = MaterialTheme.typography.bodySmall)
            Text("• 스와이프 집계: 전체 ${snap.totalSwipesInRun}회 → 유효 ${snap.effectiveSwipesInRun}회 (광고·단발 즉시 스킵 제외 ${snap.filteredAdSkipsInRun}회) · 연속 고속 스트릭 ${snap.currentFastStreak}회", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("1) 광고 보정 유효 분당 스와이프 수 (SPM)")
            Text("  • 최근 1분 유효 SPM: ${"%.1f".format(snap.effectiveSpm1m)} 회/분 (원시: ${"%.1f".format(snap.rawSpm1m)} · 직전 1분 유효: ${"%.1f".format(snap.effectiveSpmPrev1m)})")
            Text("  • 최근 30초 단기 유효 환산: ${"%.1f".format(snap.effectiveSpm30s)} 회/분 (원시: ${"%.1f".format(snap.rawSpm30s)} · 직전 30초 유효: ${"%.1f".format(snap.effectiveSpmPrev30s)})")
            Text("  • 현재 세션 평균 유효 SPM: ${"%.1f".format(snap.effectiveSessionSpm)} 회/분 (원시: ${"%.1f".format(snap.rawSessionSpm)})")
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("2) 광고 보정 유효 스와이프 가속도")
            Text("  • 1분 구간 유효 가속도: ${signedVal(snap.effectiveAccel1m)} 회/분² (배율 ${"%.2f".format(snap.effectiveAccelRatio1m)}x · 원시 ${signedVal(snap.rawAccel1m)})")
            Text("  • 30초 단기 유효 가속도: ${signedVal(snap.effectiveAccel30s)} 회/분² (원시 ${signedVal(snap.rawAccel30s)})")
        }

        DevCard("스와이프별 실시간 기록 로그 (최근 ${snap.events.size}건)") {
            if (snap.events.isEmpty()) {
                Text("아직 기록된 스와이프가 없습니다. 쇼츠/릴스를 넘긴 뒤 확인해 보세요.")
            } else {
                snap.events.forEach { ev ->
                    val t = Instant.ofEpochMilli(ev.wallMs).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    val tag = if (ev.filteredAsAdSkip) "[광고/단발 스킵 제외]" else "[유효 반영]"
                    Text("#${ev.index} · $t · $tag · ${ev.patternLabel}", style = MaterialTheme.typography.labelLarge)
                    Text("  - 유효 SPM: 1분 ${"%.1f".format(ev.effectiveSpm1m)} (원시 ${"%.1f".format(ev.rawSpm1m)}) · 30초환산 ${"%.1f".format(ev.effectiveSpm30s)} (원시 ${"%.1f".format(ev.rawSpm30s)}) · 스트릭 ${ev.fastStreakAtSwipe}회", style = MaterialTheme.typography.bodySmall)
                    Text("  - 유효 가속도: 1분 ${signedVal(ev.effectiveAccel1m)}회/분² (${"%.2f".format(ev.effectiveAccelRatio1m)}x) · 30초단기 ${signedVal(ev.effectiveAccel30s)}회/분²", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun AmbientLightBrightnessTestCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tracker = remember(context) { com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(context) }
    val snapshot by tracker.snapshot.collectAsState()

    DisposableEffect(tracker) {
        tracker.start()
        onDispose { tracker.stop() }
    }

    DevCard("조도 센서 · 근접 센서 · 화면 밝기 측정") {
        Text(
            text = "• 상태 감별: ${snapshot.stateLabel}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = when {
                snapshot.isOccluded -> MaterialTheme.colorScheme.error
                snapshot.userEnvironmentState == com.example.itv.infrastructure.observation.UserEnvironmentState.BEDTIME_DELAY_WATCH -> MaterialTheme.colorScheme.error
                snapshot.userEnvironmentState == com.example.itv.infrastructure.observation.UserEnvironmentState.PRE_SLEEP_REST -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        Text(
            text = snapshot.explanation,
            style = MaterialTheme.typography.bodyMedium
        )
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        val bedtimeDisplay = if (snapshot.estimatedBedtimeHour != null && snapshot.estimatedBedtimeMinute != null) {
            val h = snapshot.estimatedBedtimeHour!!
            val m = snapshot.estimatedBedtimeMinute!!
            val phaseLabel = when (snapshot.bedtimePhase) {
                com.example.itv.infrastructure.observation.BedtimePhase.ACTIVE_HOURS -> "일상 활동 시간대 (취침과 거리 멂)"
                com.example.itv.infrastructure.observation.BedtimePhase.APPROACHING -> "취침 임박 시간대 (2시간 이내)"
                com.example.itv.infrastructure.observation.BedtimePhase.PAST_BEDTIME -> "취침 시각 경과 (심야 수면 시간대)"
                else -> "확인 대기"
            }
            "%02d:%02d (%s · 출처: %s)".format(h, m, phaseLabel, snapshot.estimatedBedtimeSource)
        } else {
            "취침 기록 분석 대기 중"
        }
        Text(
            text = "• 평소 추정 취침 시각: $bedtimeDisplay",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = if (snapshot.bedtimePhase == com.example.itv.infrastructure.observation.BedtimePhase.PAST_BEDTIME ||
                snapshot.bedtimePhase == com.example.itv.infrastructure.observation.BedtimePhase.APPROACHING)
                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(
            text = "• 센서 절전 상태: ${if (snapshot.isSampling) "측정 중 (샘플링 윈도우 수신 중...)" else "절전 대기 중 (센서 완전 해제됨)"}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = if (snapshot.isSampling) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "• 최근 감지 계기: ${snapshot.lastTriggerReason}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(
            text = "• 조도 센서(주변 밝기): ${snapshot.lux?.let { "%.1f lux".format(it) } ?: "센서 대기/없음"}",
            style = MaterialTheme.typography.bodyMedium
        )
        val proximityState = when {
            snapshot.proximityDistanceCm == null -> "센서 대기/없음"
            snapshot.isOccluded -> "${snapshot.proximityDistanceCm} cm [손/물체 가림 의심!]"
            snapshot.isNear -> "${snapshot.proximityDistanceCm} cm [상단 근접 상태]"
            else -> "${snapshot.proximityDistanceCm} cm [원거리 / 가림 없음]"
        }
        Text(
            text = "• 근접 센서(상단 거리): $proximityState",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (snapshot.isOccluded) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
            color = if (snapshot.isOccluded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        val brightnessPct = snapshot.screenBrightness?.let { (it / 255.0 * 100).toInt() }
        Text(
            text = "• 화면 밝기: ${snapshot.screenBrightness?.let { "$it / 255 ($brightnessPct%)" } ?: "조회 불가"}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "• 야간 화면 모드(다크 모드): ${if (snapshot.nightModeActive) "적용 중 (다크 모드 ON)" else "미적용 (라이트 모드)"}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "※ 배터리 절전을 위해 상시 스트리밍하지 않으며, 화면 켜짐 · 앱 전환 · 밝기 조절 · 5분 주기 시에만 약 3초간 센서를 켜서 샘플링 후 자동 해제(절전)합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "※ 스마트폰 화면을 끄면 센서는 즉시 완전 해제되어 배터리를 전혀 소모하지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "※ 안드로이드 기기 근접 센서는 스마트폰 상단(수화부)을 손으로 덮으면 0.0cm(근접), 손을 떼면 최대치(5.0cm)로 동작하는 2단계 센서입니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { tracker.refresh() },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            Text("센서 및 화면 밝기 새로고침 (3초간 샘플링)")
        }
    }
}


