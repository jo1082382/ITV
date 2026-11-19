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
            listOf("연결","판단 설정","개입 기록","실시간 측정 테스트","파지 각도·목 부담 감지","유튜브 롱폼 감지").forEachIndexed { i,title ->
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
                    4 -> PostureTestPage(busy)
                    else -> LongformObservationPage()
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
    val isAll = app == "ALL"
    val allSession = vm.runtime.sessionState("ALL")
    val session = if (isAll) allSession else vm.runtime.sessionState(app)
    val ytSession = vm.runtime.sessionState(JevSettings.YOUTUBE)
    val igSession = vm.runtime.sessionState(JevSettings.INSTAGRAM)
    val devStatus = com.example.itv.domain.coaching.DeveloperStatus.value

    val swipesNow = when (app) {
        JevSettings.YOUTUBE -> YoutubeProbe.candidates
        JevSettings.INSTAGRAM -> ReelsProbe.candidates
        else -> YoutubeProbe.candidates + ReelsProbe.candidates
    }
    val swipesLast = when (app) {
        JevSettings.YOUTUBE -> YoutubeProbe.lastRunCandidates
        JevSettings.INSTAGRAM -> ReelsProbe.lastRunCandidates
        else -> YoutubeProbe.lastRunCandidates + ReelsProbe.lastRunCandidates
    }
    val continuousUnified = devStatus.usedMs
    val allC = allSession?.continuousMs ?: 0L
    val sumC = (ytSession?.continuousMs ?: 0L) + (igSession?.continuousMs ?: 0L)
    val continuousNow = when (app) {
        JevSettings.YOUTUBE -> ytSession?.continuousMs ?: 0L
        JevSettings.INSTAGRAM -> igSession?.continuousMs ?: 0L
        "ALL" -> maxOf(allC, maxOf(continuousUnified, sumC))
        else -> session?.continuousMs ?: allC
    }
    val allLast = allSession?.lastRunContinuousMs ?: 0L
    val sumLast = (ytSession?.lastRunContinuousMs ?: 0L) + (igSession?.lastRunContinuousMs ?: 0L)
    val continuousLast = when (app) {
        JevSettings.YOUTUBE -> ytSession?.lastRunContinuousMs ?: 0L
        JevSettings.INSTAGRAM -> igSession?.lastRunContinuousMs ?: 0L
        "ALL" -> maxOf(allLast, maxOf(continuousUnified, sumLast))
        else -> session?.lastRunContinuousMs ?: allLast
    }
    val latestTrial = trials.firstOrNull { it.probability != null }
    val latestLiveTrial = trials.firstOrNull { it.source == "LIVE" && it.probability != null }
    val sessionId = if (isAll) (allSession?.id ?: ytSession?.id ?: igSession?.id ?: UUID.randomUUID().toString()) else (session?.id ?: UUID.randomUUID().toString())
    val allTotal = allSession?.totalMs ?: 0L
    val sumTotal = (ytSession?.totalMs ?: 0L) + (igSession?.totalMs ?: 0L)
    val totalMs = if (isAll) {
        maxOf(allTotal, maxOf(devStatus.usedMs, sumTotal))
    } else {
        session?.totalMs ?: 0L
    }
    val continuousMs = if (continuousNow > 0L) continuousNow else continuousLast
    val returns = if (isAll) {
        allSession?.returns?.size ?: ((ytSession?.returns?.size ?: 0) + (igSession?.returns?.size ?: 0))
    } else {
        session?.returns?.size ?: 0
    }
    val rawShortformMs = if (isAll) {
        val allSf = allSession?.rawShortformMs ?: 0L
        val sumSf = (ytSession?.rawShortformMs ?: 0L) + (igSession?.rawShortformMs ?: 0L)
        maxOf(allSf, sumSf)
    } else {
        session?.rawShortformMs ?: 0L
    }
    val rawGeneralMs = if (isAll) {
        val allGen = allSession?.rawGeneralMs ?: 0L
        val sumGen = (ytSession?.rawGeneralMs ?: 0L) + (igSession?.rawGeneralMs ?: 0L)
        maxOf(allGen, maxOf(devStatus.usedMs, sumGen))
    } else {
        session?.rawGeneralMs ?: 0L
    }
    val bedtime = vm.runtime.lastObservedContext?.let { if (isAll || it.packageName == app) it.bedtimePassedMinutes else null }
    val takenAt = System.currentTimeMillis()
    val baseContext = JevContext(sessionId, app, totalMs, continuousMs, returns, 600, bedtime, takenAt,
        rawShortformMs = rawShortformMs, rawGeneralMs = rawGeneralMs)
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
    return "숏폼/롱폼 전환 대기 중 (시청 중 개입 금지 원칙에 따라 다음 숏폼 스와이프 또는 롱폼 종료·전환 시 표시)"
}

