package com.example.itv.presentation.usage

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.example.itv.R
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Normalized landmark mapping & clear-mask configuration for a stamp character's head drawable.
 *
 * Default coordinates match `R.drawable.itv_puppy_face`. When swapping or adding a new character
 * whose eyes/mouth sit at a slightly different spot inside its drawable, adjust only the mask
 * ranges and `faceOffsetX / faceOffsetY / faceScaleX / faceScaleY` — all 5 tier expressions
 * in both `DailyHealthStampBadge` and `DailyStampRitualOverlay` automatically realign.
 */
internal data class CharacterFaceLandmarks(
    // Source-image normalized regions cleared before drawing dynamic 5-tier expressions
    // (0.29..0.72 cleanly clears the original left eye edge at nx=0.319 while staying ~30px inside cheeks)
    val eyesMaskX: ClosedFloatingPointRange<Float> = 0.29f..0.72f,
    val eyesMaskY: ClosedFloatingPointRange<Float> = 0.41f..0.61f,
    val mouthMaskX: ClosedFloatingPointRange<Float> = 0.36f..0.67f,
    val mouthMaskY: ClosedFloatingPointRange<Float> = 0.56f..0.855f,
    // Optional affine adjustment of the 5-tier expression coordinates around face pivot (0.515, 0.60)
    val faceOffsetX: Float = 0f,
    val faceOffsetY: Float = 0f,
    val faceScaleX: Float = 1f,
    val faceScaleY: Float = 1f
) {
    /**
     * Maps a canonical normalized face landmark `(nx, ny)` into pixel coordinates inside
     * the rendered head box `(imgLeft, imgTop, imgW, imgH)`.
     */
    fun toCanvasOffset(
        nx: Float,
        ny: Float,
        imgLeft: Float,
        imgTop: Float,
        imgW: Float,
        imgH: Float
    ): Offset {
        val adjX = 0.515f + (nx - 0.515f) * faceScaleX + faceOffsetX
        val adjY = 0.600f + (ny - 0.600f) * faceScaleY + faceOffsetY
        return Offset(imgLeft + adjX * imgW, imgTop + adjY * imgH)
    }
}

/**
 * Complete plug-and-play definition for a stamp mascot character.
 *
 * Both the monochrome calendar rubber stamps (`DailyHealthStampBadge`) and the full-screen 3D
 * stamping animation (`DailyStampRitualOverlay`) read exclusively from this spec.
 *
 * ### How to replace or add a character later:
 * 1. Add a transparent PNG face drawable to `res/drawable-nodpi/`.
 * 2. Define a new `StampCharacterSpec` in [StampCharacterRegistry] (specifying `id`, `faceDrawableRes`,
 *    `aspectRatio`, `furColor`, `furShadeColor`, `outlineColor`, and optional `landmarks`).
 * 3. Add it to `StampCharacterRegistry.all` (or switch `default` / save the user's pick via
 *    `DailyStampPrefs.setSelectedCharacterId`).
 */
/**
 * Normalized eye-center & tilt landmarks (0f..1f of the 400x400 gallop sprite) so live 5-tier
 * expressions align 1:1 onto the blank-face gallop frames during run-in!
 */
internal data class GallopFrameLandmark(
    val eyeCenterX: Float,
    val eyeCenterY: Float,
    val eyeAngleDeg: Float,
    val eyeSpan: Float
)

