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
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.io.DataInputStream

internal enum class GoldenMotion(val durationMs: Long, val repeat: Boolean = false) {
    IDLE(2150, true), PEEK(950), PEEK_LEFT(950), APPROACH(670, true), JUMP(1080),
    LIFT(340), LOWER(360), DROWSY(530), LIE(990), SLEEP(2250, true), WAKE(940),
    SAD(450), REFUSE(900), FIRM(510), WAVE(850), GIVE(1120),
    RECEIVE(640), TURN_LEFT(860), TURN_RIGHT(860), SIDE_LEFT(670, true), SIDE_RIGHT(670, true)
}

/** Off-screen time and time spent waiting for an atlas do not consume an authored one-shot. */
internal class GoldenPlaybackClock {
    private var accumulated = 0L
    private var runningSince: Long? = null
    val running get() = runningSince != null

    fun seek(offset: Long) {
        accumulated = offset
        runningSince = null
    }

    fun elapsed(now: Long) = accumulated + (runningSince?.let { now - it } ?: 0L)
    fun resume(now: Long) { if (!running) runningSince = now }

    fun pause(now: Long) {
        accumulated = elapsed(now)
        runningSince = null
    }
}

internal class GoldenStrip(val bitmap: Bitmap, val anchors: List<EquippedDogView.FrameLandmark>) {
    companion object {
        private val cache = object : LruCache<GoldenMotion, GoldenStrip>(28 * 1024 * 1024) {
            override fun sizeOf(key: GoldenMotion, value: GoldenStrip) = value.bitmap.allocationByteCount
        }

        private val decoder = ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, LinkedBlockingQueue())
        private val main = Handler(Looper.getMainLooper())
        private val pending = mutableMapOf<GoldenMotion, Load>()

        private class Load {
            val listeners = mutableListOf<(GoldenStrip?) -> Unit>()
            lateinit var work: Runnable
        }

        // Main-thread callers can unsubscribe on input changes or detach. Discard an
        // unstarted decode if no view still needs it; another view may share the job.
        fun request(assets: AssetManager, motion: GoldenMotion, ready: (GoldenStrip?) -> Unit): Runnable? {
            cache.get(motion)?.let { ready(it); return null }
            val load = pending.getOrPut(motion) {
                Load().also { job ->
                    job.work = Runnable {
                        val strip = try { decode(assets, motion) } catch (e: Exception) {
                            android.util.Log.e("GoldenMotion", "Unable to decode ${motion.name}", e)
                            null
                        }
                        main.post {
                            if (strip != null) cache.put(motion, strip)
                            pending.remove(motion)
                            job.listeners.toList().forEach { it(strip) }
                            job.listeners.clear()
                        }
                    }
                    decoder.execute(job.work)
                }
            }
            load.listeners.add(ready)
            return Runnable {
                load.listeners.remove(ready)
                if (load.listeners.isEmpty() && decoder.remove(load.work)) pending.remove(motion)
            }
        }

        private fun decode(assets: AssetManager, motion: GoldenMotion): GoldenStrip {
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
            bitmap.prepareToDraw()
            return GoldenStrip(bitmap, anchors)
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
