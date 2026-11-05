package com.example.itv.presentation.usage
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.infrastructure.storage.shortform.ShortformDailyStore
import com.example.itv.infrastructure.coaching.InterventionEventRecorder
import kotlinx.coroutines.delay
@Composable internal fun AiPreparationCard(){
 var session by remember {mutableStateOf(ShortformDailyStore.sessionSnapshot())}
 LaunchedEffect(Unit){while(true){session=ShortformDailyStore.sessionSnapshot();delay(1000)}}
 OutlinedCard(Modifier.fillMaxWidth(),colors=CardDefaults.outlinedCardColors(containerColor=RecordContentGreen,contentColor=Color(0xFF243025))){
  Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text("AI 판단 준비",fontSize=20.sp,fontWeight=FontWeight.Bold)
   val name=when(session.app){"instagram"->"릴스";"youtube"->"쇼츠";else->"관측 대기"}
   Text(if(session.app.isEmpty()) name else "$name · 연속 ${session.durationMs/60000}분 ${session.durationMs/1000%60}초",fontSize=18.sp,fontWeight=FontWeight.Bold)
   Text(if(InterventionEventRecorder.failed) "개입 이력 저장 지연·누락 가능" else "기존 규칙 사용 · 개입·휴식 결과 기록",fontSize=14.sp)
  }
 }
}
