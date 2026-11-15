package com.example.itv.presentation.character

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.itv.domain.coaching.CharacterIntensity
import com.example.itv.domain.coaching.InterventionReasonResolver
import com.example.itv.domain.coaching.RestMissionCatalog
import com.example.itv.domain.coaching.RestRecommendation
import com.example.itv.domain.coaching.UserPosture
import com.example.itv.infrastructure.observation.LightAndBrightnessTracker
import com.example.itv.presentation.coaching.CoachingOverlayFactory

/**
 * Shares the production coaching overlay scene in ONE Activity window.
 * Dynamically reacts to real-time phone pitch, roll, and lux values,
 * generating customized intervention reasons and stretching missions.
 */
class CompanionPreviewActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var host: FrameLayout
    private lateinit var factory: CoachingOverlayFactory
    private lateinit var statusText: TextView
    private lateinit var rewardText: TextView
    private lateinit var tracker: LightAndBrightnessTracker

    private var current: View? = null
    private var selected = false
    private var activeRecommendation: RestRecommendation? = null

    private val sensorUpdater = object : Runnable {
        override fun run() {
            updateStatusDisplay()
            handler.postDelayed(this, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tracker = LightAndBrightnessTracker.getInstance(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Color.rgb(245, 247, 248))
        }

        // 1. Real-time sensor status header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        statusText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(33, 33, 33))
            text = "실시간 센서 측정 중..."
        }
        header.addView(statusText)

        rewardText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(46, 125, 50))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
            text = "성장 현황: 로딩 중..."
        }
        header.addView(rewardText)

        val guideText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(4), 0, 0)
            text = "※ 폰 각도(Pitch)나 조도를 바꾼 뒤 아래 버튼을 누르면 사유와 미션이 동적으로 바뀝니다."
        }
        header.addView(guideText)
        column.addView(header)

        // 2. Action buttons bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.rgb(238, 242, 246))
        }
        fun button(text: String, isPrimary: Boolean = false, action: () -> Unit) {
            val btn = Button(this).apply {
                this.text = text
                textSize = 11.5f
                setPadding(dp(4), dp(4), dp(4), dp(4))
                if (isPrimary) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(46, 125, 50))
                }
                setOnClickListener { action() }
            }
            bar.addView(btn, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }

        button("일반 개입", isPrimary = true) { show("offer_normal") }
        button("강한 개입") { show("offer_strong") }
        button("재약속") { show("retry") }
        button("완료") { show("complete") }
        button("닫기") { finish() }
        column.addView(bar)

        // 3. Companion display host area
        host = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(221, 236, 225))
            clipChildren = false
            clipToPadding = false
        }
        column.addView(host, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)

        factory = CoachingOverlayFactory(this, host)
        host.post {
            if (!isFinishing) show("offer_normal")
        }
    }

    override fun onResume() {
        super.onResume()
        tracker.start()
        tracker.startContinuousPostureListening()
        handler.removeCallbacks(sensorUpdater)
        handler.post(sensorUpdater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(sensorUpdater)
        tracker.stopContinuousPostureListening()
        tracker.stop()
    }

    private fun updateStatusDisplay() {
        val snap = tracker.snapshot.value
        val postureName = when (snap.posture) {
            UserPosture.LYING_DOWN -> "수평/하향 각도 (목 하중 부담)"
            UserPosture.SITTING_OR_STANDING -> "정면 파지 각도 (어깨 뭉침)"
            UserPosture.TABLE_OR_DESK -> "식탁/책상 거치 (고개 숙임)"
            UserPosture.FACEDOWN -> "화면 엎어둠 (완전 차단)"
            UserPosture.UNKNOWN -> "각도 감지 중"
        }
        val pitchStr = snap.pitchDegrees?.let { "%.1f°".format(it) } ?: "-"
        val rollStr = snap.rollDegrees?.let { "%.1f°".format(it) } ?: "-"
        val luxStr = snap.lux?.let { "%.1f lx".format(it) } ?: "-"

        val currentRec = activeRecommendation ?: RestMissionCatalog.pick(snap.posture, isStrong = false)
        statusText.text = "현재 감지: [$postureName | P:${pitchStr} R:${rollStr} L:${luxStr}]\n추천 미션: [${currentRec.category}] ${currentRec.title}"

        val reward = com.example.itv.infrastructure.reward.UserRewardStore.getReward(this)
        rewardText.text = "성장 현황: Lv.${reward.currentLevel} (${reward.currentLevelExp}/${reward.nextLevelExpRequired} EXP) · ${reward.currentPoints} P (미션 완료: ${reward.totalCompletedMissions}회)"

        val color = when (snap.posture) {
            UserPosture.LYING_DOWN -> Color.rgb(198, 40, 40)
            UserPosture.SITTING_OR_STANDING -> Color.rgb(46, 125, 50)
            UserPosture.TABLE_OR_DESK -> Color.rgb(21, 101, 192)
            UserPosture.FACEDOWN -> Color.rgb(156, 39, 176)
            UserPosture.UNKNOWN -> Color.rgb(117, 117, 117)
        }
        statusText.setTextColor(color)
    }

    private fun clear() {
        handler.removeCallbacksAndMessages(null)
        handler.post(sensorUpdater)
        current?.let { factory.detachPreview(it) }
        current = null
        selected = false
    }

    private fun show(mode: String) {
        if (!::factory.isInitialized || host.width == 0) return
        clear()

        val snap = tracker.snapshot.value
        val isStrong = (mode == "offer_strong")
        val intensity = if (isStrong) CharacterIntensity.STRONG else CharacterIntensity.NORMAL
        val rec = RestMissionCatalog.pick(snap.posture, isStrong)
        activeRecommendation = rec

        val scene = when (mode) {
            "retry" -> factory.retryOffer(
                onChoice = { title, _ -> choose(title, true) },
                intensityOverride = intensity,
                recommendation = rec
            )
            "complete" -> factory.completion(recommendation = rec) {
                updateStatusDisplay()
            }
            else -> {
                val reasons = InterventionReasonResolver.resolveReasons(
                    isJev = false,
                    jevContext = null,
                    continuousUsedMs = 1_800_000L,
                    ambient = snap,
                    shortformAppName = "숏폼 시청"
                )
                factory.offer(
                    onChoice = { title, _ -> choose(title, false) },
                    reasons = reasons,
                    intensityOverride = intensity,
                    recommendation = rec
                )
            }
        }
        current = scene.first
        factory.attachPreview(scene.first, scene.second)
        updateStatusDisplay()

        if (mode == "complete") {
            handler.postDelayed({ clear() }, 10_000L)
        }
    }

    private fun choose(title: String, retry: Boolean) {
        val root = current ?: return
        if (selected) return
        selected = true
        factory.disableControls(root)
        factory.playReaction(root, title, recommendation = activeRecommendation) {
            if (current === root) {
                clear()
                if (title == "쉬기") {
                    handler.postDelayed({ show("retry") }, com.example.itv.domain.coaching.TestCoachingPolicy.MISSION_START_MS)
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::factory.isInitialized) clear()
        super.onDestroy()
    }
}
