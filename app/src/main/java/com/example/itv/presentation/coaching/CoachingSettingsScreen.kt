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
import com.example.itv.domain.coaching.CharacterIntensity
import com.example.itv.domain.coaching.CoachingIntensity
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

            // 2. 코칭 강도 설정 (시청 시간·피로도에 따른 개입 빈도: 약하게, 보통, 강하게)
            CoachingIntensitySection(
                selectedIntensity = settings.coachingIntensity,
                onSelectIntensity = { store.updateCoachingIntensity(it) }
            )

            HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))

            // 3. 캐릭터 개입 강도 설정 (화면 등장 비율 및 모션 활발함: 약하게, 보통, 강하게)
            CharacterIntensitySection(
                selectedIntensity = settings.characterIntensity,
                isActuallyNotificationOnly = isActuallyNotificationOnly,
                onSelectIntensity = { store.updateCharacterIntensity(it) }
            )

            HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))

            // 4. 개입 시 말투 설정 (부드럽게, 장난스럽게, 강하게)
            CoachingToneSection(
                selectedTone = settings.tone,
                onSelectTone = { store.updateTone(it) }
            )

            HorizontalDivider(color = ItvLine.copy(alpha = 0.6f))

            // 5. 추천 휴식 미션 목록 모아보기
            RestMissionListSection()

            Spacer(modifier = Modifier.height(16.dp))
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
private fun CoachingIntensitySection(
    selectedIntensity: CoachingIntensity,
    onSelectIntensity: (CoachingIntensity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "코칭 강도",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ItvGreen
            )
            Text(
                text = "숏폼 시청 시간 및 피로도에 따른 개입 빈도와 민감도를 설정합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 3개 선택 세그먼트 (약하게 / 보통 / 강하게)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CoachingIntensity.entries.forEach { intensity ->
                val isSelected = intensity == selectedIntensity
                Surface(
                    onClick = { onSelectIntensity(intensity) },
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
                            text = intensity.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = intensity.subtitle,
                            fontSize = 10.sp,
                            color = if (isSelected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 선택된 강도에 대한 세부 설명 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = ItvMint),
            border = BorderStroke(1.dp, ItvLine)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${selectedIntensity.title} 코칭 특징",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ItvGreen
                )
                Text(
                    text = selectedIntensity.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun CharacterIntensitySection(
    selectedIntensity: CharacterIntensity,
    isActuallyNotificationOnly: Boolean,
    onSelectIntensity: (CharacterIntensity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "캐릭터 개입 강도",
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
                text = "개입 시 강아지 캐릭터가 화면에 등장하는 크기(비율)와 모션 크기를 설정합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 3개 선택 세그먼트 (약하게 / 보통 / 강하게)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CharacterIntensity.entries.forEach { intensity ->
                val isSelected = intensity == selectedIntensity
                Surface(
                    onClick = { onSelectIntensity(intensity) },
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
                            text = intensity.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = intensity.subtitle,
                            fontSize = 9.sp,
                            color = if (isSelected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // 선택된 캐릭터 강도에 대한 세부 설명 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = ItvMint),
            border = BorderStroke(1.dp, ItvLine)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "🐾 ${selectedIntensity.title} 캐릭터 등장 특징",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ItvGreen
                    )
                }
                Text(
                    text = selectedIntensity.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
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
                                Text(
                                    text = mission.action,
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
