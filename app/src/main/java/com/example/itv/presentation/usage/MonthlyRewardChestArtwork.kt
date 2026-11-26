package com.example.itv.presentation.usage

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.example.itv.domain.reward.MonthlyChestTier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val ChestInkOutline = Color(0xFF2B231E)

internal data class ChestPalette(
    val bodyTop: Color,
    val bodyBottom: Color,
    val lidTop: Color,
    val lidBottom: Color,
    val trimLight: Color,
    val trimMid: Color,
    val trimDark: Color,
    val lockLight: Color,
    val lockDark: Color,
    val gemColor: Color,
    val gemHighlight: Color,
    val glowInner: Color,
    val glowOuter: Color,
    val badgeBg: Color,
    val badgeAccent: Color
)

internal fun paletteForChest(tier: MonthlyChestTier): ChestPalette = when (tier) {
    MonthlyChestTier.BRONZE -> ChestPalette(
        bodyTop = Color(0xFFA97040),
        bodyBottom = Color(0xFF7B4A24),
        lidTop = Color(0xFFB97E4B),
        lidBottom = Color(0xFF8A552C),
        trimLight = Color(0xFFE5A46C),
        trimMid = Color(0xFFC87D44),
        trimDark = Color(0xFF8B4C23),
        lockLight = Color(0xFFE7A972),
        lockDark = Color(0xFFB16733),
        gemColor = Color(0xFFE5A46C),
        gemHighlight = Color(0xFFFFE1B8),
        glowInner = Color(0xFFFFE58A),
        glowOuter = Color(0x00FFBE42),
        badgeBg = Color(0xFFF6E7D8),
        badgeAccent = Color(0xFFB86B32)
    )
    MonthlyChestTier.SILVER -> ChestPalette(
        bodyTop = Color(0xFF4B8491),
        bodyBottom = Color(0xFF2B5662),
        lidTop = Color(0xFF5A96A3),
        lidBottom = Color(0xFF336572),
        trimLight = Color(0xFFEBF3F7),
        trimMid = Color(0xFFC2D2DC),
        trimDark = Color(0xFF7B93A3),
        lockLight = Color(0xFFF0F6FA),
        lockDark = Color(0xFF9EB3C0),
        gemColor = Color(0xFF3AD2E3),
        gemHighlight = Color(0xFFC8FAFF),
        glowInner = Color(0xFF8BF3FF),
        glowOuter = Color(0x0032D4EA),
        badgeBg = Color(0xFFE3F2F6),
        badgeAccent = Color(0xFF2E7C8F)
    )
    MonthlyChestTier.GOLD -> ChestPalette(
        bodyTop = Color(0xFF9A3573),
        bodyBottom = Color(0xFF641B4D),
        lidTop = Color(0xFFB14386),
        lidBottom = Color(0xFF76215B),
        trimLight = Color(0xFFFFEE85),
        trimMid = Color(0xFFF7C234),
        trimDark = Color(0xFFC47A08),
        lockLight = Color(0xFFFFF08A),
        lockDark = Color(0xFFDF9312),
        gemColor = Color(0xFF4CAEFF),
        gemHighlight = Color(0xFFCBEAFF),
        glowInner = Color(0xFFFFF08C),
        glowOuter = Color(0x00FFB72B),
        badgeBg = Color(0xFFFFF2D6),
        badgeAccent = Color(0xFFC98007)
    )
}

/**
 * Static raster cache for non-animated calendar chest & key icons (~6 small entries, <200 KB total).
 * Eliminates ~240 Path/Brush/Stroke allocations per draw frame on the home calendar screen.
 */
private object StaticChestArtworkCache {
    private val cache = LruCache<String, ImageBitmap>(32)
    private val drawScope = CanvasDrawScope()

    fun getOrCreateChest(
        tier: MonthlyChestTier,
        openProgress: Float,
        lockPopProgress: Float,
        dimmed: Boolean,
        widthPx: Int,
        heightPx: Int,
        density: Density,
        layoutDirection: LayoutDirection
    ): ImageBitmap {
        val openKey = (openProgress * 100f).roundToInt()
        val lockKey = (lockPopProgress * 100f).roundToInt()
        val key = "chest|${tier.name}|$openKey|$lockKey|$dimmed|${widthPx}x${heightPx}"
        cache.get(key)?.let { return it }

        val bitmap = ImageBitmap(widthPx, heightPx, ImageBitmapConfig.Argb8888)
        val canvas = ComposeCanvas(bitmap)
        drawScope.draw(
            density = density,
            layoutDirection = layoutDirection,
            canvas = canvas,
            size = Size(widthPx.toFloat(), heightPx.toFloat())
        ) {
            drawMonthlyRewardChest(
                tier = tier,
                openProgress = openProgress,
                lockPopProgress = lockPopProgress,
                glowPhase = 0f,
                dimmed = dimmed
            )
        }
        cache.put(key, bitmap)
        return bitmap
    }

    fun getOrCreateKey(
        tier: MonthlyChestTier,
        rotationDeg: Float,
        widthPx: Int,
        heightPx: Int,
        density: Density,
        layoutDirection: LayoutDirection
    ): ImageBitmap {
        val rotKey = (rotationDeg * 10f).roundToInt()
        val key = "key|${tier.name}|$rotKey|${widthPx}x${heightPx}"
        cache.get(key)?.let { return it }

        val bitmap = ImageBitmap(widthPx, heightPx, ImageBitmapConfig.Argb8888)
        val canvas = ComposeCanvas(bitmap)
        drawScope.draw(
            density = density,
            layoutDirection = layoutDirection,
            canvas = canvas,
            size = Size(widthPx.toFloat(), heightPx.toFloat())
        ) {
            drawRewardKey(
                tier = tier,
                center = Offset(size.width * 0.5f, size.height * 0.5f),
                length = size.minDimension * 0.86f,
                rotationDeg = rotationDeg,
                alpha = 1f
            )
        }
        cache.put(key, bitmap)
        return bitmap
    }
}

/**
 * 뼈다귀 자물쇠가 달린 월간 보상 보물상자 일러스트 (동/은/금, 닫힘~열림 연속 애니메이션 지원)
 */
@Composable
internal fun MonthlyRewardChestIllustration(
    tier: MonthlyChestTier,
    openProgress: Float,
    modifier: Modifier = Modifier,
    lockPopProgress: Float = 0f,
    glowPhase: Float = 0f,
    dimmed: Boolean = false
) {
    val clampedOpen = openProgress.coerceIn(0f, 1f)
    val clampedLock = lockPopProgress.coerceIn(0f, 1f)
    val isStaticThumbnail = glowPhase == 0f && (clampedOpen <= 0.001f || clampedOpen >= 0.999f)

    if (isStaticThumbnail) {
        Box(
            modifier = modifier.drawWithCache {
                val wPx = size.width.roundToInt().coerceAtLeast(1)
                val hPx = size.height.roundToInt().coerceAtLeast(1)
                val baked = StaticChestArtworkCache.getOrCreateChest(
                    tier = tier,
                    openProgress = clampedOpen,
                    lockPopProgress = clampedLock,
                    dimmed = dimmed,
                    widthPx = wPx,
                    heightPx = hPx,
                    density = this,
                    layoutDirection = layoutDirection
                )
                val dstSize = IntSize(wPx, hPx)
                onDrawBehind {
                    drawImage(image = baked, dstSize = dstSize)
                }
            }
        )
        return
    }

    Canvas(modifier = modifier) {
        drawMonthlyRewardChest(
            tier = tier,
            openProgress = clampedOpen,
            lockPopProgress = clampedLock,
            glowPhase = glowPhase,
            dimmed = dimmed
        )
    }
}

