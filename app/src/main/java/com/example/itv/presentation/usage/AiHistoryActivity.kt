package com.example.itv.presentation.usage
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.ui.theme.ITVTheme
import com.example.itv.application.records.*
import com.example.itv.infrastructure.records.RoomAiHistorySource
import com.example.itv.infrastructure.storage.shortform.ShortformDailyStore
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter
private fun duration(ms:Long)="${ms/60000}분 ${ms/1000%60}초"
private fun appName(app:String)=when(app){"instagram"->"릴스";"youtube"->"쇼츠";else->"숏폼"}
private fun eventName(kind:String)=when(kind){
 "OFFER_SHOWN"->"제안 표시";"ACCEPTED"->"쉬기 선택";"LATER"->"나중에";"DISMISSED"->"닫기";"NO_RESPONSE"->"무응답"
 "MISSION_REQUESTED"->"휴식 준비";"REST_STARTED"->"휴식 시작";"REST_COMPLETED"->"휴식 완료";"START_NOT_OBSERVED"->"시작 미관측";"INTERRUPTED"->"중단 · 결과 미상";else->kind}
class AiHistoryActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent {ITVTheme {
  val service=remember {AiHistoryService(RoomAiHistorySource(this))}
  var rows by remember {mutableStateOf<List<AiHistoryItem>>(emptyList())}
  var error by remember {mutableStateOf(false)}
  var recent by remember {mutableStateOf(ShortformDailyStore.recentSessionSnapshot())}
  LaunchedEffect(Unit){while(isActive){
   recent=ShortformDailyStore.recentSessionSnapshot()
   try {rows=withContext(Dispatchers.IO){service.recent()};error=false}catch(e:CancellationException){throw e}catch(e:Exception){error=true}
   delay(2000)
  }}
  Scaffold(containerColor=Color(0xFFF7FAF2)){padding->LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={finish()}){Text("‹ 기록")};Text("AI",fontSize=24.sp,fontWeight=FontWeight.Bold)}}
   item {MlpConnectionCard()}
   item {AiContextPanel()}
   item {AiPreparationCard()}
   item {InfoCard("최근 종료 구간") {
    Text(if(recent.app.isEmpty()) "아직 종료 구간 없음" else "${appName(recent.app)} · ${duration(recent.durationMs)}",fontSize=20.sp,fontWeight=FontWeight.Bold)
    Text("현재 실행 중인 앱에서 관측한 최근 구간",fontSize=13.sp)
   }}
   item {InfoCard("최근 개입") {
    if(error)Text("이력을 불러오지 못했습니다.")
    else if(rows.isEmpty())Text("아직 개입 이력이 없습니다.")
    else rows.groupBy {it.id}.entries.take(5).forEach {(_,events)->
     val ordered=events.sortedBy {it.time};val date=Instant.ofEpochMilli(ordered.first().time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))
     Text(date,fontWeight=FontWeight.Bold,fontSize=16.sp)
     Text(ordered.joinToString(" → "){eventName(it.kind)},fontSize=16.sp)
     HorizontalDivider()
    }
   }}
   item {InfoCard("최근 판단 당시 정보") {
    val offer=rows.firstOrNull {it.kind=="OFFER_SHOWN"}
    if(offer==null)Text("새 개입 후 표시됩니다.") else {
     Text("${appName(offer.sessionApp)} 연속 · ${duration(offer.sessionMs)}",fontSize=18.sp,fontWeight=FontWeight.Bold)
     if(offer.version=="test-coaching-v1")Text("과거 기록 · 추가 정보 미수집") else {
      Text("당일 릴스 ${duration(offer.instagramMs)}",fontSize=16.sp)
      Text("당일 쇼츠 ${duration(offer.youtubeMs)}",fontSize=16.sp)
      Text("직전 제안 간격 · ${offer.gapMs?.let {duration(it)} ?: "기록 없음"}",fontSize=16.sp)
     }
    }
    Text(if(offer?.engine=="JEV") "Jev API 판단 · 상세 확률·평가는 Jev 공동 테스트 기록에서 확인" else "기존 룰 판단 · MLP 진단과 별도 · 누적량은 당시 메모리 집계 기준",fontSize=13.sp)
   }}
   item {Spacer(Modifier.height(12.dp))}
  }}
 }}}
}
@Composable internal fun InfoCard(title:String,content:@Composable ColumnScope.()->Unit){
 OutlinedCard(Modifier.fillMaxWidth(),colors=CardDefaults.outlinedCardColors(containerColor=RecordContentGreen,contentColor=Color(0xFF243025))){
  Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text(title,fontSize=20.sp,fontWeight=FontWeight.Bold);content()
  }
 }
}
