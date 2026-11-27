package com.example.itv.presentation.mailbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.itv.presentation.components.ItvInkBorder
import com.example.itv.presentation.components.ItvInkShadow
import com.example.itv.presentation.components.LocalDarkTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Precomputed unit-circle scalloped wax seal vertex tables (zero sin/cos during draw frames)
private const val BACK_WAX_STEPS = 40
private val BACK_WAX_UNIT_X = FloatArray(BACK_WAX_STEPS) { i ->
    val angle = (i.toDouble() / BACK_WAX_STEPS) * 2.0 * PI
    val wobble = 1f + 0.06f * sin(angle * 10.0).toFloat()
    cos(angle).toFloat() * wobble
}
private val BACK_WAX_UNIT_Y = FloatArray(BACK_WAX_STEPS) { i ->
    val angle = (i.toDouble() / BACK_WAX_STEPS) * 2.0 * PI
    val wobble = 1f + 0.06f * sin(angle * 10.0).toFloat()
    sin(angle).toFloat() * wobble
}

private const val FRONT_WAX_STEPS = 36
private val FRONT_WAX_UNIT_X = FloatArray(FRONT_WAX_STEPS) { i ->
    val angle = (2.0 * PI * i) / FRONT_WAX_STEPS
    val wobble = 1.0f + 0.055f * sin(angle * 7.0).toFloat() + 0.03f * cos(angle * 11.0).toFloat()
    cos(angle).toFloat() * wobble
}
private val FRONT_WAX_UNIT_Y = FloatArray(FRONT_WAX_STEPS) { i ->
    val angle = (2.0 * PI * i) / FRONT_WAX_STEPS
    val wobble = 1.0f + 0.055f * sin(angle * 7.0).toFloat() + 0.03f * cos(angle * 11.0).toFloat()
    sin(angle).toFloat() * wobble
}

private val TOE_OFFSETS_X = floatArrayOf(-0.38f, -0.14f, 0.14f, 0.38f)
private val TOE_OFFSETS_Y = floatArrayOf(-0.08f, -0.29f, -0.29f, -0.08f)
private val TOE_DEGREES = floatArrayOf(-26f, -8f, 8f, 26f)
private val TOE_SCALES = floatArrayOf(0.88f, 1.0f, 1.0f, 0.88f)
private val LETTER_LINE_Y_FRACS = floatArrayOf(0.40f, 0.54f, 0.68f)
private val LETTER_LINE_END_FRACS = floatArrayOf(0.85f, 0.73f, 0.60f)
private val UNFOLD_CARD_ROW_FRACS = floatArrayOf(0.14f, 0.36f, 0.60f, 0.80f)