@Composable
private fun ObservationTestPage(vm: JevTestViewModel, busy: Boolean) {
    val s by vm.store.settings.collectAsState()
    val answer by vm.manualAnswer.collectAsState()
    val trials by vm.store.dao.trials().collectAsState(initial = emptyList())
    val context = androidx.compose.ui.platform.LocalContext.current
    var refreshTick by remember { mutableLongStateOf(0L) }
    var targetApp by rememberSaveable { mutableStateOf("ALL") }
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

    TestSection("판단 설정 기준 및 개입 확률 확인") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = targetApp == "ALL",
                onClick = { targetApp = "ALL" },
                label = { Text("전체") },
                enabled = !busy
            )
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
                    "LONGFORM_BINGE_CHAIN" -> "추천영상연속시청(앱로컬)"
                    "INTERVENTION_RESISTANCE" -> "직전개입미룸·시청지속"
                    else -> r
                }
            }
            val ambientSnapshot = remember(refreshTick) {
                com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(context).snapshot.value
            }
            val ytSession = vm.runtime.sessionState(JevSettings.YOUTUBE)
            val igSession = vm.runtime.sessionState(JevSettings.INSTAGRAM)
            val allSession = vm.runtime.sessionState("ALL")
            val devStatus = com.example.itv.domain.coaching.DeveloperStatus.value

            val liveContinuousMs = remember(refreshTick, targetApp, snap) {
                when (targetApp) {
                    JevSettings.YOUTUBE -> {
                        val c = ytSession?.continuousMs ?: 0L
                        if (c > 0L) c else snap.continuousMs
                    }
                    JevSettings.INSTAGRAM -> {
                        val c = igSession?.continuousMs ?: 0L
                        if (c > 0L) c else snap.continuousMs
                    }
                    "ALL" -> {
                        val allC = allSession?.continuousMs ?: 0L
                        val unified = devStatus.usedMs
                        val sum = (ytSession?.continuousMs ?: 0L) + (igSession?.continuousMs ?: 0L)
                        val c = maxOf(allC, maxOf(unified, sum))
                        if (c > 0L) c else snap.continuousMs
                    }
                    else -> {
                        val appC = vm.runtime.sessionState(targetApp)?.continuousMs ?: 0L
                        if (appC > 0L) appC else snap.continuousMs
                    }
                }
            }
            val liveTotalMs = remember(refreshTick, targetApp, snap) {
                when (targetApp) {
                    JevSettings.YOUTUBE -> ytSession?.totalMs ?: snap.totalMs
                    JevSettings.INSTAGRAM -> igSession?.totalMs ?: snap.totalMs
                    "ALL" -> allSession?.totalMs ?: (((ytSession?.totalMs ?: 0L) + (igSession?.totalMs ?: 0L)).takeIf { it > 0L } ?: snap.totalMs)
                    else -> vm.runtime.sessionState(targetApp)?.totalMs ?: snap.totalMs
                }
            }

            // 1. 시청 & 재진입 요약 박스
            CompactCheckGroup("1. 시청 시간 · 재진입") {
                val contLabel = if (snap.app == "ALL") "통합 연속 사용" else "연속 시청"
                val contDetail = if (snap.app == "ALL") " (숏폼 1.0x + 일반 1/3x)" else ""
                CompactCheckRow(
                    label = contLabel,
                    value = "${"%.1f".format(liveContinuousMs / 1000.0)}초$contDetail / 기준 ${if (s.continuousEnabled) "${s.continuousSeconds}초" else "꺼짐"}"
                )
                CompactCheckRow(
                    label = "구간 누적 · 재진입",
                    value = "누적 ${"%.1f".format(liveTotalMs / 1000.0)}초 · 재진입 ${snap.returns}회 (기준 ${if (s.returnsEnabled) "${s.returnsThreshold}회" else "꺼짐"})"
                )
                val rawSf = snap.enrichedContext?.rawShortformMs ?: 0L
                val rawGen = snap.enrichedContext?.rawGeneralMs ?: 0L
                val rawPhysicalMs = rawSf + rawGen
                if (snap.app == "ALL" || rawPhysicalMs > 0L) {
                    val sfRatio = if (rawPhysicalMs > 0L) (rawSf.toDouble() / rawPhysicalMs.toDouble()) * 100.0 else 0.0
                    val weightedMs = rawSf + (rawGen / 3L)
                    CompactCheckRow(
                        label = "물리 세션 (휴대폰 전체)",
                        value = "${"%.1f".format(rawPhysicalMs / 1000.0)}초 (숏폼 ${"%.1f".format(rawSf / 1000.0)}초 + 일반 ${"%.1f".format(rawGen / 1000.0)}초)"
                    )
                    CompactCheckRow(
                        label = "숏폼 비율 / 환산 세션",
                        value = "숏폼 비중 ${"%.1f".format(sfRatio)}% · 환산 ${"%.1f".format(weightedMs / 1000.0)}초 (1.0x+1/3x)"
                    )
                }
                if (snap.app == JevSettings.YOUTUBE || snap.app == "ALL") {
                    val lfTotalSec = ctx.longformWatchMs / 1000.0
                    val lfCurSec = ctx.currentVideoWatchMs / 1000.0
                    val lfModeStr = ctx.youtubeWatchMode ?: "대기"
                    CompactCheckRow(
                        label = "롱폼 시청 분석",
                        value = "세션 롱폼 ${"%.0f".format(lfTotalSec)}초 · 현재영상 ${"%.0f".format(lfCurSec)}초 · 완료 ${ctx.completedLongformCount}편 ($lfModeStr)",
                        highlight = ctx.longformWatchMs > 0L
                    )
                    CompactCheckRow(
                        label = "추천 영상 연속 전환",
                        value = "연속 ${ctx.consecutiveLongformChainCount}회 전환 (상황 분석 전용)",
                        highlight = ctx.consecutiveLongformChainCount >= 2
                    )
                }
            }

            // 2. 개인화 비율 & 스와이프 요약 박스
            CompactCheckGroup("2. 개인화 비율 · 스와이프 · 피로도 · 취침환경") {
                val adj = JevCandidatePolicy.computeThresholdAdjustment(s, ctx)
                val sessionRatioStr = ctx.sessionVsAvgRatio?.let { "${(it * 100).roundToInt()}%" } ?: "집계 중"
                val avgSessionStr = ctx.personalAvgSessionMinutes?.let {
                    val totalSec = (it * 60).roundToInt()
                    val min = totalSec / 60
                    val sec = totalSec % 60
                    if (sec > 0) "기준 ${min}분 ${sec}초" else "기준 ${min}분"
                } ?: "기준 수집 중"
                val sessionDeltaStr = if (adj.sessionDelta != 0.0) " (${if (adj.sessionDelta > 0) "+" else ""}${"%.0f".format(adj.sessionDelta * 100)}%p)" else ""
                CompactCheckRow(
                    label = "세션 과몰입 비율",
                    value = "$sessionRatioStr ($avgSessionStr)$sessionDeltaStr",
                    highlight = adj.sessionDelta < 0
                )
                val fatigueMin = (liveContinuousMs / 60000.0)
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
                val isAllApp = snap.app == "ALL"
                val spmStr = if (isAllApp) "전체 사용 모드 (스와이프 제외)" else "분당 ${ctx.effectiveSpm1m ?: 0.0}회 (평소 ${ctx.personalAvgSpm ?: 0.0})"
                val swipeDeltaStr = if (isAllApp) " · 제외 (0%p)" else if (adj.swipeDelta < 0) " · 과열(${"%.0f".format(adj.swipeDelta * 100)}%p)" else " · 정상"
                CompactCheckRow(
                    label = if (isAllApp) "스와이프 (적용 제외)" else "스와이프 (${snap.swipes}회)",
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

                val evalProb = answer?.probability
                    ?: snap.latestLiveProbability
                    ?: snap.latestTrialProbability

                if (evalProb != null) {
                    val passed = JevCandidatePolicy.passes(evalProb, s, ctx)
                    val diff = (evalProb * 100.0) - effThresholdPct
                    val diffStr = if (diff >= 0) "+${"%.1f".format(diff)}%p 초과" else "${"%.1f".format(diff)}%p 미달"
                    CompactCheckRow(
                        label = "적용 개입 기준 판정",
                        value = "${"%.1f".format(evalProb * 100)}% vs 기준 ${"%.0f".format(effThresholdPct)}% → ${if (passed) "기준 통과 ✅ (개입 대상)" else "기준 미달 ❌ (미개입)"} ($diffStr)",
                        highlight = passed
                    )
                } else {
                    CompactCheckRow(
                        label = "적용 개입 기준 판정",
                        value = "확률 산출 대기 (적용 기준 ${"%.0f".format(effThresholdPct)}%)"
                    )
                }

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
            val appSuppression = remember(refreshTick, targetApp) {
                if (targetApp == "ALL") {
                    val ytSup = vm.runtime.getAppSuppressionInfo(JevSettings.YOUTUBE)
                    val igSup = vm.runtime.getAppSuppressionInfo(JevSettings.INSTAGRAM)
                    if (ytSup.isAsked) ytSup else igSup
                } else {
                    vm.runtime.getAppSuppressionInfo(targetApp)
                }
            }
            val dynInterval = appSuppression.dynamicIntervalSeconds
            val lastGap = appSuppression.lastProbabilityGap
            val nextInSec = appSuppression.nextEvaluationInSeconds ?: 0L
            val addSec = if (liveState.context?.packageName == targetApp || (targetApp == "ALL" && liveState.context?.packageName in listOf(JevSettings.YOUTUBE, JevSettings.INSTAGRAM))) {
                liveState.additionalViewingNeededSeconds ?: 0L
            } else 0L
            val snapProb = snap.latestLiveProbability
            val isLongformOrAll = targetApp == "ALL" || (snap.enrichedContext?.rawGeneralMs ?: 0L) > (snap.enrichedContext?.rawShortformMs ?: 0L)
            val expInterval = snapProb?.let { JevCandidatePolicy.calculateDynamicInterval(effThresholdPct / 100.0, it, isLongformOrAll) }
            val expGap = snapProb?.let { ((effThresholdPct / 100.0) - it).coerceAtLeast(0.0) }
            val intervalDesc = when {
                appSuppression.isAsked && lastGap != null ->
                    "${dynInterval}초 (실시간 반영 · 격차 -${"%.1f".format(lastGap * 100)}%p${if (isLongformOrAll) " · 실사용 약 ${dynInterval * 3 / 60}분" else ""})"
                expInterval != null && expGap != null ->
                    "${dynInterval}초 (최근 확률 예상: ${expInterval}초, 격차 -${"%.1f".format(expGap * 100)}%p${if (isLongformOrAll) " · 실사용 약 ${expInterval * 3 / 60}분" else ""})"
                else -> "${dynInterval}초 (기본값 · 첫 실시간 평가 대기)"
            }
            CompactCheckGroup("4. 동적 재평가 · 추가 시청") {
                CompactCheckRow(
                    label = if (isLongformOrAll) "동적 간격 (60~360초 · 실사용 3~18분)" else "동적 간격 (30~360초 · 실사용 0.5~6분)",
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
            Text(if (targetApp == "ALL") "현재 통합 측정값으로 Jev 확률 요청 (토큰 1회)" else "현재 측정값으로 Jev 확률 요청 (토큰 1회)")
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
                val annotatedNormalAction = androidx.compose.ui.text.buildAnnotatedString {
                    append(normalMission.action)
                    normalMission.boldPhrases.forEach { phrase ->
                        val startIdx = normalMission.action.indexOf(phrase)
                        if (startIdx >= 0) {
                            addStyle(
                                style = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold),
                                start = startIdx,
                                end = startIdx + phrase.length
                            )
                        }
                    }
                }
                Text(annotatedNormalAction, style = MaterialTheme.typography.bodyMedium)
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
                val annotatedStrongAction = androidx.compose.ui.text.buildAnnotatedString {
                    append(strongMission.action)
                    strongMission.boldPhrases.forEach { phrase ->
                        val startIdx = strongMission.action.indexOf(phrase)
                        if (startIdx >= 0) {
                            addStyle(
                                style = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold),
                                start = startIdx,
                                end = startIdx + phrase.length
                            )
                        }
                    }
                }
                Text(annotatedStrongAction, style = MaterialTheme.typography.bodyMedium)
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
    var hudShowing by remember { mutableStateOf(com.example.itv.presentation.coaching.LongformDiagnosticHud.isShowing) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            refreshTick = android.os.SystemClock.elapsedRealtime()
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
                if (hudShowing) {
                    com.example.itv.presentation.coaching.LongformDiagnosticHud.hide()
                    hudShowing = false
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(context)) {
                        android.widget.Toast.makeText(context, "다른 앱 위에 표시 권한을 허용해주세요.", android.widget.Toast.LENGTH_LONG).show()
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } else {
                        val success = com.example.itv.presentation.coaching.LongformDiagnosticHud.show(context)
                        if (success) {
                            hudShowing = true
                            android.widget.Toast.makeText(context, "실시간 플로팅 HUD가 활성화되었습니다. 다른 앱을 실행해보세요!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hudShowing) MaterialTheme.colorScheme.error else ItvGreen
            )
        ) {
            Text(if (hudShowing) "실시간 플로팅 HUD 끄기" else "통합 실시간 플로팅 HUD 켜기", fontWeight = FontWeight.Bold)
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



