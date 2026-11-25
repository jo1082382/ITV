package com.example.itv.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import com.example.itv.infrastructure.theme.SeasonThemeMode
import com.example.itv.infrastructure.theme.UserThemeStore
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Flat primitive arrays for foreground drifting specks (zero Triple/List boxing!)
private val FG_X_FRACTIONS = floatArrayOf(0.06f, 0.93f, 0.11f, 0.89f, 0.24f, 0.76f, 0.04f, 0.95f)
private val FG_Y_OFFSETS = floatArrayOf(0.08f, 0.22f, 0.48f, 0.61f, 0.78f, 0.35f, 0.86f, 0.90f)
private val FG_SPEEDS = intArrayOf(1, 2, 1, 1, 2, 1, 2, 1)

private class CachedRoofTree(
    val lower: Path,
    val upper: Path,
    val orn1: Offset,
    val orn2: Offset,
    val topAccent: Offset,
    val isChristmas: Boolean
)

private fun buildRoofTree(base: Offset, height: Float, width: Float, dp1: Float, isChristmas: Boolean): CachedRoofTree {
    val topY = base.y - height
    val lower = Path().apply {
        moveTo(base.x, topY + height * 0.25f)
        lineTo(base.x - width * 0.52f, base.y)
        lineTo(base.x + width * 0.52f, base.y)
        close()
    }
    val upper = Path().apply {
        moveTo(base.x, topY)
        lineTo(base.x - width * 0.38f, topY + height * 0.55f)
        lineTo(base.x + width * 0.38f, topY + height * 0.55f)
        close()
    }
    return CachedRoofTree(
        lower = lower,
        upper = upper,
        orn1 = Offset(base.x - width * 0.14f, topY + height * 0.45f),
        orn2 = Offset(base.x + width * 0.15f, topY + height * 0.72f),
        topAccent = if (isChristmas) Offset(base.x, topY - 2f * dp1) else Offset(base.x, topY + 2f * dp1),
        isChristmas = isChristmas
    )
}

/**
 * Piece 2: Cute Roof-Top seasonal decoration perched directly atop a tactile card's upper border.
 * All Paths and Strokes are cached inside drawWithCache so scrolling never reallocates geometry.
 */