/**
 * 동/은/금 각각 색과 모양이 다른 열쇠 일러스트 (캘린더 카드 & 강아지 입에 물고 오는 연출 공용)
 */
@Composable
internal fun RewardKeyIllustration(
    tier: MonthlyChestTier,
    modifier: Modifier = Modifier,
    rotationDeg: Float = -18f,
    alpha: Float = 1f
) {
    if (alpha >= 0.999f) {
        Box(
            modifier = modifier.drawWithCache {
                val wPx = size.width.roundToInt().coerceAtLeast(1)
                val hPx = size.height.roundToInt().coerceAtLeast(1)
                val baked = StaticChestArtworkCache.getOrCreateKey(
                    tier = tier,
                    rotationDeg = rotationDeg,
                    widthPx = wPx,
                    heightPx = hPx,
                    density = this,
                    layoutDirection = layoutDirection
                )
                val dstSize = IntSize(wPx, hPx)
                onDrawBehind {
                    drawImage(image = baked, dstSize = dstSize)
                }
            }
        )
        return
    }

    Canvas(modifier = modifier) {
        drawRewardKey(
            tier = tier,
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            length = size.minDimension * 0.86f,
            rotationDeg = rotationDeg,
            alpha = alpha
        )
    }
}

internal fun DrawScope.drawMonthlyRewardChest(
    tier: MonthlyChestTier,
    openProgress: Float,
    lockPopProgress: Float = 0f,
    glowPhase: Float = 0f,
    dimmed: Boolean = false
) {
    val palette = paletteForChest(tier)
    val w = size.width
    val h = size.height
    val u = minOf(w, h)
    val strokeW = (u * 0.028f).coerceIn(1.3f, 4.2f)
    val contentAlpha = if (dimmed) 0.58f else 1f

    val chestW = u * 0.68f
    val baseH = u * 0.30f
    val lidH = u * 0.21f
    val chestLeft = (w - chestW) * 0.5f
    val baseBottom = h * 0.87f
    val baseTop = baseBottom - baseH
    val cx = w * 0.5f

    // 1. Soft floor shadow
    drawOval(
        color = Color(0xFF2B231E).copy(alpha = 0.16f * contentAlpha),
        topLeft = Offset(chestLeft - chestW * 0.06f, baseBottom - u * 0.04f),
        size = Size(chestW * 1.12f, u * 0.11f)
    )

    // 2. Radiant treasure glow behind/inside when opening
    if (openProgress > 0.04f) {
        val pulse = 0.94f + 0.06f * sin(glowPhase * 2f * PI.toFloat())
        val glowRadius = u * (0.34f + 0.16f * openProgress) * pulse
        val glowCenter = Offset(cx, baseTop - u * 0.04f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.glowInner.copy(alpha = (0.88f * openProgress * contentAlpha).coerceIn(0f, 1f)),
                    palette.glowInner.copy(alpha = (0.40f * openProgress * contentAlpha).coerceIn(0f, 1f)),
                    palette.glowOuter
                ),
                center = glowCenter,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = glowCenter
        )
    }

    // 3. Open Lid (back-tilted perspective) & Treasure Interior
    val lidLift = openProgress * (u * 0.20f)
    if (openProgress > 0.02f) {
        // Raised inner lid back wall
        val openLidTop = baseTop - lidH * (0.72f + 0.58f * openProgress)
        val openLidBottom = baseTop + u * 0.02f
        val lidInset = chestW * 0.04f * openProgress
        val openLidPath = Path().apply {
            moveTo(chestLeft + lidInset, openLidBottom)
            lineTo(chestLeft - chestW * 0.03f * openProgress, openLidTop + lidH * 0.32f)
            quadraticTo(cx, openLidTop - lidH * 0.20f, chestLeft + chestW + chestW * 0.03f * openProgress, openLidTop + lidH * 0.32f)
            lineTo(chestLeft + chestW - lidInset, openLidBottom)
            close()
        }
        drawPath(
            path = openLidPath,
            brush = Brush.verticalGradient(
                colors = listOf(palette.lidTop, palette.lidBottom),
                startY = openLidTop,
                endY = openLidBottom
            ),
            alpha = contentAlpha
        )
        // Inner cavity lining
        val innerCavity = Path().apply {
            moveTo(chestLeft + chestW * 0.08f, baseTop + u * 0.01f)
            lineTo(chestLeft + chestW * 0.06f, openLidTop + lidH * 0.52f)
            quadraticTo(cx, openLidTop + lidH * 0.18f, chestLeft + chestW * 0.94f, openLidTop + lidH * 0.52f)
            lineTo(chestLeft + chestW * 0.92f, baseTop + u * 0.01f)
            close()
        }
        drawPath(
            path = innerCavity,
            color = Color(0xFF3E2618).copy(alpha = 0.62f * openProgress * contentAlpha)
        )
        // Rim band on raised lid
        drawPath(
            path = openLidPath,
            color = palette.trimMid.copy(alpha = contentAlpha),
            style = Stroke(width = strokeW * 1.9f, join = StrokeJoin.Round)
        )
        drawPath(
            path = openLidPath,
            color = ChestInkOutline.copy(alpha = contentAlpha),
            style = Stroke(width = strokeW, join = StrokeJoin.Round)
        )

        // Gold chest crown sitting atop the opened lid!
        if (tier == MonthlyChestTier.GOLD) {
            drawMiniCrown(
                center = Offset(cx, openLidTop + lidH * 0.04f),
                crownW = chestW * 0.29f,
                crownH = u * 0.13f,
                strokeW = strokeW * 0.85f,
                alpha = contentAlpha
            )
        }

        // Dark chest interior mouth
        drawRoundRect(
            color = Color(0xFF3A2416).copy(alpha = 0.90f * contentAlpha),
            topLeft = Offset(chestLeft + chestW * 0.04f, baseTop - u * 0.03f),
            size = Size(chestW * 0.92f, u * 0.09f),
            cornerRadius = CornerRadius(u * 0.03f, u * 0.03f)
        )

        // Tier-specific treasures bursting out of the open chest!
        drawChestTreasures(
            tier = tier,
            cx = cx,
            mouthY = baseTop,
            chestW = chestW,
            u = u,
            openProgress = openProgress,
            glowPhase = glowPhase,
            strokeW = strokeW * 0.82f,
            alpha = contentAlpha
        )
    }

    // 4. Chest Lower Base Box
    val baseRect = Rect(chestLeft, baseTop, chestLeft + chestW, baseBottom)
    val baseCorner = CornerRadius(u * 0.055f, u * 0.055f)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(palette.bodyTop, palette.bodyBottom),
            startY = baseTop,
            endY = baseBottom
        ),
        topLeft = baseRect.topLeft,
        size = baseRect.size,
        cornerRadius = baseCorner,
        alpha = contentAlpha
    )

    // Material texture details on base
    when (tier) {
        MonthlyChestTier.BRONZE -> {
            // Horizontal wooden plank lines
            for (i in 1..2) {
                val py = baseTop + baseH * (i / 3f)
                drawLine(
                    color = ChestInkOutline.copy(alpha = 0.28f * contentAlpha),
                    start = Offset(chestLeft + chestW * 0.06f, py),
                    end = Offset(chestLeft + chestW * 0.94f, py),
                    strokeWidth = strokeW * 0.7f
                )
            }
        }
        MonthlyChestTier.SILVER -> {
            // Cute subtle paw embosses on lower corners
            drawTinyPawPrint(
                center = Offset(chestLeft + chestW * 0.21f, baseTop + baseH * 0.62f),
                radius = u * 0.036f,
                color = Color(0xFF1F424D).copy(alpha = 0.36f * contentAlpha)
            )
            drawTinyPawPrint(
                center = Offset(chestLeft + chestW * 0.79f, baseTop + baseH * 0.62f),
                radius = u * 0.036f,
                color = Color(0xFF1F424D).copy(alpha = 0.36f * contentAlpha)
            )
        }
        MonthlyChestTier.GOLD -> {
            // Royal gold filigree curved wing accents on front panel
            val leftFiligree = Path().apply {
                moveTo(chestLeft + chestW * 0.14f, baseBottom - baseH * 0.24f)
                quadraticTo(chestLeft + chestW * 0.28f, baseTop + baseH * 0.34f, cx - chestW * 0.12f, baseTop + baseH * 0.64f)
            }
            val rightFiligree = Path().apply {
                moveTo(chestLeft + chestW * 0.86f, baseBottom - baseH * 0.24f)
                quadraticTo(chestLeft + chestW * 0.72f, baseTop + baseH * 0.34f, cx + chestW * 0.12f, baseTop + baseH * 0.64f)
            }
            drawPath(leftFiligree, color = palette.trimLight.copy(alpha = 0.85f * contentAlpha), style = Stroke(width = strokeW * 1.1f, cap = StrokeCap.Round))
            drawPath(rightFiligree, color = palette.trimLight.copy(alpha = 0.85f * contentAlpha), style = Stroke(width = strokeW * 1.1f, cap = StrokeCap.Round))
        }
    }

    // Metallic rim & vertical corner bands on lower box
    val bandW = chestW * 0.11f
    val rimH = baseH * 0.20f
    // Top rim bar
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(palette.trimLight, palette.trimMid, palette.trimDark),
            startY = baseTop,
            endY = baseTop + rimH
        ),
        topLeft = Offset(chestLeft, baseTop),
        size = Size(chestW, rimH),
        cornerRadius = CornerRadius(u * 0.02f, u * 0.02f),
        alpha = contentAlpha
    )
    // Left & right vertical metal corner straps
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(palette.trimLight, palette.trimMid, palette.trimDark),
            startX = chestLeft,
            endX = chestLeft + bandW
        ),
        topLeft = Offset(chestLeft, baseTop),
        size = Size(bandW, baseH),
        cornerRadius = CornerRadius(u * 0.03f, u * 0.03f),
        alpha = contentAlpha
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(palette.trimDark, palette.trimMid, palette.trimLight),
            startX = chestLeft + chestW - bandW,
            endX = chestLeft + chestW
        ),
        topLeft = Offset(chestLeft + chestW - bandW, baseTop),
        size = Size(bandW, baseH),
        cornerRadius = CornerRadius(u * 0.03f, u * 0.03f),
        alpha = contentAlpha
    )
    // Bottom horizontal metal trim
    val bottomTrimH = baseH * 0.16f
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(palette.trimMid, palette.trimDark),
            startY = baseBottom - bottomTrimH,
            endY = baseBottom
        ),
        topLeft = Offset(chestLeft, baseBottom - bottomTrimH),
        size = Size(chestW, bottomTrimH),
        cornerRadius = CornerRadius(u * 0.03f, u * 0.03f),
        alpha = contentAlpha
    )

    // Corner rivets or gems (Bronze = rivets, Silver = teal gems, Gold = sapphire gems)
    val cornerOffsets = listOf(
        Offset(chestLeft + bandW * 0.52f, baseBottom - bottomTrimH * 0.58f),
        Offset(chestLeft + chestW - bandW * 0.52f, baseBottom - bottomTrimH * 0.58f)
    )
    cornerOffsets.forEach { pos ->
        if (tier == MonthlyChestTier.BRONZE) {
            drawCircle(color = palette.trimLight.copy(alpha = contentAlpha), radius = u * 0.017f, center = pos)
            drawCircle(color = ChestInkOutline.copy(alpha = contentAlpha), radius = u * 0.017f, center = pos, style = Stroke(strokeW * 0.65f))
        } else {
            drawFacetedGem(
                center = pos,
                radius = u * 0.025f,
                color = palette.gemColor,
                highlight = palette.gemHighlight,
                strokeW = strokeW * 0.65f,
                alpha = contentAlpha
            )
        }
    }

    // Crisp outline around base box
    drawRoundRect(
        color = ChestInkOutline.copy(alpha = contentAlpha),
        topLeft = baseRect.topLeft,
        size = baseRect.size,
        cornerRadius = baseCorner,
        style = Stroke(width = strokeW)
    )
    drawLine(
        color = ChestInkOutline.copy(alpha = contentAlpha),
        start = Offset(chestLeft, baseTop + rimH),
        end = Offset(chestLeft + chestW, baseTop + rimH),
        strokeWidth = strokeW * 0.78f
    )

    // 5. Closed Arched Lid (fades out cleanly as openProgress crosses 0 -> 0.30)
    if (openProgress < 0.32f) {
        val closedAlpha = ((1f - openProgress / 0.32f) * contentAlpha).coerceIn(0f, 1f)
        val lidTop = baseTop - lidH - lidLift * 0.25f
        val lidPath = Path().apply {
            moveTo(chestLeft - chestW * 0.02f, baseTop + u * 0.012f)
            lineTo(chestLeft - chestW * 0.02f, lidTop + lidH * 0.45f)
            quadraticTo(cx, lidTop - lidH * 0.26f, chestLeft + chestW + chestW * 0.02f, lidTop + lidH * 0.45f)
            lineTo(chestLeft + chestW + chestW * 0.02f, baseTop + u * 0.012f)
            close()
        }
        drawPath(
            path = lidPath,
            brush = Brush.verticalGradient(
                colors = listOf(palette.lidTop, palette.lidBottom),
                startY = lidTop,
                endY = baseTop
            ),
            alpha = closedAlpha
        )
        // Specular highlight across curved top
        val glossPath = Path().apply {
            moveTo(chestLeft + chestW * 0.14f, lidTop + lidH * 0.34f)
            quadraticTo(cx, lidTop + lidH * 0.06f, chestLeft + chestW * 0.86f, lidTop + lidH * 0.34f)
        }
        drawPath(
            path = glossPath,
            color = Color.White.copy(alpha = 0.24f * closedAlpha),
            style = Stroke(width = strokeW * 1.4f, cap = StrokeCap.Round)
        )
        // Left & right curved lid metal straps
        val leftStrap = Path().apply {
            moveTo(chestLeft + bandW * 0.15f, baseTop + u * 0.01f)
            lineTo(chestLeft + bandW * 0.15f, lidTop + lidH * 0.38f)
            quadraticTo(chestLeft + bandW * 0.65f, lidTop + lidH * 0.14f, chestLeft + bandW * 1.15f, lidTop + lidH * 0.16f)
            lineTo(chestLeft + bandW * 1.15f, baseTop + u * 0.01f)
            close()
        }
        val rightStrap = Path().apply {
            moveTo(chestLeft + chestW - bandW * 0.15f, baseTop + u * 0.01f)
            lineTo(chestLeft + chestW - bandW * 0.15f, lidTop + lidH * 0.38f)
            quadraticTo(chestLeft + chestW - bandW * 0.65f, lidTop + lidH * 0.14f, chestLeft + chestW - bandW * 1.15f, lidTop + lidH * 0.16f)
            lineTo(chestLeft + chestW - bandW * 1.15f, baseTop + u * 0.01f)
            close()
        }
        drawPath(leftStrap, color = palette.trimMid.copy(alpha = closedAlpha))
        drawPath(rightStrap, color = palette.trimMid.copy(alpha = closedAlpha))
        drawPath(leftStrap, color = ChestInkOutline.copy(alpha = closedAlpha), style = Stroke(strokeW * 0.72f))
        drawPath(rightStrap, color = ChestInkOutline.copy(alpha = closedAlpha), style = Stroke(strokeW * 0.72f))

        // Lid outline
        drawPath(
            path = lidPath,
            color = ChestInkOutline.copy(alpha = closedAlpha),
            style = Stroke(width = strokeW, join = StrokeJoin.Round)
        )

        // Gold chest crown on top of closed lid!
        if (tier == MonthlyChestTier.GOLD) {
            drawMiniCrown(
                center = Offset(cx, lidTop + lidH * 0.01f),
                crownW = chestW * 0.30f,
                crownH = u * 0.135f,
                strokeW = strokeW * 0.85f,
                alpha = closedAlpha
            )
        }
    }

    // 6. Signature Bone-Shaped Padlock (뼈다귀 자물쇠) on center front latch!
    val lockAlpha = ((1f - lockPopProgress * 0.9f) * contentAlpha).coerceIn(0f, 1f)
    if (lockAlpha > 0.02f) {
        val lockCenterY = baseTop + rimH * 1.12f - lockPopProgress * u * 0.12f
        val lockRot = lockPopProgress * 22f
        val shackleOpenOffset = if (openProgress > 0.05f || lockPopProgress > 0.1f) u * 0.025f else 0f
        withTransform({
            rotate(degrees = lockRot, pivot = Offset(cx, lockCenterY))
        }) {
            drawBonePadlock(
                center = Offset(cx, lockCenterY),
                width = chestW * 0.29f,
                height = u * 0.115f,
                shackleLift = shackleOpenOffset,
                palette = palette,
                hasPinkGem = tier == MonthlyChestTier.GOLD,
                strokeW = strokeW * 0.88f,
                alpha = lockAlpha
            )
        }
    }

    // 7. Opened Gold Chest celebratory ribbon bow on bottom-right corner & Bronze toy ball
    if (openProgress > 0.45f) {
        val extraAlpha = (((openProgress - 0.45f) / 0.55f) * contentAlpha).coerceIn(0f, 1f)
        if (tier == MonthlyChestTier.BRONZE) {
            // Cute blue & orange dog ball resting beside opened bronze chest
            val ballCenter = Offset(chestLeft + chestW * 0.98f, baseBottom - u * 0.03f)
            val ballR = u * 0.062f
            drawCircle(color = Color(0xFF2F69B2).copy(alpha = extraAlpha), radius = ballR, center = ballCenter)
            drawArc(
                color = Color(0xFFF39C38).copy(alpha = extraAlpha),
                startAngle = -35f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(ballCenter.x - ballR * 0.72f, ballCenter.y - ballR * 0.72f),
                size = Size(ballR * 1.44f, ballR * 1.44f),
                style = Stroke(width = ballR * 0.40f, cap = StrokeCap.Round)
            )
            drawCircle(color = ChestInkOutline.copy(alpha = extraAlpha), radius = ballR, center = ballCenter, style = Stroke(strokeW * 0.75f))
        } else if (tier == MonthlyChestTier.GOLD) {
            // Golden ribbon bow on right front corner
            drawGoldRibbonBow(
                center = Offset(chestLeft + chestW * 0.90f, baseBottom - baseH * 0.25f),
                sizePx = u * 0.12f,
                strokeW = strokeW * 0.72f,
                alpha = extraAlpha
            )
        }
    }
}

