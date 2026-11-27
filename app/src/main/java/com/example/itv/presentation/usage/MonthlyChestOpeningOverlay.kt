package com.example.itv.presentation.usage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.itv.domain.reward.MonthlyChestTier
import com.example.itv.infrastructure.reward.MonthlyChestClaimResult
import com.example.itv.presentation.components.ItvCardSurface
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.ItvInkBorder
import com.example.itv.presentation.components.ItvInkShadow
import com.example.itv.presentation.components.ItvInsetSurface
import com.example.itv.presentation.components.ItvTactileButton
import com.example.itv.presentation.components.ItvTactileCard
import com.example.itv.presentation.components.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class ChestOpeningRequest(
    val tier: MonthlyChestTier,
    val isPreview: Boolean = false,
    val claimResult: MonthlyChestClaimResult? = null
)

private val BronzeBurstColors = arrayOf(
    Color(0xFFFFF1B8),
    Color(0xFFF4AC54),
    Color(0xFFFFDC85),
    Color(0xFFD98338)
)
private val SilverBurstColors = arrayOf(
    Color(0xFFFFFFFF),
    Color(0xFF9AF5FF),
    Color(0xFF42D6F7),
    Color(0xFFDDFCFF)
)
private val GoldBurstColors = arrayOf(
    Color(0xFFFFFFFF),
    Color(0xFFFFF58A),
    Color(0xFFFFD02A),
    Color(0xFFFFA81C),
    Color(0xFFFFFBE0)
)

/**
 * Reusable scratch paths for the puppy face & standing body so every animation frame stays
 * zero-allocation and 60fps smooth.
 */
private class PuppyStageScratch {
    val path1 = Path()
    val path2 = Path()
    val path3 = Path()
    val path4 = Path()
}

/**
 * 해맑게 도장을 찍어주는 강아지(`DailyStampRitualOverlay`)와 1:1 동일한 크기·얼굴로
 * 열쇠를 입에 물고 달려와 상자 앞에 놓아준 뒤, 열쇠가 뼈다귀 자물쇠 열쇠구멍에 정확히
 * 꽂혀 돌아가며 상자가 활짝 열리고 등급별(동→은→금) 신비한 검은 실루엣과 광채가 피어오르는
 * 월간 보물상자 오픈 연출 오버레이.
 */