@Composable
fun ItvSeasonalRoofTopAccent(
    modifier: Modifier = Modifier,
    seasonMode: SeasonThemeMode = UserThemeStore.rememberSeasonMode()
) {
    val activeSeason = remember(seasonMode) { UserThemeStore.resolveActiveSeason(seasonMode) }
    if (activeSeason == SeasonThemeMode.OFF) return

    val isDark = LocalDarkTheme.current
    val inkColor = ItvInkBorder

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val dp1 = 1.dp.toPx()
                val stroke = Stroke(width = 1.6f * dp1, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val treeStroke = Stroke(width = 1.4f * dp1, join = StrokeJoin.Round)
                val twigStroke = Stroke(width = 2.2f * dp1, cap = StrokeCap.Round)
                val sunRimStroke = Stroke(width = 1.5f * dp1)
                val cloudStroke = Stroke(width = 1.4f * dp1, cap = StrokeCap.Round)

                val snowPath = if (activeSeason == SeasonThemeMode.WINTER) {
                    Path().apply {
                        moveTo(w * 0.08f, h)
                        quadraticTo(w * 0.19f, h - 9f * dp1, w * 0.30f, h)
                        moveTo(w * 0.42f, h)
                        quadraticTo(w * 0.54f, h - 7f * dp1, w * 0.66f, h)
                        moveTo(w * 0.74f, h)
                        quadraticTo(w * 0.84f, h - 8f * dp1, w * 0.93f, h)
                    }
                } else null

                val roofTrees = if (activeSeason == SeasonThemeMode.WINTER) {
                    listOf(
                        buildRoofTree(Offset(w * 0.14f, h), 24f * dp1, 17f * dp1, dp1, isChristmas = true),
                        buildRoofTree(Offset(w * 0.87f, h), 20f * dp1, 14f * dp1, dp1, isChristmas = false)
                    )
                } else emptyList()

                val springTwig = if (activeSeason == SeasonThemeMode.SPRING) {
                    Path().apply {
                        moveTo(w * 0.08f, h)
                        quadraticTo(w * 0.15f, h - 14f * dp1, w * 0.28f, h - 10f * dp1)
                    }
                } else null

                val summerCloudArc = if (activeSeason == SeasonThemeMode.SUMMER) {
                    Path().apply {
                        moveTo(w * 0.08f, h)
                        quadraticTo(w * 0.14f, h - 13f * dp1, w * 0.20f, h - 15f * dp1)
                        quadraticTo(w * 0.27f, h - 13f * dp1, w * 0.31f, h)
                    }
                } else null

                val snowColor = if (isDark) Color(0xFFDDEBF5) else Color(0xFFFFFFFF)
                val treeFill = if (isDark) Color(0xFF2B6947) else Color(0xFF2F7D52)
                val branchColor = if (isDark) Color(0xFF9C7B63) else Color(0xFF6B4F3B)
                val sunColor = if (isDark) Color(0xFFFFD85E) else Color(0xFFF6B73C)
                val cloudColor = if (isDark) Color(0xFFD6E8F2) else Color.White

                onDrawBehind {
                    when (activeSeason) {
                        SeasonThemeMode.WINTER -> {
                            snowPath?.let {
                                drawPath(it, color = snowColor)
                                drawPath(it, color = inkColor, style = stroke)
                            }
                            roofTrees.fastForEach { t ->
                                drawPath(t.lower, treeFill)
                                drawPath(t.lower, inkColor, style = treeStroke)
                                drawPath(t.upper, treeFill)
                                drawPath(t.upper, inkColor, style = treeStroke)
                                if (t.isChristmas) {
                                    drawCircle(Color(0xFFD94A4A), 1.9f * dp1, t.orn1)
                                    drawCircle(Color(0xFFF6C851), 1.9f * dp1, t.orn2)
                                    drawCircle(Color(0xFFF7C948), 2.5f * dp1, t.topAccent)
                                } else {
                                    drawCircle(Color.White, 2.2f * dp1, t.topAccent)
                                }
                            }
                        }
                        SeasonThemeMode.SPRING -> {
                            springTwig?.let { drawPath(it, color = branchColor, style = twigStroke) }
                            drawBlossomCluster(Offset(w * 0.15f, h - 12f * dp1), 6.5f * dp1, isDark, inkColor)
                            drawBlossomCluster(Offset(w * 0.25f, h - 10f * dp1), 5.5f * dp1, isDark, inkColor)
                            drawBlossomCluster(Offset(w * 0.86f, h - 7f * dp1), 6f * dp1, isDark, inkColor)
                        }
                        SeasonThemeMode.SUMMER -> {
                            val sunCenter = Offset(w * 0.86f, h - 2f * dp1)
                            val r1 = 13f * dp1
                            val r2 = 20f * dp1
                            for (i in 0..6) {
                                val rad = (195.0 + i * 25.0) * PI / 180.0
                                val c = cos(rad).toFloat()
                                val s = sin(rad).toFloat()
                                drawLine(
                                    color = sunColor,
                                    start = Offset(sunCenter.x + c * r1, sunCenter.y + s * r1),
                                    end = Offset(sunCenter.x + c * r2, sunCenter.y + s * r2),
                                    strokeWidth = 2f * dp1,
                                    cap = StrokeCap.Round
                                )
                            }
                            drawCircle(color = sunColor, radius = 10f * dp1, center = sunCenter)
                            drawCircle(color = inkColor, radius = 10f * dp1, center = sunCenter, style = sunRimStroke)

                            drawCircle(cloudColor, 8f * dp1, Offset(w * 0.14f, h - 3f * dp1))
                            drawCircle(cloudColor, 10f * dp1, Offset(w * 0.20f, h - 6f * dp1))
                            drawCircle(cloudColor, 7.5f * dp1, Offset(w * 0.26f, h - 3f * dp1))
                            summerCloudArc?.let { drawPath(it, color = inkColor, style = cloudStroke) }
                        }
                        SeasonThemeMode.AUTUMN -> {
                            drawAutumnLeafMotif(Offset(w * 0.15f, h - 8f * dp1), 8f * dp1, -22f, Color(0xFFD96432), inkColor)
                            drawAutumnLeafMotif(Offset(w * 0.24f, h - 6f * dp1), 7f * dp1, 18f, Color(0xFFE6A835), inkColor)
                            drawAutumnLeafMotif(Offset(w * 0.85f, h - 8f * dp1), 8f * dp1, 24f, Color(0xFFC44536), inkColor)
                        }
                        SeasonThemeMode.OFF, SeasonThemeMode.AUTO -> Unit
                    }
                }
            }
    )
}

/**
 * Piece 1: Two-Tier Postcard Calendar Top Banner.
 * Decorates ONLY the top month-header strip with seasonal scenery while keeping the calendar
 * grid below 100% pure crisp white for maximum contrast.
 * Uses drawWithCache with zero infinite animations inside the LazyColumn for 0fps idle cost!
 */
