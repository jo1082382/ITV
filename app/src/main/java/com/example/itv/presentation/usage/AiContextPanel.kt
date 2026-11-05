package com.example.itv.presentation.usage
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.domain.records.*
import com.example.itv.domain.goals.HomeGoalRow
import com.example.itv.composition.*
import com.example.itv.data.local.ItvDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** Read-only aggregation view. Does not relocate or overwrite the source records. */
@Composable internal fun AiContextPanel(){
 val context=LocalContext.current
 var revision by remember {mutableStateOf(0)}
 var days by remember {mutableStateOf(1)}
 var report by remember {mutableStateOf<Report?>(null)}
 var today by remember {mutableStateOf<Report?>(null)}
 var goals by remember {mutableStateOf<List<HomeGoalRow>>(emptyList())}
 var sleep by remember {mutableStateOf<List<String>>(emptyList())}
 var shorts by remember {mutableStateOf<List<String>>(emptyList())}
 var errors by remember {mutableStateOf<List<String>>(emptyList())}
 var updated by remember {mutableStateOf("")}
 var loading by remember {mutableStateOf(false)}
 var selected by remember(days,revision) {mutableStateOf<Int?>(null)}
 LaunchedEffect(days,revision){
  loading=true;report=null;today=null;goals=emptyList();sleep=emptyList();shorts=emptyList();errors=emptyList()
  val failures=mutableListOf<String>()
  suspend fun attempt(name:String, action:suspend ()->Unit){
   try {action()} catch(e:CancellationException){throw e}catch(e:Exception){failures+=name}
  }
  attempt("사용량"){val pair=withContext(Dispatchers.IO){context.queryUsageReport(days) to context.queryUsageReport(1)};report=pair.first;today=pair.second}
  attempt("목표"){goals=goalService(ItvDatabase.get(context)).observeHomeGoals("demo-guest").first()}
  attempt("숏폼"){shorts=withContext(Dispatchers.IO){
   val end=LocalDate.now();val start=end.minusDays(days.toLong()-1)
   val rows=ItvDatabase.get(context).shortformDao().all("demo-guest").filter {it.localDate>=start.toString() && it.localDate<=end.toString()}
   listOf("com.instagram.android" to "릴스","com.google.android.youtube" to "쇼츠").map {(pkg,name)->
    val found=rows.filter {it.packageName==pkg}
    if(found.isEmpty()) "$name · 기록 없음" else "$name · ${summaryDuration(found.sumOf {it.durationMs}/60000.0)} · ${found.map {it.localDate}.distinct().size}일 기록"
   }
  }}
  attempt("수면"){sleep=withContext(Dispatchers.IO){
   val nights=queryNightReport(context,days);val db=ItvDatabase.get(context)
   (0 until days).map {offset->
    val date=LocalDate.now().minusDays(offset.toLong());val confirmed=db.sleepConfirmationDao().read("demo-guest",date.toString())
    val auto=nights.segments.firstOrNull {it.wakeDate==date && it.end!=null}
    val label=when {
     confirmed==null -> auto?.let {"자동 추정 · ${summaryDuration(it.minutes)}"} ?: "기록 없음"
     confirmed.startMs!=null && confirmed.endMs!=null -> "사용자 확인 · ${summaryDuration((confirmed.endMs-confirmed.startMs)/60000.0)}"
     else -> "사용자 확인 · 수면 없음"
    }
    "$date · $label"
   }
  }}
  errors=failures;updated=java.time.LocalTime.now().withNano(0).toString();loading=false
 }
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
   Text("판단 자료 모음",fontSize=20.sp,fontWeight=FontWeight.Bold)
   TextButton(onClick={revision++},enabled=!loading){Text("새로고침")}
  }
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   listOf(1 to "오늘",7 to "7일",30 to "30일").forEach {(n,title)->FilterChip(selected=days==n,onClick={days=n},label={Text(title)})}
  }
  Text("원래 화면은 그대로 · 조회 $updated",fontSize=13.sp)
  if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
  if(errors.isNotEmpty())Text("조회 불가: ${errors.joinToString()} · 0으로 처리하지 않음")
  InfoCard("사용량 요약"){
   val data=report
   if(data==null)Text("조회 전 또는 조회 불가") else {
    Text("기간 총 ${summaryDuration(data.total)}",fontSize=20.sp,fontWeight=FontWeight.Bold)
    if(days>1)Text("날짜별 기록 ${data.dailyTotals.size}일 · 오늘 포함")
    shorts.forEach {Text(it,fontSize=16.sp)}
   }
  }
  InfoCard("시간대·앱별 사용량"){
   report?.let {data->
    UsageChart(data,selected){selected=it}
    Text(if(days==1) "막대를 눌러 앱별 사용량 확인" else "오늘 포함 시간대별 하루 평균 · 숏폼은 아래 날짜별 자료 참고",fontSize=13.sp)
    selected?.let {hour->
     Text("${hour}시–${hour+1}시",fontWeight=FontWeight.Bold)
     data.hourlyApps.getOrNull(hour).orEmpty().filter {it.minutes>0}.forEach {AppUsageCard(it,androidx.compose.ui.graphics.Color(0xFF1751D0))}
     if(days==1) ShortformHourPanel(LocalDate.now().toString(),hour)
    }
   } ?: Text("사용량 기록 조회 불가")
  }
  if(days>1) {val data=report;if(data!=null)AiDailyDetails(data)}
  InfoCard("설정한 목표") {GoalComparisonSection(goals,report,today)}
  InfoCard("취침 추정·사용자 확인"){
   Text("수면 종료일 기준 · 실제 수면 측정 아님",fontSize=13.sp)
   sleep.forEach {Text(it,fontSize=16.sp)}
  }
  Text("누락은 미사용이 아닙니다. 현재 자료는 조회 시점 기준이며 AI 전송은 하지 않습니다.",fontSize=13.sp)
 }
}
@Composable private fun AiDailyDetails(report:Report){

 // Emit the existing lazy items through the outer screen, not a nested scrolling list.
 // This view uses the dedicated date picker below instead.
 AiDateHours(report)
}
@Composable private fun AiDateHours(report:Report){
 val context=LocalContext.current
 var date by remember(report){mutableStateOf<LocalDate?>(null)}
 var day by remember(report){mutableStateOf<Report?>(null)}
 var hour by remember(date){mutableStateOf<Int?>(null)}
 var failed by remember(date){mutableStateOf(false)}
 LaunchedEffect(date){day=null;failed=false;date?.let {chosen->try {day=withContext(Dispatchers.IO){context.queryUsageReport(1,chosen)}}catch(e:CancellationException){throw e}catch(e:Exception){failed=true}}}
 InfoCard("日別時間帯".replace("日別時間帯","날짜별 시간대·숏폼")){
  androidx.compose.foundation.lazy.LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   items(report.days){offset->val target=LocalDate.now().minusDays(offset.toLong());FilterChip(selected=date==target,onClick={date=target},label={Text("${target.monthValue}.${target.dayOfMonth}")})}
  }
  if(failed)Text("해당 날짜 조회 실패")
  day?.let {data->UsageChart(data,hour){hour=it};hour?.let {h->
   ShortformHourPanel(checkNotNull(date).toString(),h)
   data.hourlyApps.getOrNull(h).orEmpty().filter {it.minutes>0}.forEach {AppUsageCard(it,androidx.compose.ui.graphics.Color(0xFF1751D0))}
  }}
 }
}
