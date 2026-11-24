package com.example.itv.presentation.coaching

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.itv.domain.coaching.CoachingTone
import com.example.itv.domain.coaching.InterventionType
import com.example.itv.domain.coaching.UserCoachingSettings
import com.example.itv.infrastructure.coaching.UserCoachingSettingsStore
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.ItvLine
import com.example.itv.presentation.components.ItvMint
import com.example.itv.presentation.components.PuppyFaceMascot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachingSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember { UserCoachingSettingsStore.getInstance(context) }
    val settings by store.settings.collectAsState()

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Effective intervention mode: if user chose overlay but permission is OFF, fallback to notification
    val isActuallyNotificationOnly = settings.interventionType == InterventionType.NOTIFICATION_ONLY || !hasOverlayPermission

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("코칭 설정", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = ItvGreen) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("‹ 뒤로", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ItvGreen)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // 안내 헤더 카드
            CoachingHeaderCard()

            // 1. 개입 방식 설정 (캐릭터 개입 vs 알림만 받기)
            InterventionModeSection(
                selectedType = settings.interventionType,
                hasOverlayPermission = hasOverlayPermission,
                isActuallyNotificationOnly = isActuallyNotificationOnly,
                onSelectType = { store.updateInterventionType(it) },
                onRequestOverlayPermission = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )

            HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))

            // 2. 상황별 자동 개입 강도 안내 (수동 선택 대신 취침 전/암실 사전 개입 → 본 개입 → 자동 격상)
            AutoIntensityPolicySection(
                isActuallyNotificationOnly = isActuallyNotificationOnly
            )

            HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))

            // 3. 개입 시 말투 설정 (부드럽게, 장난스럽게, 강하게)
            CoachingToneSection(
                selectedTone = settings.tone,
                onSelectTone = { store.updateTone(it) }
            )

            HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))

            // 5. 추천 휴식 미션 목록 모아보기
            RestMissionListSection()

            HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))

            // 6. 취침 개입 (취침 3분기 케어 On/Off)
            BedtimeCareSection(
                enabled = settings.bedtimeCareEnabled,
                settings = settings,
                onToggle = { store.updateBedtimeCareEnabled(it) },
                onUpdateCustomBedtime = { hour, minute ->
                    store.updateCustomBedtime(hour, minute)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BedtimeCareSection(
    enabled: Boolean,
    settings: UserCoachingSettings,
    onToggle: (Boolean) -> Unit,
    onUpdateCustomBedtime: (Int?, Int?) -> Unit
) {
    val context = LocalContext.current
    val ambientTracker = remember { com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(context) }
    val ambientSnapshot by ambientTracker.snapshot.collectAsState()

    var showHelpDialog by remember { mutableStateOf(false) }
    var showTimePickerDialog by remember { mutableStateOf(false) }

    val displayHour = settings.customBedtimeHour ?: ambientSnapshot.estimatedBedtimeHour ?: 23
    val displayMinute = settings.customBedtimeMinute ?: ambientSnapshot.estimatedBedtimeMinute ?: 30
    val ampm = if (displayHour < 12) "오전" else "오후"
    val hour12 = when (val h = displayHour % 12) { 0 -> 12 else -> h }
    val timeText = "$ampm ${hour12}시 ${String.format("%02d", displayMinute)}분"
    val isCustom = settings.customBedtimeHour != null

    val diffMinutes = ambientSnapshot.bedtimeDistanceMinutes
    val statusText = when {
        diffMinutes == null -> "취침 시각 추정 중"
        diffMinutes < -60 -> "취침까지 ${(-diffMinutes) / 60}시간 ${(-diffMinutes) % 60}분 남음 · 일상 활동 시간대 (개입 비대상)"
        diffMinutes in -60..-1 -> "취침까지 ${-diffMinutes}분 남음 · 취침 준비 시간대 (암실 30분 사용 시 개입)"
        diffMinutes == 0 -> "지금이 평소 취침 시각입니다"
        else -> "평소 취침 시각보다 ${diffMinutes}분 지남 · 심야 수면 지연 케어 대상"
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🌙", fontSize = 20.sp)
                    Text("취침 개입 안내", fontWeight = FontWeight.Bold, color = ItvGreen)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "어두운 방에서 평소 취침 시간이 지났거나 직전에 잠들지 못하고 장시간 스마트폰을 사용할 때, 수면을 방해하지 않고 자연스럽게 잠자리에 들 수 있도록 돕는 3단계 수면 케어입니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, ItvLine)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("🌙 3대 취침 케어 구성", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = ItvGreen)
                            Text("1. 마음 비우기 노트: 잠들기 전 잡념이나 할 일을 메모하여 도파민 및 뇌 각성 완화", style = MaterialTheme.typography.bodySmall)
                            Text("2. 화면 흑백 전환: 화려한 색채 자극을 줄여 눈 피로 완화 및 빠른 취침 유도", style = MaterialTheme.typography.bodySmall)
                            Text("3. 수면 ASMR: 편안한 빗소리·백색소음으로 뇌파 안정", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "※ 스위치를 끄면 야간 시간대에도 취침 3분기 케어 대신 일반 휴식 코칭 미션이 제공됩니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("확인", fontWeight = FontWeight.Bold, color = ItvGreen)
                }
            }
        )
    }

    if (showTimePickerDialog) {
        var selectedModeIsCustom by remember { mutableStateOf(settings.customBedtimeHour != null) }
        var isAm by remember { mutableStateOf(displayHour < 12) }
        var currentHour12 by remember {
            mutableStateOf(when (val h = displayHour % 12) { 0 -> 12 else -> h })
        }
        var currentMinute by remember { mutableStateOf(displayMinute) }

        AlertDialog(
            onDismissRequest = { showTimePickerDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("⏰", fontSize = 20.sp)
                    Text("평소 취침 시각 설정", fontWeight = FontWeight.Bold, color = ItvGreen)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "야간 취침 개입(3분기 케어)의 기준이 되는 평소 취침 시각을 설정합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 모드 선택: 자동 vs 직접 지정
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { selectedModeIsCustom = false },
                            shape = RoundedCornerShape(10.dp),
                            color = if (!selectedModeIsCustom) ItvGreen else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (!selectedModeIsCustom) ItvGreen else ItvLine),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "수면 기록 자동",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!selectedModeIsCustom) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Surface(
                            onClick = { selectedModeIsCustom = true },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedModeIsCustom) ItvGreen else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (selectedModeIsCustom) ItvGreen else ItvLine),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "직접 시각 지정",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedModeIsCustom) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    if (!selectedModeIsCustom) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = ItvMint),
                            border = BorderStroke(1.dp, ItvLine)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("📊 수면 기록 기반 자동 추정", fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = ItvGreen)
                                Text(
                                    "스마트폰 비사용 시간대와 수면 기록을 분석하여 일상 취침 패턴에 맞춰 유동적으로 취침 시간을 계산합니다.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        // 직접 시각 지정 UI (오전/오후, 시, 분)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, ItvLine)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // AM/PM 토글
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = { isAm = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isAm) ItvGreen else MaterialTheme.colorScheme.surface,
                                            contentColor = if (isAm) Color.White else MaterialTheme.colorScheme.onSurface
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("오전 (AM)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = { isAm = false },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (!isAm) ItvGreen else MaterialTheme.colorScheme.surface,
                                            contentColor = if (!isAm) Color.White else MaterialTheme.colorScheme.onSurface
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("오후 (PM)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                // 시간 및 분 선택
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // 시 조절
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                currentHour12 = if (currentHour12 <= 1) 12 else currentHour12 - 1
                                            }
                                        ) { Text("◀", fontSize = 14.sp) }
                                        Text(
                                            text = "${currentHour12}시",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp)
                                        )
                                        IconButton(
                                            onClick = {
                                                currentHour12 = if (currentHour12 >= 12) 1 else currentHour12 + 1
                                            }
                                        ) { Text("▶", fontSize = 14.sp) }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    // 분 조절
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                currentMinute = (currentMinute - 10 + 60) % 60
                                            }
                                        ) { Text("◀", fontSize = 14.sp) }
                                        Text(
                                            text = "${String.format("%02d", currentMinute)}분",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp)
                                        )
                                        IconButton(
                                            onClick = {
                                                currentMinute = (currentMinute + 10) % 60
                                            }
                                        ) { Text("▶", fontSize = 14.sp) }
                                    }
                                }

                                // 분 퀵 칩 (00분, 15분, 30분, 45분)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    listOf(0, 15, 30, 45).forEach { m ->
                                        val isSel = currentMinute == m
                                        Surface(
                                            onClick = { currentMinute = m },
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isSel) ItvGreen else MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, if (isSel) ItvGreen else ItvLine),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(modifier = Modifier.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "${String.format("%02d", m)}분",
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedModeIsCustom) {
                            val h24 = if (isAm) {
                                if (currentHour12 == 12) 0 else currentHour12
                            } else {
                                if (currentHour12 == 12) 12 else currentHour12 + 12
                            }
                            onUpdateCustomBedtime(h24, currentMinute)
                        } else {
                            onUpdateCustomBedtime(null, null)
                        }
                        ambientTracker.refreshBedtimeEstimates()
                        showTimePickerDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ItvGreen)
                ) {
                    Text("저장", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePickerDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ItvLine)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🌙", fontSize = 18.sp)
                    Text(
                        text = "취침 개입",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ItvGreen
                    )
                    Surface(
                        onClick = { showHelpDialog = true },
                        shape = CircleShape,
                        color = ItvMint,
                        border = BorderStroke(1.dp, ItvLine),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("?", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ItvGreen)
                        }
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ItvGreen
                    )
                )
            }

            if (enabled) {
                // 평소 취침 시각 카드
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, ItvLine)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("⏰", fontSize = 16.sp)
                                Text(
                                    text = "평소 취침 시각",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = ItvGreen
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isCustom) ItvGreen.copy(alpha = 0.15f) else Color(0xFF0284C7).copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, if (isCustom) ItvGreen.copy(alpha = 0.5f) else Color(0xFF0284C7).copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = if (isCustom) "직접 설정" else "수면 기록 자동 분석",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isCustom) ItvGreen else Color(0xFF0284C7),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = timeText,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = statusText,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { showTimePickerDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("시각 변경", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 취침 개입 철저한 오발동 방지 규칙 카드
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = ItvMint),
                    border = BorderStroke(1.dp, ItvLine)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("🛡️", fontSize = 14.sp)
                            Text(
                                "취침 개입 철저한 오발동 방지 규칙",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = ItvGreen
                            )
                        }
                        Text(
                            "• 어두운 방 필수: 실내 조명이 켜져 있거나(20 lux 초과) 낮 시간대에는 절대 취침 개입이 뜨지 않습니다.\n" +
                            "• 취침 시간대 한정: 취침 2시간 전 등 일상 시간대에는 뜨지 않으며, 취침 1시간 이내(어두운 방 30분 연속 사용) 또는 취침 시간 이후에만 발동합니다.\n" +
                            "• 금융 앱 보호: 은행, 뱅킹, 결제 앱 사용 중에는 모든 코칭 개입이 완전 차단됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        com.example.itv.presentation.sleep.BedtimeCareController.getInstance(context).showBedtimeCareDialog()
                    },
                    modifier = Modifier.weight(1.1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("🌙 3분기 팝업", fontSize = 11.5.sp, maxLines = 1)
                }
                Button(
                    onClick = {
                        com.example.itv.presentation.sleep.BedtimeCareController.getInstance(context).startGrayscaleMode(forceManual = true)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("🖤 흑백", fontSize = 11.5.sp, maxLines = 1)
                }
                Button(
                    onClick = {
                        com.example.itv.presentation.sleep.BedtimeCareController.getInstance(context).stopGrayscaleMode()
                    },
                    modifier = Modifier.weight(1.1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("🤍 흑백 취소", fontSize = 11.5.sp, maxLines = 1)
                }
            }
            Button(
                onClick = {
                    context.startActivity(Intent(context, com.example.itv.presentation.character.CompanionPreviewActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ItvGreen),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("🐶 강아지 개입 및 야간 종합 테스트 열기", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CoachingHeaderCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = ItvMint),
        border = BorderStroke(1.dp, ItvLine)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PuppyFaceMascot(Modifier.size(46.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "나를 위한 맞춤 코칭",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ItvGreen
                )
                Text(
                    text = "숏폼 과몰입 방지를 위한 개입 방식, 빈도와 강도, 캐릭터의 화면 크기와 말투를 자유롭게 조정할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InterventionModeSection(
    selectedType: InterventionType,
    hasOverlayPermission: Boolean,
    isActuallyNotificationOnly: Boolean,
    onSelectType: (InterventionType) -> Unit,
    onRequestOverlayPermission: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "개입 방식",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ItvGreen
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isActuallyNotificationOnly) Color(0xFFFFF3E0) else ItvMint,
                border = BorderStroke(1.dp, if (isActuallyNotificationOnly) Color(0xFFFFB74D) else ItvLine)
            ) {
                Text(
                    text = if (isActuallyNotificationOnly) "알림 모드 동작 중" else "캐릭터 개입 동작 중",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActuallyNotificationOnly) Color(0xFFE65100) else ItvGreen,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 캐릭터 개입 옵션 카드
        InterventionOptionCard(
            title = "캐릭터 개입 (오버레이 + 알림)",
            description = "숏폼 시청 중 화면 위에 강아지 캐릭터가 나타나 대화와 미션으로 휴식을 권합니다.",
            isSelected = selectedType == InterventionType.CHARACTER_OVERLAY,
            onClick = { onSelectType(InterventionType.CHARACTER_OVERLAY) }
        )

        // 알림만 받기 옵션 카드
        InterventionOptionCard(
            title = "알림만 받기 (푸시 알림)",
            description = "화면을 가리지 않고 상단 알림창에 리마인드 푸시 메시지만 조용히 전송합니다.",
            isSelected = selectedType == InterventionType.NOTIFICATION_ONLY,
            onClick = { onSelectType(InterventionType.NOTIFICATION_ONLY) }
        )

        // 오버레이 권한이 비활성화되어 있는 경우 안내 배너
        if (!hasOverlayPermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                border = BorderStroke(1.dp, Color(0xFFFFE082))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("⚠️", fontSize = 16.sp)
                        Text(
                            text = "'다른 앱 위에 표시' 권한 비활성화됨",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF57F17)
                        )
                    }
                    Text(
                        text = "현재 오버레이 권한이 꺼져 있어 캐릭터 개입을 선택하셔도 자동으로 [알림만 받기]로 전환되어 동작합니다. 캐릭터 개입을 원하시면 권한을 허용해 주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF5D4037)
                    )
                    Button(
                        onClick = onRequestOverlayPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57F17)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    ) {
                        Text("설정에서 다른 앱 위에 표시 권한 켜기", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun InterventionOptionCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) ItvMint else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) ItvGreen else ItvLine),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = ItvGreen)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) ItvGreen else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AutoIntensityPolicySection(
    isActuallyNotificationOnly: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "상황별 자동 개입 강도",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ItvGreen
                )
                if (isActuallyNotificationOnly) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(캐릭터 모드 시 적용)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "직접 강도를 고르지 않아도 취침 시간·주변 밝기·연속 사용 상황에 맞춰 개입 강도가 자동으로 조절됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ItvMint),
            border = BorderStroke(1.dp, ItvLine)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AutoIntensityTierRow(
                    badge = "사전 개입 · 약하게",
                    badgeBg = Color(0xFFDBEAFE),
                    badgeText = Color(0xFF1D4ED8),
                    description = "취침 전·경과 암실이나 어두운 환경에서 일정 시간 사용 시, 본 개입 전에 강아지가 화면 구석에서 조용히 먼저 알려줍니다. (6초 후 자동 퇴장 · 터치 시 즉시 휴식)"
                )
                HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))
                AutoIntensityTierRow(
                    badge = "기본 개입 · 보통",
                    badgeBg = ItvGreen.copy(alpha = 0.14f),
                    badgeText = ItvGreen,
                    description = "연속 시청·피로도 기준에 도달하면 균형 있게 휴식을 제안합니다. 취침 경과 암실에서는 사전 개입 이후에도 계속 시청하면 취침 케어로 자동 전환됩니다."
                )
                HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))
                AutoIntensityTierRow(
                    badge = "자동 격상 · 강하게",
                    badgeBg = Color(0xFFFEE2E2),
                    badgeText = Color(0xFFB91C1C),
                    description = "휴식 제안을 연속 2회 이상 미루거나 무응답하면 자동으로 강한 개입으로 격상되어 과몰입을 차단하며, 휴식을 마치면 기본 단계로 복귀합니다."
                )
            }
        }
    }
}