private fun DrawScope.drawBonePadlock(
    center: Offset,
    width: Float,
    height: Float,
    shackleLift: Float,
    palette: ChestPalette,
    hasPinkGem: Boolean,
    strokeW: Float,
    alpha: Float
) {
    val shackleW = width * 0.44f
    val shackleH = height * 0.95f
    val shackleTop = center.y - height * 0.45f - shackleH * 0.72f - shackleLift

    // Padlock shackle arch
    val shacklePath = Path().apply {
        moveTo(center.x - shackleW * 0.5f, center.y - height * 0.20f)
        lineTo(center.x - shackleW * 0.5f, shackleTop + shackleW * 0.5f)
        arcTo(
            rect = Rect(center.x - shackleW * 0.5f, shackleTop, center.x + shackleW * 0.5f, shackleTop + shackleW),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false
        )
        lineTo(center.x + shackleW * 0.5f, center.y - height * 0.20f - shackleLift * 0.6f)
    }
    drawPath(shacklePath, color = ChestInkOutline.copy(alpha = alpha), style = Stroke(width = strokeW * 2.5f, cap = StrokeCap.Round))
    drawPath(shacklePath, color = palette.trimLight.copy(alpha = alpha), style = Stroke(width = strokeW * 1.35f, cap = StrokeCap.Round))

    // Cute Dog-Bone Lock Body!
    val knobR = height * 0.44f
    val barHalfW = width * 0.34f
    val barHalfH = height * 0.32f
    val knobs = listOf(
        Offset(center.x - barHalfW, center.y - knobR * 0.48f),
        Offset(center.x - barHalfW, center.y + knobR * 0.48f),
        Offset(center.x + barHalfW, center.y - knobR * 0.48f),
        Offset(center.x + barHalfW, center.y + knobR * 0.48f)
    )

    // Outline shadow pass first so the merged bone silhouette looks seamless
    knobs.forEach { k ->
        drawCircle(color = ChestInkOutline.copy(alpha = alpha), radius = knobR + strokeW * 0.9f, center = k)
    }
    drawRoundRect(
        color = ChestInkOutline.copy(alpha = alpha),
        topLeft = Offset(center.x - barHalfW - strokeW * 0.4f, center.y - barHalfH - strokeW * 0.9f),
        size = Size((barHalfW + strokeW * 0.4f) * 2f, (barHalfH + strokeW * 0.9f) * 2f),
        cornerRadius = CornerRadius(barHalfH, barHalfH)
    )

    // Fill pass
    val fillBrush = Brush.verticalGradient(
        colors = listOf(palette.lockLight, palette.lockDark),
        startY = center.y - height * 0.6f,
        endY = center.y + height * 0.6f
    )
    knobs.forEach { k ->
        drawCircle(brush = fillBrush, radius = knobR, center = k, alpha = alpha)
    }
    drawRoundRect(
        brush = fillBrush,
        topLeft = Offset(center.x - barHalfW, center.y - barHalfH),
        size = Size(barHalfW * 2f, barHalfH * 2f),
        cornerRadius = CornerRadius(barHalfH * 0.8f, barHalfH * 0.8f),
        alpha = alpha
    )

    // Center keyhole (or pink royal gem + keyhole on Gold chest!)
    if (hasPinkGem) {
        drawFacetedGem(
            center = center,
            radius = height * 0.29f,
            color = Color(0xFFF0508E),
            highlight = Color(0xFFFFC1DA),
            strokeW = strokeW * 0.62f,
            alpha = alpha
        )
    } else {
        val khR = height * 0.14f
        drawCircle(color = ChestInkOutline.copy(alpha = alpha), radius = khR, center = Offset(center.x, center.y - khR * 0.45f))
        val khSlot = Path().apply {
            moveTo(center.x - khR * 0.55f, center.y)
            lineTo(center.x + khR * 0.55f, center.y)
            lineTo(center.x + khR * 0.85f, center.y + khR * 1.45f)
            lineTo(center.x - khR * 0.85f, center.y + khR * 1.45f)
            close()
        }
        drawPath(khSlot, color = ChestInkOutline.copy(alpha = alpha))
    }
}

