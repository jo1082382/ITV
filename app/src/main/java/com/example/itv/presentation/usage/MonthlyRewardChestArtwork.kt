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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.example.itv.domain.reward.MonthlyChestTier
import kotlin.math.PI
import kotlin.math.abs
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

internal data class MysteryLightSpec(
    val silhouetteRadiusFactor: Float,
    val riseHeightFactor: Float,
    val coreColor: Color,
    val midColor: Color,
    val outerColor: Color,
    val rimColor: Color,
    val sparklePrimary: Color,
    val sparkleSecondary: Color,
    val primaryRayCount: Int,
    val secondaryRayCount: Int,
    val rayLengthFactor: Float,
    val rayHalfSpreadRad: Float,
    val rayAlpha: Float,
    val haloRadiusFactor: Float,
    val coronaRadiusFactor: Float,
    val haloAlpha: Float,
    val sparkleCount: Int,
    val moteCount: Int,
    val ringCount: Int
)

internal fun mysteryLightSpecFor(tier: MonthlyChestTier): MysteryLightSpec = when (tier) {
    MonthlyChestTier.BRONZE -> MysteryLightSpec(
        silhouetteRadiusFactor = 0.110f,
        riseHeightFactor = 0.125f,
        coreColor = Color(0xFFFFF3C6),
        midColor = Color(0xFFF2AA52),
        outerColor = Color(0xFFCC722A),
        rimColor = Color(0xFFFFE9A8),
        sparklePrimary = Color(0xFFFFE599),
        sparkleSecondary = Color(0xFFF6B55E),
        primaryRayCount = 6,
        secondaryRayCount = 0,
        rayLengthFactor = 0.30f,
        rayHalfSpreadRad = (9.5 * PI / 180.0).toFloat(),
        rayAlpha = 0.44f,
        haloRadiusFactor = 0.28f,
        coronaRadiusFactor = 0.19f,
        haloAlpha = 0.62f,
        sparkleCount = 4,
        moteCount = 4,
        ringCount = 0
    )
    MonthlyChestTier.SILVER -> MysteryLightSpec(
        silhouetteRadiusFactor = 0.148f,
        riseHeightFactor = 0.175f,
        coreColor = Color(0xFFFFFFFF),
        midColor = Color(0xFFA8F6FF),
        outerColor = Color(0xFF39CEFA),
        rimColor = Color(0xFFE9FEFF),
        sparklePrimary = Color(0xFFFFFFFF),
        sparkleSecondary = Color(0xFF7CF0FF),
        primaryRayCount = 10,
        secondaryRayCount = 5,
        rayLengthFactor = 0.48f,
        rayHalfSpreadRad = (10.5 * PI / 180.0).toFloat(),
        rayAlpha = 0.70f,
        haloRadiusFactor = 0.44f,
        coronaRadiusFactor = 0.27f,
        haloAlpha = 0.80f,
        sparkleCount = 9,
        moteCount = 8,
        ringCount = 1
    )
    MonthlyChestTier.GOLD -> MysteryLightSpec(
        silhouetteRadiusFactor = 0.192f,
        riseHeightFactor = 0.230f,
        coreColor = Color(0xFFFFFFFF),
        midColor = Color(0xFFFFF68F),
        outerColor = Color(0xFFFFB81A),
        rimColor = Color(0xFFFFFFFF),
        sparklePrimary = Color(0xFFFFFFFF),
        sparkleSecondary = Color(0xFFFFE654),
        primaryRayCount = 16,
        secondaryRayCount = 8,
        rayLengthFactor = 0.66f,
        rayHalfSpreadRad = (11.5 * PI / 180.0).toFloat(),
        rayAlpha = 0.92f,
        haloRadiusFactor = 0.60f,
        coronaRadiusFactor = 0.36f,
        haloAlpha = 0.96f,
        sparkleCount = 16,
        moteCount = 14,
        ringCount = 2
    )
}

/**
 * Reusable Path scratch container to keep 60fps chest opening draws free of per-frame Path allocations.
 */
internal class ChestDrawScratch {
    val primaryPath = Path()
    val secondaryPath = Path()
    val rayPath = Path()
    val silhouettePath = Path()
    val sparklePath = Path()
    val shacklePath = Path()
}

private val DefaultChestDrawScratch = ChestDrawScratch()

// Precomputed deterministic angular & radial tables for sparkles & rising light motes (zero per-frame List allocations)
private val SparkleAnglesDeg = floatArrayOf(
    -155f, -125f, -92f, -58f, -25f,
    -172f, -140f, -108f, -74f, -40f, -10f,
    18f, 162f, -118f, -82f, -48f
)
private val SparkleDistMultipliers = floatArrayOf(
    1.28f, 1.56f, 1.44f, 1.60f, 1.32f,
    1.78f, 1.94f, 1.86f, 1.98f, 1.82f, 1.72f,
    1.46f, 1.48f, 2.18f, 2.24f, 2.12f
)
private val SparkleSizeMultipliers = floatArrayOf(
    0.032f, 0.044f, 0.038f, 0.046f, 0.034f,
    0.028f, 0.040f, 0.048f, 0.042f, 0.036f, 0.030f,
    0.026f, 0.026f, 0.052f, 0.046f, 0.040f
)
private val MoteXOffsets = floatArrayOf(
    -0.28f, 0.24f, -0.12f, 0.36f, -0.40f, 0.10f, -0.22f,
    0.30f, -0.04f, 0.44f, -0.46f, 0.18f, -0.34f, 0.02f
)
private val MotePhaseOffsets = floatArrayOf(
    0.05f, 0.31f, 0.58f, 0.82f, 0.17f, 0.44f, 0.71f,
    0.93f, 0.24f, 0.51f, 0.77f, 0.11f, 0.38f, 0.65f
)

