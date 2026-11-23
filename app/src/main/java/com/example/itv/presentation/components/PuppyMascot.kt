package com.example.itv.presentation.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.example.itv.R
import com.example.itv.domain.equip.EquipItem
import com.example.itv.domain.equip.EquipSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 5 expressive moods & distinct body poses for the character mascot,
 * mapped 1:1 to yesterday's calendar `DailyHealthTier`:
 * - [BRIGHT] (`GOLD`): 해맑 — 양팔 벌려 팔짝팔짝 점프하는 자세 (`itv_gallop_front_00`)
 * - [SMILE] (`SILVER`): 웃음 — 기분 좋게 서서 웃는 기본 자세 (`itv_puppy_mascot`)
 * - [SAD] (`BRONZE`): 슬픔 — 축 처져 눈물 흘리는 자세 (`itv_motion_sad`)
 * - [ANGRY] (`WARNING`): 화남 — 네 발을 바닥에 딛고 화면을 향해 짖는 자세 (`itv_motion_angry`)
 * - [SICK] (`CRITICAL`): 아픔 — 취침(자는) 자세로 웅크려 벌벌 떠는 자세 (`itv_motion_sleep`)
 */
enum class PuppyMood(
    val label: String,
) {
    BRIGHT("해맑"),
    SMILE("웃음"),
    SAD("슬픔"),
    ANGRY("화남"),
    SICK("아픔")
}

private data class NormalizedRect(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float
)

private data class EquipAnchor(
    val centerX: Float,
    val centerY: Float,
    val sizeFraction: Float,
    val rotationDeg: Float = 0f
)

private data class PuppyPoseSpec(
    @param:DrawableRes val drawableRes: Int,
    val srcW: Float,
    val srcH: Float,
    val wipeRects: List<NormalizedRect>,
    val furSamplePoints: List<Pair<Float, Float>>,
    val leftEyeNx: Float,
    val leftEyeNy: Float,
    val rightEyeNx: Float,
    val rightEyeNy: Float,
    val noseOffsetFactor: Float,
    val mouthOffsetFactor: Float,
    val drawNose: Boolean,
    val shadowWidthFraction: Float,
    val shadowOffsetYFraction: Float,
    val hatAnchor: EquipAnchor,
    val glassesAnchor: EquipAnchor,
    val noseAnchor: EquipAnchor,
    val mouthAnchor: EquipAnchor,
    val clothesAnchor: EquipAnchor, // centerX, topY
    val shoesAnchor: EquipAnchor
)

private object PuppyPoseCatalog {
    val OUTLINE_INK = Color(0xFF2B201B)
    val DEFAULT_FUR_COLOR = Color(0xFFFFFFFF)

    private val brightPose = PuppyPoseSpec(
        drawableRes = R.drawable.itv_gallop_front_00,
        srcW = 400f,
        srcH = 400f,
        wipeRects = listOf(
            NormalizedRect(138f / 400f, 264f / 400f, 105f / 400f, 144f / 400f),
            NormalizedRect(148f / 400f, 256f / 400f, 140f / 400f, 160f / 400f),
            NormalizedRect(152f / 400f, 246f / 400f, 156f / 400f, 185f / 400f)
        ),
        furSamplePoints = listOf(
            (204f / 400f) to (78f / 400f),
            (176f / 400f) to (88f / 400f),
            (234f / 400f) to (88f / 400f)
        ),
        leftEyeNx = 160f / 400f,
        leftEyeNy = 124f / 400f,
        rightEyeNx = 246f / 400f,
        rightEyeNy = 130f / 400f,
        noseOffsetFactor = 0.26f,
        mouthOffsetFactor = 0.52f,
        drawNose = true,
        shadowWidthFraction = 0.62f,
        shadowOffsetYFraction = 0.02f,
        hatAnchor = EquipAnchor(204f / 400f, 44f / 400f, 0.36f, 2f),
        glassesAnchor = EquipAnchor(203f / 400f, 127f / 400f, 0.34f, 4f),
        noseAnchor = EquipAnchor(202f / 400f, 150f / 400f, 0.14f, 3f),
        mouthAnchor = EquipAnchor(202f / 400f, 170f / 400f, 0.24f, 3f),
        clothesAnchor = EquipAnchor(204f / 400f, 198f / 400f, 0.44f, 0f),
        shoesAnchor = EquipAnchor(204f / 400f, 356f / 400f, 0.30f, 0f)
    )

    private val smilePose = PuppyPoseSpec(
        drawableRes = R.drawable.itv_puppy_mascot,
        srcW = 584f,
        srcH = 832f,
        wipeRects = listOf(
            NormalizedRect(178f / 584f, 384f / 584f, 188f / 832f, 390f / 832f)
        ),
        furSamplePoints = listOf(
            (288f / 584f) to (160f / 832f),
            (240f / 584f) to (165f / 832f),
            (336f / 584f) to (165f / 832f),
            (360f / 584f) to (215f / 832f)
        ),
        leftEyeNx = 208f / 584f,
        leftEyeNy = 220f / 832f,
        rightEyeNx = 348f / 584f,
        rightEyeNy = 284f / 832f,
        noseOffsetFactor = 0.23f,
        mouthOffsetFactor = 0.56f,
        drawNose = true,
        shadowWidthFraction = 0.72f,
        shadowOffsetYFraction = 0.02f,
        hatAnchor = EquipAnchor(280f / 584f, 133f / 832f, 85f / 200f, 1.5f),
        glassesAnchor = EquipAnchor(287f / 584f, (265f / 832f) - (82f / 200f) * 0.17f, 82f / 200f, 1.5f),
        noseAnchor = EquipAnchor(290f / 584f, (308f / 832f) - (34f / 200f) * 0.02f, 34f / 200f, 1.5f),
        mouthAnchor = EquipAnchor(290f / 584f, 340f / 832f, 60f / 200f, 1.5f),
        clothesAnchor = EquipAnchor(280f / 584f, 316f / 832f, 92f / 200f, 0f),
        shoesAnchor = EquipAnchor(282f / 584f, (730f / 832f) - (68f / 200f) * 0.10f, 68f / 200f, 16f)
    )

    private val sadPose = PuppyPoseSpec(
        drawableRes = R.drawable.itv_motion_sad,
        srcW = 400f,
        srcH = 400f,
        wipeRects = listOf(
            NormalizedRect(154f / 400f, 252f / 400f, 86f / 400f, 106f / 400f),
            NormalizedRect(138f / 400f, 252f / 400f, 104f / 400f, 126f / 400f),
            NormalizedRect(126f / 400f, 252f / 400f, 124f / 400f, 154f / 400f),
            NormalizedRect(146f / 400f, 246f / 400f, 150f / 400f, 175f / 400f)
        ),
        furSamplePoints = listOf(
            (196f / 400f) to (72f / 400f),
            (225f / 400f) to (80f / 400f),
            (215f / 400f) to (185f / 400f)
        ),
        leftEyeNx = 164f / 400f,
        leftEyeNy = 118f / 400f,
        rightEyeNx = 232f / 400f,
        rightEyeNy = 138f / 400f,
        noseOffsetFactor = 0.28f,
        mouthOffsetFactor = 0.56f,
        drawNose = true,
        shadowWidthFraction = 0.70f,
        shadowOffsetYFraction = -0.05f,
        hatAnchor = EquipAnchor(194f / 400f, 52f / 400f, 0.36f, 8f),
        glassesAnchor = EquipAnchor(198f / 400f, 128f / 400f, 0.32f, 15f),
        noseAnchor = EquipAnchor(193f / 400f, 146f / 400f, 0.13f, 14f),
        mouthAnchor = EquipAnchor(188f / 400f, 164f / 400f, 0.22f, 14f),
        clothesAnchor = EquipAnchor(196f / 400f, 212f / 400f, 0.40f, 0f),
        shoesAnchor = EquipAnchor(198f / 400f, 342f / 400f, 0.30f, 0f)
    )