@Composable
private fun AutoIntensityTierRow(
    badge: String,
    badgeBg: Color,
    badgeText: Color,
    description: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = badgeBg
        ) {
            Text(
                text = badge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = badgeText
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CoachingToneSection(
    selectedTone: CoachingTone,
    onSelectTone: (CoachingTone) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "개입 시 캐릭터 말투",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ItvGreen
            )
            Text(
                text = "강아지 캐릭터가 휴식을 제안할 때 사용하는 대화 톤을 선택합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 3개 선택 세그먼트 (부드럽게 / 장난스럽게 / 강하게)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CoachingTone.entries.forEach { tone ->
                val isSelected = tone == selectedTone
                Surface(
                    onClick = { onSelectTone(tone) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) ItvGreen else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (isSelected) ItvGreen else ItvLine)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = tone.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = tone.subtitle,
                            fontSize = 10.sp,
                            color = if (isSelected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 선택된 말투의 실제 대사 미리보기 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, ItvLine)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("💬", fontSize = 16.sp)
                        Text(
                            text = "캐릭터 대사 미리보기 (${selectedTone.title})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = ItvGreen
                        )
                    }
                    Text(
                        text = "상황별 랜덤 발화 적용됨 🎲",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 강아지와 말풍선
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PuppyFaceMascot(Modifier.size(52.dp))

                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 4.dp,
                            topEnd = 14.dp,
                            bottomEnd = 14.dp,
                            bottomStart = 14.dp
                        ),
                        color = ItvMint,
                        border = BorderStroke(1.dp, ItvLine),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = selectedTone.sampleDialog,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp),
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RestMissionListSection() {
    var expanded by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("전체") }

    val categories = listOf("전체", "목·경추 교정", "어깨·등 이완", "허리·척추 교정", "식탁·책상 거치", "공통 쿨다운", "강한 개입 (엎어두기)")
    val allMissions = remember { com.example.itv.domain.coaching.RestMissionCatalog.ALL_RECOMMENDATIONS }
    val filteredMissions = remember(selectedCategory) {
        if (selectedCategory == "전체") allMissions
        else allMissions.filter { it.category == selectedCategory }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "📋 추천 휴식 미션 목록 (${allMissions.size}가지)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ItvGreen
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = if (expanded) "접기 ▲" else "목록 보기 ▼",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ItvGreen
                    )
                }
            }
            Text(
                text = "휴식 시 그 자리에서 15~30초간 간단히 실천할 수 있는 스트레칭 및 쿨다운 미션들입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded) {
            // 카테고리 필터 칩
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                categories.take(4).forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat, fontSize = 11.sp) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                categories.drop(4).forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat, fontSize = 11.sp) }
                    )
                }
            }

            // 미션 카드 리스트
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filteredMissions.forEach { mission ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (mission.isFacedownRequired) ItvMint else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (mission.isFacedownRequired) ItvGreen.copy(alpha = 0.5f) else ItvLine)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(mission.icon, fontSize = 22.sp)
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = mission.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (mission.isFacedownRequired) ItvGreen else MaterialTheme.colorScheme.onSurface
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (mission.isFacedownRequired) ItvGreen else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = mission.category,
                                            fontSize = 9.sp,
                                            color = if (mission.isFacedownRequired) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                val annotatedAction = androidx.compose.ui.text.buildAnnotatedString {
                                    append(mission.action)
                                    mission.boldPhrases.forEach { phrase ->
                                        val startIdx = mission.action.indexOf(phrase)
                                        if (startIdx >= 0) {
                                            addStyle(
                                                style = androidx.compose.ui.text.SpanStyle(
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                ),
                                                start = startIdx,
                                                end = startIdx + phrase.length
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = annotatedAction,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // 접혀 있을 때 요약 카드
            Card(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = ItvMint),
                border = BorderStroke(1.dp, ItvLine)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "총 ${allMissions.size}가지 미션 등록됨 (터치하여 펼치기)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ItvGreen
                        )
                        Text(
                            text = "🐢 목 5종 · 🙆 어깨 5종 · 🧍 허리 4종 · 🍽️ 테이블 5종 · 🌬️ 공통 6종 · 🛑 엎어두기 4종",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text("❯", color = ItvGreen, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