@Composable
internal fun MonthlyChestOpeningOverlay(
    request: ChestOpeningRequest,
    character: StampCharacterSpec,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isDark = LocalDarkTheme.current
    val haptic = LocalHapticFeedback.current
    val palette = remember(request.tier) { paletteForChest(request.tier) }
    val lightSpec = remember(request.tier) { mysteryLightSpecFor(request.tier) }
    val chestScratch = remember { ChestDrawScratch() }
    val puppyScratch = remember { PuppyStageScratch() }

    val gallopFrames by produceState(
        initialValue = StampCharacterBitmapCache.getCachedFrontGallopFrames(character.id).orEmpty(),
        key1 = context,
        key2 = character.id
    ) {
        if (value.isEmpty()) {
            value = withContext(Dispatchers.Default) {
                StampCharacterBitmapCache.preload(context, character)
                StampCharacterBitmapCache.getFrontGallopFrames(context, character)
            }
        }
    }
    val simpleHeadBitmap by produceState(
        initialValue = StampCharacterBitmapCache.getCachedSimpleHead(character.id),
        key1 = context,
        key2 = character.id
    ) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                StampCharacterBitmapCache.preload(context, character)
                StampCharacterBitmapCache.getSimpleHead(context, character)
            }
        }
    }

    // Choreography animatables (all read strictly inside Canvas draw phase for zero recomposition jank)
    val puppyAlpha = remember(request) { Animatable(0f) }
    val runInProgress = remember(request) { Animatable(0f) }          // 0 -> 1: 뒤에서 상자 왼쪽을 돌아 정면 앞으로 달려옴
    val dropNodProgress = remember(request) { Animatable(0f) }        // 0 -> 1 -> 0: 고개를 숙여 열쇠 내려놓기
    val keyDropProgress = remember(request) { Animatable(0f) }        // 0 -> 1: 입에서 상자 앞 바닥으로 열쇠 톡!
    val stepBackProgress = remember(request) { Animatable(0f) }       // 0 -> 1: 상자 왼쪽 뒤로 살짝 물러나 구경
    val keyLiftProgress = remember(request) { Animatable(0f) }        // 0 -> 1: 바닥 열쇠가 떠올라 열쇠구멍 정조준
    val keyInsertProgress = remember(request) { Animatable(0f) }      // 0 -> 1: 열쇠 날이 열쇠구멍 안으로 쏙! 삽입
    val keyTurnProgress = remember(request) { Animatable(0f) }        // 0 -> 1: 열쇠 90도 회전(딸깍!)
    val shackleUnlockProgress = remember(request) { Animatable(0f) }  // 0 -> 1: 회전 완료 순간 자물쇠 걸쇠 팝!
    val lockShakePx = remember(request) { Animatable(0f) }            // 자물쇠 미세 진동
    val lockPopProgress = remember(request) { Animatable(0f) }        // 0 -> 1: 풀린 자물쇠 + 열쇠 해제 이탈
    val seamGlow = remember(request) { Animatable(0f) }               // 0 -> 1: 열리기 직전 상자 틈새 빛 새어나옴
    val chestSquash = remember(request) { Animatable(1f) }            // 열리기 직전 쫀득한 스쿼시
    val chestOpenProgress = remember(request) { Animatable(0f) }      // 0 -> 1: 단일 힌지 뚜껑 활짝 열림
    val silhouetteRiseProgress = remember(request) { Animatable(0f) } // 0 -> 1: 상자 속에서 검은 실루엣 + 빛 상승
    val burstProgress = remember(request) { Animatable(0f) }          // 0 -> 1: 열림 순간 충격파 + 별빛 폭발
    val puppyCheer = remember(request) { Animatable(0f) }             // 0 -> 1: 강아지 기쁨 점프

    var phaseCaption by remember(request) { mutableStateOf("강아지가 ${request.tier.keyLabel}를 물고 달려오고 있어요!") }
    var showRewardCard by remember(request) { mutableStateOf(false) }

    // Unclipped full-dialog canvas aligned 0.0px to the card's stage placeholder (just like DailyStampRitualOverlay!)
    var dialogRootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var stageCenterInDialog by remember { mutableStateOf<Offset?>(null) }
    var stageWidthPx by remember { mutableFloatStateOf(0f) }

    // Kept as State<Float> (NOT `by` delegated) so reading `.value` inside Canvas never triggers recomposition!
    val infinite = rememberInfiniteTransition(label = "chest_glow")
    val glowPhaseState = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_phase"
    )

    LaunchedEffect(request) {
        // Phase 1: 도장 강아지와 동일한 크기·해맑은 얼굴로 열쇠를 입에 물고 정면 원근감으로 달려옴!
        coroutineScope {
            launch { puppyAlpha.animateTo(1f, animationSpec = tween(durationMillis = 160)) }
            launch {
                runInProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 1050, easing = CubicBezierEasing(0.20f, 0.04f, 0.24f, 1f))
                )
            }
        }
        delay(90)

        // Phase 2: 상자 앞에 멈춰 고개를 숙이고 열쇠를 톡! 내려놓음 (해맑은 혀 내민 미소로 전환!)
        phaseCaption = "상자 앞에 ${request.tier.keyLabel}를 놓아주었어요!"
        dropNodProgress.animateTo(1f, animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing))
        coroutineScope {
            launch {
                keyDropProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 360, easing = CubicBezierEasing(0.24f, 0.0f, 0.34f, 1.0f))
                )
            }
            launch {
                delay(130)
                dropNodProgress.animateTo(0f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
            }
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        delay(90)

        // Phase 3: 강아지가 상자 왼쪽 옆으로 통통 물러나 구경하며 상자 전면 시야 확보
        stepBackProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 420, easing = CubicBezierEasing(0.28f, 0.0f, 0.24f, 1f))
        )
        delay(100)

        // Phase 4a: 바닥의 열쇠가 떠올라 뼈다귀 자물쇠 열쇠구멍 정중앙을 정확히 조준
        phaseCaption = "뼈다귀 자물쇠 구멍에 ${request.tier.keyLabel}를 꽂는 중..."
        keyLiftProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 420, easing = CubicBezierEasing(0.26f, 0.0f, 0.20f, 1.0f))
        )
        delay(70)

        // Phase 4b: 열쇠 날이 열쇠구멍 안으로 쏙! 삽입 + 자물쇠 미세 반동
        keyInsertProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 280, easing = CubicBezierEasing(0.20f, 0.0f, 0.16f, 1.0f))
        )
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        coroutineScope {
            launch {
                lockShakePx.animateTo(2.8f, tween(45))
                lockShakePx.animateTo(-2.0f, tween(45))
                lockShakePx.animateTo(0f, tween(45))
            }
        }
        delay(110)

        // Phase 4c: 꽂힌 열쇠를 90도 돌려 딸깍! 해제 + 자물쇠 걸쇠가 위로 튕겨 열림!
        phaseCaption = "열쇠를 돌려 자물쇠를 열고 있어요..."
        coroutineScope {
            launch {
                keyTurnProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 420, easing = CubicBezierEasing(0.30f, 0.0f, 0.20f, 1.0f))
                )
            }
            launch {
                delay(290)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                shackleUnlockProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessMedium)
                )
            }
        }
        delay(100)

        // Phase 4d: 풀린 자물쇠 팝! 이탈 + 상자 틈새 눈부신 빛 + 스쿼시 예비 동작
        phaseCaption = "딸깍! 상자 안에서 눈부신 빛이 새어 나와요!"
        coroutineScope {
            launch {
                lockPopProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
                )
            }
            launch {
                seamGlow.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                )
            }
            launch {
                lockShakePx.animateTo(-3.5f, tween(50))
                lockShakePx.animateTo(3.2f, tween(50))
                lockShakePx.animateTo(0f, tween(50))
            }
            launch {
                delay(70)
                chestSquash.animateTo(0.87f, animationSpec = tween(durationMillis = 130))
                chestSquash.animateTo(1.06f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                chestSquash.animateTo(1f, animationSpec = tween(durationMillis = 130))
            }
        }

        // Phase 5: 보물상자 활짝 열림 + 상자 속 검은 실루엣 & 등급별 광채 상승 + 강아지 기쁨 점프!
        phaseCaption = "${request.tier.title} 속 신비한 빛이 피어올랐어요!"
        coroutineScope {
            launch {
                chestOpenProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow)
                )
            }
            launch {
                delay(90)
                silhouetteRiseProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessLow)
                )
            }
            launch {
                burstProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 820, easing = FastOutSlowInEasing)
                )
            }
            launch {
                puppyCheer.animateTo(1f, animationSpec = tween(durationMillis = 220))
                puppyCheer.animateTo(0f, animationSpec = tween(durationMillis = 200))
                puppyCheer.animateTo(0.78f, animationSpec = tween(durationMillis = 190))
                puppyCheer.animateTo(0f, animationSpec = tween(durationMillis = 200))
            }
        }
        showRewardCard = true
    }

    Dialog(
        onDismissRequest = { if (showRewardCard) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = showRewardCard,
            dismissOnClickOutside = showRewardCard
        )
    ) {
        val textInk = if (isDark) Color(0xFFE2EBE4) else Color(0xFF241E19)
        val subInk = if (isDark) Color(0xFFA6B7A9) else Color(0xFF61564B)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords -> dialogRootCoords = coords }
                .background(Color.Black.copy(alpha = 0.64f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (showRewardCard) onDismiss()
                },
            contentAlignment = Alignment.Center
        ) {
            ItvTactileCard(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 396.dp)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {},
                shape = RoundedCornerShape(28.dp),
                containerColor = ItvCardSurface,
                contentColor = textInk,
                borderColor = ItvInkBorder,
                shadowColor = ItvInkShadow,
                borderWidth = 2.2.dp,
                shadowOffsetX = 2.dp,
                shadowOffsetY = 5.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Top Tier Pill
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = palette.badgeBg,
                        border = BorderStroke(1.2.dp, palette.badgeAccent.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = if (request.isPreview) {
                                "${request.tier.title} 미리보기 · ${request.tier.requiredHappyStamps}개 보상"
                            } else {
                                "월간 ${request.tier.requiredHappyStamps}개 달성 · ${request.tier.title}"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.badgeAccent,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }

                    // Reserved stage area measured in Dialog coordinates so the unclipped full-size
                    // puppy, chest, key, & rays align with 0.0px error even as the reward card expands!
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .onGloballyPositioned { stageCoords ->
                                val root = dialogRootCoords ?: return@onGloballyPositioned
                                val topLeft = root.localPositionOf(stageCoords, Offset.Zero)
                                stageCenterInDialog = Offset(
                                    x = topLeft.x + stageCoords.size.width * 0.5f,
                                    y = topLeft.y + stageCoords.size.height * 0.5f
                                )
                                stageWidthPx = stageCoords.size.width.toFloat()
                            }
                    )

                    // Dynamic Status Caption
                    Text(
                        text = phaseCaption,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = textInk,
                        textAlign = TextAlign.Center
                    )

                    // Reward Breakdown Card (reveals smoothly once chest opens)
                    AnimatedVisibility(
                        visible = showRewardCard,
                        enter = fadeIn(tween(260)) + scaleIn(initialScale = 0.92f, animationSpec = tween(260))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = ItvInsetSurface,
                                border = BorderStroke(1.dp, palette.badgeAccent.copy(alpha = 0.35f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val result = request.claimResult
                                    val expDisplay = result?.rewardResult?.expGained ?: request.tier.rewardExp
                                    val pointsDisplay = result?.rewardResult?.pointsGained ?: request.tier.rewardPoints
                                    val shieldsDisplay = result?.shieldsGained ?: request.tier.bonusShields
                                    val overflowPts = result?.overflowShieldBonusPoints ?: 0L

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RewardChip(
                                            label = "경험치",
                                            value = "+${expDisplay} EXP",
                                            accent = Color(0xFF2E8B57)
                                        )
                                        RewardChip(
                                            label = "포인트",
                                            value = "+${pointsDisplay} P",
                                            accent = Color(0xFFD9820B)
                                        )
                                        if (shieldsDisplay > 0) {
                                            RewardChip(
                                                label = "스트릭 보호권",
                                                value = "🛡️ +${shieldsDisplay}",
                                                accent = Color(0xFF25789A)
                                            )
                                        }
                                    }

                                    if (overflowPts > 0L) {
                                        Text(
                                            text = "보호권이 최대 보유량이라 +${overflowPts} P로 전환 지급되었어요!",
                                            fontSize = 11.sp,
                                            color = subInk,
                                            textAlign = TextAlign.Center
                                        )
                                    }

                                    if (result?.rewardResult?.didLevelUp == true) {
                                        Text(
                                            text = "🎉 축하해요! Lv.${result.rewardResult.oldLevel} → Lv.${result.rewardResult.newLevel} 레벨업!",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ItvGreen,
                                            textAlign = TextAlign.Center
                                        )
                                    } else if (request.isPreview) {
                                        Text(
                                            text = "미리보기 모드 · 실제 달성 시 위 보상이 지급됩니다",
                                            fontSize = 11.sp,
                                            color = subInk,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            ItvTactileButton(
                                onClick = onDismiss,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                containerColor = ItvGreen,
                                contentColor = Color.White,
                                borderColor = ItvInkBorder,
                                shadowColor = ItvInkShadow,
                                borderWidth = 1.8.dp,
                                shadowOffsetX = 1.5.dp,
                                shadowOffsetY = 3.5.dp
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 11.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (request.isPreview) "미리보기 닫기" else "보상 받기 완료!",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    if (!showRewardCard) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // =========================================================================
            // FULL-SCREEN UNCLIPPED ANIMATION STAGE (0.0px locked to the card stage!)
            // Allows the cheerful stamp puppy to appear at 100% IDENTICAL size & face to
            // DailyStampRitualOverlay without any shrinking or awkward box clipping!
            // =========================================================================
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stageCenter = stageCenterInDialog ?: Offset(size.width * 0.5f, size.height * 0.45f)
                val stageW = if (stageWidthPx > 0f) stageWidthPx else minOf(size.width - 76.dp.toPx(), 360.dp.toPx())
                val wFactor = (stageW / 320.dp.toPx()).coerceIn(0.84f, 1.08f)
                val glowPhase = glowPhaseState.value

                // 1. Exact shared ChestGeometry (0.0px alignment for lock, keyhole, lid, floor & silhouette!)
                val chestSize = minOf(stageW * 0.68f, 218.dp.toPx())
                val chestRect = Rect(
                    left = stageCenter.x - chestSize * 0.5f,
                    top = stageCenter.y - chestSize * 0.52f,
                    right = stageCenter.x + chestSize * 0.5f,
                    bottom = stageCenter.y + chestSize * 0.48f
                )
                val chestGeo = computeChestGeometry(chestRect)
                val openP = chestOpenProgress.value
                val riseP = silhouetteRiseProgress.value
                val silhouetteWorldCenter = Offset(
                    x = chestGeo.cx,
                    y = chestGeo.baseTop + chestGeo.u * 0.045f -
                        chestGeo.u * (0.055f + lightSpec.riseHeightFactor) * riseP
                )

                // Soft stage ambient radiance behind chest
                val stageGlowR = chestSize * (0.64f + 0.44f * riseP * (lightSpec.haloRadiusFactor / 0.28f).coerceAtMost(1.8f))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            lightSpec.midColor.copy(alpha = ((if (isDark) 0.12f else 0.22f) + 0.24f * riseP).coerceIn(0f, 0.52f)),
                            lightSpec.outerColor.copy(alpha = (0.14f * riseP).coerceIn(0f, 0.22f)),
                            Color.Transparent
                        ),
                        center = Offset(chestGeo.cx, chestGeo.baseTop - chestGeo.u * 0.04f),
                        radius = stageGlowR.coerceAtLeast(1f)
                    ),
                    radius = stageGlowR,
                    center = Offset(chestGeo.cx, chestGeo.baseTop - chestGeo.u * 0.04f)
                )

                // 2. Puppy 2.5D path & exact DailyStampRitualOverlay proportions!
                val rp = runInProgress.value
                val nodP = dropNodProgress.value
                val dropP = keyDropProgress.value
                val stepBackP = stepBackProgress.value
                val pAlpha = puppyAlpha.value
                val cheer = puppyCheer.value

                // Phase 1: Starts far behind chest -> curves cleanly around left flank -> front-left of chest at 0.86x (exact stamp size!)
                val startX = chestGeo.cx - 42.dp.toPx() * wFactor
                val startY = chestGeo.baseBottom - 196.dp.toPx()
                val arriveX = chestGeo.cx - 32.dp.toPx() * wFactor
                val arriveY = chestGeo.baseBottom + 28.dp.toPx()
                val flankCurveX = -sin(rp * PI.toFloat()) * 62.dp.toPx() * wFactor
                val runFootX = lerpChestFloat(startX, arriveX, rp) + flankCurveX
                val runFootY = lerpChestFloat(startY, arriveY, rp)
                val runScale = lerpChestFloat(0.44f, 0.86f, rp)

                // Phase 3: Hops left-first then slightly behind-left of chest so depth-swap has 0px visual pop!
                val watchX = chestGeo.cx - 102.dp.toPx() * wFactor
                val watchY = chestGeo.baseBottom - 10.dp.toPx()
                val stepEaseX = 1f - (1f - stepBackP) * (1f - stepBackP)
                val stepEaseY = stepBackP * stepBackP
                val stepDoubleHop = if (stepBackP in 0.01f..0.99f) {
                    abs(sin(stepBackP * 2f * PI.toFloat())) * 13.dp.toPx()
                } else {
                    0f
                }

                val puppyFootX = lerpChestFloat(runFootX, watchX, stepEaseX) + nodP * 6.dp.toPx()
                val puppyGroundY = lerpChestFloat(runFootY, watchY, stepEaseY)
                val pScale = lerpChestFloat(runScale, 0.54f, stepBackP)

                val stepPhase = rp * 7.5f * PI.toFloat()
                val isGalloping = rp < 0.985f
                val runStrideBounce = if (isGalloping && rp > 0.01f) {
                    abs(sin(stepPhase)) * 5.5.dp.toPx() * pScale
                } else {
                    0f
                }
                val cheerJump = cheer * 16.dp.toPx()
                val puppyFootY = puppyGroundY - runStrideBounce - stepDoubleHop - cheerJump

                // EXACT 1:1 DailyStampRitualOverlay head & body proportions!
                val spriteSizePx = 264.dp.toPx() * pScale
                val unit = 104.dp.toPx() * 0.86f * pScale
                val headW = unit * character.headScaleAtRest
                val headH = headW / character.aspectRatio

                val strideBobPx = if (isGalloping) -abs(sin(stepPhase)) * 4.5.dp.toPx() * pScale else 0f
                val neckY = if (isGalloping) {
                    puppyFootY - spriteSizePx * 0.395f
                } else {
                    // Grounded plush 4-paw body neck join (matching DailyStampRitualOverlay)
                    puppyFootY - unit * 0.94f
                }
                val headBottomY = neckY + headH * (if (isGalloping) 0.25f else 0.22f) + strideBobPx + nodP * 14.dp.toPx()
                val headTopY = headBottomY - headH
                val headLeft = puppyFootX - headW * 0.50f
                val headCenter = Offset(puppyFootX, headTopY + headH * 0.50f)

                val keyFullLength = chestGeo.u * 0.37f
                val keyInMouthLength = (headW * 0.42f).coerceIn(keyFullLength * 0.46f, keyFullLength * 1.04f)
                val keyStillInMouth = dropP < 0.02f

                val headTiltDeg = when {
                    isGalloping -> sin(stepPhase) * 2.2f
                    nodP > 0.01f -> nodP * 12f
                    stepBackP in 0.01f..0.99f -> sin(stepBackP * 2f * PI.toFloat()) * -5.5f
                    else -> 5.0f * cheer
                }

                // Exact mouth position inside head-local and head-rotated world space (0.0px release continuity!)
                val mouthLocalPos = character.landmarks.toCanvasOffset(
                    nx = 0.515f,
                    ny = 0.722f,
                    imgLeft = headLeft,
                    imgTop = headTopY,
                    imgW = headW,
                    imgH = headH
                )
                val keyMouthLocalCenter = Offset(mouthLocalPos.x - headW * 0.015f, mouthLocalPos.y + headH * 0.015f)
                val tiltRad = headTiltDeg * (PI / 180.0)
                val relMx = keyMouthLocalCenter.x - headCenter.x
                val relMy = keyMouthLocalCenter.y - headCenter.y
                val mouthReleaseWorldPos = Offset(
                    x = headCenter.x + (relMx * cos(tiltRad) - relMy * sin(tiltRad)).toFloat(),
                    y = headCenter.y + (relMx * sin(tiltRad) + relMy * cos(tiltRad)).toFloat()
                )

                val drawPuppyAction: DrawScope.() -> Unit = {
                    if (pAlpha > 0.01f) {
                        // Subtle dust puffs trailing behind paws while galloping in
                        if (isGalloping && rp in 0.06f..0.94f) {
                            drawRunDustPuffs(
                                footX = puppyFootX,
                                groundY = puppyGroundY,
                                scale = pScale,
                                phase = rp * 7.5f,
                                isDark = isDark
                            )
                        }

                        // Grounded soft contact shadow directly under puppy's floor plane
                        val liftRatio = ((runStrideBounce + stepDoubleHop + cheerJump) / 85.dp.toPx()).coerceIn(0f, 0.38f)
                        val shadowRx = 72.dp.toPx() * pScale * (1f - liftRatio * 0.55f)
                        val shadowRy = shadowRx * 0.34f
                        drawOval(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF2B231E).copy(alpha = 0.22f * pAlpha),
                                    Color(0xFF2B231E).copy(alpha = 0.09f * pAlpha),
                                    Color.Transparent
                                ),
                                center = Offset(puppyFootX, puppyGroundY + 3.dp.toPx() * pScale),
                                radius = (shadowRx * 1.08f).coerceAtLeast(1f)
                            ),
                            topLeft = Offset(puppyFootX - shadowRx, puppyGroundY + 3.dp.toPx() * pScale - shadowRy),
                            size = Size(shadowRx * 2f, shadowRy * 2f)
                        )

                        // Puppy body: exact 6-frame gallop while running, seamless plush 4-paw standing body once stopped!
                        if (isGalloping && gallopFrames.isNotEmpty()) {
                            val frameIdx = ((rp * 15f).toInt()).mod(gallopFrames.size)
                            val bodyBmp: ImageBitmap = gallopFrames[frameIdx]
                            val bodyLeft = (puppyFootX - spriteSizePx * 0.50f).roundToInt()
                            val bodyTop = (puppyFootY - spriteSizePx * 0.90f).roundToInt()
                            val dim = spriteSizePx.roundToInt().coerceAtLeast(1)
                            drawImage(
                                image = bodyBmp,
                                dstOffset = IntOffset(bodyLeft, bodyTop),
                                dstSize = IntSize(dim, dim),
                                alpha = pAlpha
                            )
                        } else {
                            val tailWagPhase = glowPhase * 4f * PI.toFloat() + cheer * 4.5f
                            drawPlushStandingPuppyBody(
                                anchorX = puppyFootX,
                                anchorY = puppyFootY,
                                unit = unit,
                                headW = headW,
                                headBottomY = headBottomY,
                                headH = headH,
                                character = character,
                                tailWagPhase = tailWagPhase,
                                cheerAmount = cheer,
                                alpha = pAlpha,
                                scratch = puppyScratch
                            )
                        }

                        // Exact stamp-puppy chibi head + exact cheerful GOLD face + key clenched naturally in mouth!
                        withTransform({
                            rotate(degrees = headTiltDeg, pivot = headCenter)
                        }) {
                            simpleHeadBitmap?.let { bmp ->
                                drawImage(
                                    image = bmp,
                                    dstOffset = IntOffset(headLeft.roundToInt(), headTopY.roundToInt()),
                                    dstSize = IntSize(
                                        headW.roundToInt().coerceAtLeast(1),
                                        headH.roundToInt().coerceAtLeast(1)
                                    ),
                                    alpha = pAlpha
                                )
                            }

                            // 1) Behind-key face parts: blush cheeks, cheerful ^ ^ eyes, and open mouth cavity when biting
                            drawStampGoldFaceUpper(
                                character = character,
                                imgLeft = headLeft,
                                imgTop = headTopY,
                                imgW = headW,
                                imgH = headH,
                                isBitingKey = keyStillInMouth,
                                alpha = pAlpha,
                                scratch = puppyScratch
                            )

                            // 2) Key held horizontally inside mouth (bow on right, blade tip on left -> zero flip later!)
                            if (keyStillInMouth) {
                                val keyBobRot = -5f + if (isGalloping) sin(stepPhase) * 3.0f else nodP * 4f
                                withTransform({
                                    scale(scaleX = -1f, scaleY = 1f, pivot = keyMouthLocalCenter)
                                }) {
                                    drawRewardKey(
                                        tier = request.tier,
                                        center = keyMouthLocalCenter,
                                        length = keyInMouthLength,
                                        rotationDeg = -keyBobRot,
                                        alpha = pAlpha
                                    )
                                }
                            }

                            // 3) Front-of-key face parts: upper curved smile lip + glossy nose (or joyful pink tongue smile!)
                            drawStampGoldFaceLowerAndNose(
                                character = character,
                                imgLeft = headLeft,
                                imgTop = headTopY,
                                imgW = headW,
                                imgH = headH,
                                isBitingKey = keyStillInMouth,
                                alpha = pAlpha,
                                scratch = puppyScratch
                            )
                        }
                    }
                }

                // =========================================================================
                // TRUE 2.5D DEPTH ORDER (PAINTER'S ALGORITHM)
                //   1. Puppy if physically BEHIND the chest ground plane (`puppyGroundY < chestGeo.baseBottom`)
                //   2. Monthly Treasure Chest (with its own internal back-to-front layers!)
                //   3. Puppy if physically IN FRONT OF the chest (`puppyGroundY >= chestGeo.baseBottom`)
                //   4. Dropped Key -> Floating Key -> Inserting & Turning inside Exact Keyhole!
                //   5. Opening Light Shockwave & Tier Sparkle Burst
                // =========================================================================
                val puppyBehindChest = puppyGroundY < chestGeo.baseBottom
                if (puppyBehindChest) {
                    drawPuppyAction()
                }

                val squashY = chestSquash.value
                val squashX = 2f - squashY
                val chestPivot = Offset(chestGeo.cx, chestGeo.baseBottom)
                withTransform({
                    if (abs(squashY - 1f) > 0.001f) {
                        scale(scaleX = squashX, scaleY = squashY, pivot = chestPivot)
                    }
                }) {
                    drawMonthlyRewardChest(
                        tier = request.tier,
                        openProgress = openP,
                        lockPopProgress = lockPopProgress.value,
                        glowPhase = glowPhase,
                        seamGlowAlpha = seamGlow.value,
                        lockShakeOffsetPx = lockShakePx.value,
                        silhouetteRiseProgress = riseP,
                        scratch = chestScratch,
                        bounds = chestRect,
                        shackleUnlockProgress = shackleUnlockProgress.value
                    )
                }

                if (!puppyBehindChest) {
                    drawPuppyAction()
                }

                // 3. Dropped Key -> Floor Rest (right-front) -> Lift on open RIGHT side -> Slide LEFT INTO Keyhole -> 3D 90° Turn!
                val lp = lockPopProgress.value
                if (!keyStillInMouth && lp < 0.94f) {
                    val liftP = keyLiftProgress.value
                    val insertP = keyInsertProgress.value
                    val turnP = keyTurnProgress.value
                    val shakeX = lockShakePx.value
                    val popArcX = lp * chestGeo.u * 0.14f
                    val popArcY = -sin(lp * PI.toFloat()) * chestGeo.u * 0.16f + lp * lp * chestGeo.u * 0.18f
                    val liveKeyhole = Offset(
                        x = chestGeo.keyholeCenter.x + shakeX + popArcX,
                        y = chestGeo.keyholeCenter.y + popArcY
                    )
                    // Lands cleanly in front-right of chest, 100% clear of the puppy watching on the left!
                    val keyGroundPos = Offset(chestGeo.cx + 26.dp.toPx() * wFactor, chestGeo.baseBottom + 24.dp.toPx())
                    // Approaches from the completely unobstructed right side pointing slightly UP-LEFT into liveKeyhole!
                    val approachAngleDeg = 18f
                    val approachRad = approachAngleDeg * (PI / 180.0)
                    // Direction FROM keyhole OUT toward the key bow (right-downward along +18°!)
                    val outDirX = cos(approachRad).toFloat()
                    val outDirY = sin(approachRad).toFloat()
                    val preInsertGapPx = 15.dp.toPx()

                    // Exact world center of key when its tip (left side after scaleX=-1) hovers preInsertGapPx right of keyhole
                    val approachCenter = Offset(
                        x = liveKeyhole.x + outDirX * (preInsertGapPx + keyFullLength * 0.46f),
                        y = liveKeyhole.y + outDirY * (preInsertGapPx + keyFullLength * 0.46f)
                    )

                    if (liftP < 0.005f) {
                        // Phase 2: Key arcs smoothly from exact rotated mouth position onto the ground in front of chest!
                        val arcY = sin(dropP * PI.toFloat()) * 20.dp.toPx()
                        val bounceY = if (dropP > 0.72f) {
                            sin(((dropP - 0.72f) / 0.28f) * PI.toFloat()) * 5.5.dp.toPx()
                        } else {
                            0f
                        }
                        val kx = lerpChestFloat(mouthReleaseWorldPos.x, keyGroundPos.x, dropP)
                        val ky = lerpChestFloat(mouthReleaseWorldPos.y, keyGroundPos.y, dropP) - arcY - bounceY
                        val kRot = lerpChestFloat(headTiltDeg - 5f, 6f, dropP)
                        val kLen = lerpChestFloat(keyInMouthLength, keyFullLength * 0.90f, dropP)

                        // Ground contact shadow beneath the resting key
                        val shadowAlpha = (0.06f + 0.18f * dropP).coerceIn(0f, 0.24f)
                        drawOval(
                            color = Color(0xFF2B231E).copy(alpha = shadowAlpha),
                            topLeft = Offset(kx - kLen * 0.38f, keyGroundPos.y + 5.dp.toPx()),
                            size = Size(kLen * 0.76f, 7.dp.toPx())
                        )
                        val kCenter = Offset(kx, ky)
                        withTransform({
                            rotate(degrees = kRot, pivot = kCenter)
                            scale(scaleX = -1f, scaleY = 1f, pivot = kCenter)
                        }) {
                            drawRewardKey(
                                tier = request.tier,
                                center = kCenter,
                                length = kLen,
                                rotationDeg = 0f
                            )
                        }
                        if (dropP > 0.74f) {
                            val glintA = sin(((dropP - 0.74f) / 0.26f) * PI.toFloat()).coerceIn(0f, 1f)
                            drawFourPointSparkle(
                                center = Offset(keyGroundPos.x + kLen * 0.20f, keyGroundPos.y - 8.dp.toPx()),
                                radius = 7.dp.toPx(),
                                color = lightSpec.coreColor,
                                alpha = glintA
                            )
                        }
                    } else if (insertP < 0.005f) {
                        // Phase 4a: Key levitates gracefully on the clear right side and aims its tip dead-center at keyhole!
                        val liftArcX = sin(liftP * PI.toFloat()) * 16.dp.toPx()
                        val liftArcY = -sin(liftP * PI.toFloat()) * 8.dp.toPx()
                        val kx = lerpChestFloat(keyGroundPos.x, approachCenter.x, liftP) + liftArcX
                        val ky = lerpChestFloat(keyGroundPos.y, approachCenter.y, liftP) + liftArcY
                        val kRot = lerpChestFloat(6f, approachAngleDeg, liftP)
                        val kLen = lerpChestFloat(keyFullLength * 0.90f, keyFullLength, liftP)

                        // Fading floor shadow as key lifts into the air
                        val floorShadowA = (0.20f * (1f - liftP)).coerceIn(0f, 0.20f)
                        if (floorShadowA > 0.01f) {
                            drawOval(
                                color = Color(0xFF2B231E).copy(alpha = floorShadowA),
                                topLeft = Offset(keyGroundPos.x - kLen * 0.36f, keyGroundPos.y + 5.dp.toPx()),
                                size = Size(kLen * 0.72f, 6.5.dp.toPx())
                            )
                        }

                        // Soft magical aura around floating key
                        val auraA = (0.36f * sin(liftP * PI.toFloat())).coerceIn(0f, 0.40f)
                        if (auraA > 0.01f) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(lightSpec.midColor.copy(alpha = auraA), Color.Transparent),
                                    center = Offset(kx, ky),
                                    radius = kLen * 0.56f
                                ),
                                radius = kLen * 0.56f,
                                center = Offset(kx, ky)
                            )
                        }

                        val kCenter = Offset(kx, ky)
                        withTransform({
                            rotate(degrees = kRot, pivot = kCenter)
                            scale(scaleX = -1f, scaleY = 1f, pivot = kCenter)
                        }) {
                            drawRewardKey(
                                tier = request.tier,
                                center = kCenter,
                                length = kLen,
                                rotationDeg = 0f
                            )
                        }
                    } else {
                        // Phase 4b -> 4c -> 4d: Key blade slides STRAIGHT INTO liveKeyhole from the clear right
                        // side (clipped at exact keyhole mouth!) and performs a 3D 90° axial turn to unlock!
                        val keyAlpha = (1f - lp * 1.08f).coerceIn(0f, 1f)
                        val slideDist = preInsertGapPx + keyFullLength * 0.31f
                        // In flipped local space (scaleX = -1 around keyhole), local -X extends OUTSIDE to the right,
                        // and local +X enters INSIDE the keyhole (all teeth slide completely inside while collar & bow stay visible)!
                        val localCenterX = -(preInsertGapPx + keyFullLength * 0.46f) + slideDist * insertP

                        // 3D axial twist of the protruding key handle as it turns 90° clockwise around the keyhole axis!
                        val turnRad = turnP * (PI.toFloat() * 0.5f)
                        val bowScaleY = (0.28f + 0.72f * abs(cos(turnRad))).coerceIn(0.28f, 1f)
                        val torqueSweepDeg = -24f * sin(turnP * PI.toFloat() * 0.5f) + lp * 32f

                        // Glowing 90° turning motion arc right around the keyhole!
                        if (turnP > 0.04f && keyAlpha > 0.02f) {
                            val arcR = chestGeo.lockHeight * 0.96f
                            val arcAlpha = (sin(turnP * PI.toFloat()) * 0.90f * keyAlpha).coerceIn(0f, 1f)
                            drawArc(
                                color = lightSpec.coreColor.copy(alpha = arcAlpha),
                                startAngle = -50f,
                                sweepAngle = 115f * turnP,
                                useCenter = false,
                                topLeft = Offset(liveKeyhole.x - arcR, liveKeyhole.y - arcR),
                                size = Size(arcR * 2f, arcR * 2f),
                                style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        // Keyhole-aligned local space: origin = liveKeyhole, rotate(approachAngleDeg + torque),
                        // then flip X so drawRewardKey's bow sits outside to the right and its tip slides into x = 0!
                        withTransform({
                            translate(left = liveKeyhole.x, top = liveKeyhole.y)
                            rotate(degrees = approachAngleDeg + torqueSweepDeg, pivot = Offset.Zero)
                            scale(scaleX = -1f, scaleY = 1f, pivot = Offset.Zero)
                        }) {
                            clipRect(
                                left = -keyFullLength * 1.6f,
                                top = -keyFullLength * 0.9f,
                                right = 1.6.dp.toPx(),
                                bottom = keyFullLength * 0.9f
                            ) {
                                withTransform({
                                    if (turnP > 0.005f) {
                                        scale(scaleX = 1f, scaleY = bowScaleY, pivot = Offset.Zero)
                                    }
                                }) {
                                    drawRewardKey(
                                        tier = request.tier,
                                        center = Offset(localCenterX, 0f),
                                        length = keyFullLength,
                                        rotationDeg = 0f,
                                        alpha = keyAlpha
                                    )
                                }
                                // Subtle 3D metallic edge highlight on key bow while turning edge-on
                                val edgeFactor = sin(turnRad).coerceIn(0f, 1f)
                                if (edgeFactor > 0.15f) {
                                    val edgeHalfH = keyFullLength * 0.055f
                                    drawRoundRect(
                                        color = lightSpec.coreColor.copy(alpha = (0.72f * edgeFactor * keyAlpha).coerceIn(0f, 1f)),
                                        topLeft = Offset(localCenterX - keyFullLength * 0.42f, -edgeHalfH),
                                        size = Size(keyFullLength * 0.40f, edgeHalfH * 2f),
                                        cornerRadius = CornerRadius(edgeHalfH, edgeHalfH)
                                    )
                                }
                            }

                            // Crisp dark collar contact shadow right where the shaft enters the keyhole slot
                            if (insertP > 0.25f) {
                                val collarA = ((insertP - 0.25f) / 0.75f * 0.55f * keyAlpha).coerceIn(0f, 0.55f)
                                drawCircle(
                                    color = Color(0xFF1A120E).copy(alpha = collarA),
                                    radius = chestGeo.keyholeRadius * 1.05f,
                                    center = Offset.Zero
                                )
                            }
                        }

                        // Crisp seating click glint when key slides home
                        if (insertP > 0.72f && turnP < 0.20f) {
                            val seatGlint = sin(((insertP - 0.72f) / 0.28f) * PI.toFloat()).coerceIn(0f, 1f)
                            drawFourPointSparkle(
                                center = liveKeyhole,
                                radius = 10.dp.toPx(),
                                color = lightSpec.coreColor,
                                alpha = seatGlint
                            )
                        }

                        // Bright unlock sparkles right on the keyhole & popping shackle as key clicks!
                        if (turnP > 0.55f && keyAlpha > 0.05f) {
                            val clickSpark = sin(((turnP - 0.55f) / 0.45f) * PI.toFloat()).coerceIn(0f, 1f)
                            drawFourPointSparkle(
                                center = Offset(liveKeyhole.x + 11.dp.toPx(), liveKeyhole.y - 16.dp.toPx()),
                                radius = 12.dp.toPx(),
                                color = lightSpec.coreColor,
                                alpha = clickSpark
                            )
                            drawFourPointSparkle(
                                center = Offset(liveKeyhole.x - 12.dp.toPx(), liveKeyhole.y - 10.dp.toPx()),
                                radius = 7.5.dp.toPx(),
                                color = lightSpec.midColor,
                                alpha = clickSpark * 0.92f
                            )
                        }
                    }
                }

                // 4. Tier-Scaled Opening Light Shockwave & Sparkle Burst radiating from the Mystery Silhouette!
                val bp = burstProgress.value
                if (bp > 0.01f) {
                    drawTierOpeningBurst(
                        origin = silhouetteWorldCenter,
                        progress = bp,
                        tier = request.tier,
                        spec = lightSpec,
                        chestSize = chestSize
                    )
                }
            }
        }
    }
}

