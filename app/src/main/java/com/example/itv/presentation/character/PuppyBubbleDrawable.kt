package com.example.itv.presentation.character

import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * Cream speech bubble with rounded corners and tail circles, matching the puppy artwork.
 * Draws completely outside the text content so text never overflows outside the bubble.
 */
class PuppyBubbleDrawable : Drawable() {
    enum class TailSide { BOTTOM, TOP, RIGHT }

    private var targetFraction = 0.5f
    private var tailSide = TailSide.BOTTOM

    fun pointToward(fraction: Float, belowBubble: Boolean) {
        val clamped = fraction.coerceIn(0.12f, 0.88f)
        val nextSide = if (belowBubble) TailSide.BOTTOM else TailSide.TOP
        if (kotlin.math.abs(targetFraction - clamped) < 0.004f && tailSide == nextSide) return
        targetFraction = clamped
        tailSide = nextSide
        invalidateSelf()
    }

    fun pointTowardRight(verticalFraction: Float) {
        val clamped = verticalFraction.coerceIn(0.18f, 0.82f)
        if (kotlin.math.abs(targetFraction - clamped) < 0.004f && tailSide == TailSide.RIGHT) return
        targetFraction = clamped
        tailSide = TailSide.RIGHT
        invalidateSelf()
    }

    private val density = Resources.getSystem().displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 249, 235) // warm cream
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(113, 73, 43) // warm brown outline
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val tailSpace = 14f * density
        val strokeW = strokePaint.strokeWidth
        val halfStroke = strokeW / 2f

        val bodyTop = when (tailSide) {
            TailSide.TOP -> tailSpace + halfStroke + 1f
            TailSide.BOTTOM, TailSide.RIGHT -> halfStroke + 1f
        }
        val bodyBottom = when (tailSide) {
            TailSide.BOTTOM -> h - tailSpace - halfStroke - 1f
            TailSide.TOP, TailSide.RIGHT -> h - halfStroke - 1f
        }
        val bodyLeft = halfStroke + 1f
        val bodyRight = when (tailSide) {
            TailSide.RIGHT -> w - tailSpace - halfStroke - 1f
            TailSide.BOTTOM, TailSide.TOP -> w - halfStroke - 1f
        }

        if (bodyBottom <= bodyTop || bodyRight <= bodyLeft) return

        val rect = RectF(bodyLeft, bodyTop, bodyRight, bodyBottom)
        val cornerRadius = 20f * density

        // 1. 말풍선 본체 (글자 전체를 바깥에서 넉넉하게 감싸는 둥근 풍선)
        c.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        c.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

        // 2. 강아지를 향하는 생각/말풍선 꼬리 원 (Tail Circles)
        val bigR = 5.5f * density
        val smallR = 3.2f * density

        if (tailSide == TailSide.RIGHT) {
            val minTailY = (bodyTop + cornerRadius + 4f).coerceAtMost((bodyTop + bodyBottom) * 0.5f)
            val maxTailY = (bodyBottom - cornerRadius - 4f).coerceAtLeast(minTailY)
            val tailY = (targetFraction * h).coerceIn(minTailY, maxTailY)
            val bigX = bodyRight + 4.5f * density
            val smallX = w - 3.5f * density
            val smallYOffset = when {
                targetFraction > 0.55f -> 2.5f * density
                targetFraction < 0.45f -> -2.5f * density
                else -> 0f
            }
            val smallY = (tailY + smallYOffset).coerceIn(smallR + halfStroke, h - smallR - halfStroke)

            c.drawCircle(bigX, tailY, bigR, fillPaint)
            c.drawCircle(bigX, tailY, bigR, strokePaint)

            c.drawCircle(smallX, smallY, smallR, fillPaint)
            c.drawCircle(smallX, smallY, smallR, strokePaint)
        } else {
            val minTailX = (bodyLeft + cornerRadius + 4f).coerceAtMost((bodyLeft + bodyRight) * 0.5f)
            val maxTailX = (bodyRight - cornerRadius - 4f).coerceAtLeast(minTailX)
            val tailX = (targetFraction * w).coerceIn(minTailX, maxTailX)
            val (bigY, smallY) = if (tailSide == TailSide.BOTTOM) {
                Pair(bodyBottom + 4.5f * density, h - 3.5f * density)
            } else {
                Pair(bodyTop - 4.5f * density, 3.5f * density)
            }

            // 큰 꼬리 원
            c.drawCircle(tailX, bigY, bigR, fillPaint)
            c.drawCircle(tailX, bigY, bigR, strokePaint)

            // 작은 꼬리 원 (강아지 머리 방향을 향해 자연스럽게 이어짐)
            val smallXOffset = when {
                targetFraction > 0.55f -> 2.5f * density
                targetFraction < 0.45f -> -2.5f * density
                else -> 0f
            }
            val smallX = (tailX + smallXOffset).coerceIn(smallR + halfStroke, w - smallR - halfStroke)
            c.drawCircle(smallX, smallY, smallR, fillPaint)
            c.drawCircle(smallX, smallY, smallR, strokePaint)
        }
    }

    override fun getPadding(padding: Rect): Boolean {
        val hPad = (24f * density).toInt()
        val tailPad = (14f * density).toInt()
        val vPad = (16f * density).toInt()
        when (tailSide) {
            TailSide.BOTTOM -> padding.set(hPad, vPad, hPad, vPad + tailPad)
            TailSide.TOP -> padding.set(hPad, vPad + tailPad, hPad, vPad)
            TailSide.RIGHT -> padding.set(hPad, vPad, hPad + tailPad, vPad)
        }
        return true
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        fillPaint.colorFilter = filter
        strokePaint.colorFilter = filter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
