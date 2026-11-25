package com.example.itv.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.itv.infrastructure.theme.SeasonThemeMode
import com.example.itv.infrastructure.theme.UserThemeStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 스크롤 중이거나 오버레이/드로어가 열려 있을 때 계절 배경·전경 파티클 애니메이션을
 * 0fps로 일시정지시켜 메인 스레드와 GPU를 스크롤 렌더링에 100% 양보하는 컨트롤러.
 */
class SeasonalAnimationController {
    var scrollPaused by mutableStateOf(false)
    var overlayPaused by mutableStateOf(false)
    val isPaused: Boolean get() = scrollPaused || overlayPaused
}

val LocalSeasonalAnimationController = compositionLocalOf<SeasonalAnimationController?> { null }

/**
 * 풀스크린 계절 파티클을 60~120fps 무한 Choreographer 콜백 대신 저전력 20fps(50ms)로 스로틀링하고,
 * 스크롤 중 또는 Activity 비활성(ON_PAUSE/ON_STOP) 시 0fps로 즉시 정지시키는 공유 시계.
 * State<Long>을 반환하므로 Draw 단계(onDrawBehind)에서만 읽어 Compose 재구성을 전혀 유발하지 않습니다.
 */
@Composable
internal fun rememberThrottledSeasonalTimeMs(
    frameIntervalMs: Long = 50L
): State<Long> {
    val controller = LocalSeasonalAnimationController.current
    val paused = controller?.isPaused == true
    val lifecycleOwner = LocalLifecycleOwner.current
    val timeMs = remember { mutableLongStateOf(0L) }

    LaunchedEffect(lifecycleOwner, paused, frameIntervalMs) {
        if (paused) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastWall = android.os.SystemClock.uptimeMillis()
            while (isActive) {
                delay(frameIntervalMs)
                val now = android.os.SystemClock.uptimeMillis()
                val dt = (now - lastWall).coerceIn(0L, 250L)
                lastWall = now
                timeMs.longValue += dt
            }
        }
    }
    return timeMs
}

internal fun pingPongFraction(elapsedMs: Long, halfCycleMs: Long): Float {
    val cycle = (halfCycleMs * 2L).coerceAtLeast(2L)
    val phase = (elapsedMs % cycle).toFloat() / halfCycleMs.toFloat()
    return 1f - abs(phase - 1f)
}

private data class SeasonalParticle(
    val xFraction: Float,
    val yOffset: Float,
    val speedCycles: Int,
    val radiusDp: Float,
    val swayAmplitudeDp: Float,
    val swayPhase: Float,
    val isAccentShape: Boolean,
    val colorVariant: Int
)

private val WINTER_BULB_FRACTIONS = floatArrayOf(0.08f, 0.19f, 0.31f, 0.42f, 0.58f, 0.69f, 0.81f, 0.92f)

private fun buildParticles(): List<SeasonalParticle> {
    val count = 20
    // Deterministic pseudo-random distribution so particles never jump across recompositions.
    return List(count) { i ->
        val seed = (i * 73 + 19) % 97
        val seed2 = (i * 41 + 11) % 89
        SeasonalParticle(
            xFraction = ((i + 0.5f) / count + ((seed % 11) - 5) * 0.008f).coerceIn(0.03f, 0.97f),
            yOffset = (seed2 / 89f),
            speedCycles = if (i % 3 == 0) 2 else 1,
            radiusDp = 2.4f + (seed % 5) * 0.85f,
            swayAmplitudeDp = 6f + (seed2 % 4) * 4.5f,
            swayPhase = (seed / 97f) * (2f * PI.toFloat()),
            isAccentShape = (i % 4 == 0),
            colorVariant = i % 3
        )
    }
}

private class CachedPineTree(
    val trunkTopLeft: Offset,
    val trunkSize: Size,
    val lowerTier: Path,
    val upperTier: Path,
    val ornament1: Offset,
    val ornament2: Offset
)