internal data class StampCharacterSpec(
    val id: String,
    val displayName: String,
    @param:DrawableRes val faceDrawableRes: Int,
    val aspectRatio: Float,
    val furColor: Color = Color(0xFFFFFEFC),
    val furShadeColor: Color = Color(0xFFF0ECE6),
    val outlineColor: Color = Color(0xFF2B231E),
    val headScaleAtRest: Float = 2.75f,
    val headScaleAtImpact: Float = 3.15f,
    val landmarks: CharacterFaceLandmarks = CharacterFaceLandmarks(),
    // Original intervention running-in gallop sprite frames (400x400) + arrival arms-out leap pose
    val frontGallopDrawables: List<Int> = listOf(
        R.drawable.itv_gallop_front_01,
        R.drawable.itv_gallop_front_02,
        R.drawable.itv_gallop_front_03,
        R.drawable.itv_gallop_front_04,
        R.drawable.itv_gallop_front_05,
        R.drawable.itv_gallop_front_06
    ),
    val frontGallopLandmarks: List<GallopFrameLandmark> = listOf(
        GallopFrameLandmark(206.4f / 400f, 132.2f / 400f, 1.1f, 67.3f / 400f),
        GallopFrameLandmark(202.0f / 400f, 130.2f / 400f, 3.3f, 66.1f / 400f),
        GallopFrameLandmark(216.1f / 400f, 132.3f / 400f, -11.9f, 59.7f / 400f),
        GallopFrameLandmark(193.4f / 400f, 135.9f / 400f, 10.6f, 65.7f / 400f),
        GallopFrameLandmark(200.7f / 400f, 133.6f / 400f, 3.6f, 60.7f / 400f),
        GallopFrameLandmark(204.5f / 400f, 132.2f / 400f, -0.1f, 55.6f / 400f)
    ),
    @param:DrawableRes val arriveLeapDrawable: Int = R.drawable.itv_gallop_front_00,
    val arriveLeapLandmark: GallopFrameLandmark = GallopFrameLandmark(
        eyeCenterX = 210.9f / 400f,
        eyeCenterY = 135.6f / 400f,
        eyeAngleDeg = -5.2f,
        eyeSpan = 66.0f / 400f
    ),
    // Outline extraction tuning for the simple live head vs. monochrome rubber stamp
    val liveOutlineThreshold: Float = 168f,
    val liveOutlineSpread: Float = 98f,
    val stampOutlineThreshold: Float = 184f,
    val stampOutlineSpread: Float = 118f
)

/**
 * Central registry for all stamp characters.
 * Adding a new mascot or replacing the current mascot only touches this object.
 */
internal object StampCharacterRegistry {
    /** Default ITV white puppy mascot (Simple Pure-White style) */
    val PUPPY = StampCharacterSpec(
        id = "itv_puppy",
        displayName = "하얀 강아지",
        faceDrawableRes = R.drawable.itv_puppy_face,
        aspectRatio = 832f / 599f,
        furColor = Color(0xFFFFFEFC),
        furShadeColor = Color(0xFFF0ECE6),
        outlineColor = Color(0xFF2B231E),
        landmarks = CharacterFaceLandmarks()
    )

    // 추후 새 캐릭터(예: 고양이, 곰돌이 등)를 추가할 때 여기에 StampCharacterSpec을 정의하고 all 목록에 등록하세요.
    val all: List<StampCharacterSpec> = listOf(PUPPY)

    val default: StampCharacterSpec get() = PUPPY

    fun findById(id: String?): StampCharacterSpec =
        if (id.isNullOrBlank()) default else all.firstOrNull { it.id == id } ?: default
}

/**
 * Per-character thread-safe bitmap cache keyed by [StampCharacterSpec.id], providing:
 * 1. Pure monochrome ink-outline silhouettes for `DailyHealthStampBadge`
 * 2. Clean solid-fur + crisp-outline blank heads for `DailyStampRitualOverlay`
 * 3. Clean open-neck back-of-head silhouettes for the walk-away back view
 * 4. Clean blank-face, pure-white + crisp-outline intervention front-gallop sprite frames
 *    (`itv_gallop_front_01..06` + `00`) ready for dynamic 5-tier live expressions!
 *
 * Call [preload] from a background thread (`Dispatchers.Default`) to warm all bitmaps off-thread
 * with zero UI jank.
 */