@Composable
fun ItvSeasonalCalendarBanner(
    modifier: Modifier = Modifier,
    seasonMode: SeasonThemeMode = UserThemeStore.rememberSeasonMode(),
    content: @Composable BoxScope.() -> Unit
) {
    val activeSeason = remember(seasonMode) { UserThemeStore.resolveActiveSeason(seasonMode) }
    val isDark = LocalDarkTheme.current
    val inkColor = ItvInkBorder
    val cardBg = ItvCardSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawWithCache {
                if (activeSeason == SeasonThemeMode.OFF) {
                    return@drawWithCache onDrawBehind {}
                }
                val w = size.width
                val h = size.height
                val dp1 = 1.dp.toPx()

                val (topColor, bottomColor) = when (activeSeason) {
                    SeasonThemeMode.WINTER -> if (isDark) {
                        Color(0xFF142833) to Color(0xFF1A2F2B)
                    } else {
                        Color(0xFFDCE8F2) to Color(0xFFEDF3F7)
                    }
                    SeasonThemeMode.SPRING -> if (isDark) {
                        Color(0xFF2B1C24) to Color(0xFF221E20)
                    } else {
                        Color(0xFFFCE8EE) to Color(0xFFFFF5F8)
                    }
                    SeasonThemeMode.SUMMER -> if (isDark) {
                        Color(0xFF112836) to Color(0xFF172C2B)
                    } else {
                        Color(0xFFD6EEFA) to Color(0xFFECF7FC)
                    }
                    SeasonThemeMode.AUTUMN -> if (isDark) {
                        Color(0xFF2B1E14) to Color(0xFF231D17)
                    } else {
                        Color(0xFFF8E7D2) to Color(0xFFFDF4E8)
                    }
                    SeasonThemeMode.OFF, SeasonThemeMode.AUTO -> Color.Transparent to Color.Transparent
                }
                val skyBrush = Brush.verticalGradient(listOf(topColor, bottomColor))

                val garlandPath = if (activeSeason == SeasonThemeMode.WINTER) {
                    Path().apply {
                        moveTo(0f, 6f * dp1)
                        quadraticTo(w * 0.25f, 18f * dp1, w * 0.5f, 8f * dp1)
                        quadraticTo(w * 0.75f, 18f * dp1, w, 6f * dp1)
                    }
                } else null

                val waveTopY = h - 7f * dp1
                val snowEdgePath = if (activeSeason == SeasonThemeMode.WINTER) {
                    Path().apply {
                        moveTo(0f, h)
                        lineTo(0f, waveTopY)
                        quadraticTo(w * 0.16f, h - 12f * dp1, w * 0.34f, waveTopY)
                        quadraticTo(w * 0.52f, h - 2f * dp1, w * 0.70f, waveTopY)
                        quadraticTo(w * 0.86f, h - 12f * dp1, w, waveTopY)
                        lineTo(w, h)
                        close()
                    }
                } else null

                val waveStrokePath = if (activeSeason == SeasonThemeMode.WINTER) {
                    Path().apply {
                        moveTo(0f, waveTopY)
                        quadraticTo(w * 0.16f, h - 12f * dp1, w * 0.34f, waveTopY)
                        quadraticTo(w * 0.52f, h - 2f * dp1, w * 0.70f, waveTopY)
                        quadraticTo(w * 0.86f, h - 12f * dp1, w, waveTopY)
                    }
                } else null

                val garlandStroke = Stroke(1.2f * dp1)
                val waveStroke = Stroke(1.3f * dp1)
                val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                val rayColor = (if (isDark) Color(0xFFFFE07D) else Color(0xFFF6B73C))
                    .copy(alpha = if (isDark) 0.13f else 0.19f)

                onDrawBehind {
                    drawRect(brush = skyBrush)

                    when (activeSeason) {
                        SeasonThemeMode.WINTER -> {
                            garlandPath?.let { drawPath(it, color = inkColor.copy(alpha = 0.4f), style = garlandStroke) }
                            val bulbY = 13f * dp1
                            val gold = Color(0xFFF6C64C)
                            val red = Color(0xFFD94A4A)
                            // Warm glowing static fairy lights
                            drawCircle(gold.copy(alpha = 0.34f), 6.5f * dp1, Offset(w * 0.16f, bulbY))
                            drawCircle(gold, 2.5f * dp1, Offset(w * 0.16f, bulbY))
                            drawCircle(red.copy(alpha = 0.32f), 6.2f * dp1, Offset(w * 0.34f, bulbY))
                            drawCircle(red, 2.5f * dp1, Offset(w * 0.34f, bulbY))
                            drawCircle(gold.copy(alpha = 0.34f), 6.5f * dp1, Offset(w * 0.66f, bulbY))
                            drawCircle(gold, 2.5f * dp1, Offset(w * 0.66f, bulbY))
                            drawCircle(red.copy(alpha = 0.32f), 6.2f * dp1, Offset(w * 0.84f, bulbY))
                            drawCircle(red, 2.5f * dp1, Offset(w * 0.84f, bulbY))

                            val dotCol = Color.White.copy(alpha = if (isDark) 0.55f else 0.9f)
                            val dotR = 2f * dp1
                            drawCircle(dotCol, dotR, Offset(w * 0.22f, h * 0.52f))
                            drawCircle(dotCol, dotR, Offset(w * 0.78f, h * 0.45f))
                            drawCircle(dotCol, dotR, Offset(w * 0.28f, h * 0.30f))
                            drawCircle(dotCol, dotR, Offset(w * 0.72f, h * 0.68f))

                            snowEdgePath?.let { drawPath(it, color = cardBg) }
                            waveStrokePath?.let { drawPath(it, color = inkColor.copy(alpha = 0.35f), style = waveStroke) }
                        }
                        SeasonThemeMode.SPRING -> {
                            drawBlossomCluster(Offset(w * 0.05f, h * 0.25f), 8f * dp1, isDark, inkColor)
                            drawBlossomCluster(Offset(w * 0.14f, h * 0.18f), 5.5f * dp1, isDark, inkColor)
                            drawBlossomCluster(Offset(w * 0.94f, h * 0.26f), 7.5f * dp1, isDark, inkColor)
                            drawBlossomCluster(Offset(w * 0.85f, h * 0.18f), 5f * dp1, isDark, inkColor)
                            drawLine(inkColor.copy(alpha = 0.32f), Offset(12f * dp1, h - dp1), Offset(w - 12f * dp1, h - dp1), 1.2f * dp1, pathEffect = dashedEffect)
                        }
                        SeasonThemeMode.SUMMER -> {
                            for (i in 0..3) {
                                val startX = w * (0.76f + i * 0.07f)
                                drawLine(rayColor, Offset(startX, 0f), Offset(startX - 42f * dp1, h), 10f * dp1)
                            }
                            drawLine(inkColor.copy(alpha = 0.32f), Offset(12f * dp1, h - dp1), Offset(w - 12f * dp1, h - dp1), 1.2f * dp1, pathEffect = dashedEffect)
                        }
                        SeasonThemeMode.AUTUMN -> {
                            drawAutumnLeafMotif(Offset(w * 0.08f, h * 0.25f), 7f * dp1, -30f, Color(0xFFD96432), inkColor)
                            drawAutumnLeafMotif(Offset(w * 0.18f, h * 0.22f), 5.5f * dp1, 20f, Color(0xFFE6A835), inkColor)
                            drawAutumnLeafMotif(Offset(w * 0.92f, h * 0.26f), 7f * dp1, 28f, Color(0xFFC44536), inkColor)
                            drawAutumnLeafMotif(Offset(w * 0.82f, h * 0.20f), 5.5f * dp1, -18f, Color(0xFFE6A835), inkColor)
                            drawLine(inkColor.copy(alpha = 0.32f), Offset(12f * dp1, h - dp1), Offset(w - 12f * dp1, h - dp1), 1.2f * dp1, pathEffect = dashedEffect)
                        }
                        SeasonThemeMode.OFF, SeasonThemeMode.AUTO -> Unit
                    }
                }
            }
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = if (activeSeason == SeasonThemeMode.OFF) 16.dp else 14.dp,
                bottom = if (activeSeason == SeasonThemeMode.WINTER) 16.dp else 12.dp
            ),
        content = content
    )
}