private fun lerpChestFloat(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

@Composable
private fun RewardChip(
    label: String,
    value: String,
    accent: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            color = accent
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (LocalDarkTheme.current) Color(0xFFA6B7A9) else Color(0xFF61564B)
        )
    }
}

private fun DrawScope.drawRunDustPuffs(
    footX: Float,
    groundY: Float,
    scale: Float,
    phase: Float,
    isDark: Boolean
) {
    val puffColor = if (isDark) Color(0xFFD5E0D8) else Color(0xFFB5A798)
    for (i in 0 until 3) {
        val p = (phase + i * 0.33f) % 1f
        val side = if (i % 2 == 0) -1f else 1f
        val px = footX + side * (16f + p * 22f).dp.toPx() * scale
        val py = groundY - p * 15.dp.toPx() * scale
        val r = (4.0f + p * 6.0f).dp.toPx() * scale
        val a = ((1f - p) * 0.28f).coerceIn(0f, 0.30f)
        drawCircle(
            color = puffColor.copy(alpha = a),
            radius = r,
            center = Offset(px, py)
        )
    }
}

/**
 * Draws the standing/cheering plush 4-paw puppy body matching `DailyStampRitualOverlay`'s
 * exact fur, shading, outline stroke, and seamless neck-into-cheek silhouette:
 *   1) Fluffy wagging tail peeking behind the rump
 *   2) Grounded chubby hind paws
 *   3) Continuous plush white torso flowing cleanly under the cheeks
 *   4) Cute stubby front paws (lifting joyously on cheer hops!)
 */
