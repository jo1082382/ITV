package com.example.itv.presentation.jev

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.itv.domain.jev.JevCandidatePolicy
import com.example.itv.domain.jev.JevSettings
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.ItvLine
import com.example.itv.presentation.components.ItvMint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
internal fun CategoryProbabilityTestPage(
    vm: JevTestViewModel,
    s: JevSettings,
    busy: Boolean,
) {
    val configured by vm.store.tokenConfigured.collectAsState()
    val results by vm.simResults.collectAsState()
    val progress by vm.simProgress.collectAsState()

    var categoryOrdinal by rememberSaveable { mutableIntStateOf(SimAppCategory.SHORTFORM.ordinal) }
    var minutesText by rememberSaveable { mutableStateOf("15") }
    var weightOrdinal by rememberSaveable { mutableIntStateOf(SimWeightMode.CURRENT_1_TO_3.ordinal) }
    var selectedCompareId by rememberSaveable { mutableStateOf<String?>(null) }
    var filterCategoryOrdinal by rememberSaveable { mutableIntStateOf(-1) }

    // 아래 3개는 2번 섹션의 "로컬 직접 조합 비교" 전용이며 Jev 요청에는 절대 포함되지 않음
    var customEnvOrdinal by rememberSaveable { mutableIntStateOf(SimEnvironment.INDOOR_NEUTRAL.ordinal) }
    var customSessionOrdinal by rememberSaveable { mutableIntStateOf(SimSessionRatio.NORMAL.ordinal) }
    var customImmersionOrdinal by rememberSaveable { mutableIntStateOf(SimImmersionLevel.LOW.ordinal) }

    val category = SimAppCategory.entries[categoryOrdinal.coerceIn(0, SimAppCategory.entries.lastIndex)]
    val weightMode = SimWeightMode.entries[weightOrdinal.coerceIn(0, SimWeightMode.entries.lastIndex)]
    val customEnv = SimEnvironment.entries[customEnvOrdinal.coerceIn(0, SimEnvironment.entries.lastIndex)]
    val customSession = SimSessionRatio.entries[customSessionOrdinal.coerceIn(0, SimSessionRatio.entries.lastIndex)]
    val customImmersion = SimImmersionLevel.entries[customImmersionOrdinal.coerceIn(0, SimImmersionLevel.entries.lastIndex)]

    val rawMinutes = minutesText.toIntOrNull()?.coerceIn(1, 360) ?: 15
    val currentRequest = remember(category, rawMinutes, weightMode) {
        CategorySimRequest(
            category = category,
            rawMinutes = rawMinutes,
            weightMode = weightMode
        )
    }
    val baseThresholdPct = s.probabilityThreshold * 100.0

    val comparedResult = remember(results, selectedCompareId) {
        if (selectedCompareId != null) {
            results.firstOrNull { it.id == selectedCompareId } ?: results.firstOrNull()
        } else {
            results.firstOrNull()
        }
    }

    // 2번 섹션 로컬 비교에 사용할 기준 요청 (선택된 실측 결과가 있으면 그 카테고리·시간을 따라가고, 없으면 상단 선택값 사용)
    val comparisonBaseRequest = comparedResult?.request ?: currentRequest
    val customLocalCtx = remember(comparisonBaseRequest, customEnv, customSession, customImmersion) {
        comparisonBaseRequest.toLocalThresholdContext(customEnv, customSession, customImmersion)
    }
    val customLocalAdj = remember(s, customLocalCtx) {
        JevCandidatePolicy.computeThresholdAdjustment(s, customLocalCtx)
    }

    val canRequest = (!busy) && configured && s.collectionConsent && (minutesText.toIntOrNull() in 1..360)

    // 1. 순수 카테고리 · 시간 Jev 확률 측정
    TestSection("1. 카테고리 · 시간 Jev 확률 측정") {
        Text(
            text = "Jev에는 오직 선택한 앱 카테고리와 사용/환산 시간만 전송합니다. 주간·암실·취침·세션 비율·몰입도는 Jev에 보내지 않으며, 아래 2번에서 우리 앱의 개입 기준 확률과 로컬로만 비교합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!configured || !s.collectionConsent) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    text = if (!s.collectionConsent) {
                        "연결 탭에서 데이터 전송 동의를 저장해야 Jev 요청이 가능합니다. (아래 2번 로컬 상황별 기준 비교는 바로 확인 가능)"
                    } else {
                        "연결 탭에서 ITV_TEST_TOKEN을 저장해야 Jev 요청이 가능합니다. (아래 2번 로컬 상황별 기준 비교는 바로 확인 가능)"
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // 앱 카테고리 선택
        CompactCheckGroup("앱 카테고리") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SimAppCategory.entries.forEachIndexed { idx, item ->
                    FilterChip(
                        selected = categoryOrdinal == idx,
                        onClick = { categoryOrdinal = idx },
                        label = { Text(item.shortLabel) },
                        enabled = !busy
                    )
                }
            }
            Text(
                text = "선택: ${category.label} · Jev 전송 category=${category.appCategory}, phase=${category.activityPhase}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 실사용 시간 + 환산 비율
        CompactCheckGroup("실사용 시간 및 환산 비율") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(5, 10, 15, 20, 30, 45, 60).forEach { min ->
                    FilterChip(
                        selected = rawMinutes == min && minutesText == min.toString(),
                        onClick = { minutesText = min.toString() },
                        label = { Text("${min}분") },
                        enabled = !busy
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { v -> if (v.length <= 3 && v.all { it.isDigit() }) minutesText = v },
                    label = { Text("직접 입력 (1~360분)") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ItvMint,
                    border = BorderStroke(1.dp, ItvLine)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text("Jev 등가 시간", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${"%.1f".format(currentRequest.equivalentMinutes)}분",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = ItvGreen
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SimWeightMode.entries.forEachIndexed { idx, mode ->
                    FilterChip(
                        selected = weightOrdinal == idx,
                        onClick = { weightOrdinal = idx },
                        label = { Text(mode.label) },
                        enabled = !busy && !category.isShortform
                    )
                }
            }
            Text(
                text = if (category.isShortform) {
                    "숏폼은 항상 1:1(원본 시간 그대로) 환산됩니다."
                } else {
                    "현재 앱은 비숏폼을 1:3으로 환산합니다. 1:2나 1:1로 바꿔 Jev가 카테고리와 시간 중 무엇에 더 반응하는지 확인할 수 있습니다."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Jev 전송 페이로드 미리보기 (순수 카테고리 + 시간 확인용)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, ItvGreen.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "Jev 전송 요약: ${category.shortLabel} ${rawMinutes}분 → 등가 ${"%.1f".format(currentRequest.equivalentMinutes)}분 (${if (category.isShortform) "1:1" else weightMode.shortLabel})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ItvGreen
                )
                Text(
                    text = "환경·조도·취침·세션 비율·몰입도 미전송 (순수 카테고리×시간 판단)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 진행 표시 및 실행 버튼
        progress?.let { status ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = ItvGreen.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(status, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = ItvGreen)
                    TextButton(onClick = { vm.cancelCategorySimulation() }) {
                        Text("취소")
                    }
                }
            }
        }

        Button(
            onClick = { vm.runCategorySimulation(currentRequest) },
            enabled = canRequest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("선택 조건 Jev 확률 측정 (1회)")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val batch = category.curveMinutes.map { m ->
                        currentRequest.copy(
                            rawMinutes = m,
                            labelOverride = "${category.shortLabel} ${m}분"
                        )
                    }
                    vm.runCategorySimulations(batch, reasonPrefix = "CATEGORY_SIM_CURVE")
                },
                enabled = canRequest,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "시간대 곡선 (${category.curveMinutes.joinToString("·")}분 · 4회)",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            OutlinedButton(
                onClick = {
                    val batch = SimAppCategory.entries.map { cat ->
                        CategorySimRequest(
                            category = cat,
                            rawMinutes = rawMinutes,
                            weightMode = weightMode,
                            labelOverride = "${cat.shortLabel} ${rawMinutes}분"
                        )
                    }
                    vm.runCategorySimulations(batch, reasonPrefix = "CATEGORY_SIM_ALL6")
                },
                enabled = canRequest,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "6개 카테고리 동일 ${rawMinutes}분 (6회)",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

    // 2. 우리 앱 내 상황별 개입 기준 확률 변동 비교 (100% 로컬 전용)
    TestSection("2. 앱 내 상황별 개입 기준 확률 비교 (로컬 전용)") {
        Text(
            text = "Jev 판단과는 완전히 분리된 우리 앱 내부의 개입 기준 확률 변동표입니다. 위에서 측정한 Jev 실측 확률이 주간·어두운 환경·취침·세션 비율·몰입도 등 각 상황에서 통과하는지 바로 비교합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!s.personalizedEnabled) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFFEF3C7)
            ) {
                Text(
                    text = "현재 '판단 설정'에서 개인화 동적 기준 보정이 꺼져 있어 모든 상황이 기본 ${"%.0f".format(baseThresholdPct)}%로 고정 표시됩니다.",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF92400E)
                )
            }
        }

        if (comparedResult != null) {
            val probPct = comparedResult.probability * 100.0
            val ratioTag = if (comparedResult.request.category.isShortform) "1:1" else comparedResult.request.weightMode.shortLabel
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = ItvGreen.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, ItvGreen)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "비교 중인 Jev 실측 확률",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ItvGreen
                        )
                        Text(
                            text = "${"%.1f".format(probPct)}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = ItvGreen
                        )
                    }
                    Text(
                        text = "${comparedResult.request.category.shortLabel} ${comparedResult.request.rawMinutes}분 (등가 ${"%.1f".format(comparedResult.request.equivalentMinutes)}분 · $ratioTag) · 아래 3번 결과표에서 다른 항목 선택 가능",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, ItvLine)
            ) {
                Text(
                    text = "현재 '${currentRequest.category.shortLabel} ${currentRequest.rawMinutes}분(등가 ${"%.1f".format(currentRequest.equivalentMinutes)}분)' 기준으로 9개 상황의 개입 기준선을 표시합니다. 위에서 Jev 확률을 측정하면 통과/미달이 자동 대조됩니다.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 9가지 대표 상황 비교 카드
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SimContextPresets.all.forEach { preset ->
                val presetCtx = comparisonBaseRequest.toLocalThresholdContext(
                    environment = preset.environment,
                    sessionRatio = preset.sessionRatio,
                    immersion = preset.immersion
                )
                val presetAdj = JevCandidatePolicy.computeThresholdAdjustment(s, presetCtx)
                val isSelectedPreset = customEnv == preset.environment &&
                    customSession == preset.sessionRatio &&
                    customImmersion == preset.immersion

                ScenarioThresholdCard(
                    preset = preset,
                    baseThresholdPct = baseThresholdPct,
                    adj = presetAdj,
                    comparedProbability = comparedResult?.probability,
                    isGeneralOrLongform = !comparisonBaseRequest.category.isShortform,
                    isSelected = isSelectedPreset
                ) {
                    customEnvOrdinal = preset.environment.ordinal
                    customSessionOrdinal = preset.sessionRatio.ordinal
                    customImmersionOrdinal = preset.immersion.ordinal
                }
            }
        }

        // 직접 상황 조합 (로컬 비교 전용)
        CompactCheckGroup("직접 상황 조합 비교 (로컬 전용 · Jev 미전송)") {
            Text("환경 · 시간대", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SimEnvironment.entries.forEachIndexed { idx, env ->
                    FilterChip(
                        selected = customEnvOrdinal == idx,
                        onClick = { customEnvOrdinal = idx },
                        label = { Text(env.shortLabel) }
                    )
                }
            }
            Text("세션 비율", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SimSessionRatio.entries.forEachIndexed { idx, ratio ->
                    FilterChip(
                        selected = customSessionOrdinal == idx,
                        onClick = { customSessionOrdinal = idx },
                        label = { Text(ratio.label) }
                    )
                }
            }
            Text("몰입도 (전환 · 스와이프)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SimImmersionLevel.entries.forEachIndexed { idx, level ->
                    FilterChip(
                        selected = customImmersionOrdinal == idx,
                        onClick = { customImmersionOrdinal = idx },
                        label = { Text(level.label) }
                    )
                }
            }
            val customEffPct = customLocalAdj.effectiveThreshold * 100.0
            val customDeltaPct = customLocalAdj.totalDelta * 100.0
            val customSign = if (customDeltaPct > 0) "+" else ""
            val customProbPct = comparedResult?.probability?.times(100.0)
            val customPassed = customProbPct != null && customProbPct >= customEffPct
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "조합 기준 ${"%.0f".format(customEffPct)}% ($customSign${"%.0f".format(customDeltaPct)}%p)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = deltaColor(customDeltaPct)
                        )
                        if (customProbPct != null) {
                            val diff = customProbPct - customEffPct
                            val diffSign = if (diff >= 0) "+" else ""
                            Text(
                                text = if (customPassed) "통과 ($diffSign${"%.1f".format(diff)}%p)" else "미달 ($diffSign${"%.1f".format(diff)}%p)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (customPassed) ItvGreen else Color(0xFF92400E)
                            )
                        }
                    }
                    Text(
                        text = formatFactorChips(customLocalAdj),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // 3. 측정 결과 누적 비교표
    TestSection("3. 카테고리 · 시간 측정 결과 비교표 (${results.size}건)") {
        if (results.isEmpty()) {
            Text(
                text = "아직 측정 결과가 없습니다. 1번에서 카테고리와 시간을 선택해 단건·시간대 곡선·6개 카테고리 동일 시간 비교를 실행해 보세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val presentCategories = remember(results) {
                results.asSequence().map { it.request.category }.distinct().sortedBy { it.ordinal }.toList()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = filterCategoryOrdinal == -1,
                        onClick = { filterCategoryOrdinal = -1 },
                        label = { Text("전체 (${results.size})") }
                    )
                    presentCategories.forEach { cat ->
                        val count = results.count { it.request.category == cat }
                        FilterChip(
                            selected = filterCategoryOrdinal == cat.ordinal,
                            onClick = { filterCategoryOrdinal = cat.ordinal },
                            label = { Text("${cat.shortLabel} ($count)") }
                        )
                    }
                }
                TextButton(
                    onClick = {
                        vm.clearSimResults()
                        selectedCompareId = null
                        filterCategoryOrdinal = -1
                    },
                    enabled = !busy
                ) {
                    Text("초기화")
                }
            }

            val filtered = remember(results, filterCategoryOrdinal) {
                if (filterCategoryOrdinal == -1) results
                else results.filter { it.request.category.ordinal == filterCategoryOrdinal }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filtered.forEach { item ->
                    val isComparing = comparedResult?.id == item.id
                    SimResultComparisonCard(
                        item = item,
                        isComparing = isComparing,
                        onSelectCompare = { selectedCompareId = item.id }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScenarioThresholdCard(
    preset: SimContextPreset,
    baseThresholdPct: Double,
    adj: JevCandidatePolicy.ThresholdAdjustment,
    comparedProbability: Double?,
    isGeneralOrLongform: Boolean,
    isSelected: Boolean,
    onApply: () -> Unit,
) {
    val effPct = adj.effectiveThreshold * 100.0
    val deltaPct = adj.totalDelta * 100.0
    val deltaSign = if (deltaPct > 0) "+" else ""
    val probPct = comparedProbability?.times(100.0)
    val passed = probPct != null && probPct >= effPct
    val gapPct = probPct?.let { it - effPct }
    val intervalSec = comparedProbability?.let {
        JevCandidatePolicy.calculateDynamicInterval(adj.effectiveThreshold, it, isGeneralOrLongform)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onApply),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) ItvGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) ItvGreen else ItvLine)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = preset.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) ItvGreen else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = preset.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = deltaChipBg(deltaPct)
                    ) {
                        Text(
                            text = if (abs(deltaPct) < 0.5) "0%p" else "$deltaSign${"%.0f".format(deltaPct)}%p",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = deltaColor(deltaPct)
                        )
                    }
                    Text(
                        text = "기준 ${"%.0f".format(effPct)}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Text(
                text = formatFactorChips(adj),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (probPct != null && gapPct != null && intervalSec != null) {
                ProbabilityGaugeBar(
                    probabilityPct = probPct,
                    baseThresholdPct = baseThresholdPct,
                    effectiveThresholdPct = effPct,
                    passed = passed
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val gapSign = if (gapPct >= 0) "+" else ""
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (passed) ItvGreen.copy(alpha = 0.16f) else Color(0xFFFEF3C7)
                    ) {
                        Text(
                            text = if (passed) {
                                "개입 통과 ($gapSign${"%.1f".format(gapPct)}%p)"
                            } else {
                                "개입 미달 ($gapSign${"%.1f".format(gapPct)}%p)"
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (passed) ItvGreen else Color(0xFF92400E)
                        )
                    }
                    Text(
                        text = "동적 재평가 ${intervalSec}초",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SimResultComparisonCard(
    item: CategorySimResult,
    isComparing: Boolean,
    onSelectCompare: () -> Unit,
) {
    val probPct = item.probability * 100.0
    val basePct = item.baseThreshold * 100.0
    val gapPct = probPct - basePct
    val gapSign = if (gapPct >= 0) "+" else ""
    val ratioTag = if (item.request.category.isShortform) "1:1" else item.request.weightMode.shortLabel
    val timeStr = Instant.ofEpochMilli(item.recordedAt)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelectCompare),
        shape = RoundedCornerShape(14.dp),
        color = if (isComparing) ItvGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (isComparing) 1.5.dp else 1.dp, if (isComparing) ItvGreen else ItvLine)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = RoundedCornerShape(6.dp), color = ItvMint) {
                            Text(
                                text = item.request.category.shortLabel,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = ItvGreen
                            )
                        }
                        Text(
                            text = "${item.request.rawMinutes}분 → 등가 ${"%.1f".format(item.request.equivalentMinutes)}분 ($ratioTag)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "순수 카테고리·시간 측정 · 기본 기준 ${"%.0f".format(basePct)}% 대비",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${"%.1f".format(probPct)}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (item.passedBase) ItvGreen else Color(0xFFB45309)
                    )
                    Text(
                        text = if (item.passedBase) "기본 통과 ($gapSign${"%.1f".format(gapPct)}%p)" else "기본 미달 ($gapSign${"%.1f".format(gapPct)}%p)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (item.passedBase) ItvGreen else Color(0xFF92400E)
                    )
                }
            }

            ProbabilityGaugeBar(
                probabilityPct = probPct,
                baseThresholdPct = basePct,
                effectiveThresholdPct = basePct,
                passed = item.passedBase
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$timeStr · ${item.model ?: "Jev"} · ${item.latencyMs?.let { "${it}ms" } ?: "-"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isComparing) "위 9개 상황과 비교 중 ✓" else "탭하여 위 9개 상황과 비교",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isComparing) FontWeight.Bold else FontWeight.Normal,
                    color = if (isComparing) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatFactorChips(adj: JevCandidatePolicy.ThresholdAdjustment): String {
    fun fmt(name: String, delta: Double): String {
        val p = delta * 100.0
        val sign = if (p > 0) "+" else ""
        return if (abs(p) < 0.5) "$name 0" else "$name $sign${"%.0f".format(p)}%p"
    }
    val immersionTotal = adj.swipeDelta + adj.contentSwitchDelta
    return listOf(
        fmt("환경", adj.bedtimeDelta),
        fmt("세션", adj.sessionDelta),
        fmt("몰입", immersionTotal),
        fmt("피로", adj.fatigueDelta)
    ).joinToString(" · ")
}

private fun deltaColor(deltaPct: Double): Color = when {
    deltaPct <= -0.5 -> Color(0xFFB91C1C)
    deltaPct >= 0.5 -> Color(0xFF1D4ED8)
    else -> Color(0xFF4B5563)
}

private fun deltaChipBg(deltaPct: Double): Color = when {
    deltaPct <= -0.5 -> Color(0xFFFEE2E2)
    deltaPct >= 0.5 -> Color(0xFFDBEAFE)
    else -> Color(0xFFE5E7EB)
}