/**
 * Exact shared world-space geometry of a monthly reward chest rendered inside [bounds].
 * Both [drawMonthlyRewardChest] and [MonthlyChestOpeningOverlay] consume this single source of
 * truth so the key tip, keyhole slot, padlock shackle, floor plane, and emerging silhouette align
 * with 0.0px error at every resolution and aspect ratio.
 */
internal data class ChestGeometry(
    val bounds: Rect,
    val u: Float,
    val cx: Float,
    val chestLeft: Float,
    val chestRight: Float,
    val chestW: Float,
    val baseTop: Float,
    val baseBottom: Float,
    val baseH: Float,
    val lidH: Float,
    val rimH: Float,
    val lockCenter: Offset,
    val lockWidth: Float,
    val lockHeight: Float,
    val keyholeCenter: Offset,
    val keyholeRadius: Float
)

internal fun computeChestGeometry(bounds: Rect): ChestGeometry {
    val u = minOf(bounds.width, bounds.height)
    val cx = bounds.center.x
    val chestW = u * 0.68f
    val baseH = u * 0.30f
    val lidH = u * 0.21f
    val rimH = baseH * 0.20f
    val chestLeft = cx - chestW * 0.5f
    val baseBottom = bounds.top + bounds.height * 0.87f
    val baseTop = baseBottom - baseH
    val lockCenter = Offset(cx, baseTop + rimH * 1.18f)
    val lockWidth = chestW * 0.295f
    val lockHeight = u * 0.118f
    val keyholeRadius = lockHeight * 0.145f
    val keyholeCenter = Offset(lockCenter.x, lockCenter.y - keyholeRadius * 0.12f)
    return ChestGeometry(
        bounds = bounds,
        u = u,
        cx = cx,
        chestLeft = chestLeft,
        chestRight = chestLeft + chestW,
        chestW = chestW,
        baseTop = baseTop,
        baseBottom = baseBottom,
        baseH = baseH,
        lidH = lidH,
        rimH = rimH,
        lockCenter = lockCenter,
        lockWidth = lockWidth,
        lockHeight = lockHeight,
        keyholeCenter = keyholeCenter,
        keyholeRadius = keyholeRadius
    )
}

