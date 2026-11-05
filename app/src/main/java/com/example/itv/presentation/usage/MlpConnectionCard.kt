package com.example.itv.presentation.usage
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.itv.domain.ai.InterventionFeatures
import com.example.itv.application.records.InterventionInferenceService
import com.example.itv.infrastructure.ai.AssetMlpPredictor
import com.example.itv.infrastructure.storage.shortform.ShortformDailyStore
import kotlinx.coroutines.*
import java.time.LocalTime
@Composable internal fun MlpConnectionCard(){
 val context=LocalContext.current
 var status by remember {mutableStateOf("연결 상태 확인 중")}
 var detail by remember {mutableStateOf("")}
 var revision by remember {mutableStateOf(0)}
 var busy by remember {mutableStateOf(false)}
 LaunchedEffect(revision){
  busy=true
  try {
   val service=withContext(Dispatchers.IO){InterventionInferenceService(AssetMlpPredictor(context))}
   val session=ShortformDailyStore.sessionSnapshot()
   val storage=ShortformDailyStore.storageStatus()
   if(!ShortformDailyStore.isReady()) {status="기록 준비 중 · 예측 보류"}
   else if(storage.issue!=com.example.itv.domain.records.ShortformStorageIssue.NONE) {status="기록 품질 확인 필요 · 예측 보류"}
   else if(session.app.isEmpty()) {status="현재 연속 관측 없음 · 예측 보류"}
   else {
    val now=LocalTime.now()
    val input=InterventionFeatures(session.durationMs/60000.0,
      ShortformDailyStore.today(context,"instagram").ms/60000.0,
      ShortformDailyStore.today(context,"youtube").ms/60000.0,now.hour+now.minute/60.0)
    val prediction=withContext(Dispatchers.Default){service.evaluate(input)}
    status="휴식 시작 예측 · ${"%.1f".format(prediction.probability*100)}%"
    detail="${prediction.modelVersion} · 그림자 모드"
   }
  }catch(e:CancellationException){throw e}catch(e:Exception){status=e.message ?: "모델 연결 실패";detail="예측 보류 · 현재 개입 설정 유지"}
  finally {busy=false}
 }
 InfoCard("MLP 모델 연결"){
  Text(status,fontSize=18.sp,fontWeight=FontWeight.Bold)
  if(detail.isNotEmpty())Text(detail,fontSize=14.sp)
  Text("현재는 실제 개입을 바꾸지 않습니다.",fontSize=13.sp)
  OutlinedButton(onClick={revision++;detail=""},enabled=!busy){Text("연결·예측 확인")}
 }
}
