package com.example.itv.presentation.character

import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * Cream speech bubble with rounded corners and tail circles, matching the puppy artwork.
 * Draws completely outside the text content so text never overflows outside the bubble.
 */
class PuppyBubbleDrawable : Drawable() {
    private var targetFraction = 0.5f
    private var below = true

    fun pointToward(fraction: Float, belowBubble: Boolean) {
        targetFraction = fraction.coerceIn(0.12f, 0.88f)
        below = belowBubble
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

        val bodyTop = if (below) halfStroke + 1f else tailSpace + halfStroke + 1f
        val bodyBottom = if (below) h - tailSpace - halfStroke - 1f else h - halfStroke - 1f
        val bodyLeft = halfStroke + 1f
        val bodyRight = w - halfStroke - 1f

        if (bodyBottom <= bodyTop || bodyRight <= bodyLeft) return

        val rect = RectF(bodyLeft, bodyTop, bodyRight, bodyBottom)
        val cornerRadius = 20f * density

        // 1. 말풍선 본체 (글자 전체를 바깥에서 넉넉하게 감싸는 둥근 풍선)
        c.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        c.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

        // 2. 강아지를 향하는 생각/말풍선 꼬리 원 (Tail Circles)
        val tailX = (targetFraction * w).coerceIn(bodyLeft + cornerRadius + 4f, bodyRight - cornerRadius - 4f)
        val bigR = 5.5f * density
        val smallR = 3.2f * density

        val (bigY, smallY) = if (below) {
            Pair(bodyBottom + 4.5f * density, h - 3.5f * density)
        } else {
            Pair(bodyTop - 4.5f * density, 3.5f * density)
        }

        // 큰 꼬리 원
        c.drawCircle(tailX, bigY, bigR, fillPaint)
        c.drawCircle(tailX, bigY, bigR, strokePaint)

        // 작은 꼬리 원 (강아지 방향으로 살짝 이동)
        val smallX = tailX + if (tailX < w / 2f) 3f * density else -3f * density
        c.drawCircle(smallX, smallY, smallR, fillPaint)
        c.drawCircle(smallX, smallY, smallR, strokePaint)
    }

    override fun getPadding(padding: Rect): Boolean {
        val hPad = (24f * density).toInt()
        val topPad = if (below) (16f * density).toInt() else (16f * density + 14f * density).toInt()
        val botPad = if (below) (16f * density + 14f * density).toInt() else (16f * density).toInt()
        padding.set(hPad, topPad, hPad, botPad)
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