internal fun DrawScope.drawMonthlyRewardChest(
    tier: MonthlyChestTier,
    openProgress: Float,
    lockPopProgress: Float = 0f,
    glowPhase: Float = 0f,
    dimmed: Boolean = false,
    seamGlowAlpha: Float = 0f,
    lockShakeOffsetPx: Float = 0f,
    silhouetteRiseProgress: Float = openProgress,
    scratch: ChestDrawScratch = DefaultChestDrawScratch,
    bounds: Rect = Rect(0f, 0f, size.width, size.height),
    shackleUnlockProgress: Float = 0f
) {
    val palette = paletteForChest(tier)
    val lightSpec = mysteryLightSpecFor(tier)
    val geo = computeChestGeometry(bounds)
    val u = geo.u
    val cx = geo.cx
    val chestW = geo.chestW
    val baseH = geo.baseH
    val lidH = geo.lidH
    val rimH = geo.rimH
    val chestLeft = geo.chestLeft
    val baseBottom = geo.baseBottom
    val baseTop = geo.baseTop
    val strokeW = (u * 0.027f).coerceIn(1.3f, 4.2f)
    val contentAlpha = if (dimmed) 0.58f else 1f

    val clampedOpen = openProgress.coerceIn(0f, 1f)
    val clampedRise = silhouetteRiseProgress.coerceIn(0f, 1f)
    val clampedShackle = shackleUnlockProgress.coerceIn(0f, 1f)
    val pulse = 0.94f + 0.06f * sin(glowPhase * 2f * PI.toFloat())
    val floatBob = sin(glowPhase * 2f * PI.toFloat()) * u * 0.014f * clampedRise
    // Silhouette starts tucked down INSIDE the dark chest cavity at rise=0, then ascends smoothly!
    val silhouetteCenter = Offset(
        x = cx,
        y = baseTop + u * 0.045f - u * (0.055f + lightSpec.riseHeightFactor) * clampedRise + floatBob
    )

    // =========================================================================
    // LAYER 1 (DEEPEST BACK): Ground shadow & wide background volumetric rays
    // =========================================================================
    drawOval(
        color = Color(0xFF2B231E).copy(alpha = 0.18f * contentAlpha),
        topLeft = Offset(chestLeft - chestW * 0.07f, baseBottom - u * 0.042f),
        size = Size(chestW * 1.14f, u * 0.115f)
    )

    if (clampedOpen > 0.04f) {
        val lightStrength = (clampedOpen * 0.40f + clampedRise * 0.60f).coerceIn(0f, 1f) * contentAlpha
        drawMysteryBackRaysAndHalo(
            spec = lightSpec,
            origin = silhouetteCenter,
            u = u,
            strength = lightStrength,
            glowPhase = glowPhase,
            pulse = pulse,
            scratch = scratch
        )
    }

    // =========================================================================
    // LAYER 2 (BEHIND INTERIOR): Single continuous hinged lid — rear-swing phase (open >= 0.28)
    // =========================================================================
    val lidPivotSplit = 0.28f
    if (clampedOpen >= lidPivotSplit) {
        val backT = ((clampedOpen - lidPivotSplit) / (1f - lidPivotSplit)).coerceIn(0f, 1f)
        val openLidTop = baseTop - lidH * (0.14f + 1.12f * backT)
        val openLidBottom = baseTop + u * 0.015f
        val flareX = chestW * 0.032f * backT
        val lidInset = chestW * 0.025f * backT
        val openLidPath = scratch.primaryPath.apply {
            rewind()
            moveTo(chestLeft + lidInset, openLidBottom)
            lineTo(chestLeft - flareX, openLidTop + lidH * 0.30f * backT)
            quadraticTo(
                cx,
                openLidTop - lidH * 0.22f * backT,
                chestLeft + chestW + flareX,
                openLidTop + lidH * 0.30f * backT
            )
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

        // Inner underside of the swung-back lid bathed in reflected light from the mystery object!
        if (backT > 0.06f) {
            val innerCavity = scratch.secondaryPath.apply {
                rewind()
                moveTo(chestLeft + chestW * 0.07f, baseTop + u * 0.008f)
                lineTo(chestLeft + chestW * 0.04f, openLidTop + lidH * 0.44f * backT)
                quadraticTo(
                    cx,
                    openLidTop + lidH * 0.14f * backT,
                    chestLeft + chestW * 0.96f,
                    openLidTop + lidH * 0.44f * backT
                )
                lineTo(chestLeft + chestW * 0.93f, baseTop + u * 0.008f)
                close()
            }
            drawPath(
                path = innerCavity,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF3A2215).copy(alpha = 0.66f * backT * contentAlpha),
                        lightSpec.midColor.copy(alpha = 0.44f * backT * contentAlpha)
                    ),
                    startY = openLidTop + lidH * 0.16f * backT,
                    endY = baseTop + u * 0.015f
                )
            )
        }

        // Metallic lid rim band + crisp outline
        drawPath(
            path = openLidPath,
            color = palette.trimMid.copy(alpha = contentAlpha),
            style = Stroke(width = strokeW * 1.85f, join = StrokeJoin.Round)
        )
        drawPath(
            path = openLidPath,
            color = ChestInkOutline.copy(alpha = contentAlpha),
            style = Stroke(width = strokeW, join = StrokeJoin.Round)
        )

        // Gold chest crown riding smoothly atop the swung-back lid crest
        if (tier == MonthlyChestTier.GOLD) {
            drawMiniCrown(
                center = Offset(cx, openLidTop + lidH * 0.02f * backT),
                crownW = chestW * 0.29f,
                crownH = u * 0.13f * (0.42f + 0.58f * backT),
                strokeW = strokeW * 0.85f,
                alpha = contentAlpha
            )
        }
    }

    // =========================================================================
    // LAYER 3 (INSIDE CHEST CAVITY): Dark hollow interior + clipped light pillar + rising silhouette
    // Clipped strictly above the front rim (`baseTop + 0.022*u`) so the mystery object physically
    // rises FROM INSIDE the chest behind the front wooden/metallic wall!
    // =========================================================================
    if (clampedOpen > 0.02f) {
        val mouthOpenFactor = (clampedOpen / 0.35f).coerceIn(0f, 1f)
        // Dark hollow cavity rim
        drawRoundRect(
            color = Color(0xFF1F120C).copy(alpha = 0.94f * mouthOpenFactor * contentAlpha),
            topLeft = Offset(chestLeft + chestW * 0.045f, baseTop - u * 0.028f * mouthOpenFactor),
            size = Size(chestW * 0.91f, u * 0.082f),
            cornerRadius = CornerRadius(u * 0.028f, u * 0.028f)
        )

        clipRect(
            left = 0f,
            top = 0f,
            right = size.width,
            bottom = baseTop + u * 0.020f
        ) {
            // Upward volumetric light pillar pouring straight out of the open chest mouth
            val pillarTopY = silhouetteCenter.y - u * lightSpec.rayLengthFactor * 0.68f
            val pillarSpreadTop = chestW * (0.70f + lightSpec.haloRadiusFactor * 1.15f)
            val pillarPath = scratch.rayPath.apply {
                rewind()
                moveTo(chestLeft + chestW * 0.08f, baseTop + u * 0.022f)
                lineTo(cx - pillarSpreadTop * 0.5f, pillarTopY)
                lineTo(cx + pillarSpreadTop * 0.5f, pillarTopY)
                lineTo(chestLeft + chestW * 0.92f, baseTop + u * 0.022f)
                close()
            }
            drawPath(
                path = pillarPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        lightSpec.midColor.copy(alpha = (0.36f * clampedOpen * contentAlpha).coerceIn(0f, 1f)),
                        lightSpec.coreColor.copy(alpha = (0.80f * clampedOpen * contentAlpha).coerceIn(0f, 1f))
                    ),
                    startY = pillarTopY,
                    endY = baseTop + u * 0.022f
                )
            )

            // Enigmatic Black Silhouette Object + Tight Blazing Backlit Corona rising out of the interior!
            if (clampedRise > 0.01f) {
                drawMysterySilhouetteObject(
                    spec = lightSpec,
                    center = silhouetteCenter,
                    u = u,
                    riseProgress = clampedRise,
                    pulse = pulse,
                    contentAlpha = contentAlpha,
                    scratch = scratch
                )
            }
        }
    }

    // =========================================================================
    // LAYER 4 (CHEST FRONT BOX): Solid front wall enclosing the chest interior & silhouette base
    // =========================================================================
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
            val leftFiligree = scratch.primaryPath.apply {
                rewind()
                moveTo(chestLeft + chestW * 0.14f, baseBottom - baseH * 0.24f)
                quadraticTo(chestLeft + chestW * 0.28f, baseTop + baseH * 0.34f, cx - chestW * 0.12f, baseTop + baseH * 0.64f)
            }
            val rightFiligree = scratch.secondaryPath.apply {
                rewind()
                moveTo(chestLeft + chestW * 0.86f, baseBottom - baseH * 0.24f)
                quadraticTo(chestLeft + chestW * 0.72f, baseTop + baseH * 0.34f, cx + chestW * 0.12f, baseTop + baseH * 0.64f)
            }
            drawPath(leftFiligree, color = palette.trimLight.copy(alpha = 0.85f * contentAlpha), style = Stroke(width = strokeW * 1.1f, cap = StrokeCap.Round))
            drawPath(rightFiligree, color = palette.trimLight.copy(alpha = 0.85f * contentAlpha), style = Stroke(width = strokeW * 1.1f, cap = StrokeCap.Round))
        }
    }

    // Metallic rim & vertical corner bands on lower box
    val bandW = chestW * 0.11f
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

    // Corner rivets or gems
    val leftCornerPos = Offset(chestLeft + bandW * 0.52f, baseBottom - bottomTrimH * 0.58f)
    val rightCornerPos = Offset(chestLeft + chestW - bandW * 0.52f, baseBottom - bottomTrimH * 0.58f)
    if (tier == MonthlyChestTier.BRONZE) {
        drawCircle(color = palette.trimLight.copy(alpha = contentAlpha), radius = u * 0.017f, center = leftCornerPos)
        drawCircle(color = ChestInkOutline.copy(alpha = contentAlpha), radius = u * 0.017f, center = leftCornerPos, style = Stroke(strokeW * 0.65f))
        drawCircle(color = palette.trimLight.copy(alpha = contentAlpha), radius = u * 0.017f, center = rightCornerPos)
        drawCircle(color = ChestInkOutline.copy(alpha = contentAlpha), radius = u * 0.017f, center = rightCornerPos, style = Stroke(strokeW * 0.65f))
    } else {
        drawFacetedGem(leftCornerPos, u * 0.025f, palette.gemColor, palette.gemHighlight, strokeW * 0.65f, contentAlpha)
        drawFacetedGem(rightCornerPos, u * 0.025f, palette.gemColor, palette.gemHighlight, strokeW * 0.65f, contentAlpha)
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

    // Center metallic latch staple mounted directly on the front rim (physically holds the padlock!)
    val stapleW = chestW * 0.13f
    val stapleH = rimH * 1.18f
    val stapleLeft = cx - stapleW * 0.5f
    val stapleTop = baseTop - rimH * 0.14f
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(palette.trimLight, palette.trimDark),
            startY = stapleTop,
            endY = stapleTop + stapleH
        ),
        topLeft = Offset(stapleLeft, stapleTop),
        size = Size(stapleW, stapleH),
        cornerRadius = CornerRadius(u * 0.016f, u * 0.016f),
        alpha = contentAlpha
    )
    drawRoundRect(
        color = ChestInkOutline.copy(alpha = contentAlpha),
        topLeft = Offset(stapleLeft, stapleTop),
        size = Size(stapleW, stapleH),
        cornerRadius = CornerRadius(u * 0.016f, u * 0.016f),
        style = Stroke(width = strokeW * 0.78f)
    )

    // =========================================================================
    // LAYER 5 (SINGLE HINGED LID — FRONT LIFT PHASE): openProgress < 0.28
    // Smoothly foreshortens & lifts off the rim before seamlessly handing off to Layer 2!
    // =========================================================================
    if (clampedOpen < lidPivotSplit) {
        val liftT = (clampedOpen / lidPivotSplit).coerceIn(0f, 1f)
        val frontLipY = baseTop + u * 0.012f - liftT * u * 0.055f
        val effectiveLidH = lidH * (1f - liftT * 0.80f)
        val lidTop = frontLipY - effectiveLidH
        val lidPath = scratch.primaryPath.apply {
            rewind()
            moveTo(chestLeft - chestW * 0.02f, frontLipY)
            lineTo(chestLeft - chestW * 0.02f, lidTop + effectiveLidH * 0.45f)
            quadraticTo(cx, lidTop - effectiveLidH * 0.26f, chestLeft + chestW + chestW * 0.02f, lidTop + effectiveLidH * 0.45f)
            lineTo(chestLeft + chestW + chestW * 0.02f, frontLipY)
            close()
        }
        drawPath(
            path = lidPath,
            brush = Brush.verticalGradient(
                colors = listOf(palette.lidTop, palette.lidBottom),
                startY = lidTop,
                endY = frontLipY
            ),
            alpha = contentAlpha
        )
        val glossPath = scratch.secondaryPath.apply {
            rewind()
            moveTo(chestLeft + chestW * 0.14f, lidTop + effectiveLidH * 0.34f)
            quadraticTo(cx, lidTop + effectiveLidH * 0.06f, chestLeft + chestW * 0.86f, lidTop + effectiveLidH * 0.34f)
        }
        drawPath(
            path = glossPath,
            color = Color.White.copy(alpha = 0.24f * contentAlpha),
            style = Stroke(width = strokeW * 1.4f, cap = StrokeCap.Round)
        )
        val leftStrap = scratch.rayPath.apply {
            rewind()
            moveTo(chestLeft + bandW * 0.15f, frontLipY)
            lineTo(chestLeft + bandW * 0.15f, lidTop + effectiveLidH * 0.38f)
            quadraticTo(chestLeft + bandW * 0.65f, lidTop + effectiveLidH * 0.14f, chestLeft + bandW * 1.15f, lidTop + effectiveLidH * 0.16f)
            lineTo(chestLeft + bandW * 1.15f, frontLipY)
            close()
        }
        drawPath(leftStrap, color = palette.trimMid.copy(alpha = contentAlpha))
        drawPath(leftStrap, color = ChestInkOutline.copy(alpha = contentAlpha), style = Stroke(strokeW * 0.72f))

        val rightStrap = scratch.rayPath.apply {
            rewind()
            moveTo(chestLeft + chestW - bandW * 0.15f, frontLipY)
            lineTo(chestLeft + chestW - bandW * 0.15f, lidTop + effectiveLidH * 0.38f)
            quadraticTo(chestLeft + chestW - bandW * 0.65f, lidTop + effectiveLidH * 0.14f, chestLeft + chestW - bandW * 1.15f, lidTop + effectiveLidH * 0.16f)
            lineTo(chestLeft + chestW - bandW * 1.15f, frontLipY)
            close()
        }
        drawPath(rightStrap, color = palette.trimMid.copy(alpha = contentAlpha))
        drawPath(rightStrap, color = ChestInkOutline.copy(alpha = contentAlpha), style = Stroke(strokeW * 0.72f))

        drawPath(
            path = lidPath,
            color = ChestInkOutline.copy(alpha = contentAlpha),
            style = Stroke(width = strokeW, join = StrokeJoin.Round)
        )

        if (tier == MonthlyChestTier.GOLD) {
            drawMiniCrown(
                center = Offset(cx, lidTop + effectiveLidH * 0.01f),
                crownW = chestW * 0.30f,
                crownH = u * 0.135f * (1f - liftT * 0.58f),
                strokeW = strokeW * 0.85f,
                alpha = contentAlpha
            )
        }

        // Radiant light leaking out of the closed/cracking chest seam right before bursting open!
        val seamStrength = maxOf(seamGlowAlpha, liftT * 0.95f).coerceIn(0f, 1f)
        if (seamStrength > 0.01f) {
            val sAlpha = seamStrength * contentAlpha
            val seamGlowR = chestW * 0.56f
            val seamY = (frontLipY + baseTop) * 0.5f
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lightSpec.coreColor.copy(alpha = 0.94f * sAlpha),
                        lightSpec.midColor.copy(alpha = 0.48f * sAlpha),
                        Color.Transparent
                    ),
                    center = Offset(cx, seamY),
                    radius = seamGlowR
                ),
                topLeft = Offset(cx - seamGlowR, seamY - u * 0.06f),
                size = Size(seamGlowR * 2f, u * 0.13f)
            )
            drawLine(
                color = lightSpec.coreColor.copy(alpha = 0.96f * sAlpha),
                start = Offset(chestLeft + chestW * 0.04f, seamY),
                end = Offset(chestLeft + chestW * 0.96f, seamY),
                strokeWidth = strokeW * (1.7f + liftT * 1.4f),
                cap = StrokeCap.Round
            )
        }
    }

    // =========================================================================
    // LAYER 6 (FRONT LATCH): Bone-Shaped Padlock at exact geo.lockCenter / geo.keyholeCenter!
    // =========================================================================
    val lockAlpha = ((1f - lockPopProgress * 1.05f) * contentAlpha).coerceIn(0f, 1f)
    if (lockAlpha > 0.02f) {
        val popArcX = lockPopProgress * u * 0.14f
        val popArcY = -sin(lockPopProgress * PI.toFloat()) * u * 0.16f + lockPopProgress * lockPopProgress * u * 0.18f
        val lockCenterX = geo.lockCenter.x + lockShakeOffsetPx + popArcX
        val lockCenterY = geo.lockCenter.y + popArcY
        val lockRot = lockPopProgress * 32f + lockShakeOffsetPx * 0.62f
        val shackleOpenOffset = u * (
            clampedShackle * 0.036f +
                lockPopProgress * 0.026f +
                (if (clampedOpen > 0.05f && clampedShackle < 0.01f) 0.026f else 0f)
            )
        withTransform({
            rotate(degrees = lockRot, pivot = Offset(lockCenterX, lockCenterY))
        }) {
            drawBonePadlock(
                center = Offset(lockCenterX, lockCenterY),
                width = geo.lockWidth,
                height = geo.lockHeight,
                shackleLift = shackleOpenOffset,
                palette = palette,
                isRoyalGold = tier == MonthlyChestTier.GOLD,
                strokeW = strokeW * 0.88f,
                alpha = lockAlpha,
                scratch = scratch
            )
        }
    }

    // =========================================================================
    // LAYER 7 (FOREGROUND GLARE): Light spill over rim, aura rings, sparkles & rising motes
    // =========================================================================
    if (clampedRise > 0.02f) {
        drawMysteryForegroundLightEffects(
            spec = lightSpec,
            silhouetteCenter = silhouetteCenter,
            mouthY = baseTop,
            chestW = chestW,
            u = u,
            riseProgress = clampedRise,
            glowPhase = glowPhase,
            pulse = pulse,
            contentAlpha = contentAlpha,
            scratch = scratch
        )
    }
}