private fun DrawScope.drawChestTreasures(
    tier: MonthlyChestTier,
    cx: Float,
    mouthY: Float,
    chestW: Float,
    u: Float,
    openProgress: Float,
    glowPhase: Float,
    strokeW: Float,
    alpha: Float
) {
    val popY = (1f - openProgress) * u * 0.08f
    val baseY = mouthY - u * 0.02f + popY

    when (tier) {
        MonthlyChestTier.BRONZE -> {
            // Gold coins bed
            for (i in -2..2) {
                val coinCenter = Offset(cx + i * chestW * 0.13f, baseY + u * 0.01f)
                drawOval(
                    color = Color(0xFFF4BC3C).copy(alpha = alpha),
                    topLeft = Offset(coinCenter.x - u * 0.048f, coinCenter.y - u * 0.025f),
                    size = Size(u * 0.096f, u * 0.050f)
                )
                drawOval(
                    color = ChestInkOutline.copy(alpha = alpha),
                    topLeft = Offset(coinCenter.x - u * 0.048f, coinCenter.y - u * 0.025f),
                    size = Size(u * 0.096f, u * 0.050f),
                    style = Stroke(strokeW * 0.7f)
                )
            }
            // Two cream dog bones peeking out
            drawMiniDogBone(
                center = Offset(cx - chestW * 0.18f, baseY - u * 0.05f),
                length = chestW * 0.34f,
                rotationDeg = -28f,
                fillLight = Color(0xFFFFF1DC),
                fillDark = Color(0xFFE7C59B),
                strokeW = strokeW,
                alpha = alpha
            )
            drawMiniDogBone(
                center = Offset(cx + chestW * 0.17f, baseY - u * 0.04f),
                length = chestW * 0.30f,
                rotationDeg = 22f,
                fillLight = Color(0xFFFFF1DC),
                fillDark = Color(0xFFE7C59B),
                strokeW = strokeW,
                alpha = alpha
            )
            // Purple ball peeking inside right
            val ballCenter = Offset(cx + chestW * 0.05f, baseY - u * 0.025f)
            drawCircle(color = Color(0xFF8348B5).copy(alpha = alpha), radius = u * 0.052f, center = ballCenter)
            drawCircle(color = ChestInkOutline.copy(alpha = alpha), radius = u * 0.052f, center = ballCenter, style = Stroke(strokeW * 0.75f))
        }
        MonthlyChestTier.SILVER -> {
            // Silver coins
            for (i in -2..2) {
                val coinCenter = Offset(cx + i * chestW * 0.14f, baseY + u * 0.01f)
                drawOval(
                    color = Color(0xFFDCE7EE).copy(alpha = alpha),
                    topLeft = Offset(coinCenter.x - u * 0.046f, coinCenter.y - u * 0.024f),
                    size = Size(u * 0.092f, u * 0.048f)
                )
                drawOval(
                    color = ChestInkOutline.copy(alpha = alpha),
                    topLeft = Offset(coinCenter.x - u * 0.046f, coinCenter.y - u * 0.024f),
                    size = Size(u * 0.092f, u * 0.048f),
                    style = Stroke(strokeW * 0.7f)
                )
            }
            // Silver bone on left
            drawMiniDogBone(
                center = Offset(cx - chestW * 0.19f, baseY - u * 0.055f),
                length = chestW * 0.32f,
                rotationDeg = -32f,
                fillLight = Color(0xFFF6FBFF),
                fillDark = Color(0xFFB3C7D4),
                strokeW = strokeW,
                alpha = alpha
            )
            // Silver 5-point star in center
            drawFivePointStar(
                center = Offset(cx, baseY - u * 0.06f),
                outerR = u * 0.072f,
                innerR = u * 0.032f,
                fillColor = Color(0xFFE7F3F9),
                strokeW = strokeW * 0.85f,
                alpha = alpha
            )
            // Sparkling cyan diamond on right
            drawFacetedGem(
                center = Offset(cx + chestW * 0.18f, baseY - u * 0.045f),
                radius = u * 0.068f,
                color = Color(0xFF42DCFF),
                highlight = Color(0xFFD6FAFF),
                strokeW = strokeW * 0.85f,
                alpha = alpha
            )
            // Floating cyan sparkles
            val bob = sin(glowPhase * 2f * PI.toFloat()) * u * 0.018f
            drawFourPointSparkle(Offset(cx - chestW * 0.34f, baseY - u * 0.15f + bob), u * 0.036f, Color(0xFF7AF2FF), alpha)
            drawFourPointSparkle(Offset(cx + chestW * 0.32f, baseY - u * 0.18f - bob), u * 0.042f, Color(0xFFB8FAFF), alpha)
        }
        MonthlyChestTier.GOLD -> {
            // Piles of radiant gold coins
            for (i in -2..2) {
                val coinCenter = Offset(cx + i * chestW * 0.14f, baseY + u * 0.008f)
                drawOval(
                    color = Color(0xFFFFD447).copy(alpha = alpha),
                    topLeft = Offset(coinCenter.x - u * 0.052f, coinCenter.y - u * 0.026f),
                    size = Size(u * 0.104f, u * 0.052f)
                )
                drawOval(
                    color = ChestInkOutline.copy(alpha = alpha),
                    topLeft = Offset(coinCenter.x - u * 0.052f, coinCenter.y - u * 0.026f),
                    size = Size(u * 0.104f, u * 0.052f),
                    style = Stroke(strokeW * 0.7f)
                )
            }
            // Mini gold crown peeking on left
            drawMiniCrown(
                center = Offset(cx - chestW * 0.18f, baseY - u * 0.055f),
                crownW = chestW * 0.28f,
                crownH = u * 0.12f,
                strokeW = strokeW * 0.85f,
                alpha = alpha
            )
            // Jewel-studded golden bone peeking center-right
            drawMiniDogBone(
                center = Offset(cx + chestW * 0.06f, baseY - u * 0.065f),
                length = chestW * 0.35f,
                rotationDeg = -22f,
                fillLight = Color(0xFFFFF39A),
                fillDark = Color(0xFFE39E14),
                strokeW = strokeW,
                alpha = alpha
            )
            drawFacetedGem(
                center = Offset(cx + chestW * 0.06f, baseY - u * 0.065f),
                radius = u * 0.024f,
                color = Color(0xFFF04886),
                highlight = Color(0xFFFFC5DC),
                strokeW = strokeW * 0.55f,
                alpha = alpha
            )
            // Festive flying stars & confetti burst!
            val bob = sin(glowPhase * 2f * PI.toFloat()) * u * 0.02f
            drawFivePointStar(
                center = Offset(cx - chestW * 0.42f, baseY - u * 0.22f + bob),
                outerR = u * 0.048f,
                innerR = u * 0.022f,
                fillColor = Color(0xFFFFD84A),
                strokeW = strokeW * 0.65f,
                alpha = alpha
            )
            drawFivePointStar(
                center = Offset(cx + chestW * 0.42f, baseY - u * 0.20f - bob),
                outerR = u * 0.044f,
                innerR = u * 0.020f,
                fillColor = Color(0xFFFF88B7),
                strokeW = strokeW * 0.65f,
                alpha = alpha
            )
            drawFourPointSparkle(Offset(cx, baseY - u * 0.24f - bob), u * 0.045f, Color(0xFFFFF3A1), alpha)
            // Mini celebratory firework streamers
            drawLine(
                color = Color(0xFFFF8A2B).copy(alpha = 0.88f * alpha),
                start = Offset(cx - chestW * 0.26f, baseY - u * 0.12f),
                end = Offset(cx - chestW * 0.46f, baseY - u * 0.27f),
                strokeWidth = strokeW * 1.1f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFF47B5FF).copy(alpha = 0.88f * alpha),
                start = Offset(cx + chestW * 0.26f, baseY - u * 0.12f),
                end = Offset(cx + chestW * 0.46f, baseY - u * 0.26f),
                strokeWidth = strokeW * 1.1f,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * 동/은/금 각각 색과 모양이 완전히 다른 열쇠를 그립니다.
 * - BRONZE: 따뜻한 동색 + 뼈다귀 고리 손잡이 + 사각 2날
 * - SILVER: 차가운 은/청록색 + 4각 별 다이아 보석 손잡이 + 3단 계단형 날
 * - GOLD: 화려한 황금/루비색 + 날개 왕관 손잡이 + 3갈래 왕실 문양 날
 */
internal fun DrawScope.drawRewardKey(
    tier: MonthlyChestTier,
    center: Offset,
    length: Float,
    rotationDeg: Float = 0f,
    alpha: Float = 1f
) {
    val strokeW = (length * 0.046f).coerceIn(1.2f, 3.6f)
    val leftX = center.x - length * 0.46f
    val rightX = center.x + length * 0.46f
    val bowCenterX = leftX + length * 0.21f
    val shaftStartX = bowCenterX + length * 0.14f
    val shaftThickness = length * 0.115f

    val (lightCol, midCol, darkCol, gemCol, gemHi) = when (tier) {
        MonthlyChestTier.BRONZE -> KeyColors(
            Color(0xFFF0B47B), Color(0xFFCA7B42), Color(0xFF8B4B20),
            Color(0xFFFFE0B2), Color(0xFFFFFFFF)
        )
        MonthlyChestTier.SILVER -> KeyColors(
            Color(0xFFF5FAFD), Color(0xFFBFD4E0), Color(0xFF7291A3),
            Color(0xFF3CD9EB), Color(0xFFD2FCFF)
        )
        MonthlyChestTier.GOLD -> KeyColors(
            Color(0xFFFFF49E), Color(0xFFF8C32E), Color(0xFFBF7804),
            Color(0xFFF23F82), Color(0xFFFFC9DF)
        )
    }

    val metalBrush = Brush.verticalGradient(
        colors = listOf(lightCol, midCol, darkCol),
        startY = center.y - length * 0.26f,
        endY = center.y + length * 0.26f
    )

    withTransform({
        rotate(degrees = rotationDeg, pivot = center)
    }) {
        // 1. Key Shaft (몸통)
        drawRoundRect(
            brush = metalBrush,
            topLeft = Offset(shaftStartX, center.y - shaftThickness * 0.5f),
            size = Size(rightX - shaftStartX, shaftThickness),
            cornerRadius = CornerRadius(shaftThickness * 0.5f, shaftThickness * 0.5f),
            alpha = alpha
        )
        drawRoundRect(
            color = ChestInkOutline.copy(alpha = alpha),
            topLeft = Offset(shaftStartX, center.y - shaftThickness * 0.5f),
            size = Size(rightX - shaftStartX, shaftThickness),
            cornerRadius = CornerRadius(shaftThickness * 0.5f, shaftThickness * 0.5f),
            style = Stroke(width = strokeW)
        )

        // 2. Tier-Specific Key Teeth (열쇠 날 모양)
        val teethStartX = rightX - length * 0.27f
        val teethBaseY = center.y + shaftThickness * 0.35f
        when (tier) {
            MonthlyChestTier.BRONZE -> {
                // Classic sturdy 2-block teeth
                val t1 = Rect(teethStartX, teethBaseY, teethStartX + length * 0.085f, teethBaseY + length * 0.145f)
                val t2 = Rect(teethStartX + length * 0.125f, teethBaseY, teethStartX + length * 0.215f, teethBaseY + length * 0.185f)
                listOf(t1, t2).forEach { r ->
                    drawRoundRect(brush = metalBrush, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(strokeW, strokeW), alpha = alpha)
                    drawRoundRect(color = ChestInkOutline.copy(alpha = alpha), topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(strokeW, strokeW), style = Stroke(strokeW))
                }
            }
            MonthlyChestTier.SILVER -> {
                // Sleek collar ring + 3 stepped silver teeth
                val collarX = shaftStartX + length * 0.08f
                drawRoundRect(
                    brush = metalBrush,
                    topLeft = Offset(collarX, center.y - shaftThickness * 0.85f),
                    size = Size(length * 0.055f, shaftThickness * 1.70f),
                    cornerRadius = CornerRadius(strokeW, strokeW),
                    alpha = alpha
                )
                drawRoundRect(
                    color = ChestInkOutline.copy(alpha = alpha),
                    topLeft = Offset(collarX, center.y - shaftThickness * 0.85f),
                    size = Size(length * 0.055f, shaftThickness * 1.70f),
                    cornerRadius = CornerRadius(strokeW, strokeW),
                    style = Stroke(strokeW * 0.9f)
                )
                val toothPath = Path().apply {
                    moveTo(teethStartX - length * 0.02f, teethBaseY)
                    lineTo(teethStartX - length * 0.02f, teethBaseY + length * 0.18f)
                    lineTo(teethStartX + length * 0.05f, teethBaseY + length * 0.18f)
                    lineTo(teethStartX + length * 0.05f, teethBaseY + length * 0.10f)
                    lineTo(teethStartX + length * 0.11f, teethBaseY + length * 0.10f)
                    lineTo(teethStartX + length * 0.11f, teethBaseY + length * 0.20f)
                    lineTo(teethStartX + length * 0.18f, teethBaseY + length * 0.20f)
                    lineTo(teethStartX + length * 0.18f, teethBaseY + length * 0.13f)
                    lineTo(teethStartX + length * 0.24f, teethBaseY + length * 0.13f)
                    lineTo(teethStartX + length * 0.24f, teethBaseY)
                    close()
                }
                drawPath(toothPath, brush = metalBrush, alpha = alpha)
                drawPath(toothPath, color = ChestInkOutline.copy(alpha = alpha), style = Stroke(strokeW, join = StrokeJoin.Round))
            }
            MonthlyChestTier.GOLD -> {
                // Double ornate scepter collars + royal 3-prong fleur teeth
                for (i in 0..1) {
                    val cxCol = shaftStartX + length * (0.06f + i * 0.075f)
                    drawRoundRect(
                        brush = metalBrush,
                        topLeft = Offset(cxCol, center.y - shaftThickness * 0.95f),
                        size = Size(length * 0.045f, shaftThickness * 1.90f),
                        cornerRadius = CornerRadius(strokeW, strokeW),
                        alpha = alpha
                    )
                    drawRoundRect(
                        color = ChestInkOutline.copy(alpha = alpha),
                        topLeft = Offset(cxCol, center.y - shaftThickness * 0.95f),
                        size = Size(length * 0.045f, shaftThickness * 1.90f),
                        cornerRadius = CornerRadius(strokeW, strokeW),
                        style = Stroke(strokeW * 0.85f)
                    )
                }
                val royalTeeth = Path().apply {
                    moveTo(teethStartX - length * 0.02f, teethBaseY)
                    lineTo(teethStartX - length * 0.02f, teethBaseY + length * 0.17f)
                    lineTo(teethStartX + length * 0.04f, teethBaseY + length * 0.21f)
                    lineTo(teethStartX + length * 0.08f, teethBaseY + length * 0.11f)
                    lineTo(teethStartX + length * 0.12f, teethBaseY + length * 0.23f)
                    lineTo(teethStartX + length * 0.16f, teethBaseY + length * 0.11f)
                    lineTo(teethStartX + length * 0.20f, teethBaseY + length * 0.20f)
                    lineTo(teethStartX + length * 0.25f, teethBaseY + length * 0.15f)
                    lineTo(teethStartX + length * 0.25f, teethBaseY)
                    close()
                }
                drawPath(royalTeeth, brush = metalBrush, alpha = alpha)
                drawPath(royalTeeth, color = ChestInkOutline.copy(alpha = alpha), style = Stroke(strokeW, join = StrokeJoin.Round))
            }
        }

        // 3. Tier-Specific Key Bow (열쇠 손잡이 모양 — 각 등급별 완전 차별화!)
        val bowCenter = Offset(bowCenterX, center.y)
        when (tier) {
            MonthlyChestTier.BRONZE -> {
                // Cute vertical Dog-Bone Ring Bow!
                val knobR = length * 0.10f
                val dx = length * 0.08f
                val dy = length * 0.145f
                val knobs = listOf(
                    Offset(bowCenter.x - dx, bowCenter.y - dy),
                    Offset(bowCenter.x + dx, bowCenter.y - dy),
                    Offset(bowCenter.x - dx, bowCenter.y + dy),
                    Offset(bowCenter.x + dx, bowCenter.y + dy)
                )
                knobs.forEach { k -> drawCircle(color = ChestInkOutline.copy(alpha = alpha), radius = knobR + strokeW * 0.9f, center = k) }
                drawCircle(color = ChestInkOutline.copy(alpha = alpha), radius = length * 0.18f + strokeW * 0.9f, center = bowCenter)
                knobs.forEach { k -> drawCircle(brush = metalBrush, radius = knobR, center = k, alpha = alpha) }
                drawCircle(brush = metalBrush, radius = length * 0.18f, center = bowCenter, alpha = alpha)
                // Inner cutout hole
                drawCircle(color = Color(0xFF4B2B17).copy(alpha = 0.28f * alpha), radius = length * 0.085f, center = bowCenter)
                drawCircle(color = ChestInkOutline.copy(alpha = alpha), radius = length * 0.085f, center = bowCenter, style = Stroke(strokeW * 0.85f))
            }
            MonthlyChestTier.SILVER -> {
                // 4-Pointed Diamond Star Bow with Cyan Center Gem!
                val outerR = length * 0.26f
                val innerR = length * 0.135f
                val starPath = Path().apply {
                    for (i in 0 until 8) {
                        val r = if (i % 2 == 0) outerR else innerR
                        val angle = (i * 45.0) * PI / 180.0
                        val px = bowCenter.x + (cos(angle) * r).toFloat()
                        val py = bowCenter.y + (sin(angle) * r).toFloat()
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    close()
                }
                drawPath(starPath, brush = metalBrush, alpha = alpha)
                drawPath(starPath, color = ChestInkOutline.copy(alpha = alpha), style = Stroke(strokeW, join = StrokeJoin.Round))
                drawFacetedGem(
                    center = bowCenter,
                    radius = length * 0.095f,
                    color = gemCol,
                    highlight = gemHi,
                    strokeW = strokeW * 0.78f,
                    alpha = alpha
                )
            }
            MonthlyChestTier.GOLD -> {
                // Royal Crown-Winged Bow with Ruby Centerpiece!
                val r = length * 0.20f
                // Winged ornate heart/crest loop
                val crestPath = Path().apply {
                    moveTo(bowCenter.x - r * 1.15f, bowCenter.y - r * 0.95f)
                    lineTo(bowCenter.x - r * 0.45f, bowCenter.y - r * 0.45f)
                    lineTo(bowCenter.x, bowCenter.y - r * 1.30f)
                    lineTo(bowCenter.x + r * 0.45f, bowCenter.y - r * 0.45f)
                    lineTo(bowCenter.x + r * 1.15f, bowCenter.y - r * 0.95f)
                    lineTo(bowCenter.x + r * 0.95f, bowCenter.y + r * 0.65f)
                    quadraticTo(bowCenter.x, bowCenter.y + r * 1.25f, bowCenter.x - r * 0.95f, bowCenter.y + r * 0.65f)
                    close()
                }
                drawPath(crestPath, brush = metalBrush, alpha = alpha)
                drawPath(crestPath, color = ChestInkOutline.copy(alpha = alpha), style = Stroke(strokeW, join = StrokeJoin.Round))
                // Crown tip pearls
                listOf(
                    Offset(bowCenter.x - r * 1.15f, bowCenter.y - r * 0.95f),
                    Offset(bowCenter.x, bowCenter.y - r * 1.30f),
                    Offset(bowCenter.x + r * 1.15f, bowCenter.y - r * 0.95f)
                ).forEach { tip ->
                    drawCircle(color = lightCol.copy(alpha = alpha), radius = length * 0.042f, center = tip)
                    drawCircle(color = ChestInkOutline.copy(alpha = alpha), radius = length * 0.042f, center = tip, style = Stroke(strokeW * 0.7f))
                }
                // Ruby center gem
                drawFacetedGem(
                    center = Offset(bowCenter.x, bowCenter.y + r * 0.12f),
                    radius = length * 0.095f,
                    color = gemCol,
                    highlight = gemHi,
                    strokeW = strokeW * 0.78f,
                    alpha = alpha
                )
            }
        }
    }
}

private data class KeyColors(
    val light: Color,
    val mid: Color,
    val dark: Color,
    val gem: Color,
    val gemHighlight: Color
)

private fun DrawScope.drawMiniCrown(
    center: Offset,
    crownW: Float,
    crownH: Float,
    strokeW: Float,
    alpha: Float
) {
    val left = center.x - crownW * 0.5f
    val right = center.x + crownW * 0.5f
    val bottom = center.y + crownH * 0.35f
    val top = center.y - crownH * 0.65f
    val path = Path().apply {
        moveTo(left + crownW * 0.08f, bottom)
        lineTo(left, top + crownH * 0.28f)
        lineTo(left + crownW * 0.28f, top + crownH * 0.56f)
        lineTo(center.x, top)
        lineTo(right - crownW * 0.28f, top + crownH * 0.56f)
        lineTo(right, top + crownH * 0.28f)
        lineTo(right - crownW * 0.08f, bottom)
        close()
    }
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFFF28C), Color(0xFFF6BE2E), Color(0xFFC88008)),
            startY = top,
            endY = bottom
        ),
        alpha = alpha
    )
    drawPath(path, color = ChestInkOutline.copy(alpha = alpha), style = Stroke(width = strokeW, join = StrokeJoin.Round))
    // Pink centerpiece jewel on the crown
    drawFacetedGem(
        center = Offset(center.x, bottom - crownH * 0.26f),
        radius = crownH * 0.20f,
        color = Color(0xFFF0518D),
        highlight = Color(0xFFFFC7DD),
        strokeW = strokeW * 0.6f,
        alpha = alpha
    )
}

