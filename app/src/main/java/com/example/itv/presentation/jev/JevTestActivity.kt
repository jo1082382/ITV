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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            listOf("연결","판단 설정","개입 기록","실시간 측정 테스트","카테고리 확률 테스트","개입 맥락·말풍선 테스트").forEachIndexed { i,title ->
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
                    4 -> CategoryProbabilityTestPage(vm,s,busy)
                    else -> InterventionContextBubbleTestPage(vm,s,busy)
                }
            }
        }
    }
}
@Composable
internal fun TestSection(title: String,content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,ItvLine),
        colors=CardDefaults.cardColors(containerColor=ItvMint)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            content()
        }
    }
}
@Composable
internal fun CompactCheckGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
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
internal fun CompactCheckRow(label: String, value: String, highlight: Boolean = false) {
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
    val sessionId: String,
    val totalMs: Long,
    val continuousMs: Long,
    val continuousLive: Boolean,
    val swipes: Int,
    val returns: Int,
    val bedtime: Int?,
    val takenAt: Long,
    val enrichedContext: JevContext,
    val liveProbability: Double?,
    val latestTrialProbability: Double?,
    val latestTrialSource: String?,
    val latestLiveProbability: Double?,
    val latestLiveCreatedAt: Long?,
    val latestLiveTrial: JevTrial?,
    val displayBlockingReason: String?,
    val ambientSnapshot: com.example.itv.infrastructure.observation.AmbientSnapshot,
    val bedtimeCareEnabled: Boolean,
    val appSuppression: JevAppSuppressionInfo,
    val additionalViewingNeededSeconds: Long,
    val tokenConfigured: Boolean,
    val isTestModeOn: Boolean,
    val canDrawOverlays: Boolean
) {
    fun context() = enrichedContext
}

