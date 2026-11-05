package com.example.itv.presentation.jev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.example.itv.domain.jev.*
import com.example.itv.infrastructure.jev.*
import com.example.itv.presentation.components.*
import com.example.itv.ui.theme.ITVTheme
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

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
        ScrollableTabRow(selectedTabIndex=page,edgePadding=12.dp) {
            listOf("연결","판단 설정","수동 요청","상태·기록").forEachIndexed { i,title ->
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
                    2 -> ManualPage(vm,s,busy)
                    else -> RecordsPage(vm,busy)
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
    var interval by rememberSaveable(s.revision){mutableStateOf(s.reevaluateSeconds.toString())}
    var additional by rememberSaveable(s.revision){mutableStateOf(s.additionalViewingSeconds.toString())}
    var ttl by rememberSaveable(s.revision){mutableStateOf(s.resultTtlSeconds.toString())}
    var later by rememberSaveable(s.revision){mutableStateOf(s.laterSeconds.toString())}
    var daily by rememberSaveable(s.revision){mutableStateOf(s.dailyLimit.toString())}
    var error by remember {mutableStateOf<String?>(null)}
    TestSection("실행 모드") {
        Toggle("Jev 자동 관측·판단 사용",enabled,{enabled=it},!busy)
        Toggle("확률 통과 시 실제 개입 (끄면 판단만 기록)",actual,{actual=it},!busy)
        Text("최초에는 실제 개입이 꺼져 있습니다. 자동 관측에는 기존 수집 동의·사용 정보·접근성·감시 알림 등 필요한 권한도 허용해야 합니다.",style=MaterialTheme.typography.bodySmall)
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
    TestSection("2단계 · 실제 개입") {
        Numeric("개입 확률 기준 (0~100%)",probability,{probability=it},!busy,true)
        Numeric("응답 유효시간 (초, 최대 300)",ttl,{ttl=it},!busy)
        Text("확률 기준을 통과해도 작성·잠금·보류·미션·상황 변경 시에는 표시하지 않습니다. 숏폼의 다음 전환 시점에 다시 확인합니다.",style=MaterialTheme.typography.bodySmall)
    }
    TestSection("재평가·호출 제한") {
        Numeric("재평가 최소 간격 (초)",interval,{interval=it},!busy)
        Numeric("다시 요청하기 전 추가 시청 (초)",additional,{additional=it},!busy)
        Numeric("나중에·무응답 보류 (초)",later,{later=it},!busy)
        Numeric("기기 일일 요청 한도 (1~40회)",daily,{daily=it},!busy)
        Text("재평가는 시간 간격과 추가 시청을 모두 충족해야 합니다. 수동 요청도 한도에 포함되며 서버 전체 한도는 앱에서 올릴 수 없습니다.",style=MaterialTheme.typography.bodySmall)
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
                probabilityThreshold=p/100.0,reevaluateSeconds=int(interval,"재평가 간격"),
                additionalViewingSeconds=int(additional,"추가 시청"),resultTtlSeconds=int(ttl,"응답 유효시간"),
                laterSeconds=int(later,"보류 시간"),dailyLimit=int(daily,"일일 한도")).validated()
            error=null;vm.save(next)
        } catch(e:Exception) {error=e.message}
    },modifier=Modifier.fillMaxWidth()) {Text("판단 설정 저장")}
    OutlinedButton(enabled=!busy,onClick={vm.save(JevSettings(testerId=s.testerId,collectionConsent=s.collectionConsent))},modifier=Modifier.fillMaxWidth()) {Text("초기 시험 설정으로 복원")}
}
@Composable
private fun ManualPage(vm: JevTestViewModel,s: JevSettings,busy: Boolean) {
    var pkg by rememberSaveable {mutableStateOf(JevSettings.INSTAGRAM)}
    var total by rememberSaveable {mutableStateOf("18")}
    var continuous by rememberSaveable {mutableStateOf("18")}
    var returns by rememberSaveable {mutableStateOf("4")}
    var bedtimeOn by rememberSaveable {mutableStateOf(true)}
    var bedtime by rememberSaveable {mutableStateOf("20")}
    var error by remember {mutableStateOf<String?>(null)}
    val answer by vm.manualAnswer.collectAsState()
    TestSection("가상 상황 입력") {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=pkg==JevSettings.INSTAGRAM,onClick={pkg=JevSettings.INSTAGRAM},label={Text("Instagram")},enabled=!busy)
            FilterChip(selected=pkg==JevSettings.YOUTUBE,onClick={pkg=JevSettings.YOUTUBE},label={Text("YouTube")},enabled=!busy)
        }
        Numeric("구간 숏폼 누적시간 (분)",total,{total=it},!busy,true)
        Numeric("연속 숏폼 시청시간 (분)",continuous,{continuous=it},!busy,true)
        Numeric("최근 10분 재진입 (회)",returns,{returns=it},!busy)
        Toggle("취침 목표 적용",bedtimeOn,{bedtimeOn=it},!busy)
        if(bedtimeOn) Numeric("취침 목표 초과 (분)",bedtime,{bedtime=it},!busy)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={total="2";continuous="2";returns="0";bedtimeOn=false},enabled=!busy) {Text("짧은 시청")}
            OutlinedButton(onClick={total="30";continuous="30";returns="4";bedtimeOn=false},enabled=!busy) {Text("장시간")}
            OutlinedButton(onClick={total="18";continuous="18";returns="4";bedtime="20";bedtimeOn=true},enabled=!busy) {Text("취침 이후")}
        }
        OutlinedButton(enabled=!busy,onClick={
            val c=vm.runtime.lastObservedContext
            if(c==null)error="확인된 최근 숏폼 관측이 없습니다."
            else {pkg=c.packageName;total=(c.sessionMs/60000.0).toString();continuous=(c.continuousMs/60000.0).toString()
                returns=c.returns10m.toString();bedtimeOn=c.bedtimePassedMinutes!=null;bedtime=(c.bedtimePassedMinutes ?: 0).toString();error=null}
        },modifier=Modifier.fillMaxWidth()) {Text("최근 관측값 불러오기")}
        Text("수동 요청은 호출 후보 조건을 우회하지만 인증·서버 한도는 적용됩니다. MANUAL로 기록하며 실제 개입·평가를 발생시키지 않습니다.",style=MaterialTheme.typography.bodySmall)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Button(enabled=!busy && s.collectionConsent,onClick={
            try {
                val t=total.toDoubleOrNull() ?: error("누적시간을 확인해 주세요.")
                val c=continuous.toDoubleOrNull() ?: error("연속시간을 확인해 주세요.")
                val r=returns.toIntOrNull() ?: error("재진입 횟수를 확인해 주세요.")
                require(t.isFinite() && c.isFinite() && t in 0.0..1440.0 && c in 0.0..t) {"시청시간은 0~1440분이며 연속시간은 누적시간 이하여야 합니다."}
                require(r in 0..1440) {"재진입은 0~1440회입니다."}
                val b=if(bedtimeOn)bedtime.toIntOrNull()?.also {require(it in 0..1440)} ?: error("취침 초과 시간을 확인해 주세요.") else null
                error=null
                vm.manual(JevContext(UUID.randomUUID().toString(),pkg,(t*60000).toLong(),(c*60000).toLong(),r,600,b,System.currentTimeMillis()))
            } catch(e:Exception) {error=e.message ?: "입력값을 확인해 주세요."}
        },modifier=Modifier.fillMaxWidth()) {Text("입력값으로 Jev 판단 요청")}
    }
    answer?.let { a ->
        val threshold=JSONObject(a.trial.payload).getJSONObject("settings").optDouble("probabilityThreshold")
        TestSection("최근 수동·재확인 결과") {
            Text("개입 적절 확률: ${"%.1f".format(a.probability*100)}%")
            Text("요청 당시 기준: ${"%.1f".format(threshold*100)}% · ${if(a.probability>=threshold)"통과" else "미달"}")
            Text("모델: ${a.trial.model} · 입력 ${a.trial.inputTokens ?: "미제공"} 토큰")
            Text("응답: ${a.trial.latencyMs}ms · 실제 개입 없음")
        }
    }
}
@Composable
private fun RecordsPage(vm: JevTestViewModel,busy: Boolean) {
    val live by vm.runtime.state.collectAsState()
    val missionActive by vm.runtime.missionActiveState.collectAsState()
    val trials by vm.store.dao.trials().collectAsState(initial=emptyList())
    val feedback by vm.store.dao.pendingFeedback().collectAsState(initial=emptyList())
    val pending by vm.store.dao.pendingCount().collectAsState(initial=0)
    val failed by vm.store.dao.failedCount().collectAsState(initial=0)
    var confirmReset by remember {mutableStateOf(false)}
    TestSection("실시간 관측·판단") {
        Text(live.status)
        live.context?.let { c ->
            Text("전면 대상: ${c.packageName}\n연속 ${c.continuousMs/1000}초 · 구간 ${c.sessionMs/1000}초\n최근 10분 재진입 ${c.returns10m}회 · 현재 관측 범위 ${c.returnsObservedSeconds}초")
            Text("품질: ${c.quality} · 취침 초과: ${c.bedtimePassedMinutes?.let { "$it 분" } ?: "미적용"}")
        } ?: Text("ITV 화면에서는 숏폼 관측을 중단합니다. 대상 앱을 사용한 뒤 돌아와 요청 기록을 확인하세요.")
        Text("전송 대기 $pending 건 · 확인이 필요한 실패 $failed 건")
        OutlinedButton(onClick={vm.retryUploads()},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("기록 전송 재시도")}
        OutlinedButton(onClick={confirmReset=true},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("테스트 관측 상태 초기화")}
    }
    if(confirmReset) AlertDialog(onDismissRequest={confirmReset=false},title={Text("테스트 관측 상태를 초기화할까요?")},
        text={Text("현재 구간·재평가·보류·구간 억제 상태만 초기화합니다. 기존 사용량·목표·서버 기록·저장된 평가 결과는 유지됩니다.")},
        confirmButton={TextButton(onClick={confirmReset=false;vm.runtime.resetTestObservation()}){Text("초기화")}},
        dismissButton={TextButton(onClick={confirmReset=false}){Text("취소")}})
    feedback.firstOrNull()?.let { trial ->
        TestSection("미완료 개입 평가 · ${trial.id.take(8)}") {
            if(missionActive) Text("미션이 끝난 뒤 개입 시점의 적절성을 평가해 주세요.")
            else key(trial.id) { FeedbackForm(busy){vm.feedback(trial.id,it)} }
        }
    }
    Text("최근 요청 30건",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
    if(trials.isEmpty())Text("아직 요청 기록이 없습니다.")
    trials.forEach { trial ->
        TestSection("${trial.testerId} · ${trial.source} · ${trial.id.take(8)}") {
            Text(Instant.ofEpochMilli(trial.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM.dd HH:mm:ss")))
            Text("상태: ${trial.state} · 확률: ${trial.probability?.let { "%.1f%%".format(it*100) } ?: "없음"}")
            Text("실제 표시: ${if(trial.displayedAt==null)"없음" else "있음"} · 평가: ${trial.feedbackStatus ?: "대상 아님"}${trial.feedbackScore?.let { " · $it 점" } ?: ""}")
            trial.error?.let {Text("오류: $it",color=MaterialTheme.colorScheme.error)}
            if(trial.state in setOf("PENDING","UNKNOWN")) OutlinedButton(onClick={vm.retry(trial.id)},enabled=!busy) {Text("동일 요청 결과 확인")}
        }
    }
}
@Composable
private fun FeedbackForm(busy: Boolean,onSubmit: (Int?)->Unit) {
    var score by rememberSaveable {mutableStateOf<Int?>(null)}
    Text("이번 개입은 적절했나요?")
    Text("0점 매우 부적절 · 5점 보통 · 10점 매우 적절",style=MaterialTheme.typography.bodySmall)
    Slider(value=score?.toFloat() ?: 5f,onValueChange={score=it.roundToInt()},valueRange=0f..10f,steps=9,enabled=!busy)
    Text(score?.let {"선택: $it 점"} ?: "점수를 선택해 주세요.")
    Button(onClick={onSubmit(score)},enabled=!busy && score!=null,modifier=Modifier.fillMaxWidth()) {Text("평가 제출")}
    TextButton(onClick={onSubmit(null)},enabled=!busy) {Text("건너뛰기")}
}