private fun DrawScope.drawMiniDogBone(
    center: Offset,
    length: Float,
    rotationDeg: Float,
    fillLight: Color,
    fillDark: Color,
    strokeW: Float,
    alpha: Float
) {
    val halfW = length * 0.34f
    val halfH = length * 0.14f
    val knobR = length * 0.16f
    withTransform({
        rotate(degrees = rotationDeg, pivot = center)
    }) {
        val knobs = listOf(
            Offset(center.x - halfW, center.y - knobR * 0.55f),
            Offset(center.x - halfW, center.y + knobR * 0.55f),
            Offset(center.x + halfW, center.y - knobR * 0.55f),
            Offset(center.x + halfW, center.y + knobR * 0.55f)
        )
        knobs.forEach { k -> drawCircle(color = ChestInkOutline.copy(alpha = alpha), radius = knobR + strokeW * 0.85f, center = k) }
        drawRoundRect(
            color = ChestInkOutline.copy(alpha = alpha),
            topLeft = Offset(center.x - halfW, center.y - halfH - strokeW * 0.85f),
            size = Size(halfW * 2f, (halfH + strokeW * 0.85f) * 2f),
            cornerRadius = CornerRadius(halfH, halfH)
        )
        val brush = Brush.verticalGradient(
            colors = listOf(fillLight, fillDark),
            startY = center.y - knobR * 1.5f,
            endY = center.y + knobR * 1.5f
        )
        knobs.forEach { k -> drawCircle(brush = brush, radius = knobR, center = k, alpha = alpha) }
        drawRoundRect(
            brush = brush,
            topLeft = Offset(center.x - halfW, center.y - halfH),
            size = Size(halfW * 2f, halfH * 2f),
            cornerRadius = CornerRadius(halfH, halfH),
            alpha = alpha
        )
    }
}

