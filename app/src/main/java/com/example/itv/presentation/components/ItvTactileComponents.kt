package com.example.itv.presentation.components

import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Concept 1: Soft Ink Brutalism x Stamp Journal tactile design tokens.
 * Warm espresso ink strokes in light mode (matching the puppy mascot's outlines)
 * and crisp sage-ink strokes in dark mode, paired with solid 3D offset lips.
 */
val ItvInkBorder: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalDarkTheme.current) Color(0xFF4F6B59) else Color(0xFF3A2E25)

val ItvInkShadow: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalDarkTheme.current) Color(0xFF040705) else Color(0xFF3A2E25)

val ItvDotGridColor: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalDarkTheme.current) Color(0xFF202D25) else Color(0xFFC5B9A6)

/**
 * Builds a tiny repeatable BitmapShader tile (~12 KB) for the notebook dot-grid so painting
 * the entire screen takes ONE GPU drawRect call instead of ~1,600 drawCircle calls.
 */
internal fun buildDotGridBrush(
    stepPx: Float,
    radiusPx: Float,
    dotColor: Color
): ShaderBrush? {
    val tilePx = stepPx.roundToInt()
    if (tilePx < 4) return null
    val tile = createBitmap(tilePx, tilePx)
    val canvas = AndroidCanvas(tile)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = dotColor.toArgb()
        style = AndroidPaint.Style.FILL
    }
    val center = tilePx * 0.5f
    canvas.drawCircle(center, center, radiusPx, paint)
    val shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    return ShaderBrush(shader)
}

/**
 * Lightweight notebook dot-grid background painted via a single tiled BitmapShader draw call.
 */
@Composable
fun ItvDotGridBackground(
    modifier: Modifier = Modifier,
    dotSpacing: Dp = 18.dp,
    dotRadius: Dp = 1.25.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val bgColor = MaterialTheme.colorScheme.background
    val dotColor = ItvDotGridColor.copy(alpha = 0.72f)
    Box(
        modifier = modifier
            .background(bgColor)
            .drawWithCache {
                val dotBrush = buildDotGridBrush(
                    stepPx = dotSpacing.toPx(),
                    radiusPx = dotRadius.toPx(),
                    dotColor = dotColor
                )
                onDrawBehind {
                    if (dotBrush != null) {
                        drawRect(brush = dotBrush)
                    }
                }
            },
        content = content
    )
}

/**
 * Soft-brutalist paper card with an ink outline and hard 3D offset bottom-right lip.
 * Draws the offset shadow directly via drawWithCache (no extra wrapper Box or matchParentSize pass).
 */
@Composable
fun ItvTactileCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    containerColor: Color = ItvCardSurface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderColor: Color = ItvInkBorder,
    shadowColor: Color = ItvInkShadow,
    borderWidth: Dp = 1.8.dp,
    shadowOffsetX: Dp = 2.dp,
    shadowOffsetY: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .padding(end = shadowOffsetX, bottom = shadowOffsetY)
            .drawWithCache {
                val shadowXPx = shadowOffsetX.toPx()
                val shadowYPx = shadowOffsetY.toPx()
                val hasShadow = shadowColor != Color.Transparent && (shadowXPx > 0f || shadowYPx > 0f)
                val outline = if (hasShadow) shape.createOutline(size, layoutDirection, this) else null
                onDrawBehind {
                    if (outline != null) {
                        translate(left = shadowXPx, top = shadowYPx) {
                            drawOutline(outline = outline, color = shadowColor)
                        }
                    }
                }
            },
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * Pressable 3D tactile button/chip that physically depresses into its hard shadow lip on press.
 * Press displacement is deferred strictly to the Draw phase via graphicsLayer (zero recomposition
 * and zero relayout during press/release animations).
 */
@Composable
fun ItvTactileButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    shape: Shape = RoundedCornerShape(18.dp),
    containerColor: Color = ItvCardSurface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderColor: Color = ItvInkBorder,
    shadowColor: Color = ItvInkShadow,
    borderWidth: Dp = 1.8.dp,
    shadowOffsetX: Dp = 1.5.dp,
    shadowOffsetY: Dp = 3.5.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val activeShadowX = if (enabled) shadowOffsetX else 0.dp
    val activeShadowY = if (enabled) shadowOffsetY else 0.dp

    // Keep as State<Float> (do NOT delegate with `by`) so reading happens exclusively in graphicsLayer Draw phase.
    val pressProgress = animateFloatAsState(
        targetValue = if (isPressed && enabled) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "tactilePress"
    )

    Box(
        modifier = modifier
            .padding(end = shadowOffsetX, bottom = shadowOffsetY)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick
            )
            .drawWithCache {
                val shadowXPx = activeShadowX.toPx()
                val shadowYPx = activeShadowY.toPx()
                val hasShadow = enabled && shadowColor != Color.Transparent && (shadowXPx > 0f || shadowYPx > 0f)
                val outline = if (hasShadow) shape.createOutline(size, layoutDirection, this) else null
                onDrawBehind {
                    if (outline != null) {
                        translate(left = shadowXPx, top = shadowYPx) {
                            drawOutline(outline = outline, color = shadowColor)
                        }
                    }
                }
            },
        propagateMinConstraints = true
    ) {
        Surface(
            modifier = Modifier.graphicsLayer {
                val p = pressProgress.value
                if (p > 0.001f) {
                    translationX = activeShadowX.toPx() * p
                    translationY = activeShadowY.toPx() * p
                }
            },
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = if (borderWidth > 0.dp && borderColor != Color.Transparent) {
                BorderStroke(borderWidth, borderColor)
            } else {
                null
            }
        ) {
            Box(content = content)
        }
    }
}