/**
 * Piece 3: Touch-passthrough subtle foreground seasonal layer.
 * All brushes, strokes, and particle arrays are cached via drawWithCache for zero per-frame allocations.
 */
@Composable
fun ItvSeasonalForegroundOverlay(
    modifier: Modifier = Modifier,
    seasonMode: SeasonThemeMode = UserThemeStore.rememberSeasonMode()
) {
    val activeSeason = remember(seasonMode) { UserThemeStore.resolveActiveSeason(seasonMode) }
    if (activeSeason == SeasonThemeMode.OFF) return

    val isDark = LocalDarkTheme.current
    val timeMsState = rememberThrottledSeasonalTimeMs(frameIntervalMs = 50L)

    // Dedicated hardware RenderNode isolates 20fps (0fps during scroll) foreground specks from scrolling content below
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val dp1 = 1.dp.toPx()
                val twoPi = (2.0 * PI).toFloat()
                val rimStroke = Stroke(0.8f * dp1)

                val snowCol = (if (isDark) Color(0xFFEAF6FF) else Color(0xFFFFFFFF)).copy(alpha = 0.58f)
                val rimCol = (if (isDark) Color(0xFF9AC2DC) else Color(0xFF7D93A6)).copy(alpha = 0.32f)
                val pinkCol = (if (isDark) Color(0xFFE8A4BA) else Color(0xFFF39CB7)).copy(alpha = 0.48f)
                val leafCols = arrayOf(
                    Color(0xFFD96432).copy(alpha = 0.44f),
                    Color(0xFFE6A835).copy(alpha = 0.44f),
                    Color(0xFFC44536).copy(alpha = 0.44f)
                )
                val sunGlow = if (isDark) Color(0xFFFFDF73) else Color(0xFFF6B73C)
                val sunCenter = Offset(w * 0.96f, 24f * dp1)
                val sunRadius = w * 0.48f
                val sunBrush = if (activeSeason == SeasonThemeMode.SUMMER && sunRadius > 1f) {
                    Brush.radialGradient(
                        colors = listOf(sunGlow.copy(alpha = if (isDark) 0.20f else 0.24f), Color.Transparent),
                        center = sunCenter,
                        radius = sunRadius
                    )
                } else null
                val sparkleW = 1.3f * dp1

                onDrawBehind {
                    val elapsed = timeMsState.value
                    val drift = (elapsed % 19_000L) / 19_000f
                    val pulse = pingPongFraction(elapsed, 3_600L)

                    when (activeSeason) {
                        SeasonThemeMode.WINTER -> {
                            for (i in FG_X_FRACTIONS.indices) {
                                val prog = (FG_Y_OFFSETS[i] + drift * FG_SPEEDS[i]) % 1f
                                val px = w * FG_X_FRACTIONS[i] + sin(prog * twoPi + i) * 10f * dp1
                                val py = prog * (h + 24f * dp1) - 12f * dp1
                                val r = (2.6f + (i % 3) * 0.9f) * dp1
                                drawCircle(snowCol, r, Offset(px, py))
                                drawCircle(rimCol, r, Offset(px, py), style = rimStroke)
                            }
                        }
                        SeasonThemeMode.SPRING -> {
                            for (i in FG_X_FRACTIONS.indices) {
                                val prog = (FG_Y_OFFSETS[i] + drift * FG_SPEEDS[i]) % 1f
                                val px = w * FG_X_FRACTIONS[i] + sin(prog * twoPi + i * 1.3f) * 13f * dp1
                                val py = prog * (h + 24f * dp1) - 12f * dp1
                                val r = (3.4f + (i % 2) * 1.1f) * dp1
                                rotate(drift * 360f + i * 50f, pivot = Offset(px, py)) {
                                    drawOval(pinkCol, Offset(px - r, py - r * 0.55f), Size(r * 2f, r * 1.1f))
                                }
                            }
                        }
                        SeasonThemeMode.SUMMER -> {
                            if (sunBrush != null) {
                                drawCircle(
                                    brush = sunBrush,
                                    radius = sunRadius,
                                    center = sunCenter,
                                    alpha = 0.72f + pulse * 0.28f
                                )
                            }
                            for (i in 0 until 6) {
                                val px = w * FG_X_FRACTIONS[i]
                                val py = h * (0.06f + FG_Y_OFFSETS[i] * 0.80f)
                                val p = if (i % 2 == 0) pulse else 1f - pulse
                                val arm = (2.8f + p * 2.8f) * dp1
                                val col = sunGlow.copy(alpha = 0.18f + p * 0.34f)
                                drawLine(col, Offset(px - arm, py), Offset(px + arm, py), sparkleW, StrokeCap.Round)
                                drawLine(col, Offset(px, py - arm), Offset(px, py + arm), sparkleW, StrokeCap.Round)
                            }
                        }
                        SeasonThemeMode.AUTUMN -> {
                            for (i in FG_X_FRACTIONS.indices) {
                                val prog = (FG_Y_OFFSETS[i] + drift * FG_SPEEDS[i]) % 1f
                                val px = w * FG_X_FRACTIONS[i] + sin(prog * twoPi + i * 1.4f) * 14f * dp1
                                val py = prog * (h + 24f * dp1) - 12f * dp1
                                val r = (3.8f + (i % 2) * 1.1f) * dp1
                                rotate(drift * 360f + i * 65f, pivot = Offset(px, py)) {
                                    drawOval(leafCols[i % 3], Offset(px - r, py - r * 0.52f), Size(r * 2f, r * 1.04f))
                                }
                            }
                        }
                        SeasonThemeMode.OFF, SeasonThemeMode.AUTO -> Unit
                    }
                }
            }
    )
}

