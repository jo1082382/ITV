package com.example.itv.presentation.jev

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.LocalContext
import com.example.itv.MainActivity
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.domain.coaching.RestMissionCatalog
import com.example.itv.domain.coaching.UserPosture
import com.example.itv.domain.jev.*
import com.example.itv.infrastructure.jev.*
import com.example.itv.infrastructure.observation.LightAndBrightnessTracker
import com.example.itv.infrastructure.observation.SwipeDynamicsTracker
import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe
import com.example.itv.infrastructure.storage.shortform.ShortformDailyStore
import com.example.itv.infrastructure.storage.shortform.ReelsUsageStore
import com.example.itv.infrastructure.coaching.InterventionJournal
import com.example.itv.infrastructure.coaching.MissionStats
import com.example.itv.presentation.character.CompanionPreviewActivity
import com.example.itv.presentation.coaching.CoachingMonitorService
import com.example.itv.presentation.components.*
import com.example.itv.ui.theme.ITVTheme
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

class JevTestActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(!JevFeature.available()) { finish();return }
        enableEdgeToEdge()
        val vm=ViewModelProvider(this)[JevTestViewModel::class.java]
        setContent {
            ITVTheme { ItvVisualTheme {
                Scaffold { padding -> JevTestScreen(vm,{finish()},Modifier.padding(padding)) }
            } }
        }
    }
}
@Composable
private fun JevTestScreen(vm: JevTestViewModel,onBack: ()->Unit,modifier: Modifier) {
    val s by vm.store.settings.collectAsState()
    val initialized by vm.store.initialized.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val notice by vm.store.notice.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    val holder=rememberSaveableStateHolder()
    Column(modifier.fillMaxSize()) {
        TextButton(onClick=onBack) { Text("‹ 설정으로 돌아가기") }
        Text("Jev 공동 테스트",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.headlineSmall,
            fontWeight=FontWeight.Bold,color=ItvGreen)
        PrimaryScrollableTabRow(selectedTabIndex=page,edgePadding=12.dp) {
            listOf("연결","판단 설정","개입 기록","실시간 측정 테스트","파지 각도·목 부담 감지").forEachIndexed { i,title ->
                Tab(selected=page==i,onClick={page=i},text={Text(title)})
            }
        }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(!initialized) { CircularProgressIndicator(Modifier.padding(24.dp));return@Column }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            message?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }
            notice?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            holder.SaveableStateProvider(page) {
                when(page) {
                    0 -> ConnectionPage(vm,s,busy)
                    1 -> SettingsPage(vm,s,busy)
                    2 -> InterventionHistoryPage()
                    3 -> ObservationTestPage(vm,busy)
                    else -> PostureTestPage(busy)
                }
            }
        }
    }
}
@Composable
private fun TestSection(title: String,content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,ItvLine),
        colors=CardDefaults.cardColors(containerColor=ItvMint)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            content()
        }
    }
}
@Composable
private fun CompactCheckGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, ItvLine)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ItvGreen)
            content()
        }
    }
}
@Composable
private fun CompactCheckRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (highlight) ItvGreen else MaterialTheme.colorScheme.onSurface
        )
    }
}
@Composable
private fun Toggle(label: String,value: Boolean,onChange: (Boolean)->Unit,enabled: Boolean=true) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f))
        Switch(checked=value,onCheckedChange=onChange,enabled=enabled)
    }
}
@Composable
private fun Numeric(label: String,value: String,onChange: (String)->Unit,enabled: Boolean=true,decimal: Boolean=false) {
    OutlinedTextField(value=value,onValueChange={ if(it.length<=10 && it.all { c-> c.isDigit() || (decimal && c=='.') })onChange(it) },
        label={Text(label)},enabled=enabled,singleLine=true,
        keyboardOptions=KeyboardOptions(keyboardType=if(decimal)KeyboardType.Decimal else KeyboardType.Number),
        modifier=Modifier.fillMaxWidth())
}
@Composable
private fun ConnectionPage(vm: JevTestViewModel,s: JevSettings,busy: Boolean) {
    val configured by vm.store.tokenConfigured.collectAsState()
    var tester by rememberSaveable(s.revision) { mutableStateOf(s.testerId) }
    var consent by rememberSaveable(s.revision) { mutableStateOf(s.collectionConsent) }
    var token by remember { mutableStateOf("") } // never put a secret into savedInstanceState
    TestSection("참가자와 데이터 전송 동의") {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            (1..7).forEach { n -> val id="T0$n";FilterChip(selected=tester==id,onClick={tester=id},label={Text(id)},enabled=!busy) }
        }
        Text("T01은 본인, T02~T07은 팀원입니다. 참가자 ID는 인증 수단이 아닙니다.",style=MaterialTheme.typography.bodySmall)
        Text("이 시험은 참가자 ID, 대상 앱 식별 정보, 시청·재진입·취침 목표 요약, 당시 설정, Jev 판단과 모델·토큰 수, 실제 개입·반응·0~10점 평가를 Cloudflare와 TypeSafe에 전송합니다. 화면 이미지·메시지·영상 원문은 보내지 않습니다.",style=MaterialTheme.typography.bodySmall)
        Text("보관기간은 아직 확정되지 않았으며 자동 삭제는 적용하지 않았습니다. 팀이 참가자에게 보관·삭제 정책을 공지하고 확인받기 전에는 실제 개인정보 수집 시험에 참여하지 마세요. 삭제 요청은 테스트 담당자에게 참가자 ID와 기록 ID로 전달합니다. 동의 철회는 새 수집·전송을 중단하며 기존 서버 기록의 즉시 삭제와는 구분됩니다.",style=MaterialTheme.typography.bodySmall)
        Toggle("안내를 확인하고 테스트 데이터 전송에 동의",consent,{consent=it},!busy)
        Button(onClick={vm.save(s.copy(testerId=tester,collectionConsent=consent))},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("참가자·동의 저장") }
    }
    TestSection("테스트 토큰") {
        Text(if(configured)"토큰 저장됨 · 원문은 표시하지 않습니다." else "저장된 토큰 없음")
        OutlinedTextField(value=token,onValueChange={token=it},label={Text("ITV_TEST_TOKEN 입력")},
            visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
        Button(onClick={vm.saveToken(token){token=""}},enabled=!busy && token.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("토큰 저장·교체") }
        OutlinedButton(onClick={vm.deleteToken()},enabled=!busy && configured,modifier=Modifier.fillMaxWidth()) { Text("저장 토큰 삭제") }
        Text("JEV_API_KEY는 앱에 입력하지 않습니다. 공통 테스트 토큰은 개발 테스트 전용입니다.",style=MaterialTheme.typography.bodySmall)
    }
    TestSection("Worker 연결") {
        Text(JevRepository.BASE,style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={vm.checkConnection()},enabled=!busy && configured && s.collectionConsent,modifier=Modifier.fillMaxWidth()) { Text("인증·D1 연결 확인 (Jev 호출 없음)") }
    }
}
@Composable
private fun SettingsPage(vm: JevTestViewModel,s: JevSettings,busy: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var testMode by remember { mutableStateOf(com.example.itv.presentation.coaching.CoachingAutoStart.testModeEnabled(context)) }
    var enabled by rememberSaveable(s.revision){mutableStateOf(s.enabled)}
    var actual by rememberSaveable(s.revision){mutableStateOf(s.actualIntervention)}
    var ig by rememberSaveable(s.revision){mutableStateOf(s.instagram)}
    var yt by rememberSaveable(s.revision){mutableStateOf(s.youtube)}
    var continuousOn by rememberSaveable(s.revision){mutableStateOf(s.continuousEnabled)}
    var continuous by rememberSaveable(s.revision){mutableStateOf(s.continuousSeconds.toString())}
    var returnsOn by rememberSaveable(s.revision){mutableStateOf(s.returnsEnabled)}
    var returns by rememberSaveable(s.revision){mutableStateOf(s.returnsThreshold.toString())}
    var bedtimeOn by rememberSaveable(s.revision){mutableStateOf(s.bedtimeEnabled)}
    var bedtimeDelay by rememberSaveable(s.revision){mutableStateOf(s.bedtimeDelayMinutes.toString())}
    var bedtimeWindow by rememberSaveable(s.revision){mutableStateOf(s.bedtimeWindowMinutes.toString())}
    var probability by rememberSaveable(s.revision){mutableStateOf((s.probabilityThreshold*100).toString())}
    var personalizedOn by rememberSaveable(s.revision){mutableStateOf(s.personalizedEnabled)}
    var ttl by rememberSaveable(s.revision){mutableStateOf(s.resultTtlSeconds.toString())}
    var error by remember {mutableStateOf<String?>(null)}
    TestSection("실행 모드") {
        Toggle("테스트 모드 (30초 시청 개입 · 10초 무응답 · 30초 재판단 · 10초 내 15초 휴식)",testMode,{
            testMode=it
            com.example.itv.presentation.coaching.CoachingAutoStart.setEnabled(context,it)
        },!busy)
        Toggle("Jev 자동 관측·판단 사용",enabled,{enabled=it},!busy)
        Toggle("확률 통과 시 실제 개입 (끄면 판단만 기록)",actual,{actual=it},!busy)
        Text("테스트 모드를 켜면 빠른 확인용 고정값으로 개입합니다. 테스트 모드를 끄고 Jev 개입을 켜면 Jev 기준(무응답 45초 · 나중에 5분 · 10초 내 30초 휴식)으로 동작합니다.",style=MaterialTheme.typography.bodySmall)
        Toggle("Instagram",ig,{ig=it},!busy);Toggle("YouTube",yt,{yt=it},!busy)
    }
    TestSection("1단계 · Jev 호출 후보") {
        Text("켜 둔 조건 중 하나 이상 충족하면 후보입니다. 기준은 ‘이상(≥)’이며 모든 조건을 끄면 자동 요청하지 않습니다.",style=MaterialTheme.typography.bodySmall)
        Toggle("연속 숏폼 시청 조건",continuousOn,{continuousOn=it},!busy)
        Numeric("연속 시청 기준 (초)",continuous,{continuous=it},!busy)
        Toggle("최근 10분 대상 앱 재진입 조건",returnsOn,{returnsOn=it},!busy)
        Numeric("재진입 기준 (회)",returns,{returns=it},!busy)
        Toggle("취침 목표 이후 시청 조건",bedtimeOn,{bedtimeOn=it},!busy)
        Numeric("취침 목표 후 지연 (분)",bedtimeDelay,{bedtimeDelay=it},!busy)
        Numeric("취침 기준 적용 시간 (분)",bedtimeWindow,{bedtimeWindow=it},!busy)
        Text("취침 목표 시각은 기존 목표 설정을 사용합니다. 적용 시간 안에서만 비교하여 자정 전후를 연결하며, 목표가 없으면 이 조건을 제외합니다.",style=MaterialTheme.typography.bodySmall)
    }
    TestSection("2단계 · 실제 개입 및 개인화·스와이프 보정") {
        Numeric("기본 개입 확률 기준 (0~100%)",probability,{probability=it},!busy,true)
        Toggle("개인화 비율 및 스와이프 과열 동적 보정 사용",personalizedOn,{personalizedOn=it},!busy)
        Text("켜 두면 개인 평균 세션 대비 과몰입 비율(±10%p), 전체 폰 사용 중 숏폼 비중(+5~-10%p), 연속 고속 스와이프 과열도(최대 -10%p)를 선형 증감 곡선으로 개입 확률 기준선에 정밀 자동 반영합니다.",style=MaterialTheme.typography.bodySmall)
        Numeric("응답 유효시간 (초, 최대 300)",ttl,{ttl=it},!busy)
        Text("확률 기준을 통과해도 작성·잠금·보류·미션·상황 변경 시에는 표시하지 않습니다. 숏폼의 다음 전환 시점에 다시 확인합니다.",style=MaterialTheme.typography.bodySmall)
    }
    TestSection("재평가·호출 제한 (동적 자동 조절)") {
        val liveState by vm.runtime.state.collectAsState()
        val dynInterval = liveState.dynamicIntervalSeconds ?: 300
        val lastGap = liveState.lastProbabilityGap
        val nextInSec = liveState.nextEvaluationInSeconds ?: 0L
        val addSec = liveState.additionalViewingNeededSeconds ?: 0L

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "실시간 재평가 간격 & 추가 시청: ${dynInterval}초 (${"%.1f".format(dynInterval / 60.0)}분)",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (lastGap != null) {
                        "직전 평가 확률 격차: -${"%.1f".format(lastGap * 100)}%p → 동적 보간(${dynInterval}초) 적용 중"
                    } else {
                        "첫 평가 전 기본 간격: 300초 (첫 확률 평가 후 30~360초 동적 보간으로 자동 조정)"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (liveState.probability != null) {
                    Text(
                        text = "대기 경과: ${maxOf(0L, dynInterval - nextInSec)}초 / ${dynInterval}초 · 추가 시청: ${maxOf(0L, dynInterval - addSec)}초 / ${dynInterval}초",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "• 격차 ≤ 15%p (기준치 근접/초과): 30초 ~ 90초 (빠른 재평가)\n" +
                           "• 격차 15%p ~ 30%p (중간 격차): 90초 ~ 210초 (1.5 ~ 3.5분)\n" +
                           "• 격차 30%p ~ 50%p+ (큰 격차): 210초 ~ 360초 (3.5 ~ 6.0분)\n" +
                           "• '나중에'·무응답 시: 5분(300초) 쿨다운 대기 후 재개\n" +
                           "• 기기 일일 호출 한도: 무제한 (비용 최적화 자동 관리)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    Button(enabled=!busy,onClick={
        try {
            fun int(text:String,label:String)=text.toIntOrNull() ?: error("$label 값을 확인해 주세요.")
            val p=probability.toDoubleOrNull() ?: error("확률을 확인해 주세요.")
            val next=s.copy(enabled=enabled,actualIntervention=actual,instagram=ig,youtube=yt,
                continuousEnabled=continuousOn,continuousSeconds=int(continuous,"시청 기준"),
                returnsEnabled=returnsOn,returnsThreshold=int(returns,"재진입"),
                bedtimeEnabled=bedtimeOn,bedtimeDelayMinutes=int(bedtimeDelay,"취침 지연"),bedtimeWindowMinutes=int(bedtimeWindow,"취침 적용 시간"),
                probabilityThreshold=p/100.0,personalizedEnabled=personalizedOn,reevaluateSeconds=300,
                additionalViewingSeconds=300,resultTtlSeconds=int(ttl,"응답 유효시간"),
                laterSeconds=s.laterSeconds,dailyLimit=999999).validated()
            error=null;vm.save(next)
        } catch(e:Exception) {error=e.message}
    },modifier=Modifier.fillMaxWidth()) {Text("판단 설정 저장")}
    OutlinedButton(enabled=!busy,onClick={vm.save(JevSettings(testerId=s.testerId,collectionConsent=s.collectionConsent))},modifier=Modifier.fillMaxWidth()) {Text("초기 시험 설정으로 복원")}
}

/** Frozen measurement used for the probability check; changes only when the user presses refresh. */
private data class ProbabilitySnapshot(
    val app: String, val sessionId: String, val totalMs: Long,
    val continuousMs: Long, val continuousLive: Boolean,
    val swipes: Int, val swipesLive: Boolean,
    val returns: Int, val bedtime: Int?, val takenAt: Long,
    val enrichedContext: JevContext,
    val liveProbability: Double?, val latestTrialProbability: Double?, val latestTrialSource: String?,
    val latestLiveProbability: Double?, val latestLiveCreatedAt: Long?,
    val latestLiveTrial: JevTrial?,
    val displayBlockingReason: String?
) {
    fun context() = enrichedContext
}

private fun takeProbabilitySnapshot(vm: JevTestViewModel, app: String, trials: List<JevTrial>): ProbabilitySnapshot {
    val session = vm.runtime.sessionState(app)
    val youtube = app == JevSettings.YOUTUBE
    val swipesNow = if (youtube) YoutubeProbe.candidates else ReelsProbe.candidates
    val swipesLast = if (youtube) YoutubeProbe.lastRunCandidates else ReelsProbe.lastRunCandidates
    val continuousNow = session?.continuousMs ?: 0L
    val latestTrial = trials.firstOrNull { it.probability != null }
    val latestLiveTrial = trials.firstOrNull { it.source == "LIVE" && it.probability != null }
    val sessionId = session?.id ?: UUID.randomUUID().toString()
    val totalMs = session?.totalMs ?: 0L
    val continuousMs = if (continuousNow > 0L) continuousNow else session?.lastRunContinuousMs ?: 0L
    val returns = session?.returns?.size ?: 0
    val bedtime = vm.runtime.lastObservedContext?.takeIf { it.packageName == app }?.bedtimePassedMinutes
    val takenAt = System.currentTimeMillis()
    val baseContext = JevContext(sessionId, app, totalMs, continuousMs, returns, 600, bedtime, takenAt)
    val enriched = vm.runtime.enrichContext(baseContext)
    val blockingReason = vm.runtime.displayBlockingReason()
    return ProbabilitySnapshot(
        app = app,
        sessionId = sessionId,
        totalMs = totalMs,
        // Keep the latest run's value even after the idle reset so the probability can still be checked.
        continuousMs = continuousMs,
        continuousLive = continuousNow > 0L,
        swipes = if (swipesNow > 0) swipesNow else swipesLast,
        swipesLive = swipesNow > 0,
        returns = returns,
        bedtime = bedtime,
        takenAt = takenAt,
        enrichedContext = enriched,
        liveProbability = vm.runtime.state.value.probability,
        latestTrialProbability = latestTrial?.probability,
        latestTrialSource = latestTrial?.source,
        latestLiveProbability = vm.runtime.state.value.probability ?: latestLiveTrial?.probability,
        latestLiveCreatedAt = latestLiveTrial?.createdAt,
        latestLiveTrial = latestLiveTrial,
        displayBlockingReason = blockingReason
    )
}

private fun resolveNotShownReason(
    trial: JevTrial?,
    isActualIntervention: Boolean,
    isTestMode: Boolean,
    isJevEnabled: Boolean,
    canDrawOverlays: Boolean
): String {
    if (isTestMode) {
        return "테스트 모드가 켜져 있어 Jev 대신 고정값 규칙으로 개입함 (설정에서 테스트 모드 끄기 필요)"
    }
    if (!isActualIntervention) {
        return "‘확률 통과 시 실제 개입’ 설정이 꺼져 있어 판단만 기록됨 (설정에서 켜기 필요)"
    }
    if (!isJevEnabled) {
        return "Jev 자동 관측·판단 설정이 꺼져 있음"
    }
    if (!canDrawOverlays) {
        return "‘다른 앱 위에 표시’ 권한이 허용되지 않음"
    }
    val state = trial?.state
    if (state != null && state.startsWith("SUPPRESSED:")) {
        val code = state.removePrefix("SUPPRESSED:")
        return when (code) {
            "OBSERVE_ONLY" -> "‘확률 통과 시 실제 개입’ 설정이 꺼져 있어 개입이 보류됨 (설정에서 켜기 필요)"
            "STALE_CONTEXT_OR_SETTINGS" -> "설정 변경 또는 앱 전환으로 이전 확률이 만료됨"
            "BELOW_PROBABILITY_THRESHOLD" -> "개입 기준 확률 미달"
            "MONITOR_STOPPED" -> "모니터링 서비스 중지로 취소됨"
            "SETTINGS_CHANGED" -> "설정 변경으로 취소됨"
            "STALE_CONTEXT_OR_RESULT" -> "상황 변화로 결과 만료됨"
            "DISPLAY_FAILED" -> "화면 표시 실패"
            else -> "보류됨 ($code)"
        }
    }
    return "다음 숏폼 전환 대기 중 (시청 방해를 줄이기 위해 다음 영상으로 넘기거나 최대 15초 경과 시 표시)"
}

@Composable
private fun ObservationTestPage(vm: JevTestViewModel, busy: Boolean) {
    val s by vm.store.settings.collectAsState()
    val answer by vm.manualAnswer.collectAsState()
    val trials by vm.store.dao.trials().collectAsState(initial = emptyList())
    val context = androidx.compose.ui.platform.LocalContext.current
    var refreshTick by remember { mutableLongStateOf(0L) }
    var targetApp by rememberSaveable { mutableStateOf(JevSettings.YOUTUBE) }
    var snapshot by remember { mutableStateOf<ProbabilitySnapshot?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1.seconds)
            refreshTick = android.os.SystemClock.elapsedRealtime()
        }
    }
    // First entry / app switch takes one snapshot; also updates if trials initially load when snapshot had no trial probability yet.
    LaunchedEffect(targetApp, trials.size) {
        if (snapshot == null || snapshot?.app != targetApp || (snapshot?.latestLiveProbability == null && trials.isNotEmpty())) {
            snapshot = takeProbabilitySnapshot(vm, targetApp, trials)
        }
    }

    val ytToday = remember(refreshTick) { ShortformDailyStore.today(context, "youtube") }
    val igToday = remember(refreshTick) { ShortformDailyStore.today(context, "instagram") }
    val reelsUsage = remember(refreshTick) { ReelsUsageStore.today(context) }
    val activeSession = remember(refreshTick) { ShortformDailyStore.sessionSnapshot() }
    val recentSession = remember(refreshTick) { ShortformDailyStore.recentSessionSnapshot() }

    val ytSession = vm.runtime.sessionState(JevSettings.YOUTUBE)
    val igSession = vm.runtime.sessionState(JevSettings.INSTAGRAM)
    val lastObserved = vm.runtime.lastObservedContext

    TestSection("판단 설정 기준 및 개입 확률 확인") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = targetApp == JevSettings.YOUTUBE,
                onClick = { targetApp = JevSettings.YOUTUBE },
                label = { Text("YouTube") },
                enabled = !busy
            )
            FilterChip(
                selected = targetApp == JevSettings.INSTAGRAM,
                onClick = { targetApp = JevSettings.INSTAGRAM },
                label = { Text("Instagram") },
                enabled = !busy
            )
            Spacer(Modifier.weight(1f))
            snapshot?.let {
                val takenAt = Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                Text("갱신 $takenAt", style = MaterialTheme.typography.labelSmall)
            }
        }
        OutlinedButton(
            onClick = {
                snapshot = takeProbabilitySnapshot(vm, targetApp, trials)
                refreshTick = android.os.SystemClock.elapsedRealtime()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("측정값 갱신 (토큰 사용 없음)")
        }

        val snap = snapshot
        if (snap == null) {
            Text("갱신 버튼을 눌러 측정값을 불러오세요.")
        } else {
            val ctx = snap.context()
            val matchedReasons = JevCandidatePolicy.reasons(ctx, s)
            val effThresholdPct = JevCandidatePolicy.effectiveThreshold(s, ctx) * 100.0
            val reasonKor = matchedReasons.map { r ->
                when (r) {
                    "CONTINUOUS" -> "연속시청"
                    "REPEATED_RETURNS" -> "반복재진입"
                    "BEDTIME" -> "취침시간"
                    "PERSONAL_SESSION_EXCEEDED" -> "평균세션초과"
                    "RAPID_SWIPE_OVERHEAT" -> "스와이프과열"
                    "PRE_BEDTIME_DARK_USAGE" -> "취침전암실사용"
                    "CONTINUOUS_FATIGUE" -> "연속시청피로누적"
                    "INTERVENTION_RESISTANCE" -> "직전개입미룸·시청지속"
                    else -> r
                }
            }
            val ambientSnapshot = remember(refreshTick) {
                com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(context).snapshot.value
            }

            // 1. 시청 & 재진입 요약 박스
            CompactCheckGroup("1. 시청 시간 · 재진입") {
                CompactCheckRow(
                    label = "연속 시청",
                    value = "${"%.1f".format(snap.continuousMs / 1000.0)}초 / 기준 ${if (s.continuousEnabled) "${s.continuousSeconds}초" else "꺼짐"}"
                )
                CompactCheckRow(
                    label = "구간 누적 · 재진입",
                    value = "누적 ${"%.1f".format(snap.totalMs / 1000.0)}초 · 재진입 ${snap.returns}회 (기준 ${if (s.returnsEnabled) "${s.returnsThreshold}회" else "꺼짐"})"
                )
            }

            // 2. 개인화 비율 & 스와이프 요약 박스
            CompactCheckGroup("2. 개인화 비율 · 스와이프 · 피로도 · 취침환경") {
                val adj = JevCandidatePolicy.computeThresholdAdjustment(s, ctx)
                val sessionRatioStr = ctx.sessionVsAvgRatio?.let { "${(it * 100).roundToInt()}%" } ?: "집계 중"
                val avgSessionStr = ctx.personalAvgSessionMinutes?.let { "기준 ${it}분" } ?: "기준 수집 중"
                val sessionDeltaStr = if (adj.sessionDelta != 0.0) " (${if (adj.sessionDelta > 0) "+" else ""}${"%.0f".format(adj.sessionDelta * 100)}%p)" else ""
                CompactCheckRow(
                    label = "세션 과몰입 비율",
                    value = "$sessionRatioStr ($avgSessionStr)$sessionDeltaStr",
                    highlight = adj.sessionDelta < 0
                )
                val fatigueMin = (ctx.continuousMs / 60000.0)
                val fatigueDeltaStr = if (adj.fatigueDelta < 0) " (${"%.0f".format(adj.fatigueDelta * 100)}%p)" else ""
                val fatigueDesc = when {
                    fatigueMin >= 30.0 -> "고위험 (${"%.1f".format(fatigueMin)}분 연속)"
                    fatigueMin >= 15.0 -> "주의 (${"%.1f".format(fatigueMin)}분 연속)"
                    else -> "정상 (${"%.1f".format(fatigueMin)}분 연속)"
                }
                CompactCheckRow(
                    label = "연속 시청 피로도",
                    value = "$fatigueDesc$fatigueDeltaStr",
                    highlight = adj.fatigueDelta < 0
                )
                val resistanceDeltaStr = if (adj.resistanceDelta < 0) " (${"%.0f".format(adj.resistanceDelta * 100)}%p)" else ""
                val resistanceDesc = when {
                    ctx.lastInterventionOutcome == "LATER" -> "나중에 선택 후 시청 지속 (${ctx.resistanceStreak}회)"
                    ctx.lastInterventionOutcome == "NO_RESPONSE" && ctx.postInterventionActivity -> "무응답 무시 후 스와이프 지속 (${ctx.resistanceStreak}회)"
                    ctx.lastInterventionOutcome != null -> "${ctx.lastInterventionOutcome} (정상 대기)"
                    else -> "최근 개입 없음 (정상)"
                }
                CompactCheckRow(
                    label = "직전 개입 반응",
                    value = "$resistanceDesc$resistanceDeltaStr",
                    highlight = adj.resistanceDelta < 0
                )
                val spmStr = "분당 ${ctx.effectiveSpm1m ?: 0.0}회 (평소 ${ctx.personalAvgSpm ?: 0.0})"
                val swipeDeltaStr = if (adj.swipeDelta < 0) " · 과열(${"%.0f".format(adj.swipeDelta * 100)}%p)" else " · 정상"
                CompactCheckRow(
                    label = "스와이프 (${snap.swipes}회)",
                    value = "$spmStr$swipeDeltaStr",
                    highlight = adj.swipeDelta < 0
                )
                val bedtimeDeltaStr = if (adj.bedtimeDelta != 0.0) " (${if (adj.bedtimeDelta > 0) "+" else ""}${"%.0f".format(adj.bedtimeDelta * 100)}%p)" else ""
                val bedtimeStatusStr = when {
                    ctx.isNearOrOccluded -> "센서 가림 감지 (0cm 근접)"
                    adj.bedtimeDelta < 0 -> {
                        val distStr = ctx.bedtimeDistanceMinutes?.let { if (it < 0) "취침 ${-it}분 전" else "취침 ${it}분 경과" } ?: "취침임박"
                        "취침 전 암실 시청 · $distStr (${ctx.ambientLux?.toInt() ?: 0}lx / 화면 ${ctx.screenBrightness ?: 0})"
                    }
                    adj.bedtimeDelta > 0 -> "주간/밝은 환경 (${ctx.ambientLux?.toInt() ?: 0}lx / 화면 ${ctx.screenBrightness ?: 0})"
                    else -> "일반 실내 조명 (${ctx.ambientLux?.toInt() ?: 0}lx / 화면 ${ctx.screenBrightness ?: 0})"
                }
                CompactCheckRow(
                    label = "취침환경 · 조도",
                    value = "$bedtimeStatusStr$bedtimeDeltaStr",
                    highlight = adj.bedtimeDelta < 0
                )
                val postureStr = when (ambientSnapshot.posture) {
                    com.example.itv.domain.coaching.UserPosture.LYING_DOWN -> "수평/하향 각도 (전면 ${ambientSnapshot.pitchDegrees?.toInt() ?: 0}° · 경추 고하중 🐢)"
                    com.example.itv.domain.coaching.UserPosture.SITTING_OR_STANDING -> "정면 파지 각도 (전면 ${ambientSnapshot.pitchDegrees?.toInt() ?: 0}° · 어깨/허리 긴장 🙆)"
                    com.example.itv.domain.coaching.UserPosture.TABLE_OR_DESK -> "테이블 거치 각도 (전면 ${ambientSnapshot.pitchDegrees?.toInt() ?: 0}° · 고개숙임 🍽️)"
                    com.example.itv.domain.coaching.UserPosture.FACEDOWN -> "스마트폰 엎어둠 (완전 차단 🛑)"
                    else -> "각도 측정 중 (기울기 ${ambientSnapshot.pitchDegrees?.toInt() ?: 0}°)"
                }
                CompactCheckRow(
                    label = "파지 각도 및 하중",
                    value = postureStr,
                    highlight = ambientSnapshot.posture == com.example.itv.domain.coaching.UserPosture.LYING_DOWN
                )
            }

            // 2-1. 취침 전 환경 및 점수 판정 (Bedtime Score)
            CompactCheckGroup("취침 전 환경 및 점수 판정 (Bedtime Score)") {
                val score = ambientSnapshot.bedtimeScore
                val isCandidate = ambientSnapshot.isBedtimeCandidate
                CompactCheckRow(
                    label = "취침 확신도 점수 (60점 이상)",
                    value = "${score}점 / 100점 · ${if (isCandidate) "취침 케어 개입 대상 확정 ✅" else "일상 활동 상태 (미대상) ❌"}",
                    highlight = isCandidate
                )
                CompactCheckRow(
                    label = "점수 계산 내역",
                    value = ambientSnapshot.bedtimeScoreBreakdown
                )
                val chargingText = when (ambientSnapshot.isCharging) {
                    true -> "충전기 연결됨 (+15점) 🔌"
                    false -> "미연결 (0점)"
                    else -> "확인 대기 (0점)"
                }
                val wifiText = when (ambientSnapshot.isWifiConnected) {
                    true -> "Wi-Fi 연결됨 (+10점) 📶"
                    false -> "모바일 데이터/미연결 (0점)"
                    else -> "확인 대기 (0점)"
                }
                CompactCheckRow(
                    label = "소프트 맥락 (배터리·네트워크)",
                    value = "$chargingText · $wifiText"
                )
                CompactCheckRow(
                    label = "숏폼 진입 시 동작",
                    value = if (isCandidate) "취침 3분기 케어(마음비우기/흑백모드/ASMR) 화면 발동" else "일반 숏폼 피로도/과열 개입 모델 적용",
                    highlight = isCandidate
                )
            }

            // 3. 개입 확률 기준 & 판정 결과 박스
            CompactCheckGroup("3. 개입 기준 · 확률 결과") {
                CompactCheckRow(
                    label = "적용 개입 기준",
                    value = "${"%.0f".format(effThresholdPct)}% 이상 · ${JevCandidatePolicy.thresholdBreakdown(s, ctx)}",
                    highlight = kotlin.math.abs(effThresholdPct - s.probabilityThreshold * 100.0) >= 0.1
                )
                CompactCheckRow(
                    label = "호출 조건",
                    value = if (reasonKor.isNotEmpty()) "충족 (${reasonKor.joinToString(", ")})" else "미충족",
                    highlight = reasonKor.isNotEmpty()
                )

                val liveProb = snap.latestLiveProbability
                val latestLiveTrial = snap.latestLiveTrial
                if (liveProb != null) {
                    val passed = JevCandidatePolicy.passes(liveProb, s, ctx)
                    val timeLabel = snap.latestLiveCreatedAt?.let {
                        " · " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    }.orEmpty()
                    CompactCheckRow(
                        label = "최근 LIVE 확률",
                        value = "${"%.1f".format(liveProb * 100)}% (${if (passed) "기준 통과" else "기준 미달"}$timeLabel)",
                        highlight = passed
                    )

                    if (passed) {
                        val displayedAt = latestLiveTrial?.displayedAt
                        val isDisplayed = displayedAt != null
                        val displayStatusText = if (isDisplayed) {
                            val dispTime = Instant.ofEpochMilli(displayedAt)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                            "화면에 표시 완료 ($dispTime)"
                        } else {
                            "미표시 (화면에 뜨지 않음)"
                        }

                        CompactCheckRow(
                            label = "개입 표시 여부",
                            value = displayStatusText,
                            highlight = !isDisplayed
                        )

                        if (!isDisplayed) {
                            val notShownReason = snap.displayBlockingReason ?: resolveNotShownReason(
                                trial = latestLiveTrial,
                                isActualIntervention = s.actualIntervention,
                                isTestMode = com.example.itv.presentation.coaching.CoachingAutoStart.testModeEnabled(context),
                                isJevEnabled = s.enabled,
                                canDrawOverlays = android.provider.Settings.canDrawOverlays(context)
                            )
                            CompactCheckRow(
                                label = "미표시 사유",
                                value = notShownReason,
                                highlight = true
                            )
                        }
                    }
                } else if (snap.latestTrialProbability != null) {
                    val p = snap.latestTrialProbability
                    val passed = JevCandidatePolicy.passes(p, s, ctx)
                    CompactCheckRow(
                        label = "최근 요청 확률",
                        value = "${"%.1f".format(p * 100)}% (${if (passed) "기준 통과" else "기준 미달"})",
                        highlight = passed
                    )
                } else {
                    CompactCheckRow(label = "최근 개입 확률", value = "아직 기록 없음")
                }
            }

            val liveState by vm.runtime.state.collectAsState()
            val appSuppression = remember(refreshTick, targetApp) { vm.runtime.getAppSuppressionInfo(targetApp) }
            val dynInterval = appSuppression.dynamicIntervalSeconds
            val lastGap = appSuppression.lastProbabilityGap
            val nextInSec = appSuppression.nextEvaluationInSeconds ?: 0L
            val addSec = if (liveState.context?.packageName == targetApp) liveState.additionalViewingNeededSeconds ?: 0L else 0L
            val snapProb = snap.latestLiveProbability
            val expInterval = snapProb?.let { JevCandidatePolicy.calculateDynamicInterval(effThresholdPct / 100.0, it) }
            val expGap = snapProb?.let { ((effThresholdPct / 100.0) - it).coerceAtLeast(0.0) }
            val intervalDesc = when {
                appSuppression.isAsked && lastGap != null ->
                    "${dynInterval}초 (실시간 평가 반영 · 격차 -${"%.1f".format(lastGap * 100)}%p)"
                expInterval != null && expGap != null ->
                    "${dynInterval}초 (기본값 · 최근 확률 예상: ${expInterval}초, 격차 -${"%.1f".format(expGap * 100)}%p)"
                else -> "${dynInterval}초 (기본값 · 첫 실시간 평가 대기)"
            }
            CompactCheckGroup("4. 동적 재평가 · 추가 시청") {
                CompactCheckRow(
                    label = "동적 간격 (30~360초)",
                    value = intervalDesc
                )
                CompactCheckRow(
                    label = "다음 재평가 대기",
                    value = if (nextInSec > 0) "${nextInSec}초 남음" else "대기 시간 충족"
                )
                CompactCheckRow(
                    label = "필요 추가 시청",
                    value = if (addSec > 0) "${addSec}초 남음" else "시청량 충족"
                )
            }
        }

        answer?.let { a ->
            val passed = JevCandidatePolicy.passes(a.probability, s, snap?.context())
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "방금 요청 결과: ${"%.1f".format(a.probability * 100)}% (${if (passed) "개입 기준 통과" else "기준 미달"} · ${a.trial.latencyMs}ms)",
                    fontWeight = FontWeight.Bold,
                    color = if (passed) ItvGreen else MaterialTheme.colorScheme.error
                )
                if (passed) {
                    val disp = a.trial.displayedAt != null
                    val r = if (disp) "화면에 표시 완료" else (snap?.displayBlockingReason ?: resolveNotShownReason(a.trial, s.actualIntervention, com.example.itv.presentation.coaching.CoachingAutoStart.testModeEnabled(context), s.enabled, android.provider.Settings.canDrawOverlays(context)))
                    Text(
                        "• 개입 표시: ${if (disp) "표시됨" else "미표시"} ($r)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (disp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Button(
            enabled = !busy && s.collectionConsent && snap != null,
            onClick = { snap?.let { vm.manual(it.context()) } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("현재 측정값으로 Jev 확률 요청 (토큰 1회)")
        }
    }

    TestSection("YouTube 쇼츠 측정 현황") {
        val ytTotalSec = (ytSession?.totalMs ?: 0L) / 1000.0
        val ytContSec = (ytSession?.continuousMs ?: 0L) / 1000.0
        val ytDailySec = ytToday.ms / 1000.0
        Text("• Jev 세션 누적 시간: ${"%.1f".format(ytTotalSec)}초 (${"%.1f".format(ytTotalSec / 60.0)}분)", fontWeight = FontWeight.SemiBold)
        Text("• Jev 연속 시청 시간: ${"%.1f".format(ytContSec)}초 (${"%.1f".format(ytContSec / 60.0)}분)", fontWeight = FontWeight.SemiBold, color = ItvGreen)
        Text("• 실시간 스와이프(전환) 감지 횟수: ${YoutubeProbe.candidates}회", fontWeight = FontWeight.SemiBold, color = ItvGreen)
        Text("• 최근 10분 재진입: ${ytSession?.returns?.size ?: 0}회")
        Text("• 오늘 총 누적 시청 시간: ${"%.1f".format(ytDailySec)}초 (${"%.1f".format(ytDailySec / 60.0)}분)")
        Text("• 오늘 총 전환(스와이프) 기록: ${ytToday.transitions}회")
        Text("• 탐색기 상태: ${YoutubeProbe.status}")
        Text("• 상세: ${YoutubeProbe.detail}")
    }

    TestSection("Instagram 릴스 측정 현황") {
        val igTotalSec = (igSession?.totalMs ?: 0L) / 1000.0
        val igContSec = (igSession?.continuousMs ?: 0L) / 1000.0
        val igDailySec = igToday.ms / 1000.0
        Text("• Jev 세션 누적 시간: ${"%.1f".format(igTotalSec)}초 (${"%.1f".format(igTotalSec / 60.0)}분)", fontWeight = FontWeight.SemiBold)
        Text("• Jev 연속 시청 시간: ${"%.1f".format(igContSec)}초 (${"%.1f".format(igContSec / 60.0)}분)", fontWeight = FontWeight.SemiBold, color = ItvGreen)
        Text("• 실시간 스와이프(전환) 감지 횟수: ${ReelsProbe.candidates}회", fontWeight = FontWeight.SemiBold, color = ItvGreen)
        Text("• 최근 10분 재진입: ${igSession?.returns?.size ?: 0}회")
        Text("• 오늘 총 누적 시청 시간: ${"%.1f".format(igDailySec)}초 (${"%.1f".format(igDailySec / 60.0)}분)")
        Text("• 오늘 총 전환(스와이프) 기록: ${igToday.transitions}회 (릴스 저장소: ${reelsUsage.count}회)")
        Text("• 탐색기 상태: ${ReelsProbe.status}")
        Text("• 스크롤 이벤트 수신: ${ReelsProbe.events}회")
    }

    TestSection("공통 세션 & 최근 관측값 (Jev 판단 입력)") {
        Text("• 활성 세션: ${if (activeSession.app.isNotEmpty()) "${activeSession.app} (${activeSession.durationMs / 1000}초)" else "없음"}")
        Text("• 직전 세션: ${if (recentSession.app.isNotEmpty()) "${recentSession.app} (${recentSession.durationMs / 1000}초)" else "없음"}")
        if (lastObserved != null) {
            Text("• 마지막 유효 JevContext:")
            Text("  - 앱: ${lastObserved.packageName}")
            Text("  - 누적 시청: ${lastObserved.sessionMs / 1000}초 (${"%.1f".format(lastObserved.sessionMs / 60000.0)}분)")
            Text("  - 연속 시청: ${lastObserved.continuousMs / 1000}초 (${"%.1f".format(lastObserved.continuousMs / 60000.0)}분)")
            Text("  - 10분 내 재진입: ${lastObserved.returns10m}회")
        } else {
            Text("• 아직 기록된 유효 JevContext가 없습니다.")
        }
    }

    TestSection("테스트 도구") {
        val context = LocalContext.current
        Text("YouTube와 Instagram의 실시간 스와이프 카운터와 세션을 초기화하여 새 테스트를 진행할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = {
                YoutubeProbe.reset()
                ReelsProbe.reset()
                SwipeDynamicsTracker.reset()
                vm.runtime.resetTestObservation()
                refreshTick = android.os.SystemClock.elapsedRealtime()
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("스와이프 수 & Jev 관측 상태 초기화")
        }
        Spacer(modifier = Modifier.height(6.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🚀 초기 화면(온보딩) 테스트 이동", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "현재의 사용 기록·목표·로그인 계정은 그대로 보존한 채, 앱을 처음 켰을 때 나오는 '권한 설정'과 '로그인 창' 화면으로 이동하여 변경 사항을 확인합니다.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val intent = Intent(context, com.example.itv.MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(com.example.itv.MainActivity.EXTRA_FORCE_ONBOARDING, true)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("초기 화면으로 이동 (권한/로그인 테스트)", fontWeight = FontWeight.Bold)
                }
            }
        }
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(context, CompanionPreviewActivity::class.java))
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🐶 캐릭터 상호작용 미리보기 열기")
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun PostureTestPage(busy: Boolean) {
    val context = LocalContext.current
    val tracker = remember { LightAndBrightnessTracker.getInstance(context) }

    DisposableEffect(Unit) {
        tracker.start()
        tracker.startContinuousPostureListening()
        onDispose {
            tracker.stopContinuousPostureListening()
            tracker.stop()
        }
    }

    val snap by tracker.snapshot.collectAsState()
    var missionSeed by remember { mutableIntStateOf(0) }
    val normalMission = remember(snap.posture, missionSeed) {
        RestMissionCatalog.pick(snap.posture, isStrong = false)
    }
    val strongMission = remember(snap.posture, missionSeed) {
        RestMissionCatalog.pick(snap.posture, isStrong = true)
    }
    val isFacedown = tracker.isFacedown()

    val postureTitle = when (snap.posture) {
        UserPosture.LYING_DOWN -> "수평/하향 각도 (목 하중·거북목 부담)"
        UserPosture.SITTING_OR_STANDING -> "정면 파지 각도 (어깨·등 뭉침)"
        UserPosture.TABLE_OR_DESK -> "식탁/책상 거치 (고개 숙임)"
        UserPosture.FACEDOWN -> "스마트폰 엎어둠 (완전 차단)"
        UserPosture.UNKNOWN -> "각도 측정 중 / 미정"
    }

    val postureColor = when (snap.posture) {
        UserPosture.LYING_DOWN -> MaterialTheme.colorScheme.error
        UserPosture.SITTING_OR_STANDING -> ItvGreen
        UserPosture.TABLE_OR_DESK -> MaterialTheme.colorScheme.primary
        UserPosture.FACEDOWN -> MaterialTheme.colorScheme.tertiary
        UserPosture.UNKNOWN -> MaterialTheme.colorScheme.outline
    }

    TestSection("실시간 각도 및 목·어깨 부담 감지 (실시간 반응)") {
        Text(
            "자이로/중력 센서를 분석하여 스마트폰 파지 각도와 그에 따른 목·어깨·허리의 부담을 실시간 측정하고, 2° 이상의 각도 변화에도 즉시 반응하여 최적의 부위별 스트레칭을 매칭합니다.",
            style = MaterialTheme.typography.bodySmall
        )

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(2.dp, postureColor),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        postureTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = postureColor
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isFacedown) MaterialTheme.colorScheme.errorContainer else ItvMint
                    ) {
                        Text(
                            text = if (isFacedown) "엎어둠 감지!" else "정상 시청 각도",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isFacedown) MaterialTheme.colorScheme.onErrorContainer else ItvGreen
                        )
                    }
                }
                Text(
                    text = "판별 요약: ${snap.postureExplanation}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    TestSection("센서 실시간 측정 수치") {
        CompactCheckGroup("자이로·중력 및 환경 센서") {
            CompactCheckRow(
                label = "기기 거치 안정도",
                value = if (snap.isStationary) "식탁/거치대 완전 정지 (Stationary)" else "손 파지 상태 (미세 떨림 감지)",
                highlight = snap.isStationary
            )
            CompactCheckRow(
                label = "전면 기울기 (Pitch)",
                value = snap.pitchDegrees?.let { "%.1f° (수평 0° ~ 직립 90°)".format(it) } ?: "측정 대기",
                highlight = snap.pitchDegrees != null && snap.pitchDegrees!! in 22f..88f
            )
            CompactCheckRow(
                label = "좌우 기울기 (Roll)",
                value = snap.rollDegrees?.let { "%.1f° (좌우 수평 0° · 엎음 180°)".format(it) } ?: "측정 대기",
                highlight = snap.rollDegrees != null && kotlin.math.abs(snap.rollDegrees!!) > 45f
            )
            CompactCheckRow(
                label = "Z축 중력가속도",
                value = snap.gravityZ?.let { "%.2f m/s² (상향 +9.8 · 하향 -9.8)".format(it) } ?: "측정 대기",
                highlight = snap.gravityZ != null && snap.gravityZ!! < -6.0f
            )
            CompactCheckRow(
                label = "주변 조도 (Lux)",
                value = snap.lux?.let { "%.1f lux".format(it) } ?: "측정 대기",
                highlight = snap.lux != null && snap.lux!! <= 1.0f
            )
            val proxText = when {
                snap.proximityDistanceCm == null -> "측정 대기"
                snap.isNear -> "가림 (닫힘, 0cm)"
                isFacedown -> "열림 (${snap.proximityDistanceCm}cm) · ※카툭튀/가상센서 특성 (중력·조도로 엎어둠 감지)"
                else -> "열림 (${snap.proximityDistanceCm}cm)"
            }
            CompactCheckRow(
                label = "근접 센서 상태",
                value = proxText,
                highlight = snap.isNear || isFacedown
            )
        }
    }

    TestSection("파지 각도 및 신체 부위별 부담 판별 기준") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("• 수평/하향 각도 (LYING_DOWN): 폰을 바닥이나 침대에 눕혀 내려다보거나 얼굴 위로 들고 보는 각도입니다. 고개가 꺾여 목에 하중이 실리므로, 고개를 뒤로 젖히고 목 주변 근육을 풀어주는 스트레칭을 우선 추천합니다.", style = MaterialTheme.typography.bodySmall)
            Text("• 정면 파지 각도 (SITTING_OR_STANDING): 손에 쥐고 장시간 고정하는 표준 파지 각도입니다. 어깨와 등이 굳고 뭉치기 쉬우므로, 어깨를 둥글게 굴리거나 기지개로 굳은 상체를 시원하게 풀어주도록 제안합니다.", style = MaterialTheme.typography.bodySmall)
            Text("• 식탁/책상 거치 (TABLE_OR_DESK): 폰을 내려놓고 고개를 푹 숙인 채 시청하는 상태입니다. 허리를 곧게 펴고 고개를 들어 시야를 넓히도록 자세 교정과 휴식을 제안합니다.", style = MaterialTheme.typography.bodySmall)
            Text("• 화면 엎어둠 (FACEDOWN): 화면을 바닥이나 침대에 엎어두어 조도가 차단된 상태입니다. 폰을 완전히 엎어두고 기지개를 켜거나 어깨를 푸는 등 실질적인 휴식 미션 완료를 판단합니다.", style = MaterialTheme.typography.bodySmall)
        }
    }

    TestSection("현재 파지 각도 맞춤 스트레칭 미션 매칭") {
        Text("현재 감지된 파지 각도 [${snap.posture.name}]에 따라 목, 어깨, 허리 등 부위별 뭉친 곳을 풀어주는 맞춤 스트레칭 미션입니다.", style = MaterialTheme.typography.bodySmall)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { missionSeed++ }) {
                Text("다른 미션 뽑아보기")
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, ItvLine),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("일반 추천 미션 (부위별 이완)", fontWeight = FontWeight.Bold, color = ItvGreen)
                    Surface(shape = RoundedCornerShape(6.dp), color = ItvMint) {
                        Text(
                            normalMission.category,
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(normalMission.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(normalMission.action, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("강한 개입 미션 (화면 엎어두기)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            strongMission.category,
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Text(strongMission.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(strongMission.action, style = MaterialTheme.typography.bodyMedium)
                Text("※ 화면을 끄고 뒤집어(엎어)두어야만 미션 시간이 흘러갑니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    TestSection("강아지 개입 실시간 동작 테스트") {
        Text(
            "현재 측정된 각도(Pitch/Roll)와 조도(Lux)에 따라 개입 사유와 추천 미션이 동적으로 달라집니다. 기다리지 않고 즉시 테스트해 보세요.",
            style = MaterialTheme.typography.bodySmall
        )

        Button(
            onClick = {
                context.startActivity(Intent(context, CompanionPreviewActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("현재 감지 상태로 개입 테스트 (미리보기 창 열기)")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    android.widget.Toast.makeText(context, "3초 뒤 오버레이가 뜹니다! 원하는 자세를 취해보세요", android.widget.Toast.LENGTH_SHORT).show()
                    CoachingMonitorService.triggerTest(context, forceStrong = false, delayMs = 3000L)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("3초 뒤 일반 개입")
            }

            OutlinedButton(
                onClick = {
                    android.widget.Toast.makeText(context, "3초 뒤 강한 개입이 뜹니다", android.widget.Toast.LENGTH_SHORT).show()
                    CoachingMonitorService.triggerTest(context, forceStrong = true, delayMs = 3000L)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("3초 뒤 강한 개입")
            }
        }
    }
}

@Composable
private fun InterventionHistoryPage() {
    val context = LocalContext.current
    var refreshTick by remember { mutableLongStateOf(0L) }
    var showJournalDelete by remember { mutableStateOf(false) }

    val missionCounts = remember(refreshTick) { MissionStats.counts(context) }
    val userReward = remember(refreshTick) { com.example.itv.infrastructure.reward.UserRewardStore.getReward(context) }
    val journalRows = remember(refreshTick) { InterventionJournal.rows(context) }

    if (showJournalDelete) {
        AlertDialog(
            onDismissRequest = { showJournalDelete = false },
            title = { Text("개입 결과 기록을 삭제할까요?") },
            text = { Text("최근 개입 이벤트만 삭제합니다. 미션 누적 통계·사용량 통계·진행 중인 미션은 유지됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    InterventionJournal.clear(context)
                    showJournalDelete = false
                    refreshTick = android.os.SystemClock.elapsedRealtime()
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showJournalDelete = false }) { Text("취소") }
            }
        )
    }

    TestSection("캐릭터 상호작용 미리보기") {
        Text(
            "강아지 캐릭터의 다양한 표정(환영, 피곤, 응원, 휴식 제안, 축하 등)과 대화 상호작용 오버레이 동작을 직접 테스트하고 확인합니다.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = {
                context.startActivity(Intent(context, CompanionPreviewActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("캐릭터 상호작용 미리보기 열기", fontWeight = FontWeight.Bold)
        }
    }

    TestSection("캐릭터 성장 및 보상 시스템 (레벨·EXP·포인트)") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("현재 캐릭터 레벨: Lv.${userReward.currentLevel}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ItvGreen)
            Surface(shape = RoundedCornerShape(6.dp), color = ItvMint) {
                Text(
                    "${userReward.currentPoints} P 보유",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text("• 경험치: ${userReward.currentLevelExp} / ${userReward.nextLevelExpRequired} EXP (${(userReward.progressPercent * 100).toInt()}%) · 누적 ${userReward.totalExp} EXP")
        Text("• 누적 미션 성공: ${userReward.totalCompletedMissions}회")
        LinearProgressIndicator(
            progress = { userReward.progressPercent },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = ItvGreen,
            trackColor = ItvMint
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val res = com.example.itv.infrastructure.reward.UserRewardStore.grantMissionReward(context, isStrong = false)
                    android.widget.Toast.makeText(context, "+${res.expGained} EXP, +${res.pointsGained} P 지급 (Lv.${res.newLevel})", android.widget.Toast.LENGTH_SHORT).show()
                    refreshTick = android.os.SystemClock.elapsedRealtime()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("+30 EXP (일반)", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = {
                    val res = com.example.itv.infrastructure.reward.UserRewardStore.grantMissionReward(context, isStrong = true)
                    android.widget.Toast.makeText(context, "+${res.expGained} EXP, +${res.pointsGained} P 지급 (Lv.${res.newLevel})", android.widget.Toast.LENGTH_SHORT).show()
                    refreshTick = android.os.SystemClock.elapsedRealtime()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("+50 EXP (강함)", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(
                onClick = {
                    com.example.itv.infrastructure.reward.UserRewardStore.reset(context)
                    refreshTick = android.os.SystemClock.elapsedRealtime()
                }
            ) {
                Text("초기화", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    TestSection("휴식 미션 성공률 · 누적 통계") {
        val rate = if (missionCounts.assigned == 0L) "—" else
            String.format(java.util.Locale.KOREA, "%.1f%%", missionCounts.success * 100.0 / missionCounts.assigned)
        Text("성공률: $rate", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ItvGreen)
        Text("• 미션 부여: ${missionCounts.assigned}회")
        Text("• 미션 성공: ${missionCounts.success}회")
        Text("• 미션 실패: ${missionCounts.failed}회 · 관측 중단: ${missionCounts.interrupted}회")
        val pending = (missionCounts.assigned - missionCounts.success - missionCounts.failed - missionCounts.interrupted).coerceAtLeast(0L)
        Text("• 진행 중/미확정: ${pending}회")
        Text(
            "※ 성공률 = 성공 ÷ 전체 부여 × 100. 진행 중·관측 중단도 부여 횟수에 포함됩니다.\n" +
            "• 테스트 모드: 10초 내 화면 끄기 시작, 15초 꺼짐 시 완료\n" +
            "• Jev 개입 모드: 10초 내 화면 끄기 시작, 30초 꺼짐 시 완료",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    TestSection("개입 결과 로그 (최근 200건)") {
        Text("표시 성공·선택·무응답·휴식 미션을 시간순으로 확인합니다. 같은 제안 ID로 연결됩니다.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { refreshTick = android.os.SystemClock.elapsedRealtime() },
                modifier = Modifier.weight(1f)
            ) {
                Text("기록 새로고침")
            }
            TextButton(
                onClick = { showJournalDelete = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("개입 기록 삭제", color = MaterialTheme.colorScheme.error)
            }
        }
        if (journalRows.isEmpty()) {
            Text("아직 개입 기록이 없습니다.")
        } else {
            journalRows.forEachIndexed { index, rowText ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ItvLine),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "결과 #${journalRows.size - index}",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            color = ItvGreen
                        )
                        Text(rowText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}