private fun populateScallopedPath(
    path: Path,
    unitX: FloatArray,
    unitY: FloatArray,
    cx: Float,
    cy: Float,
    radius: Float,
) {
    path.rewind()
    for (i in unitX.indices) {
        val px = cx + unitX[i] * radius
        val py = cy + unitY[i] * radius
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
}

private fun populatePalmPath(path: Path, cx: Float, cy: Float, radius: Float) {
    val palmCy = cy + radius * 0.18f
    val pw = radius * 0.34f
    val ph = radius * 0.25f
    path.rewind()
    path.moveTo(cx, palmCy - ph * 0.95f)
    path.cubicTo(
        cx + pw * 0.65f, palmCy - ph * 0.95f,
        cx + pw * 1.15f, palmCy - ph * 0.15f,
        cx + pw * 0.92f, palmCy + ph * 0.62f,
    )
    path.cubicTo(
        cx + pw * 0.72f, palmCy + ph * 1.05f,
        cx + pw * 0.22f, palmCy + ph * 0.82f,
        cx, palmCy + ph * 0.68f,
    )
    path.cubicTo(
        cx - pw * 0.22f, palmCy + ph * 0.82f,
        cx - pw * 0.72f, palmCy + ph * 1.05f,
        cx - pw * 0.92f, palmCy + ph * 0.62f,
    )
    path.cubicTo(
        cx - pw * 1.15f, palmCy - ph * 0.15f,
        cx - pw * 0.65f, palmCy - ph * 0.95f,
        cx, palmCy - ph * 0.95f,
    )
    path.close()
}

/**
 * Classic paper letter envelope with a centered 3D dog-paw wax seal that supports smooth
 * flap-opening + letter-pull-out animation AND persistent open state for already-read letters.
 *
 * Performance: uses an isolated hardware RenderNode ([graphicsLayer]) + [drawWithCache] with
 * pre-allocated reusable [Path]s (`rewind()`), cached [Stroke]s, and precomputed wax-seal trig
 * tables for zero per-frame heap allocations during scrolling or animation.
 */
@Composable
internal fun PawSealedEnvelopeArt(
    isRead: Boolean,
    modifier: Modifier = Modifier,
    openProgress: Float = if (isRead) 1f else 0f,
    letterSlideProgress: Float = if (isRead) 0.30f else 0f,
    openProgressProvider: (() -> Float)? = null,
    letterSlideProgressProvider: (() -> Float)? = null,
) {
    val isDark = LocalDarkTheme.current
    val inkColor = ItvInkBorder
    val shadowColor = ItvInkShadow

    val paperBase = when {
        isDark && isRead -> Color(0xFF233028)
        isDark -> Color(0xFF2A3830)
        isRead -> Color(0xFFF3ECE0)
        else -> Color(0xFFFFFBF2)
    }
    val paperInterior = if (isDark) Color(0xFF19231D) else Color(0xFFE7DAC6)
    val paperLowerFold = when {
        isDark && isRead -> Color(0xFF1D2821)
        isDark -> Color(0xFF233028)
        isRead -> Color(0xFFE9DFCE)
        else -> Color(0xFFF5EBDC)
    }
    val flapFrontFill = when {
        isDark && isRead -> Color(0xFF28372E)
        isDark -> Color(0xFF314238)
        isRead -> Color(0xFFF7F0E4)
        else -> Color(0xFFFFFDF8)
    }
    val flapInsideFill = if (isDark) Color(0xFF212E26) else Color(0xFFEEE3D1)
    val letterSheetFill = if (isDark) Color(0xFF1B2921) else Color(0xFFFFFEF9)
    val letterLineColor = if (isDark) Color(0xFF476452) else Color(0xFFCBBBA4)
    val flapShadowColor = if (isDark) Color(0x44000000) else Color(0x1C3A2E25)

    val waxOuter = if (isDark) Color(0xFFD3564C) else Color(0xFFC8433A)
    val waxInner = if (isDark) Color(0xFFB54138) else Color(0xFFAC332B)
    val pawEmboss = Color(0xFFFFEFE6)

    Box(
        modifier = modifier
            .aspectRatio(1.0f)
            .semantics {
                contentDescription = if (isRead) "열려 있는 확인 완료 편지 봉투" else "강아지 발자국 실링이 찍힌 새 편지 봉투"
            }
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val envLeft = w * 0.08f
                val envW = w * 0.82f
                val envH = envW * 0.61f
                val envBottom = h * 0.94f
                val envTop = envBottom - envH
                val envRight = envLeft + envW
                val centerX = envLeft + envW * 0.5f
                val cornerR = envH * 0.12f
                val corner = CornerRadius(cornerR, cornerR)
                val sheetCorner = CornerRadius(envH * 0.085f, envH * 0.085f)
                val strokeW = (w * 0.021f).coerceIn(1.5f, 3.6f)
                val pocketMidY = envTop + envH * 0.50f
                val sheetW = envW * 0.80f
                val sheetH = envH * 0.72f
                val sheetLeft = centerX - sheetW * 0.5f

                // Static pocket & rim paths cached once per layout size
                val leftSidePocket = Path().apply {
                    moveTo(envLeft, envTop + envH * 0.05f)
                    lineTo(centerX, pocketMidY)
                    lineTo(envLeft, envBottom - envH * 0.05f)
                    close()
                }
                val rightSidePocket = Path().apply {
                    moveTo(envRight, envTop + envH * 0.05f)
                    lineTo(centerX, pocketMidY)
                    lineTo(envRight, envBottom - envH * 0.05f)
                    close()
                }
                val bottomPocket = Path().apply {
                    moveTo(envLeft + envH * 0.05f, envBottom - strokeW * 0.3f)
                    lineTo(centerX, pocketMidY - envH * 0.05f)
                    lineTo(envRight - envH * 0.05f, envBottom - strokeW * 0.3f)
                    close()
                }
                val uBorderPath = Path().apply {
                    moveTo(envLeft, envTop + envH * 0.045f)
                    lineTo(envLeft, envBottom - cornerR)
                    quadraticTo(envLeft, envBottom, envLeft + cornerR, envBottom)
                    lineTo(envRight - cornerR, envBottom)
                    quadraticTo(envRight, envBottom, envRight, envBottom - cornerR)
                    lineTo(envRight, envTop + envH * 0.045f)
                }
                val topRimPath = Path().apply {
                    moveTo(envLeft, envTop + envH * 0.055f)
                    quadraticTo(envLeft, envTop, envLeft + cornerR, envTop)
                    lineTo(envRight - cornerR, envTop)
                    quadraticTo(envRight, envTop, envRight, envTop + envH * 0.055f)
                }

                // Reusable dynamic paths (zero per-frame allocations)
                val backWaxPath = Path()
                val openFlapPath = Path()
                val flapShadowPath = Path()
                val flapPath = Path()
                val waxBlobPath = Path()
                val palmPath = Path()

                // Cached strokes
                val strokeMain = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val strokeSheet = Stroke(width = strokeW * 0.85f)
                val strokeFlapOpen = Stroke(width = strokeW * 0.90f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val strokeFlapClosed = Stroke(width = strokeW * 0.88f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val strokeSealRim = Stroke(width = strokeW * 0.65f, join = StrokeJoin.Round)
                val strokeSealInner = Stroke(width = strokeW * 0.65f * 0.7f)

                val creaseColor = inkColor.copy(alpha = if (isDark) 0.55f else 0.30f)
                val vCutColor = inkColor.copy(alpha = 0.62f)
                val rimOutlineColor = inkColor.copy(alpha = if (isDark) 0.55f else 0.42f)
                val envShadowColor = shadowColor.copy(alpha = if (isDark) 0.55f else 0.26f)
                val flapSpan = envH * 0.52f
                val sealRadius = envH * 0.255f

                onDrawBehind {
                    val clampedOpen = (openProgressProvider?.invoke() ?: openProgress).coerceIn(0f, 1f)
                    val clampedSlide = (letterSlideProgressProvider?.invoke() ?: letterSlideProgress).coerceIn(0f, 1f)
                    val cosA = cos(clampedOpen * PI).toFloat()
                    val flapTipY = envTop + cosA * flapSpan

                    fun drawLetterSheet(broughtForward: Boolean) {
                        val pullPhase = (clampedSlide / 0.65f).coerceIn(0f, 1f)
                        val forwardRaw = ((clampedSlide - 0.65f) / 0.35f).coerceIn(0f, 1f)
                        val forwardSmooth = forwardRaw * forwardRaw * (3f - 2f * forwardRaw)

                        val sheetTop = (envTop + envH * 0.14f) - pullPhase * (envH * 0.74f) + forwardSmooth * (envH * 0.24f)
                        val liftScale = 1f + forwardSmooth * 0.12f
                        val sheetCenter = Offset(centerX, sheetTop + sheetH * 0.5f)

                        fun drawBody() {
                            scale(scaleX = liftScale, scaleY = liftScale, pivot = sheetCenter) {
                                val shadowOff = strokeW * (0.55f + forwardSmooth * 1.15f)
                                drawRoundRect(
                                    color = Color(0x28000000).copy(alpha = 0.16f + forwardSmooth * 0.16f),
                                    topLeft = Offset(sheetLeft + shadowOff, sheetTop + shadowOff * 1.15f),
                                    size = Size(sheetW, sheetH),
                                    cornerRadius = sheetCorner,
                                )
                                drawRoundRect(
                                    color = letterSheetFill,
                                    topLeft = Offset(sheetLeft, sheetTop),
                                    size = Size(sheetW, sheetH),
                                    cornerRadius = sheetCorner,
                                )
                                drawRoundRect(
                                    color = inkColor.copy(alpha = 0.82f),
                                    topLeft = Offset(sheetLeft, sheetTop),
                                    size = Size(sheetW, sheetH),
                                    cornerRadius = sheetCorner,
                                    style = strokeSheet,
                                )
                                drawMiniWoodPaw(
                                    center = Offset(centerX, sheetTop + sheetH * 0.20f),
                                    radius = envH * 0.082f,
                                    color = waxOuter.copy(alpha = 0.88f),
                                )
                                val linePad = sheetW * 0.15f
                                for (idx in LETTER_LINE_Y_FRACS.indices) {
                                    val y = sheetTop + sheetH * LETTER_LINE_Y_FRACS[idx]
                                    drawLine(
                                        color = letterLineColor,
                                        start = Offset(sheetLeft + linePad, y),
                                        end = Offset(sheetLeft + sheetW * LETTER_LINE_END_FRACS[idx], y),
                                        strokeWidth = strokeW * 0.65f,
                                        cap = StrokeCap.Round,
                                    )
                                }
                            }
                        }

                        if (broughtForward) {
                            drawBody()
                        } else {
                            clipRect(left = 0f, top = 0f, right = w, bottom = envBottom - strokeW * 0.5f) {
                                drawBody()
                            }
                        }
                    }

                    fun drawFrontPocket() {
                        drawPath(path = leftSidePocket, color = paperBase)
                        drawPath(path = rightSidePocket, color = paperBase)
                        drawPath(path = bottomPocket, color = paperLowerFold)

                        drawLine(
                            color = creaseColor,
                            start = Offset(envLeft + envH * 0.06f, envBottom - envH * 0.04f),
                            end = Offset(centerX, pocketMidY - envH * 0.04f),
                            strokeWidth = strokeW * 0.72f,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = creaseColor,
                            start = Offset(envRight - envH * 0.06f, envBottom - envH * 0.04f),
                            end = Offset(centerX, pocketMidY - envH * 0.04f),
                            strokeWidth = strokeW * 0.72f,
                            cap = StrokeCap.Round,
                        )
                        if (clampedOpen > 0.08f) {
                            drawLine(
                                color = vCutColor,
                                start = Offset(envLeft + strokeW * 0.3f, envTop + envH * 0.05f),
                                end = Offset(centerX, pocketMidY),
                                strokeWidth = strokeW * 0.78f,
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                color = vCutColor,
                                start = Offset(envRight - strokeW * 0.3f, envTop + envH * 0.05f),
                                end = Offset(centerX, pocketMidY),
                                strokeWidth = strokeW * 0.78f,
                                cap = StrokeCap.Round,
                            )
                        }
                        drawPath(path = uBorderPath, color = inkColor, style = strokeMain)
                    }

                    // 1. Offset shadow
                    drawRoundRect(
                        color = envShadowColor,
                        topLeft = Offset(envLeft + strokeW * 1.15f, envTop + strokeW * 1.4f),
                        size = Size(envW, envH),
                        cornerRadius = corner,
                    )

                    // 2. Opened flap + back-side crimson wax seal behind letter
                    if (clampedOpen >= 0.5f) {
                        val openFraction = (-cosA).coerceIn(0f, 1f)
                        val backSealCenter = Offset(centerX, flapTipY + envH * 0.01f * openFraction)
                        val backScaleY = openFraction.coerceIn(0.22f, 1f)
                        populateScallopedPath(backWaxPath, BACK_WAX_UNIT_X, BACK_WAX_UNIT_Y, backSealCenter.x, backSealCenter.y, sealRadius)
                        scale(scaleX = 1f, scaleY = backScaleY, pivot = backSealCenter) {
                            drawPath(path = backWaxPath, color = waxOuter)
                            drawPath(path = backWaxPath, color = rimOutlineColor, style = strokeSealRim)
                        }

                        openFlapPath.rewind()
                        openFlapPath.moveTo(envLeft + strokeW * 0.2f, envTop + envH * 0.045f)
                        openFlapPath.lineTo(centerX, flapTipY)
                        openFlapPath.lineTo(envRight - strokeW * 0.2f, envTop + envH * 0.045f)
                        openFlapPath.close()
                        drawPath(path = openFlapPath, color = flapInsideFill)
                        drawPath(path = openFlapPath, color = inkColor.copy(alpha = 0.88f), style = strokeFlapOpen)
                    }

                    // 3. Back interior + top rim behind letter
                    drawRoundRect(
                        color = if (clampedOpen > 0.05f) paperInterior else paperBase,
                        topLeft = Offset(envLeft, envTop),
                        size = Size(envW, envH),
                        cornerRadius = corner,
                    )
                    drawPath(
                        path = topRimPath,
                        color = inkColor.copy(alpha = if (clampedOpen >= 0.5f) 0.45f else 1f),
                        style = strokeMain,
                    )

                    // 4 & 5. Letter sheet vs front pocket layering
                    val showLetter = clampedOpen >= 0.42f || clampedSlide > 0.01f
                    val letterClearedPocket = clampedSlide >= 0.65f
                    if (showLetter && !letterClearedPocket) {
                        drawLetterSheet(false)
                        drawFrontPocket()
                    } else {
                        drawFrontPocket()
                        if (showLetter) drawLetterSheet(true)
                    }

                    // 6. Closed / lifting front flap + front crimson wax seal
                    if (clampedOpen < 0.5f) {
                        val shadowAlpha = cosA.coerceIn(0f, 1f)
                        flapShadowPath.rewind()
                        flapShadowPath.moveTo(envLeft + envH * 0.04f, envTop + envH * 0.06f)
                        flapShadowPath.lineTo(centerX, flapTipY + envH * 0.06f * shadowAlpha)
                        flapShadowPath.lineTo(envRight - envH * 0.04f, envTop + envH * 0.06f)
                        flapShadowPath.close()
                        drawPath(path = flapShadowPath, color = flapShadowColor.copy(alpha = flapShadowColor.alpha * shadowAlpha))

                        flapPath.rewind()
                        flapPath.moveTo(envLeft + cornerR * 0.35f, envTop + strokeW * 0.15f)
                        flapPath.lineTo(centerX, flapTipY)
                        flapPath.lineTo(envRight - cornerR * 0.35f, envTop + strokeW * 0.15f)
                        flapPath.close()
                        drawPath(path = flapPath, color = flapFrontFill)
                        drawPath(path = flapPath, color = inkColor.copy(alpha = 0.88f), style = strokeFlapClosed)

                        val sealCenter = Offset(centerX, flapTipY - envH * 0.01f)
                        val verticalSquash = cosA.coerceIn(0.18f, 1f)
                        scale(scaleX = 1f, scaleY = verticalSquash, pivot = sealCenter) {
                            drawDogPawWaxSealFast(
                                waxBlobPath = waxBlobPath,
                                palmPath = palmPath,
                                center = sealCenter,
                                radius = sealRadius,
                                waxOuter = waxOuter,
                                waxInner = waxInner,
                                pawColor = pawEmboss,
                                rimOutline = rimOutlineColor,
                                rimStroke = strokeSealRim,
                                innerRimStroke = strokeSealInner,
                            )
                        }
                    }
                }
            }
    )
}

/**
 * Continuous full-stage GPU-friendly animation for opening an unread weekly letter with ZERO blank gap:
 * 1) [openProgressProvider] (`0f..1f`): Crimson dog-paw wax seal & top flap lift open.
 * 2) [letterSlideProgressProvider] (`0f..1f`): Folded cream letter slides UP & forward in front.
 * 3) [unfoldProgressProvider] (`0f..1f`): Envelope recedes while the letter (folded vertically in
 *    half TWICE: 1/4 -> 1/2 -> full 1/1 height) unfolds into the exact stationery sheet bounds.
 *
 * Performance: zero `Path()`, `Stroke()`, `List`, or `sin/cos` table allocations per animation frame.
 */
@Composable
internal fun EnvelopeUnsealAndUnfoldCanvas(
    openProgressProvider: () -> Float,
    letterSlideProgressProvider: () -> Float,
    unfoldProgressProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalDarkTheme.current
    val inkColor = ItvInkBorder
    val shadowColor = ItvInkShadow

    val paperBase = if (isDark) Color(0xFF2A3830) else Color(0xFFFFFBF2)
    val paperInterior = if (isDark) Color(0xFF19231D) else Color(0xFFE7DAC6)
    val paperLowerFold = if (isDark) Color(0xFF233028) else Color(0xFFF5EBDC)
    val flapFrontFill = if (isDark) Color(0xFF314238) else Color(0xFFFFFDF8)
    val flapInsideFill = if (isDark) Color(0xFF212E26) else Color(0xFFEEE3D1)

    val stationeryFill = if (isDark) Color(0xFF17221C) else Color(0xFFFFFCF5)
    val foldedBackFill = if (isDark) Color(0xFF213028) else Color(0xFFF3EAD9)
    val letterLineColor = if (isDark) Color(0xFF476452) else Color(0xFFCBBBA4)
    val cardGhostFill = if (isDark) Color(0xFF1E2E25) else Color(0xFFF3F8F4)
    val creaseLineColor = if (isDark) Color(0x55000000) else Color(0x283A2E25)
    val flapShadowColor = if (isDark) Color(0x44000000) else Color(0x1C3A2E25)

    val waxOuter = if (isDark) Color(0xFFD3564C) else Color(0xFFC8433A)
    val waxInner = if (isDark) Color(0xFFB54138) else Color(0xFFAC332B)
    val pawEmboss = Color(0xFFFFEFE6)

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "강아지 발자국 실링 편지를 열어 두 번 접힌 편지지를 펼치는 중"
            }
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val centerX = w * 0.5f

                val envW = (w * 0.78f).coerceAtMost(h * 0.62f)
                val envH = envW * 0.61f
                val envLeft = centerX - envW * 0.5f
                val envRight = envLeft + envW
                val envTopBase = (h * 0.54f) - envH * 0.42f
                val envBottomBase = envTopBase + envH
                val cornerR = envH * 0.12f
                val corner = CornerRadius(cornerR, cornerR)
                val strokeW = (envW * 0.024f).coerceIn(1.8f, 3.4f)
                val flapSpan = envH * 0.52f
                val sealRadius = envH * 0.255f
                val startSheetW = envW * 0.80f
                val startSheetH = envH * 0.68f
                val sheetCornerStartPx = envH * 0.085f
                val sheetCornerStart = CornerRadius(sheetCornerStartPx, sheetCornerStartPx)
                val targetCornerPx = 20.dp.toPx()
                val borderW = 1.6.dp.toPx().coerceIn(1.5f, 3.2f)
                val dp4 = 4.dp.toPx()
                val dp5 = 5.dp.toPx()
                val dp10Corner = CornerRadius(10.dp.toPx(), 10.dp.toPx())
                val dp12Corner = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                val maxEmblemRadius = 24.dp.toPx()

                // Reusable Path pool (rewind() each frame -> 0 heap allocations)
                val leftSidePocket = Path()
                val rightSidePocket = Path()
                val bottomPocket = Path()
                val uBorderPath = Path()
                val backWaxPath = Path()
                val openFlapPath = Path()
                val topRimPath = Path()
                val flapShadowPath = Path()
                val flapPath = Path()
                val waxBlobPath = Path()
                val palmPath = Path()
                val panelPath = Path()

                // Cached Stroke instances
                val strokeMain = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val strokeSheet = Stroke(width = strokeW * 0.82f)
                val strokeFlapOpen = Stroke(width = strokeW * 0.90f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val strokeFlapClosed = Stroke(width = strokeW * 0.88f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val strokeSealRim = Stroke(width = strokeW * 0.65f, join = StrokeJoin.Round)
                val strokeSealInner = Stroke(width = strokeW * 0.65f * 0.7f)
                val strokeEmblemRim = Stroke(width = strokeW * 0.45f, join = StrokeJoin.Round)
                val strokeEmblemInner = Stroke(width = strokeW * 0.45f * 0.7f)
                val strokePanel = Stroke(width = borderW * 0.9f, join = StrokeJoin.Round)
                val strokePanelRect = Stroke(width = borderW * 0.85f)
                val strokeFinalFrame = Stroke(width = borderW)

                fun smooth(x: Float): Float {
                    val c = x.coerceIn(0f, 1f)
                    return c * c * (3f - 2f * c)
                }

                onDrawBehind {
                    val clampedOpen = openProgressProvider().coerceIn(0f, 1f)
                    val clampedSlide = letterSlideProgressProvider().coerceIn(0f, 1f)
                    val clampedUnfold = unfoldProgressProvider().coerceIn(0f, 1f)

                    val envFadeT = (clampedUnfold / 0.26f).coerceIn(0f, 1f)
                    val envAlpha = 1f - envFadeT
                    val envDropY = envFadeT * envH * 0.30f
                    val cosA = cos(clampedOpen * PI).toFloat()

                    val pullPhase = (clampedSlide / 0.65f).coerceIn(0f, 1f)
                    val forwardRaw = ((clampedSlide - 0.65f) / 0.35f).coerceIn(0f, 1f)
                    val forwardSmooth = forwardRaw * forwardRaw * (3f - 2f * forwardRaw)

                    val pulledSheetTop = (envTopBase + envH * 0.14f) - pullPhase * (envH * 0.74f) + forwardSmooth * (envH * 0.26f)
                    val pulledScale = 1f + forwardSmooth * 0.12f
                    val pulledCenterY = pulledSheetTop + startSheetH * 0.5f
                    val pulledActualW = startSheetW * pulledScale
                    val pulledActualH = startSheetH * pulledScale

                    fun drawPulledFoldedStrip(broughtForward: Boolean) {
                        val sheetLeft = centerX - startSheetW * 0.5f
                        val sheetCenter = Offset(centerX, pulledCenterY)

                        fun drawBody() {
                            scale(scaleX = pulledScale, scaleY = pulledScale, pivot = sheetCenter) {
                                val shadowOff = strokeW * (0.55f + forwardSmooth * 1.15f)
                                drawRoundRect(
                                    color = Color(0x28000000).copy(alpha = 0.16f + forwardSmooth * 0.16f),
                                    topLeft = Offset(sheetLeft + shadowOff, pulledSheetTop + shadowOff * 1.15f),
                                    size = Size(startSheetW, startSheetH),
                                    cornerRadius = sheetCornerStart,
                                )
                                drawRoundRect(
                                    color = foldedBackFill,
                                    topLeft = Offset(sheetLeft + strokeW * 0.5f, pulledSheetTop + strokeW * 1.1f),
                                    size = Size(startSheetW - strokeW, startSheetH),
                                    cornerRadius = sheetCornerStart,
                                )
                                drawRoundRect(
                                    color = stationeryFill,
                                    topLeft = Offset(sheetLeft, pulledSheetTop),
                                    size = Size(startSheetW, startSheetH),
                                    cornerRadius = sheetCornerStart,
                                )
                                drawRoundRect(
                                    color = inkColor.copy(alpha = 0.80f),
                                    topLeft = Offset(sheetLeft, pulledSheetTop),
                                    size = Size(startSheetW, startSheetH),
                                    cornerRadius = sheetCornerStart,
                                    style = strokeSheet,
                                )
                                drawLine(
                                    color = creaseLineColor,
                                    start = Offset(sheetLeft + startSheetW * 0.08f, pulledSheetTop + startSheetH * 0.16f),
                                    end = Offset(sheetLeft + startSheetW * 0.92f, pulledSheetTop + startSheetH * 0.16f),
                                    strokeWidth = strokeW * 0.45f,
                                    cap = StrokeCap.Round,
                                )
                                drawMiniWoodPaw(
                                    center = Offset(centerX, pulledSheetTop + startSheetH * 0.42f),
                                    radius = envH * 0.078f,
                                    color = waxOuter.copy(alpha = 0.85f),
                                )
                                drawLine(
                                    color = letterLineColor,
                                    start = Offset(sheetLeft + startSheetW * 0.22f, pulledSheetTop + startSheetH * 0.68f),
                                    end = Offset(sheetLeft + startSheetW * 0.78f, pulledSheetTop + startSheetH * 0.68f),
                                    strokeWidth = strokeW * 0.6f,
                                    cap = StrokeCap.Round,
                                )
                            }
                        }

                        if (broughtForward) {
                            drawBody()
                        } else {
                            clipRect(left = 0f, top = 0f, right = w, bottom = envBottomBase - strokeW * 0.5f) {
                                drawBody()
                            }
                        }
                    }

                    fun drawFrontPocket(envTop: Float, envBottom: Float) {
                        val pocketMidY = envTop + envH * 0.50f
                        leftSidePocket.rewind()
                        leftSidePocket.moveTo(envLeft, envTop + envH * 0.05f)
                        leftSidePocket.lineTo(centerX, pocketMidY)
                        leftSidePocket.lineTo(envLeft, envBottom - envH * 0.05f)
                        leftSidePocket.close()

                        rightSidePocket.rewind()
                        rightSidePocket.moveTo(envRight, envTop + envH * 0.05f)
                        rightSidePocket.lineTo(centerX, pocketMidY)
                        rightSidePocket.lineTo(envRight, envBottom - envH * 0.05f)
                        rightSidePocket.close()

                        bottomPocket.rewind()
                        bottomPocket.moveTo(envLeft + envH * 0.05f, envBottom - strokeW * 0.3f)
                        bottomPocket.lineTo(centerX, pocketMidY - envH * 0.05f)
                        bottomPocket.lineTo(envRight - envH * 0.05f, envBottom - strokeW * 0.3f)
                        bottomPocket.close()

                        drawPath(path = leftSidePocket, color = paperBase.copy(alpha = envAlpha))
                        drawPath(path = rightSidePocket, color = paperBase.copy(alpha = envAlpha))
                        drawPath(path = bottomPocket, color = paperLowerFold.copy(alpha = envAlpha))

                        val creaseColor = inkColor.copy(alpha = (if (isDark) 0.55f else 0.30f) * envAlpha)
                        drawLine(
                            color = creaseColor,
                            start = Offset(envLeft + envH * 0.06f, envBottom - envH * 0.04f),
                            end = Offset(centerX, pocketMidY - envH * 0.04f),
                            strokeWidth = strokeW * 0.72f,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = creaseColor,
                            start = Offset(envRight - envH * 0.06f, envBottom - envH * 0.04f),
                            end = Offset(centerX, pocketMidY - envH * 0.04f),
                            strokeWidth = strokeW * 0.72f,
                            cap = StrokeCap.Round,
                        )
                        if (clampedOpen > 0.08f) {
                            val vCol = inkColor.copy(alpha = 0.62f * envAlpha)
                            drawLine(
                                color = vCol,
                                start = Offset(envLeft + strokeW * 0.3f, envTop + envH * 0.05f),
                                end = Offset(centerX, pocketMidY),
                                strokeWidth = strokeW * 0.78f,
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                color = vCol,
                                start = Offset(envRight - strokeW * 0.3f, envTop + envH * 0.05f),
                                end = Offset(centerX, pocketMidY),
                                strokeWidth = strokeW * 0.78f,
                                cap = StrokeCap.Round,
                            )
                        }
                        uBorderPath.rewind()
                        uBorderPath.moveTo(envLeft, envTop + envH * 0.045f)
                        uBorderPath.lineTo(envLeft, envBottom - cornerR)
                        uBorderPath.quadraticTo(envLeft, envBottom, envLeft + cornerR, envBottom)
                        uBorderPath.lineTo(envRight - cornerR, envBottom)
                        uBorderPath.quadraticTo(envRight, envBottom, envRight, envBottom - cornerR)
                        uBorderPath.lineTo(envRight, envTop + envH * 0.045f)
                        drawPath(path = uBorderPath, color = inkColor.copy(alpha = envAlpha), style = strokeMain)
                    }

                    // --- ENVELOPE LAYERS ---
                    if (envAlpha > 0.01f) {
                        val envTop = envTopBase + envDropY
                        val envBottom = envBottomBase + envDropY
                        val flapTipY = envTop + cosA * flapSpan

                        drawRoundRect(
                            color = shadowColor.copy(alpha = (if (isDark) 0.55f else 0.26f) * envAlpha),
                            topLeft = Offset(envLeft + strokeW * 1.15f, envTop + strokeW * 1.4f),
                            size = Size(envW, envH),
                            cornerRadius = corner,
                        )

                        if (clampedOpen >= 0.5f) {
                            val openFraction = (-cosA).coerceIn(0f, 1f)
                            val backSealCenter = Offset(centerX, flapTipY + envH * 0.01f * openFraction)
                            val backScaleY = openFraction.coerceIn(0.22f, 1f)
                            populateScallopedPath(backWaxPath, BACK_WAX_UNIT_X, BACK_WAX_UNIT_Y, backSealCenter.x, backSealCenter.y, sealRadius)
                            scale(scaleX = 1f, scaleY = backScaleY, pivot = backSealCenter) {
                                drawPath(path = backWaxPath, color = waxOuter.copy(alpha = envAlpha))
                                drawPath(
                                    path = backWaxPath,
                                    color = inkColor.copy(alpha = (if (isDark) 0.55f else 0.42f) * envAlpha),
                                    style = strokeSealRim,
                                )
                            }

                            openFlapPath.rewind()
                            openFlapPath.moveTo(envLeft + strokeW * 0.2f, envTop + envH * 0.045f)
                            openFlapPath.lineTo(centerX, flapTipY)
                            openFlapPath.lineTo(envRight - strokeW * 0.2f, envTop + envH * 0.045f)
                            openFlapPath.close()
                            drawPath(path = openFlapPath, color = flapInsideFill.copy(alpha = envAlpha))
                            drawPath(path = openFlapPath, color = inkColor.copy(alpha = 0.88f * envAlpha), style = strokeFlapOpen)
                        }

                        drawRoundRect(
                            color = (if (clampedOpen > 0.05f) paperInterior else paperBase).copy(alpha = envAlpha),
                            topLeft = Offset(envLeft, envTop),
                            size = Size(envW, envH),
                            cornerRadius = corner,
                        )
                        topRimPath.rewind()
                        topRimPath.moveTo(envLeft, envTop + envH * 0.055f)
                        topRimPath.quadraticTo(envLeft, envTop, envLeft + cornerR, envTop)
                        topRimPath.lineTo(envRight - cornerR, envTop)
                        topRimPath.quadraticTo(envRight, envTop, envRight, envTop + envH * 0.055f)
                        drawPath(
                            path = topRimPath,
                            color = inkColor.copy(alpha = (if (clampedOpen >= 0.5f) 0.45f else 1f) * envAlpha),
                            style = strokeMain,
                        )

                        val showLetterInEnvelope = clampedUnfold <= 0.001f && (clampedOpen >= 0.42f || clampedSlide > 0.01f)
                        val letterClearedPocket = clampedSlide >= 0.65f
                        if (showLetterInEnvelope && !letterClearedPocket) {
                            drawPulledFoldedStrip(false)
                            drawFrontPocket(envTop, envBottom)
                        } else {
                            drawFrontPocket(envTop, envBottom)
                            if (showLetterInEnvelope) drawPulledFoldedStrip(true)
                        }

                        if (clampedOpen < 0.5f) {
                            val shadowAlpha = cosA.coerceIn(0f, 1f) * envAlpha
                            flapShadowPath.rewind()
                            flapShadowPath.moveTo(envLeft + envH * 0.04f, envTop + envH * 0.06f)
                            flapShadowPath.lineTo(centerX, flapTipY + envH * 0.06f * cosA.coerceIn(0f, 1f))
                            flapShadowPath.lineTo(envRight - envH * 0.04f, envTop + envH * 0.06f)
                            flapShadowPath.close()
                            drawPath(path = flapShadowPath, color = flapShadowColor.copy(alpha = flapShadowColor.alpha * shadowAlpha))

                            flapPath.rewind()
                            flapPath.moveTo(envLeft + cornerR * 0.35f, envTop + strokeW * 0.15f)
                            flapPath.lineTo(centerX, flapTipY)
                            flapPath.lineTo(envRight - cornerR * 0.35f, envTop + strokeW * 0.15f)
                            flapPath.close()
                            drawPath(path = flapPath, color = flapFrontFill.copy(alpha = envAlpha))
                            drawPath(path = flapPath, color = inkColor.copy(alpha = 0.88f * envAlpha), style = strokeFlapClosed)

                            val sealCenter = Offset(centerX, flapTipY - envH * 0.01f)
                            val verticalSquash = cosA.coerceIn(0.18f, 1f)
                            scale(scaleX = 1f, scaleY = verticalSquash, pivot = sealCenter) {
                                drawDogPawWaxSealFast(
                                    waxBlobPath = waxBlobPath,
                                    palmPath = palmPath,
                                    center = sealCenter,
                                    radius = sealRadius,
                                    waxOuter = waxOuter.copy(alpha = envAlpha),
                                    waxInner = waxInner.copy(alpha = envAlpha),
                                    pawColor = pawEmboss.copy(alpha = envAlpha),
                                    rimOutline = inkColor.copy(alpha = (if (isDark) 0.55f else 0.42f) * envAlpha),
                                    rimStroke = strokeSealRim,
                                    innerRimStroke = strokeSealInner,
                                )
                            }
                        }
                    }

                    // --- PHASE 3: VERTICAL DOUBLE-UNFOLD (1/4 -> 1/2 -> full 1/1) ---
                    if (clampedUnfold > 0.001f) {
                        val u1 = smooth(clampedUnfold / 0.48f)
                        val u2 = smooth((clampedUnfold - 0.48f) / 0.52f)
                        val grow = smooth(clampedUnfold)

                        val targetPanelH = h * 0.25f
                        val panelW = pulledActualW + (w - pulledActualW) * grow
                        val panelH = pulledActualH + (targetPanelH - pulledActualH) * grow
                        val left = centerX - panelW * 0.5f
                        val right = left + panelW

                        val startCenterCreaseY = pulledCenterY + pulledActualH * 0.5f
                        val centerCreaseY = startCenterCreaseY + (h * 0.50f - startCenterCreaseY) * grow
                        val topCreaseY = centerCreaseY - panelH
                        val sheetCornerPx = sheetCornerStartPx + (targetCornerPx - sheetCornerStartPx) * grow
                        val sheetCorner = CornerRadius(sheetCornerPx, sheetCornerPx)
                        val creaseFade = (1f - smooth((clampedUnfold - 0.85f) / 0.15f)).coerceIn(0f, 1f)

                        val cos1 = cos(u1 * PI).toFloat()
                        val cos2 = cos(u2 * PI).toFloat()

                        fun drawSwingingPanel(
                            hingeY: Float,
                            freeEdgeY: Float,
                            taperPx: Float,
                            fillColor: Color,
                            borderAlpha: Float = 0.72f,
                        ) {
                            panelPath.rewind()
                            panelPath.moveTo(left, hingeY)
                            panelPath.lineTo(right, hingeY)
                            panelPath.lineTo(right - taperPx, freeEdgeY)
                            panelPath.lineTo(left + taperPx, freeEdgeY)
                            panelPath.close()
                            drawPath(path = panelPath, color = fillColor)
                            drawPath(path = panelPath, color = inkColor.copy(alpha = borderAlpha), style = strokePanel)
                        }

                        // Soft drop shadow behind unfolding paper
                        val curTopY = if (cos1 < 0f) topCreaseY + cos1 * panelH else topCreaseY
                        val curBottomY = if (cos2 < 0f) centerCreaseY - cos2 * (panelH * 2f) else centerCreaseY
                        drawRoundRect(
                            color = Color(0x24000000).copy(alpha = 0.22f * (1f - grow * 0.6f)),
                            topLeft = Offset(left + dp4, curTopY + dp5),
                            size = Size(panelW, (curBottomY - curTopY).coerceAtLeast(panelH)),
                            cornerRadius = sheetCorner,
                        )

                        // 1) Base Panel 2
                        drawRect(color = stationeryFill, topLeft = Offset(left, topCreaseY), size = Size(panelW, panelH))
                        val p2ContentAlpha = smooth((u1 - 0.35f) / 0.45f)
                        if (p2ContentAlpha > 0.01f) {
                            val padX = panelW * 0.07f
                            val cardTop = topCreaseY + panelH * 0.18f
                            val cardH = panelH * 0.62f
                            drawRoundRect(
                                color = cardGhostFill.copy(alpha = p2ContentAlpha),
                                topLeft = Offset(left + padX, cardTop),
                                size = Size(panelW - padX * 2f, cardH),
                                cornerRadius = dp12Corner,
                            )
                            drawLine(
                                color = letterLineColor.copy(alpha = 0.85f * p2ContentAlpha),
                                start = Offset(left + padX * 1.6f, cardTop + cardH * 0.38f),
                                end = Offset(right - padX * 2.2f, cardTop + cardH * 0.38f),
                                strokeWidth = strokeW * 0.7f,
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                color = letterLineColor.copy(alpha = 0.6f * p2ContentAlpha),
                                start = Offset(left + padX * 1.6f, cardTop + cardH * 0.66f),
                                end = Offset(right - padX * 3.4f, cardTop + cardH * 0.66f),
                                strokeWidth = strokeW * 0.65f,
                                cap = StrokeCap.Round,
                            )
                        }
                        drawRect(
                            color = inkColor.copy(alpha = 0.68f),
                            topLeft = Offset(left, topCreaseY),
                            size = Size(panelW, panelH),
                            style = strokePanelRect,
                        )

                        // 2) Panel 1 unfolding UPWARD
                        if (cos1 >= 0f) {
                            val flapBottomY = topCreaseY + cos1 * panelH
                            val perspectiveTaper = sin(u1 * PI).toFloat() * panelW * 0.055f
                            if (flapBottomY < centerCreaseY) {
                                val sh = (centerCreaseY - flapBottomY).coerceAtMost(panelH * 0.25f)
                                drawRect(color = Color(0x1C000000), topLeft = Offset(left, flapBottomY), size = Size(panelW, sh * 0.55f))
                                drawRect(color = Color(0x0D000000), topLeft = Offset(left, flapBottomY + sh * 0.55f), size = Size(panelW, sh * 0.45f))
                            }
                            val shade = (1f - cos1) * 0.10f
                            val tintedBack = Color(
                                red = (foldedBackFill.red * (1f - shade)).coerceIn(0f, 1f),
                                green = (foldedBackFill.green * (1f - shade)).coerceIn(0f, 1f),
                                blue = (foldedBackFill.blue * (1f - shade)).coerceIn(0f, 1f),
                                alpha = 1f,
                            )
                            drawSwingingPanel(topCreaseY, flapBottomY, perspectiveTaper, tintedBack, 0.78f)
                        } else {
                            val openRatio = (-cos1).coerceIn(0f, 1f)
                            val openSpan = openRatio * panelH
                            val p1TopY = topCreaseY - openSpan
                            val perspectiveTaper = sin(u1 * PI).toFloat() * panelW * 0.045f
                            drawSwingingPanel(topCreaseY, p1TopY, perspectiveTaper, stationeryFill, 0.68f)
                            if (openRatio > 0.15f) {
                                val emblemCenter = Offset(centerX, topCreaseY - openSpan * 0.62f)
                                scale(scaleX = 1f, scaleY = openRatio, pivot = emblemCenter) {
                                    drawDogPawWaxSealFast(
                                        waxBlobPath = waxBlobPath,
                                        palmPath = palmPath,
                                        center = emblemCenter,
                                        radius = (panelH * 0.18f).coerceAtMost(maxEmblemRadius),
                                        waxOuter = waxOuter,
                                        waxInner = waxInner,
                                        pawColor = pawEmboss,
                                        rimOutline = inkColor.copy(alpha = 0.35f),
                                        rimStroke = strokeEmblemRim,
                                        innerRimStroke = strokeEmblemInner,
                                    )
                                }
                                val titleY = topCreaseY - openSpan * 0.22f
                                drawLine(
                                    color = letterLineColor.copy(alpha = openRatio),
                                    start = Offset(centerX - panelW * 0.24f, titleY),
                                    end = Offset(centerX + panelW * 0.24f, titleY),
                                    strokeWidth = strokeW * 0.85f,
                                    cap = StrokeCap.Round,
                                )
                            }
                            val creaseShadowH = panelH * 0.14f * openRatio * (0.35f + 0.65f * (1f - openRatio)) * creaseFade
                            if (creaseShadowH > 1f) {
                                drawRect(
                                    color = Color(0x12000000),
                                    topLeft = Offset(left, topCreaseY - creaseShadowH),
                                    size = Size(panelW, creaseShadowH),
                                )
                            }
                        }

                        // 3) Lower half (Panels 3 + 4) unfolding DOWNWARD
                        if (u2 > 0.001f) {
                            val lowerHalfH = panelH * 2f
                            if (cos2 >= 0f) {
                                val flapTopY = centerCreaseY - cos2 * lowerHalfH
                                val perspectiveTaper = sin(u2 * PI).toFloat() * panelW * 0.055f
                                val shade = (1f - cos2) * 0.12f
                                val tintedBack = Color(
                                    red = (foldedBackFill.red * (1f - shade)).coerceIn(0f, 1f),
                                    green = (foldedBackFill.green * (1f - shade)).coerceIn(0f, 1f),
                                    blue = (foldedBackFill.blue * (1f - shade)).coerceIn(0f, 1f),
                                    alpha = 1f,
                                )
                                drawSwingingPanel(centerCreaseY, flapTopY, perspectiveTaper, tintedBack, 0.78f)
                            } else {
                                val openRatio = (-cos2).coerceIn(0f, 1f)
                                val openSpan = openRatio * lowerHalfH
                                val p4BottomY = centerCreaseY + openSpan
                                val perspectiveTaper = sin(u2 * PI).toFloat() * panelW * 0.045f
                                drawSwingingPanel(centerCreaseY, p4BottomY, perspectiveTaper, stationeryFill, 0.68f)

                                if (openRatio > 0.2f) {
                                    val padX = panelW * 0.07f
                                    val cardAlpha = smooth((openRatio - 0.2f) / 0.6f)
                                    val ghostColor = cardGhostFill.copy(alpha = 0.85f * cardAlpha)
                                    val rowH = (openSpan * 0.15f).coerceAtLeast(4f)
                                    val rowW = panelW - padX * 2f
                                    for (i in UNFOLD_CARD_ROW_FRACS.indices) {
                                        drawRoundRect(
                                            color = ghostColor,
                                            topLeft = Offset(left + padX, centerCreaseY + openSpan * UNFOLD_CARD_ROW_FRACS[i]),
                                            size = Size(rowW, rowH),
                                            cornerRadius = dp10Corner,
                                        )
                                    }
                                }
                                if (creaseFade > 0.02f) {
                                    val thirdCreaseY = centerCreaseY + openSpan * 0.5f
                                    drawLine(
                                        color = creaseLineColor.copy(alpha = creaseLineColor.alpha * openRatio * creaseFade),
                                        start = Offset(left + 4f, thirdCreaseY),
                                        end = Offset(right - 4f, thirdCreaseY),
                                        strokeWidth = strokeW * 0.55f,
                                    )
                                }
                                val centerShadowH = openSpan * 0.12f * (0.35f + 0.65f * (1f - openRatio)) * creaseFade
                                if (centerShadowH > 1f) {
                                    drawRect(
                                        color = Color(0x14000000),
                                        topLeft = Offset(left, centerCreaseY),
                                        size = Size(panelW, centerShadowH),
                                    )
                                }
                            }
                        }

                        if (creaseFade > 0.02f && u1 > 0.5f) {
                            drawLine(
                                color = creaseLineColor.copy(alpha = creaseLineColor.alpha * creaseFade),
                                start = Offset(left + 4f, topCreaseY),
                                end = Offset(right - 4f, topCreaseY),
                                strokeWidth = strokeW * 0.6f,
                            )
                        }
                        if (creaseFade > 0.02f && u2 > 0.5f) {
                            drawLine(
                                color = creaseLineColor.copy(alpha = creaseLineColor.alpha * creaseFade),
                                start = Offset(left + 4f, centerCreaseY),
                                end = Offset(right - 4f, centerCreaseY),
                                strokeWidth = strokeW * 0.65f,
                            )
                        }

                        val finalFrameAlpha = smooth((clampedUnfold - 0.84f) / 0.16f)
                        if (finalFrameAlpha > 0.01f) {
                            drawRoundRect(
                                color = stationeryFill.copy(alpha = finalFrameAlpha * 0.85f),
                                topLeft = Offset(left, 0f),
                                size = Size(panelW, h),
                                cornerRadius = sheetCorner,
                            )
                            drawRoundRect(
                                color = inkColor.copy(alpha = 0.65f * finalFrameAlpha),
                                topLeft = Offset(left, 0f),
                                size = Size(panelW, h),
                                cornerRadius = sheetCorner,
                                style = strokeFinalFrame,
                            )
                        }
                    }
                }
            }
    )
}

/**
 * Standalone dog-paw wax seal emblem used at the top of opened weekly letters.
 * Fully cached in [drawWithCache] + isolated hardware RenderNode ([graphicsLayer]).
 */
@Composable
internal fun PawWaxSealEmblem(
    modifier: Modifier = Modifier,
    isAccentCrimson: Boolean = true,
) {
    val isDark = LocalDarkTheme.current
    val ink = ItvInkBorder
    val outer = if (isAccentCrimson) {
        if (isDark) Color(0xFFD3564C) else Color(0xFFC8433A)
    } else {
        if (isDark) Color(0xFF4D7B5E) else Color(0xFF5D9070)
    }
    val inner = if (isAccentCrimson) {
        if (isDark) Color(0xFFB54138) else Color(0xFFAC332B)
    } else {
        if (isDark) Color(0xFF3F684E) else Color(0xFF4D7C5F)
    }
    val paw = if (isAccentCrimson) Color(0xFFFFEFE6) else Color(0xFFF3FBF5)
    val rimOutline = ink.copy(alpha = 0.45f)

    Box(
        modifier = modifier
            .size(44.dp)
            .semantics { contentDescription = "강아지 발자국 왁스 실링" }
            .graphicsLayer()
            .drawWithCache {
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                val r = size.minDimension * 0.44f
                val sw = (size.minDimension * 0.032f).coerceIn(1.2f, 2.5f)
                val waxBlobPath = Path()
                val palmPath = Path()
                populateScallopedPath(waxBlobPath, FRONT_WAX_UNIT_X, FRONT_WAX_UNIT_Y, center.x, center.y, r)
                populatePalmPath(palmPath, center.x, center.y, r)
                val rimStroke = Stroke(width = sw, join = StrokeJoin.Round)
                val innerStroke = Stroke(width = sw * 0.7f)

                onDrawBehind {
                    drawCachedDogPawWaxSeal(
                        waxBlobPath = waxBlobPath,
                        palmPath = palmPath,
                        center = center,
                        radius = r,
                        waxOuter = outer,
                        waxInner = inner,
                        pawColor = paw,
                        rimOutline = rimOutline,
                        rimStroke = rimStroke,
                        innerRimStroke = innerStroke,
                    )
                }
            }
    )
}

/**
 * Concept 1: Classic Natural Wood & Bone Mailbox icon.
 * - Default ([hasUnread] = false): wooden arched mailbox with its top-hinged front door closed,
 *   a small pull handle at the bottom edge of the door, and the red bone flag resting quietly.
 * - New mail ([hasUnread] = true): the top-hinged front door is lifted slightly upward from the
 *   bottom handle, an envelope corner peeks out wedged in the lower opening gap, and the red bone
 *   flag stands upright.
 */
@Composable
internal fun MailboxHeaderIcon(
    hasUnread: Boolean,
    modifier: Modifier = Modifier,
) {
    WoodenMailboxArt(
        hasUnread = hasUnread,
        modifier = modifier,
    )
}

/**
 * Static wooden mailbox artwork fully cached inside [drawWithCache] + dedicated hardware
 * RenderNode ([graphicsLayer]) so Home screen scrolling and seasonal particle ticks incur
 * ZERO Path allocations and ZERO cubic-Bezier tessellation overhead.
 */
@Composable
internal fun WoodenMailboxArt(
    hasUnread: Boolean,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalDarkTheme.current
    val ink = if (isDark) Color(0xFF26170E) else Color(0xFF3A2314)
    val roofLight = if (isDark) Color(0xFFC9884B) else Color(0xFFCC8A4C)
    val roofMid = if (isDark) Color(0xFFAA6B34) else Color(0xFFB17038)
    val sideWood = if (isDark) Color(0xFF8C5326) else Color(0xFF965A2B)
    val sideDark = if (isDark) Color(0xFF70401B) else Color(0xFF7A4720)
    val frontRim = if (isDark) Color(0xFF7A461E) else Color(0xFF854E24)
    val doorLight = if (isDark) Color(0xFFD69A5E) else Color(0xFFDBA064)
    val doorMid = if (isDark) Color(0xFFBE7F44) else Color(0xFFC58549)
    val interiorDark = Color(0xFF361E10)
    val postWood = if (isDark) Color(0xFF7C4A22) else Color(0xFF8A5429)
    val grassMain = if (isDark) Color(0xFF568A3E) else Color(0xFF6BA24A)
    val grassLight = if (isDark) Color(0xFF72AC54) else Color(0xFF89C063)
    val handleFill = Color(0xFF4B2E1A)
    val handleHighlight = Color(0xFFE6BA6E)
    val boneRed = if (hasUnread) Color(0xFFD9443A) else Color(0xFFB94D45)
    val boneRedDark = Color(0xFFA82F26)
    val letterPaper = Color(0xFFFFFBF2)
    val letterShadow = Color(0xFFEBDCC6)

    Box(
        modifier = modifier
            .size(34.dp)
            .semantics {
                contentDescription = if (hasUnread) "새 편지가 끼워져 있는 우드 우편함" else "닫힌 우드 우편함"
            }
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val u = size.minDimension
                val sw = (u * 0.044f).coerceIn(1.35f, 3.2f)
                val k = 0.5523f

                // 1. Post & grass geometry
                val postTopLeft = Offset(w * 0.45f, h * 0.68f)
                val postSize = Size(w * 0.16f, h * 0.24f)
                val postCorner = CornerRadius(u * 0.03f, u * 0.03f)
                val strokePost = Stroke(width = sw * 0.9f)
                val strokeBarrel = Stroke(width = sw, join = StrokeJoin.Round, cap = StrokeCap.Round)
                val strokeFrame = Stroke(width = sw, join = StrokeJoin.Round)
                val strokeDoor = Stroke(width = sw * 0.85f, join = StrokeJoin.Round)
                val strokeCavity = Stroke(width = sw * 0.82f, join = StrokeJoin.Round)
                val strokeEnv = Stroke(width = sw * 0.80f, join = StrokeJoin.Round)
                val strokeLip = Stroke(width = sw * 0.7f, join = StrokeJoin.Round)
                val strokeStem = Stroke(width = sw * 0.72f)
                val strokeRivet = Stroke(width = sw * 0.6f)
                val strokeFlagDot = Stroke(width = sw * 0.55f)

                // 2. 3D Barrel geometry
                val frontLeft = w * 0.11f
                val frontRight = w * 0.54f
                val frontWidth = frontRight - frontLeft
                val archCenterX = (frontLeft + frontRight) * 0.5f
                val archRadius = frontWidth * 0.5f
                val archCenterY = h * 0.43f
                val frontBottom = h * 0.77f

                val backShiftX = w * 0.31f
                val backShiftY = -h * 0.09f
                val backRight = frontRight + backShiftX
                val backBottom = frontBottom + backShiftY
                val backArchCenterX = archCenterX + backShiftX
                val backArchCenterY = archCenterY + backShiftY

                val barrelPath = Path().apply {
                    moveTo(frontLeft, frontBottom)
                    lineTo(frontLeft, archCenterY)
                    cubicTo(
                        frontLeft, archCenterY - archRadius * k,
                        archCenterX - archRadius * k, archCenterY - archRadius,
                        archCenterX, archCenterY - archRadius,
                    )
                    lineTo(backArchCenterX, backArchCenterY - archRadius)
                    cubicTo(
                        backArchCenterX + archRadius * k, backArchCenterY - archRadius,
                        backRight, backArchCenterY - archRadius * k,
                        backRight, backArchCenterY,
                    )
                    lineTo(backRight, backBottom)
                    lineTo(frontRight, frontBottom)
                    close()
                }
                val sideWallPath = Path().apply {
                    moveTo(frontRight, archCenterY)
                    lineTo(backRight, backArchCenterY)
                    lineTo(backRight, backBottom)
                    lineTo(frontRight, frontBottom)
                    close()
                }
                val roofHighlightPath = Path().apply {
                    moveTo(archCenterX - archRadius * 0.12f, archCenterY - archRadius * 0.96f)
                    lineTo(backArchCenterX - archRadius * 0.12f, backArchCenterY - archRadius * 0.96f)
                    lineTo(backArchCenterX + archRadius * 0.42f, backArchCenterY - archRadius * 0.80f)
                    lineTo(archCenterX + archRadius * 0.42f, archCenterY - archRadius * 0.80f)
                    close()
                }
                val frontFramePath = Path().apply {
                    moveTo(frontLeft, frontBottom)
                    lineTo(frontLeft, archCenterY)
                    cubicTo(
                        frontLeft, archCenterY - archRadius * k,
                        archCenterX - archRadius * k, archCenterY - archRadius,
                        archCenterX, archCenterY - archRadius,
                    )
                    cubicTo(
                        archCenterX + archRadius * k, archCenterY - archRadius,
                        frontRight, archCenterY - archRadius * k,
                        frontRight, archCenterY,
                    )
                    lineTo(frontRight, frontBottom)
                    close()
                }

                // 3. Flag geometry
                val pivotX = frontRight + backShiftX * 0.46f
                val pivotY = archCenterY + backShiftY * 0.46f + h * 0.08f
                val stemTopY = pivotY - h * 0.36f
                val stemW = u * 0.055f
                val stemCorner = CornerRadius(stemW * 0.5f, stemW * 0.5f)
                val flagEndX = pivotX + u * 0.22f
                val flagEndY = pivotY - u * 0.03f

                // 4. Inner door / unread letter geometry
                val inset = frontWidth * 0.11f
                val inLeft = frontLeft + inset
                val inRight = frontRight - inset
                val inBottom = frontBottom - inset * 0.65f
                val inRadius = (inRight - inLeft) * 0.5f
                val inArchY = archCenterY + inset * 0.2f

                val innerArchPath = Path().apply {
                    moveTo(inLeft, inBottom)
                    lineTo(inLeft, inArchY)
                    cubicTo(
                        inLeft, inArchY - inRadius * k,
                        archCenterX - inRadius * k, inArchY - inRadius,
                        archCenterX, inArchY - inRadius,
                    )
                    cubicTo(
                        archCenterX + inRadius * k, inArchY - inRadius,
                        inRight, inArchY - inRadius * k,
                        inRight, inArchY,
                    )
                    lineTo(inRight, inBottom)
                    close()
                }

                val doorHighlightPath: Path?
                val envPath: Path?
                val envFoldShadow: Path?
                val liftedDoorPath: Path?
                val doorLipPath: Path?
                val envMidPt: Offset
                val liftOutLeft: Float
                val liftOutRight: Float
                val liftedBottomY: Float

                if (!hasUnread) {
                    doorHighlightPath = Path().apply {
                        moveTo(inLeft + sw * 0.5f, inArchY + (inBottom - inArchY) * 0.22f)
                        lineTo(inLeft + sw * 0.5f, inArchY)
                        cubicTo(
                            inLeft + sw * 0.5f, inArchY - inRadius * k * 0.92f,
                            archCenterX - inRadius * k * 0.92f, inArchY - inRadius * 0.92f,
                            archCenterX, inArchY - inRadius * 0.92f,
                        )
                        cubicTo(
                            archCenterX + inRadius * k * 0.92f, inArchY - inRadius * 0.92f,
                            inRight - sw * 0.5f, inArchY - inRadius * k * 0.92f,
                            inRight - sw * 0.5f, inArchY,
                        )
                        lineTo(inRight - sw * 0.5f, inArchY + (inBottom - inArchY) * 0.22f)
                        close()
                    }
                    envPath = null
                    envFoldShadow = null
                    liftedDoorPath = null
                    doorLipPath = null
                    envMidPt = Offset.Zero
                    liftOutLeft = 0f
                    liftOutRight = 0f
                    liftedBottomY = 0f
                } else {
                    doorHighlightPath = null
                    envPath = Path().apply {
                        moveTo(inLeft - frontWidth * 0.16f, inBottom - (inBottom - inArchY) * 0.08f)
                        lineTo(inLeft + (inRight - inLeft) * 0.48f, inBottom - (inBottom - inArchY) * 0.58f)
                        lineTo(inRight - (inRight - inLeft) * 0.06f, inBottom - (inBottom - inArchY) * 0.06f)
                        lineTo(inLeft + (inRight - inLeft) * 0.30f, inBottom + (inBottom - inArchY) * 0.32f)
                        close()
                    }
                    envFoldShadow = Path().apply {
                        moveTo(inLeft - frontWidth * 0.13f, inBottom - (inBottom - inArchY) * 0.07f)
                        lineTo(inLeft + (inRight - inLeft) * 0.32f, inBottom + (inBottom - inArchY) * 0.06f)
                        lineTo(inLeft + (inRight - inLeft) * 0.30f, inBottom + (inBottom - inArchY) * 0.30f)
                        close()
                    }
                    envMidPt = Offset(
                        inLeft + (inRight - inLeft) * 0.31f,
                        inBottom - (inBottom - inArchY) * 0.04f,
                    )
                    liftedBottomY = inBottom - (inBottom - inArchY) * 0.38f
                    liftOutLeft = inLeft - frontWidth * 0.08f
                    liftOutRight = inRight - frontWidth * 0.02f
                    liftedDoorPath = Path().apply {
                        moveTo(liftOutLeft, liftedBottomY)
                        lineTo(inLeft, inArchY)
                        cubicTo(
                            inLeft, inArchY - inRadius * k,
                            archCenterX - inRadius * k, inArchY - inRadius,
                            archCenterX, inArchY - inRadius,
                        )
                        cubicTo(
                            archCenterX + inRadius * k, inArchY - inRadius,
                            inRight, inArchY - inRadius * k,
                            inRight, inArchY,
                        )
                        lineTo(liftOutRight, liftedBottomY)
                        close()
                    }
                    val doorLipH = (u * 0.042f).coerceAtLeast(1.6f)
                    doorLipPath = Path().apply {
                        moveTo(liftOutLeft, liftedBottomY)
                        lineTo(liftOutRight, liftedBottomY)
                        lineTo(liftOutRight + sw * 0.4f, liftedBottomY + doorLipH)
                        lineTo(liftOutLeft + sw * 0.4f, liftedBottomY + doorLipH)
                        close()
                    }
                }

                onDrawBehind {
                    // 1. Post & grass
                    drawRoundRect(color = postWood, topLeft = postTopLeft, size = postSize, cornerRadius = postCorner)
                    drawRoundRect(color = ink, topLeft = postTopLeft, size = postSize, cornerRadius = postCorner, style = strokePost)
                    drawOval(color = grassMain, topLeft = Offset(w * 0.30f, h * 0.85f), size = Size(w * 0.46f, h * 0.12f))
                    drawOval(color = grassLight, topLeft = Offset(w * 0.36f, h * 0.86f), size = Size(w * 0.24f, h * 0.07f))

                    // 2. 3D Wooden mailbox body
                    drawPath(path = barrelPath, color = roofMid)
                    drawPath(path = sideWallPath, color = sideWood)
                    drawLine(
                        color = sideDark,
                        start = Offset(frontRight + sw * 0.4f, (archCenterY + frontBottom) * 0.52f),
                        end = Offset(backRight - sw * 0.4f, (backArchCenterY + backBottom) * 0.52f),
                        strokeWidth = sw * 0.65f,
                        cap = StrokeCap.Round,
                    )
                    drawPath(path = roofHighlightPath, color = roofLight)
                    drawPath(path = barrelPath, color = ink, style = strokeBarrel)
                    drawLine(
                        color = ink.copy(alpha = 0.72f),
                        start = Offset(frontRight, archCenterY),
                        end = Offset(backRight, backArchCenterY),
                        strokeWidth = sw * 0.7f,
                        cap = StrokeCap.Round,
                    )

                    // 3. Red Bone Flag
                    if (hasUnread) {
                        drawRoundRect(
                            color = boneRed,
                            topLeft = Offset(pivotX - stemW * 0.5f, stemTopY),
                            size = Size(stemW, pivotY - stemTopY),
                            cornerRadius = stemCorner,
                        )
                        drawRoundRect(
                            color = ink,
                            topLeft = Offset(pivotX - stemW * 0.5f, stemTopY),
                            size = Size(stemW, pivotY - stemTopY),
                            cornerRadius = stemCorner,
                            style = strokeStem,
                        )
                        drawBoneFlagHead(
                            center = Offset(pivotX + u * 0.06f, stemTopY - u * 0.02f),
                            boneWidth = u * 0.22f,
                            boneHeight = u * 0.11f,
                            fill = boneRed,
                            outline = ink,
                            strokeW = sw * 0.75f,
                        )
                    } else {
                        drawLine(
                            color = boneRedDark,
                            start = Offset(pivotX, pivotY),
                            end = Offset(flagEndX, flagEndY),
                            strokeWidth = sw * 1.45f,
                            cap = StrokeCap.Round,
                        )
                        val knobR = u * 0.042f
                        val c1 = Offset(flagEndX, flagEndY - u * 0.022f)
                        val c2 = Offset(flagEndX, flagEndY + u * 0.022f)
                        drawCircle(color = boneRed, radius = knobR, center = c1)
                        drawCircle(color = boneRed, radius = knobR, center = c2)
                        drawCircle(color = ink, radius = knobR, center = c1, style = strokeFlagDot)
                        drawCircle(color = ink, radius = knobR, center = c2, style = strokeFlagDot)
                    }
                    val rivetCenter = Offset(pivotX, pivotY)
                    drawCircle(color = handleHighlight, radius = u * 0.035f, center = rivetCenter)
                    drawCircle(color = ink, radius = u * 0.035f, center = rivetCenter, style = strokeRivet)

                    // 4. Front Arched Face Frame
                    drawPath(path = frontFramePath, color = frontRim)
                    drawPath(path = frontFramePath, color = ink, style = strokeFrame)

                    if (!hasUnread) {
                        drawPath(path = innerArchPath, color = doorMid)
                        doorHighlightPath?.let { drawPath(path = it, color = doorLight.copy(alpha = 0.55f)) }
                        drawPath(path = innerArchPath, color = ink, style = strokeDoor)
                        drawMiniWoodPaw(
                            center = Offset(archCenterX, inArchY + (inBottom - inArchY) * 0.18f),
                            radius = inRadius * 0.42f,
                            color = sideDark.copy(alpha = 0.72f),
                        )
                        val handleW = (inRight - inLeft) * 0.42f
                        val handleH = (u * 0.055f).coerceAtLeast(2.2f)
                        val handleY = inBottom - handleH * 1.9f
                        drawRoundRect(
                            color = handleFill,
                            topLeft = Offset(archCenterX - handleW * 0.5f, handleY),
                            size = Size(handleW, handleH),
                            cornerRadius = CornerRadius(handleH * 0.5f, handleH * 0.5f),
                        )
                        drawLine(
                            color = handleHighlight,
                            start = Offset(archCenterX - handleW * 0.30f, handleY + handleH * 0.35f),
                            end = Offset(archCenterX + handleW * 0.30f, handleY + handleH * 0.35f),
                            strokeWidth = (handleH * 0.32f).coerceAtLeast(1f),
                            cap = StrokeCap.Round,
                        )
                    } else {
                        drawPath(path = innerArchPath, color = interiorDark)
                        drawPath(path = innerArchPath, color = ink, style = strokeCavity)

                        envPath?.let { drawPath(path = it, color = letterPaper) }
                        envFoldShadow?.let { drawPath(path = it, color = letterShadow) }
                        drawLine(
                            color = ink.copy(alpha = 0.65f),
                            start = Offset(inLeft - frontWidth * 0.13f, inBottom - (inBottom - inArchY) * 0.07f),
                            end = envMidPt,
                            strokeWidth = sw * 0.62f,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = ink.copy(alpha = 0.65f),
                            start = Offset(inLeft + (inRight - inLeft) * 0.48f, inBottom - (inBottom - inArchY) * 0.56f),
                            end = envMidPt,
                            strokeWidth = sw * 0.62f,
                            cap = StrokeCap.Round,
                        )
                        envPath?.let { drawPath(path = it, color = ink, style = strokeEnv) }
                        drawCircle(
                            color = Color(0xFFC8433A),
                            radius = (u * 0.052f).coerceAtLeast(1.8f),
                            center = envMidPt,
                        )

                        doorLipPath?.let { drawPath(path = it, color = sideDark) }
                        liftedDoorPath?.let {
                            drawPath(path = it, color = doorLight)
                            drawPath(path = it, color = ink, style = strokeDoor)
                        }
                        doorLipPath?.let { drawPath(path = it, color = ink, style = strokeLip) }

                        drawMiniWoodPaw(
                            center = Offset((liftOutLeft + liftOutRight) * 0.5f, inArchY - inRadius * 0.08f),
                            radius = inRadius * 0.34f,
                            color = sideDark.copy(alpha = 0.65f),
                        )

                        val handleW = (liftOutRight - liftOutLeft) * 0.40f
                        val handleH = (u * 0.052f).coerceAtLeast(2.0f)
                        val handleCx = (liftOutLeft + liftOutRight) * 0.5f
                        val handleY = liftedBottomY - handleH * 1.35f
                        drawRoundRect(
                            color = handleFill,
                            topLeft = Offset(handleCx - handleW * 0.5f, handleY),
                            size = Size(handleW, handleH),
                            cornerRadius = CornerRadius(handleH * 0.5f, handleH * 0.5f),
                        )
                        drawLine(
                            color = handleHighlight,
                            start = Offset(handleCx - handleW * 0.28f, handleY + handleH * 0.36f),
                            end = Offset(handleCx + handleW * 0.28f, handleY + handleH * 0.36f),
                            strokeWidth = (handleH * 0.32f).coerceAtLeast(1f),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
    )
}

private fun DrawScope.drawBoneFlagHead(
    center: Offset,
    boneWidth: Float,
    boneHeight: Float,
    fill: Color,
    outline: Color,
    strokeW: Float,
) {
    val halfW = boneWidth * 0.34f
    val knobR = boneHeight * 0.44f
    val barH = boneHeight * 0.54f
    val leftX = center.x - halfW
    val rightX = center.x + halfW
    val knobOffY = boneHeight * 0.25f
    val outR = knobR + strokeW * 0.7f

    drawCircle(color = outline, radius = outR, center = Offset(leftX, center.y - knobOffY))
    drawCircle(color = outline, radius = outR, center = Offset(leftX, center.y + knobOffY))
    drawCircle(color = outline, radius = outR, center = Offset(rightX, center.y - knobOffY))
    drawCircle(color = outline, radius = outR, center = Offset(rightX, center.y + knobOffY))
    drawRect(
        color = outline,
        topLeft = Offset(leftX, center.y - barH * 0.5f - strokeW * 0.6f),
        size = Size(halfW * 2f, barH + strokeW * 1.2f),
    )

    drawCircle(color = fill, radius = knobR, center = Offset(leftX, center.y - knobOffY))
    drawCircle(color = fill, radius = knobR, center = Offset(leftX, center.y + knobOffY))
    drawCircle(color = fill, radius = knobR, center = Offset(rightX, center.y - knobOffY))
    drawCircle(color = fill, radius = knobR, center = Offset(rightX, center.y + knobOffY))
    drawRect(
        color = fill,
        topLeft = Offset(leftX, center.y - barH * 0.5f),
        size = Size(halfW * 2f, barH),
    )
}

private fun DrawScope.drawMiniWoodPaw(
    center: Offset,
    radius: Float,
    color: Color,
) {
    drawOval(
        color = color,
        topLeft = Offset(center.x - radius * 0.46f, center.y - radius * 0.02f),
        size = Size(radius * 0.92f, radius * 0.68f),
    )
    val toeR = radius * 0.22f
    drawCircle(color, toeR, Offset(center.x - radius * 0.50f, center.y - radius * 0.24f))
    drawCircle(color, toeR, Offset(center.x - radius * 0.17f, center.y - radius * 0.52f))
    drawCircle(color, toeR, Offset(center.x + radius * 0.17f, center.y - radius * 0.52f))
    drawCircle(color, toeR, Offset(center.x + radius * 0.50f, center.y - radius * 0.24f))
}

private fun DrawScope.drawDogPawWaxSealFast(
    waxBlobPath: Path,
    palmPath: Path,
    center: Offset,
    radius: Float,
    waxOuter: Color,
    waxInner: Color,
    pawColor: Color,
    rimOutline: Color,
    rimStroke: Stroke,
    innerRimStroke: Stroke,
) {
    populateScallopedPath(waxBlobPath, FRONT_WAX_UNIT_X, FRONT_WAX_UNIT_Y, center.x, center.y, radius)
    populatePalmPath(palmPath, center.x, center.y, radius)
    drawCachedDogPawWaxSeal(
        waxBlobPath = waxBlobPath,
        palmPath = palmPath,
        center = center,
        radius = radius,
        waxOuter = waxOuter,
        waxInner = waxInner,
        pawColor = pawColor,
        rimOutline = rimOutline,
        rimStroke = rimStroke,
        innerRimStroke = innerRimStroke,
    )
}

private fun DrawScope.drawCachedDogPawWaxSeal(
    waxBlobPath: Path,
    palmPath: Path,
    center: Offset,
    radius: Float,
    waxOuter: Color,
    waxInner: Color,
    pawColor: Color,
    rimOutline: Color,
    rimStroke: Stroke,
    innerRimStroke: Stroke,
) {
    drawCircle(
        color = Color(0x33000000),
        radius = radius * 1.03f,
        center = Offset(center.x + radius * 0.06f, center.y + radius * 0.10f),
    )
    drawPath(path = waxBlobPath, color = waxOuter)
    drawPath(path = waxBlobPath, color = rimOutline, style = rimStroke)
    drawCircle(color = waxInner, radius = radius * 0.77f, center = center)
    drawCircle(color = Color.White.copy(alpha = 0.22f), radius = radius * 0.77f, center = center, style = innerRimStroke)
    drawCircle(
        color = Color.White.copy(alpha = 0.25f),
        radius = radius * 0.15f,
        center = Offset(center.x - radius * 0.48f, center.y - radius * 0.44f),
    )
    drawPath(path = palmPath, color = Color(0x2A000000))
    drawPath(path = palmPath, color = pawColor)

    val toeW = radius * 0.21f
    val toeH = radius * 0.27f
    for (i in TOE_OFFSETS_X.indices) {
        val pos = Offset(center.x + radius * TOE_OFFSETS_X[i], center.y + radius * TOE_OFFSETS_Y[i])
        val tw = toeW * TOE_SCALES[i]
        val th = toeH * TOE_SCALES[i]
        rotate(degrees = TOE_DEGREES[i], pivot = pos) {
            drawOval(
                color = pawColor,
                topLeft = Offset(pos.x - tw * 0.5f, pos.y - th * 0.5f),
                size = Size(tw, th),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFEFE9DF, widthDp = 420, heightDp = 150)
@Composable
private fun WoodenMailboxArtworkPreview() {
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WoodenMailboxArt(hasUnread = false, modifier = Modifier.size(110.dp))
        WoodenMailboxArt(hasUnread = true, modifier = Modifier.size(110.dp))
        PawSealedEnvelopeArt(isRead = false, modifier = Modifier.width(135.dp))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFEFE9DF, widthDp = 460, heightDp = 170)
@Composable
private fun PawEnvelopeStatesPreview() {
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PawSealedEnvelopeArt(isRead = false, openProgress = 0f, letterSlideProgress = 0f, modifier = Modifier.width(125.dp))
        PawSealedEnvelopeArt(isRead = true, openProgress = 1f, letterSlideProgress = 0.30f, modifier = Modifier.width(125.dp))
        PawSealedEnvelopeArt(isRead = false, openProgress = 1f, letterSlideProgress = 0.90f, modifier = Modifier.width(125.dp))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFEFE9DF, widthDp = 520, heightDp = 240)
@Composable
private fun LetterDoubleUnfoldPreview() {
    Row(
        modifier = Modifier.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(0.0f, 0.22f, 0.48f, 0.78f, 1.0f).forEach { unfold ->
            EnvelopeUnsealAndUnfoldCanvas(
                openProgressProvider = { 1f },
                letterSlideProgressProvider = { 1f },
                unfoldProgressProvider = { unfold },
                modifier = Modifier
                    .width(92.dp)
                    .height(210.dp),
            )
        }
    }
}
