package com.example.itv.presentation.usage
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.itv.data.local.ItvDatabase
import com.example.itv.domain.records.AppHourUsage
import kotlinx.coroutines.*
internal data class ShortformHourDisplay(val packageName:String,val ms:Long)
@Composable
internal fun ShortformHourPanel(date:String,hour:Int) {
 val context=LocalContext.current
 var rows by remember(date,hour) {mutableStateOf<List<ShortformHourDisplay>>(emptyList())}
 var loading by remember(date,hour) {mutableStateOf(true)}
 var error by remember(date,hour) {mutableStateOf(false)}
 LaunchedEffect(date,hour) {
  try { rows=withContext(Dispatchers.IO) {
   ItvDatabase.get(context).shortformHourDao().hour("demo-guest",date,hour)
    .groupBy {it.packageName}.map { (pkg,values)->ShortformHourDisplay(pkg,values.sumOf {it.durationMs}) }.filter {it.ms>0}
  }} catch(e:CancellationException){throw e} catch(e:Exception){error=true} finally {loading=false}
 }
 OutlinedCard(Modifier.fillMaxWidth(),colors=CardDefaults.outlinedCardColors(containerColor=RecordContentGreen)) {
  Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   Text("숏폼",style=MaterialTheme.typography.titleMedium)
   when {
    loading->Text("불러오는 중…")
    error->Text("숏폼 시간대 기록을 조회하지 못했습니다.")
    rows.isEmpty()->Text("이 시간대의 숏폼 기록 없음 · 미사용을 뜻하지 않습니다.",style=MaterialTheme.typography.bodySmall)
    else->rows.forEach { item ->
     Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
      AppUsageCard(AppHourUsage(item.packageName,"",0.0),Color(0xFF4263EB),iconOnly=true)
      val name=if(item.packageName=="com.instagram.android") "릴스" else "쇼츠"
      val seconds=item.ms/1000
      Text("$name · ${seconds/60}분 ${seconds%60}초",style=MaterialTheme.typography.bodyLarge)
     }
    }
   }
   Text("앱 전체 사용시간에 포함된 값입니다. 과거 일일 합계는 시간대별로 복원하지 않습니다.",style=MaterialTheme.typography.bodySmall)
  }
 }
}
