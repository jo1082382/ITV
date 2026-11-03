package com.example.itv.presentation.goal

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.itv.presentation.navigation.ItvMainScreen
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import com.example.itv.presentation.components.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val OWNER = "demo-guest"

private data class Preset(val label: String, val first: Int, val second: Int = 0)
private val dailyPresets = listOf(Preset("1시간", 1), Preset("2시간", 2), Preset("3시간", 3), Preset("4시간", 4))
private val bedtimePresets = listOf(Preset("22:00", 22), Preset("23:00", 23), Preset("00:00", 0), Preset("01:00", 1))
private val breakPresets = listOf(Preset("15분 사용 / 3분 휴식", 15, 3), Preset("30분 사용 / 5분 휴식", 30, 5), Preset("45분 사용 / 10분 휴식", 45, 10), Preset("60분 사용 / 10분 휴식", 60, 10))

@Composable
fun GoalSettingsScreen(viewModel: GoalSettingsViewModel, modifier: Modifier = Modifier) {
    val repository = viewModel.repository
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var home by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf("home") }
    var openedFromMain by rememberSaveable { mutableStateOf(false) }

    var dailyOn by remember { mutableStateOf(false) }
    var dailyChoice by remember { mutableStateOf(4) }
    var hours by remember { mutableStateOf("3") }
    var minutes by remember { mutableStateOf("0") }
    var bedtimeOn by remember { mutableStateOf(false) }
    var bedtimeChoice by remember { mutableStateOf(4) }
    var bedHour by remember { mutableStateOf("23") }
    var bedMinute by remember { mutableStateOf("0") }
    var breakOn by remember { mutableStateOf(false) }
    var breakChoice by remember { mutableStateOf(4) }
    var useMinutes by remember { mutableStateOf("30") }
    var restMinutes by remember { mutableStateOf("5") }
    // Preserve any non-default metric/scope from the existing database.
    var breakMetric by remember { mutableStateOf("CONFIRMED_SHORTFORM") }
    var breakPackage by remember { mutableStateOf<String?>("com.instagram.android") }

    LaunchedEffect(viewModel) {
        try {
            viewModel.initialize()
            repository.snapshot(OWNER).let { snapshot ->
                snapshot.daily?.let { (goal, detail) ->
                    dailyOn = goal.enabled
                    hours = (detail.dailyLimitSeconds / 3600).toString()
                    minutes = ((detail.dailyLimitSeconds % 3600) / 60).toString()
                    dailyChoice = dailyPresets.indexOfFirst { it.first * 3600L == detail.dailyLimitSeconds }.let { if (it < 0) 4 else it }
                    // Keep this screen minute-based; never silently truncate second-level values.
                    require(detail.dailyLimitSeconds % 60L == 0L) { "초 단위 하루 목표는 현재 분 단위 화면에서 편집할 수 없습니다." }
                }
                snapshot.bedtime?.let { (goal, detail) ->
                    bedtimeOn = goal.enabled
                    bedHour = (detail.localMinuteOfDay / 60).toString()
                    bedMinute = (detail.localMinuteOfDay % 60).toString()
                    bedtimeChoice = bedtimePresets.indexOfFirst { it.first * 60 + it.second == detail.localMinuteOfDay }.let { if (it < 0) 4 else it }
                }
                snapshot.usageBreak?.let { (goal, detail) ->
                    breakOn = goal.enabled
                    require(detail.usageThresholdSeconds % 60L == 0L && detail.breakDurationSeconds % 60L == 0L) { "초 단위 휴식 목표는 현재 분 단위 화면에서 편집할 수 없습니다." }
                    require(detail.accumulationPolicy == "CURRENT_SESSION") { "지원하지 않는 사용량 누적 정책입니다." }
                    useMinutes = (detail.usageThresholdSeconds / 60).toString()
                    restMinutes = (detail.breakDurationSeconds / 60).toString()
                    breakMetric = detail.usageMetric
                    breakPackage = detail.scopePackageName
                    breakChoice = breakPresets.indexOfFirst { it.first * 60L == detail.usageThresholdSeconds && it.second * 60L == detail.breakDurationSeconds }.let { if (it < 0) 4 else it }
                }
            }
            home = viewModel.setupCompleted()
            ready = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "불러오기 실패: ${e.message}" }
        finally { loading = false }
    }

    fun submit(skip: Boolean) {
        scope.launch {
            busy = true
            error = null
            try {
                // Validate all active inputs before beginning any writes.
                val dailySeconds = if (!skip && dailyOn) {
                    if (dailyChoice < 4) dailyPresets[dailyChoice].first * 3600L
                    else {
                        val h = hours.toIntOrNull()
                        val m = minutes.toIntOrNull()
                        require(h != null && h in 0..24 && m != null && m in 0..59 && (h < 24 || m == 0)) { "하루 사용시간은 0~24시간이며 분은 0~59로 입력해 주세요." }
                        h * 3600L + m * 60L
                    }
                } else null
                val bedtimeValue = if (!skip && bedtimeOn) {
                    if (bedtimeChoice < 4) bedtimePresets[bedtimeChoice].let { it.first to it.second }
                    else {
                        val h = bedHour.toIntOrNull()
                        val m = bedMinute.toIntOrNull()
                        require(h != null && h in 0..23 && m != null && m in 0..59) { "취침시간은 시 0~23, 분 0~59로 입력해 주세요." }
                        h to m
                    }
                } else null
                val breakValue = if (!skip && breakOn) {
                    if (breakChoice < 4) breakPresets[breakChoice].let { it.first.toLong() * 60 to it.second.toLong() * 60 }
                    else {
                        val u = useMinutes.toLongOrNull()
                        val r = restMinutes.toLongOrNull()
                        require(u != null && u in 1..1440 && r != null && r in 1..1440) { "사용·휴식시간은 각각 1~1440분으로 입력해 주세요." }
                        u * 60 to r * 60
                    }
                } else null

                // RoomGoalStore preserves the atomic setup transaction.
                // Any error rolls back all three goals and their outbox entries.
                viewModel.saveAndComplete {
                    suspend fun disable(type: String) = repository.disableGoal(OWNER, type)
                    if (dailySeconds != null) repository.saveDailyUsageGoal(OWNER, dailySeconds)
                    else disable("DAILY_PHONE_USAGE")
                    if (bedtimeValue != null) repository.saveBedtimeGoal(OWNER, bedtimeValue.first, bedtimeValue.second)
                    else disable("BEDTIME")
                    if (breakValue != null) repository.saveUsageBreakGoal(OWNER, breakValue.first, breakValue.second, breakMetric, breakPackage)
                    else disable("USAGE_BREAK")
                }
                if (skip) { dailyOn = false; bedtimeOn = false; breakOn = false }
                home = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "저장 실패" }
            finally { busy = false }
        }
    }

    BackHandler(enabled = !home && openedFromMain) {
        if (!busy) home = true
    }
    if (home && !loading) {
        ItvVisualTheme {
            ItvMainScreen(
                goals = viewModel.homeGoals,
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onOpenGoals = { openedFromMain = true; home = false },
                modifier = modifier
            )
        }
        return
    }

    ItvVisualTheme {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (loading) {
            CircularProgressIndicator()
            Text("저장된 목표를 불러오는 중입니다.")
        } else {
            if (openedFromMain) {
                TextButton(enabled = !busy, onClick = { home = true }) { Text("‹ 이전 화면으로") }
            }
            Text("나에게 맞는 목표", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = ItvGreen)
            Text("작은 목표부터 천천히 시작해요.", style = MaterialTheme.typography.titleMedium)
            Text("원하는 목표만 켜세요. 추천값 4개 중 선택하거나 다섯 번째에서 직접 입력할 수 있습니다.")
            OutlinedButton(enabled = !busy, onClick = { submit(true) }) { Text("목표 설정 건너뛰기") }
            Text("건너뛰면 기존 목표도 모두 비활성화됩니다. 기록과 설정값은 삭제하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            if (ready) {
                GoalCard("하루 휴대폰 사용시간", "휴대폰 전체 사용량의 하루 목표입니다.", dailyOn, { dailyOn = it }, dailyPresets, dailyChoice, { dailyChoice = it }, !busy) {
                    NumericPair("시간 (0~24)", hours, { hours = it }, "분 (0~59)", minutes, { minutes = it }, !busy)
                }
                GoalCard("취침시간", "현재 기기의 현지 시각을 기준으로 합니다. 수면 측정 기능은 아닙니다.", bedtimeOn, { bedtimeOn = it }, bedtimePresets, bedtimeChoice, { bedtimeChoice = it }, !busy) {
                    NumericPair("시 (0~23)", bedHour, { bedHour = it }, "분 (0~59)", bedMinute, { bedMinute = it }, !busy)
                }
                GoalCard("일정 시간 사용 후 휴식", "사용 지표: $breakMetric. 새 목표는 Instagram의 확인된 숏폼 시청량을 기준으로 합니다. 기존 자동 미션과는 별개입니다.", breakOn, { breakOn = it }, breakPresets, breakChoice, { breakChoice = it }, !busy) {
                    NumericPair("사용 기준 (분)", useMinutes, { useMinutes = it }, "휴식 목표 (분)", restMinutes, { restMinutes = it }, !busy)
                }
                Button(enabled = !busy, onClick = { submit(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Text(if (busy) "저장 중..." else "저장하고 시작하기") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    }
    }
}

@Composable
private fun GoalCard(title: String, description: String, active: Boolean, onActive: (Boolean) -> Unit,
                     presets: List<Preset>, selected: Int, onSelected: (Int) -> Unit,
                     enabled: Boolean, custom: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, ItvLine), colors = CardDefaults.cardColors(containerColor = ItvMint)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = active, onCheckedChange = onActive, enabled = enabled)
            }
            Text(description, style = MaterialTheme.typography.bodySmall)
            if (active) {
                presets.forEachIndexed { index, preset ->
                    OutlinedButton(onClick = { onSelected(index) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(if (selected == index) 2.dp else 1.dp, if (selected == index) ItvGreen else ItvLine),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                        Text((if (selected == index) "● " else "○ ") + "${index + 1}. ${preset.label}")
                    }
                }
                OutlinedButton(onClick = { onSelected(4) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text((if (selected == 4) "● " else "○ ") + "5. 직접 입력")
                }
                if (selected == 4) custom()
            }
        }
    }
}

@Composable
private fun NumericPair(firstLabel: String, first: String, onFirst: (String) -> Unit,
                        secondLabel: String, second: String, onSecond: (String) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = first, onValueChange = { if (it.length <= 6 && it.all { c -> c in '0'..'9' }) onFirst(it) },
            label = { Text(firstLabel) }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = second, onValueChange = { if (it.length <= 6 && it.all { c -> c in '0'..'9' }) onSecond(it) },
            label = { Text(secondLabel) }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    }
}
