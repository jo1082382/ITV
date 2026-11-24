package com.example.itv.presentation.coaching

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.example.itv.R
import com.example.itv.presentation.components.PuppyMood
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Maps the signed difference between ITV's effective intervention threshold and Jev's probability
 * (`gap = effectiveThreshold - jevProbability`) to ITV's 5-stage puppy expressions:
 *
 * - 1단계 해맑 ([PuppyMood.BRIGHT]): 여유 >= 30%p (아주 안정/건강)
 * - 2단계 웃음 ([PuppyMood.SMILE]):  15%p <= 여유 < 30%p (양호)
 * - 3단계 슬픔 ([PuppyMood.SAD]):    5%p  <= 여유 < 15%p (기준 근접 주의)
 * - 4단계 화남 ([PuppyMood.ANGRY]):  0%p  <  여유 < 5%p  (개입 임박 경고)
 * - 5단계 아픔 ([PuppyMood.SICK]):   여유 <= 0%p (Jev 확률 >= 우리 앱 기준 확률, 휴식 필요)
 */
internal fun resolveHudMoodForProbabilityGap(
    jevProbability: Double?,
    effectiveThreshold: Double,
): PuppyMood? {
    if ((jevProbability == null) || !jevProbability.isFinite() || !effectiveThreshold.isFinite()) {
        return null
    }
    val gap = effectiveThreshold - jevProbability
    val eps = 1e-9
    return when {
        gap >= 0.30 - eps -> PuppyMood.BRIGHT
        gap >= 0.15 - eps -> PuppyMood.SMILE
        gap >= 0.05 - eps -> PuppyMood.SAD
        gap > eps -> PuppyMood.ANGRY
        else -> PuppyMood.SICK
    }
}

/**
 * Renders and caches crisp 160x160 circular puppy-face bitmaps for all 5 [PuppyMood] stages
 * directly onto ITV's real puppy head silhouette (`R.drawable.itv_puppy_face`) for the native
 * WindowManager `LongformDiagnosticHud`.
 *
 * Each mood is generated lazily once and cached in memory (~0.5 MB total for all 5 stages,
 * 0 allocations per HUD refresh tick).
 */
internal object HudPuppyFaceRenderer {

    private const val CANVAS_SIZE = 240
    private val cache = ConcurrentHashMap<PuppyMood, Bitmap>()
    @Volatile
    private var blankHeadCache: Bitmap? = null

    fun stageLabel(mood: PuppyMood): String = when (mood) {
        PuppyMood.BRIGHT -> "1단계 해맑"
        PuppyMood.SMILE -> "2단계 웃음"
        PuppyMood.SAD -> "3단계 슬픔"
        PuppyMood.ANGRY -> "4단계 화남"
        PuppyMood.SICK -> "5단계 아픔"
    }

    fun gapRangeLabel(mood: PuppyMood): String = when (mood) {
        PuppyMood.BRIGHT -> "여유 ≥30%p"
        PuppyMood.SMILE -> "여유 15~30%p"
        PuppyMood.SAD -> "여유 5~15%p"
        PuppyMood.ANGRY -> "여유 0~5%p"
        PuppyMood.SICK -> "초과 ≤0%p"
    }

    fun accentColorInt(mood: PuppyMood): Int = when (mood) {
        PuppyMood.BRIGHT -> Color.parseColor("#48C774")
        PuppyMood.SMILE -> Color.parseColor("#38BDF8")
        PuppyMood.SAD -> Color.parseColor("#818CF8")
        PuppyMood.ANGRY -> Color.parseColor("#FB923C")
        PuppyMood.SICK -> Color.parseColor("#F87171")
    }

    private fun backgroundTintInt(mood: PuppyMood): Int = when (mood) {
        PuppyMood.BRIGHT -> Color.parseColor("#E6F8ED")
        PuppyMood.SMILE -> Color.parseColor("#E5F4FE")
        PuppyMood.SAD -> Color.parseColor("#ECEEFF")
        PuppyMood.ANGRY -> Color.parseColor("#FFF0E0")
        PuppyMood.SICK -> Color.parseColor("#FDE8E8")
    }

    fun getFaceBitmap(context: Context, mood: PuppyMood): Bitmap {
        cache[mood]?.let { return it }
        val built = renderMoodBitmap(context.applicationContext, mood)
        cache.putIfAbsent(mood, built)
        return cache[mood] ?: built
    }