internal object StampCharacterBitmapCache {
    private val outlineCache = ConcurrentHashMap<String, ImageBitmap>()
    private val simpleHeadCache = ConcurrentHashMap<String, ImageBitmap>()
    private val simpleBackHeadCache = ConcurrentHashMap<String, ImageBitmap>()
    private val frontGallopCache = ConcurrentHashMap<String, List<ImageBitmap>>()
    private val arriveLeapCache = ConcurrentHashMap<String, ImageBitmap>()

    // Fast precomputed gamma lookup tables (replaces ~250k Math.pow calls inside pixel loops!)
    private val stampGammaLut = FloatArray(256) { i -> (i / 255f).toDouble().pow(0.54).toFloat() }
    private val liveGammaLut = FloatArray(256) { i -> (i / 255f).toDouble().pow(0.82).toFloat() }

    private fun fastLut(lut: FloatArray, value: Float): Float =
        lut[(value.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)]

    fun getCachedMonochromeOutline(specId: String): ImageBitmap? = outlineCache[specId]
    fun getCachedSimpleHead(specId: String): ImageBitmap? = simpleHeadCache[specId]
    fun getCachedSimpleBackHead(specId: String): ImageBitmap? = simpleBackHeadCache[specId]
    fun getCachedFrontGallopFrames(specId: String): List<ImageBitmap>? = frontGallopCache[specId]
    fun getCachedArriveLeapFrame(specId: String): ImageBitmap? = arriveLeapCache[specId]

    /**
     * Decodes [StampCharacterSpec.faceDrawableRes] ONCE at an optimal render resolution (420px wide)
     * and generates all cached bitmaps (plus the clean blank-face intervention gallop frames). Safe
     * to call repeatedly; returns immediately when already warm.
     */
    fun preload(context: Context, spec: StampCharacterSpec) {
        if (outlineCache.containsKey(spec.id) &&
            simpleHeadCache.containsKey(spec.id) &&
            simpleBackHeadCache.containsKey(spec.id) &&
            frontGallopCache.containsKey(spec.id) &&
            arriveLeapCache.containsKey(spec.id)
        ) {
            return
        }
        synchronized(this) {
            val needOutline = !outlineCache.containsKey(spec.id)
            val needHead = !simpleHeadCache.containsKey(spec.id)
            val needBack = !simpleBackHeadCache.containsKey(spec.id)
            val needGallop = !frontGallopCache.containsKey(spec.id)
            val needArrive = !arriveLeapCache.containsKey(spec.id)
            if (!needOutline && !needHead && !needBack && !needGallop && !needArrive) return

            if (needOutline || needHead || needBack) {
                runCatching {
                    val raw = BitmapFactory.decodeResource(context.resources, spec.faceDrawableRes)
                        ?: return@runCatching
                    val targetW = 420.coerceAtMost(raw.width)
                    val targetH = (targetW / spec.aspectRatio).roundToInt().coerceAtLeast(1)
                    val scaled = if (raw.width != targetW || raw.height != targetH) {
                        raw.scale(targetW, targetH, filter = true).also {
                            if (it !== raw) raw.recycle()
                        }
                    } else {
                        raw
                    }

                    val srcPixels = IntArray(targetW * targetH)
                    scaled.getPixels(srcPixels, 0, targetW, 0, 0, targetW, targetH)
                    scaled.recycle()

                    if (needOutline) {
                        outlineCache[spec.id] = buildMonochromeOutline(srcPixels, targetW, targetH, spec)
                    }
                    if (needHead) {
                        simpleHeadCache[spec.id] = buildSimpleHead(srcPixels, targetW, targetH, spec)
                    }
                    if (needBack) {
                        simpleBackHeadCache[spec.id] = buildSimpleBackHead(srcPixels, targetW, targetH, spec)
                    }
                }
            }

            if (needGallop) {
                val decoded = spec.frontGallopDrawables.mapNotNull { resId ->
                    buildCleanGallopSprite(context, resId, spec)
                }
                if (decoded.isNotEmpty()) {
                    frontGallopCache[spec.id] = decoded
                }
            }
            if (needArrive) {
                buildCleanGallopSprite(context, spec.arriveLeapDrawable, spec)?.let {
                    arriveLeapCache[spec.id] = it
                }
            }
        }
    }