private fun DrawScope.drawFacetedGem(
    center: Offset,
    radius: Float,
    color: Color,
    highlight: Color,
    strokeW: Float,
    alpha: Float
) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius * 0.86f, center.y)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - radius * 0.86f, center.y)
        close()
    }
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(highlight, color),
            start = Offset(center.x - radius * 0.5f, center.y - radius),
            end = Offset(center.x + radius * 0.5f, center.y + radius)
        ),
        alpha = alpha
    )
    drawPath(path, color = ChestInkOutline.copy(alpha = alpha), style = Stroke(width = strokeW, join = StrokeJoin.Round))
}

private fun DrawScope.drawTinyPawPrint(center: Offset, radius: Float, color: Color) {
    drawOval(
        color = color,
        topLeft = Offset(center.x - radius * 0.55f, center.y - radius * 0.25f),
        size = Size(radius * 1.1f, radius * 0.85f)
    )
    listOf(
        Offset(-0.62f, -0.58f),
        Offset(-0.20f, -0.82f),
        Offset(0.22f, -0.82f),
        Offset(0.62f, -0.58f)
    ).forEach { (dx, dy) ->
        drawCircle(
            color = color,
            radius = radius * 0.24f,
            center = Offset(center.x + dx * radius, center.y + dy * radius)
        )
    }
}