    private fun getOrBuildBlankHead(context: Context): Bitmap? {
        blankHeadCache?.let { return it }
        return synchronized(this) {
            blankHeadCache?.let { return it }
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeResource(context.resources, R.drawable.itv_puppy_face, opts)
                ?: return null
            val targetW = 300
            val targetH = ((raw.height.toFloat() / raw.width.coerceAtLeast(1)) * targetW).roundToInt().coerceAtLeast(1)
            val scaled = if (raw.width != targetW || raw.height != targetH) {
                raw.scale(targetW, targetH, filter = true).also { raw.recycle() }
            } else {
                raw
            }

            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            scaled.recycle()

            val furRgb = 0x00FFFEFC
            val furR = 255f
            val furG = 254f
            val furB = 252f
            val outR = 43f
            val outG = 35f
            val outB = 30f
            val dR = outR - furR
            val dG = outG - furG
            val dB = outB - furB
            val invW = 1f / w
            val invH = 1f / h

            val outPixels = IntArray(w * h)
            for (py in 0 until h) {
                val ny = py * invH
                val row = py * w
                val yInEyes = ny in 0.41f..0.61f
                val yInMouth = ny in 0.56f..0.855f
                for (px in 0 until w) {
                    val idx = row + px
                    val pxVal = pixels[idx]
                    val a = (pxVal ushr 24) and 0xFF
                    if (a < 32) continue

                    val nx = px * invW
                    if ((yInEyes && nx in 0.29f..0.72f) || (yInMouth && nx in 0.36f..0.67f)) {
                        outPixels[idx] = (a shl 24) or furRgb
                        continue
                    }

                    val r = (pxVal ushr 16) and 0xFF
                    val g = (pxVal ushr 8) and 0xFF
                    val b = pxVal and 0xFF
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b
                    val linear = ((168f - lum) / 98f).coerceIn(0f, 1f)
                    val factor = linear * linear
                    val nr = (furR + dR * factor).roundToInt().coerceIn(0, 255)
                    val ng = (furG + dG * factor).roundToInt().coerceIn(0, 255)
                    val nb = (furB + dB * factor).roundToInt().coerceIn(0, 255)
                    outPixels[idx] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
            val head = createBitmap(w, h)
            head.setPixels(outPixels, 0, w, 0, 0, w, h)
            blankHeadCache = head
            head
        }
    }

    private fun renderMoodBitmap(context: Context, mood: PuppyMood): Bitmap {
        val out = createBitmap(CANVAS_SIZE, CANVAS_SIZE)
        val canvas = Canvas(out)
        val size = CANVAS_SIZE.toFloat()

        // 1. Soft mood-tinted background disc
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundTintInt(mood)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, size, size, bgPaint)

        // 2. Zoomed-in puppy head (ears crop slightly past circular edge so expressions are large & crisp)
        val head = getOrBuildBlankHead(context)
        val aspect = if (head != null && head.height > 0) head.width.toFloat() / head.height else (832f / 599f)
        val imgW = size * 1.24f
        val imgH = imgW / aspect
        val imgLeft = size * 0.50f - 0.515f * imgW
        val imgTop = (size - imgH) * 0.52f

        if (head != null) {
            val srcRect = Rect(0, 0, head.width, head.height)
            val dstRect = RectF(imgLeft, imgTop, imgLeft + imgW, imgTop + imgH)
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(head, srcRect, dstRect, bmpPaint)
        }

        fun fx(nx: Float): Float = imgLeft + nx * imgW
        fun fy(ny: Float): Float = imgTop + ny * imgH

        val inkColor = Color.parseColor("#2B231E")
        val lineW = (imgW * 0.028f).coerceAtLeast(3.0f)
        val thinW = (imgW * 0.021f).coerceAtLeast(2.3f)

        val inkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.FILL
        }
        val inkStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.STROKE
            strokeWidth = lineW
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val thinStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.STROKE
            strokeWidth = thinW
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // 3. Dainty puppy nose dot
        val noseX = fx(0.515f)
        val noseY = fy(0.612f)
        canvas.drawOval(
            noseX - imgW * 0.032f,
            noseY - imgH * 0.024f,
            noseX + imgW * 0.032f,
            noseY + imgH * 0.024f,
            inkFill
        )

        // 4. 5-stage expressive eyes, cheeks, mouth & emotional cues
        when (mood) {
            PuppyMood.BRIGHT -> {
                // Soft rosy cheeks
                val blush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#55FF8FAB")
                    style = Paint.Style.FILL
                }
                canvas.drawOval(fx(0.285f), fy(0.585f), fx(0.385f), fy(0.648f), blush)
                canvas.drawOval(fx(0.645f), fy(0.585f), fx(0.745f), fy(0.648f), blush)

                // Happy ^ ^ arched eyes
                val leftEye = Path().apply {
                    moveTo(fx(0.352f), fy(0.530f))
                    quadTo(fx(0.405f), fy(0.440f), fx(0.458f), fy(0.530f))
                }
                val rightEye = Path().apply {
                    moveTo(fx(0.572f), fy(0.530f))
                    quadTo(fx(0.625f), fy(0.440f), fx(0.678f), fy(0.530f))
                }
                canvas.drawPath(leftEye, inkStroke)
                canvas.drawPath(rightEye, inkStroke)

                // Playful pink tongue filled + delicate ink outline
                val tonguePath = Path().apply {
                    moveTo(fx(0.465f), fy(0.700f))
                    lineTo(fx(0.465f), fy(0.785f))
                    cubicTo(fx(0.465f), fy(0.858f), fx(0.565f), fy(0.858f), fx(0.565f), fy(0.785f))
                    lineTo(fx(0.565f), fy(0.700f))
                    close()
                }
                val tongueFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FF7A9C")
                    style = Paint.Style.FILL
                }
                canvas.drawPath(tonguePath, tongueFill)
                canvas.drawPath(tonguePath, thinStroke)

                val mouthTop = Path().apply {
                    moveTo(fx(0.420f), fy(0.678f))
                    quadTo(fx(0.515f), fy(0.728f), fx(0.610f), fy(0.678f))
                }
                canvas.drawPath(mouthTop, thinStroke)

                // Bright celebration rays inside circular frame above forehead
                val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#22A055")
                    style = Paint.Style.STROKE
                    strokeWidth = thinW * 0.95f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(fx(0.515f), fy(0.115f), fx(0.515f), fy(0.045f), rayPaint)
                canvas.drawLine(fx(0.405f), fy(0.145f), fx(0.370f), fy(0.085f), rayPaint)
                canvas.drawLine(fx(0.625f), fy(0.145f), fx(0.660f), fy(0.085f), rayPaint)
            }

