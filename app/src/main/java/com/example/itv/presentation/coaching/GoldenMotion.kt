package com.example.itv.presentation.coaching

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.io.DataInputStream

internal enum class GoldenMotion(val durationMs: Long, val repeat: Boolean = false) {
    IDLE(2150, true), PEEK(950), APPROACH(670, true), JUMP(1080),
    LIFT(340), LOWER(360), DROWSY(530), LIE(990), SLEEP(2250, true), WAKE(940),
    SAD(450), REFUSE(900), FIRM(510), WAVE(850), GIVE(1120)
}

internal class GoldenStrip(val bitmap: Bitmap, val anchors: List<EquippedDogView.FrameLandmark>) {
    companion object {
        private val cache = object : LruCache<GoldenMotion, GoldenStrip>(28 * 1024 * 1024) {
            override fun sizeOf(key: GoldenMotion, value: GoldenStrip) = value.bitmap.allocationByteCount
        }

        fun load(assets: AssetManager, motion: GoldenMotion): GoldenStrip {
            cache.get(motion)?.let { return it }
            val stem = "golden/" + motion.name.lowercase(java.util.Locale.ROOT)
            val bytes = assets.open("$stem.anchors").use { it.readBytes() }
            val anchors = DataInputStream(bytes.inputStream()).use { input ->
                List(bytes.size / (13 * 4)) {
                    EquippedDogView.FrameLandmark(
                        input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
                        input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
                        input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()
                    )
                }
            }
            val bitmap = assets.open("$stem.webp").use { requireNotNull(BitmapFactory.decodeStream(it)) }
            return GoldenStrip(bitmap, anchors).also { cache.put(motion, it) }
        }
    }
}

internal class GoldenFrameDrawable(val strip: GoldenStrip) : Drawable() {
    private val side = strip.bitmap.width / 4
    private val source = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var index = 0
        set(value) { field = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val x = (index % 4) * side
        val y = (index / 4) * side
        source.set(x, y, x + side, y + side)
        canvas.drawBitmap(strip.bitmap, source, bounds, paint)
    }

    override fun getIntrinsicWidth() = side
    override fun getIntrinsicHeight() = side
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