private fun DrawScope.drawMysteryBackRaysAndHalo(
    spec: MysteryLightSpec,
    origin: Offset,
    u: Float,
    strength: Float,
    glowPhase: Float,
    pulse: Float,
    scratch: ChestDrawScratch
) {
    // 1) Wide soft outer bloom halo
    val haloR = u * spec.haloRadiusFactor * (0.65f + 0.35f * strength) * pulse
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                spec.coreColor.copy(alpha = (spec.haloAlpha * strength).coerceIn(0f, 1f)),
                spec.midColor.copy(alpha = (spec.haloAlpha * 0.56f * strength).coerceIn(0f, 1f)),
                spec.outerColor.copy(alpha = (spec.haloAlpha * 0.22f * strength).coerceIn(0f, 1f)),
                Color.Transparent
            ),
            center = origin,
            radius = haloR.coerceAtLeast(1f)
        ),
        radius = haloR,
        center = origin
    )

    // 2) Primary volumetric rotating god-rays radiating outward from the mystery silhouette!
    val primaryLen = u * spec.rayLengthFactor * (0.45f + 0.55f * strength) * pulse
    val baseRot = glowPhase * 2f * PI.toFloat() * 0.25f
    val rayStep = (2.0 * PI / spec.primaryRayCount).toFloat()
    val rayBrush = Brush.radialGradient(
        colors = listOf(
            spec.coreColor.copy(alpha = (spec.rayAlpha * strength).coerceIn(0f, 1f)),
            spec.midColor.copy(alpha = (spec.rayAlpha * 0.52f * strength).coerceIn(0f, 1f)),
            spec.outerColor.copy(alpha = (spec.rayAlpha * 0.12f * strength).coerceIn(0f, 1f)),
            Color.Transparent
        ),
        center = origin,
        radius = primaryLen.coerceAtLeast(1f)
    )

    val rayPath = scratch.rayPath
    for (i in 0 until spec.primaryRayCount) {
        val angle = baseRot + i * rayStep
        val lengthMod = if (i % 2 == 0) 1.0f else 0.78f
        val rLen = primaryLen * lengthMod
        val spread = spec.rayHalfSpreadRad * (if (i % 2 == 0) 1.0f else 0.72f)
        val a1 = angle - spread
        val a2 = angle + spread
        val innerR = u * spec.silhouetteRadiusFactor * 0.42f
        rayPath.rewind()
        rayPath.moveTo(origin.x + cos(a1) * innerR, origin.y + sin(a1) * innerR)
        rayPath.lineTo(origin.x + cos(a1) * rLen, origin.y + sin(a1) * rLen)
        rayPath.lineTo(origin.x + cos(a2) * rLen, origin.y + sin(a2) * rLen)
        rayPath.lineTo(origin.x + cos(a2) * innerR, origin.y + sin(a2) * innerR)
        rayPath.close()
        drawPath(path = rayPath, brush = rayBrush)
    }

    // 3) Counter-rotating sharp secondary starlight rays for Silver & Gold!
    if (spec.secondaryRayCount > 0) {
        val secLen = primaryLen * 1.16f
        val secRot = -glowPhase * 2f * PI.toFloat() * 0.18f + 0.32f
        val secStep = (2.0 * PI / spec.secondaryRayCount).toFloat()
        val secBrush = Brush.radialGradient(
            colors = listOf(
                spec.coreColor.copy(alpha = (spec.rayAlpha * 0.72f * strength).coerceIn(0f, 1f)),
                spec.midColor.copy(alpha = (spec.rayAlpha * 0.28f * strength).coerceIn(0f, 1f)),
                Color.Transparent
            ),
            center = origin,
            radius = secLen.coerceAtLeast(1f)
        )
        val secSpread = spec.rayHalfSpreadRad * 0.48f
        for (i in 0 until spec.secondaryRayCount) {
            val angle = secRot + i * secStep
            val a1 = angle - secSpread
            val a2 = angle + secSpread
            rayPath.rewind()
            rayPath.moveTo(origin.x, origin.y)
            rayPath.lineTo(origin.x + cos(a1) * secLen, origin.y + sin(a1) * secLen)
            rayPath.lineTo(origin.x + cos(a2) * secLen, origin.y + sin(a2) * secLen)
            rayPath.close()
            drawPath(path = rayPath, brush = secBrush)
        }
    }
}

