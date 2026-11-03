package com.example.itv.presentation.permissions

import java.util.Locale



import androidx.activity.result.contract.ActivityResultContracts
import com.example.itv.presentation.coaching.ReelsProbeActivity
import com.example.itv.presentation.coaching.YoutubeProbeActivity
import kotlinx.coroutines.delay
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.lazy.LazyColumn
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.itv.ui.theme.ITVTheme

// Consent is intentionally NOT backed up or restored. OS grants are always read live.
class PermissionSettingsActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private var showJournalDelete by mutableStateOf(false)
    private var selectedPage by mutableStateOf(0)
    private var showAccessibilityDisclosure by mutableStateOf(false)
    private val guardConnected get() = settingsService.state.value.data.guardConnected
    private val notifications get() = settingsService.state.value.data.notifications
    private val monitorVisible get() = settingsService.state.value.data.monitorVisible
    private val autoEnabled get() = settingsService.state.value.data.autoEnabled
    private val consent get() = settingsService.state.value.data.consent
    private val usage get() = settingsService.state.value.data.usage
    private val overlay get() = settingsService.state.value.data.overlay
    private val history get() = settingsService.state.value.data.history
    private val settingsService: com.example.itv.application.permissions.PermissionSettingsService by lazy {
        com.example.itv.composition.permissionSettingsService(this, lifecycleScope)
    }
    private var settingsInitialized = false

    @androidx.compose.runtime.Composable
    private fun StandalonePermissions() {
        val screenState by settingsService.state.collectAsState()
        val notice = screenState.error
        val guardConnected = screenState.data.guardConnected
        val notifications = screenState.data.notifications
        val monitorVisible = screenState.data.monitorVisible
        val autoEnabled = screenState.data.autoEnabled
        val consent = screenState.data.consent
        val usage = screenState.data.usage
        val overlay = screenState.data.overlay
        val history = screenState.data.history

        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (screenState.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { finish() }) { Text("‹ 돌아가기") }
                Text("권한", style = MaterialTheme.typography.headlineMedium)
                Text("항목을 누르면 설명과 설정 버튼을 볼 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { FoldPermission("사용 정보 접근", usage,
                        "전체·앱별 사용시간을 조회하는 권한입니다. 허용되지 않으면 사용량을 조회할 수 없습니다.") { openSystem(false) } }
                    item { FoldPermission("다른 앱 위에 표시", overlay,
                        "다른 앱을 사용하는 중 강아지 개입과 휴식 완료 창을 표시하는 권한입니다.") { openSystem(true) } }
                    item { FoldPermission("휴식 제안 알림", notifications,
                        "휴식 제안 알림을 표시합니다. 앱 알림 허용과 제안 알림 채널 상태를 함께 확인합니다.") { openNotifications() } }
                    item { FoldPermission("감시 상태 알림", monitorVisible,
                        "관측 서비스의 실행 상태를 알리는 채널입니다. 이 채널을 숨기는 것만으로 관측이 중지되지는 않습니다.") {
                        try { startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, com.example.itv.composition.CoachingAccess.MONITOR)) }
                        catch (_: Exception) { settingsService.showMessage("기기 설정에서 ITV의 알림 채널을 확인하세요.") }
                    } }
                    item { if (!consent) Text("수집 동의가 없습니다. 시스템 권한 허용과 수집 동의는 별개입니다.", style = MaterialTheme.typography.bodySmall) }
                    item { notice?.let { Text(it) } }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
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
        if (savedInstanceState == null) selectedPage = intent.getIntExtra("initial_page", 0).coerceIn(0, 4)
        settingsService.initialize()
        settingsInitialized = true
        setContent {
            val screenState by settingsService.state.collectAsState()
            val notice = screenState.error
            val guardConnected = screenState.data.guardConnected
            val notifications = screenState.data.notifications
            val monitorVisible = screenState.data.monitorVisible
            val autoEnabled = screenState.data.autoEnabled
            val consent = screenState.data.consent
            val usage = screenState.data.usage
            val overlay = screenState.data.overlay
            val history = screenState.data.history
            ITVTheme {
                if (intent.getBooleanExtra("permissions_only", false)) {
                    StandalonePermissions()
                    return@ITVTheme
                }
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
                                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                catch (_: Exception) { settingsService.showMessage("기기 설정 → 접근성 → 설치된 앱 → ITV 작성 보호 테스트에서 변경하세요.") }
                            }) { Text("확인하고 접근성 설정 열기") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAccessibilityDisclosure = false }) { Text("나중에") }
                        }
                    )
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
                            settingsService.pollDiagnostics(selectedPage == 4)
                                            }
                        delay(if (selectedPage == 0) 1000L else if (selectedPage == 4) 5000L else 60_000L)
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
                        ScrollableTabRow(selectedTabIndex = selectedPage, edgePadding = 0.dp) {
                            listOf("상태", "권한", "테스트", "동의", "개입기록").forEachIndexed { index, label ->
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
                                            Text("접근성 연결 ${permissionText(guardConnected)} · 자동 개입 ${if (autoEnabled) "켜짐" else "꺼짐"}")
                                            TextButton(onClick = { selectedPage = 1 }) { Text("권한 관리 열기") }
                                        }

}
                                    }
                                    1 -> {item {

                                        DevCard("강아지 자동 개입 · 개발용") {
                                            Row {
                                                Text(if (autoEnabled) "켜짐" else "꺼짐", Modifier.weight(1f))
                                                Switch(checked = autoEnabled, enabled = !screenState.loading, onCheckedChange = {
                                                    settingsService.setAutoEnabled(it)
                                                })
                                            }
                                            Text("이 기기의 테스트 코칭 실행 스위치입니다. Room의 사용자별 정식 코칭 설정과는 별개입니다. 기존 누적 사용·휴식·전환 대기 규칙을 유지하며, 끄기는 모든 기록의 일괄 중지를 뜻하지 않습니다.")
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

                                        Text("구조 테스트", style = MaterialTheme.typography.titleLarge)

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
                                    4 -> {
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
                                                Text("15초 안에 화면을 끄기 시작하지 않으면 실패. 시작 후 연속 30초 꺼짐이 완료 조건입니다.")
                                                Text("이 수정본 적용 이후부터 누적합니다. 아래 최근 이벤트 삭제와는 별도로 보존합니다.", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        item {
                                            Text("개입 결과 · 최근 200건", style = MaterialTheme.typography.titleLarge)
                                            Text("표시 성공·선택·무응답·휴식 미션을 시간순으로 확인합니다. 같은 제안 ID로 연결됩니다.")
                                            Text("무응답: 표시 후 10초 동안 선택 없음. 화면 꺼짐/보호로 먼저 닫히면 표시 중단으로 구분합니다.")
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
                                    3 -> {item {

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
    private fun openAccessibility() {
        if (!consent && !guardConnected) {
            settingsService.showMessage("동의/내역 탭에서 수집 안내를 확인하고 동의해 주세요.")
            selectedPage = 3
        } else showAccessibilityDisclosure = true
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
        val packageUri = Uri.parse("package:$packageName")
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