private fun takeProbabilitySnapshot(
    context: android.content.Context,
    vm: JevTestViewModel,
    trials: List<JevTrial>
): ProbabilitySnapshot {
    val allSession = vm.runtime.sessionState("ALL")
    val ytSession = vm.runtime.sessionState(JevSettings.YOUTUBE)
    val igSession = vm.runtime.sessionState(JevSettings.INSTAGRAM)
    val devStatus = com.example.itv.domain.coaching.DeveloperStatus.value

    val swipesNow = YoutubeProbe.candidates + ReelsProbe.candidates
    val swipesLast = YoutubeProbe.lastRunCandidates + ReelsProbe.lastRunCandidates

    val allC = allSession?.continuousMs ?: 0L
    val sumC = (ytSession?.continuousMs ?: 0L) + (igSession?.continuousMs ?: 0L)
    val continuousNow = maxOf(allC, maxOf(devStatus.usedMs, sumC))

    val allLast = allSession?.lastRunContinuousMs ?: 0L
    val sumLast = (ytSession?.lastRunContinuousMs ?: 0L) + (igSession?.lastRunContinuousMs ?: 0L)
    val continuousLast = maxOf(allLast, maxOf(devStatus.usedMs, sumLast))

    val latestTrial = trials.firstOrNull { it.probability != null }
    val latestLiveTrial = trials.firstOrNull { it.source == "LIVE" && it.probability != null }
    val sessionId = allSession?.id ?: ytSession?.id ?: igSession?.id ?: UUID.randomUUID().toString()

    val allTotal = allSession?.totalMs ?: 0L
    val sumTotal = (ytSession?.totalMs ?: 0L) + (igSession?.totalMs ?: 0L)
    val totalMs = maxOf(allTotal, maxOf(devStatus.usedMs, sumTotal))
    val continuousMs = if (continuousNow > 0L) continuousNow else continuousLast
    val returns = allSession?.returns?.size ?: ((ytSession?.returns?.size ?: 0) + (igSession?.returns?.size ?: 0))

    val rawShortformMs = maxOf(
        allSession?.rawShortformMs ?: 0L,
        (ytSession?.rawShortformMs ?: 0L) + (igSession?.rawShortformMs ?: 0L)
    )
    val rawGeneralMs = maxOf(
        allSession?.rawGeneralMs ?: 0L,
        (ytSession?.rawGeneralMs ?: 0L) + (igSession?.rawGeneralMs ?: 0L)
    )

    val bedtime = vm.runtime.lastObservedContext?.bedtimePassedMinutes
    val takenAt = System.currentTimeMillis()
    val baseContext = JevContext(
        sessionId = sessionId,
        packageName = "ALL",
        sessionMs = totalMs,
        continuousMs = continuousMs,
        returns10m = returns,
        returnsObservedSeconds = 600,
        bedtimePassedMinutes = bedtime,
        observedAt = takenAt,
        rawShortformMs = rawShortformMs,
        rawGeneralMs = rawGeneralMs
    )
    val enrichedRaw = vm.runtime.enrichContext(baseContext)
    val snapshotSwipes = if (swipesNow > 0) swipesNow else swipesLast
    val enriched = enrichedRaw.copy(
        shortformSwipeCount = maxOf(enrichedRaw.shortformSwipeCount, snapshotSwipes)
    )
    val runtimeState = vm.runtime.state.value
    val suppression = vm.runtime.latestSuppressionInfo()
    val ambient = LightAndBrightnessTracker.getInstance(context).snapshot.value
    val bedtimeCare = com.example.itv.infrastructure.coaching.UserCoachingSettingsStore.getInstance(context).loadSettings().bedtimeCareEnabled
    return ProbabilitySnapshot(
        sessionId = sessionId,
        totalMs = totalMs,
        continuousMs = continuousMs,
        continuousLive = continuousNow > 0L,
        swipes = if (swipesNow > 0) swipesNow else swipesLast,
        returns = returns,
        bedtime = bedtime,
        takenAt = takenAt,
        enrichedContext = enriched,
        liveProbability = runtimeState.probability,
        latestTrialProbability = latestTrial?.probability,
        latestTrialSource = latestTrial?.source,
        latestLiveProbability = runtimeState.probability ?: latestLiveTrial?.probability,
        latestLiveCreatedAt = latestLiveTrial?.createdAt,
        latestLiveTrial = latestLiveTrial,
        displayBlockingReason = vm.runtime.displayBlockingReason(),
        ambientSnapshot = ambient,
        bedtimeCareEnabled = bedtimeCare,
        appSuppression = suppression,
        additionalViewingNeededSeconds = runtimeState.additionalViewingNeededSeconds ?: 0L,
        tokenConfigured = vm.store.tokenConfigured.value,
        isTestModeOn = com.example.itv.presentation.coaching.CoachingAutoStart.testModeEnabled(context),
        canDrawOverlays = android.provider.Settings.canDrawOverlays(context)
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
    return "숏폼/롱폼 전환 대기 중 (시청 중 개입 금지 원칙에 따라 다음 숏폼 스와이프 또는 롱폼 종료·전환 시 표시)"
}

@Composable
private fun LiveContinuousUsageCard(
    vm: JevTestViewModel,
    targetSeconds: Int,
    continuousEnabled: Boolean,
    fallbackMs: Long,
    snapshotTotalMs: Long,
    snapshotReturns: Int,
    rawShortformMs: Long,
    rawGeneralMs: Long
) {
    var liveContinuousMs by remember(fallbackMs) { mutableLongStateOf(fallbackMs) }

    LaunchedEffect(vm, fallbackMs) {
        while (true) {
            val allC = vm.runtime.sessionState("ALL")?.continuousMs ?: 0L
            val devMs = com.example.itv.domain.coaching.DeveloperStatus.value.usedMs
            val current = maxOf(allC, devMs)
            liveContinuousMs = if (current > 0L) current else fallbackMs
            kotlinx.coroutines.delay(1.seconds)
        }
    }

    val liveSec = liveContinuousMs / 1000.0
    val progress = if (continuousEnabled && targetSeconds > 0) {
        (liveSec / targetSeconds.toDouble()).toFloat().coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, ItvLine)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "실시간 연속 사용 (전체 통합)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ItvGreen
                )
                Text(
                    text = "${"%.1f".format(liveSec)}초 / ${if (continuousEnabled) "${targetSeconds}초" else "꺼짐"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (continuousEnabled && liveSec >= targetSeconds) ItvGreen else MaterialTheme.colorScheme.onSurface
                )
            }
            if (continuousEnabled) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = ItvGreen,
                    trackColor = ItvLine
                )
            }
            Text(
                text = "숏폼 1:1 · 피드/SNS·게임 1:2 · 롱폼/일반 1:3 · ITV 앱 일시정지",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "갱신 시점: 숏폼 ${"%.1f".format(rawShortformMs / 1000.0)}초 + 일반 ${"%.1f".format(rawGeneralMs / 1000.0)}초 · 누적 ${"%.1f".format(snapshotTotalMs / 1000.0)}초 · 재진입 ${snapshotReturns}회",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ProbabilityGaugeBar(
    probabilityPct: Double?,
    baseThresholdPct: Double,
    effectiveThresholdPct: Double,
    passed: Boolean
) {
    val probFrac = ((probabilityPct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val baseFrac = (baseThresholdPct / 100.0).toFloat().coerceIn(0f, 1f)
    val effFrac = (effectiveThresholdPct / 100.0).toFloat().coerceIn(0f, 1f)
    val fillColor = when {
        probabilityPct == null -> MaterialTheme.colorScheme.outlineVariant
        passed -> ItvGreen
        else -> Color(0xFFD97706)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
        ) {
            val w = maxWidth
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(ItvLine)
            )
            if (probabilityPct != null && probFrac > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(probFrac)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(fillColor)
                )
            }
            // Base threshold marker (muted)
            if (kotlin.math.abs(baseThresholdPct - effectiveThresholdPct) >= 0.5) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (w * baseFrac - 1.dp).coerceIn(0.dp, w - 2.dp))
                        .width(2.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
                )
            }
            // Effective threshold marker (bold)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (w * effFrac - 1.5.dp).coerceIn(0.dp, w - 3.dp))
                    .width(3.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.onSurface)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val baseLabel = if (kotlin.math.abs(baseThresholdPct - effectiveThresholdPct) >= 0.5) {
                "기본 ${"%.0f".format(baseThresholdPct)}% → 적용 기준 ${"%.0f".format(effectiveThresholdPct)}%"
            } else {
                "적용 기준 ${"%.0f".format(effectiveThresholdPct)}%"
            }
            Text(baseLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("100%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeltaFactorRow(
    title: String,
    rangeHint: String,
    detail: String,
    deltaPct: Double,
    excluded: Boolean = false
) {
    val isNeg = deltaPct <= -0.5
    val isPos = deltaPct >= 0.5
    val chipBg = when {
        excluded -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        isNeg -> Color(0xFFFEE2E2)
        isPos -> Color(0xFFDBEAFE)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val chipFg = when {
        excluded -> MaterialTheme.colorScheme.onSurfaceVariant
        isNeg -> Color(0xFFB91C1C)
        isPos -> Color(0xFF1D4ED8)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val deltaText = when {
        excluded -> "제외"
        isPos -> "+${"%.0f".format(deltaPct)}%p"
        isNeg -> "${"%.0f".format(deltaPct)}%p"
        else -> "0%p"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(rangeHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = chipBg
        ) {
            Text(
                text = deltaText,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = chipFg
            )
        }
    }
}

@Composable
private fun DynamicTierRow(
    tierLabel: String,
    gapRange: String,
    intervalText: String,
    active: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (active) ItvGreen.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) ItvGreen else ItvLine)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tierLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) ItvGreen else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = gapRange,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = intervalText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (active) ItvGreen else MaterialTheme.colorScheme.onSurface
                )
                if (active) {
                    Surface(shape = RoundedCornerShape(6.dp), color = ItvGreen) {
                        Text(
                            text = "현재",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ObservationTestPage(vm: JevTestViewModel, busy: Boolean) {
    val s by vm.store.settings.collectAsState()
    val answer by vm.manualAnswer.collectAsState()
    val trials by vm.store.dao.trials().collectAsState(initial = emptyList())
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf<ProbabilitySnapshot?>(null) }
    var showDetails by rememberSaveable { mutableStateOf(false) }

    // Initial load takes one snapshot; subsequent updates happen only when pressing the refresh button.
    LaunchedEffect(trials.size) {
        if (snapshot == null || (snapshot?.latestLiveProbability == null && trials.isNotEmpty())) {
            snapshot = takeProbabilitySnapshot(context, vm, trials)
        }
    }

    TestSection("실시간 측정 테스트 (전체 통합)") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(8.dp), color = ItvGreen.copy(alpha = 0.14f)) {
                Text(
                    text = "전체 통합 기준",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ItvGreen
                )
            }
            snapshot?.let {
                val takenAt = Instant.ofEpochMilli(it.takenAt).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                Text(
                    text = "마지막 갱신 $takenAt",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(
            onClick = { snapshot = takeProbabilitySnapshot(context, vm, trials) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("측정값 갱신 (토큰 사용 없음)")
        }

        val snap = snapshot
        if (snap == null) {
            Text("갱신 버튼을 눌러 측정값을 불러오세요.")
        } else {
            val ctx = snap.context()
            val adj = JevCandidatePolicy.computeThresholdAdjustment(s, ctx)
            val baseThresholdPct = s.probabilityThreshold * 100.0
            val effThresholdPct = adj.effectiveThreshold * 100.0
            val totalDeltaPct = effThresholdPct - baseThresholdPct
            val matchedReasons = JevCandidatePolicy.reasons(ctx, s)
            val reasonKor = matchedReasons.map { r ->
                when (r) {
                    "CONTINUOUS" -> "연속시청"
                    "REPEATED_RETURNS" -> "반복재진입"
                    "BEDTIME" -> "취침시간"
                    "PERSONAL_SESSION_EXCEEDED" -> "평균세션초과"
                    "RAPID_SWIPE_OVERHEAT" -> "스와이프과열"
                    "PRE_BEDTIME_DARK_USAGE" -> "취침전암실사용"
                    "CONTINUOUS_FATIGUE" -> "연속피로누적"
                    "LONGFORM_BINGE_CHAIN" -> "롱폼영상연속전환"
                    "SHORTFORM_SWIPE_COUNT" -> "숏폼누적스와이프"
                    else -> r
                }
            }

            val callBlocker = when {
                !s.enabled -> "Jev 테스트 꺼짐 (판단 설정 탭에서 켜기 필요)"
                snap.isTestModeOn -> "30초 테스트 모드 켜짐 (끄면 Jev 자동 호출)"
                !s.collectionConsent -> "외부 전송 미동의 (연결 탭에서 동의 필요)"
                !snap.tokenConfigured -> "테스트 토큰 미등록 (연결 탭에서 저장 필요)"
                else -> null
            }
            if (callBlocker != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "호출 차단: $callBlocker",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // 연속 사용 초만 실시간으로 증가 표시 (나머지 지표는 갱신 버튼 기준 고정)
            LiveContinuousUsageCard(
                vm = vm,
                targetSeconds = s.continuousSeconds,
                continuousEnabled = s.continuousEnabled,
                fallbackMs = snap.continuousMs,
                snapshotTotalMs = snap.totalMs,
                snapshotReturns = snap.returns,
                rawShortformMs = ctx.rawShortformMs,
                rawGeneralMs = ctx.rawGeneralMs
            )

            // 1. 개입 판단 확률 (한눈에 보는 판정 카드)
            val evalProb = answer?.probability ?: snap.latestLiveProbability ?: snap.latestTrialProbability
            val evalProbPct = evalProb?.times(100.0)
            val probSourceLabel = when {
                answer != null -> "방금 수동 요청 (${answer!!.trial.latencyMs}ms)"
                snap.latestLiveProbability != null -> {
                    val t = snap.latestLiveCreatedAt?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    }
                    if (t != null) "LIVE · $t" else "LIVE 최신"
                }
                snap.latestTrialProbability != null -> "최근 요청 (${snap.latestTrialSource ?: "기록"})"
                else -> "아직 기록 없음"
            }
            val passed = evalProb != null && JevCandidatePolicy.passes(evalProb, s, ctx)
            val diffPct = evalProbPct?.minus(effThresholdPct)

            CompactCheckGroup("1. 개입 판단 확률") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("Jev 판단 확률", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = evalProbPct?.let { "${"%.1f".format(it)}%" } ?: "--%",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = when {
                                    evalProbPct == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    passed -> ItvGreen
                                    else -> Color(0xFFD97706)
                                }
                            )
                            Text(
                                text = probSourceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("적용 개입 기준", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "${"%.0f".format(effThresholdPct)}%",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val deltaCaption = if (!s.personalizedEnabled) {
                                "보정 꺼짐"
                            } else if (kotlin.math.abs(totalDeltaPct) >= 0.5) {
                                "기본 ${"%.0f".format(baseThresholdPct)}% (${if (totalDeltaPct > 0) "+" else ""}${"%.0f".format(totalDeltaPct)}%p)"
                            } else {
                                "기본 ${"%.0f".format(baseThresholdPct)}% (변동 없음)"
                            }
                            Text(
                                text = deltaCaption,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // 판정 요약 배너
                val verdictBg = when {
                    diffPct == null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    passed -> ItvGreen.copy(alpha = 0.16f)
                    else -> Color(0xFFFEF3C7)
                }
                val verdictFg = when {
                    diffPct == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    passed -> ItvGreen
                    else -> Color(0xFFB45309)
                }
                val verdictText = when {
                    diffPct == null -> "확률 산출 대기 · 호출 조건 ${if (reasonKor.isNotEmpty()) "충족 (${reasonKor.joinToString(", ")})" else "미충족"}"
                    passed -> "개입 기준 통과 (+${"%.1f".format(diffPct)}%p 초과) · 개입 대상"
                    else -> "개입 기준 미달 (${"%.1f".format(diffPct)}%p 부족) · 미개입"
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = verdictBg
                ) {
                    Text(
                        text = verdictText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = verdictFg,
                        textAlign = TextAlign.Center
                    )
                }

                ProbabilityGaugeBar(
                    probabilityPct = evalProbPct,
                    baseThresholdPct = baseThresholdPct,
                    effectiveThresholdPct = effThresholdPct,
                    passed = passed
                )

                CompactCheckRow(
                    label = "호출 조건",
                    value = if (reasonKor.isNotEmpty()) "충족 (${reasonKor.joinToString(", ")})" else "미충족",
                    highlight = reasonKor.isNotEmpty()
                )

                val displayTrial = answer?.trial ?: snap.latestLiveTrial
                if (passed && displayTrial != null) {
                    val displayedAt = displayTrial.displayedAt
                    val isDisplayed = displayedAt != null
                    val displayText = if (isDisplayed) {
                        val dispTime = Instant.ofEpochMilli(displayedAt)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        "표시 완료 ($dispTime)"
                    } else {
                        val reason = snap.displayBlockingReason ?: resolveNotShownReason(
                            trial = displayTrial,
                            isActualIntervention = s.actualIntervention,
                            isTestMode = snap.isTestModeOn,
                            isJevEnabled = s.enabled,
                            canDrawOverlays = snap.canDrawOverlays
                        )
                        "미표시 · $reason"
                    }
                    CompactCheckRow(
                        label = "개입 표시 상태",
                        value = displayText,
                        highlight = !isDisplayed
                    )
                }
            }

            // 2. 개입 기준 확률 변동 (보정 내역 한눈에 보기)
            CompactCheckGroup("2. 개입 기준 확률 변동") {
                val totalSign = if (totalDeltaPct > 0) "+" else ""
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (s.personalizedEnabled) {
                                "기본 ${"%.0f".format(baseThresholdPct)}%  ${totalSign}${"%.0f".format(totalDeltaPct)}%p  →  적용 ${"%.0f".format(effThresholdPct)}%"
                            } else {
                                "기본 ${"%.0f".format(baseThresholdPct)}% 고정 (개인화 동적 보정 꺼짐)"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (totalDeltaPct <= -0.5) Color(0xFFB91C1C) else if (totalDeltaPct >= 0.5) Color(0xFF1D4ED8) else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "마이너스(-%p)일수록 개입이 더 빨라지고, 플러스(+%p)일수록 기준이 완화됩니다. (총 보정 -40~+25%p)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val sessionRatioStr = ctx.sessionVsAvgRatio?.let { "평균 대비 ${(it * 100).roundToInt()}%" } ?: "집계 중"
                val avgSessionStr = ctx.personalAvgSessionMinutes?.let {
                    val totalSec = (it * 60).roundToInt()
                    val m = totalSec / 60
                    val sec = totalSec % 60
                    if (sec > 0) "기준 ${m}분 ${sec}초" else "기준 ${m}분"
                } ?: "기준 수집 중"
                DeltaFactorRow(
                    title = "세션 과몰입",
                    rangeHint = "±10%p",
                    detail = "$sessionRatioStr ($avgSessionStr)",
                    deltaPct = adj.sessionDelta * 100.0
                )

                HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))
                val bedtimeDetail = when {
                    ctx.isNearOrOccluded -> "센서 가림 감지 (0cm 근접 · 보정 제외)"
                    adj.bedtimeDelta < 0 -> {
                        val distStr = ctx.bedtimeDistanceMinutes?.let { if (it < 0) "취침 ${-it}분 전" else "취침 ${it}분 경과" } ?: "취침 임박"
                        "암실 시청 · $distStr (${ctx.ambientLux?.toInt() ?: 0}lx · 밝기 ${ctx.screenBrightness ?: 0})"
                    }
                    adj.bedtimeDelta > 0 -> "주간/밝은 환경 (${ctx.ambientLux?.toInt() ?: 0}lx · 밝기 ${ctx.screenBrightness ?: 0})"
                    else -> "일반 실내 조명 (${ctx.ambientLux?.toInt() ?: 0}lx · 밝기 ${ctx.screenBrightness ?: 0})"
                }
                DeltaFactorRow(
                    title = "취침환경 · 조도",
                    rangeHint = "-30~+15%p",
                    detail = bedtimeDetail,
                    deltaPct = adj.bedtimeDelta * 100.0
                )

                HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))
                val fatigueMin = ctx.continuousMs / 60000.0
                val fatigueDetail = when {
                    fatigueMin >= 30.0 -> "고위험 (${"%.1f".format(fatigueMin)}분 연속)"
                    fatigueMin >= 15.0 -> "주의 (${"%.1f".format(fatigueMin)}분 연속)"
                    else -> "정상 (${"%.1f".format(fatigueMin)}분 연속 · 15분 미만 0%p)"
                }
                DeltaFactorRow(
                    title = "연속 시청 피로",
                    rangeHint = "0~-4%p",
                    detail = fatigueDetail,
                    deltaPct = adj.fatigueDelta * 100.0
                )

                HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))
                val lfSwitchStr = if (adj.longformSwitchDelta < 0.0) {
                    "전환 ${ctx.consecutiveLongformChainCount}회 (${"%.1f".format(adj.longformSwitchDelta * 100)}%p)"
                } else {
                    "전환 ${ctx.consecutiveLongformChainCount}회 (2회+ 적용)"
                }
                val sfSwipeStr = if (adj.shortformSwipeCountDelta < 0.0) {
                    "스와이프 ${ctx.shortformSwipeCount}회 (${"%.1f".format(adj.shortformSwipeCountDelta * 100)}%p)"
                } else {
                    "스와이프 ${ctx.shortformSwipeCount}회 (11회+ 적용)"
                }
                DeltaFactorRow(
                    title = "롱폼 전환 · 숏폼 스와이프",
                    rangeHint = "0~-10%p",
                    detail = "유튜브 롱폼 $lfSwitchStr · 숏폼 $sfSwipeStr",
                    deltaPct = adj.contentSwitchDelta * 100.0
                )
            }

            // 3. 동적 재평가 확률 변동 (격차에 따른 재평가 간격 변화 한눈에 보기)
            val appSuppression = snap.appSuppression
            val isLongformOrAll = ctx.rawGeneralMs >= ctx.rawShortformMs
            val activeGap = when {
                evalProb != null -> ((effThresholdPct / 100.0) - evalProb).coerceAtLeast(0.0)
                appSuppression.isAsked && appSuppression.lastProbabilityGap != null -> appSuppression.lastProbabilityGap
                else -> null
            }
            val effectiveIntervalSec = when {
                answer != null -> JevCandidatePolicy.calculateDynamicInterval(effThresholdPct / 100.0, answer!!.probability, isLongformOrAll)
                appSuppression.isAsked -> appSuppression.dynamicIntervalSeconds
                evalProb != null -> JevCandidatePolicy.calculateDynamicInterval(effThresholdPct / 100.0, evalProb, isLongformOrAll)
                else -> appSuppression.dynamicIntervalSeconds
            }
            val nextInSec = appSuppression.nextEvaluationInSeconds ?: 0L
            val addSec = snap.additionalViewingNeededSeconds

            CompactCheckGroup("3. 동적 재평가 확률 변동") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "재평가 간격 ${effectiveIntervalSec}초",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = ItvGreen
                            )
                            Text(
                                text = if (isLongformOrAll) "일반 환산 약 ${"%.1f".format(effectiveIntervalSec * 3.0 / 60.0)}분" else "숏폼 약 ${"%.1f".format(effectiveIntervalSec / 60.0)}분",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val gapSummary = when {
                            activeGap != null && evalProb != null && evalProb * 100.0 >= effThresholdPct ->
                                "기준 통과 상태 (격차 0.0%p) → 최소 간격 ${effectiveIntervalSec}초 적용"
                            activeGap != null ->
                                "기준과 격차 -${"%.1f".format(activeGap * 100.0)}%p → 선형 보간 ${effectiveIntervalSec}초 적용"
                            else ->
                                "첫 실시간 평가 전 기본값 ${effectiveIntervalSec}초 (평가 후 격차에 따라 자동 변동)"
                        }
                        Text(
                            text = gapSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, ItvLine)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("다음 재평가 대기", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = if (nextInSec > 0) "${nextInSec}초 남음" else "대기 충족",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (nextInSec > 0) MaterialTheme.colorScheme.onSurface else ItvGreen
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, ItvLine)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("필요 추가 시청", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = if (addSec > 0) "${addSec}초 남음" else "시청량 충족",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (addSec > 0) MaterialTheme.colorScheme.onSurface else ItvGreen
                            )
                        }
                    }
                }

                val activeTier = activeGap?.let { g ->
                    when {
                        g <= 0.0 -> 0
                        g <= 0.15 -> 1
                        g <= 0.30 -> 2
                        else -> 3
                    }
                } ?: -1

                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = if (isLongformOrAll) "확률 격차별 재평가 변동표 (일반/통합 기준)" else "확률 격차별 재평가 변동표 (숏폼 1:1 기준)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DynamicTierRow(
                        tierLabel = "통과/초과",
                        gapRange = "격차 0%p",
                        intervalText = if (isLongformOrAll) "60초 (실사용 3분)" else "30초 (0.5분)",
                        active = activeTier == 0
                    )
                    DynamicTierRow(
                        tierLabel = "근접",
                        gapRange = "0 ~ 15%p",
                        intervalText = if (isLongformOrAll) "60~120초 (3~6분)" else "30~90초 (0.5~1.5분)",
                        active = activeTier == 1
                    )
                    DynamicTierRow(
                        tierLabel = "중간",
                        gapRange = "15 ~ 30%p",
                        intervalText = if (isLongformOrAll) "120~240초 (6~12분)" else "90~210초 (1.5~3.5분)",
                        active = activeTier == 2
                    )
                    DynamicTierRow(
                        tierLabel = "원거리",
                        gapRange = "30%p 이상",
                        intervalText = if (isLongformOrAll) "240~360초 (12~18분)" else "210~360초 (3.5~6분)",
                        active = activeTier == 3
                    )
                }
            }

            // 4. 세부 관측 맥락 (접기/펼치기 - 갱신 시점 고정)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, ItvLine)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDetails = !showDetails },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "세부 관측 맥락 (물리 시간 · 롱폼 · 취침 점수 · 각도)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = ItvGreen
                        )
                        Text(
                            text = if (showDetails) "접기 ▴" else "펼치기 ▾",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (showDetails) {
                        val rawPhysicalMs = ctx.rawShortformMs + ctx.rawGeneralMs
                        val rawLowMs = (ctx.rawGeneralMs - ctx.rawMidWeightMs).coerceAtLeast(0L)
                        val sfRatio = if (rawPhysicalMs > 0L) (ctx.rawShortformMs.toDouble() / rawPhysicalMs.toDouble()) * 100.0 else 0.0
                        val weightedMs = ctx.rawShortformMs + (ctx.rawMidWeightMs / 2L) + (rawLowMs / 3L)
                        CompactCheckRow(
                            label = "물리 사용 시간",
                            value = "${"%.1f".format(rawPhysicalMs / 1000.0)}초 (숏폼 ${"%.1f".format(ctx.rawShortformMs / 1000.0)}초 + 피드·게임 ${"%.1f".format(ctx.rawMidWeightMs / 1000.0)}초 + 롱폼·일반 ${"%.1f".format(rawLowMs / 1000.0)}초)"
                        )
                        CompactCheckRow(
                            label = "숏폼 비중 / 환산",
                            value = "${"%.1f".format(sfRatio)}% · 환산 ${"%.1f".format(weightedMs / 1000.0)}초 (1:1 / 1:2 / 1:3)"
                        )
                        CompactCheckRow(
                            label = "롱폼 시청 분석",
                            value = "롱폼 ${"%.0f".format(ctx.longformWatchMs / 1000.0)}초 · 현재영상 ${"%.0f".format(ctx.currentVideoWatchMs / 1000.0)}초 · 완료 ${ctx.completedLongformCount}편 (${ctx.youtubeWatchMode ?: "대기"})"
                        )
                        val amb = snap.ambientSnapshot
                        CompactCheckRow(
                            label = "취침 점수 (60점+)",
                            value = "${amb.bedtimeScore}점 / 100점 · ${if (amb.isBedtimeCandidate) "취침 케어 대상" else "일상 활동"} (${if (snap.bedtimeCareEnabled) "설정 ON" else "설정 OFF"})",
                            highlight = amb.isBedtimeCandidate
                        )
                        CompactCheckRow(
                            label = "취침 점수 내역",
                            value = amb.bedtimeScoreBreakdown
                        )
                        val postureStr = when (amb.posture) {
                            UserPosture.LYING_DOWN -> "수평/하향 (${amb.pitchDegrees?.toInt() ?: 0}° · 경추 고하중)"
                            UserPosture.SITTING_OR_STANDING -> "정면 파지 (${amb.pitchDegrees?.toInt() ?: 0}° · 어깨/허리)"
                            UserPosture.TABLE_OR_DESK -> "테이블 거치 (${amb.pitchDegrees?.toInt() ?: 0}° · 고개 숙임)"
                            UserPosture.FACEDOWN -> "엎어둠 (완전 차단)"
                            else -> "각도 측정 중 (${amb.pitchDegrees?.toInt() ?: 0}°)"
                        }
                        CompactCheckRow(label = "파지 각도", value = postureStr)
                    }
                }
            }
        }

        Button(
            enabled = !busy && s.collectionConsent && snap != null,
            onClick = { snap?.let { vm.manual(it.context()) } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("현재 전체 측정값으로 Jev 확률 요청 (토큰 1회)")
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

@Composable
private fun LongformObservationPage() {
    val context = LocalContext.current
    val snapshot by com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.snapshot.collectAsState()
    val history by com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.history.collectAsState()
    val igSnapshot by com.example.itv.infrastructure.observation.instagram.InstagramTracker.snapshot.collectAsState()
    val igHistory by com.example.itv.infrastructure.observation.instagram.InstagramTracker.history.collectAsState()
    val genHistory by com.example.itv.infrastructure.observation.GeneralAppTracker.history.collectAsState()
    var refreshTick by remember { mutableLongStateOf(0L) }
    val lastSession = com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.lastWatchedSession
    val lastIgSession = com.example.itv.infrastructure.observation.instagram.InstagramTracker.lastSessionEvent
    val lastGenSession = com.example.itv.infrastructure.observation.GeneralAppTracker.lastExternalAppSession
    var hasHudPermissions by remember {
        mutableStateOf(com.example.itv.presentation.coaching.LongformDiagnosticHud.hasRequiredPermissions(context))
    }
    var hudShowing by remember {
        mutableStateOf(com.example.itv.presentation.coaching.LongformDiagnosticHud.isShowing && hasHudPermissions)
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            refreshTick = android.os.SystemClock.elapsedRealtime()
            com.example.itv.presentation.coaching.LongformDiagnosticHud.hideIfPermissionsRevoked(context)
            hasHudPermissions = com.example.itv.presentation.coaching.LongformDiagnosticHud.hasRequiredPermissions(context)
            hudShowing = com.example.itv.presentation.coaching.LongformDiagnosticHud.isShowing
        }
    }

    TestSection("통합 실시간 플로팅 HUD (유튜브 · 인스타 · 모든 앱 활동 & 개입 판정)") {
        Text(
            "기본적으로 화면을 가리지 않도록 우리 앱(ITV) 아이콘 모양의 작은 플로팅 버튼(개입 가능/대기/불가 상태 색상 테두리 표시)으로 떠 있으며, 터치하면 상세 분석 HUD(유튜브·인스타·모든 앱의 4계층 카테고리 확정 근거, 게임/영상/음성 오디오 파이프라인, 앱 내 화면, 개입 판정)로 자유롭게 크게/작게 전환할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall
        )

        Button(
            onClick = {
                if (!hasHudPermissions) return@Button
                if (hudShowing) {
                    com.example.itv.presentation.coaching.LongformDiagnosticHud.hide()
                    hudShowing = false
                } else {
                    val success = com.example.itv.presentation.coaching.LongformDiagnosticHud.show(context)
                    if (success) {
                        hudShowing = true
                        android.widget.Toast.makeText(context, "실시간 플로팅 HUD가 활성화되었습니다. 다른 앱을 실행해보세요!", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        hasHudPermissions = com.example.itv.presentation.coaching.LongformDiagnosticHud.hasRequiredPermissions(context)
                    }
                }
            },
            enabled = hasHudPermissions,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hudShowing) MaterialTheme.colorScheme.error else ItvGreen,
                disabledContainerColor = androidx.compose.ui.graphics.Color(0xFF4B5563),
                disabledContentColor = androidx.compose.ui.graphics.Color(0xFF9CA3AF)
            )
        ) {
            Text(
                when {
                    !hasHudPermissions -> "플로팅 HUD 사용 불가 (권한 필요)"
                    hudShowing -> "실시간 플로팅 HUD 끄기"
                    else -> "통합 실시간 플로팅 HUD 켜기"
                },
                fontWeight = FontWeight.Bold
            )
        }

        if (!hasHudPermissions) {
            Text(
                text = com.example.itv.presentation.coaching.LongformDiagnosticHud.missingPermissionSummary(context),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    com.example.itv.presentation.sleep.NightGentleAlertController.showGentleBanner(context, isTestMode = true)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF4F46E5))
            ) {
                Text("🌙 비침해 배너(30초)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    if (!hasHudPermissions) return@Button
                    val hud = com.example.itv.presentation.coaching.LongformDiagnosticHud
                    if (!hud.isShowing) {
                        hud.show(context)
                        hudShowing = hud.isShowing
                    }
                    if (hud.isShowing) {
                        hud.setSleepingState(!hud.isSleeping)
                    }
                },
                enabled = hasHudPermissions,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF7C3AED),
                    disabledContainerColor = androidx.compose.ui.graphics.Color(0xFF4B5563),
                    disabledContentColor = androidx.compose.ui.graphics.Color(0xFF9CA3AF)
                )
            ) {
                Text("💤 HUD 수면 토글", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Jev 확률 vs 앱 기준 확률 차이 5단계 HUD 표정 미리보기
        var previewMood by remember { mutableStateOf<com.example.itv.presentation.components.PuppyMood?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "🐾 HUD 5단계 표정 미리보기 (실제 실행 중에는 Jev 확률과 기준 확률 차이에 따라 자동 변경)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val moodItems = listOf<Pair<String, com.example.itv.presentation.components.PuppyMood?>>(
                "자동" to null,
                "해맑" to com.example.itv.presentation.components.PuppyMood.BRIGHT,
                "웃음" to com.example.itv.presentation.components.PuppyMood.SMILE,
                "슬픔" to com.example.itv.presentation.components.PuppyMood.SAD,
                "화남" to com.example.itv.presentation.components.PuppyMood.ANGRY,
                "아픔" to com.example.itv.presentation.components.PuppyMood.SICK
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                moodItems.forEach { (label, mood) ->
                    val selected = previewMood == mood
                    OutlinedButton(
                        onClick = {
                            if (!hasHudPermissions) return@OutlinedButton
                            val hud = com.example.itv.presentation.coaching.LongformDiagnosticHud
                            if (!hud.isShowing) {
                                hud.show(context)
                                hudShowing = hud.isShowing
                            }
                            if (hud.isShowing) {
                                if (mood != null && hud.isSleeping) {
                                    hud.setSleepingState(false)
                                }
                                previewMood = mood
                                hud.setPreviewMoodOverride(context, mood)
                            }
                        },
                        enabled = hasHudPermissions,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
                        )
                    ) {
                        Text(
                            label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("💡 유튜브·인스타그램·타 앱과 함께 감지 결과를 보는 3가지 방법", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text("1. [플로팅 HUD]: 위 버튼을 켜면 화면 위에 미니 창이 떠서 모든 앱 사용 중 실시간 갱신됩니다.", style = MaterialTheme.typography.bodySmall)
                Text("2. [삼성 팝업 화면]: 최근 실행 앱 목록에서 ITV 앱 아이콘을 길게 누른 뒤 '팝업 화면으로 열기'를 선택하면 창 크기를 조절하여 띄워둘 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                Text("3. [화면 분할]: 최근 실행 앱 목록에서 '분할 화면으로 열기'를 선택하여 위에는 대상 앱, 아래는 ITV를 띄웁니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    TestSection("최근 앱 복귀 시청/활동 기록 (유튜브 · 인스타그램 · 일반 앱)") {
        Text(
            "유튜브, 인스타그램 또는 다른 앱을 사용하다가 최근 실행 앱(작업전환) 키를 눌러 ITV 앱으로 복귀했을 때, 직전까지 보던 화면과 개입 가능 여부가 자동으로 기록됩니다.",
            style = MaterialTheme.typography.bodySmall
        )
        if (lastSession != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.5.dp, ItvGreen),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = RoundedCornerShape(6.dp), color = ItvMint) {
                            Text(
                                "YouTube 직전 시청 기록",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = ItvGreen
                            )
                        }
                        Text(
                            "복귀: ${lastSession.formattedTime}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        lastSession.previousTitle ?: "제목 감지되지 않음",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val watchedSec = lastSession.watchedDurationMs / 1000L
                    val watchedStr = if (watchedSec >= 60) "${watchedSec / 60}분 ${watchedSec % 60}초" else "${watchedSec}초"
                    CompactCheckRow(label = "시청 모드", value = lastSession.fromMode.label)
                    CompactCheckRow(label = "시청 지속 시간", value = watchedStr)
                    CompactCheckRow(label = "전환 감지 내용", value = lastSession.description)
                }
            }
        }
        if (lastIgSession != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                            Text(
                                "Instagram 직전 화면 기록",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            "복귀: ${lastIgSession.formattedTime}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        lastIgSession.fromMode.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val reelsSec = lastIgSession.reelsWatchDurationMs / 1000L
                    val reelsStr = if (reelsSec >= 60) "${reelsSec / 60}분 ${reelsSec % 60}초" else "${reelsSec}초"
                    CompactCheckRow(label = "누적 릴스 시청", value = "$reelsStr (스와이프 ${lastIgSession.reelsSwipeCount}회)")
                    CompactCheckRow(label = "전환 감지 내용", value = lastIgSession.description)
                }
            }
        }
        if (lastGenSession != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)) {
                            Text(
                                "기타 앱 직전 활동 기록",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Text(
                            "기록: ${lastGenSession.formattedTime}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${lastGenSession.category.badge} · ${lastGenSession.appName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    CompactCheckRow(label = "확정/추론된 활동", value = lastGenSession.inferredActivity)
                    CompactCheckRow(label = "감지된 앱 내 화면", value = lastGenSession.inferredScreen)
                    if (lastGenSession.classificationBasis.isNotBlank()) {
                        CompactCheckRow(label = "판별 근거", value = lastGenSession.classificationBasis)
                    }
                    CompactCheckRow(
                        label = "개입 환경 판정",
                        value = "${lastGenSession.eligibility.badge} (${lastGenSession.eligibilityReason})",
                        highlight = lastGenSession.eligibility == com.example.itv.infrastructure.observation.InterventionEligibility.ELIGIBLE
                    )
                }
            }
        }
        if (lastSession == null && lastIgSession == null && lastGenSession == null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    "아직 앱 사용 복귀 기록이 없습니다.\n유튜브·인스타·다른 앱을 사용하다가 최근 실행 앱 키를 눌러 ITV로 돌아오면 여기에 즉시 기록됩니다.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (genHistory.isNotEmpty()) {
        TestSection("기타 앱 활동 추론 & 개입 가능 환경 로그 (최근 20건)") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        com.example.itv.infrastructure.observation.GeneralAppTracker.clearHistory()
                        refreshTick = android.os.SystemClock.elapsedRealtime()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("기타 앱 기록 비우기", color = MaterialTheme.colorScheme.error)
                }
            }
            genHistory.take(20).forEach { event ->
                val isEligible = event.eligibility == com.example.itv.infrastructure.observation.InterventionEligibility.ELIGIBLE
                val tagColor = if (isEligible) ItvGreen else MaterialTheme.colorScheme.error
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ItvLine),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = RoundedCornerShape(4.dp), color = tagColor.copy(alpha = 0.15f)) {
                                Text(
                                    "${event.category.badge} · ${event.eligibility.badge}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = tagColor
                                )
                            }
                            Text(event.formattedTime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${event.appName}: ${event.inferredActivity}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text("• 판정 사유: ${event.eligibilityReason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    TestSection("실시간 인스타그램 관측 상태 (홈/피드 · 릴스 · 스토리 · DM · 댓글)") {
        CompactCheckGroup("현재 인스타그램 화면 상태") {
            CompactCheckRow(
                label = "현재 인스타 화면 모드",
                value = igSnapshot.mode.label,
                highlight = igSnapshot.isInInstagram
            )
            CompactCheckRow(
                label = "릴스(Reels) 활성 여부",
                value = if (igSnapshot.isReelsActive) "🎬 릴스 재생 중 (스와이프 ${igSnapshot.reelsSwipeCount}회)" else "비활성",
                highlight = igSnapshot.isReelsActive
            )
            CompactCheckRow(
                label = "DM / 작성 보호 상태",
                value = if (igSnapshot.isDmProtected) "🛡 DM 또는 키보드 활성 (개입 자동 차단)" else "보호 조건 없음",
                highlight = igSnapshot.isDmProtected
            )
            val reelsSec = igSnapshot.reelsWatchDurationMs / 1000L
            val reelsStr = if (reelsSec >= 60) "${reelsSec / 60}분 ${reelsSec % 60}초" else "${reelsSec}초"
            CompactCheckRow(
                label = "누적 릴스 시청 시간",
                value = "$reelsStr (스와이프 전환 ${igSnapshot.reelsSwipeCount}회)"
            )
            val modeSec = igSnapshot.currentModeDurationMs / 1000L
            val modeStr = if (modeSec >= 60) "${modeSec / 60}분 ${modeSec % 60}초" else "${modeSec}초"
            CompactCheckRow(
                label = "현재 화면 체류 시간",
                value = if (igSnapshot.isInInstagram) modeStr else "0초 (외부 화면)"
            )
            CompactCheckRow(
                label = "절전 스캔 모드",
                value = if (igSnapshot.isPowerSaveScanMode) "🔋 절전 스캔 동작 중 (1.5초 주기)" else "일반 반응형 스캔 (0.1초)"
            )
            CompactCheckRow(
                label = "상세 상태",
                value = igSnapshot.detail
            )
        }
    }

    TestSection("실시간 유튜브 관측 상태") {
        CompactCheckGroup("현재 화면 상태 및 영상 정보") {
            val isWatching = snapshot.mode == com.example.itv.infrastructure.observation.youtube.YoutubeWatchMode.LANDSCAPE_FULLSCREEN ||
                snapshot.mode == com.example.itv.infrastructure.observation.youtube.YoutubeWatchMode.PORTRAIT_WATCH
            CompactCheckRow(
                label = "현재 화면 모드",
                value = snapshot.mode.label,
                highlight = isWatching
            )
            CompactCheckRow(
                label = "인식된 영상 제목",
                value = snapshot.currentTitle?.take(35)?.let { if (it.length >= 35) "$it…" else it } ?: "제목 미감지 (대기 중)"
            )
            CompactCheckRow(
                label = "실제 재생 상태",
                value = when {
                    snapshot.isEndscreenDetected -> "🏁 영상 종료 (엔드스크린)"
                    isWatching && snapshot.isPlaying -> "▶ 재생 중 (시청 시간 카운트 중)"
                    isWatching && !snapshot.isPlaying -> "⏸ 일시정지됨 (시청 시간 멈춤)"
                    else -> "⏹ 시청 중 아님 (타이머 정지)"
                },
                highlight = isWatching && snapshot.isPlaying
            )
            val watchSec = snapshot.currentVideoWatchDurationMs / 1000L
            val watchStr = if (watchSec >= 60) "${watchSec / 60}분 ${watchSec % 60}초" else "${watchSec}초"
            CompactCheckRow(
                label = "실제 재생 시청 시간",
                value = if (isWatching || watchSec > 0L) "$watchStr (일시정지·타앱 제외)" else "0초"
            )
            CompactCheckRow(
                label = "유튜브 재생바 위치",
                value = snapshot.playbackPositionText ?: "재생바 숨김 (화면 터치 시 표시)"
            )
            CompactCheckRow(
                label = "화면 방향",
                value = if (snapshot.isLandscape) "가로 모드 (Landscape)" else "세로 모드 (Portrait)"
            )
            CompactCheckRow(
                label = "상세 상태",
                value = snapshot.detail
            )
        }
    }

    TestSection("인스타그램 화면 전환 이벤트 로그 (최근 100건)") {
        Text(
            "인스타 홈/피드, 릴스, 댓글, 스토리, DM, 탐색, 프로필 전환 및 우리 앱↔인스타 복귀 시나리오가 시간순으로 기록됩니다.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { refreshTick = android.os.SystemClock.elapsedRealtime() },
                modifier = Modifier.weight(1f)
            ) {
                Text("기록 새로고침")
            }
            TextButton(
                onClick = {
                    com.example.itv.infrastructure.observation.instagram.InstagramTracker.clearHistory()
                    refreshTick = android.os.SystemClock.elapsedRealtime()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("인스타 기록 비우기", color = MaterialTheme.colorScheme.error)
            }
        }
        if (igHistory.isEmpty()) {
            Text("아직 기록된 인스타그램 전환 이벤트가 없습니다.\n인스타그램을 실행하고 피드·릴스·스토리·DM 등을 이동해보세요.", style = MaterialTheme.typography.bodySmall)
        } else {
            igHistory.forEach { event ->
                val tagColor = when (event.eventType) {
                    "IG_REELS", "IG_REELS_SWIPE" -> MaterialTheme.colorScheme.error
                    "IG_DM" -> ItvGreen
                    "IG_STORY" -> MaterialTheme.colorScheme.primary
                    "IG_COMMENTS" -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val tagLabel = when (event.eventType) {
                    "IG_REELS" -> "인스타 릴스 진입"
                    "IG_REELS_SWIPE" -> "릴스 스와이프 전환"
                    "IG_COMMENTS" -> "인스타 댓글 열람"
                    "IG_STORY" -> "인스타 스토리 시청"
                    "IG_DM" -> "인스타 DM (보호)"
                    "IG_HOME_FEED" -> "인스타 홈/피드"
                    "IG_EXPLORE" -> "인스타 검색/탐색"
                    "IG_PROFILE" -> "인스타 프로필"
                    "IG_TO_ITV" -> "인스타 → 우리 앱 복귀"
                    "ITV_TO_IG" -> "우리 앱 → 인스타 복귀"
                    "IG_EXITED_TO_HOME" -> "홈 화면 복귀 (이탈)"
                    "ENTERED_IG" -> "홈 → 인스타 진입"
                    else -> event.eventType
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ItvLine),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = RoundedCornerShape(4.dp), color = tagColor.copy(alpha = 0.15f)) {
                                Text(
                                    tagLabel,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = tagColor
                                )
                            }
                            Text(event.formattedTime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(event.description, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    TestSection("유튜브 롱폼 전환 & 시청 이벤트 로그 (최근 100건)") {
        Text(
            "우리 앱↔유튜브 복귀, 휴대폰 홈 화면 복귀, 세로 시청, 가로 시청, 유튜브 홈 이동, 영상 종료(한 영상 끝), 다음 영상 전환 시나리오가 상태 변화 시점마다 1회씩 기록됩니다.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { refreshTick = android.os.SystemClock.elapsedRealtime() },
                modifier = Modifier.weight(1f)
            ) {
                Text("기록 새로고침")
            }
            TextButton(
                onClick = {
                    com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.clearHistory()
                    refreshTick = android.os.SystemClock.elapsedRealtime()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("유튜브 기록 비우기", color = MaterialTheme.colorScheme.error)
            }
        }
        if (history.isEmpty()) {
            Text("아직 기록된 롱폼 전환 이벤트가 없습니다.\n유튜브를 실행하고 영상을 보거나 화면을 전환해보세요.", style = MaterialTheme.typography.bodySmall)
        } else {
            history.forEachIndexed { _, event ->
                val tagColor = when (event.eventType) {
                    "TITLE_CHANGED" -> ItvGreen
                    "VIDEO_ENDED", "ENDSCREEN" -> MaterialTheme.colorScheme.error
                    "YOUTUBE_TO_ITV", "SWITCHED_TO_ITV" -> ItvGreen
                    "ITV_TO_YOUTUBE", "ENTERED_YOUTUBE" -> MaterialTheme.colorScheme.primary
                    "PORTRAIT_WATCH", "LANDSCAPE_WATCH" -> ItvGreen
                    "MOVED_TO_YT_HOME", "EXITED_TO_FEED", "EXITED_TO_HOME" -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val tagLabel = when (event.eventType) {
                    "TITLE_CHANGED" -> "다음 영상 전환"
                    "VIDEO_ENDED", "ENDSCREEN" -> "유튜브 영상 종료"
                    "YOUTUBE_TO_ITV", "SWITCHED_TO_ITV" -> "유튜브 → 우리 앱 복귀"
                    "ITV_TO_YOUTUBE" -> "우리 앱 → 유튜브 복귀"
                    "EXITED_TO_HOME" -> "홈 화면 복귀 (이탈)"
                    "ENTERED_YOUTUBE" -> "홈 → 유튜브 진입"
                    "PORTRAIT_WATCH" -> "세로 영상 시청"
                    "LANDSCAPE_WATCH" -> "가로 전체화면 시청"
                    "MOVED_TO_YT_HOME", "EXITED_TO_FEED" -> "유튜브 홈 이동"
                    "SHORTS_WATCH" -> "쇼츠 시청 진입"
                    "SHORTS_COMMENTS" -> "숏폼 댓글 열람"
                    else -> event.eventType
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ItvLine),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = RoundedCornerShape(4.dp), color = tagColor.copy(alpha = 0.15f)) {
                                Text(
                                    tagLabel,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = tagColor
                                )
                            }
                            Text(event.formattedTime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(event.description, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        if (event.watchedDurationMs > 0L) {
                            val sec = event.watchedDurationMs / 1000L
                            Text("• 누적 실제 시청: ${if (sec >= 60) "${sec / 60}분 ${sec % 60}초" else "${sec}초"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}