    private val angryPose = PuppyPoseSpec(
        drawableRes = R.drawable.itv_motion_angry,
        srcW = 400f,
        srcH = 400f,
        wipeRects = listOf(
            NormalizedRect(134f / 400f, 252f / 400f, 112f / 400f, 183f / 400f)
        ),
        furSamplePoints = listOf(
            (196f / 400f) to (82f / 400f),
            (225f / 400f) to (90f / 400f),
            (220f / 400f) to (192f / 400f)
        ),
        leftEyeNx = 162f / 400f,
        leftEyeNy = 135f / 400f,
        rightEyeNx = 230f / 400f,
        rightEyeNy = 145f / 400f,
        noseOffsetFactor = 0.25f,
        mouthOffsetFactor = 0.55f,
        drawNose = true,
        shadowWidthFraction = 0.72f,
        shadowOffsetYFraction = 0.01f,
        hatAnchor = EquipAnchor(194f / 400f, 54f / 400f, 0.36f, 4f),
        glassesAnchor = EquipAnchor(196f / 400f, 140f / 400f, 0.32f, 8f),
        noseAnchor = EquipAnchor(194f / 400f, 156f / 400f, 0.13f, 8f),
        mouthAnchor = EquipAnchor(191f / 400f, 174f / 400f, 0.22f, 8f),
        clothesAnchor = EquipAnchor(196f / 400f, 216f / 400f, 0.42f, 0f),
        shoesAnchor = EquipAnchor(198f / 400f, 356f / 400f, 0.32f, 0f)
    )

    private val sickPose = PuppyPoseSpec(
        drawableRes = R.drawable.itv_motion_sleep,
        srcW = 400f,
        srcH = 400f,
        wipeRects = listOf(
            NormalizedRect(208f / 400f, 254f / 400f, 290f / 400f, 316f / 400f),
            NormalizedRect(272f / 400f, 314f / 400f, 314f / 400f, 338f / 400f)
        ),
        furSamplePoints = listOf(
            (256f / 400f) to (248f / 400f),
            (284f / 400f) to (268f / 400f),
            (236f / 400f) to (272f / 400f)
        ),
        leftEyeNx = 230f / 400f,
        leftEyeNy = 304f / 400f,
        rightEyeNx = 294f / 400f,
        rightEyeNy = 326f / 400f,
        noseOffsetFactor = 0.24f,
        mouthOffsetFactor = 0.46f,
        drawNose = false, // Preserves the original curled sleeping nose & tucked chin cleanly
        shadowWidthFraction = 0.88f,
        shadowOffsetYFraction = 0.0f,
        hatAnchor = EquipAnchor(282f / 400f, 214f / 400f, 0.34f, 18f),
        glassesAnchor = EquipAnchor(262f / 400f, 315f / 400f, 0.30f, 18f),
        noseAnchor = EquipAnchor(256f / 400f, 333f / 400f, 0.12f, 18f),
        mouthAnchor = EquipAnchor(250f / 400f, 344f / 400f, 0.20f, 18f),
        clothesAnchor = EquipAnchor(142f / 400f, 242f / 400f, 0.36f, -8f),
        shoesAnchor = EquipAnchor(206f / 400f, 358f / 400f, 0.24f, 0f)
    )

    fun specFor(mood: PuppyMood): PuppyPoseSpec = when (mood) {
        PuppyMood.BRIGHT -> brightPose
        PuppyMood.SMILE -> smilePose
        PuppyMood.SAD -> sadPose
        PuppyMood.ANGRY -> angryPose
        PuppyMood.SICK -> sickPose
    }
}

private data class BlankPoseAsset(
    val mood: PuppyMood,
    val bitmap: ImageBitmap,
    val furColor: Color
)

/**
 * Per-mood thread-safe cache that decodes each pose drawable ONCE off-thread, samples the exact
 * fur/skin tone from the character's face, and cleanly erases only the original facial-feature
 * regions inside [PuppyPoseSpec.wipeRects] without touching outer outlines.
 */
private object PuppyPoseBitmapCache {
    private val cache = ConcurrentHashMap<PuppyMood, BlankPoseAsset>()

    fun getCached(mood: PuppyMood): BlankPoseAsset? = cache[mood]

    fun getOrCreate(context: Context, mood: PuppyMood): BlankPoseAsset? {
        cache[mood]?.let { return it }
        return synchronized(this) {
            cache[mood]?.let { return it }
            buildBlankPose(context, mood, PuppyPoseCatalog.specFor(mood))?.also { cache[mood] = it }
        }
    }

    private fun buildBlankPose(context: Context, mood: PuppyMood, spec: PuppyPoseSpec): BlankPoseAsset? = runCatching {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val raw = BitmapFactory.decodeResource(context.resources, spec.drawableRes, opts) ?: return null
        val targetW = 420.coerceAtMost(raw.width)
        val targetH = ((raw.height.toFloat() / raw.width.toFloat()) * targetW)
            .roundToInt()
            .coerceAtLeast(1)
        val scaled = if ((raw.width != targetW) || (raw.height != targetH)) {
            raw.scale(targetW, targetH, filter = true).also {
                if (it !== raw) raw.recycle()
            }
        } else {
            raw
        }

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        scaled.recycle()

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0L
        for ((nx, ny) in spec.furSamplePoints) {
            val px = (nx * w).roundToInt().coerceIn(0, w - 1)
            val py = (ny * h).roundToInt().coerceIn(0, h - 1)
            val c = pixels[py * w + px]
            val a = (c ushr 24) and 0xFF
            if (a < 230) continue
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            if (r >= 230 && g >= 230 && b >= 225) {
                rSum += r
                gSum += g
                bSum += b
                count++
            }
        }
        val furR = if (count > 0) (rSum / count).toInt().coerceIn(240, 255) else 255
        val furG = if (count > 0) (gSum / count).toInt().coerceIn(240, 255) else 255
        val furB = if (count > 0) (bSum / count).toInt().coerceIn(235, 255) else 255
        val furArgb = (0xFF shl 24) or (furR shl 16) or (furG shl 8) or furB

        for (rect in spec.wipeRects) {
            val xStart = (w * rect.left).roundToInt().coerceIn(0, w - 1)
            val xEnd = (w * rect.right).roundToInt().coerceIn(0, w - 1)
            val yStart = (h * rect.top).roundToInt().coerceIn(0, h - 1)
            val yEnd = (h * rect.bottom).roundToInt().coerceIn(0, h - 1)
            for (py in yStart..yEnd) {
                val row = py * w
                for (px in xStart..xEnd) {
                    val c = pixels[row + px]
                    if (((c ushr 24) and 0xFF) > 200) {
                        pixels[row + px] = furArgb
                    }
                }
            }
        }

        val outBmp = createBitmap(w, h)
        outBmp.setPixels(pixels, 0, w, 0, 0, w, h)
        BlankPoseAsset(
            mood = mood,
            bitmap = outBmp.asImageBitmap(),
            furColor = Color(furArgb)
        )
    }.getOrNull()
}

