package com.example.itv.presentation.coaching

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.example.itv.R
import com.example.itv.domain.equip.EquipSlot
import com.example.itv.infrastructure.equip.UserEquipStore
import java.util.concurrent.Executors

/**
 * 숏폼 개입/코칭 및 프리뷰 시 강아지의 달리기 프레임별 신체 랜드마크(눈·코·머리 각도)를 실시간 추적하여
 * 착용한 코디 아이템들이 강아지의 움직임에 완벽히 동기화되어 달려오도록 하는 커스텀 뷰.
 *
 * 성능 최적화:
 * - 400x400 달리기 스프라이트를 LruCache에 사전 디코딩하여 프레임 전환 시 메인 스레드 PNG 디코딩 제거
 * - 동일 리소스 재설정 무시(early-out)로 불필요한 invalidation 차단
 * - 스프라이트 프레임 교체 시 requestLayout() 대신 GPU translation/scale/rotation만 갱신
 */
class EquippedDogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private data class FrameLandmark(
        val eyeCenterX: Float,
        val eyeCenterY: Float,
        val eyeAngle: Float,
        val eyeSpan: Float,
        val headTopX: Float,
        val headTopY: Float,
        val noseX: Float,
        val noseY: Float,
        val chestX: Float,
        val chestY: Float,
        val feetX: Float,
        val feetY: Float,
        val feetAngle: Float
    )

    companion object {
        // 400x400 스프라이트 약 24~26장(장당 ~640KB)을 메모리에 캐시하여 애니메이션 중 디코딩 0회 유지
        private const val SPRITE_CACHE_KB = 18 * 1024
        private val spriteCache = object : LruCache<Int, Bitmap>(SPRITE_CACHE_KB) {
            override fun sizeOf(key: Int, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
        }
        private val preloadExecutor = Executors.newSingleThreadExecutor()

        /**
         * 애니메이션 시작 직전 백그라운드 스레드에서 스프라이트 프레임을 미리 디코딩해 둡니다.
         */
        fun preloadSprites(context: Context, resIds: IntArray) {
            val appCtx = context.applicationContext
            preloadExecutor.execute {
                for (resId in resIds) {
                    if (resId == 0 || spriteCache.get(resId) != null) continue
                    decodeAndCache(appCtx, resId)
                }
            }
        }

        private fun getOrDecodeSprite(context: Context, @DrawableRes resId: Int): Bitmap? {
            if (resId == 0) return null
            spriteCache.get(resId)?.let { return it }
            return decodeAndCache(context.applicationContext, resId)
        }

        private fun decodeAndCache(appCtx: Context, @DrawableRes resId: Int): Bitmap? {
            return runCatching {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeResource(appCtx.resources, resId, opts)?.also { bmp ->
                    spriteCache.put(resId, bmp)
                }
            }.getOrNull()
        }

        // 400x400 원본 해상도 기준 각 달리기 프레임별 정밀 랜드마크 (발구름 각도 feetAngle 포함)
        private val FRAME_LANDMARKS = mapOf(
            // 프레임 0: 안기려고 점프하는 자세
            R.drawable.itv_gallop_front_00 to FrameLandmark(
                eyeCenterX = 210.9f, eyeCenterY = 135.6f, eyeAngle = -5.2f, eyeSpan = 66.0f,
                headTopX = 199.0f, headTopY = 32.0f, noseX = 203.7f, noseY = 154.9f,
                chestX = 208.3f, chestY = 245.7f, feetX = 205.5f, feetY = 361.2f, feetAngle = 0.6f
            ),
            // 프레임 1~6: 신나게 정면으로 달려오는 연속 프레임 (발걸음 방향과 각도 실시간 반영)
            R.drawable.itv_gallop_front_01 to FrameLandmark(
                eyeCenterX = 206.4f, eyeCenterY = 132.2f, eyeAngle = 1.1f, eyeSpan = 67.3f,
                headTopX = 200.5f, headTopY = 32.0f, noseX = 202.2f, noseY = 148.8f,
                chestX = 215.4f, chestY = 245.6f, feetX = 186.3f, feetY = 358.8f, feetAngle = -12.7f
            ),
            R.drawable.itv_gallop_front_02 to FrameLandmark(
                eyeCenterX = 202.0f, eyeCenterY = 130.2f, eyeAngle = 3.3f, eyeSpan = 66.1f,
                headTopX = 200.0f, headTopY = 32.0f, noseX = 200.1f, noseY = 148.2f,
                chestX = 183.4f, chestY = 246.8f, feetX = 179.0f, feetY = 358.8f, feetAngle = -10.8f
            ),
            R.drawable.itv_gallop_front_03 to FrameLandmark(
                eyeCenterX = 216.1f, eyeCenterY = 132.3f, eyeAngle = -11.9f, eyeSpan = 59.7f,
                headTopX = 212.0f, headTopY = 34.0f, noseX = 207.5f, noseY = 149.1f,
                chestX = 206.1f, chestY = 245.5f, feetX = 208.8f, feetY = 358.2f, feetAngle = 11.3f
            ),
            R.drawable.itv_gallop_front_04 to FrameLandmark(
                eyeCenterX = 193.4f, eyeCenterY = 135.9f, eyeAngle = 10.6f, eyeSpan = 65.7f,
                headTopX = 199.0f, headTopY = 32.0f, noseX = 195.8f, noseY = 156.0f,
                chestX = 211.1f, chestY = 249.3f, feetX = 206.2f, feetY = 359.4f, feetAngle = 2.0f
            ),
            R.drawable.itv_gallop_front_05 to FrameLandmark(
                eyeCenterX = 200.7f, eyeCenterY = 133.6f, eyeAngle = 3.6f, eyeSpan = 60.7f,
                headTopX = 200.0f, headTopY = 32.0f, noseX = 200.8f, noseY = 151.0f,
                chestX = 215.3f, chestY = 244.6f, feetX = 217.3f, feetY = 360.9f, feetAngle = 10.9f
            ),
            R.drawable.itv_gallop_front_06 to FrameLandmark(
                eyeCenterX = 204.5f, eyeCenterY = 132.2f, eyeAngle = -0.1f, eyeSpan = 55.6f,
                headTopX = 203.0f, headTopY = 36.0f, noseX = 201.2f, noseY = 147.9f,
                chestX = 206.2f, chestY = 245.4f, feetX = 207.5f, feetY = 356.6f, feetAngle = 11.3f
            )
        )
        private val DEFAULT_LANDMARK = FrameLandmark(
            eyeCenterX = 205f, eyeCenterY = 133f, eyeAngle = 0f, eyeSpan = 64f,
            headTopX = 200f, headTopY = 32f, noseX = 202f, noseY = 150f,
            chestX = 205f, chestY = 245f, feetX = 205f, feetY = 358f, feetAngle = 0f
        )
    }

    val baseDog = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private val clothesLayer = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val shoesLayer = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val noseLayer = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val glassesLayer = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val hatLayer = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

    private var currentSpriteRes: Int = 0
    private var hasAnyEquipped: Boolean = false
    private var lastLaidOutWidth: Int = 0

    init {
        clipChildren = false
        clipToPadding = false
        addView(baseDog)
        addView(clothesLayer)
        addView(shoesLayer)
        addView(noseLayer)
        addView(glassesLayer)
        addView(hatLayer)
        refreshEquipped()
    }

    fun setImageResource(@DrawableRes resId: Int) {
        if (currentSpriteRes == resId && resId != 0) return
        currentSpriteRes = resId
        val cached = getOrDecodeSprite(context, resId)
        if (cached != null) {
            baseDog.setImageBitmap(cached)
        } else {
            baseDog.setImageResource(resId)
        }
        // 이미 레이아웃된 상태라면 requestLayout() 없이 GPU 속성(translation/scale/rotation)만 즉시 동기화
        if (hasAnyEquipped && width > 0 && height > 0) {
            applyEquippedTransforms(width)
        }
    }

    fun setImageDrawable(drawable: Drawable?) {
        currentSpriteRes = 0
        baseDog.setImageDrawable(drawable)
    }

    fun refreshEquipped() {
        val equipped = UserEquipStore.getEquipped(context)
        val hasClothes = updateLayer(clothesLayer, equipped[EquipSlot.CLOTHES]?.iconRes)
        val hasShoes = updateLayer(shoesLayer, equipped[EquipSlot.SHOES]?.iconRes)
        val hasNose = updateLayer(noseLayer, equipped[EquipSlot.NOSE_MOUTH]?.iconRes)
        val hasGlasses = updateLayer(glassesLayer, equipped[EquipSlot.GLASSES]?.iconRes)
        val hasHat = updateLayer(hatLayer, equipped[EquipSlot.HAT]?.iconRes)
        hasAnyEquipped = hasClothes || hasShoes || hasNose || hasGlasses || hasHat
        lastLaidOutWidth = 0
        requestLayout()
    }

    private fun updateLayer(layer: ImageView, @DrawableRes resId: Int?): Boolean {
        return if (resId != null) {
            val cached = getOrDecodeSprite(context, resId)
            if (cached != null) {
                layer.setImageBitmap(cached)
            } else {
                layer.setImageResource(resId)
            }
            layer.visibility = View.VISIBLE
            true
        } else {
            layer.setImageDrawable(null)
            layer.visibility = View.GONE
            false
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0 || !hasAnyEquipped) return

        // 각 코디 레이어의 기본 박스 크기는 View 너비가 바뀔 때만 1회 layout(0, 0, box, box) 설정
        if (changed || lastLaidOutWidth != w) {
            val scale = w.toFloat() / 400f
            layoutOriginBox(hatLayer, 180f * scale)
            layoutOriginBox(glassesLayer, (DEFAULT_LANDMARK.eyeSpan / 0.37f) * scale)
            layoutOriginBox(noseLayer, 75f * scale)
            layoutOriginBox(clothesLayer, 240f * scale)
            layoutOriginBox(shoesLayer, 180f * scale)
            lastLaidOutWidth = w
        }
        applyEquippedTransforms(w)
    }

    private fun layoutOriginBox(v: View, sizePx: Float) {
        if (v.visibility != View.VISIBLE) return
        val box = sizePx.toInt().coerceAtLeast(1)
        v.layout(0, 0, box, box)
        v.pivotX = box / 2f
        v.pivotY = box / 2f
    }

    private fun applyEquippedTransforms(w: Int) {
        val landmark = FRAME_LANDMARKS[currentSpriteRes] ?: DEFAULT_LANDMARK
        val scale = w.toFloat() / 400f

        // 1. 모자 (머리 정수리에 푹 눌러쓴 실제 모자 크기와 핏 + 고개 회전각 동기화)
        if (hatLayer.visibility == View.VISIBLE) {
            val hatBoxSize = 180f * scale
            val cx = landmark.headTopX * scale
            val cy = (landmark.headTopY + 45f) * scale
            positionLayer(hatLayer, cx, cy, hatBoxSize, landmark.eyeAngle)
        }

        // 2. 안경 (양 눈 중심점 및 안경알 거리 완벽 동기화 + 눈 기울기 회전각)
        if (glassesLayer.visibility == View.VISIBLE) {
            val baseBoxSize = (DEFAULT_LANDMARK.eyeSpan / 0.37f) * scale
            val targetBoxSize = (landmark.eyeSpan / 0.37f) * scale
            val ratio = (targetBoxSize / baseBoxSize).coerceIn(0.5f, 1.5f)
            val cx = landmark.eyeCenterX * scale
            val cy = landmark.eyeCenterY * scale - (targetBoxSize * 0.17f)
            glassesLayer.scaleX = ratio
            glassesLayer.scaleY = ratio
            positionLayer(glassesLayer, cx, cy, baseBoxSize, landmark.eyeAngle)
        }

        // 3. 코와 입 (머즐 중심점 실시간 추적 + 회전각 동기화)
        if (noseLayer.visibility == View.VISIBLE) {
            val noseBoxSize = 75f * scale
            val cx = landmark.noseX * scale
            val cy = (landmark.noseY + 4f) * scale
            positionLayer(noseLayer, cx, cy, noseBoxSize, landmark.eyeAngle)
        }

        // 4. 옷 (강아지 몸통 전체를 포근히 감싸는 핏 + 달리기 가슴 반동 동기화)
        if (clothesLayer.visibility == View.VISIBLE) {
            val clothesBoxSize = 240f * scale
            val cx = landmark.chestX * scale
            val cy = (landmark.chestY - 5f) * scale
            positionLayer(clothesLayer, cx, cy, clothesBoxSize, landmark.eyeAngle * 0.4f)
        }

        // 5. 신발 (발의 실제 크기에 맞고 달리기 발구름 각도 feetAngle에 따라 실시간 틸팅)
        if (shoesLayer.visibility == View.VISIBLE) {
            val shoesBoxSize = 180f * scale
            val cx = landmark.feetX * scale
            val cy = (landmark.feetY - 18f) * scale
            positionLayer(shoesLayer, cx, cy, shoesBoxSize, landmark.feetAngle)
        }
    }

    private fun positionLayer(v: View, cx: Float, cy: Float, boxSize: Float, angle: Float) {
        v.translationX = cx - (boxSize / 2f)
        v.translationY = cy - (boxSize / 2f)
        v.rotation = angle
    }
}
