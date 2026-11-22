package com.example.itv.presentation.usage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.itv.domain.records.DailyHealthTier
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.LocalDarkTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Resolves the monochrome rubber-stamp ink color for each tier.
 */
@Composable
internal fun stampInkColor(
    tier: DailyHealthTier,
    onSelectedSurface: Boolean = false
): Color {
    if (onSelectedSurface) return Color.White
    val isDark = LocalDarkTheme.current
    return when (tier) {
        DailyHealthTier.GOLD -> if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)     // 1단계: 해맑음 (그린 잉크)
        DailyHealthTier.SILVER -> if (isDark) Color(0xFF38BDF8) else Color(0xFF0369A1)   // 2단계: 웃음 (스카이 블루 잉크)
        DailyHealthTier.BRONZE -> if (isDark) Color(0xFFA5B4FC) else Color(0xFF4F46E5)   // 3단계: 슬픔 (인디고 잉크)
        DailyHealthTier.WARNING -> if (isDark) Color(0xFFFB923C) else Color(0xFFD97706)  // 4단계: 화남 (오렌지/앰버 잉크)
        DailyHealthTier.CRITICAL -> if (isDark) Color(0xFFF87171) else Color(0xFFDC2626) // 5단계: 아픔 (레드 잉크)
        DailyHealthTier.IN_PROGRESS -> ItvGreen
        DailyHealthTier.NO_DATA -> Color.Transparent
    }
}

/**
 * Monochrome ink-outline rubber stamp using ITV's real puppy character silhouette (`itv_puppy_face`)
 * with 5 expressions and optional animated tier reaction (`reactionPhase` in 0f..1f):
 * - GOLD (1단계): 혀 내밀고 해맑게 웃는 얼굴 + 행복한 바운스 & 반짝이/하트
 * - SILVER (2단계): 기분 좋게 웃는 얼굴 + 행복한 바운스 & 반짝이/하트
 * - BRONZE (3단계): 슬픈 얼굴 + 눈물이 흐르는 모션
 * - WARNING (4단계): 화난 얼굴 + 짖는 모션 (입 벌림 + 음파 + 화남 마크)
 * - CRITICAL (5단계): 아픈 얼굴 (체온계) + 열이 나는 모션 (오한 떨림 + 열기 김 + 체온계 펄스)
 * - IN_PROGRESS: 기상 전 집계 중 점선 시계 링
 */
@Composable
internal fun DailyHealthStampBadge(
    tier: DailyHealthTier,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onSelectedSurface: Boolean = false,
    tiltDegrees: Float = 0f,
    reactionPhase: Float = 0f,
    character: StampCharacterSpec = StampCharacterRegistry.default
) {
    val context = LocalContext.current
    val outlineBitmap by produceState(
        initialValue = StampCharacterBitmapCache.getCachedMonochromeOutline(character.id),
        key1 = context,
        key2 = character.id
    ) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                StampCharacterBitmapCache.getMonochromeOutline(context, character)
            }
        }
    }
    val ink = stampInkColor(tier, onSelectedSurface)

    Canvas(modifier = modifier.size(size)) {
        if (tier == DailyHealthTier.NO_DATA) return@Canvas
        if (tier == DailyHealthTier.IN_PROGRESS) {
            drawInProgressStamp(ink)
            return@Canvas
        }

        val p = reactionPhase.coerceIn(0f, 1f)
        val isAnimating = p > 0.001f

        // Compute whole-face motion offsets for the post-stamp reaction phase
        var animTx = 0f
        var animTy = 0f
        var animRot = 0f
        var animScaleX = 1f
        var animScaleY = 1f

        if (isAnimating) {
            when (tier) {
                DailyHealthTier.GOLD, DailyHealthTier.SILVER -> {
                    // Joyful bounce & happy head wiggle
                    val bounce = abs(sin(p * PI * 3.0)).toFloat()
                    animTy = -this.size.height * 0.055f * bounce
                    animRot = sin(p * PI * 4.0).toFloat() * 7.5f
                    animScaleX = 1f + 0.05f * (1f - bounce)
                    animScaleY = 1f + 0.06f * bounce
                }
                DailyHealthTier.BRONZE -> {
                    // Gentle sad droop & sniffle sway
                    val droop = sin(p * PI * 2.0).toFloat()
                    animTy = this.size.height * 0.025f * abs(droop)
                    animRot = -3.5f * droop
                }
                DailyHealthTier.WARNING -> {
                    // 3 rhythmic barking lunges!
                    val bark = computeBarkPulse(p)
                    animTy = -this.size.height * 0.045f * bark
                    animRot = -5.5f * bark
                    animScaleX = 1f + 0.045f * bark
                    animScaleY = 1f + 0.045f * bark
                }
                DailyHealthTier.CRITICAL -> {
                    // Feverish shiver tremble
                    val shiver = sin(p * PI * 24.0).toFloat()
                    animTx = this.size.width * 0.016f * shiver
                    animRot = shiver * 1.8f
                }
                else -> Unit
            }
        }

        withTransform({
            translate(left = animTx, top = animTy)
            rotate(degrees = tiltDegrees + animRot, pivot = center)
            scale(scaleX = animScaleX, scaleY = animScaleY, pivot = center)
        }) {
            drawRealCharacterStamp(
                tier = tier,
                ink = ink,
                outlineBitmap = outlineBitmap,
                character = character,
                reactionPhase = p
            )
        }
    }
}

