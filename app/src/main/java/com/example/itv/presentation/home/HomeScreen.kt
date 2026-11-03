package com.example.itv.presentation.home

import com.example.itv.domain.records.*
import com.example.itv.composition.queryNightReport
import com.example.itv.composition.queryUsageReport


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.itv.presentation.components.*
import com.example.itv.presentation.usage.*
import com.example.itv.presentation.coaching.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(){
    val context=LocalContext.current
    val requestScope = rememberCoroutineScope()
    var showDelete by remember { mutableStateOf(false) }
    var report by remember{mutableStateOf<Report?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var revision by remember{mutableStateOf(0)}
    val shortformService = remember(context) { com.example.itv.composition.shortformRecordService(context) }
    val shortformState by shortformService.state.collectAsState()
    val ig = shortformState.data.instagram
    val yt = shortformState.data.youtube
    DisposableEffect(context){
        val lifecycle=context.hostActivity()?.lifecycle
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)revision++}
        lifecycle?.addObserver(observer)
        onDispose{lifecycle?.removeObserver(observer)}
    }
    LaunchedEffect(revision){
        while(true){
            try{report=withContext(Dispatchers.IO){context.queryUsageReport(1)};error=null}
            catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){report=null;error=e.message?:"조회 실패"}
            shortformService.refresh()
            kotlinx.coroutines.delay(5000)
        }
    }
    val youtube=report?.hourlyApps?.sumOf{apps->apps.filter{it.packageName=="com.google.android.youtube"}.sumOf{it.minutes}}
    if (showDelete) AlertDialog(
        onDismissRequest = { showDelete = false },
        title = { Text("숏폼 기록을 모두 삭제할까요?") },
        text = { Text("현재 로컬 사용자의 모든 날짜 Instagram 릴스·YouTube 쇼츠 시간과 전환 수를 삭제합니다. OS 앱 전체 사용량, 목표, 동의, 판별 표본과 별도 진단 기록은 유지합니다. 삭제 이후 사용은 새로 기록됩니다.") },
        confirmButton = { TextButton(onClick = {
            showDelete = false
            requestScope.launch { shortformService.deleteAll() }
        }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("취소") } }
    )
    HomeUsageLayout(
        total = report?.let { summaryDuration(it.total) } ?: "—",
        instagram = report?.let { summaryDuration(it.instagramMinutes) } ?: "—",
        reels = if (!shortformState.data.ready) "—" else if (ig.observed) duration(ig.ms) else "기록 없음",
        reelsTransitions = if (shortformState.data.ready) ig.transitions.toString() else "—",
        youtube = youtube?.let { summaryDuration(it) } ?: "—",
        shorts = if (!shortformState.data.ready) "—" else if (yt.observed) duration(yt.ms) else "기록 없음",
        shortsTransitions = if (shortformState.data.ready) yt.transitions.toString() else "—",
        notice = listOfNotNull(shortformState.data.notice, shortformStorageText(shortformState.data.storage)).joinToString(" · ").ifEmpty { null },
        onDeleteShortform = { showDelete = true },
        deleting = shortformState.loading,
        message = shortformState.message,
        error = error ?: shortformState.error
    )
}

private fun duration(ms:Long):String{
    val seconds=ms/1000;val minutes=seconds/60
    return if(minutes>=60) "${minutes/60}시간 ${minutes%60}분 ${seconds%60}초" else "${minutes}분 ${seconds%60}초"
}
private fun shortformStorageText(s:ShortformStorageStatus):String? = listOfNotNull(
    if(!s.ready) "숏폼 기록 준비 중" else null,
    if(s.pendingSave) "최신 누적값 저장 대기 중" else null,
    when(s.issue) {
        ShortformStorageIssue.PREPARATION -> "숏폼 준비 실패 · 재시도 대기"
        ShortformStorageIssue.SAVING -> "숏폼 저장 실패 · 재시도 대기"
        ShortformStorageIssue.PROCESSING -> "숏폼 처리 실패"
        ShortformStorageIssue.OVERFLOW -> "숏폼 대기열 초과"
        ShortformStorageIssue.NONE -> null
    },
    if(s.rejectedEvents>0) "미접수 사건 ${s.rejectedEvents}건 · 기록이 불완전할 수 있습니다" else null
).joinToString(" · ").ifEmpty { null }
