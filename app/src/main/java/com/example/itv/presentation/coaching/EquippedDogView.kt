package com.example.itv.presentation.coaching

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.example.itv.R
import com.example.itv.domain.equip.EquipItem
import com.example.itv.domain.equip.EquipSlot
import com.example.itv.infrastructure.equip.UserEquipStore

/**
 * 숏폼 개입/코칭 및 프리뷰 시 강아지의 달리기 프레임별 신체 랜드마크(눈·코·머리 각도)를 실시간 추적하여
 * 착용한 코디 아이템들이 강아지의 움직임에 완벽히 동기화되어 달려오도록 하는 커스텀 뷰
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
        // 400x400 원본 해상도 기준 각 달리기 프레임별 정밀 랜드마크 (발구름 각도 feetAngle 포함)
        private val FRAME_LANDMARKS = mapOf(
            R.drawable.itv_motion_front to FrameLandmark(201.65f, 171.29f, 0.93f, 72.48f, 200.00f, 79.29f, 195.29f, 189.88f, 200.00f, 266.35f, 200.00f, 381.45f, 0.00f),
            R.drawable.itv_motion_turn to FrameLandmark(201.65f, 134.82f, 0.93f, 72.48f, 200.00f, 42.82f, 195.29f, 153.41f, 200.00f, 229.88f, 200.00f, 381.45f, 0.00f),
            R.drawable.itv_motion_sad to FrameLandmark(201.65f, 171.29f, 0.93f, 72.48f, 200.00f, 79.29f, 195.29f, 189.88f, 200.00f, 266.35f, 200.00f, 381.45f, 0.00f),
            R.drawable.itv_motion_sleep to FrameLandmark(201.65f, 171.29f, 0.93f, 72.48f, 200.00f, 79.29f, 195.29f, 189.88f, 200.00f, 266.35f, 200.00f, 381.45f, 0.00f),
            R.drawable.itv_motion_angry to FrameLandmark(201.65f, 171.29f, 0.93f, 72.48f, 200.00f, 79.29f, 195.29f, 189.88f, 200.00f, 266.35f, 200.00f, 381.45f, 0.00f),
            R.drawable.itv_motion_bone to FrameLandmark(201.65f, 171.29f, 0.93f, 72.48f, 200.00f, 79.29f, 195.29f, 189.88f, 200.00f, 266.35f, 200.00f, 381.45f, 0.00f),
            R.drawable.itv_motion_wave to FrameLandmark(193.72f, 91.69f, -1.49f, 68.51f, 197.36f, 2.45f, 181.69f, 114.85f, 200.00f, 185.21f, 192.96f, 304.85f, 0.00f),
            R.drawable.itv_gallop_away_00 to FrameLandmark(301.53f, 132.17f, 0.00f, 23.53f, 267.82f, 57.90f, 372.25f, 138.17f, 250.18f, 227.45f, 263.22f, 356.96f, 0.00f),
            R.drawable.itv_gallop_away_01 to FrameLandmark(299.99f, 127.46f, 0.00f, 23.53f, 266.15f, 52.97f, 370.68f, 133.91f, 248.51f, 220.26f, 267.31f, 365.67f, 0.00f),
            R.drawable.itv_gallop_away_02 to FrameLandmark(298.15f, 132.83f, 0.00f, 23.53f, 263.64f, 57.20f, 368.58f, 141.64f, 245.99f, 222.63f, 260.93f, 368.24f, 0.00f),
            R.drawable.itv_gallop_away_03 to FrameLandmark(296.14f, 138.40f, 0.00f, 23.53f, 261.75f, 62.99f, 366.62f, 146.76f, 244.10f, 231.13f, 259.38f, 359.15f, 0.00f),
            R.drawable.itv_gallop_away_04 to FrameLandmark(295.30f, 132.96f, 0.00f, 23.53f, 261.59f, 58.69f, 366.02f, 138.96f, 243.94f, 228.64f, 265.26f, 358.01f, 0.00f),
            R.drawable.itv_gallop_away_05 to FrameLandmark(297.10f, 128.53f, 0.00f, 23.53f, 263.26f, 54.05f, 367.78f, 134.99f, 245.61f, 221.46f, 267.51f, 367.02f, 0.00f),
            R.drawable.itv_gallop_away_06 to FrameLandmark(300.29f, 134.72f, 0.00f, 23.53f, 265.77f, 59.10f, 370.72f, 143.54f, 248.13f, 225.51f, 259.92f, 368.24f, 0.00f),
            R.drawable.itv_gallop_away_07 to FrameLandmark(302.05f, 137.99f, 0.00f, 23.53f, 267.66f, 62.58f, 372.54f, 146.35f, 250.02f, 230.51f, 255.95f, 360.99f, 0.00f),
            R.drawable.itv_walk_half_00 to FrameLandmark(300.80f, 128.99f, 0.00f, 23.53f, 267.18f, 54.85f, 371.55f, 134.73f, 249.53f, 223.80f, 266.19f, 360.80f, 0.00f),
            R.drawable.itv_walk_half_01 to FrameLandmark(299.12f, 130.81f, 0.00f, 23.53f, 264.91f, 55.72f, 369.68f, 138.55f, 247.26f, 221.00f, 265.14f, 368.24f, 0.00f),
            R.drawable.itv_walk_half_02 to FrameLandmark(297.12f, 138.17f, 0.00f, 23.53f, 262.53f, 62.42f, 367.51f, 147.25f, 244.88f, 229.56f, 258.54f, 364.66f, 0.00f),
            R.drawable.itv_walk_half_03 to FrameLandmark(295.44f, 136.34f, 0.00f, 23.53f, 261.42f, 61.56f, 366.06f, 143.44f, 243.77f, 230.91f, 262.08f, 356.39f, 0.00f),
            R.drawable.itv_walk_half_04 to FrameLandmark(295.86f, 129.62f, 0.00f, 23.53f, 262.24f, 55.48f, 366.61f, 135.36f, 244.59f, 224.76f, 267.54f, 362.60f, 0.00f),
            R.drawable.itv_walk_half_05 to FrameLandmark(298.71f, 132.47f, 0.00f, 23.53f, 264.50f, 57.38f, 369.26f, 140.21f, 246.85f, 222.98f, 264.14f, 368.24f, 0.00f),
            R.drawable.itv_walk_half_06 to FrameLandmark(301.47f, 138.26f, 0.00f, 23.53f, 266.88f, 62.50f, 371.87f, 147.34f, 249.24f, 229.70f, 256.39f, 366.52f, 0.00f),
            R.drawable.itv_walk_half_07 to FrameLandmark(302.02f, 135.63f, 0.00f, 23.53f, 267.99f, 60.84f, 372.64f, 142.72f, 250.35f, 229.83f, 257.70f, 356.89f, 0.00f),
            R.drawable.itv_look_front_00 to FrameLandmark(296.02f, 130.99f, 0.00f, 23.53f, 262.32f, 56.75f, 366.74f, 136.96f, 244.67f, 226.09f, 262.87f, 365.28f, 0.00f),
            R.drawable.itv_look_front_01 to FrameLandmark(297.43f, 129.13f, 0.00f, 23.53f, 263.89f, 54.36f, 367.98f, 136.41f, 246.25f, 221.46f, 260.33f, 366.75f, 0.00f),
            R.drawable.itv_look_front_02 to FrameLandmark(294.15f, 135.24f, 0.00f, 23.53f, 264.91f, 59.42f, 363.56f, 147.22f, 247.38f, 227.62f, 259.56f, 363.03f, 0.00f),
            R.drawable.itv_look_front_03 to FrameLandmark(304.49f, 132.77f, -2.01f, 38.80f, 264.33f, 56.56f, 352.41f, 151.59f, 247.50f, 224.21f, 260.99f, 363.54f, 0.00f),
            R.drawable.itv_look_front_04 to FrameLandmark(293.51f, 130.35f, -3.87f, 45.82f, 261.34f, 53.14f, 334.01f, 156.34f, 246.46f, 222.36f, 262.60f, 367.06f, 0.00f),
            R.drawable.itv_look_front_05 to FrameLandmark(277.85f, 130.19f, -3.65f, 54.15f, 255.18f, 50.06f, 306.72f, 159.78f, 243.93f, 223.71f, 262.59f, 368.24f, 0.00f),
            R.drawable.itv_look_front_06 to FrameLandmark(259.83f, 131.97f, -2.05f, 61.80f, 245.96f, 47.58f, 275.17f, 159.41f, 239.06f, 226.68f, 262.11f, 368.24f, 0.00f),
            R.drawable.itv_look_front_07 to FrameLandmark(242.32f, 136.32f, -0.47f, 67.45f, 234.94f, 48.03f, 246.39f, 159.56f, 231.64f, 231.41f, 259.71f, 366.49f, 0.00f),
            R.drawable.itv_look_front_08 to FrameLandmark(227.64f, 143.14f, 0.41f, 70.56f, 223.81f, 52.57f, 225.27f, 163.55f, 222.55f, 238.25f, 250.93f, 362.72f, 0.00f),
            R.drawable.itv_look_front_09 to FrameLandmark(216.77f, 147.80f, 0.66f, 71.48f, 213.98f, 56.55f, 212.50f, 167.34f, 213.32f, 242.89f, 238.31f, 360.71f, 0.00f),
            R.drawable.itv_look_front_10 to FrameLandmark(208.44f, 138.05f, 0.76f, 71.83f, 206.06f, 46.53f, 203.44f, 157.26f, 205.64f, 233.14f, 223.31f, 361.47f, 0.00f),
            R.drawable.itv_look_front_11 to FrameLandmark(203.19f, 134.81f, 0.84f, 72.14f, 201.15f, 43.06f, 197.54f, 153.72f, 200.93f, 229.88f, 210.67f, 366.92f, 0.00f),
            R.drawable.itv_look_front_12 to FrameLandmark(201.86f, 134.82f, 0.90f, 72.36f, 200.08f, 42.91f, 195.75f, 153.52f, 200.00f, 229.88f, 204.31f, 373.01f, 0.00f),
            R.drawable.itv_look_front_13 to FrameLandmark(201.66f, 134.82f, 0.93f, 72.47f, 200.00f, 42.83f, 195.32f, 153.42f, 200.00f, 229.88f, 200.69f, 379.38f, 0.00f),
            R.drawable.itv_gallop_bone_00 to FrameLandmark(204.70f, 135.37f, -0.21f, 72.48f, 203.12f, 43.41f, 198.72f, 154.09f, 203.12f, 232.64f, 200.00f, 370.24f, 0.00f),
            R.drawable.itv_gallop_bone_01 to FrameLandmark(203.05f, 133.49f, 0.16f, 72.48f, 201.45f, 41.51f, 196.95f, 152.16f, 201.45f, 230.05f, 200.00f, 379.80f, 0.00f),
            R.drawable.itv_gallop_bone_02 to FrameLandmark(200.64f, 137.49f, 2.07f, 72.48f, 198.93f, 45.45f, 193.92f, 155.94f, 198.93f, 230.34f, 200.00f, 381.03f, 0.00f),
            R.drawable.itv_gallop_bone_03 to FrameLandmark(198.73f, 136.16f, 1.70f, 72.48f, 197.04f, 44.13f, 192.13f, 154.66f, 197.04f, 229.72f, 200.00f, 371.62f, 0.00f),
            R.drawable.itv_gallop_bone_04 to FrameLandmark(198.46f, 135.37f, -0.21f, 72.48f, 196.88f, 43.41f, 192.48f, 154.09f, 196.88f, 232.64f, 200.00f, 371.72f, 0.00f),
            R.drawable.itv_gallop_bone_05 to FrameLandmark(200.15f, 134.41f, 0.16f, 72.48f, 198.55f, 42.43f, 194.05f, 153.08f, 198.55f, 230.96f, 200.00f, 381.20f, 0.00f),
            R.drawable.itv_gallop_bone_06 to FrameLandmark(202.78f, 139.01f, 2.07f, 72.48f, 201.07f, 46.98f, 196.06f, 157.47f, 201.07f, 231.86f, 200.00f, 380.82f, 0.00f),
            R.drawable.itv_gallop_bone_07 to FrameLandmark(204.65f, 136.92f, 1.70f, 72.48f, 202.96f, 44.90f, 198.05f, 155.43f, 202.96f, 230.48f, 200.00f, 372.74f, 0.00f),
            R.drawable.itv_gallop_front_00 to FrameLandmark(193.72f, 91.69f, -1.49f, 68.51f, 197.36f, 2.45f, 181.69f, 114.85f, 200.00f, 185.21f, 192.96f, 304.85f, 0.00f),
            R.drawable.itv_gallop_front_01 to FrameLandmark(204.70f, 135.37f, -0.21f, 72.48f, 203.12f, 43.41f, 198.72f, 154.09f, 203.12f, 232.64f, 200.00f, 370.24f, 0.00f),
            R.drawable.itv_gallop_front_02 to FrameLandmark(202.27f, 135.03f, 0.83f, 72.48f, 200.63f, 43.03f, 195.95f, 153.63f, 200.63f, 230.28f, 200.00f, 381.77f, 0.00f),
            R.drawable.itv_gallop_front_03 to FrameLandmark(199.24f, 137.17f, 2.17f, 72.48f, 197.52f, 45.14f, 192.48f, 155.62f, 197.52f, 229.83f, 200.00f, 375.04f, 0.00f),
            R.drawable.itv_gallop_front_04 to FrameLandmark(198.46f, 135.37f, -0.21f, 72.48f, 196.88f, 43.41f, 192.48f, 154.09f, 196.88f, 232.64f, 200.00f, 371.72f, 0.00f),
            R.drawable.itv_gallop_front_05 to FrameLandmark(201.01f, 137.92f, 0.83f, 72.48f, 199.37f, 45.92f, 194.69f, 156.52f, 199.37f, 233.17f, 200.00f, 382.00f, 0.00f),
            R.drawable.itv_gallop_front_06 to FrameLandmark(204.20f, 138.21f, 2.17f, 72.48f, 202.48f, 46.18f, 197.45f, 156.66f, 202.48f, 230.87f, 200.00f, 376.54f, 0.00f),
            R.drawable.itv_motion_run_a to FrameLandmark(301.53f, 132.17f, 0.00f, 23.53f, 267.82f, 57.90f, 372.25f, 138.17f, 250.18f, 227.45f, 263.22f, 356.96f, 0.00f),
            R.drawable.itv_motion_front_run_a to FrameLandmark(204.70f, 135.37f, -0.21f, 72.48f, 203.12f, 43.41f, 198.72f, 154.09f, 203.12f, 232.64f, 200.00f, 370.24f, 0.00f),
            R.drawable.itv_motion_run_b to FrameLandmark(295.30f, 132.96f, 0.00f, 23.53f, 261.59f, 58.69f, 366.02f, 138.96f, 243.94f, 228.64f, 265.26f, 358.01f, 0.00f),
            R.drawable.itv_motion_front_run_b to FrameLandmark(198.46f, 135.37f, -0.21f, 72.48f, 196.88f, 43.41f, 192.48f, 154.09f, 196.88f, 232.64f, 200.00f, 371.72f, 0.00f)
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

    init {
        addView(baseDog)
        addView(clothesLayer)
        addView(shoesLayer)
        addView(noseLayer)
        addView(glassesLayer)
        addView(hatLayer)
        refreshEquipped()
    }

    fun setImageResource(@DrawableRes resId: Int) {
        baseDog.setImageResource(resId)
        if (currentSpriteRes != resId) {
            currentSpriteRes = resId
            requestLayout()
        }
    }

    fun setImageDrawable(drawable: Drawable?) {
        baseDog.setImageDrawable(drawable)
    }

    fun refreshEquipped() {
        setEquipment(UserEquipStore.getEquipped(context))
    }

    fun setEquipment(equipped: Map<EquipSlot, EquipItem?>) {
        updateLayer(clothesLayer, equipped[EquipSlot.CLOTHES]?.iconRes)
        updateLayer(shoesLayer, equipped[EquipSlot.SHOES]?.iconRes)
        updateLayer(noseLayer, equipped[EquipSlot.NOSE_MOUTH]?.iconRes)
        updateLayer(glassesLayer, equipped[EquipSlot.GLASSES]?.iconRes)
        updateLayer(hatLayer, equipped[EquipSlot.HAT]?.iconRes)
        requestLayout()
    }

    private fun updateLayer(layer: ImageView, @DrawableRes resId: Int?) {
        if (resId != null) {
            layer.setImageResource(resId)
            layer.visibility = View.VISIBLE
        } else {
            layer.setImageDrawable(null)
            layer.visibility = View.GONE
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return

        val landmark = FRAME_LANDMARKS[currentSpriteRes] ?: DEFAULT_LANDMARK
        val scale = w.toFloat() / 400f

        // 1. 모자 (머리 정수리에 푹 눌러쓴 실제 모자 크기와 핏 + 고개 회전각 동기화)
        if (hatLayer.visibility == View.VISIBLE) {
            val hatBoxSize = 180f * scale
            val cx = landmark.headTopX * scale
            val cy = (landmark.headTopY + 45f) * scale
            layoutCenter(hatLayer, cx, cy, hatBoxSize, hatBoxSize)
            hatLayer.rotation = landmark.eyeAngle
        }

        // 2. 안경 (양 눈 중심점 및 안경알 거리 완벽 동기화 + 눈 기울기 회전각)
        if (glassesLayer.visibility == View.VISIBLE) {
            val glassesBoxSize = (landmark.eyeSpan / 0.37f) * scale
            val cx = landmark.eyeCenterX * scale
            val cy = landmark.eyeCenterY * scale - (glassesBoxSize * 0.17f)
            layoutCenter(glassesLayer, cx, cy, glassesBoxSize, glassesBoxSize)
            glassesLayer.rotation = landmark.eyeAngle
        }

        // 3. 코와 입 (머즐 중심점 실시간 추적 + 회전각 동기화)
        if (noseLayer.visibility == View.VISIBLE) {
            val noseBoxSize = 75f * scale
            val cx = landmark.noseX * scale
            val cy = (landmark.noseY + 4f) * scale
            layoutCenter(noseLayer, cx, cy, noseBoxSize, noseBoxSize)
            noseLayer.rotation = landmark.eyeAngle
        }

        // 4. 옷 (강아지 몸통 전체를 포근히 감싸는 핏 + 달리기 가슴 반동 동기화)
        if (clothesLayer.visibility == View.VISIBLE) {
            val clothesBoxSize = 240f * scale
            val cx = landmark.chestX * scale
            val cy = (landmark.chestY - 5f) * scale
            layoutCenter(clothesLayer, cx, cy, clothesBoxSize, clothesBoxSize)
            clothesLayer.rotation = landmark.eyeAngle * 0.4f
        }

        // 5. 신발 (발의 실제 크기에 맞고 달리기 발구름 각도 feetAngle에 따라 실시간 틸팅)
        if (shoesLayer.visibility == View.VISIBLE) {
            val shoesBoxSize = 180f * scale
            val cx = landmark.feetX * scale
            val cy = (landmark.feetY - 18f) * scale
            layoutCenter(shoesLayer, cx, cy, shoesBoxSize, shoesBoxSize)
            shoesLayer.rotation = landmark.feetAngle
        }
    }

    private fun layoutCenter(v: View, cx: Float, cy: Float, w: Float, h: Float) {
        val l = (cx - (w / 2f)).toInt()
        val t = (cy - (h / 2f)).toInt()
        v.layout(l, t, (l + w).toInt(), (t + h).toInt())
    }
}