private data class MotionFrame(
    val txFraction: Float = 0f,
    val tyFraction: Float = 0f, // negative = upward jump
    val rotDeg: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val shadowScale: Float = 1f,
    val barkPulse: Float = 0f,
    val isPlaying: Boolean = false
)

/**
 * Computes the 3.8~4.2s one-shot expressive animation frame (`progress` in 0f..1f) for each mood.
 * When `progress >= 0.999f`, returns a clean zero-motion resting pose so no battery/CPU is spent idling.
 */
private fun evaluateMoodMotion(mood: PuppyMood, progress: Float): MotionFrame {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 0.999f) {
        return MotionFrame(isPlaying = false)
    }
    // Smooth fade-out envelope during the last 12% of the 4s clip so landing into rest is seamless
    val tailFade = if (p > 0.88f) ((1f - p) / 0.12f).coerceIn(0f, 1f) else 1f

    return when (mood) {
        PuppyMood.BRIGHT -> {
            // 4 energetic "팔짝팔짝" vertical jumps with squash-and-stretch & playful side tilt!
            val cycle = (p * 4f) % 1f
            val hop = sin(cycle * PI).toFloat().coerceIn(0f, 1f)
            val damp = (1f - 0.12f * p) * tailFade
            val jumpLift = -0.155f * hop * damp // stays strictly inside the reserved top headroom!
            val squash = if (cycle < 0.14f || cycle > 0.86f) 0.055f * damp else 0f
            val stretch = 0.055f * hop * damp
            val tiltSign = if (((p * 4f).toInt() % 2) == 0) -1f else 1f
            MotionFrame(
                tyFraction = jumpLift,
                rotDeg = tiltSign * 5.5f * hop * damp,
                scaleX = 1f + squash - stretch * 0.6f,
                scaleY = 1f - squash + stretch,
                shadowScale = (1f - 0.30f * hop * damp).coerceIn(0.62f, 1f),
                isPlaying = true
            )
        }

        PuppyMood.SMILE -> {
            // Gentle happy bounce & friendly head-nod sway
            val wave = sin(p * PI * 4.0).toFloat() * tailFade
            val bounce = abs(sin(p * PI * 3.0)).toFloat() * tailFade
            MotionFrame(
                tyFraction = -0.025f * bounce,
                rotDeg = 3.8f * wave,
                scaleX = 1f + 0.015f * (1f - bounce) * tailFade,
                scaleY = 1f + 0.020f * bounce,
                shadowScale = 1f - 0.06f * bounce,
                isPlaying = true
            )
        }

        PuppyMood.SAD -> {
            // Heavy sighing slump down & slow drooping sway
            val sigh = (0.5f - 0.5f * sin(p * PI * 3.0 + PI * 0.5)).toFloat() * tailFade
            MotionFrame(
                tyFraction = 0.028f * sigh,
                rotDeg = -3.2f * sigh,
                scaleX = 1f + 0.032f * sigh,
                scaleY = 1f - 0.045f * sigh,
                shadowScale = 1f + 0.08f * sigh,
                isPlaying = true
            )
        }

        PuppyMood.ANGRY -> {
            // 4 rhythmic forward-leaning barking lunges toward the screen!
            val bark = computeBarkPulse(p) * tailFade
            val microShake = sin(p * PI * 38.0).toFloat() * 0.012f * bark
            MotionFrame(
                txFraction = microShake,
                tyFraction = -0.045f * bark,
                rotDeg = -4.0f * bark,
                scaleX = 1f + 0.065f * bark,
                scaleY = 1f + 0.055f * bark,
                shadowScale = 1f + 0.06f * bark,
                barkPulse = bark,
                isPlaying = true
            )
        }

        PuppyMood.SICK -> {
            // 3 feverish "벌벌 떨며 웅크린" shivering bursts in sleeping posture!
            val burstEnv = abs(sin(p * PI * 3.0)).toFloat().coerceIn(0f, 1f) * tailFade
            val shiver = sin(p * PI * 54.0).toFloat() * burstEnv
            MotionFrame(
                txFraction = 0.022f * shiver,
                tyFraction = 0.010f * burstEnv,
                rotDeg = 1.6f * shiver,
                scaleX = 1f - 0.025f * burstEnv,
                scaleY = 1f - 0.030f * burstEnv,
                shadowScale = 1f - 0.02f * burstEnv,
                isPlaying = true
            )
        }
    }
}

private fun computeBarkPulse(p: Float): Float {
    val local = (p * 4f) % 1f
    return when {
        local < 0.24f -> sin((local / 0.24f) * PI).toFloat().coerceIn(0f, 1f)
        local < 0.46f -> sin(((local - 0.24f) / 0.22f) * PI).toFloat().coerceIn(0f, 1f) * 0.72f
        else -> 0f
    }
}

@Composable
fun PuppyMascot(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.itv_puppy_mascot),
        contentDescription = "하얀색 강아지 캐릭터",
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}