private fun buildCachedPineTree(
    baseCenter: Offset,
    heightPx: Float,
    widthPx: Float
): CachedPineTree {
    val topY = baseCenter.y - heightPx
    val lowerTier = Path().apply {
        moveTo(baseCenter.x, topY + heightPx * 0.28f)
        lineTo(baseCenter.x - widthPx * 0.52f, baseCenter.y - heightPx * 0.14f)
        lineTo(baseCenter.x + widthPx * 0.52f, baseCenter.y - heightPx * 0.14f)
        close()
    }
    val upperTier = Path().apply {
        moveTo(baseCenter.x, topY)
        lineTo(baseCenter.x - widthPx * 0.38f, topY + heightPx * 0.50f)
        lineTo(baseCenter.x + widthPx * 0.38f, topY + heightPx * 0.50f)
        close()
    }
    return CachedPineTree(
        trunkTopLeft = Offset(baseCenter.x - widthPx * 0.12f, baseCenter.y - heightPx * 0.18f),
        trunkSize = Size(widthPx * 0.24f, heightPx * 0.22f),
        lowerTier = lowerTier,
        upperTier = upperTier,
        ornament1 = Offset(baseCenter.x - widthPx * 0.13f, topY + heightPx * 0.42f),
        ornament2 = Offset(baseCenter.x + widthPx * 0.15f, topY + heightPx * 0.65f)
    )
}

/**
 * Concept 1 Dot-Grid Stationery Canvas enhanced with a subtle, hand-inked seasonal atmosphere.
 * Performance architecture:
 * - Outer Box renders ONLY static geometry (1-call tiled BitmapShader dot grid, ambient gradient,
 *   static hills, pine trees, garland string & holly) inside drawWithCache -> 0 invalidations/sec!
 * - Inner sibling layer renders ONLY lightweight drifting particles & fairy-light glows with
 *   zero Path/Brush/List allocations per frame!
 */