/**
 * Computes a crisp 3-bark rhythmic pulse (0f..1f) over phase `p` in [0..1].
 */
private fun computeBarkPulse(p: Float): Float {
    if (p <= 0.01f) return 0f
    val local = (p * 3f) % 1f
    return when {
        local < 0.22f -> sin((local / 0.22f) * PI).toFloat().coerceIn(0f, 1f)
        local < 0.44f -> sin(((local - 0.22f) / 0.22f) * PI).toFloat().coerceIn(0f, 1f) * 0.65f
        else -> 0f
    }
}

/**
 * Compact inline "도장 쾅!" slam wrapper used in the detail verdict banner.
 */
@Composable
internal fun SlammablePuppyStamp(
    tier: DailyHealthTier,
    slamKey: Int,
    modifier: Modifier = Modifier,
    size: Dp = 62.dp,
    onSelectedSurface: Boolean = false,
    tiltDegrees: Float = -4f,
    character: StampCharacterSpec = StampCharacterRegistry.default
) {
    val haptic = LocalHapticFeedback.current
    val ink = stampInkColor(tier, onSelectedSurface)
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(1f) }
    val rippleProgress = remember { Animatable(1f) }

    LaunchedEffect(slamKey) {
        if (slamKey <= 0) return@LaunchedEffect
        scale.snapTo(2.2f)
        alpha.snapTo(0.25f)
        rippleProgress.snapTo(0f)

        alpha.animateTo(1f, tween(durationMillis = 110))
        scale.animateTo(
            targetValue = 0.86f,
            animationSpec = tween(durationMillis = 130, easing = FastOutLinearInEasing)
        )
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        rippleProgress.animateTo(1f, tween(durationMillis = 340))
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (rippleProgress.value < 0.99f) {
            Canvas(modifier = Modifier.size(size)) {
                val p = rippleProgress.value
                val r = (this.size.minDimension * 0.46f) * (1f + p * 0.55f)
                drawCircle(
                    color = ink.copy(alpha = (1f - p) * 0.55f),
                    radius = r,
                    style = Stroke(width = (this.size.minDimension * 0.042f * (1f - p * 0.6f)).coerceAtLeast(1f))
                )
            }
        }

        DailyHealthStampBadge(
            tier = tier,
            size = size,
            onSelectedSurface = onSelectedSurface,
            tiltDegrees = tiltDegrees,
            character = character,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
        )
    }
}