private fun DrawScope.drawMysterySilhouetteObject(
    spec: MysteryLightSpec,
    center: Offset,
    u: Float,
    riseProgress: Float,
    pulse: Float,
    contentAlpha: Float,
    scratch: ChestDrawScratch
) {
    val scaleFactor = (0.36f + 0.64f * riseProgress).coerceIn(0.2f, 1.08f)
    val r = u * spec.silhouetteRadiusFactor * scaleFactor
    val objAlpha = (riseProgress * 1.35f).coerceIn(0f, 1f) * contentAlpha

    // 1) Intense tight eclipse corona directly behind the black object so light pours off its contour!
    val coronaR = u * spec.coronaRadiusFactor * scaleFactor * pulse
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                spec.coreColor.copy(alpha = (0.98f * objAlpha).coerceIn(0f, 1f)),
                spec.midColor.copy(alpha = (0.72f * objAlpha).coerceIn(0f, 1f)),
                spec.outerColor.copy(alpha = (0.24f * objAlpha).coerceIn(0f, 1f)),
                Color.Transparent
            ),
            center = center,
            radius = coronaR.coerceAtLeast(1f)
        ),
        radius = coronaR,
        center = center
    )

    // 2) Sculpted enigmatic dark mystery silhouette object (zero concrete item details revealed!)
    val path = scratch.silhouettePath
    buildMysterySilhouettePath(path, center, r)

    // Soft outer glowing halo stroke wrapping tightly around the silhouette edge
    val outerRimW = (r * 0.24f).coerceIn(2.2f, 10f)
    drawPath(
        path = path,
        color = spec.midColor.copy(alpha = (0.68f * objAlpha).coerceIn(0f, 1f)),
        style = Stroke(width = outerRimW, join = StrokeJoin.Round, cap = StrokeCap.Round)
    )

    // Solid pitch-black silhouette interior (mysterious, unreadable contents!)
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF171522).copy(alpha = objAlpha),
                Color(0xFF0A0910).copy(alpha = objAlpha),
                Color(0xFF050408).copy(alpha = objAlpha)
            ),
            center = Offset(center.x, center.y - r * 0.16f),
            radius = (r * 1.25f).coerceAtLeast(1f)
        )
    )

    // Crisp luminous backlit rim hugging the dark silhouette contour
    val innerRimW = (r * 0.085f).coerceIn(1.2f, 3.8f)
    drawPath(
        path = path,
        color = spec.rimColor.copy(alpha = (0.94f * objAlpha).coerceIn(0f, 1f)),
        style = Stroke(width = innerRimW, join = StrokeJoin.Round, cap = StrokeCap.Round)
    )
}