    /**
     * Converts a 400x400 intervention gallop sprite into a clean headless 4-legged running body
     * (`py >= 50.5%`, where the narrow neck cleanly meets the shoulders on all 6 frames `01..06`)
     * in the EXACT same pure-white fur + crisp warm-dark outline art style as [buildSimpleHead].
     *
     * Pairing this running 4-legged body with the real chibi `simpleHeadBitmap` + `drawPuppyLiveExpression`
     * guarantees 100% identical face/head proportions from far run-in to stamping with ZERO erratic
     * frame-to-frame head jitter!
     */
    private fun buildCleanGallopSprite(
        context: Context,
        @DrawableRes resId: Int,
        spec: StampCharacterSpec
    ): ImageBitmap? = runCatching {
        val raw = BitmapFactory.decodeResource(context.resources, resId) ?: return null
        val maxDim = 360
        val maxSide = maxOf(raw.width, raw.height).coerceAtLeast(1)
        val scaled = if (maxSide > maxDim) {
            val ratio = maxDim.toFloat() / maxSide.toFloat()
            val tw = (raw.width * ratio).roundToInt().coerceAtLeast(1)
            val th = (raw.height * ratio).roundToInt().coerceAtLeast(1)
            raw.scale(tw, th, filter = true).also {
                if (it !== raw) raw.recycle()
            }
        } else {
            raw
        }

        val w = scaled.width
        val h = scaled.height
        val total = w * h
        val src = IntArray(total)
        scaled.getPixels(src, 0, w, 0, 0, w, h)
        scaled.recycle()

        val furArgb = spec.furColor.toArgb()
        val furR = ((furArgb ushr 16) and 0xFF).toFloat()
        val furG = ((furArgb ushr 8) and 0xFF).toFloat()
        val furB = (furArgb and 0xFF).toFloat()

        val outArgb = spec.outlineColor.toArgb()
        val dR = ((outArgb ushr 16) and 0xFF) - furR
        val dG = ((outArgb ushr 8) and 0xFF) - furG
        val dB = (outArgb and 0xFF) - furB

        val threshold = spec.liveOutlineThreshold
        val invSpread = 1f / spec.liveOutlineSpread.coerceAtLeast(1f)
        val neckCutY = (h * 0.505f).roundToInt()
        val outPixels = IntArray(total)

        for (py in neckCutY until h) {
            val row = py * w
            for (px in 0 until w) {
                val idx = row + px
                val pixel = src[idx]
                val a = (pixel ushr 24) and 0xFF
                if (a < 32) continue

                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b

                val linear = ((threshold - lum) * invSpread).coerceIn(0f, 1f)
                val outlineFactor = fastLut(liveGammaLut, linear)
                val nr = (furR + dR * outlineFactor).roundToInt().coerceIn(0, 255)
                val ng = (furG + dG * outlineFactor).roundToInt().coerceIn(0, 255)
                val nb = (furB + dB * outlineFactor).roundToInt().coerceIn(0, 255)
                outPixels[idx] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        val out = createBitmap(w, h)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        out.asImageBitmap()
    }.getOrNull()

    fun getFrontGallopFrames(context: Context, spec: StampCharacterSpec): List<ImageBitmap> {
        frontGallopCache[spec.id]?.let { return it }
        preload(context, spec)
        return frontGallopCache[spec.id].orEmpty()
    }

    fun getArriveLeapFrame(context: Context, spec: StampCharacterSpec): ImageBitmap? {
        arriveLeapCache[spec.id]?.let { return it }
        preload(context, spec)
        return arriveLeapCache[spec.id]
    }

    /**
     * Extracts and caches a pure monochrome outline silhouette (white + alpha, ready for
     * `ColorFilter.tint(ink)`) from [StampCharacterSpec.faceDrawableRes], clearing inner facial landmarks.
     */
    fun getMonochromeOutline(context: Context, spec: StampCharacterSpec): ImageBitmap? {
        outlineCache[spec.id]?.let { return it }
        preload(context, spec)
        return outlineCache[spec.id]
    }

    /**
     * Extracts and caches a clean solid-fur (`spec.furColor`) + crisp-outline (`spec.outlineColor`)
     * blank head bitmap from [StampCharacterSpec.faceDrawableRes], matching the character's paws & body 1:1.
     */
    fun getSimpleHead(context: Context, spec: StampCharacterSpec): ImageBitmap? {
        simpleHeadCache[spec.id]?.let { return it }
        preload(context, spec)
        return simpleHeadCache[spec.id]
    }

    /**
     * Generates and caches a clean BACK-OF-HEAD bitmap from [StampCharacterSpec.faceDrawableRes]:
     * - Fills the entire head & floppy-ear silhouette with solid [StampCharacterSpec.furColor]
     * - Draws a crisp outer contour ([StampCharacterSpec.outlineColor]) derived from the alpha edge
     * - Leaves the bottom-center neck join smoothly open so the head flows seamlessly into the back!
     */
    fun getSimpleBackHead(context: Context, spec: StampCharacterSpec): ImageBitmap? {
        simpleBackHeadCache[spec.id]?.let { return it }
        preload(context, spec)
        return simpleBackHeadCache[spec.id]
    }

    private fun buildMonochromeOutline(
        src: IntArray,
        w: Int,
        h: Int,
        spec: StampCharacterSpec
    ): ImageBitmap {
        val total = w * h
        var alphaMap = FloatArray(total)
        val lm = spec.landmarks
        val threshold = spec.stampOutlineThreshold
        val invSpread = 1f / spec.stampOutlineSpread.coerceAtLeast(1f)
        val invW = 1f / w
        val invH = 1f / h

        for (py in 0 until h) {
            val ny = py * invH
            val rowOffset = py * w
            val yInEyes = ny in lm.eyesMaskY
            val yInMouth = ny in lm.mouthMaskY
            for (px in 0 until w) {
                val idx = rowOffset + px
                val pixel = src[idx]
                val a = (pixel ushr 24) and 0xFF
                if (a < 45) continue

                val nx = px * invW
                if ((yInEyes && nx in lm.eyesMaskX) || (yInMouth && nx in lm.mouthMaskX)) continue

                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b

                val rawOutline = ((threshold - lum) * invSpread).coerceIn(0f, 1f) * (a * (1f / 255f))
                if (rawOutline > 0.03f) {
                    alphaMap[idx] = fastLut(stampGammaLut, rawOutline)
                }
            }
        }

        // Smooth circular-weighted 3x3 morphological dilation (4 passes) so the 420px outline
        // stays bold, chunky, and clearly legible even when scaled down to 39~41dp calendar cells!
        val passes = ((w * 0.0105f).roundToInt()).coerceIn(3, 5)
        var nextMap = FloatArray(total)
        repeat(passes) {
            nextMap.fill(0f)
            for (py in 1 until h - 1) {
                val row = py * w
                val prev = row - w
                val next = row + w
                for (px in 1 until w - 1) {
                    val idx = row + px
                    var m = alphaMap[idx]
                    val cL = alphaMap[idx - 1]
                    val cR = alphaMap[idx + 1]
                    val cU = alphaMap[prev + px]
                    val cD = alphaMap[next + px]
                    if (cL > m) m = cL
                    if (cR > m) m = cR
                    if (cU > m) m = cU
                    if (cD > m) m = cD

                    val dUL = alphaMap[prev + px - 1] * 0.88f
                    val dUR = alphaMap[prev + px + 1] * 0.88f
                    val dDL = alphaMap[next + px - 1] * 0.88f
                    val dDR = alphaMap[next + px + 1] * 0.88f
                    if (dUL > m) m = dUL
                    if (dUR > m) m = dUR
                    if (dDL > m) m = dDL
                    if (dDR > m) m = dDR
                    nextMap[idx] = m
                }
            }
            val tmp = alphaMap
            alphaMap = nextMap
            nextMap = tmp
        }

        val outPixels = IntArray(total)
        for (i in 0 until total) {
            val v = alphaMap[i]
            if (v > 0.03f) {
                val boosted = (v * 1.12f).coerceIn(0f, 1f)
                outPixels[i] = ((boosted * 255f).roundToInt() shl 24) or 0x00FFFFFF
            }
        }
        val out = createBitmap(w, h)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out.asImageBitmap()
    }

    private fun buildSimpleHead(
        src: IntArray,
        w: Int,
        h: Int,
        spec: StampCharacterSpec
    ): ImageBitmap {
        val outPixels = IntArray(w * h)
        val furArgb = spec.furColor.toArgb()
        val furR = ((furArgb ushr 16) and 0xFF).toFloat()
        val furG = ((furArgb ushr 8) and 0xFF).toFloat()
        val furB = (furArgb and 0xFF).toFloat()
        val furRgb = furArgb and 0x00FFFFFF

        val outArgb = spec.outlineColor.toArgb()
        val dR = ((outArgb ushr 16) and 0xFF) - furR
        val dG = ((outArgb ushr 8) and 0xFF) - furG
        val dB = (outArgb and 0xFF) - furB

        val lm = spec.landmarks
        val threshold = spec.liveOutlineThreshold
        val invSpread = 1f / spec.liveOutlineSpread.coerceAtLeast(1f)
        val invW = 1f / w
        val invH = 1f / h

        for (py in 0 until h) {
            val ny = py * invH
            val rowOffset = py * w
            val yInEyes = ny in lm.eyesMaskY
            val yInMouth = ny in lm.mouthMaskY
            for (px in 0 until w) {
                val idx = rowOffset + px
                val pixel = src[idx]
                val a = (pixel ushr 24) and 0xFF
                if (a < 32) continue

                val nx = px * invW
                if ((yInEyes && nx in lm.eyesMaskX) || (yInMouth && nx in lm.mouthMaskX)) {
                    outPixels[idx] = (a shl 24) or furRgb
                    continue
                }

                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b

                val linear = ((threshold - lum) * invSpread).coerceIn(0f, 1f)
                val outlineFactor = fastLut(liveGammaLut, linear)
                val nr = (furR + dR * outlineFactor).roundToInt().coerceIn(0, 255)
                val ng = (furG + dG * outlineFactor).roundToInt().coerceIn(0, 255)
                val nb = (furB + dB * outlineFactor).roundToInt().coerceIn(0, 255)
                outPixels[idx] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        val out = createBitmap(w, h)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out.asImageBitmap()
    }

    private fun buildSimpleBackHead(
        src: IntArray,
        w: Int,
        h: Int,
        spec: StampCharacterSpec
    ): ImageBitmap {
        val total = w * h
        val outPixels = IntArray(total)
        val alphas = IntArray(total)
        val dist = FloatArray(total)
        var centerBottomY = (h * 0.85f).roundToInt()
        var maxFoundY = 0
        val centerMinX = (w * 0.44f).roundToInt()
        val centerMaxX = (w * 0.58f).roundToInt()

        for (py in 0 until h) {
            val row = py * w
            val isYBorder = py == 0 || py == h - 1
            for (px in 0 until w) {
                val idx = row + px
                val a = (src[idx] ushr 24) and 0xFF
                alphas[idx] = a
                if (a >= 128 && px in centerMinX..centerMaxX && py > maxFoundY) {
                    maxFoundY = py
                }
                val isBorder = isYBorder || px == 0 || px == w - 1
                dist[idx] = if (a < 128 || isBorder) 0f else 1000f
            }
        }
        if (maxFoundY > h / 3) centerBottomY = maxFoundY

        // Fast 2-pass Chamfer Euclidean distance transform from alpha silhouette boundary
        for (py in 1 until h - 1) {
            val row = py * w
            val prevRow = row - w
            for (px in 1 until w - 1) {
                val idx = row + px
                var d = dist[idx]
                if (d > 0f) {
                    val dL = dist[idx - 1] + 1f
                    val dU = dist[prevRow + px] + 1f
                    val dUL = dist[prevRow + px - 1] + 1.414f
                    val dUR = dist[prevRow + px + 1] + 1.414f
                    if (dL < d) d = dL
                    if (dU < d) d = dU
                    if (dUL < d) d = dUL
                    if (dUR < d) d = dUR
                    dist[idx] = d
                }
            }
        }
        for (py in h - 2 downTo 1) {
            val row = py * w
            val nextRow = row + w
            for (px in w - 2 downTo 1) {
                val idx = row + px
                var d = dist[idx]
                if (d > 0f) {
                    val dR = dist[idx + 1] + 1f
                    val dD = dist[nextRow + px] + 1f
                    val dDL = dist[nextRow + px - 1] + 1.414f
                    val dDR = dist[nextRow + px + 1] + 1.414f
                    if (dR < d) d = dR
                    if (dD < d) d = dD
                    if (dDL < d) d = dDL
                    if (dDR < d) d = dDR
                    dist[idx] = d
                }
            }
        }

        val furArgb = spec.furColor.toArgb()
        val furR = ((furArgb ushr 16) and 0xFF).toFloat()
        val furG = ((furArgb ushr 8) and 0xFF).toFloat()
        val furB = (furArgb and 0xFF).toFloat()
        val furRgb = furArgb and 0x00FFFFFF

        val outArgb = spec.outlineColor.toArgb()
        val dR = ((outArgb ushr 16) and 0xFF) - furR
        val dG = ((outArgb ushr 8) and 0xFF) - furG
        val dB = (outArgb and 0xFF) - furB

        val outlinePx = (w * 0.0155f).coerceIn(3.5f, 12f)
        val neckFadeTopY = centerBottomY - outlinePx * 2.2f
        val neckOpenY = centerBottomY - outlinePx * 1.15f
        val invNeckSpan = 1f / (neckOpenY - neckFadeTopY).coerceAtLeast(1f)
        val invW = 1f / w

        for (py in 0 until h) {
            val row = py * w
            val vertMask = if (py <= neckFadeTopY) {
                0f
            } else {
                ((py - neckFadeTopY) * invNeckSpan).coerceIn(0f, 1f)
            }
            for (px in 0 until w) {
                val idx = row + px
                val a = alphas[idx]
                if (a < 32) continue

                var outlineFactor = ((outlinePx + 0.8f - dist[idx]) * 0.5f).coerceIn(0f, 1f)
                if (outlineFactor <= 0.001f) {
                    outPixels[idx] = (a shl 24) or furRgb
                    continue
                }
                if (vertMask > 0f) {
                    val nx = px * invW
                    val horizMask = when {
                        nx <= 0.32f || nx >= 0.70f -> 0f
                        nx < 0.38f -> (nx - 0.32f) / 0.06f
                        nx > 0.64f -> (0.70f - nx) / 0.06f
                        else -> 1f
                    }
                    outlineFactor *= (1f - horizMask * vertMask)
                }
                val nr = (furR + dR * outlineFactor).roundToInt().coerceIn(0, 255)
                val ng = (furG + dG * outlineFactor).roundToInt().coerceIn(0, 255)
                val nb = (furB + dB * outlineFactor).roundToInt().coerceIn(0, 255)
                outPixels[idx] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        val out = createBitmap(w, h)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out.asImageBitmap()
    }
}