private fun DrawScope.drawRealCharacterStamp(
    tier: DailyHealthTier,
    ink: Color,
    outlineBitmap: ImageBitmap?,
    character: StampCharacterSpec,
    reactionPhase: Float
) {
    val w = size.width
    val h = size.height

    // Reserve clean headroom above forehead for sparkles / anger mark / fever steam
    val imgW = w * 0.96f
    val imgH = imgW / character.aspectRatio
    val imgLeft = (w - imgW) * 0.5f
    val imgTop = (h - imgH) * 0.56f

    // Map normalized face landmarks using `character.landmarks` so any swapped/added mascot aligns cleanly!
    fun pt(nx: Float, ny: Float): Offset =
        character.landmarks.toCanvasOffset(nx, ny, imgLeft, imgTop, imgW, imgH)

    // 1. Draw ITV's genuine puppy face outline silhouette in pure monochrome stamp ink
    if (outlineBitmap != null) {
        drawImage(
            image = outlineBitmap,
            dstOffset = IntOffset(imgLeft.roundToInt(), imgTop.roundToInt()),
            dstSize = IntSize(imgW.roundToInt().coerceAtLeast(1), imgH.roundToInt().coerceAtLeast(1)),
            colorFilter = ColorFilter.tint(ink)
        )
    } else {
        drawFallbackHeadOutline(imgLeft, imgTop, imgW, imgH, ink)
    }

    val lineW = (imgW * 0.042f).coerceAtLeast(1.25f)
    val thinW = (imgW * 0.032f).coerceAtLeast(1.0f)
    val stroke = Stroke(width = lineW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thinStroke = Stroke(width = thinW, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // 2. Cute puppy nose dot centered at exact real landmark (0.515, 0.610)
    val noseCenter = pt(0.515f, 0.612f)
    drawOval(
        color = ink,
        topLeft = Offset(noseCenter.x - imgW * 0.036f, noseCenter.y - imgH * 0.026f),
        size = Size(imgW * 0.072f, imgH * 0.052f)
    )

    val isAnim = reactionPhase > 0.001f

    // 3. Monochrome ink expressions aligned precisely to (0.405, 0.515) & (0.625, 0.515) eyes
    when (tier) {
        DailyHealthTier.GOLD -> {
            // 1단계: 혀를 내밀고 해맑게 웃는 얼굴
            val leftEye = Path().apply {
                val p0 = pt(0.355f, 0.535f)
                val ctrl = pt(0.405f, 0.445f)
                val p1 = pt(0.455f, 0.535f)
                moveTo(p0.x, p0.y)
                quadraticTo(ctrl.x, ctrl.y, p1.x, p1.y)
            }
            val rightEye = Path().apply {
                val p0 = pt(0.575f, 0.535f)
                val ctrl = pt(0.625f, 0.445f)
                val p1 = pt(0.675f, 0.535f)
                moveTo(p0.x, p0.y)
                quadraticTo(ctrl.x, ctrl.y, p1.x, p1.y)
            }
            drawPath(leftEye, color = ink, style = stroke)
            drawPath(rightEye, color = ink, style = stroke)

            // Wide happy open mouth + playful tongue outline (subtle wiggle when animating)
            val tongueWiggle = if (isAnim) sin(reactionPhase * PI * 6.0).toFloat() * 0.015f else 0f
            val mouthTop = Path().apply {
                val m0 = pt(0.415f, 0.675f)
                val mc = pt(0.515f, 0.725f)
                val m1 = pt(0.615f, 0.675f)
                moveTo(m0.x, m0.y)
                quadraticTo(mc.x, mc.y, m1.x, m1.y)
            }
            drawPath(mouthTop, color = ink, style = thinStroke)

            val tongue = Path().apply {
                val tL = pt(0.465f + tongueWiggle, 0.700f)
                val tLB = pt(0.465f + tongueWiggle, 0.785f)
                val cL = pt(0.465f + tongueWiggle, 0.855f)
                val cR = pt(0.565f + tongueWiggle, 0.855f)
                val tRB = pt(0.565f + tongueWiggle, 0.785f)
                val tR = pt(0.565f + tongueWiggle, 0.700f)
                moveTo(tL.x, tL.y)
                lineTo(tLB.x, tLB.y)
                cubicTo(cL.x, cL.y, cR.x, cR.y, tRB.x, tRB.y)
                lineTo(tR.x, tR.y)
            }
            drawPath(tongue, color = ink, style = thinStroke)
            drawLine(
                color = ink,
                start = pt(0.515f + tongueWiggle, 0.715f),
                end = pt(0.515f + tongueWiggle, 0.790f),
                strokeWidth = thinW * 0.75f,
                cap = StrokeCap.Round
            )

            // Happy sparkle rays above head (static subtle rays + animated floating sparkles & hearts)
            if (!isAnim) {
                drawLine(ink, pt(0.515f, 0.01f), pt(0.515f, -0.08f), thinW * 0.85f, StrokeCap.Round)
                drawLine(ink, pt(0.39f, 0.05f), pt(0.34f, -0.02f), thinW * 0.85f, StrokeCap.Round)
                drawLine(ink, pt(0.64f, 0.05f), pt(0.69f, -0.02f), thinW * 0.85f, StrokeCap.Round)
            } else {
                drawHappyCelebrationEffects(pt = ::pt, imgW = imgW, ink = ink, thinW = thinW, p = reactionPhase, extraRich = true)
            }
        }

        DailyHealthTier.SILVER -> {
            // 2단계: 기분 좋게 웃는 얼굴 (동글 반짝 눈 + 귀여운 w 미소)
            val eyeRadius = (imgW * 0.032f).coerceAtLeast(1.3f)
            drawCircle(color = ink, radius = eyeRadius, center = pt(0.405f, 0.515f))
            drawCircle(color = ink, radius = eyeRadius, center = pt(0.625f, 0.515f))

            // Cute happy 'w' puppy smile
            val smile = Path().apply {
                val p0 = pt(0.415f, 0.690f)
                val c1 = pt(0.465f, 0.765f)
                val mid = pt(0.515f, 0.680f)
                val c2 = pt(0.565f, 0.765f)
                val p1 = pt(0.615f, 0.690f)
                moveTo(p0.x, p0.y)
                quadraticTo(c1.x, c1.y, mid.x, mid.y)
                quadraticTo(c2.x, c2.y, p1.x, p1.y)
            }
            drawPath(smile, color = ink, style = thinStroke)

            if (isAnim) {
                drawHappyCelebrationEffects(pt = ::pt, imgW = imgW, ink = ink, thinW = thinW, p = reactionPhase, extraRich = false)
            }
        }

        DailyHealthTier.BRONZE -> {
            // 3단계: 슬픈 얼굴 (처진 눈썹 / \ + 둥근 눈 + 시무룩 입 + 눈물방울)
            drawLine(ink, pt(0.355f, 0.455f), pt(0.445f, 0.415f), thinW, StrokeCap.Round)
            drawLine(ink, pt(0.675f, 0.455f), pt(0.585f, 0.415f), thinW, StrokeCap.Round)

            val eyeRadius = (imgW * 0.030f).coerceAtLeast(1.2f)
            drawCircle(color = ink, radius = eyeRadius, center = pt(0.405f, 0.525f))
            drawCircle(color = ink, radius = eyeRadius, center = pt(0.625f, 0.525f))

            // Downturned sad frown
            val frown = Path().apply {
                val p0 = pt(0.435f, 0.755f)
                val ctrl = pt(0.515f, 0.675f)
                val p1 = pt(0.595f, 0.755f)
                moveTo(p0.x, p0.y)
                quadraticTo(ctrl.x, ctrl.y, p1.x, p1.y)
            }
            drawPath(frown, color = ink, style = thinStroke)

            if (!isAnim) {
                // Static teardrop outline under right eye
                drawTeardrop(center = pt(0.655f, 0.600f), dropW = imgW * 0.048f, ink = ink, strokeW = thinW * 0.8f, alpha = 1f)
            } else {
                // Animated flowing tears trickling down from BOTH eyes in staggered cycles!
                val cycles = listOf(
                    Triple(0.375f, 0.0f, 1.0f),
                    Triple(0.655f, 0.35f, 1.0f),
                    Triple(0.385f, 0.65f, 0.9f),
                    Triple(0.645f, 0.85f, 0.9f)
                )
                cycles.forEach { (eyeX, phaseOffset, scaleMul) ->
                    val t = ((reactionPhase * 1.9f + phaseOffset) % 1f)
                    val tearY = 0.565f + t * 0.32f
                    val tearAlpha = when {
                        t < 0.15f -> t / 0.15f
                        t > 0.78f -> (1f - t) / 0.22f
                        else -> 1f
                    }.coerceIn(0f, 1f)
                    val tearSize = imgW * 0.052f * scaleMul * (0.75f + 0.35f * sin(t * PI).toFloat())
                    drawTeardrop(
                        center = pt(eyeX, tearY),
                        dropW = tearSize,
                        ink = ink,
                        strokeW = thinW * 0.85f,
                        alpha = tearAlpha
                    )
                }
                // Wet tear gleam along lower eye rims
                drawArc(
                    color = ink.copy(alpha = 0.65f),
                    startAngle = 15f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(pt(0.365f, 0.525f).x, pt(0.365f, 0.525f).y),
                    size = Size(imgW * 0.08f, imgH * 0.045f),
                    style = Stroke(width = thinW * 0.7f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = ink.copy(alpha = 0.65f),
                    startAngle = 15f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(pt(0.585f, 0.525f).x, pt(0.585f, 0.525f).y),
                    size = Size(imgW * 0.08f, imgH * 0.045f),
                    style = Stroke(width = thinW * 0.7f, cap = StrokeCap.Round)
                )
            }
        }

        DailyHealthTier.WARNING -> {
            // 4단계: 화난 얼굴 (날카로운 \ / 눈썹 + 강렬한 눈 + 짖는 입 + 💢 마크)
            val bark = if (isAnim) computeBarkPulse(reactionPhase) else 0f

            // Sharp furrowed angry brows
            drawLine(ink, pt(0.345f, 0.430f), pt(0.460f, 0.485f), lineW, StrokeCap.Round)
            drawLine(ink, pt(0.685f, 0.430f), pt(0.570f, 0.485f), lineW, StrokeCap.Round)

            val eyeRadius = (imgW * 0.030f).coerceAtLeast(1.2f)
            drawCircle(color = ink, radius = eyeRadius, center = pt(0.410f, 0.535f))
            drawCircle(color = ink, radius = eyeRadius, center = pt(0.620f, 0.535f))

            if (bark > 0.12f) {
                // Barking open mouth! Dynamic oval opening + cute little fangs
                val openH = imgH * (0.07f + 0.12f * bark)
                val openW = imgW * (0.12f + 0.07f * bark)
                val mouthCenter = pt(0.515f, 0.735f)
                drawOval(
                    color = ink,
                    topLeft = Offset(mouthCenter.x - openW / 2f, mouthCenter.y - openH / 2f),
                    size = Size(openW, openH),
                    style = stroke
                )
                // Little upper fangs inside barking mouth
                val fangTopY = mouthCenter.y - openH * 0.42f
                val fangBotY = mouthCenter.y - openH * 0.08f
                drawLine(ink, Offset(mouthCenter.x - openW * 0.25f, fangTopY), Offset(mouthCenter.x - openW * 0.20f, fangBotY), thinW * 0.85f, StrokeCap.Round)
                drawLine(ink, Offset(mouthCenter.x + openW * 0.25f, fangTopY), Offset(mouthCenter.x + openW * 0.20f, fangBotY), thinW * 0.85f, StrokeCap.Round)

                // Concentric barking sound-wave arcs radiating from the mouth/right cheek!
                val waveOrigin = pt(0.75f, 0.65f)
                for (i in 0 until 3) {
                    val r = imgW * (0.075f + i * 0.055f + bark * 0.045f)
                    val waveAlpha = (bark * (1f - i * 0.22f)).coerceIn(0f, 1f)
                    drawArc(
                        color = ink.copy(alpha = waveAlpha),
                        startAngle = -42f,
                        sweepAngle = 84f,
                        useCenter = false,
                        topLeft = Offset(waveOrigin.x - r, waveOrigin.y - r),
                        size = Size(r * 2f, r * 2f),
                        style = Stroke(width = thinW * 0.9f, cap = StrokeCap.Round)
                    )
                }
                // Extra bark burst lines on the left side too for symmetric punch
                val leftWaveOrigin = pt(0.28f, 0.65f)
                for (i in 0 until 2) {
                    val r = imgW * (0.07f + i * 0.055f + bark * 0.04f)
                    val waveAlpha = (bark * (0.9f - i * 0.25f)).coerceIn(0f, 1f)
                    drawArc(
                        color = ink.copy(alpha = waveAlpha),
                        startAngle = 138f,
                        sweepAngle = 84f,
                        useCenter = false,
                        topLeft = Offset(leftWaveOrigin.x - r, leftWaveOrigin.y - r),
                        size = Size(r * 2f, r * 2f),
                        style = Stroke(width = thinW * 0.85f, cap = StrokeCap.Round)
                    )
                }
            } else {
                // Grumpy inverted-V mouth at rest
                val angryMouth = Path().apply {
                    val p0 = pt(0.420f, 0.755f)
                    val mid = pt(0.515f, 0.690f)
                    val p1 = pt(0.610f, 0.755f)
                    moveTo(p0.x, p0.y)
                    lineTo(mid.x, mid.y)
                    lineTo(p1.x, p1.y)
                }
                drawPath(angryMouth, color = ink, style = thinStroke)
            }

            // Comic anger vein mark near top-right forehead (pulses when barking!)
            val angerCenter = pt(0.75f, 0.08f)
            val angerR = imgW * 0.052f * (1f + bark * 0.35f)
            drawAngerMark(center = angerCenter, r = angerR, ink = ink, strokeW = thinW * 0.9f)
        }

        DailyHealthTier.CRITICAL -> {
            // 5단계: 아픈 얼굴 (힘없는 > < 눈 + 체온계 + 이마 열기 김)
            val leftSickEye = Path().apply {
                val p0 = pt(0.365f, 0.465f)
                val mid = pt(0.445f, 0.515f)
                val p1 = pt(0.365f, 0.565f)
                moveTo(p0.x, p0.y)
                lineTo(mid.x, mid.y)
                lineTo(p1.x, p1.y)
            }
            val rightSickEye = Path().apply {
                val p0 = pt(0.665f, 0.465f)
                val mid = pt(0.585f, 0.515f)
                val p1 = pt(0.665f, 0.565f)
                moveTo(p0.x, p0.y)
                lineTo(mid.x, mid.y)
                lineTo(p1.x, p1.y)
            }
            drawPath(leftSickEye, color = ink, style = thinStroke)
            drawPath(rightSickEye, color = ink, style = thinStroke)

            // Small weak 'o' mouth holding the thermometer
            val mouthTopLeft = pt(0.465f, 0.695f)
            drawArc(
                color = ink,
                startAngle = 145f,
                sweepAngle = 230f,
                useCenter = false,
                topLeft = mouthTopLeft,
                size = Size(imgW * 0.10f, imgH * 0.085f),
                style = thinStroke
            )

            // Diagonal thermometer sticking out from mouth to bottom-right
            val thermoStart = pt(0.525f, 0.740f)
            val thermoEnd = pt(0.785f, 0.915f)
            val dx = thermoEnd.x - thermoStart.x
            val dy = thermoEnd.y - thermoStart.y
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
            val halfThick = imgW * 0.025f
            val nx = -dy / len * halfThick
            val ny = dx / len * halfThick

            val thermoBody = Path().apply {
                moveTo(thermoStart.x + nx, thermoStart.y + ny)
                lineTo(thermoEnd.x + nx, thermoEnd.y + ny)
                lineTo(thermoEnd.x - nx, thermoEnd.y - ny)
                lineTo(thermoStart.x - nx, thermoStart.y - ny)
                close()
            }
            drawPath(thermoBody, color = ink, style = Stroke(width = thinW * 0.85f, join = StrokeJoin.Round))
            // Mercury line inside thermometer
            drawLine(
                color = ink,
                start = Offset(thermoStart.x + dx * 0.25f, thermoStart.y + dy * 0.25f),
                end = thermoEnd,
                strokeWidth = thinW * 0.65f,
                cap = StrokeCap.Round
            )
            // Thermometer bulb at outer end
            val feverPulse = if (isAnim) (0.5f + 0.5f * sin(reactionPhase * PI * 8.0).toFloat()) else 0f
            drawCircle(
                color = ink,
                radius = (imgW * (0.032f + 0.008f * feverPulse)).coerceAtLeast(1.2f),
                center = thermoEnd
            )
            if (isAnim) {
                // Pulsing heat ring around thermometer bulb
                drawCircle(
                    color = ink.copy(alpha = (1f - feverPulse) * 0.65f),
                    radius = imgW * (0.045f + 0.045f * feverPulse),
                    center = thermoEnd,
                    style = Stroke(width = thinW * 0.75f)
                )
            }

            // Fever heat steam waves above forehead (animated rising wavy steam when animating!)
            val steamXPositions = listOf(0.41f, 0.515f, 0.62f)
            steamXPositions.forEachIndexed { i, sx ->
                val rise = if (isAnim) ((reactionPhase * 1.8f + i * 0.33f) % 1f) else 0f
                val steamAlpha = if (!isAnim) 0.9f else {
                    when {
                        rise < 0.2f -> rise / 0.2f
                        rise > 0.75f -> (1f - rise) / 0.25f
                        else -> 1f
                    }.coerceIn(0f, 1f)
                }
                val baseY = 0.07f - rise * 0.14f
                val sway = if (isAnim) sin((reactionPhase * 4f + i) * PI).toFloat() * 0.022f else 0.015f
                val wave = Path().apply {
                    val p0 = pt(sx, baseY)
                    val c1 = pt(sx - sway, baseY - 0.055f)
                    val mid = pt(sx, baseY - 0.105f)
                    val c2 = pt(sx + sway, baseY - 0.155f)
                    val p1 = pt(sx, baseY - 0.20f)
                    moveTo(p0.x, p0.y)
                    quadraticTo(c1.x, c1.y, mid.x, mid.y)
                    if (isAnim) {
                        quadraticTo(c2.x, c2.y, p1.x, p1.y)
                    }
                }
                drawPath(
                    path = wave,
                    color = ink.copy(alpha = steamAlpha),
                    style = Stroke(width = thinW * 0.85f, cap = StrokeCap.Round)
                )
            }

            // Fever sweat drop sliding down temple when animating
            if (isAnim) {
                val sweatT = (reactionPhase * 1.5f) % 1f
                val sweatAlpha = (1f - sweatT).coerceIn(0f, 1f)
                drawTeardrop(
                    center = pt(0.715f, 0.32f + sweatT * 0.16f),
                    dropW = imgW * 0.044f,
                    ink = ink,
                    strokeW = thinW * 0.75f,
                    alpha = sweatAlpha
                )
            }
        }

        else -> Unit
    }
}

private fun DrawScope.drawHappyCelebrationEffects(
    pt: (Float, Float) -> Offset,
    imgW: Float,
    ink: Color,
    thinW: Float,
    p: Float,
    extraRich: Boolean
) {
    // Twinkling 4-point sparkles popping around the puppy's head
    val sparkles = if (extraRich) {
        listOf(
            Triple(0.22f, 0.02f, 0.0f),
            Triple(0.515f, -0.10f, 0.25f),
            Triple(0.80f, 0.02f, 0.50f),
            Triple(0.14f, 0.28f, 0.72f),
            Triple(0.88f, 0.28f, 0.18f)
        )
    } else {
        listOf(
            Triple(0.24f, 0.03f, 0.10f),
            Triple(0.78f, 0.03f, 0.45f),
            Triple(0.515f, -0.09f, 0.75f)
        )
    }

    sparkles.forEach { (sx, sy, offset) ->
        val t = (p * 2.2f + offset) % 1f
        val pulse = sin(t * PI).toFloat().coerceIn(0f, 1f)
        val r = imgW * 0.055f * (0.4f + 0.8f * pulse)
        val c = pt(sx, sy - t * 0.05f)
        val sparkleAlpha = pulse * 0.95f
        drawFourPointStar(center = c, r = r, ink = ink.copy(alpha = sparkleAlpha), strokeW = thinW * 0.85f)
    }

    // Floating cute outline heart rising on the top-right
    val heartT = (p * 1.4f) % 1f
    val heartAlpha = sin(heartT * PI).toFloat().coerceIn(0f, 1f)
    val heartCenter = pt(0.82f + sin(heartT * PI * 2.0).toFloat() * 0.03f, 0.16f - heartT * 0.22f)
    drawHeartOutline(
        center = heartCenter,
        size = imgW * 0.075f * (0.8f + 0.25f * heartAlpha),
        ink = ink.copy(alpha = heartAlpha * 0.9f),
        strokeW = thinW * 0.85f
    )
}

private fun DrawScope.drawFourPointStar(center: Offset, r: Float, ink: Color, strokeW: Float) {
    drawLine(ink, Offset(center.x - r, center.y), Offset(center.x + r, center.y), strokeW, StrokeCap.Round)
    drawLine(ink, Offset(center.x, center.y - r), Offset(center.x, center.y + r), strokeW, StrokeCap.Round)
    val d = r * 0.42f
    drawLine(ink, Offset(center.x - d, center.y - d), Offset(center.x + d, center.y + d), strokeW * 0.7f, StrokeCap.Round)
    drawLine(ink, Offset(center.x + d, center.y - d), Offset(center.x - d, center.y + d), strokeW * 0.7f, StrokeCap.Round)
}

private fun DrawScope.drawHeartOutline(center: Offset, size: Float, ink: Color, strokeW: Float) {
    val s = size * 0.5f
    val path = Path().apply {
        moveTo(center.x, center.y + s * 0.85f)
        cubicTo(
            center.x - s * 1.25f, center.y + s * 0.1f,
            center.x - s * 1.15f, center.y - s * 0.95f,
            center.x, center.y - s * 0.25f
        )
        cubicTo(
            center.x + s * 1.15f, center.y - s * 0.95f,
            center.x + s * 1.25f, center.y + s * 0.1f,
            center.x, center.y + s * 0.85f
        )
        close()
    }
    drawPath(path, color = ink, style = Stroke(width = strokeW, join = StrokeJoin.Round))
}

private fun DrawScope.drawTeardrop(center: Offset, dropW: Float, ink: Color, strokeW: Float, alpha: Float) {
    val r = dropW * 0.5f
    val tear = Path().apply {
        moveTo(center.x, center.y - r * 1.55f)
        quadraticTo(center.x + r * 1.25f, center.y + r * 0.55f, center.x, center.y + r * 1.15f)
        quadraticTo(center.x - r * 1.25f, center.y + r * 0.55f, center.x, center.y - r * 1.55f)
        close()
    }
    drawPath(tear, color = ink.copy(alpha = alpha), style = Stroke(width = strokeW, join = StrokeJoin.Round))
}

private fun DrawScope.drawAngerMark(center: Offset, r: Float, ink: Color, strokeW: Float) {
    val gap = r * 0.25f
    // Top-left L
    drawLine(ink, Offset(center.x - r, center.y - gap), Offset(center.x - gap, center.y - gap), strokeW, StrokeCap.Round)
    drawLine(ink, Offset(center.x - gap, center.y - r), Offset(center.x - gap, center.y - gap), strokeW, StrokeCap.Round)
    // Bottom-right L
    drawLine(ink, Offset(center.x + r, center.y + gap), Offset(center.x + gap, center.y + gap), strokeW, StrokeCap.Round)
    drawLine(ink, Offset(center.x + gap, center.y + r), Offset(center.x + gap, center.y + gap), strokeW, StrokeCap.Round)
    // Top-right L
    drawLine(ink, Offset(center.x + r, center.y - gap), Offset(center.x + gap, center.y - gap), strokeW, StrokeCap.Round)
    drawLine(ink, Offset(center.x + gap, center.y - r), Offset(center.x + gap, center.y - gap), strokeW, StrokeCap.Round)
    // Bottom-left L
    drawLine(ink, Offset(center.x - r, center.y + gap), Offset(center.x - gap, center.y + gap), strokeW, StrokeCap.Round)
    drawLine(ink, Offset(center.x - gap, center.y + r), Offset(center.x - gap, center.y + gap), strokeW, StrokeCap.Round)
}

private fun DrawScope.drawFallbackHeadOutline(
    imgLeft: Float,
    imgTop: Float,
    imgW: Float,
    imgH: Float,
    ink: Color
) {
    val stroke = Stroke(width = (imgW * 0.042f).coerceAtLeast(1.3f), cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun pt(nx: Float, ny: Float) = Offset(imgLeft + nx * imgW, imgTop + ny * imgH)
    val head = Path().apply {
        moveTo(pt(0.28f, 0.25f).x, pt(0.28f, 0.25f).y)
        cubicTo(pt(0.38f, 0.06f).x, pt(0.38f, 0.06f).y, pt(0.64f, 0.06f).x, pt(0.64f, 0.06f).y, pt(0.74f, 0.25f).x, pt(0.74f, 0.25f).y)
        cubicTo(pt(0.84f, 0.50f).x, pt(0.84f, 0.50f).y, pt(0.80f, 0.92f).x, pt(0.80f, 0.92f).y, pt(0.51f, 0.93f).x, pt(0.51f, 0.93f).y)
        cubicTo(pt(0.22f, 0.92f).x, pt(0.22f, 0.92f).y, pt(0.18f, 0.50f).x, pt(0.18f, 0.50f).y, pt(0.28f, 0.25f).x, pt(0.28f, 0.25f).y)
        close()
    }
    drawPath(head, color = ink, style = stroke)
}

private fun DrawScope.drawInProgressStamp(ink: Color) {
    val w = size.width
    val center = Offset(w * 0.5f, w * 0.5f)
    val radius = w * 0.34f
    val strokeW = (w * 0.065f).coerceAtLeast(1.3f)

    drawCircle(
        color = ink.copy(alpha = 0.72f),
        radius = radius,
        center = center,
        style = Stroke(
            width = strokeW,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.13f, w * 0.09f), 0f)
        )
    )
    drawLine(
        color = ink,
        start = center,
        end = Offset(center.x, center.y - radius * 0.48f),
        strokeWidth = strokeW * 0.9f,
        cap = StrokeCap.Round
    )
    drawLine(
        color = ink,
        start = center,
        end = Offset(center.x + radius * 0.38f, center.y + radius * 0.14f),
        strokeWidth = strokeW * 0.9f,
        cap = StrokeCap.Round
    )
}