/**
 * Enigmatic shrouded mystery artifact silhouette: symmetrical domed crest, soft organic shoulders,
 * subtly tapered lower body — unmistakably a mysterious solid treasure object without spoiling contents.
 */
private fun buildMysterySilhouettePath(path: Path, center: Offset, r: Float) {
    val cx = center.x
    val cy = center.y
    path.rewind()
    path.moveTo(cx, cy - r * 1.06f)
    // Upper-right mystical dome & shoulder
    path.cubicTo(
        cx + r * 0.42f, cy - r * 1.02f,
        cx + r * 0.88f, cy - r * 0.62f,
        cx + r * 0.86f, cy - r * 0.14f
    )
    // Right waist & rounded lower relic curve
    path.cubicTo(
        cx + r * 0.84f, cy + r * 0.28f,
        cx + r * 0.64f, cy + r * 0.72f,
        cx + r * 0.32f, cy + r * 0.90f
    )
    // Smooth bottom keel
    path.quadraticTo(
        cx, cy + r * 1.02f,
        cx - r * 0.32f, cy + r * 0.90f
    )
    // Left lower relic curve & waist
    path.cubicTo(
        cx - r * 0.64f, cy + r * 0.72f,
        cx - r * 0.84f, cy + r * 0.28f,
        cx - r * 0.86f, cy - r * 0.14f
    )
    // Upper-left shoulder & mystical dome back to apex
    path.cubicTo(
        cx - r * 0.88f, cy - r * 0.62f,
        cx - r * 0.42f, cy - r * 1.02f,
        cx, cy - r * 1.06f
    )
    path.close()
}