            PuppyMood.SMILE -> {
                // Soft rosy cheeks
                val blush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#44FF8FAB")
                    style = Paint.Style.FILL
                }
                canvas.drawOval(fx(0.285f), fy(0.585f), fx(0.385f), fy(0.648f), blush)
                canvas.drawOval(fx(0.645f), fy(0.585f), fx(0.745f), fy(0.648f), blush)

                // Glossy round eyes with crisp white catchlights
                val eyeR = imgW * 0.033f
                val lEyeX = fx(0.405f)
                val rEyeX = fx(0.625f)
                val eyeY = fy(0.515f)
                canvas.drawCircle(lEyeX, eyeY, eyeR, inkFill)
                canvas.drawCircle(rEyeX, eyeY, eyeR, inkFill)

                val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(lEyeX - eyeR * 0.30f, eyeY - eyeR * 0.30f, eyeR * 0.32f, whiteFill)
                canvas.drawCircle(rEyeX - eyeR * 0.30f, eyeY - eyeR * 0.30f, eyeR * 0.32f, whiteFill)

                // Delicate 'w' puppy smile
                val smile = Path().apply {
                    moveTo(fx(0.422f), fy(0.692f))
                    quadTo(fx(0.468f), fy(0.762f), fx(0.515f), fy(0.684f))
                    quadTo(fx(0.562f), fy(0.762f), fx(0.608f), fy(0.692f))
                }
                canvas.drawPath(smile, thinStroke)
            }

            PuppyMood.SAD -> {
                // Drooping worried eyebrows
                canvas.drawLine(fx(0.350f), fy(0.462f), fx(0.440f), fy(0.422f), thinStroke)
                canvas.drawLine(fx(0.680f), fy(0.462f), fx(0.590f), fy(0.422f), thinStroke)

                // Soft round sad eyes
                val eyeR = imgW * 0.030f
                canvas.drawCircle(fx(0.405f), fy(0.525f), eyeR, inkFill)
                canvas.drawCircle(fx(0.625f), fy(0.525f), eyeR, inkFill)

                // Downturned frown
                val frown = Path().apply {
                    moveTo(fx(0.435f), fy(0.755f))
                    quadTo(fx(0.515f), fy(0.682f), fx(0.595f), fy(0.755f))
                }
                canvas.drawPath(frown, thinStroke)

                // Sky-blue teardrop under right eye
                val tx = fx(0.662f)
                val ty = fy(0.618f)
                val tr = imgW * 0.031f
                val tearPath = Path().apply {
                    moveTo(tx, ty - tr * 1.55f)
                    quadTo(tx + tr * 1.25f, ty + tr * 0.55f, tx, ty + tr * 1.15f)
                    quadTo(tx - tr * 1.25f, ty + tr * 0.55f, tx, ty - tr * 1.55f)
                    close()
                }
                val tearFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#60A5FA")
                    style = Paint.Style.FILL
                }
                val tearRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#2563EB")
                    style = Paint.Style.STROKE
                    strokeWidth = thinW * 0.75f
                }
                canvas.drawPath(tearPath, tearFill)
                canvas.drawPath(tearPath, tearRim)
            }

            PuppyMood.ANGRY -> {
                // Sharp furrowed angry brows \ /
                canvas.drawLine(fx(0.345f), fy(0.432f), fx(0.455f), fy(0.484f), inkStroke)
                canvas.drawLine(fx(0.685f), fy(0.432f), fx(0.575f), fy(0.484f), inkStroke)

                val eyeR = imgW * 0.025f
                canvas.drawCircle(fx(0.410f), fy(0.540f), eyeR, inkFill)
                canvas.drawCircle(fx(0.620f), fy(0.540f), eyeR, inkFill)

                // Grumpy inverted-V mouth
                val angryMouth = Path().apply {
                    moveTo(fx(0.422f), fy(0.755f))
                    lineTo(fx(0.515f), fy(0.692f))
                    lineTo(fx(0.608f), fy(0.755f))
                }
                canvas.drawPath(angryMouth, thinStroke)

                // Vivid orange-red comic anger mark on upper-right temple (inside circular border)
                val angerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#EA580C")
                    style = Paint.Style.STROKE
                    strokeWidth = thinW * 0.95f
                    strokeCap = Paint.Cap.ROUND
                }
                val cx = fx(0.705f)
                val cy = fy(0.245f)
                val r = imgW * 0.048f
                val g = r * 0.28f
                canvas.drawLine(cx - r, cy - g, cx - g, cy - g, angerPaint)
                canvas.drawLine(cx - g, cy - r, cx - g, cy - g, angerPaint)
                canvas.drawLine(cx + r, cy + g, cx + g, cy + g, angerPaint)
                canvas.drawLine(cx + g, cy + r, cx + g, cy + g, angerPaint)
                canvas.drawLine(cx + r, cy - g, cx + g, cy - g, angerPaint)
                canvas.drawLine(cx + g, cy - r, cx + g, cy - g, angerPaint)
                canvas.drawLine(cx - r, cy + g, cx - g, cy + g, angerPaint)
                canvas.drawLine(cx - g, cy + r, cx - g, cy + g, angerPaint)
            }

            PuppyMood.SICK -> {
                // Feverish red cheek flush
                val feverBlush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#66EF4444")
                    style = Paint.Style.FILL
                }
                canvas.drawOval(fx(0.285f), fy(0.585f), fx(0.395f), fy(0.655f), feverBlush)
                canvas.drawOval(fx(0.635f), fy(0.585f), fx(0.745f), fy(0.655f), feverBlush)

                // Weak > < eyes
                val leftSick = Path().apply {
                    moveTo(fx(0.358f), fy(0.468f))
                    lineTo(fx(0.440f), fy(0.515f))
                    lineTo(fx(0.358f), fy(0.562f))
                }
                val rightSick = Path().apply {
                    moveTo(fx(0.672f), fy(0.468f))
                    lineTo(fx(0.590f), fy(0.515f))
                    lineTo(fx(0.672f), fy(0.562f))
                }
                canvas.drawPath(leftSick, thinStroke)
                canvas.drawPath(rightSick, thinStroke)

                // Small weak 'o' mouth
                canvas.drawOval(fx(0.478f), fy(0.698f), fx(0.552f), fy(0.768f), thinStroke)

                // Diagonal thermometer angled toward bottom-left (away from bottom-right HUD status dot)
                val sx = fx(0.505f)
                val sy = fy(0.735f)
                val ex = fx(0.310f)
                val ey = fy(0.848f)
                val dx = ex - sx
                val dy = ey - sy
                val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                val halfT = imgW * 0.019f
                val nx = -dy / len * halfT
                val ny = dx / len * halfT

                val thermoBody = Path().apply {
                    moveTo(sx + nx, sy + ny)
                    lineTo(ex + nx, ey + ny)
                    lineTo(ex - nx, ey - ny)
                    lineTo(sx - nx, sy - ny)
                    close()
                }
                val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#DC2626")
                    style = Paint.Style.FILL
                }
                val redStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#DC2626")
                    style = Paint.Style.STROKE
                    strokeWidth = thinW * 0.85f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawPath(thermoBody, whiteFill)
                canvas.drawPath(thermoBody, thinStroke)
                canvas.drawLine(sx + dx * 0.28f, sy + dy * 0.28f, ex, ey, redStroke)
                canvas.drawCircle(ex, ey, imgW * 0.029f, redPaint)

                // Fever steam waves positioned cleanly inside circular rim above forehead
                val steamXs = floatArrayOf(0.43f, 0.515f, 0.60f)
                for (i in steamXs.indices) {
                    val bx = steamXs[i]
                    val wave = Path().apply {
                        moveTo(fx(bx), fy(0.145f))
                        quadTo(fx(bx - 0.020f), fy(0.095f), fx(bx), fy(0.048f))
                    }
                    canvas.drawPath(wave, redStroke)
                }
            }
        }

        return out
    }
}