private fun DrawScope.drawPlushStandingPuppyBody(
    anchorX: Float,
    anchorY: Float,
    unit: Float,
    headW: Float,
    headBottomY: Float,
    headH: Float,
    character: StampCharacterSpec,
    tailWagPhase: Float,
    cheerAmount: Float,
    alpha: Float,
    scratch: PuppyStageScratch
) {
    val furWhite = character.furColor.copy(alpha = alpha)
    val furShade = character.furShadeColor.copy(alpha = alpha)
    val outline = character.outlineColor.copy(alpha = alpha)
    val strokeW = (unit * 0.035f).coerceIn(1.7f, 3.6.dp.toPx())
    val stroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)

    val bellyY = anchorY - unit * 0.26f
    val neckJoinY = headBottomY - headH * 0.22f
    val chestHalfW = unit * 0.56f
    val neckHalfW = headW * 0.21f

    // 1. Fluffy wagging tail peeking gently behind the left rump
    val tailBase = Offset(anchorX - chestHalfW * 0.78f, bellyY - unit * 0.20f)
    val wagDeg = -28f + sin(tailWagPhase) * (16f + cheerAmount * 14f)
    withTransform({
        rotate(degrees = wagDeg, pivot = tailBase)
    }) {
        val tailPath = scratch.path1.apply {
            rewind()
            moveTo(tailBase.x, tailBase.y + unit * 0.06f)
            cubicTo(
                tailBase.x - unit * 0.34f, tailBase.y - unit * 0.06f,
                tailBase.x - unit * 0.44f, tailBase.y - unit * 0.42f,
                tailBase.x - unit * 0.22f, tailBase.y - unit * 0.48f
            )
            cubicTo(
                tailBase.x - unit * 0.04f, tailBase.y - unit * 0.52f,
                tailBase.x - unit * 0.02f, tailBase.y - unit * 0.20f,
                tailBase.x + unit * 0.06f, tailBase.y - unit * 0.12f
            )
            close()
        }
        drawPath(
            path = tailPath,
            brush = Brush.verticalGradient(
                colors = listOf(furWhite, furShade),
                startY = tailBase.y - unit * 0.50f,
                endY = tailBase.y + unit * 0.08f
            )
        )
        drawPath(path = tailPath, color = outline, style = stroke)
    }

    // 2. Cute chubby braced hind paws peeking slightly wider behind the torso
    for (side in floatArrayOf(-1f, 1f)) {
        val footCx = anchorX + side * unit * 0.44f
        val footBottomY = anchorY - unit * 0.01f
        val pawHalfW = unit * 0.175f
        val pawH = unit * 0.22f
        val hindPath = scratch.path2.apply {
            rewind()
            moveTo(anchorX + side * unit * 0.30f, bellyY - unit * 0.20f)
            cubicTo(
                anchorX + side * unit * 0.62f, bellyY - unit * 0.12f,
                footCx + side * pawHalfW * 1.15f, footBottomY - pawH * 0.65f,
                footCx + side * pawHalfW, footBottomY - pawH * 0.28f
            )
            cubicTo(
                footCx + side * pawHalfW, footBottomY + pawH * 0.26f,
                footCx - side * pawHalfW, footBottomY + pawH * 0.26f,
                footCx - side * pawHalfW, footBottomY - pawH * 0.28f
            )
            lineTo(anchorX + side * unit * 0.24f, bellyY)
            close()
        }
        drawPath(
            path = hindPath,
            brush = Brush.verticalGradient(
                colors = listOf(furShade, furWhite, furShade),
                startY = bellyY - unit * 0.22f,
                endY = footBottomY + pawH * 0.25f
            )
        )
        drawPath(path = hindPath, color = outline, style = stroke)
    }

    // 3. Seamless Plush White Puppy Torso & Chest (open top fuses cleanly behind cheeks!)
    val torsoOutline = scratch.path3.apply {
        rewind()
        moveTo(anchorX - neckHalfW, neckJoinY)
        cubicTo(
            anchorX - chestHalfW * 1.06f, lerpChestFloat(neckJoinY, bellyY, 0.36f),
            anchorX - chestHalfW * 1.04f, lerpChestFloat(neckJoinY, bellyY, 0.84f),
            anchorX - chestHalfW * 0.60f, bellyY
        )
        quadraticTo(anchorX, bellyY + unit * 0.14f, anchorX + chestHalfW * 0.60f, bellyY)
        cubicTo(
            anchorX + chestHalfW * 1.04f, lerpChestFloat(neckJoinY, bellyY, 0.84f),
            anchorX + chestHalfW * 1.06f, lerpChestFloat(neckJoinY, bellyY, 0.36f),
            anchorX + neckHalfW, neckJoinY
        )
    }
    val torsoFill = scratch.path4.apply {
        rewind()
        addPath(torsoOutline)
        close()
    }
    drawPath(
        path = torsoFill,
        brush = Brush.verticalGradient(
            colors = listOf(furWhite, furWhite, furShade.copy(alpha = 0.85f * alpha)),
            startY = neckJoinY,
            endY = bellyY + unit * 0.16f
        )
    )
    drawPath(path = torsoOutline, color = outline, style = stroke)

    // 4. Cute stubby front 솜방망이 paws planted neatly in front (lifting happily when cheering!)
    val pawLift = cheerAmount * unit * 0.22f
    val toeStrokeW = (strokeW * 0.66f).coerceAtLeast(1.3f)
    for (side in floatArrayOf(-1f, 1f)) {
        val shoulderX = anchorX + side * unit * 0.31f
        val shoulderY = lerpChestFloat(neckJoinY, bellyY, 0.44f)
        val pawCx = anchorX + side * (unit * 0.25f + cheerAmount * unit * 0.09f)
        val pawBottomY = anchorY + unit * 0.02f - pawLift
        val pawHalfW = unit * 0.155f
        val pawRound = unit * 0.085f

        val frontLegOutline = scratch.path2.apply {
            rewind()
            moveTo(shoulderX + side * unit * 0.10f, shoulderY)
            cubicTo(
                pawCx + side * pawHalfW * 1.08f, lerpChestFloat(shoulderY, pawBottomY, 0.55f),
                pawCx + side * pawHalfW * 1.05f, pawBottomY - pawRound,
                pawCx + side * pawHalfW, pawBottomY - pawRound * 0.55f
            )
            cubicTo(
                pawCx + side * pawHalfW, pawBottomY + pawRound,
                pawCx - side * pawHalfW, pawBottomY + pawRound,
                pawCx - side * pawHalfW, pawBottomY - pawRound * 0.55f
            )
            cubicTo(
                pawCx - side * pawHalfW * 0.96f, lerpChestFloat(pawBottomY, shoulderY, 0.45f),
                shoulderX - side * unit * 0.08f, shoulderY + unit * 0.08f,
                shoulderX - side * unit * 0.08f, shoulderY + unit * 0.02f
            )
        }
        val frontLegFill = scratch.path4.apply {
            rewind()
            addPath(frontLegOutline)
            close()
        }
        drawPath(
            path = frontLegFill,
            brush = Brush.verticalGradient(
                colors = listOf(furWhite, furWhite, furShade),
                startY = shoulderY,
                endY = pawBottomY + pawRound
            )
        )
        drawPath(path = frontLegOutline, color = outline, style = stroke)

        // Two tiny cute toe lines on each front paw
        val toeX1 = pawCx - pawHalfW * 0.30f
        val toeX2 = pawCx + pawHalfW * 0.30f
        drawLine(outline, Offset(toeX1, pawBottomY - pawRound * 0.18f), Offset(toeX1, pawBottomY + pawRound * 0.52f), toeStrokeW, StrokeCap.Round)
        drawLine(outline, Offset(toeX2, pawBottomY - pawRound * 0.18f), Offset(toeX2, pawBottomY + pawRound * 0.52f), toeStrokeW, StrokeCap.Round)
    }
}