private fun DrawScope.drawMysteryForegroundLightEffects(
    spec: MysteryLightSpec,
    silhouetteCenter: Offset,
    mouthY: Float,
    chestW: Float,
    u: Float,
    riseProgress: Float,
    glowPhase: Float,
    pulse: Float,
    contentAlpha: Float,
    scratch: ChestDrawScratch
) {
    val fxAlpha = (riseProgress * contentAlpha).coerceIn(0f, 1f)

    // 1) Soft luminous rim spill over the chest opening
    val spillR = chestW * 0.52f * pulse
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                spec.coreColor.copy(alpha = (0.48f * fxAlpha).coerceIn(0f, 1f)),
                spec.midColor.copy(alpha = (0.22f * fxAlpha).coerceIn(0f, 1f)),
                Color.Transparent
            ),
            center = Offset(silhouetteCenter.x, mouthY),
            radius = spillR.coerceAtLeast(1f)
        ),
        topLeft = Offset(silhouetteCenter.x - spillR, mouthY - u * 0.08f),
        size = Size(spillR * 2f, u * 0.16f)
    )

    // 2) Expanding pulsing light aura rings emanating from the black silhouette (Silver: 1, Gold: 2)
    for (ringIdx in 0 until spec.ringCount) {
        val ringPhase = (glowPhase + ringIdx * 0.5f) % 1f
        val ringR = u * (spec.silhouetteRadiusFactor * 0.95f + ringPhase * spec.haloRadiusFactor * 0.72f)
        val ringAlpha = ((1f - ringPhase) * 0.58f * fxAlpha).coerceIn(0f, 1f)
        if (ringAlpha > 0.02f) {
            drawCircle(
                color = (if (ringIdx == 0) spec.coreColor else spec.midColor).copy(alpha = ringAlpha),
                radius = ringR,
                center = silhouetteCenter,
                style = Stroke(width = (u * 0.012f * (1f - ringPhase * 0.55f)).coerceAtLeast(1f))
            )
        }
    }

    // 3) Rising luminous light motes drifting up from the chest & silhouette
    val moteTotal = spec.moteCount.coerceAtMost(MoteXOffsets.size)
    for (i in 0 until moteTotal) {
        val p = (glowPhase + MotePhaseOffsets[i]) % 1f
        val mx = silhouetteCenter.x + MoteXOffsets[i] * chestW * (0.75f + 0.35f * p)
        val my = mouthY - p * u * (spec.riseHeightFactor + spec.rayLengthFactor * 0.58f)
        val mAlpha = (sin(p * PI.toFloat()) * 0.85f * fxAlpha).coerceIn(0f, 1f)
        val mRadius = u * (0.010f + (i % 3) * 0.0045f) * (1f - p * 0.35f)
        if (mAlpha > 0.02f) {
            drawCircle(
                color = (if (i % 2 == 0) spec.sparklePrimary else spec.sparkleSecondary).copy(alpha = mAlpha),
                radius = mRadius,
                center = Offset(mx, my)
            )
        }
    }

    // 4) Crisp twinkling 4-point star sparkles radiating around the black silhouette
    val sparkleTotal = spec.sparkleCount.coerceAtMost(SparkleAnglesDeg.size)
    val baseOrbitR = u * spec.silhouetteRadiusFactor
    for (i in 0 until sparkleTotal) {
        val twinkle = 0.38f + 0.62f * abs(sin((glowPhase * 2f * PI + i * 0.95).toFloat()))
        val angleRad = SparkleAnglesDeg[i] * (PI / 180.0)
        val dist = baseOrbitR * SparkleDistMultipliers[i] * (0.94f + 0.08f * twinkle)
        val sx = silhouetteCenter.x + (cos(angleRad) * dist).toFloat()
        val sy = silhouetteCenter.y + (sin(angleRad) * dist).toFloat()
        val sr = u * SparkleSizeMultipliers[i] * (0.62f + 0.52f * twinkle)
        val col = if (i % 2 == 0) spec.sparklePrimary else spec.sparkleSecondary
        drawFourPointSparkleFast(
            path = scratch.sparklePath,
            center = Offset(sx, sy),
            radius = sr,
            color = col,
            alpha = (twinkle * fxAlpha).coerceIn(0f, 1f)
        )
    }
}