@Composable
fun EquippedPuppyMascot(
    equippedItems: Map<EquipSlot, EquipItem?>,
    modifier: Modifier = Modifier,
    mood: PuppyMood = PuppyMood.SMILE,
    replayKey: Int = 0,
    showGroundShadow: Boolean = false,
    animateMood: Boolean = true
) {
    val context = LocalContext.current
    val spec = remember(mood) { PuppyPoseCatalog.specFor(mood) }
    val cachedAsset = PuppyPoseBitmapCache.getCached(mood)
    val loadedAsset by produceState<BlankPoseAsset?>(
        initialValue = cachedAsset,
        key1 = context,
        key2 = mood
    ) {
        val existing = PuppyPoseBitmapCache.getCached(mood)
        if (existing != null) {
            value = existing
        } else {
            value = null
            value = withContext(Dispatchers.Default) {
                PuppyPoseBitmapCache.getOrCreate(context, mood)
            }
        }
    }
    // Never reuse a stale BlankPoseAsset from a previous mood when switching poses!
    val blankAsset = cachedAsset ?: loadedAsset?.takeIf { it.mood == mood }

    // Pre-warm all 5 mood pose bitmaps off-thread so subsequent mood switches are instantaneous
    LaunchedEffect(context) {
        withContext(Dispatchers.Default) {
            PuppyMood.entries.forEach { targetMood ->
                PuppyPoseBitmapCache.getOrCreate(context, targetMood)
            }
        }
    }

    // One-shot ~4.0s expressive animation triggered on initial entry, mood change, or explicit replay
    val animProgress = remember { Animatable(if (animateMood) 0f else 0.35f) }
    LaunchedEffect(mood, replayKey, animateMood) {
        if (!animateMood) {
            animProgress.snapTo(0.35f)
            return@LaunchedEffect
        }
        val durationMs = when (mood) {
            PuppyMood.BRIGHT -> 4000
            PuppyMood.SMILE -> 3600
            PuppyMood.SAD -> 4000
            PuppyMood.ANGRY -> 4000
            PuppyMood.SICK -> 4200
        }
        animProgress.snapTo(0f)
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
        )
    }

    val progress = animProgress.value
    val motion = evaluateMoodMotion(mood, if (animateMood) progress else 0.35f)

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter
    ) {
        val density = LocalDensity.current
        // Reserve top headroom (~16% of stage height) so even peak BRIGHT jumps never leave this box
        // or collide with the HAT equip slot card above!
        val jumpHeadroom = maxHeight * 0.16f
        val availH = (maxHeight - jumpHeadroom).coerceAtLeast(80.dp)
        val availW = maxWidth
        val aspect = spec.srcW / spec.srcH

        val spriteW: androidx.compose.ui.unit.Dp
        val spriteH: androidx.compose.ui.unit.Dp
        if (availW / availH > aspect) {
            spriteH = availH
            spriteW = availH * aspect
        } else {
            spriteW = availW
            spriteH = availW / aspect
        }

        val spriteLeft = (maxWidth - spriteW) / 2f
        val spriteTop = maxHeight - spriteH

        // Ground shadow anchored underneath the puppy's paws/curled body
        if (showGroundShadow) {
            val shadowBaseW = (spriteW * spec.shadowWidthFraction).coerceAtMost(maxWidth * 0.88f)
            val shadowBaseH = (spriteH * 0.11f).coerceIn(14.dp, 22.dp)
            Canvas(
                modifier = Modifier
                    .size(width = shadowBaseW, height = shadowBaseH)
                    .align(Alignment.BottomCenter)
                    .offset(y = spriteH * spec.shadowOffsetYFraction)
            ) {
                val w = size.width * motion.shadowScale
                val h = size.height * motion.shadowScale
                drawOval(
                    color = Color.Black.copy(alpha = 0.085f * motion.shadowScale),
                    topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                    size = Size(w, h)
                )
            }
        }

        val txPx = with(density) { (spriteW * motion.txFraction).toPx() }
        val tyPx = with(density) { (maxHeight * motion.tyFraction).toPx() }

        // Soft ambient mood aura centered behind the character's active silhouette
        Canvas(modifier = Modifier.size(maxWidth, maxHeight)) {
            val auraCy = if (mood == PuppyMood.SICK) size.height * 0.72f else size.height * 0.56f
            drawMoodBackAura(
                mood = mood,
                cx = size.width * 0.5f + txPx * 0.4f,
                cy = auraCy + tyPx * 0.4f,
                radius = minOf(size.width, size.height) * 0.44f,
                progress = progress,
                isPlaying = motion.isPlaying
            )
        }

        // Character pose + expression overlay + all 5 equipped cosmetic slots transformed together
        Box(
            modifier = Modifier
                .size(maxWidth, maxHeight)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0.92f)
                    translationX = txPx
                    translationY = tyPx
                    rotationZ = motion.rotDeg
                    scaleX = motion.scaleX
                    scaleY = motion.scaleY
                }
        ) {
            // 1. 표정별 전용 자세 캐릭터 (원본 눈·코·입만 피부톤으로 깨끗하게 제거된 블랭크 페이스 버전)
            Box(
                modifier = Modifier
                    .size(spriteW, spriteH)
                    .offset(x = spriteLeft, y = spriteTop)
            ) {
                val asset = blankAsset
                if (asset != null) {
                    Image(
                        bitmap = asset.bitmap,
                        contentDescription = "하얀색 강아지 캐릭터 (${mood.label})",
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Image(
                        painter = painterResource(spec.drawableRes),
                        contentDescription = "하얀색 강아지 캐릭터 (${mood.label})",
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                // 1.5 표정 오버레이 + 감정 이펙트 (각 자세의 실제 눈/코/입 좌표와 고개 각도에 정밀 정렬)
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawPoseMoodExpressionAndEffects(
                        mood = mood,
                        spec = spec,
                        furColor = asset?.furColor ?: PuppyPoseCatalog.DEFAULT_FUR_COLOR,
                        needsFallbackMask = asset == null,
                        progress = if (animateMood) progress else 0.35f,
                        barkPulse = motion.barkPulse,
                        isPlaying = motion.isPlaying
                    )
                }
            }

            // 2. 옷 (각 자세의 몸통 위치에 맞춤 피팅)
            equippedItems[EquipSlot.CLOTHES]?.let { item ->
                val a = spec.clothesAnchor
                val itemSize = spriteH * a.sizeFraction
                val cx = spriteLeft + spriteW * a.centerX
                val topY = spriteTop + spriteH * a.centerY

                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(itemSize)
                        .offset(x = cx - itemSize / 2f, y = topY)
                        .graphicsLayer { rotationZ = a.rotationDeg },
                    contentScale = ContentScale.Fit
                )
            }

            // 3. 신발 (각 자세의 발 위치에 맞춤 피팅)
            equippedItems[EquipSlot.SHOES]?.let { item ->
                val a = spec.shoesAnchor
                val itemSize = spriteH * a.sizeFraction
                val cx = spriteLeft + spriteW * a.centerX
                val cy = spriteTop + spriteH * a.centerY

                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(itemSize)
                        .offset(x = cx - itemSize / 2f, y = cy - itemSize / 2f)
                        .graphicsLayer { rotationZ = a.rotationDeg },
                    contentScale = ContentScale.Fit
                )
            }

            // 4. 코와 입 (각 자세의 코·입 위치 및 고개 각도에 맞춤 피팅)
            equippedItems[EquipSlot.NOSE_MOUTH]?.let { item ->
                val isRedNose = item.id == "face_red_nose"
                val a = if (isRedNose) spec.noseAnchor else spec.mouthAnchor
                val itemSize = spriteH * a.sizeFraction
                val cx = spriteLeft + spriteW * a.centerX
                val cy = spriteTop + spriteH * a.centerY

                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(itemSize)
                        .offset(x = cx - itemSize / 2f, y = cy - itemSize / 2f)
                        .graphicsLayer { rotationZ = a.rotationDeg },
                    contentScale = ContentScale.Fit
                )
            }

            // 5. 안경 (각 자세의 눈 위치 및 고개 각도에 맞춤 피팅)
            equippedItems[EquipSlot.GLASSES]?.let { item ->
                val a = spec.glassesAnchor
                val itemSize = spriteH * a.sizeFraction
                val cx = spriteLeft + spriteW * a.centerX
                val cy = spriteTop + spriteH * a.centerY

                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(itemSize)
                        .offset(x = cx - itemSize / 2f, y = cy - itemSize / 2f)
                        .graphicsLayer { rotationZ = a.rotationDeg },
                    contentScale = ContentScale.Fit
                )
            }

            // 6. 모자 (각 자세의 정수리 위치 및 고개 각도에 맞춤 피팅)
            equippedItems[EquipSlot.HAT]?.let { item ->
                val a = spec.hatAnchor
                val itemSize = spriteH * a.sizeFraction
                val cx = spriteLeft + spriteW * a.centerX
                val cy = spriteTop + spriteH * a.centerY

                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(itemSize)
                        .offset(x = cx - itemSize / 2f, y = cy - itemSize / 2f)
                        .graphicsLayer { rotationZ = a.rotationDeg },
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

private fun DrawScope.drawMoodBackAura(
    mood: PuppyMood,
    cx: Float,
    cy: Float,
    radius: Float,
    progress: Float,
    isPlaying: Boolean
) {
    val pulse = if (isPlaying) 0.88f + 0.12f * sin(progress * PI * 6.0).toFloat() else 0.92f
    val tint = when (mood) {
        PuppyMood.BRIGHT -> Color(0xFF4ADE80).copy(alpha = 0.18f * pulse)
        PuppyMood.SMILE -> Color(0xFF38BDF8).copy(alpha = 0.12f * pulse)
        PuppyMood.SAD -> Color(0xFF6366F1).copy(alpha = 0.16f * pulse)
        PuppyMood.ANGRY -> Color(0xFFFB923C).copy(alpha = 0.19f * pulse)
        PuppyMood.SICK -> Color(0xFFEF4444).copy(alpha = 0.17f * pulse)
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(tint, Color.Transparent),
            center = Offset(cx, cy),
            radius = radius
        ),
        radius = radius,
        center = Offset(cx, cy)
    )
}