@Composable
fun ItvSeasonalBackground(
    modifier: Modifier = Modifier,
    seasonMode: SeasonThemeMode = UserThemeStore.rememberSeasonMode(),
    dotSpacing: Dp = 18.dp,
    dotRadius: Dp = 1.25.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val activeSeason = remember(seasonMode) { UserThemeStore.resolveActiveSeason(seasonMode) }
    val isDark = LocalDarkTheme.current
    val bgColor = MaterialTheme.colorScheme.background
    val dotColor = ItvDotGridColor.copy(alpha = 0.72f)
    val inkColor = ItvInkBorder

    Box(
        modifier = modifier.background(bgColor)
    ) {
        // Dedicated hardware RenderNode for static scenery (recorded once; never dirtied by scroll or particle ticks)
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer()
                .drawWithCache {
                val w = size.width
                val h = size.height

                // 1. Cached top seasonal ambient gradient brush
                val topTint = when (activeSeason) {
                    SeasonThemeMode.WINTER -> if (isDark) Color(0xFF10222A) else Color(0xFFDBE5EC)
                    SeasonThemeMode.SPRING -> if (isDark) Color(0xFF1D1419) else Color(0xFFF3E2E6)
                    SeasonThemeMode.SUMMER -> if (isDark) Color(0xFF0E2028) else Color(0xFFD9EAF3)
                    SeasonThemeMode.AUTUMN -> if (isDark) Color(0xFF1F160E) else Color(0xFFF0DFCA)
                    SeasonThemeMode.OFF, SeasonThemeMode.AUTO -> Color.Transparent
                }
                val topBrush = if (topTint != Color.Transparent) {
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to topTint.copy(alpha = if (isDark) 0.72f else 0.52f),
                            0.36f to bgColor.copy(alpha = 0f),
                            1.0f to Color.Transparent
                        )
                    )
                } else {
                    null
                }

                // 2. Single-draw-call repeatable BitmapShader for Concept 1 dot grid
                val dotBrush = buildDotGridBrush(
                    stepPx = dotSpacing.toPx(),
                    radiusPx = dotRadius.toPx(),
                    dotColor = dotColor
                )

                // 3. Cached static winter scenery (garland cord, holly clusters, snowdrifts, mini pine trees)
                val winterGarlandPath: Path?
                val winterBackHill: Path?
                val winterFrontHill: Path?
                val winterTrees: List<CachedPineTree>
                val hollyPoints: List<Offset>

                if (activeSeason == SeasonThemeMode.WINTER) {
                    val y16 = 16.dp.toPx()
                    val y20 = 20.dp.toPx()
                    val y44 = 44.dp.toPx()
                    winterGarlandPath = Path().apply {
                        moveTo(-10f, y16)
                        quadraticTo(w * 0.25f, y44, w * 0.50f, y20)
                        quadraticTo(w * 0.75f, y44, w + 10f, y16)
                    }
                    val hollys = ArrayList<Offset>(3)
                    for (idx in WINTER_BULB_FRACTIONS.indices) {
                        if (idx % 3 == 1) {
                            val frac = WINTER_BULB_FRACTIONS[idx]
                            val localT = if (frac <= 0.5f) frac / 0.5f else (frac - 0.5f) / 0.5f
                            val p0y = if (frac <= 0.5f) y16 else y20
                            val p2y = if (frac <= 0.5f) y20 else y16
                            val inv = 1f - localT
                            val y = inv * inv * p0y + 2f * inv * localT * y44 + localT * localT * p2y
                            hollys.add(Offset(w * frac, y))
                        }
                    }
                    hollyPoints = hollys

                    winterBackHill = Path().apply {
                        moveTo(0f, h)
                        lineTo(0f, h - 88.dp.toPx())
                        quadraticTo(w * 0.28f, h - 122.dp.toPx(), w * 0.58f, h - 92.dp.toPx())
                        quadraticTo(w * 0.82f, h - 72.dp.toPx(), w, h - 104.dp.toPx())
                        lineTo(w, h)
                        close()
                    }
                    winterFrontHill = Path().apply {
                        moveTo(0f, h)
                        lineTo(0f, h - 56.dp.toPx())
                        quadraticTo(w * 0.35f, h - 74.dp.toPx(), w * 0.68f, h - 54.dp.toPx())
                        quadraticTo(w * 0.88f, h - 42.dp.toPx(), w, h - 62.dp.toPx())
                        lineTo(w, h)
                        close()
                    }
                    winterTrees = listOf(
                        buildCachedPineTree(Offset(w * 0.08f, h - 86.dp.toPx()), 46.dp.toPx(), 28.dp.toPx()),
                        buildCachedPineTree(Offset(w * 0.16f, h - 82.dp.toPx()), 32.dp.toPx(), 20.dp.toPx()),
                        buildCachedPineTree(Offset(w * 0.91f, h - 88.dp.toPx()), 42.dp.toPx(), 26.dp.toPx())
                    )
                } else {
                    winterGarlandPath = null
                    winterBackHill = null
                    winterFrontHill = null
                    winterTrees = emptyList()
                    hollyPoints = emptyList()
                }

                // 4. Cached static summer sun glow & diagonal ray endpoints
                val summerSunPos = Offset(w * 0.92f, 38.dp.toPx())
                val summerSunRadius = w * 0.52f
                val summerSunColor = if (isDark) Color(0xFFFFDF73) else Color(0xFFF4B034)
                val summerSunBrush = if (activeSeason == SeasonThemeMode.SUMMER && summerSunRadius > 1f) {
                    Brush.radialGradient(
                        colors = listOf(
                            summerSunColor.copy(alpha = if (isDark) 0.30f else 0.36f),
                            Color.Transparent
                        ),
                        center = summerSunPos,
                        radius = summerSunRadius
                    )
                } else {
                    null
                }
                val summerRayEnds = if (activeSeason == SeasonThemeMode.SUMMER) {
                    List(5) { i ->
                        val angleRad = (112.0 + i * 16.0) * PI / 180.0
                        Offset(
                            summerSunPos.x + (cos(angleRad) * h * 0.72f).toFloat(),
                            summerSunPos.y + (sin(angleRad) * h * 0.72f).toFloat()
                        )
                    }
                } else {
                    emptyList()
                }

                // Cached strokes & palette colors
                val garlandStroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                val hillStroke = Stroke(width = 1.3.dp.toPx())
                val treeStroke = Stroke(width = 1.4.dp.toPx(), join = StrokeJoin.Round)
                val garlandColor = if (isDark) Color(0xFF5D7A68).copy(alpha = 0.65f) else inkColor.copy(alpha = 0.45f)
                val hollyGreen = (if (isDark) Color(0xFF3E9C68) else Color(0xFF28754B)).copy(alpha = 0.85f)
                val berryRed = if (isDark) Color(0xFFE05D5D) else Color(0xFFC84343)
                val hillBackCol = if (isDark) Color(0xFF14211C) else Color(0xFFF2ECE2)
                val hillFrontCol = if (isDark) Color(0xFF192923) else Color(0xFFF8F5EF)
                val hillStrokeCol = if (isDark) Color(0xFF334B3E) else inkColor.copy(alpha = 0.35f)
                val treeFill = if (isDark) Color(0xFF286043) else Color(0xFF337D54)
                val treeStrokeCol = if (isDark) Color(0xFF4F7860) else inkColor.copy(alpha = 0.72f)
                val trunkColor = if (isDark) Color(0xFF4D3B2C) else Color(0xFF6B4F3B)
                val ornRed = if (isDark) Color(0xFFE05D5D) else Color(0xFFD64545)
                val ornGold = Color(0xFFF6C851)
                val summerRayColor = summerSunColor.copy(alpha = if (isDark) 0.10f else 0.15f)
                val summerRayWidth = 18.dp.toPx()

                val hollyW = 7.dp.toPx()
                val hollyH = 4.dp.toPx()
                val hollyOffY = 2.dp.toPx()
                val berryR = 2.6.dp.toPx()
                val ornR = 2.dp.toPx()

                onDrawBehind {
                    if (topBrush != null) {
                        drawRect(brush = topBrush)
                    }
                    if (dotBrush != null) {
                        drawRect(brush = dotBrush)
                    }

                    if (activeSeason == SeasonThemeMode.WINTER) {
                        winterGarlandPath?.let { drawPath(it, color = garlandColor, style = garlandStroke) }
                        hollyPoints.fastForEach { pt ->
                            drawOval(hollyGreen, Offset(pt.x - hollyW, pt.y - hollyOffY), Size(hollyW, hollyH))
                            drawOval(hollyGreen, Offset(pt.x, pt.y - hollyOffY), Size(hollyW, hollyH))
                            drawCircle(berryRed, berryR, Offset(pt.x, pt.y + hollyOffY))
                        }
                        winterBackHill?.let {
                            drawPath(it, color = hillBackCol)
                            drawPath(it, color = hillStrokeCol, style = hillStroke)
                        }
                        winterTrees.fastForEach { t ->
                            drawRect(trunkColor, t.trunkTopLeft, t.trunkSize)
                            drawPath(t.lowerTier, color = treeFill)
                            drawPath(t.lowerTier, color = treeStrokeCol, style = treeStroke)
                            drawPath(t.upperTier, color = treeFill)
                            drawPath(t.upperTier, color = treeStrokeCol, style = treeStroke)
                            drawCircle(ornRed, ornR, t.ornament1)
                            drawCircle(ornGold, ornR, t.ornament2)
                        }
                        winterFrontHill?.let {
                            drawPath(it, color = hillFrontCol)
                            drawPath(it, color = hillStrokeCol, style = hillStroke)
                        }
                    } else if (activeSeason == SeasonThemeMode.SUMMER) {
                        if (summerSunBrush != null) {
                            drawCircle(brush = summerSunBrush, radius = summerSunRadius, center = summerSunPos)
                        }
                        summerRayEnds.fastForEach { end ->
                            drawLine(summerRayColor, summerSunPos, end, summerRayWidth, StrokeCap.Round)
                        }
                    }
                }
            }
        )
        if (activeSeason != SeasonThemeMode.OFF) {
            SeasonalAnimatedParticlesLayer(
                activeSeason = activeSeason,
                isDark = isDark,
                inkColor = inkColor,
                modifier = Modifier.matchParentSize()
            )
        }
        content()
    }
}