/**
 * Exact 1:1 cheerful GOLD face from `DailyStampRitualOverlay.drawPuppyLiveExpression` (upper layer
 * drawn BEHIND the key shaft): rosy cheeks, joyful arched eyes (^ ^), and subtle mouth cavity when biting.
 */
private fun DrawScope.drawStampGoldFaceUpper(
    character: StampCharacterSpec,
    imgLeft: Float,
    imgTop: Float,
    imgW: Float,
    imgH: Float,
    isBitingKey: Boolean,
    alpha: Float,
    scratch: PuppyStageScratch
) {
    fun pt(nx: Float, ny: Float): Offset =
        character.landmarks.toCanvasOffset(nx, ny, imgLeft, imgTop, imgW, imgH)

    val outline = character.outlineColor.copy(alpha = alpha)
    val lineW = (imgW * 0.028f).coerceAtLeast(2.4f)
    val stroke = Stroke(width = lineW, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // Rosy cheerful cheeks (exact GOLD stamp coordinates & color!)
    val blushColor = Color(0xFFFF9DB2).copy(alpha = 0.42f * alpha)
    val lBlush = pt(0.325f, 0.595f)
    val rBlush = pt(0.705f, 0.595f)
    drawOval(blushColor, topLeft = Offset(lBlush.x - imgW * 0.045f, lBlush.y - imgH * 0.024f), size = Size(imgW * 0.09f, imgH * 0.048f))
    drawOval(blushColor, topLeft = Offset(rBlush.x - imgW * 0.045f, rBlush.y - imgH * 0.024f), size = Size(imgW * 0.09f, imgH * 0.048f))

    // Exact cheerful arched smiling eyes (^ ^)
    val l0 = pt(0.355f, 0.535f)
    val lc = pt(0.405f, 0.445f)
    val l1 = pt(0.455f, 0.535f)
    scratch.path1.apply {
        rewind()
        moveTo(l0.x, l0.y)
        quadraticTo(lc.x, lc.y, l1.x, l1.y)
    }
    drawPath(scratch.path1, color = outline, style = stroke)

    val r0 = pt(0.575f, 0.535f)
    val rc = pt(0.625f, 0.445f)
    val r1 = pt(0.675f, 0.535f)
    scratch.path2.apply {
        rewind()
        moveTo(r0.x, r0.y)
        quadraticTo(rc.x, rc.y, r1.x, r1.y)
    }
    drawPath(scratch.path2, color = outline, style = stroke)

    // When holding the key in its mouth, draw the soft dark-pink lower mouth cavity BEHIND the key shaft
    if (isBitingKey) {
        val mc = pt(0.515f, 0.735f)
        val mw = imgW * 0.145f
        val mh = imgH * 0.078f
        drawOval(
            color = Color(0xFF8C3B4E).copy(alpha = 0.78f * alpha),
            topLeft = Offset(mc.x - mw * 0.5f, mc.y - mh * 0.5f),
            size = Size(mw, mh)
        )
        drawOval(
            color = outline,
            topLeft = Offset(mc.x - mw * 0.5f, mc.y - mh * 0.5f),
            size = Size(mw, mh),
            style = Stroke(width = (imgW * 0.020f).coerceAtLeast(1.6f))
        )
    }
}

/**
 * Exact 1:1 cheerful GOLD face from `DailyStampRitualOverlay.drawPuppyLiveExpression` (lower/front
 * layer drawn IN FRONT OF the key shaft):
 * - Glossy dark puppy nose + white specular highlight at exact (0.515, 0.612)
 * - When biting key: crisp white upper-muzzle bridge + curved smiling upper lip overlapping the key shaft!
 * - When key dropped: exact playful pink tongue & happy GOLD smile!
 */
private fun DrawScope.drawStampGoldFaceLowerAndNose(
    character: StampCharacterSpec,
    imgLeft: Float,
    imgTop: Float,
    imgW: Float,
    imgH: Float,
    isBitingKey: Boolean,
    alpha: Float,
    scratch: PuppyStageScratch
) {
    fun pt(nx: Float, ny: Float): Offset =
        character.landmarks.toCanvasOffset(nx, ny, imgLeft, imgTop, imgW, imgH)

    val outline = character.outlineColor.copy(alpha = alpha)
    val fur = character.furColor.copy(alpha = alpha)
    val thinW = (imgW * 0.022f).coerceAtLeast(1.9f)
    val thinStroke = Stroke(width = thinW, cap = StrokeCap.Round, join = StrokeJoin.Round)

    if (isBitingKey) {
        // Subtle white upper-muzzle fur directly above the upper lip so the key visibly sits INSIDE the mouth
        val mL = pt(0.415f, 0.675f)
        val mC = pt(0.515f, 0.720f)
        val mR = pt(0.615f, 0.675f)
        val upperCover = scratch.path1.apply {
            rewind()
            moveTo(mL.x - imgW * 0.015f, mL.y - imgH * 0.055f)
            lineTo(mR.x + imgW * 0.015f, mR.y - imgH * 0.055f)
            lineTo(mR.x, mR.y)
            quadraticTo(mC.x, mC.y, mL.x, mL.y)
            close()
        }
        drawPath(upperCover, color = fur)

        // Crisp curved happy upper lip hugging the top edge of the gripped key shaft
        val lipCurve = scratch.path2.apply {
            rewind()
            moveTo(mL.x, mL.y)
            quadraticTo(mC.x, mC.y, mR.x, mR.y)
        }
        drawPath(lipCurve, color = outline, style = thinStroke)
    } else {
        // Exact playful pink tongue & big happy GOLD stamp smile!
        val tL = pt(0.465f, 0.700f)
        val tLB = pt(0.465f, 0.785f)
        val cL = pt(0.465f, 0.858f)
        val cR = pt(0.565f, 0.858f)
        val tRB = pt(0.565f, 0.785f)
        val tR = pt(0.565f, 0.700f)
        val tongue = scratch.path1.apply {
            rewind()
            moveTo(tL.x, tL.y)
            lineTo(tLB.x, tLB.y)
            cubicTo(cL.x, cL.y, cR.x, cR.y, tRB.x, tRB.y)
            lineTo(tR.x, tR.y)
            close()
        }
        drawPath(tongue, color = Color(0xFFFF7FA3).copy(alpha = alpha))
        drawPath(tongue, color = outline, style = thinStroke)
        drawLine(outline, pt(0.515f, 0.715f), pt(0.515f, 0.790f), thinW * 0.72f, StrokeCap.Round)

        val mL = pt(0.415f, 0.675f)
        val mC = pt(0.515f, 0.725f)
        val mR = pt(0.615f, 0.675f)
        val mouthTop = scratch.path2.apply {
            rewind()
            moveTo(mL.x, mL.y)
            quadraticTo(mC.x, mC.y, mR.x, mR.y)
        }
        drawPath(mouthTop, color = outline, style = thinStroke)
    }

    // Exact glossy dark puppy nose at (0.515, 0.612)
    val noseCenter = pt(0.515f, 0.612f)
    drawOval(
        color = outline,
        topLeft = Offset(noseCenter.x - imgW * 0.036f, noseCenter.y - imgH * 0.026f),
        size = Size(imgW * 0.072f, imgH * 0.052f)
    )
    drawOval(
        color = Color.White.copy(alpha = 0.55f * alpha),
        topLeft = Offset(noseCenter.x - imgW * 0.018f, noseCenter.y - imgH * 0.016f),
        size = Size(imgW * 0.018f, imgH * 0.011f)
    )
}

private fun DrawScope.drawTierOpeningBurst(
    origin: Offset,
    progress: Float,
    tier: MonthlyChestTier,
    spec: MysteryLightSpec,
    chestSize: Float
) {
    val colors = when (tier) {
        MonthlyChestTier.BRONZE -> BronzeBurstColors
        MonthlyChestTier.SILVER -> SilverBurstColors
        MonthlyChestTier.GOLD -> GoldBurstColors
    }
    val ringCount = when (tier) {
        MonthlyChestTier.BRONZE -> 1
        MonthlyChestTier.SILVER -> 2
        MonthlyChestTier.GOLD -> 3
    }
    val maxDist = chestSize * when (tier) {
        MonthlyChestTier.BRONZE -> 0.50f
        MonthlyChestTier.SILVER -> 0.68f
        MonthlyChestTier.GOLD -> 0.88f
    }

    // Expanding luminous shockwave rings
    for (rIdx in 0 until ringCount) {
        val rp = ((progress - rIdx * 0.11f) / (1f - rIdx * 0.11f)).coerceIn(0f, 1f)
        if (rp <= 0.01f) continue
        val ringR = maxDist * (0.20f + 0.88f * rp)
        val ringAlpha = ((1f - rp) * (0.72f - rIdx * 0.14f)).coerceIn(0f, 1f)
        val ringCol = if (rIdx == 0) spec.coreColor else spec.midColor
        drawCircle(
            color = ringCol.copy(alpha = ringAlpha),
            radius = ringR,
            center = origin,
            style = Stroke(width = (5.5f - rIdx * 1.1f).dp.toPx() * (1f - rp * 0.65f))
        )
    }

    // Radiating tier-colored sparkles & light motes
    val alpha = (1f - progress * 0.78f).coerceIn(0f, 1f)
    val count = when (tier) {
        MonthlyChestTier.BRONZE -> 8
        MonthlyChestTier.SILVER -> 14
        MonthlyChestTier.GOLD -> 20
    }
    for (i in 0 until count) {
        val angle = (-175.0 + (170.0 / (count - 1).coerceAtLeast(1)) * i) * PI / 180.0
        val dist = maxDist * (0.42f + 0.58f * ((i * 37) % 10) / 10f) * progress
        val px = origin.x + (cos(angle) * dist).toFloat()
        val py = origin.y + (sin(angle) * dist).toFloat() + progress * progress * 14.dp.toPx()
        val c = colors[i % colors.size]
        if (i % 2 == 0) {
            drawFourPointSparkle(
                center = Offset(px, py),
                radius = (5.5f + (i % 3) * 2.2f).dp.toPx() * (1f - progress * 0.30f),
                color = c,
                alpha = alpha
            )
        } else {
            drawCircle(
                color = c.copy(alpha = alpha),
                radius = (2.8f + (i % 2) * 1.6f).dp.toPx() * (1f - progress * 0.25f),
                center = Offset(px, py)
            )
        }
    }
}