/**
 * Draws the 5-tier facial expression aligned precisely onto [spec]'s real eye/nose/mouth
 * coordinate frame, plus expressive mood accents around the head.
 */
private fun DrawScope.drawPoseMoodExpressionAndEffects(
    mood: PuppyMood,
    spec: PuppyPoseSpec,
    furColor: Color,
    needsFallbackMask: Boolean,
    progress: Float,
    barkPulse: Float,
    isPlaying: Boolean
) {
    val w = size.width
    val h = size.height
    fun pt(nx: Float, ny: Float): Offset = Offset(w * nx, h * ny)

    // Brief fallback skin-tone mask while the blank-face bitmap decodes (< 10ms)
    if (needsFallbackMask) {
        for (rect in spec.wipeRects) {
            val tl = pt(rect.left, rect.top)
            val br = pt(rect.right, rect.bottom)
            drawRoundRect(
                color = furColor,
                topLeft = tl,
                size = Size(br.x - tl.x, br.y - tl.y),
                cornerRadius = CornerRadius(w * 0.04f, w * 0.04f)
            )
        }
    }

    val realLeftEye = pt(spec.leftEyeNx, spec.leftEyeNy)
    val realRightEye = pt(spec.rightEyeNx, spec.rightEyeNy)
    val eyeMid = Offset(
        x = (realLeftEye.x + realRightEye.x) * 0.5f,
        y = (realLeftEye.y + realRightEye.y) * 0.5f
    )
    val eyeSpan = hypot(
        (realRightEye.x - realLeftEye.x).toDouble(),
        (realRightEye.y - realLeftEye.y).toDouble()
    ).toFloat().coerceAtLeast(1f)
    val headTiltDeg = Math.toDegrees(
        atan2((realRightEye.y - realLeftEye.y).toDouble(), (realRightEye.x - realLeftEye.x).toDouble())
    ).toFloat()

    val ink = PuppyPoseCatalog.OUTLINE_INK
    val lineW = (eyeSpan * 0.088f).coerceAtLeast(1.7f)
    val thinW = (eyeSpan * 0.068f).coerceAtLeast(1.35f)
    val stroke = Stroke(width = lineW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thinStroke = Stroke(width = thinW, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // 1. On-face expression features drawn inside the pose's exact tilted face frame
    rotate(degrees = headTiltDeg, pivot = eyeMid) {
        val leftEye = Offset(eyeMid.x - eyeSpan * 0.50f, eyeMid.y)
        val rightEye = Offset(eyeMid.x + eyeSpan * 0.50f, eyeMid.y)
        val noseCenter = Offset(eyeMid.x, eyeMid.y + eyeSpan * spec.noseOffsetFactor)
        val mouthCenter = Offset(eyeMid.x, eyeMid.y + eyeSpan * spec.mouthOffsetFactor)

        if (spec.drawNose) {
            val noseW = eyeSpan * 0.31f
            val noseH = eyeSpan * 0.22f
            drawOval(
                color = ink,
                topLeft = Offset(noseCenter.x - noseW / 2f, noseCenter.y - noseH / 2f),
                size = Size(noseW, noseH)
            )
            drawOval(
                color = Color.White.copy(alpha = 0.52f),
                topLeft = Offset(noseCenter.x - noseW * 0.26f, noseCenter.y - noseH * 0.28f),
                size = Size(noseW * 0.28f, noseH * 0.24f)
            )
        }

        when (mood) {
            PuppyMood.BRIGHT -> {
                val blush = Color(0xFFFF8FAB).copy(alpha = 0.44f)
                val bW = eyeSpan * 0.34f
                val bH = eyeSpan * 0.18f
                drawOval(blush, Offset(leftEye.x - eyeSpan * 0.06f - bW / 2f, leftEye.y + eyeSpan * 0.28f - bH / 2f), Size(bW, bH))
                drawOval(blush, Offset(rightEye.x + eyeSpan * 0.06f - bW / 2f, rightEye.y + eyeSpan * 0.28f - bH / 2f), Size(bW, bH))

                // Happy arched ^ ^ eyes
                val archW = eyeSpan * 0.185f
                val archH = eyeSpan * 0.21f
                val lEyePath = Path().apply {
                    moveTo(leftEye.x - archW, leftEye.y + archH * 0.35f)
                    quadraticTo(leftEye.x, leftEye.y - archH, leftEye.x + archW, leftEye.y + archH * 0.35f)
                }
                val rEyePath = Path().apply {
                    moveTo(rightEye.x - archW, rightEye.y + archH * 0.35f)
                    quadraticTo(rightEye.x, rightEye.y - archH, rightEye.x + archW, rightEye.y + archH * 0.35f)
                }
                drawPath(lEyePath, color = ink, style = stroke)
                drawPath(rEyePath, color = ink, style = stroke)

                // Playful pink tongue & big happy smile
                val wiggle = if (isPlaying) sin(progress * PI * 12.0).toFloat() * eyeSpan * 0.025f else 0f
                val tHalfW = eyeSpan * 0.165f
                val tTopY = mouthCenter.y - eyeSpan * 0.04f
                val tMidY = mouthCenter.y + eyeSpan * 0.14f
                val tBotY = mouthCenter.y + eyeSpan * 0.32f
                val tongue = Path().apply {
                    moveTo(mouthCenter.x - tHalfW + wiggle, tTopY)
                    lineTo(mouthCenter.x - tHalfW + wiggle, tMidY)
                    cubicTo(
                        mouthCenter.x - tHalfW + wiggle, tBotY,
                        mouthCenter.x + tHalfW + wiggle, tBotY,
                        mouthCenter.x + tHalfW + wiggle, tMidY
                    )
                    lineTo(mouthCenter.x + tHalfW + wiggle, tTopY)
                    close()
                }
                drawPath(tongue, color = Color(0xFFFF7FA3))
                drawPath(tongue, color = ink, style = thinStroke)
                drawLine(
                    color = ink,
                    start = Offset(mouthCenter.x + wiggle, tTopY + eyeSpan * 0.03f),
                    end = Offset(mouthCenter.x + wiggle, tMidY + eyeSpan * 0.04f),
                    strokeWidth = thinW * 0.72f,
                    cap = StrokeCap.Round
                )

                val mouthTop = Path().apply {
                    moveTo(mouthCenter.x - eyeSpan * 0.32f, mouthCenter.y - eyeSpan * 0.08f)
                    quadraticTo(mouthCenter.x, mouthCenter.y + eyeSpan * 0.10f, mouthCenter.x + eyeSpan * 0.32f, mouthCenter.y - eyeSpan * 0.08f)
                }
                drawPath(mouthTop, color = ink, style = thinStroke)
            }

            PuppyMood.SMILE -> {
                val blush = Color(0xFFFFA8BC).copy(alpha = 0.34f)
                val bW = eyeSpan * 0.28f
                val bH = eyeSpan * 0.15f
                drawOval(blush, Offset(leftEye.x - eyeSpan * 0.08f - bW / 2f, leftEye.y + eyeSpan * 0.28f - bH / 2f), Size(bW, bH))
                drawOval(blush, Offset(rightEye.x + eyeSpan * 0.08f - bW / 2f, rightEye.y + eyeSpan * 0.28f - bH / 2f), Size(bW, bH))

                val eyeR = eyeSpan * 0.145f
                drawCircle(color = ink, radius = eyeR, center = leftEye)
                drawCircle(color = ink, radius = eyeR, center = rightEye)
                drawCircle(color = Color.White, radius = eyeR * 0.34f, center = Offset(leftEye.x - eyeR * 0.28f, leftEye.y - eyeR * 0.28f))
                drawCircle(color = Color.White, radius = eyeR * 0.34f, center = Offset(rightEye.x - eyeR * 0.28f, rightEye.y - eyeR * 0.28f))

                val smile = Path().apply {
                    moveTo(mouthCenter.x - eyeSpan * 0.29f, mouthCenter.y - eyeSpan * 0.02f)
                    quadraticTo(mouthCenter.x - eyeSpan * 0.14f, mouthCenter.y + eyeSpan * 0.16f, mouthCenter.x, mouthCenter.y - eyeSpan * 0.06f)
                    quadraticTo(mouthCenter.x + eyeSpan * 0.14f, mouthCenter.y + eyeSpan * 0.16f, mouthCenter.x + eyeSpan * 0.29f, mouthCenter.y - eyeSpan * 0.02f)
                }
                drawPath(smile, color = ink, style = thinStroke)
            }

            PuppyMood.SAD -> {
                // Drooping worried eyebrows / \
                drawLine(
                    ink,
                    Offset(leftEye.x - eyeSpan * 0.20f, leftEye.y - eyeSpan * 0.18f),
                    Offset(leftEye.x + eyeSpan * 0.14f, leftEye.y - eyeSpan * 0.30f),
                    thinW,
                    StrokeCap.Round
                )
                drawLine(
                    ink,
                    Offset(rightEye.x + eyeSpan * 0.20f, rightEye.y - eyeSpan * 0.18f),
                    Offset(rightEye.x - eyeSpan * 0.14f, rightEye.y - eyeSpan * 0.30f),
                    thinW,
                    StrokeCap.Round
                )

                val eyeR = eyeSpan * 0.145f
                val lC = Offset(leftEye.x, leftEye.y + eyeSpan * 0.03f)
                val rC = Offset(rightEye.x, rightEye.y + eyeSpan * 0.03f)
                drawCircle(color = ink, radius = eyeR, center = lC)
                drawCircle(color = ink, radius = eyeR, center = rC)
                drawCircle(color = Color.White, radius = eyeR * 0.38f, center = Offset(lC.x - eyeR * 0.25f, lC.y - eyeR * 0.25f))
                drawCircle(color = Color.White, radius = eyeR * 0.38f, center = Offset(rC.x - eyeR * 0.25f, rC.y - eyeR * 0.25f))

                drawArc(
                    color = Color(0xFF7DD3FC),
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(lC.x - eyeR * 1.1f, lC.y - eyeR * 0.45f),
                    size = Size(eyeR * 2.2f, eyeR * 1.6f),
                    style = Stroke(width = thinW * 0.85f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color(0xFF7DD3FC),
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(rC.x - eyeR * 1.1f, rC.y - eyeR * 0.45f),
                    size = Size(eyeR * 2.2f, eyeR * 1.6f),
                    style = Stroke(width = thinW * 0.85f, cap = StrokeCap.Round)
                )

                val frown = Path().apply {
                    moveTo(mouthCenter.x - eyeSpan * 0.24f, mouthCenter.y + eyeSpan * 0.10f)
                    quadraticTo(mouthCenter.x, mouthCenter.y - eyeSpan * 0.10f, mouthCenter.x + eyeSpan * 0.24f, mouthCenter.y + eyeSpan * 0.10f)
                }
                drawPath(frown, color = ink, style = thinStroke)

                // Animated flowing tears during the 4s clip; clean resting teardrop once settled
                if (isPlaying) {
                    val dropStarts = listOf(
                        Offset(lC.x - eyeSpan * 0.08f, lC.y + eyeSpan * 0.20f) to 0.0f,
                        Offset(rC.x + eyeSpan * 0.08f, rC.y + eyeSpan * 0.20f) to 0.45f
                    )
                    dropStarts.forEach { (startPos, offsetPhase) ->
                        val t = (progress * 3.2f + offsetPhase) % 1f
                        val alpha = when {
                            t < 0.15f -> t / 0.15f
                            t > 0.78f -> (1f - t) / 0.22f
                            else -> 1f
                        }.coerceIn(0f, 1f)
                        drawBlueTeardrop(
                            center = Offset(startPos.x, startPos.y + t * eyeSpan * 0.48f),
                            dropW = eyeSpan * 0.17f,
                            strokeW = thinW * 0.65f,
                            alpha = alpha
                        )
                    }
                } else {
                    drawBlueTeardrop(
                        center = Offset(rC.x + eyeSpan * 0.09f, rC.y + eyeSpan * 0.30f),
                        dropW = eyeSpan * 0.17f,
                        strokeW = thinW * 0.65f,
                        alpha = 0.95f
                    )
                }
            }

            PuppyMood.ANGRY -> {
                val heat = Color(0xFFFB923C).copy(alpha = 0.32f + 0.18f * barkPulse)
                val bW = eyeSpan * 0.30f
                val bH = eyeSpan * 0.16f
                drawOval(heat, Offset(leftEye.x - eyeSpan * 0.08f - bW / 2f, leftEye.y + eyeSpan * 0.26f - bH / 2f), Size(bW, bH))
                drawOval(heat, Offset(rightEye.x + eyeSpan * 0.08f - bW / 2f, rightEye.y + eyeSpan * 0.26f - bH / 2f), Size(bW, bH))

                // Sharp furrowed V-shaped angry brows \ /
                drawLine(
                    ink,
                    Offset(leftEye.x - eyeSpan * 0.24f, leftEye.y - eyeSpan * 0.24f),
                    Offset(leftEye.x + eyeSpan * 0.20f, leftEye.y - eyeSpan * 0.06f),
                    lineW * 1.08f,
                    StrokeCap.Round
                )
                drawLine(
                    ink,
                    Offset(rightEye.x + eyeSpan * 0.24f, rightEye.y - eyeSpan * 0.24f),
                    Offset(rightEye.x - eyeSpan * 0.20f, rightEye.y - eyeSpan * 0.06f),
                    lineW * 1.08f,
                    StrokeCap.Round
                )

                val eyeR = eyeSpan * 0.14f
                drawCircle(color = ink, radius = eyeR, center = Offset(leftEye.x + eyeSpan * 0.01f, leftEye.y + eyeSpan * 0.06f))
                drawCircle(color = ink, radius = eyeR, center = Offset(rightEye.x - eyeSpan * 0.01f, rightEye.y + eyeSpan * 0.06f))

                if (barkPulse > 0.15f) {
                    // Wide barking open mouth with white fangs during each bark lunge!
                    val openW = eyeSpan * (0.42f + 0.12f * barkPulse)
                    val openH = eyeSpan * (0.24f + 0.15f * barkPulse)
                    val mc = Offset(mouthCenter.x, mouthCenter.y + eyeSpan * 0.04f)
                    drawOval(
                        color = Color(0xFFE11D48).copy(alpha = 0.88f),
                        topLeft = Offset(mc.x - openW / 2f, mc.y - openH / 2f),
                        size = Size(openW, openH)
                    )
                    drawOval(
                        color = ink,
                        topLeft = Offset(mc.x - openW / 2f, mc.y - openH / 2f),
                        size = Size(openW, openH),
                        style = thinStroke
                    )
                    val fangTop = mc.y - openH * 0.42f
                    val fangBot = mc.y - openH * 0.04f
                    drawLine(Color.White, Offset(mc.x - openW * 0.25f, fangTop), Offset(mc.x - openW * 0.20f, fangBot), thinW * 0.9f, StrokeCap.Round)
                    drawLine(Color.White, Offset(mc.x + openW * 0.25f, fangTop), Offset(mc.x + openW * 0.20f, fangBot), thinW * 0.9f, StrokeCap.Round)
                } else {
                    // Grumpy growling inverted-V mouth with tiny fang peeking out at rest
                    val grumpy = Path().apply {
                        moveTo(mouthCenter.x - eyeSpan * 0.26f, mouthCenter.y + eyeSpan * 0.10f)
                        lineTo(mouthCenter.x, mouthCenter.y - eyeSpan * 0.06f)
                        lineTo(mouthCenter.x + eyeSpan * 0.26f, mouthCenter.y + eyeSpan * 0.10f)
                    }
                    drawPath(grumpy, color = ink, style = thinStroke)
                }
            }

            PuppyMood.SICK -> {
                // Deep feverish red flush on both cheeks
                val feverBlush = Color(0xFFEF4444).copy(alpha = 0.36f)
                val bW = eyeSpan * 0.38f
                val bH = eyeSpan * 0.20f
                drawOval(feverBlush, Offset(leftEye.x - eyeSpan * 0.06f - bW / 2f, leftEye.y + eyeSpan * 0.24f - bH / 2f), Size(bW, bH))
                drawOval(feverBlush, Offset(rightEye.x + eyeSpan * 0.06f - bW / 2f, rightEye.y + eyeSpan * 0.24f - bH / 2f), Size(bW, bH))

                // Cool-blue fever gel patch on the sleeping forehead
                val patchCenter = Offset(eyeMid.x, eyeMid.y - eyeSpan * 0.40f)
                val patchW = eyeSpan * 0.62f
                val patchH = eyeSpan * 0.24f
                drawRoundRect(
                    color = Color(0xFFDBEAFE),
                    topLeft = Offset(patchCenter.x - patchW / 2f, patchCenter.y - patchH / 2f),
                    size = Size(patchW, patchH),
                    cornerRadius = CornerRadius(patchH * 0.28f, patchH * 0.28f)
                )
                drawRoundRect(
                    color = Color(0xFF60A5FA),
                    topLeft = Offset(patchCenter.x - patchW / 2f, patchCenter.y - patchH / 2f),
                    size = Size(patchW, patchH),
                    cornerRadius = CornerRadius(patchH * 0.28f, patchH * 0.28f),
                    style = Stroke(width = thinW * 0.7f)
                )

                // Weak squeezed > < eyes in the sleeping curled face
                val sqW = eyeSpan * 0.16f
                val sqH = eyeSpan * 0.14f
                val lEyePath = Path().apply {
                    moveTo(leftEye.x - sqW, leftEye.y - sqH)
                    lineTo(leftEye.x + sqW, leftEye.y)
                    lineTo(leftEye.x - sqW, leftEye.y + sqH)
                }
                val rEyePath = Path().apply {
                    moveTo(rightEye.x + sqW, rightEye.y - sqH)
                    lineTo(rightEye.x - sqW, rightEye.y)
                    lineTo(rightEye.x + sqW, rightEye.y + sqH)
                }
                drawPath(lEyePath, color = ink, style = thinStroke)
                drawPath(rEyePath, color = ink, style = thinStroke)

                // Cold sweat drop on temple
                drawBlueTeardrop(
                    center = Offset(rightEye.x + eyeSpan * 0.20f, rightEye.y - eyeSpan * 0.28f),
                    dropW = eyeSpan * 0.15f,
                    strokeW = thinW * 0.6f,
                    alpha = 0.92f
                )

                // Clinical thermometer sticking diagonally out from the curled muzzle
                val thermoStart = Offset(mouthCenter.x + eyeSpan * 0.04f, mouthCenter.y - eyeSpan * 0.02f)
                val thermoEnd = Offset(mouthCenter.x + eyeSpan * 0.92f, mouthCenter.y + eyeSpan * 0.12f)
                val dx = thermoEnd.x - thermoStart.x
                val dy = thermoEnd.y - thermoStart.y
                val len = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                val halfThick = eyeSpan * 0.082f
                val nx = -dy / len * halfThick
                val ny = dx / len * halfThick

                val thermoBody = Path().apply {
                    moveTo(thermoStart.x + nx, thermoStart.y + ny)
                    lineTo(thermoEnd.x + nx, thermoEnd.y + ny)
                    lineTo(thermoEnd.x - nx, thermoEnd.y - ny)
                    lineTo(thermoStart.x - nx, thermoStart.y - ny)
                    close()
                }
                drawPath(thermoBody, color = Color.White)
                drawPath(thermoBody, color = ink, style = Stroke(width = thinW * 0.78f, join = StrokeJoin.Round))
                drawLine(
                    color = Color(0xFFEF4444),
                    start = Offset(thermoStart.x + dx * 0.25f, thermoStart.y + dy * 0.25f),
                    end = thermoEnd,
                    strokeWidth = thinW * 0.75f,
                    cap = StrokeCap.Round
                )
                val bulbPulse = if (isPlaying) 0.5f + 0.5f * sin(progress * PI * 14.0).toFloat() else 0.5f
                val bulbR = eyeSpan * (0.115f + 0.02f * bulbPulse)
                drawCircle(color = Color(0xFFEF4444), radius = bulbR, center = thermoEnd)
                drawCircle(color = ink, radius = bulbR, center = thermoEnd, style = Stroke(width = thinW * 0.65f))
            }
        }
    }

    // 2. Outer mood accents (kept comfortably inside the sprite bounds so they never clip into equip cards)
    when (mood) {
        PuppyMood.BRIGHT -> {
            val lift = if (isPlaying) sin(progress * PI * 8.0).toFloat() * h * 0.02f else 0f
            drawFourPointStar(
                center = Offset(pt(0.14f, 0.14f).x, pt(0.14f, 0.14f).y - lift),
                radius = w * 0.044f,
                color = Color(0xFFF59E0B)
            )
            drawFourPointStar(
                center = Offset(pt(0.86f, 0.20f).x, pt(0.86f, 0.20f).y + lift * 0.8f),
                radius = w * 0.036f,
                color = Color(0xFF22C55E)
            )
            drawMiniHeart(
                center = Offset(pt(0.82f, 0.10f).x, pt(0.82f, 0.10f).y - lift * 0.9f),
                sizePx = w * 0.072f,
                color = Color(0xFFFF6B9D)
            )
        }

        PuppyMood.SMILE -> {
            val bob = if (isPlaying) sin(progress * PI * 6.0).toFloat() * h * 0.012f else 0f
            drawFourPointStar(
                center = Offset(pt(0.84f, 0.16f).x, pt(0.84f, 0.16f).y - bob),
                radius = w * 0.044f,
                color = Color(0xFF38BDF8)
            )
        }

        PuppyMood.SAD -> {
            val gloomColor = Color(0xFF6366F1).copy(alpha = 0.62f)
            drawLine(gloomColor, pt(0.16f, 0.10f), pt(0.16f, 0.19f), thinW * 0.9f, StrokeCap.Round)
            drawLine(gloomColor, pt(0.195f, 0.085f), pt(0.195f, 0.20f), thinW * 0.9f, StrokeCap.Round)
            drawLine(gloomColor, pt(0.23f, 0.105f), pt(0.23f, 0.185f), thinW * 0.9f, StrokeCap.Round)
        }

        PuppyMood.ANGRY -> {
            val angerCenter = pt(0.76f, 0.15f)
            val angerR = w * 0.045f * (1f + barkPulse * 0.25f)
            drawRedAngerMark(center = angerCenter, r = angerR, strokeW = thinW * 1.05f)

            if (barkPulse > 0.10f) {
                val arcColor = Color(0xFFF97316).copy(alpha = (0.45f + 0.55f * barkPulse).coerceIn(0f, 1f))
                val rArc1 = w * (0.055f + 0.022f * barkPulse)
                val rArc2 = w * (0.095f + 0.028f * barkPulse)
                val rightOrigin = pt(0.76f, 0.38f)
                drawArc(
                    color = arcColor,
                    startAngle = -35f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(rightOrigin.x - rArc1, rightOrigin.y - rArc1),
                    size = Size(rArc1 * 2f, rArc1 * 2f),
                    style = Stroke(thinW, cap = StrokeCap.Round)
                )
                drawArc(
                    color = arcColor.copy(alpha = arcColor.alpha * 0.7f),
                    startAngle = -35f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(rightOrigin.x - rArc2, rightOrigin.y - rArc2),
                    size = Size(rArc2 * 2f, rArc2 * 2f),
                    style = Stroke(thinW * 0.85f, cap = StrokeCap.Round)
                )
                val leftOrigin = pt(0.22f, 0.38f)
                drawArc(
                    color = arcColor,
                    startAngle = 145f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(leftOrigin.x - rArc1, leftOrigin.y - rArc1),
                    size = Size(rArc1 * 2f, rArc1 * 2f),
                    style = Stroke(thinW, cap = StrokeCap.Round)
                )
            }
        }

        PuppyMood.SICK -> {
            // Rising wavy red fever steam above the curled sleeping head (y = 0.45 down to 0.30)
            val steamShift = if (isPlaying) (progress * 0.12f) % 0.04f else 0.01f
            val steamColor = Color(0xFFEF4444).copy(alpha = 0.68f)
            listOf(0.56f, 0.67f, 0.78f).forEachIndexed { idx, sx ->
                val baseNy = 0.48f - (steamShift + idx * 0.012f) % 0.04f
                val steam = Path().apply {
                    val p0 = pt(sx, baseNy)
                    val c1 = pt(sx - 0.025f, baseNy - 0.032f)
                    val m = pt(sx, baseNy - 0.064f)
                    val c2 = pt(sx + 0.025f, baseNy - 0.096f)
                    val p1 = pt(sx, baseNy - 0.128f)
                    moveTo(p0.x, p0.y)
                    quadraticTo(c1.x, c1.y, m.x, m.y)
                    quadraticTo(c2.x, c2.y, p1.x, p1.y)
                }
                drawPath(steam, color = steamColor, style = Stroke(width = thinW * 0.9f, cap = StrokeCap.Round))
            }
            // Small shiver vibration arcs beside the curled body during shivering bursts
            if (isPlaying) {
                val shiverAlpha = abs(sin(progress * PI * 3.0)).toFloat().coerceIn(0f, 1f) * 0.55f
                val shiverColor = Color(0xFF60A5FA).copy(alpha = shiverAlpha)
                val shStroke = Stroke(width = thinW * 0.85f, cap = StrokeCap.Round)
                drawArc(shiverColor, 145f, 70f, false, pt(0.03f, 0.62f), Size(w * 0.07f, h * 0.14f), style = shStroke)
                drawArc(shiverColor, -35f, 70f, false, pt(0.90f, 0.62f), Size(w * 0.07f, h * 0.14f), style = shStroke)
            }
        }
    }
}

private fun DrawScope.drawFourPointStar(center: Offset, radius: Float, color: Color) {
    val inner = radius * 0.30f
    val star = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x + inner * 0.4f, center.y - inner * 0.4f, center.x + radius, center.y)
        quadraticTo(center.x + inner * 0.4f, center.y + inner * 0.4f, center.x, center.y + radius)
        quadraticTo(center.x - inner * 0.4f, center.y + inner * 0.4f, center.x - radius, center.y)
        quadraticTo(center.x - inner * 0.4f, center.y - inner * 0.4f, center.x, center.y - radius)
        close()
    }
    drawPath(star, color = color)
}

private fun DrawScope.drawMiniHeart(center: Offset, sizePx: Float, color: Color) {
    val h = sizePx * 0.92f
    val topY = center.y - h * 0.35f
    val heart = Path().apply {
        moveTo(center.x, center.y + h * 0.48f)
        cubicTo(
            center.x - sizePx * 0.62f, center.y + h * 0.12f,
            center.x - sizePx * 0.56f, topY - h * 0.25f,
            center.x, topY + h * 0.12f
        )
        cubicTo(
            center.x + sizePx * 0.56f, topY - h * 0.25f,
            center.x + sizePx * 0.62f, center.y + h * 0.12f,
            center.x, center.y + h * 0.48f
        )
        close()
    }
    drawPath(heart, color = color)
}

private fun DrawScope.drawBlueTeardrop(
    center: Offset,
    dropW: Float,
    strokeW: Float,
    alpha: Float
) {
    val dropH = dropW * 1.42f
    val tear = Path().apply {
        moveTo(center.x, center.y - dropH * 0.52f)
        cubicTo(
            center.x + dropW * 0.62f, center.y + dropH * 0.08f,
            center.x + dropW * 0.50f, center.y + dropH * 0.52f,
            center.x, center.y + dropH * 0.52f
        )
        cubicTo(
            center.x - dropW * 0.50f, center.y + dropH * 0.52f,
            center.x - dropW * 0.62f, center.y + dropH * 0.08f,
            center.x, center.y - dropH * 0.52f
        )
        close()
    }
    drawPath(tear, color = Color(0xFF7DD3FC).copy(alpha = alpha))
    drawPath(
        tear,
        color = Color(0xFF2563EB).copy(alpha = alpha),
        style = Stroke(width = strokeW, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawRedAngerMark(center: Offset, r: Float, strokeW: Float) {
    val gap = r * 0.26f
    val angerStroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val angerRed = Color(0xFFEF4444)
    listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
        val corner = Path().apply {
            moveTo(center.x + sx * r, center.y + sy * gap)
            quadraticTo(center.x + sx * gap, center.y + sy * gap, center.x + sx * gap, center.y + sy * r)
        }
        drawPath(corner, color = angerRed, style = angerStroke)
    }
}

@Composable
fun PuppyFaceMascot(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.itv_puppy_face),
        contentDescription = "정면을 바라보는 강아지 얼굴",
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF8FAF9, widthDp = 700, heightDp = 240)
@Composable
private fun EquippedPuppyMoodsPreview() {
    Row(
        modifier = Modifier
            .background(Color(0xFFF8FAF9))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PuppyMood.entries.forEach { mood ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EquippedPuppyMascot(
                    equippedItems = emptyMap(),
                    mood = mood,
                    showGroundShadow = true,
                    animateMood = false,
                    modifier = Modifier.size(126.dp, 184.dp)
                )
                Text(text = mood.label, fontSize = 12.sp, color = Color(0xFF2B231E))
            }
        }
    }
}