/**
 * Piece 4: Full Illustrated Seasonal Tree Backdrop for the open Character Stage.
 * Caches all static tree & hill Paths via drawWithCache so twinkling only repaints cached Paths.
 */
@Composable
fun ItvCharacterSeasonalStageBackdrop(
    modifier: Modifier = Modifier,
    seasonMode: SeasonThemeMode = UserThemeStore.rememberSeasonMode()
) {
    val activeSeason = remember(seasonMode) { UserThemeStore.resolveActiveSeason(seasonMode) }
    if (activeSeason == SeasonThemeMode.OFF) return

    val isDark = LocalDarkTheme.current
    val inkColor = ItvInkBorder

    val stageTimeMsState: State<Long> = if (activeSeason == SeasonThemeMode.WINTER || activeSeason == SeasonThemeMode.SUMMER) {
        rememberThrottledSeasonalTimeMs(frameIntervalMs = 66L)
    } else {
        remember { mutableLongStateOf(1300L) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val dp1 = 1.dp.toPx()
                val groundY = h * 0.87f

                val hillFill = when (activeSeason) {
                    SeasonThemeMode.WINTER -> if (isDark) Color(0xFF1A2A27) else Color(0xFFF8F6F1)
                    SeasonThemeMode.SPRING -> if (isDark) Color(0xFF1A291F) else Color(0xFFE3EFE0)
                    SeasonThemeMode.SUMMER -> if (isDark) Color(0xFF172B22) else Color(0xFFD9EED6)
                    SeasonThemeMode.AUTUMN -> if (isDark) Color(0xFF282018) else Color(0xFFF2E2CC)
                    SeasonThemeMode.OFF, SeasonThemeMode.AUTO -> Color.Transparent
                }
                val hillPath = Path().apply {
                    moveTo(w * 0.08f, h)
                    quadraticTo(w * 0.50f, groundY - 24f * dp1, w * 0.92f, h)
                    close()
                }
                val hillArcPath = Path().apply {
                    moveTo(w * 0.08f, h)
                    quadraticTo(w * 0.50f, groundY - 24f * dp1, w * 0.92f, h)
                }
                val hillStroke = Stroke(1.6f * dp1, cap = StrokeCap.Round)

                // Pre-build Christmas Tree & Pine Tree paths for Winter
                val xmasBase = Offset(w * 0.26f, groundY - 2f * dp1)
                val xmasH = (h * 0.52f).coerceIn(88f * dp1, 145f * dp1)
                val xmasW = 66f * dp1
                val xmasTopY = xmasBase.y - xmasH
                val xmasTiers = if (activeSeason == SeasonThemeMode.WINTER) {
                    listOf(
                        Path().apply {
                            moveTo(xmasBase.x, xmasTopY + xmasH * 0.42f)
                            lineTo(xmasBase.x - xmasW * 0.54f, xmasBase.y - xmasH * 0.13f)
                            lineTo(xmasBase.x + xmasW * 0.54f, xmasBase.y - xmasH * 0.13f)
                            close()
                        },
                        Path().apply {
                            moveTo(xmasBase.x, xmasTopY + xmasH * 0.20f)
                            lineTo(xmasBase.x - xmasW * 0.44f, xmasTopY + xmasH * 0.66f)
                            lineTo(xmasBase.x + xmasW * 0.44f, xmasTopY + xmasH * 0.66f)
                            close()
                        },
                        Path().apply {
                            moveTo(xmasBase.x, xmasTopY)
                            lineTo(xmasBase.x - xmasW * 0.32f, xmasTopY + xmasH * 0.40f)
                            lineTo(xmasBase.x + xmasW * 0.32f, xmasTopY + xmasH * 0.40f)
                            close()
                        }
                    )
                } else emptyList()

                // Secondary Pine Tree (Winter right side / Summer left side)
                val pineBase = if (activeSeason == SeasonThemeMode.WINTER) {
                    Offset(w * 0.77f, groundY - 2f * dp1)
                } else {
                    Offset(w * 0.25f, groundY - 2f * dp1)
                }
                val pineH = if (activeSeason == SeasonThemeMode.WINTER) {
                    (h * 0.36f).coerceIn(64f * dp1, 102f * dp1)
                } else {
                    (h * 0.48f).coerceIn(82f * dp1, 134f * dp1)
                }
                val pineW = if (activeSeason == SeasonThemeMode.WINTER) 48f * dp1 else 58f * dp1
                val pineTopY = pineBase.y - pineH
                val pineLower = if (activeSeason == SeasonThemeMode.WINTER || activeSeason == SeasonThemeMode.SUMMER) {
                    Path().apply {
                        moveTo(pineBase.x, pineTopY + pineH * 0.28f)
                        lineTo(pineBase.x - pineW * 0.50f, pineBase.y - pineH * 0.14f)
                        lineTo(pineBase.x + pineW * 0.50f, pineBase.y - pineH * 0.14f)
                        close()
                    }
                } else null
                val pineUpper = if (activeSeason == SeasonThemeMode.WINTER || activeSeason == SeasonThemeMode.SUMMER) {
                    Path().apply {
                        moveTo(pineBase.x, pineTopY)
                        lineTo(pineBase.x - pineW * 0.38f, pineTopY + pineH * 0.50f)
                        lineTo(pineBase.x + pineW * 0.38f, pineTopY + pineH * 0.50f)
                        close()
                    }
                } else null
                val pineSnowCap = if (activeSeason == SeasonThemeMode.WINTER) {
                    Path().apply {
                        moveTo(pineBase.x, pineTopY)
                        lineTo(pineBase.x - pineW * 0.15f, pineTopY + pineH * 0.19f)
                        quadraticTo(pineBase.x, pineTopY + pineH * 0.14f, pineBase.x + pineW * 0.15f, pineTopY + pineH * 0.19f)
                        close()
                    }
                } else null

                val xmasStroke = Stroke(1.7f * dp1, join = StrokeJoin.Round)
                val pineStroke = Stroke(1.6f * dp1, join = StrokeJoin.Round)
                val trunkStroke = Stroke(1.3f * dp1)
                val starRimStroke = Stroke(1.2f * dp1)
                val sunRimStroke = Stroke(1.5f * dp1)

                val xmasFill = if (isDark) Color(0xFF286646) else Color(0xFF2D7A4F)
                val pineFill = if (isDark) Color(0xFF285F43) else Color(0xFF338155)
                val trunkFill = if (isDark) Color(0xFF5A4230) else Color(0xFF6E4F38)
                val snowCapCol = if (isDark) Color(0xFFDDEBF5) else Color.White

                val ornRed = Color(0xFFD94A4A)
                val ornGold = Color(0xFFF7C948)
                val ornPts = arrayOf(
                    Offset(xmasBase.x - xmasW * 0.12f, xmasTopY + xmasH * 0.28f),
                    Offset(xmasBase.x + xmasW * 0.14f, xmasTopY + xmasH * 0.48f),
                    Offset(xmasBase.x - xmasW * 0.20f, xmasTopY + xmasH * 0.60f),
                    Offset(xmasBase.x + xmasW * 0.22f, xmasTopY + xmasH * 0.74f),
                    Offset(xmasBase.x - xmasW * 0.06f, xmasTopY + xmasH * 0.76f)
                )
                val ornCols = arrayOf(ornRed, ornGold, ornGold, ornRed, Color.White)
                val starPos = Offset(xmasBase.x, xmasTopY - 4f * dp1)

                val sunCenter = Offset(w * 0.76f, h * 0.24f)
                val sunCol = if (isDark) Color(0xFFFFD85E) else Color(0xFFF5B53B)

                onDrawBehind {
                    val twinkle = pingPongFraction(stageTimeMsState.value, 2_600L)

                    drawPath(hillPath, color = hillFill)
                    drawPath(hillArcPath, color = inkColor.copy(alpha = 0.42f), style = hillStroke)

                    when (activeSeason) {
                        SeasonThemeMode.WINTER -> {
                            // Big hand-inked Christmas Tree
                            val trunkRectTop = Offset(xmasBase.x - xmasW * 0.11f, xmasBase.y - xmasH * 0.16f)
                            val trunkRectSize = Size(xmasW * 0.22f, xmasH * 0.18f)
                            drawRect(trunkFill, trunkRectTop, trunkRectSize)
                            drawRect(inkColor.copy(alpha = 0.7f), trunkRectTop, trunkRectSize, style = trunkStroke)
                            xmasTiers.fastForEach { p ->
                                drawPath(p, xmasFill)
                                drawPath(p, inkColor.copy(alpha = 0.75f), style = xmasStroke)
                            }
                            for (i in ornPts.indices) {
                                val p = if (i % 2 == 0) twinkle else 1f - twinkle
                                val col = ornCols[i]
                                drawCircle(col.copy(alpha = 0.22f + p * 0.28f), (4.5f + p * 2f) * dp1, ornPts[i])
                                drawCircle(col, 3f * dp1, ornPts[i])
                            }
                            drawCircle(ornGold.copy(alpha = 0.26f + twinkle * 0.32f), (8f + twinkle * 3f) * dp1, starPos)
                            drawCircle(ornGold, 4.5f * dp1, starPos)
                            drawCircle(inkColor.copy(alpha = 0.65f), 4.5f * dp1, starPos, style = starRimStroke)

                            // Cozy snow-dusted pine on right
                            drawRect(trunkFill, Offset(pineBase.x - pineW * 0.12f, pineBase.y - pineH * 0.18f), Size(pineW * 0.24f, pineH * 0.20f))
                            pineLower?.let { drawPath(it, pineFill); drawPath(it, inkColor.copy(alpha = 0.7f), style = pineStroke) }
                            pineUpper?.let { drawPath(it, pineFill); drawPath(it, inkColor.copy(alpha = 0.7f), style = pineStroke) }
                            pineSnowCap?.let { drawPath(it, color = snowCapCol) }
                        }
                        SeasonThemeMode.SPRING -> {
                            drawStageCanopyTree(
                                base = Offset(w * 0.25f, groundY - 2f * dp1),
                                height = (h * 0.52f).coerceIn(88f * dp1, 142f * dp1),
                                c0 = if (isDark) Color(0xFFD98AA4) else Color(0xFFF2A1BA),
                                c1 = if (isDark) Color(0xFFE7AEC1) else Color(0xFFF7BED0),
                                c2 = if (isDark) Color(0xFFF3CDD9) else Color(0xFFFCD9E4),
                                inkColor = inkColor
                            )
                            drawStageCanopyTree(
                                base = Offset(w * 0.77f, groundY - 2f * dp1),
                                height = (h * 0.34f).coerceIn(62f * dp1, 98f * dp1),
                                c0 = if (isDark) Color(0xFFC87D96) else Color(0xFFEDA0B8),
                                c1 = if (isDark) Color(0xFFE0A4B8) else Color(0xFFF6C2D3),
                                c2 = if (isDark) Color(0xFFF0C6D4) else Color(0xFFFCE0E9),
                                inkColor = inkColor
                            )
                        }
                        SeasonThemeMode.SUMMER -> {
                            drawCircle(sunCol.copy(alpha = 0.22f + twinkle * 0.14f), (26f + twinkle * 5f) * dp1, sunCenter)
                            for (i in 0 until 8) {
                                val rad = (i * 45.0) * PI / 180.0
                                val c = cos(rad).toFloat()
                                val s = sin(rad).toFloat()
                                drawLine(
                                    color = sunCol,
                                    start = Offset(sunCenter.x + c * 17f * dp1, sunCenter.y + s * 17f * dp1),
                                    end = Offset(sunCenter.x + c * 25f * dp1, sunCenter.y + s * 25f * dp1),
                                    strokeWidth = 2.2f * dp1,
                                    cap = StrokeCap.Round
                                )
                            }
                            drawCircle(sunCol, 13f * dp1, sunCenter)
                            drawCircle(inkColor.copy(alpha = 0.65f), 13f * dp1, sunCenter, style = sunRimStroke)

                            drawRect(trunkFill, Offset(pineBase.x - pineW * 0.12f, pineBase.y - pineH * 0.18f), Size(pineW * 0.24f, pineH * 0.20f))
                            pineLower?.let { drawPath(it, pineFill); drawPath(it, inkColor.copy(alpha = 0.7f), style = pineStroke) }
                            pineUpper?.let { drawPath(it, pineFill); drawPath(it, inkColor.copy(alpha = 0.7f), style = pineStroke) }
                        }
                        SeasonThemeMode.AUTUMN -> {
                            drawStageCanopyTree(
                                base = Offset(w * 0.25f, groundY - 2f * dp1),
                                height = (h * 0.52f).coerceIn(88f * dp1, 142f * dp1),
                                c0 = if (isDark) Color(0xFFC4553E) else Color(0xFFC94B38),
                                c1 = if (isDark) Color(0xFFD97D3E) else Color(0xFFDF7436),
                                c2 = if (isDark) Color(0xFFE0AE46) else Color(0xFFEBB141),
                                inkColor = inkColor
                            )
                            drawStageCanopyTree(
                                base = Offset(w * 0.77f, groundY - 2f * dp1),
                                height = (h * 0.35f).coerceIn(64f * dp1, 100f * dp1),
                                c0 = if (isDark) Color(0xFFD49A37) else Color(0xFFE2A232),
                                c1 = if (isDark) Color(0xFFE3B44E) else Color(0xFFF0C24B),
                                c2 = if (isDark) Color(0xFFC96A36) else Color(0xFFDB7836),
                                inkColor = inkColor
                            )
                        }
                        SeasonThemeMode.OFF, SeasonThemeMode.AUTO -> Unit
                    }
                }
            }
    )
}