private fun DrawScope.drawFourPointSparkleFast(
    path: Path,
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float
) {
    path.rewind()
    path.moveTo(center.x, center.y - radius)
    path.quadraticTo(center.x, center.y, center.x + radius, center.y)
    path.quadraticTo(center.x, center.y, center.x, center.y + radius)
    path.quadraticTo(center.x, center.y, center.x - radius, center.y)
    path.quadraticTo(center.x, center.y, center.x, center.y - radius)
    path.close()
    drawPath(path, color = color.copy(alpha = alpha))
}

private fun DrawScope.drawBonePadlock(
    center: Offset,
    width: Float,
    height: Float,
    shackleLift: Float,
    palette: ChestPalette,
    isRoyalGold: Boolean,
    strokeW: Float,
    alpha: Float,
    scratch: ChestDrawScratch
) {
    val shackleW = width * 0.44f
    val shackleH = height * 0.95f
    val shackleTop = center.y - height * 0.45f - shackleH * 0.72f - shackleLift

    // Padlock shackle arch
    val shacklePath = scratch.shacklePath.apply {
        rewind()
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

    // Cute Dog-Bone Lock Body
    val knobR = height * 0.44f
    val barHalfW = width * 0.34f
    val barHalfH = height * 0.32f
    val k1 = Offset(center.x - barHalfW, center.y - knobR * 0.48f)
    val k2 = Offset(center.x - barHalfW, center.y + knobR * 0.48f)
    val k3 = Offset(center.x + barHalfW, center.y - knobR * 0.48f)
    val k4 = Offset(center.x + barHalfW, center.y + knobR * 0.48f)

    val outlineR = knobR + strokeW * 0.9f
    val outlineCol = ChestInkOutline.copy(alpha = alpha)
    drawCircle(color = outlineCol, radius = outlineR, center = k1)
    drawCircle(color = outlineCol, radius = outlineR, center = k2)
    drawCircle(color = outlineCol, radius = outlineR, center = k3)
    drawCircle(color = outlineCol, radius = outlineR, center = k4)
    drawRoundRect(
        color = outlineCol,
        topLeft = Offset(center.x - barHalfW - strokeW * 0.4f, center.y - barHalfH - strokeW * 0.9f),
        size = Size((barHalfW + strokeW * 0.4f) * 2f, (barHalfH + strokeW * 0.9f) * 2f),
        cornerRadius = CornerRadius(barHalfH, barHalfH)
    )

    val fillBrush = Brush.verticalGradient(
        colors = listOf(palette.lockLight, palette.lockDark),
        startY = center.y - height * 0.6f,
        endY = center.y + height * 0.6f
    )
    drawCircle(brush = fillBrush, radius = knobR, center = k1, alpha = alpha)
    drawCircle(brush = fillBrush, radius = knobR, center = k2, alpha = alpha)
    drawCircle(brush = fillBrush, radius = knobR, center = k3, alpha = alpha)
    drawCircle(brush = fillBrush, radius = knobR, center = k4, alpha = alpha)
    drawRoundRect(
        brush = fillBrush,
        topLeft = Offset(center.x - barHalfW, center.y - barHalfH),
        size = Size(barHalfW * 2f, barHalfH * 2f),
        cornerRadius = CornerRadius(barHalfH * 0.8f, barHalfH * 0.8f),
        alpha = alpha
    )

    // Royal Gold pink ruby halo ring around the keyhole so ALL 3 tiers show a clear keyhole!
    if (isRoyalGold) {
        drawCircle(
            color = Color(0xFFF0508E).copy(alpha = alpha),
            radius = height * 0.27f,
            center = center
        )
        drawCircle(
            color = ChestInkOutline.copy(alpha = alpha),
            radius = height * 0.27f,
            center = center,
            style = Stroke(width = strokeW * 0.55f)
        )
    }

    // Center Keyhole (crisp metallic recess + clear dark slot aligned 0.0px to geo.keyholeCenter!)
    val khR = height * (if (isRoyalGold) 0.132f else 0.150f)
    val khCircleCenter = Offset(center.x, center.y - khR * 0.36f)
    drawCircle(
        color = palette.trimLight.copy(alpha = 0.65f * alpha),
        radius = khR * 1.24f,
        center = khCircleCenter
    )
    drawCircle(color = outlineCol, radius = khR, center = khCircleCenter)
    val khSlot = scratch.primaryPath.apply {
        rewind()
        moveTo(center.x - khR * 0.56f, center.y - khR * 0.05f)
        lineTo(center.x + khR * 0.56f, center.y - khR * 0.05f)
        lineTo(center.x + khR * 0.88f, center.y + khR * 1.48f)
        lineTo(center.x - khR * 0.88f, center.y + khR * 1.48f)
        close()
    }
    drawPath(khSlot, color = outlineCol)
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