@Composable
private fun SeasonalAnimatedParticlesLayer(
    activeSeason: SeasonThemeMode,
    isDark: Boolean,
    inkColor: Color,
    modifier: Modifier = Modifier
) {
    val particles = remember { buildParticles() }
    val timeMsState = rememberThrottledSeasonalTimeMs(frameIntervalMs = 50L)

    // Dedicated hardware RenderNode isolates 20fps (0fps during scroll) particle ticks from Scaffold/LazyColumn
    Box(
        modifier = modifier
            .graphicsLayer()
            .drawWithCache {
            val w = size.width
            val h = size.height

            // Precompute all pixel sizes, strokes, and colors once per resize
            val dp1 = 1.dp.toPx()
            val snowDotStroke = Stroke(width = 0.9.dp.toPx())
            val bulbRimStroke = Stroke(width = dp1)
            val petalRimStroke = Stroke(width = 0.8.dp.toPx())
            val waveStroke = Stroke(width = 1.2.dp.toPx())

            // Precompute winter bulb positions along the catenary curve
            val y16 = 16.dp.toPx()
            val y20 = 20.dp.toPx()
            val y44 = 44.dp.toPx()
            val bulbOffsetY = 4.dp.toPx()
            val bulbXs = FloatArray(WINTER_BULB_FRACTIONS.size)
            val bulbYs = FloatArray(WINTER_BULB_FRACTIONS.size)
            for (idx in WINTER_BULB_FRACTIONS.indices) {
                val frac = WINTER_BULB_FRACTIONS[idx]
                val localT = if (frac <= 0.5f) frac / 0.5f else (frac - 0.5f) / 0.5f
                val p0y = if (frac <= 0.5f) y16 else y20
                val p2y = if (frac <= 0.5f) y20 else y16
                val inv = 1f - localT
                bulbXs[idx] = w * frac
                bulbYs[idx] = inv * inv * p0y + 2f * inv * localT * y44 + localT * localT * p2y + bulbOffsetY
            }

            // Precompute star positions on the two tall pine trees
            val star1 = Offset(w * 0.08f, h - 86.dp.toPx() - 46.dp.toPx() - 3.dp.toPx())
            val star2 = Offset(w * 0.91f, h - 88.dp.toPx() - 42.dp.toPx() - 3.dp.toPx())

            // Reusable Path for Summer wave (uses rewind() per frame -> zero buffer reallocations!)
            val wavePath = Path()

            // Precomputed colors
            val berryRed = if (isDark) Color(0xFFE05D5D) else Color(0xFFC84343)
            val fairyGold = if (isDark) Color(0xFFFFDF73) else Color(0xFFF0B83A)
            val bulbRimColor = inkColor.copy(alpha = if (isDark) 0.35f else 0.45f)
            val snowFill = (if (isDark) Color(0xFFEAF5FF) else Color(0xFFFFFFFF)).copy(alpha = if (isDark) 0.78f else 0.90f)
            val snowCenter = (if (isDark) Color(0xFFEAF5FF) else Color(0xFFFFFFFF)).copy(alpha = 0.90f)
            val snowRim = if (isDark) Color(0xFF9AC2DC).copy(alpha = 0.45f) else Color(0xFF8C9FAE).copy(alpha = 0.42f)
            val crystalColor = if (isDark) Color(0xFFD9EEFF).copy(alpha = 0.62f) else Color(0xFF8EA3B5).copy(alpha = 0.52f)
            val starGlowBase = Color(0xFFFFDE6B)
            val starCore = Color(0xFFF7C948)

            val petalColors = if (isDark) {
                arrayOf(
                    Color(0xFFD989A1).copy(alpha = 0.62f),
                    Color(0xFFE8B0C2).copy(alpha = 0.62f),
                    Color(0xFF9AC9A6).copy(alpha = 0.62f)
                )
            } else {
                arrayOf(
                    Color(0xFFF2A7BC).copy(alpha = 0.78f),
                    Color(0xFFF8C6D4).copy(alpha = 0.78f),
                    Color(0xFFB5DCBF).copy(alpha = 0.78f)
                )
            }
            val petalRimColor = inkColor.copy(alpha = if (isDark) 0.25f else 0.28f)

            val leafColors = if (isDark) {
                arrayOf(
                    Color(0xFFD97D45).copy(alpha = 0.65f),
                    Color(0xFFE0B14C).copy(alpha = 0.65f),
                    Color(0xFFC45547).copy(alpha = 0.65f)
                )
            } else {
                arrayOf(
                    Color(0xFFD97438).copy(alpha = 0.75f),
                    Color(0xFFE3A63D).copy(alpha = 0.75f),
                    Color(0xFFB8493A).copy(alpha = 0.75f)
                )
            }
            val leafVeinColor = inkColor.copy(alpha = 0.4f)

            val sparkleColor = if (isDark) Color(0xFF7FD6F7) else Color(0xFF3FA0C7)
            val sunColor = if (isDark) Color(0xFFFFDF73) else Color(0xFFF4B034)
            val waveFill = (if (isDark) Color(0xFF132833) else Color(0xFFD6E8F0)).copy(alpha = 0.75f)
            val waveRim = inkColor.copy(alpha = 0.25f)

            // Precomputed snowflake 60-degree trig unit vectors (cos/sin for 0, 60, 120 deg)
            val cos60 = 0.5f
            val sin60 = 0.8660254f

            val twoPi = (2.0 * PI).toFloat()
            val crystalStrokeW = 1.25.dp.toPx()
            val crystalDotR = 1.6.dp.toPx()
            val bulbCoreR = 3.2.dp.toPx()
            val starCoreR = 2.6.dp.toPx()
            val leafVeinW = 0.9.dp.toPx()
            val sparkleStrokeW = 1.4.dp.toPx()
            val hPad30 = 30.dp.toPx()
            val hPad15 = 15.dp.toPx()

            onDrawBehind {
                val elapsed = timeMsState.value
                val drift = (elapsed % 22_000L) / 22_000f
                val twinkle = pingPongFraction(elapsed, 3_000L)

                when (activeSeason) {
                    SeasonThemeMode.WINTER -> {
                        // A. Twinkling fairy bulbs along cached garland curve
                        for (idx in WINTER_BULB_FRACTIONS.indices) {
                            if (idx % 3 == 1) continue // holly slot painted on static layer
                            val phase = if (idx % 2 == 0) twinkle else (1f - twinkle)
                            val bulbColor = if (idx % 4 == 0) berryRed else fairyGold
                            val glowAlpha = (if (isDark) 0.24f else 0.18f) + phase * (if (isDark) 0.28f else 0.16f)
                            val pt = Offset(bulbXs[idx], bulbYs[idx])
                            drawCircle(bulbColor.copy(alpha = glowAlpha), (7.5f + phase * 3.5f) * dp1, pt)
                            drawCircle(bulbColor.copy(alpha = 0.88f), bulbCoreR, pt)
                            drawCircle(bulbRimColor, bulbCoreR, pt, style = bulbRimStroke)
                        }

                        // B. Drifting snowdots & 6-point hand-inked snow crystals
                        particles.fastForEach { p ->
                            val progress = (p.yOffset + drift * p.speedCycles) % 1f
                            val py = progress * (h + hPad30) - hPad15
                            val sway = sin((progress * twoPi) + p.swayPhase) * p.swayAmplitudeDp * dp1
                            val px = w * p.xFraction + sway
                            val r = p.radiusDp * dp1

                            if (p.isAccentShape) {
                                val arm = (p.radiusDp + 2.4f) * dp1
                                val rotDeg = (drift * 360f * if (p.colorVariant == 0) 1f else -1f) + p.swayPhase * 30f
                                rotate(degrees = rotDeg, pivot = Offset(px, py)) {
                                    // 0 deg
                                    drawLine(crystalColor, Offset(px - arm, py), Offset(px + arm, py), crystalStrokeW, StrokeCap.Round)
                                    // 60 deg
                                    val dx = cos60 * arm
                                    val dy = sin60 * arm
                                    drawLine(crystalColor, Offset(px - dx, py - dy), Offset(px + dx, py + dy), crystalStrokeW, StrokeCap.Round)
                                    // 120 deg
                                    drawLine(crystalColor, Offset(px + dx, py - dy), Offset(px - dx, py + dy), crystalStrokeW, StrokeCap.Round)
                                    drawCircle(snowCenter, crystalDotR, Offset(px, py))
                                }
                            } else {
                                drawCircle(snowFill, r, Offset(px, py))
                                drawCircle(snowRim, r, Offset(px, py), style = snowDotStroke)
                            }
                        }

                        // C. Twinkling mini pine tree stars
                        drawCircle(starGlowBase.copy(alpha = 0.25f + twinkle * 0.35f), (5f + twinkle * 2.5f) * dp1, star1)
                        drawCircle(starCore, starCoreR, star1)
                        val invTwinkle = 1f - twinkle
                        drawCircle(starGlowBase.copy(alpha = 0.25f + invTwinkle * 0.35f), (5f + invTwinkle * 2.5f) * dp1, star2)
                        drawCircle(starCore, starCoreR, star2)
                    }

                    SeasonThemeMode.SPRING -> {
                        particles.fastForEach { p ->
                            val progress = (p.yOffset + drift * p.speedCycles) % 1f
                            val py = progress * (h + hPad30) - hPad15
                            val sway = sin((progress * twoPi) + p.swayPhase) * (p.swayAmplitudeDp * 1.2f) * dp1
                            val px = w * p.xFraction + sway
                            val r = (p.radiusDp + 1.2f) * dp1
                            val rot = drift * 360f + p.swayPhase * 45f
                            rotate(rot, pivot = Offset(px, py)) {
                                val topLeft = Offset(px - r, py - r * 0.6f)
                                val sz = Size(r * 2f, r * 1.2f)
                                drawOval(petalColors[p.colorVariant], topLeft, sz)
                                drawOval(petalRimColor, topLeft, sz, style = petalRimStroke)
                            }
                        }
                    }

                    SeasonThemeMode.SUMMER -> {
                        particles.fastForEachIndexed { idx, p ->
                            val py = h * (0.08f + p.yOffset * 0.78f)
                            val px = w * p.xFraction
                            val pulse = if (idx % 2 == 0) twinkle else 1f - twinkle
                            val arm = (3f + pulse * 3.5f) * dp1
                            val col = (if (p.isAccentShape) sunColor else sparkleColor).copy(alpha = 0.22f + pulse * 0.48f)
                            drawLine(col, Offset(px - arm, py), Offset(px + arm, py), sparkleStrokeW, StrokeCap.Round)
                            drawLine(col, Offset(px, py - arm), Offset(px, py + arm), sparkleStrokeW, StrokeCap.Round)
                        }

                        val waveOffset = sin(drift * twoPi) * 14f * dp1
                        wavePath.rewind()
                        wavePath.moveTo(0f, h)
                        wavePath.lineTo(0f, h - 72f * dp1)
                        wavePath.quadraticTo(w * 0.25f + waveOffset, h - 94f * dp1, w * 0.5f, h - 72f * dp1)
                        wavePath.quadraticTo(w * 0.75f + waveOffset, h - 52f * dp1, w, h - 74f * dp1)
                        wavePath.lineTo(w, h)
                        wavePath.close()
                        drawPath(wavePath, color = waveFill)
                        drawPath(wavePath, color = waveRim, style = waveStroke)
                    }

                    SeasonThemeMode.AUTUMN -> {
                        particles.fastForEach { p ->
                            val progress = (p.yOffset + drift * p.speedCycles) % 1f
                            val py = progress * (h + hPad30) - hPad15
                            val sway = sin((progress * twoPi) + p.swayPhase) * (p.swayAmplitudeDp * 1.3f) * dp1
                            val px = w * p.xFraction + sway
                            val r = (p.radiusDp + 1.8f) * dp1
                            val rot = drift * 360f + p.swayPhase * 40f
                            rotate(rot, pivot = Offset(px, py)) {
                                drawOval(leafColors[p.colorVariant], Offset(px - r, py - r * 0.55f), Size(r * 2f, r * 1.1f))
                                drawLine(leafVeinColor, Offset(px - r * 0.8f, py), Offset(px + r * 0.8f, py), leafVeinW)
                            }
                        }
                    }

                    SeasonThemeMode.OFF, SeasonThemeMode.AUTO -> Unit
                }
            }
        }
    )
}