private fun DrawScope.drawBlossomCluster(center: Offset, radius: Float, isDark: Boolean, inkColor: Color) {
    val petal = if (isDark) Color(0xFFE39BB3) else Color(0xFFF4A8C0)
    val core = if (isDark) Color(0xFFFFDF7D) else Color(0xFFF5C242)
    val stroke = Stroke(0.9.dp.toPx())
    val rimColor = inkColor.copy(alpha = 0.45f)
    for (i in 0 until 5) {
        val rad = (i * 72.0 - 90.0) * PI / 180.0
        val pt = Offset(
            center.x + (cos(rad) * radius * 0.52f).toFloat(),
            center.y + (sin(rad) * radius * 0.52f).toFloat()
        )
        drawCircle(petal, radius * 0.46f, pt)
        drawCircle(rimColor, radius * 0.46f, pt, style = stroke)
    }
    drawCircle(core, radius * 0.25f, center)
}

private fun DrawScope.drawAutumnLeafMotif(
    center: Offset,
    radius: Float,
    angleDeg: Float,
    color: Color,
    inkColor: Color
) {
    val stroke = Stroke(1.1.dp.toPx())
    val veinW = 0.9.dp.toPx()
    rotate(angleDeg, pivot = center) {
        val tl = Offset(center.x - radius, center.y - radius * 0.55f)
        val sz = Size(radius * 2f, radius * 1.1f)
        drawOval(color = color, topLeft = tl, size = sz)
        drawOval(color = inkColor.copy(alpha = 0.65f), topLeft = tl, size = sz, style = stroke)
        drawLine(inkColor.copy(alpha = 0.55f), Offset(center.x - radius * 0.8f, center.y), Offset(center.x + radius * 0.8f, center.y), veinW)
    }
}