private fun DrawScope.drawFivePointStar(
    center: Offset,
    outerR: Float,
    innerR: Float,
    fillColor: Color,
    strokeW: Float,
    alpha: Float
) {
    val path = Path().apply {
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outerR else innerR
            val angle = (-90.0 + i * 36.0) * PI / 180.0
            val px = center.x + (cos(angle) * r).toFloat()
            val py = center.y + (sin(angle) * r).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
    drawPath(path, color = fillColor.copy(alpha = alpha))
    drawPath(path, color = ChestInkOutline.copy(alpha = alpha), style = Stroke(width = strokeW, join = StrokeJoin.Round))
}

internal fun DrawScope.drawFourPointSparkle(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float = 1f
) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x, center.y, center.x + radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + radius)
        quadraticTo(center.x, center.y, center.x - radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - radius)
        close()
    }
    drawPath(path, color = color.copy(alpha = alpha))
}

private fun DrawScope.drawGoldRibbonBow(
    center: Offset,
    sizePx: Float,
    strokeW: Float,
    alpha: Float
) {
    val wingW = sizePx * 0.62f
    val wingH = sizePx * 0.42f
    val leftWing = Path().apply {
        moveTo(center.x, center.y)
        quadraticTo(center.x - wingW, center.y - wingH, center.x - wingW * 0.85f, center.y + wingH * 0.55f)
        close()
    }
    val rightWing = Path().apply {
        moveTo(center.x, center.y)
        quadraticTo(center.x + wingW, center.y - wingH, center.x + wingW * 0.85f, center.y + wingH * 0.55f)
        close()
    }
    val tail = Path().apply {
        moveTo(center.x - sizePx * 0.10f, center.y + sizePx * 0.08f)
        lineTo(center.x - sizePx * 0.36f, center.y + sizePx * 0.72f)
        lineTo(center.x - sizePx * 0.08f, center.y + sizePx * 0.56f)
        lineTo(center.x + sizePx * 0.24f, center.y + sizePx * 0.76f)
        lineTo(center.x + sizePx * 0.14f, center.y + sizePx * 0.08f)
        close()
    }
    val gold = Color(0xFFFFD64B).copy(alpha = alpha)
    drawPath(tail, color = Color(0xFFE7A922).copy(alpha = alpha))
    drawPath(tail, color = ChestInkOutline.copy(alpha = alpha), style = Stroke(strokeW, join = StrokeJoin.Round))
    drawPath(leftWing, color = gold)
    drawPath(leftWing, color = ChestInkOutline.copy(alpha = alpha), style = Stroke(strokeW, join = StrokeJoin.Round))
    drawPath(rightWing, color = gold)
    drawPath(rightWing, color = ChestInkOutline.copy(alpha = alpha), style = Stroke(strokeW, join = StrokeJoin.Round))
    drawCircle(color = Color(0xFFFFEE85).copy(alpha = alpha), radius = sizePx * 0.18f, center = center)
    drawCircle(color = ChestInkOutline.copy(alpha = alpha), radius = sizePx * 0.18f, center = center, style = Stroke(strokeW))
}
