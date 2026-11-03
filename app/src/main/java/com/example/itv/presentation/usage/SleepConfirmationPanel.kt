package com.example.itv.presentation.usage

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.itv.domain.records.*
import kotlinx.coroutines.launch
import java.time.*

@Composable
internal fun SleepConfirmationPanel(date:LocalDate, zone:ZoneId, candidates:List<NightSegment>, onSaved:()->Unit = {}) {
    val context=LocalContext.current
    key(date.toString()) {
        val service=remember { com.example.itv.composition.sleepConfirmationService(context) }
        val state by service.state.collectAsState()
        val scope=rememberCoroutineScope()
        var selecting by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf(false) }
        var start by remember { mutableStateOf(date.minusDays(1).atTime(23,0)) }
        var end by remember { mutableStateOf(date.atTime(7,0)) }
        var inputError by remember { mutableStateOf<String?>(null) }
        fun submit(a:Long,b:Long,origin:String) { scope.launch {
            service.save(SleepConfirmation(date.toString(),zone.id,"SLEEP",a,b,origin,System.currentTimeMillis()))
            if(service.state.value.error==null) { selecting=false; editing=false; onSaved() }
        } }
        LaunchedEffect(date) { service.load(date.toString()) }
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val v=state.value
            if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            OutlinedButton(enabled=!state.loading,onClick={selecting=true}) { Text("수면 시간 선택하기") }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled=!state.loading,onClick={scope.launch {
                    service.save(SleepConfirmation(date.toString(),zone.id,"NOT_SLEEP",null,null,"USER",System.currentTimeMillis()))
                    if(service.state.value.error==null) onSaved()
                }}) { Text("수면 없음") }
                OutlinedButton(enabled=!state.loading,onClick={
                    val main=candidates.firstOrNull()
                    start=(v?.startMs ?: main?.start)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() } ?: date.minusDays(1).atTime(23,0)
                    end=(v?.endMs ?: main?.end)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() } ?: date.atTime(7,0)
                    inputError=null; editing=true
                }) { Text("직접 입력") }
            }
            if(selecting) Dialog(onDismissRequest={if(!state.loading) selecting=false}) {
                Surface(shape=MaterialTheme.shapes.large,color=RecordContentGreen) {
                    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                        horizontalAlignment=Alignment.CenterHorizontally,
                        verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("${date.monthValue}월 ${date.dayOfMonth}일 · 수면 시간 선택",
                            style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                        val current=candidates.firstOrNull {it.start==v?.startMs && it.end==v?.endMs} ?: candidates.firstOrNull()
                        if(current!=null) SleepClock(current,zone,candidates.filter {it!=current},showHeading=false,
                            onPick={picked -> if(!state.loading) submit(picked.start,picked.end!!,"CANDIDATE_SELECTED")})
                    }
                }
            }
            if(editing) AlertDialog(onDismissRequest={if(!state.loading) editing=false},
                containerColor=RecordContentGreen,
                title={Text("주 수면 직접 입력")},
                text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    SleepDateTimePicker("시작",start) {start=it}
                    SleepDateTimePicker("종료",end) {end=it}
                    inputError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                }},
                confirmButton={TextButton(enabled=!state.loading,onClick={try {
                    fun millis(local:LocalDateTime):Long {
                        val offsets=zone.rules.getValidOffsets(local)
                        require(offsets.size==1) {"이 시간대에서 유효한 시각을 선택해 주세요."}
                        return local.atOffset(offsets.single()).toInstant().toEpochMilli()
                    }
                    val a=millis(start); val b=millis(end)
                    SleepConfirmationPolicy.validate(SleepConfirmation(date.toString(),zone.id,"SLEEP",a,b,"MANUAL",0),System.currentTimeMillis())
                    submit(a,b,"MANUAL")
                } catch(e:Exception) {inputError=e.message ?: "날짜와 시간을 확인해 주세요."}}) {Text("저장")}},
                dismissButton={TextButton(enabled=!state.loading,onClick={editing=false}) {Text("취소")}})
        }
    }
}

@Composable
private fun SleepDateTimePicker(title:String,value:LocalDateTime,onChange:(LocalDateTime)->Unit) {
    Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        NumberScrollPicker("년",(value.year-2..value.year+2).toList(),value.year,Modifier.weight(1.4f)) {year ->
            val day=minOf(value.dayOfMonth,YearMonth.of(year,value.monthValue).lengthOfMonth())
            onChange(value.withDayOfMonth(1).withYear(year).withDayOfMonth(day))
        }
        NumberScrollPicker("월",(1..12).toList(),value.monthValue,Modifier.weight(1f)) {month ->
            val day=minOf(value.dayOfMonth,YearMonth.of(value.year,month).lengthOfMonth())
            onChange(value.withDayOfMonth(1).withMonth(month).withDayOfMonth(day))
        }
        NumberScrollPicker("일",(1..YearMonth.from(value).lengthOfMonth()).toList(),value.dayOfMonth,Modifier.weight(1f)) {
            onChange(value.withDayOfMonth(it))
        }
    }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TimeNumberField("시",value.hour,23,Modifier.weight(1f)) {onChange(value.withHour(it))}
        Text(":",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        TimeNumberField("분",value.minute,59,Modifier.weight(1f)) {onChange(value.withMinute(it))}
    }
}

@Composable
private fun NumberScrollPicker(label:String,values:List<Int>,selected:Int,modifier:Modifier,onSelect:(Int)->Unit) {
    val listState=rememberLazyListState()
    LaunchedEffect(selected,values.first(),values.last()) {
        listState.scrollToItem(values.indexOf(selected).coerceAtLeast(0))
    }
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
        Text(label)
        OutlinedCard {
            LazyColumn(Modifier.height(108.dp).fillMaxWidth(),state=listState) {
                items(values,key={it}) {number ->
                    Text(number.toString(),Modifier.fillMaxWidth()
                        .background(if(number==selected) MaterialTheme.colorScheme.primaryContainer else RecordContentGreen)
                        .clickable {onSelect(number)}.padding(vertical=8.dp),
                        textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight=if(number==selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun TimeNumberField(label:String,value:Int,max:Int,modifier:Modifier,onChange:(Int)->Unit) {
    var text by remember {mutableStateOf("%02d".format(value))}
    OutlinedTextField(value=text,onValueChange={raw ->
        if(raw.length<=2 && raw.all {it.isDigit()}) {
            val number=raw.toIntOrNull()
            if(number==null || number in 0..max) {text=raw;onChange(number ?: 0)}
        }
    },label={Text(label)},modifier=modifier,singleLine=true,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
}