private fun DrawScope.drawStageCanopyTree(
    base: Offset,
    height: Float,
    c0: Color,
    c1: Color,
    c2: Color,
    inkColor: Color
) {
    val trunkFill = Color(0xFF6E4F38)
    val topY = base.y - height
    val trunkW = height * 0.11f
    val trunkStroke = Stroke(1.4.dp.toPx())
    val puffStroke = Stroke(1.5.dp.toPx())
    val puffRim = inkColor.copy(alpha = 0.55f)

    val trunkTl = Offset(base.x - trunkW * 0.5f, base.y - height * 0.45f)
    val trunkSz = Size(trunkW, height * 0.47f)
    drawRect(trunkFill, trunkTl, trunkSz)
    drawRect(inkColor.copy(alpha = 0.7f), trunkTl, trunkSz, style = trunkStroke)

    val p0 = Offset(base.x - height * 0.18f, topY + height * 0.34f)
    val r0 = height * 0.22f
    drawCircle(c0, r0, p0); drawCircle(puffRim, r0, p0, style = puffStroke)

    val p1 = Offset(base.x + height * 0.18f, topY + height * 0.36f)
    val r1 = height * 0.21f
    drawCircle(c0, r1, p1); drawCircle(puffRim, r1, p1, style = puffStroke)

    val p2 = Offset(base.x, topY + height * 0.24f)
    val r2 = height * 0.26f
    drawCircle(c1, r2, p2); drawCircle(puffRim, r2, p2, style = puffStroke)

    val p3 = Offset(base.x - height * 0.08f, topY + height * 0.16f)
    val r3 = height * 0.16f
    drawCircle(c2, r3, p3); drawCircle(puffRim, r3, p3, style = puffStroke)
}
