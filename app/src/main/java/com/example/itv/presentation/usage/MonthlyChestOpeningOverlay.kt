package com.example.itv.presentation.usage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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

/**
 * 강아지가 등급별 고유 열쇠(동/은/금)를 입에 물고 달려와 보물상자의 뼈다귀 자물쇠를 열어주는 연출 오버레이.
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

    // Choreography animatables
    val runInProgress = remember { Animatable(0f) }       // 0 -> 1: 강아지가 열쇠 물고 달려옴
    val keyFlightProgress = remember { Animatable(0f) }   // 0 -> 1: 입에서 뼈다귀 자물쇠로 열쇠 전달
    val keyTurnProgress = remember { Animatable(0f) }     // 0 -> 1: 열쇠 회전 + 자물쇠 해제
    val lockPopProgress = remember { Animatable(0f) }     // 0 -> 1: 뼈다귀 자물쇠 톡 풀림
    val chestSquash = remember { Animatable(1f) }         // 열리기 직전 쫀득한 스쿼시
    val chestOpenProgress = remember { Animatable(0f) }   // 0 -> 1: 상자 뚜껑 활짝 + 보물 등장
    val burstProgress = remember { Animatable(0f) }       // 0 -> 1: 축하 파티클 퍼짐
    val puppyCheer = remember { Animatable(0f) }          // 0 -> 1: 강아지 기쁨 점프

    var phaseCaption by remember { mutableStateOf("강아지가 ${request.tier.keyLabel}를 물고 달려오고 있어요!") }
    var showRewardCard by remember { mutableStateOf(false) }

    val infinite = rememberInfiniteTransition(label = "chest_glow")
    val glowPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_phase"
    )

    LaunchedEffect(request) {
        // Phase 1: 강아지가 열쇠를 입에 물고 왼쪽에서 달려옴
        runInProgress.animateTo(1f, animationSpec = tween(durationMillis = 980, easing = FastOutSlowInEasing))
        delay(120)

        // Phase 2: 열쇠를 뼈다귀 자물쇠로 쏙 전달
        phaseCaption = "뼈다귀 자물쇠에 ${request.tier.keyLabel}를 꽂는 중..."
        keyFlightProgress.animateTo(1f, animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing))
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

        // Phase 3: 딸깍! 열쇠 회전 + 자물쇠 팝!
        keyTurnProgress.animateTo(1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        coroutineScope {
            launch { lockPopProgress.animateTo(1f, animationSpec = tween(durationMillis = 260)) }
            launch {
                chestSquash.animateTo(0.88f, animationSpec = tween(durationMillis = 110))
                chestSquash.animateTo(1.06f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                chestSquash.animateTo(1f, animationSpec = tween(durationMillis = 120))
            }
        }

        // Phase 4: 보물상자 활짝 열림 + 강아지 만세 점프!
        phaseCaption = "${request.tier.title}가 활짝 열렸어요!"
        coroutineScope {
            launch {
                chestOpenProgress.animateTo(
                    1f,
                    animationSpec = spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow)
                )
            }
            launch { burstProgress.animateTo(1f, animationSpec = tween(durationMillis = 720, easing = FastOutSlowInEasing)) }
            launch {
                puppyCheer.animateTo(1f, animationSpec = tween(durationMillis = 240))
                puppyCheer.animateTo(0f, animationSpec = tween(durationMillis = 220))
                puppyCheer.animateTo(0.75f, animationSpec = tween(durationMillis = 200))
                puppyCheer.animateTo(0f, animationSpec = tween(durationMillis = 220))
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
                .background(Color.Black.copy(alpha = 0.62f))
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
                    .widthIn(max = 390.dp)
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

                    // Main Animation Stage: Puppy trotting in with Key in Mouth + Bone-Lock Chest!
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(248.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            // Stage background soft sunlit halo
                            val stageCenter = Offset(w * 0.60f, h * 0.56f)
                            drawCircle(
                                color = palette.glowInner.copy(alpha = if (isDark) 0.12f else 0.24f),
                                radius = minOf(w, h) * 0.44f,
                                center = stageCenter
                            )

                            // 1) Draw Chest on right-center of stage (with squash & lid open!)
                            val chestSize = minOf(w * 0.58f, h * 0.86f)
                            val chestOriginX = w * 0.61f - chestSize * 0.5f
                            val chestOriginY = h * 0.54f - chestSize * 0.5f
                            val squashY = chestSquash.value
                            val squashX = 2f - squashY
                            withTransform({
                                translate(left = chestOriginX, top = chestOriginY)
                                scale(
                                    scaleX = squashX,
                                    scaleY = squashY,
                                    pivot = Offset(chestSize * 0.5f, chestSize * 0.85f)
                                )
                            }) {
                                drawMonthlyRewardChest(
                                    tier = request.tier,
                                    openProgress = chestOpenProgress.value,
                                    lockPopProgress = lockPopProgress.value,
                                    glowPhase = glowPhase
                                )
                            }

                            val lockWorldPos = Offset(
                                chestOriginX + chestSize * 0.50f,
                                chestOriginY + chestSize * 0.635f
                            )

                            // 2) Draw Puppy running in from left, holding the unique key in its mouth!
                            val rp = runInProgress.value
                            val startX = -w * 0.14f
                            val stopX = w * 0.23f
                            val puppyFootX = startX + (stopX - startX) * rp
                            val stepPhase = rp * 6.5f * PI.toFloat()
                            val runBounce = if (rp < 0.99f) abs(sin(stepPhase)) * 8.dp.toPx() else 0f
                            val cheerJump = puppyCheer.value * 18.dp.toPx()
                            val puppyFootY = h * 0.84f - runBounce - cheerJump

                            val spritePx = minOf(w, h) * 0.52f
                            if (gallopFrames.isNotEmpty()) {
                                val frameIdx = if (rp < 0.99f) {
                                    ((rp * 14f).toInt()).mod(gallopFrames.size)
                                } else {
                                    0
                                }
                                val bodyBmp = gallopFrames[frameIdx]
                                val bodyLeft = (puppyFootX - spritePx * 0.50f).roundToInt()
                                val bodyTop = (puppyFootY - spritePx * 0.90f).roundToInt()
                                val dim = spritePx.roundToInt().coerceAtLeast(1)
                                drawImage(
                                    image = bodyBmp,
                                    dstOffset = IntOffset(bodyLeft, bodyTop),
                                    dstSize = IntSize(dim, dim)
                                )
                            }

                            val headW = spritePx * 0.52f
                            val headH = headW / character.aspectRatio
                            val neckY = puppyFootY - spritePx * 0.39f
                            val headTop = neckY + headH * 0.22f - headH
                            val headLeft = puppyFootX - headW * 0.50f
                            val headCenter = Offset(puppyFootX, headTop + headH * 0.5f)
                            val headTilt = if (rp < 0.99f) {
                                sin(stepPhase) * 3.5f
                            } else {
                                -6f * keyFlightProgress.value + 5f * puppyCheer.value
                            }
                            val mouthPos = character.landmarks.toCanvasOffset(
                                nx = 0.515f,
                                ny = 0.72f,
                                imgLeft = headLeft,
                                imgTop = headTop,
                                imgW = headW,
                                imgH = headH
                            )

                            val keyStillInMouth = keyFlightProgress.value < 0.04f
                            val keyLength = minOf(w, h) * 0.25f

                            withTransform({
                                rotate(degrees = headTilt, pivot = headCenter)
                            }) {
                                simpleHeadBitmap?.let { bmp ->
                                    drawImage(
                                        image = bmp,
                                        dstOffset = IntOffset(headLeft.roundToInt(), headTop.roundToInt()),
                                        dstSize = IntSize(
                                            headW.roundToInt().coerceAtLeast(1),
                                            headH.roundToInt().coerceAtLeast(1)
                                        )
                                    )
                                }
                                drawPuppyHappyKeyFace(
                                    character = character,
                                    imgLeft = headLeft,
                                    imgTop = headTop,
                                    imgW = headW,
                                    imgH = headH,
                                    isBitingKey = keyStillInMouth,
                                    isCheering = chestOpenProgress.value > 0.2f
                                )
                                // 열쇠를 입에 물고 있는 동안: 입 위치에 열쇠를 그린 뒤 코/윗입술을 위에 겹쳐 '물고 있는 느낌' 완성!
                                if (keyStillInMouth) {
                                    drawRewardKey(
                                        tier = request.tier,
                                        center = Offset(mouthPos.x + headW * 0.06f, mouthPos.y + headH * 0.02f),
                                        length = keyLength,
                                        rotationDeg = 8f + sin(stepPhase) * 4f
                                    )
                                    // Cute white muzzle lip + black nose overlapping the middle of the key shaft
                                    drawPuppyMuzzleClenchOverlay(
                                        character = character,
                                        imgLeft = headLeft,
                                        imgTop = headTop,
                                        imgW = headW,
                                        imgH = headH,
                                        furColor = character.furColor
                                    )
                                }
                            }

                            // 3) Key flying from puppy mouth -> bone padlock -> turning & sparkling!
                            if (!keyStillInMouth && lockPopProgress.value < 0.92f) {
                                val f = keyFlightProgress.value
                                val arcLift = sin(f * PI.toFloat()) * 42.dp.toPx()
                                val keyX = mouthPos.x + (lockWorldPos.x - mouthPos.x) * f
                                val keyY = mouthPos.y + (lockWorldPos.y - mouthPos.y) * f - arcLift
                                val keyRot = 8f * (1f - f) + (-14f * f) + (keyTurnProgress.value * 92f)
                                val keyAlpha = (1f - lockPopProgress.value).coerceIn(0f, 1f)
                                drawRewardKey(
                                    tier = request.tier,
                                    center = Offset(keyX, keyY),
                                    length = keyLength * (1f + 0.14f * sin(f * PI.toFloat())),
                                    rotationDeg = keyRot,
                                    alpha = keyAlpha
                                )
                                if (keyTurnProgress.value > 0.1f) {
                                    val sparkAlpha = (sin(keyTurnProgress.value * PI.toFloat())).coerceIn(0f, 1f)
                                    drawFourPointSparkle(
                                        center = Offset(lockWorldPos.x + 14.dp.toPx(), lockWorldPos.y - 12.dp.toPx()),
                                        radius = 10.dp.toPx(),
                                        color = palette.glowInner,
                                        alpha = sparkAlpha
                                    )
                                }
                            }

                            // 4) Opening Celebration Confetti / Sparkle Burst
                            if (burstProgress.value > 0.01f) {
                                drawCelebrationBurst(
                                    origin = Offset(chestOriginX + chestSize * 0.50f, chestOriginY + chestSize * 0.44f),
                                    progress = burstProgress.value,
                                    tier = request.tier
                                )
                            }
                        }
                    }

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
        }
    }
}

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

private fun DrawScope.drawPuppyHappyKeyFace(
    character: StampCharacterSpec,
    imgLeft: Float,
    imgTop: Float,
    imgW: Float,
    imgH: Float,
    isBitingKey: Boolean,
    isCheering: Boolean
) {
    val outline = Color(0xFF2B231E)
    val lm = character.landmarks
    val eyeStroke = (imgW * 0.026f).coerceIn(1.8f, 4.2f)

    // Rosy happy cheeks
    val leftCheek = lm.toCanvasOffset(0.32f, 0.65f, imgLeft, imgTop, imgW, imgH)
    val rightCheek = lm.toCanvasOffset(0.70f, 0.65f, imgLeft, imgTop, imgW, imgH)
    val cheekR = imgW * 0.062f
    drawOval(
        color = Color(0xFFFF8CA0).copy(alpha = 0.42f),
        topLeft = Offset(leftCheek.x - cheekR, leftCheek.y - cheekR * 0.65f),
        size = Size(cheekR * 2f, cheekR * 1.3f)
    )
    drawOval(
        color = Color(0xFFFF8CA0).copy(alpha = 0.42f),
        topLeft = Offset(rightCheek.x - cheekR, rightCheek.y - cheekR * 0.65f),
        size = Size(cheekR * 2f, cheekR * 1.3f)
    )

    // Happy arched smiling eyes (^ ^)
    val leftEye = lm.toCanvasOffset(0.39f, 0.52f, imgLeft, imgTop, imgW, imgH)
    val rightEye = lm.toCanvasOffset(0.63f, 0.52f, imgLeft, imgTop, imgW, imgH)
    val eyeW = imgW * 0.068f
    val eyeArch = imgH * (if (isCheering) 0.062f else 0.048f)
    listOf(leftEye, rightEye).forEach { eye ->
        val arc = Path().apply {
            moveTo(eye.x - eyeW, eye.y + eyeArch * 0.35f)
            quadraticTo(eye.x, eye.y - eyeArch, eye.x + eyeW, eye.y + eyeArch * 0.35f)
        }
        drawPath(arc, color = outline, style = Stroke(width = eyeStroke, cap = StrokeCap.Round))
    }

    // If not biting key anymore, draw nose + delighted smile!
    if (!isBitingKey) {
        val nose = lm.toCanvasOffset(0.515f, 0.63f, imgLeft, imgTop, imgW, imgH)
        drawOval(
            color = outline,
            topLeft = Offset(nose.x - imgW * 0.040f, nose.y - imgH * 0.028f),
            size = Size(imgW * 0.080f, imgH * 0.056f)
        )
        val mouth = lm.toCanvasOffset(0.515f, 0.72f, imgLeft, imgTop, imgW, imgH)
        val mw = imgW * 0.092f
        val mh = imgH * (if (isCheering) 0.082f else 0.062f)
        val smilePath = Path().apply {
            moveTo(mouth.x - mw, mouth.y - mh * 0.25f)
            quadraticTo(mouth.x, mouth.y + mh * 1.15f, mouth.x + mw, mouth.y - mh * 0.25f)
            close()
        }
        drawPath(smilePath, color = Color(0xFFFF7A96))
        drawPath(smilePath, color = outline, style = Stroke(width = eyeStroke * 0.85f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawPuppyMuzzleClenchOverlay(
    character: StampCharacterSpec,
    imgLeft: Float,
    imgTop: Float,
    imgW: Float,
    imgH: Float,
    furColor: Color
) {
    val outline = Color(0xFF2B231E)
    val lm = character.landmarks
    val nose = lm.toCanvasOffset(0.515f, 0.635f, imgLeft, imgTop, imgW, imgH)
    val lipStroke = (imgW * 0.022f).coerceIn(1.5f, 3.4f)

    // Upper white muzzle pillows overlapping the key shaft so the key is visibly clenched inside the mouth
    val pillowR = imgW * 0.062f
    drawCircle(color = furColor, radius = pillowR, center = Offset(nose.x - pillowR * 0.72f, nose.y + imgH * 0.055f))
    drawCircle(color = furColor, radius = pillowR, center = Offset(nose.x + pillowR * 0.72f, nose.y + imgH * 0.055f))

    val wLip = Path().apply {
        moveTo(nose.x - pillowR * 1.45f, nose.y + imgH * 0.062f)
        quadraticTo(nose.x - pillowR * 0.68f, nose.y + imgH * 0.125f, nose.x, nose.y + imgH * 0.072f)
        quadraticTo(nose.x + pillowR * 0.68f, nose.y + imgH * 0.125f, nose.x + pillowR * 1.45f, nose.y + imgH * 0.062f)
    }
    drawPath(wLip, color = outline, style = Stroke(width = lipStroke, cap = StrokeCap.Round))

    // Cute dark oval nose sitting right above the clenched key
    drawOval(
        color = outline,
        topLeft = Offset(nose.x - imgW * 0.042f, nose.y - imgH * 0.028f),
        size = Size(imgW * 0.084f, imgH * 0.058f)
    )
}

private fun DrawScope.drawCelebrationBurst(
    origin: Offset,
    progress: Float,
    tier: MonthlyChestTier
) {
    val colors = when (tier) {
        MonthlyChestTier.BRONZE -> listOf(Color(0xFFFFD166), Color(0xFFE59855), Color(0xFFFFF1C1), Color(0xFF9E62D4))
        MonthlyChestTier.SILVER -> listOf(Color(0xFF6CF0FF), Color(0xFFE5F6FF), Color(0xFF4BA9C4), Color(0xFFA8E6FF))
        MonthlyChestTier.GOLD -> listOf(Color(0xFFFFD84A), Color(0xFFFF5E99), Color(0xFF4BB8FF), Color(0xFFFFA93A), Color(0xFFFFF49A))
    }
    val alpha = (1f - progress * 0.75f).coerceIn(0f, 1f)
    val maxDist = size.minDimension * 0.44f
    val count = if (tier == MonthlyChestTier.GOLD) 14 else 10
    for (i in 0 until count) {
        val angle = (-165.0 + (150.0 / (count - 1)) * i) * PI / 180.0
        val dist = maxDist * (0.45f + 0.55f * ((i * 37) % 10) / 10f) * progress
        val px = origin.x + (cos(angle) * dist).toFloat()
        val py = origin.y + (sin(angle) * dist).toFloat() + progress * progress * 22.dp.toPx()
        val c = colors[i % colors.size]
        if (i % 2 == 0) {
            drawFourPointSparkle(
                center = Offset(px, py),
                radius = (5.5f + (i % 3) * 2f).dp.toPx() * (1f - progress * 0.35f),
                color = c,
                alpha = alpha
            )
        } else {
            drawCircle(
                color = c.copy(alpha = alpha),
                radius = (3.2f + (i % 2) * 1.5f).dp.toPx(),
                center = Offset(px, py)
            )
        }
    }
}
