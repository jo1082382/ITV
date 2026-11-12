package com.example.itv.presentation.coaching

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import com.example.itv.R
import com.example.itv.domain.coaching.CharacterIntensity
import com.example.itv.domain.coaching.CoachingTone
import com.example.itv.infrastructure.coaching.UserCoachingSettingsStore

/** Independent local windows: never installs a full-screen touch interceptor. */
internal class CoachingOverlayFactory(private val context: Context, private val previewHost: FrameLayout? = null) {
    private val wm = object {
        private fun local(p: WindowManager.LayoutParams) = FrameLayout.LayoutParams(p.width, p.height).apply {
            leftMargin = p.x; topMargin = p.y; gravity = Gravity.TOP or Gravity.LEFT
        }
        fun addView(view: View, p: WindowManager.LayoutParams) {
            val host = previewHost
            if (host == null) context.getSystemService(WindowManager::class.java).addView(view, p)
            else { host.addView(view, local(p)); view.visibility = if (view.visibility == View.INVISIBLE) View.INVISIBLE else View.VISIBLE }
        }
        fun updateViewLayout(view: View, p: WindowManager.LayoutParams) {
            val host = previewHost
            if (host == null) context.getSystemService(WindowManager::class.java).updateViewLayout(view, p)
            else { view.layoutParams = local(p) }
        }
        fun removeView(view: View) {
            val host = previewHost
            if (host == null) context.getSystemService(WindowManager::class.java).removeView(view)
            else host.removeView(view)
        }
    }
    fun attachPreview(root: View, params: WindowManager.LayoutParams) {
        check(previewHost != null)
        wm.addView(root, params); attachControls(root)
    }
    fun detachPreview(root: View) { removeControls(root); wm.removeView(root) }

    private val runFrames = intArrayOf(
        R.drawable.itv_gallop_away_00, R.drawable.itv_gallop_away_01, R.drawable.itv_gallop_away_02, R.drawable.itv_gallop_away_03,
        R.drawable.itv_gallop_away_04, R.drawable.itv_gallop_away_05, R.drawable.itv_gallop_away_06, R.drawable.itv_gallop_away_07
    )
    private val frontRunFrames = intArrayOf(
        R.drawable.itv_motion_front_run_a,
        R.drawable.itv_motion_front_run_b
    )
    private val rewardFrames = intArrayOf(
        R.drawable.itv_gallop_bone_00, R.drawable.itv_gallop_bone_01, R.drawable.itv_gallop_bone_02, R.drawable.itv_gallop_bone_03,
        R.drawable.itv_gallop_bone_04, R.drawable.itv_gallop_bone_05, R.drawable.itv_gallop_bone_06, R.drawable.itv_gallop_bone_07
    )
    private val sessions = mutableMapOf<View, Session>()
    private val handler = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private inner class Session(
        val root: View,
        val welcome: Boolean,
        val choice: ((String, String) -> Unit)?,
        var reasons: List<String> = emptyList(),
        val characterIntensity: CharacterIntensity = CharacterIntensity.NORMAL,
        val tone: CoachingTone = CoachingTone.SOFT
    ) {
        val windows = mutableListOf<View>()
        var animator: ValueAnimator? = null
        var tailWagAnimator: ValueAnimator? = null
        var autoDismissRunnable: Runnable? = null
        var screenOffReceiver: BroadcastReceiver? = null
        var hold: Runnable? = null
        var stopped = false
        var committed = false
        var onRewardDismiss: (() -> Unit)? = null
        var rewardClaimed = false
        var bubbleFollow: Runnable? = null
        var ready = false
        var retry = false
        var hoveringPen = false
        var hoverZone = ""
        var shake: ValueAnimator? = null
        var speechParams: WindowManager.LayoutParams? = null
        var reaction: Runnable? = null
        var reactionFinished: (() -> Unit)? = null
        var onReady: (() -> Unit)? = null
        var fence: ImageView? = null
        var fenceParams: WindowManager.LayoutParams? = null
        lateinit var dog: ImageView
        lateinit var dogParams: WindowManager.LayoutParams
        lateinit var speech: TextView
        lateinit var house: Rect
        lateinit var cage: Rect
        var homeX = 0; var homeY = 0
        var onInteraction: ((Boolean) -> Unit)? = null

        fun cleanup() {
            stopped = true
            onInteraction?.invoke(false)
            tailWagAnimator?.cancel()
            tailWagAnimator = null
            autoDismissRunnable?.let { handler.removeCallbacks(it) }
            autoDismissRunnable = null
            screenOffReceiver?.let {
                try { context.unregisterReceiver(it) } catch (_: Exception) {}
                screenOffReceiver = null
            }
            bubbleFollow?.let { handler.removeCallbacks(it) }
            shake?.cancel()
            reaction?.let { handler.removeCallbacks(it) }
            reactionFinished = null
            onReady = null
            animator?.cancel()
            hold?.let { handler.removeCallbacks(it) }
            windows.toList().forEach { try { wm.removeView(it) } catch (_: Exception) {} }
            windows.clear()
        }
    }

    private fun params(w: Int, h: Int, x: Int, y: Int, touch: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        this.x = x
        this.y = y
        alpha = 1f
    }

    private fun label(text: String, customTextSize: Float = 14f) = TextView(context).apply {
        this.text = text
        textSize = customTextSize
        setTypeface(null, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(18, 25, 16))
        setLineSpacing(dp(2).toFloat(), 1.15f)
        background = com.example.itv.presentation.character.PuppyBubbleDrawable()
        setPadding(dp(16), dp(10), dp(16), dp(16))
    }

    private fun root(
        welcome: Boolean,
        choice: ((String, String) -> Unit)?,
        reasons: List<String> = emptyList(),
        intensityOverride: CharacterIntensity? = null,
        toneOverride: CoachingTone? = null
    ): Pair<LinearLayout, WindowManager.LayoutParams> {
        val root = LinearLayout(context)
        val userSettings = UserCoachingSettingsStore.getInstance(context).loadSettings()
        val intensity = intensityOverride ?: userSettings.characterIntensity
        val tone = toneOverride ?: userSettings.tone
        sessions[root] = Session(root, welcome, choice, reasons, intensity, tone)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (welcome) attachControls(v) }
            override fun onViewDetachedFromWindow(v: View) { removeControls(v) }
        })
        return root to params(1, 1, 0, 0, false).apply { alpha = 0f }
    }

    fun retryOffer(onChoice: (String, String) -> Unit): Pair<LinearLayout, WindowManager.LayoutParams> =
        retryOffer(onChoice, null, null)

    fun retryOffer(
        onChoice: (String, String) -> Unit,
        intensityOverride: CharacterIntensity?,
        toneOverride: CoachingTone? = null
    ): Pair<LinearLayout, WindowManager.LayoutParams> {
        val result = root(false, onChoice, intensityOverride = intensityOverride, toneOverride = toneOverride)
        sessions[result.first]?.retry = true
        return result
    }

    fun offer(
        onChoice: (String, String) -> Unit,
        reasons: List<String> = emptyList(),
        intensityOverride: CharacterIntensity? = null,
        toneOverride: CoachingTone? = null
    ) = root(false, onChoice, reasons, intensityOverride, toneOverride)

    fun completion(onDismiss: () -> Unit): Pair<LinearLayout, WindowManager.LayoutParams> {
        val result = root(true, null)
        sessions[result.first]?.onRewardDismiss = onDismiss
        return result
    }

    fun attachControls(root: View, onReady: (() -> Unit)? = null, onInteraction: ((Boolean) -> Unit)? = null) {
        val s = sessions[root] ?: return
        if (s.windows.isNotEmpty() || s.stopped) return
        s.onReady = onReady
        s.onInteraction = onInteraction
        val metrics = context.resources.displayMetrics
        val width = previewHost?.width?.takeIf { it > 0 } ?: metrics.widthPixels
        val height = previewHost?.height?.takeIf { it > 0 } ?: (metrics.heightPixels - dp(48))

        fun add(v: View, p: WindowManager.LayoutParams) { wm.addView(v, p); s.windows.add(v) }

        try {
            if (s.welcome) {
                attachWelcomeControls(s, width, height, ::add)
                return
            }

            when (s.characterIntensity) {
                CharacterIntensity.GENTLE -> attachGentleControls(s, width, height, ::add)
                CharacterIntensity.STRONG -> attachStrongControls(s, width, height, ::add)
                CharacterIntensity.NORMAL -> attachNormalControls(s, width, height, ::add)
            }
        } catch (_: Exception) {
            s.cleanup()
            throw IllegalStateException("캐릭터 창 표시 실패")
        }
    }

    /**
     * [약하게] 모드: 화면 구석에서 천천히 걸어와서 가만히 선 뒤 말풍선을 띄우고, 6초 후 자동 퇴장
     */
    private fun attachGentleControls(s: Session, width: Int, height: Int, add: (View, WindowManager.LayoutParams) -> Unit) {
        val dogSize = minOf(dp(86), (width * 0.22f).toInt())
        s.homeX = width - dogSize - dp(14)
        s.homeY = height - dogSize - dp(60)

        s.dog = ImageView(context).apply {
            setImageResource(R.drawable.itv_motion_front)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "화면 모서리로 걸어나와 선 강아지. 탭하면 휴식을 시작합니다."
        }
        val startX = width + dp(4)
        s.dogParams = params(dogSize, dogSize, startX, s.homeY, true)
        s.dogParams.flags = s.dogParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        add(s.dog, s.dogParams)

        val gentleText = if (s.retry) "화면 끄고 쉬기로 약속했잖아요~ 눈을 위해 잠깐만 화면을 꺼봐요 🌿" else formatGentleSpeech(s.reasons, s.tone)
        s.speech = label(gentleText, customTextSize = 13f)
        s.speech.visibility = View.INVISIBLE
        val speechWidth = minOf(dp(260), width - dp(24))
        val speechHeight = dp(96)
        s.speechParams = params(
            speechWidth, speechHeight,
            width - speechWidth - dp(12), (s.homeY - speechHeight - dp(8)).coerceAtLeast(dp(12)), false
        )
        add(s.speech, s.speechParams!!)

        // 구석에서 천천히 걸어와서 가만히 서는 애니메이션
        s.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            interpolator = android.view.animation.DecelerateInterpolator(1.2f)
            addUpdateListener {
                if (s.stopped) return@addUpdateListener
                val f = it.animatedValue as Float
                s.dogParams.x = (startX + (s.homeX - startX) * f).toInt()
                val frame = ((it.currentPlayTime / 100L) % runFrames.size).toInt()
                if (f < 0.98f) {
                    s.dog.scaleX = -1f
                    s.dog.rotation = 0f
                    val sprite = runFrames[frame]
                    if (s.dog.tag != sprite) { s.dog.setImageResource(sprite); s.dog.tag = sprite }
                } else {
                    s.dog.scaleX = 1f
                    s.dog.rotation = 0f
                    s.dog.setImageResource(R.drawable.itv_motion_front)
                }
                try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                if (f >= 0.99f) {
                    s.ready = true
                    s.dog.scaleX = 1f
                    s.dog.rotation = 0f
                    s.dog.setImageResource(R.drawable.itv_motion_front)
                    s.speech.visibility = View.VISIBLE
                    s.onReady?.invoke()
                    s.onReady = null
                }
            }
            start()
        }

        // 탭 시 바로 휴식 선택
        s.dog.setOnClickListener {
            if (s.ready && !s.committed && !s.stopped) {
                s.committed = true
                val prepSec = com.example.itv.domain.coaching.TestCoachingPolicy.MISSION_START_MS / 1000L
                val restSec = com.example.itv.domain.coaching.TestCoachingPolicy.REST_MS / 1000L
                s.choice?.invoke("쉬기", "휴식 미션: ${prepSec}초 안에 화면을 끄고 ${restSec}초 이상 쉬어주세요.")
                s.cleanup()
            }
        }

        // 6초 후 무응답 시 자동 퇴장
        s.autoDismissRunnable = Runnable {
            if (!s.committed && !s.stopped) {
                s.committed = true
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 500
                    addUpdateListener { anim ->
                        if (!s.stopped) {
                            val f = anim.animatedValue as Float
                            s.dogParams.x = (s.homeX + (width - s.homeX) * f).toInt()
                            try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                        }
                    }
                    start()
                }
                handler.postDelayed({
                    s.choice?.invoke("나중에", "다음에 다시 찾아올게!")
                    s.cleanup()
                }, 550L)
            }
        }
        handler.postDelayed(s.autoDismissRunnable!!, 6000L)
    }

    /**
     * [강하게] 모드: 오른쪽 위에서부터 화면을 바라보며 서서히 달려와 화면 세로의 절반 이상을 가리고, 화면을 끄고 쉬어야만 사라짐
     */
    private fun attachStrongControls(s: Session, width: Int, height: Int, add: (View, WindowManager.LayoutParams) -> Unit) {
        val dimView = View(context).apply {
            setBackgroundColor(Color.argb(205, 10, 18, 12))
            alpha = 0f
        }
        add(dimView, params(width, height, 0, 0, false))

        // 화면 세로 길이의 절반 이상(약 54%)을 묵직하게 차지하는 초대형 크기
        val targetDogSize = maxOf((width * 0.95f).toInt(), (height * 0.54f).toInt())
        s.homeX = (width - targetDogSize) / 2
        s.homeY = height - targetDogSize - dp(8)

        // 오른쪽 위(Top-Right)에서 작게 시작 (원근감으로 멀리서부터 다가옴)
        val startSize = (targetDogSize * 0.16f).toInt()
        val startX = width - startSize - dp(18)
        val startY = dp(32)

        s.dog = ImageView(context).apply {
            setImageResource(R.drawable.itv_motion_front)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "화면을 바라보며 달려와 앞을 막아선 강아지. 화면을 끄고 쉬면 사라집니다."
        }
        s.dogParams = params(startSize, startSize, startX, startY, true)
        s.dogParams.flags = s.dogParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        add(s.dog, s.dogParams)

        // 말풍선 문구: 화면을 끄고 쉬면 사라진다는 멘트 포함!
        val strongMessage = if (s.retry) {
            "쉬기로 해놓고 왜 안 쉬어?! 🐾\n화면을 끄고 쉬어야 사라져요!"
        } else {
            when (s.tone) {
                CoachingTone.SOFT -> "잠깐 멈추고 쉬어가요 🌿\n화면을 끄고 쉬면 사라져요!"
                CoachingTone.PLAYFUL -> "도파민 탈출 대작전! 🐾\n화면을 끄고 쉬면 사라져요!"
                CoachingTone.FIRM -> "🛑 숏폼 과몰입 강력 차단!\n화면을 끄고 쉬면 사라져요!"
            }
        }
        s.speech = label(strongMessage, customTextSize = 14f)
        s.speech.visibility = View.INVISIBLE
        val speechWidth = minOf(dp(320), width - dp(32))
        val speechHeight = dp(116)
        val speechY = (s.homeY - speechHeight - dp(14)).coerceAtLeast(dp(36))
        s.speechParams = params(
            speechWidth, speechHeight,
            (width - speechWidth) / 2, speechY, false
        )
        add(s.speech, s.speechParams!!)

        // 2.8초 동안 프레임을 차분하게 낮추고(220ms), 서서히 가까이 다가오며 커지는 애니메이션
        s.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2800L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                if (s.stopped) return@addUpdateListener
                val f = it.animatedValue as Float
                val curSize = (startSize + (targetDogSize - startSize) * f).toInt()
                s.dogParams.width = curSize
                s.dogParams.height = curSize
                s.dogParams.x = (startX + (s.homeX - startX) * f).toInt()
                s.dogParams.y = (startY + (s.homeY - startY) * f).toInt()

                // 배경 딤 뷰도 거리에 맞춰 점진적으로 어두워짐
                dimView.alpha = (f * 1.05f).coerceIn(0f, 1f)

                // 화면을 정면으로 바라보고 달리는 프레임: 너무 빠르지 않게 220ms 간격으로 교체
                s.dog.scaleX = 1f
                s.dog.rotation = 0f
                val sprite = if (f >= 0.98f) {
                    R.drawable.itv_motion_front
                } else {
                    frontRunFrames[((it.currentPlayTime / 220L) % frontRunFrames.size).toInt()]
                }
                if (s.dog.tag != sprite) { s.dog.setImageResource(sprite); s.dog.tag = sprite }

                try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                if (f >= 0.99f) {
                    s.ready = true
                    s.dog.setImageResource(R.drawable.itv_motion_front)
                    s.speech.visibility = View.VISIBLE
                    s.onReady?.invoke()
                    s.onReady = null
                }
            }
            start()
        }

        // 화면 꺼짐 감지: ACTION_SCREEN_OFF가 들어와야지만 사라짐!
        val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    if (!s.stopped && !s.committed) {
                        s.committed = true
                        val restSec = com.example.itv.domain.coaching.TestCoachingPolicy.REST_MS / 1000L
                        s.choice?.invoke("쉬기", "화면을 꺼서 휴식을 시작했습니다! ${restSec}초 이상 쉬어주세요.")
                        s.cleanup()
                    }
                }
            }
        }
        s.screenOffReceiver = screenOffReceiver
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        context.registerReceiver(screenOffReceiver, filter)
    }

    /**
     * [보통] 모드: 기존 인터랙션(집, 펜스, 드래그) 유지 + 멈춰서 꼬리 흔들기(tail wagging idle) 추가
     */
    private fun attachNormalControls(s: Session, width: Int, height: Int, add: (View, WindowManager.LayoutParams) -> Unit) {
        val dogSize = minOf(dp(112), width / 3)
        val houseW = minOf(dp(160), width / 2)
        val houseH = houseW * 360 / 420
        val hx = (width - houseW) / 2
        val hy = height / 5
        s.house = Rect(hx, hy, hx + houseW, hy + houseH)
        val right = kotlin.random.Random.nextBoolean()
        val cageSize = minOf(dp(164), width / 2)
        val cageX = if (right) dp(14) else width - cageSize - dp(14)
        s.cage = Rect(cageX, height * 2 / 3, cageX + cageSize, height * 2 / 3 + cageSize)

        val houseView = ImageView(context).apply {
            setImageResource(R.drawable.itv_companion_house_back)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(246, 229, 199))
            alpha = 1f
            imageAlpha = 255
            visibility = View.INVISIBLE
        }
        add(houseView, params(houseW, houseH, hx, hy, true).apply { format = PixelFormat.OPAQUE; alpha = 1f })

        val normalText = if (s.retry) "화면 끄고 쉬기로 했잖아!\n왜 안 쉬어?! 🐾" else formatReasonsSpeech(s.reasons, s.tone)
        s.speech = label(normalText)
        s.speech.visibility = View.INVISIBLE
        s.speechParams = params(minOf(dp(300), width - dp(20)), dp(160), (width - minOf(dp(300), width - dp(20))) / 2, dp(8), false)
        add(s.speech, s.speechParams!!)

        val pen = ImageView(context).apply {
            setImageResource(R.drawable.itv_pen_fence)
            contentDescription = "닫기: 철창 펜스"
            visibility = View.INVISIBLE
        }
        val penParams = params(cageSize, cageSize, s.cage.left, s.cage.top, false)
        s.fence = pen
        s.fenceParams = penParams
        add(pen, penParams)

        s.dog = ImageView(context).apply {
            setImageResource(R.drawable.itv_motion_front)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "강아지. 길게 눌러 집으로 옮기면 쉬기, 화면 가장자리는 나중에, 닫기 구역은 닫기."
        }
        s.homeX = width / 2 - dogSize / 2
        s.homeY = hy + houseH - dogSize / 3
        s.dogParams = params(
            dogSize, dogSize,
            if (right) width - dogSize / 3 else -dogSize * 2 / 3,
            height - dogSize - dp(155),
            true
        )
        s.dogParams.flags = s.dogParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        add(s.dog, s.dogParams)

        val sy = s.dogParams.y

        s.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 7400
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                if (s.stopped) return@addUpdateListener
                val raw = it.animatedValue as Float
                val peeking = raw < (5000f / 7400f)
                val f = ((raw - (5000f / 7400f)) / (2400f / 7400f)).coerceIn(0f, 1f)
                if (peeking) {
                    val peek = raw / (5000f / 7400f)
                    s.dog.pivotX = if (right) dogSize.toFloat() else 0f
                    s.dog.pivotY = dogSize * 0.85f
                    s.dog.rotation = (if (right) -1f else 1f) * (8f + 24f * peek)
                    s.dogParams.x = if (right) width - (dogSize * (0.1f + 0.8f * peek)).toInt() else (-dogSize * (0.9f - 0.8f * peek)).toInt()
                    s.dogParams.y = sy
                    s.dog.setImageResource(R.drawable.itv_motion_front)
                } else {
                    s.dog.rotation = 0f
                    s.dog.pivotX = dogSize / 2f
                    s.dog.pivotY = dogSize / 2f
                    houseView.visibility = View.VISIBLE
                    s.fence?.visibility = View.VISIBLE
                    val runStart = if (right) width - dogSize * 7 / 10 else -dogSize * 3 / 10
                    s.dogParams.x = (runStart + (s.homeX - runStart) * f).toInt()
                    s.dogParams.y = (sy + (s.homeY - sy) * f).toInt()
                    val frame = ((it.currentPlayTime / 110L) % runFrames.size).toInt()
                    val sprite = if (f >= 0.99f) R.drawable.itv_motion_turn else runFrames[frame]
                    if (s.dog.tag != sprite) { s.dog.setImageResource(sprite); s.dog.tag = sprite }
                    s.dog.scaleY = 1f
                }
                s.dog.scaleX = if (!peeking && f < 0.99f && right) -1f else 1f

                try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                if (raw >= 0.999f) {
                    val firstReady = !s.ready
                    s.ready = true
                    s.speech.visibility = View.VISIBLE
                    if (firstReady) {
                        val cb = s.onReady
                        s.onReady = null
                        cb?.invoke()
                    }
                    s.speech.text = formatReasonsSpeech(s.reasons, s.tone)
                    s.speechParams?.let { sp ->
                        sp.y = (s.dogParams.y + s.dogParams.height + dp(8)).coerceAtMost(height - sp.height)
                        try { wm.updateViewLayout(s.speech, sp) } catch (_: Exception) {}
                    }
                    // 멈춰서 꼬리 흔드는 모션 시작! (드래그 전까지)
                    startTailWagging(s)
                }
            }
            start()
        }

        if (s.retry) {
            s.animator?.cancel()
            houseView.visibility = View.VISIBLE
            s.fence?.visibility = View.VISIBLE
            s.ready = true
            val cb = s.onReady
            s.onReady = null
            cb?.invoke()
            s.dogParams.x = s.homeX
            s.dogParams.y = s.homeY
            s.dog.rotation = 0f
            s.dog.scaleX = 1f
            s.dog.scaleY = 1f
            s.dog.setImageResource(R.drawable.itv_motion_angry)
            wm.updateViewLayout(s.dog, s.dogParams)
            s.speech.visibility = View.VISIBLE
            s.speech.text = "쉬러 가기로 했잖아!"
            s.speechParams?.let { sp ->
                sp.x = (s.dogParams.x + s.dogParams.width / 2 - sp.width / 2).coerceIn(0, (width - sp.width).coerceAtLeast(0))
                sp.y = (s.dogParams.y + s.dogParams.height + dp(8)).coerceAtMost((height - sp.height).coerceAtLeast(0))
                (s.speech.background as? com.example.itv.presentation.character.PuppyBubbleDrawable)?.pointToward((s.dogParams.x + s.dogParams.width / 2 - sp.x).toFloat() / sp.width, false)
                wm.updateViewLayout(s.speech, sp)
            }
            startTailWagging(s)
        }

        installDrag(s, width, height, dogSize)
        startBubbleFollow(s, width, height)
    }

    private fun startTailWagging(s: Session) {
        if (s.stopped || s.committed) return
        s.tailWagAnimator?.cancel()
        // 몸 전체가 크게 돌지 않고 꼬리/엉덩이 쪽만 살랑이도록 피벗을 우측 꼬리 쪽에 집중하고 각도를 미세화
        s.dog.pivotX = s.dogParams.width * 0.72f
        s.dog.pivotY = s.dogParams.height * 0.75f
        s.tailWagAnimator = ValueAnimator.ofFloat(-1.8f, 1.8f).apply {
            duration = 260
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                if (!s.stopped && !s.committed) {
                    s.dog.rotation = anim.animatedValue as Float
                }
            }
            start()
        }
    }

    private fun attachWelcomeControls(s: Session, width: Int, height: Int, add: (View, WindowManager.LayoutParams) -> Unit) {
        val dogSize = minOf(dp(112), width / 3)
        val houseW = minOf(dp(160), width / 2)
        val houseH = houseW * 360 / 420
        val hx = (width - houseW) / 2
        val hy = height / 5
        s.house = Rect(hx, hy, hx + houseW, hy + houseH)

        val houseView = ImageView(context).apply {
            setImageResource(R.drawable.itv_companion_house_back)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(246, 229, 199))
            alpha = 1f
            imageAlpha = 255
            visibility = View.VISIBLE
        }
        add(houseView, params(houseW, houseH, hx, hy, true).apply { format = PixelFormat.OPAQUE; alpha = 1f })
        s.speech = label("잘 쉬었어! 반가워!")
        s.speech.visibility = View.INVISIBLE
        s.speechParams = params(minOf(dp(300), width - dp(20)), dp(160), (width - minOf(dp(300), width - dp(20))) / 2, dp(8), false)
        add(s.speech, s.speechParams!!)

        s.dog = ImageView(context).apply {
            setImageResource(R.drawable.itv_motion_front)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        s.homeX = width / 2 - dogSize / 2
        s.homeY = minOf(hy + houseH + dp(65), height - dogSize - dp(30))
        s.dogParams = params(dogSize, dogSize, s.homeX, hy + houseH - dogSize, true)
        s.dogParams.flags = s.dogParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        add(s.dog, s.dogParams)

        val sx = s.dogParams.x
        val sy = s.dogParams.y
        s.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                if (s.stopped) return@addUpdateListener
                val raw = it.animatedValue as Float
                s.dog.rotation = 0f
                s.dog.pivotX = dogSize / 2f
                s.dog.pivotY = dogSize / 2f
                s.dogParams.x = (sx + (s.homeX - sx) * raw).toInt()
                s.dogParams.y = (sy + (s.homeY - sy) * raw).toInt()
                val frame = ((it.currentPlayTime / 70L) % rewardFrames.size).toInt()
                val sprite = if (raw >= 0.99f) R.drawable.itv_motion_bone else rewardFrames[frame]
                if (s.dog.tag != sprite) { s.dog.setImageResource(sprite); s.dog.tag = sprite }
                s.dog.scaleY = 0.78f + 0.22f * raw
                s.dog.scaleX = 0.78f + 0.22f * raw
                try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                if (raw >= 0.999f) {
                    s.ready = true
                    s.speech.visibility = View.VISIBLE
                    s.speech.text = "잘 쉬었어! 간식을 눌러 보상을 받아 줘!"
                }
            }
            start()
        }

        s.dog.setOnClickListener {
            if (s.ready && !s.rewardClaimed && !s.stopped) {
                s.rewardClaimed = true
                s.dog.setImageResource(R.drawable.itv_motion_front)
                s.speech.text = "보상을 받았어! 잘 쉬어 줘서 고마워!"
                s.reaction = Runnable { if (!s.stopped) s.onRewardDismiss?.invoke() }
                handler.postDelayed(s.reaction!!, 1000L)
            }
        }
        startBubbleFollow(s, width, height)
    }

    private fun stopBubbleFollow(s: Session) {
        s.bubbleFollow?.let { handler.removeCallbacks(it) }
        s.bubbleFollow = null
    }

    private fun formatGentleSpeech(reasons: List<String>, tone: CoachingTone = CoachingTone.SOFT): String {
        val firstReason = reasons.firstOrNull()
        if (!firstReason.isNullOrBlank()) {
            return "$firstReason 잠깐 눈을 쉬어가요 🌿"
        }
        return when (tone) {
            CoachingTone.SOFT -> "눈이 조금 피로하지 않으세요? 잠깐 쉬어가요~ 🌿"
            CoachingTone.PLAYFUL -> "벌써 숏폼 여러 편째! 저랑 기지개 한번 켜요! 🐾"
            CoachingTone.FIRM -> "목표 시청 시간이에요. 지금 잠깐 쉬어주세요! 🛑"
        }
    }

    private fun formatReasonsSpeech(reasons: List<String>, tone: CoachingTone = CoachingTone.SOFT): String {
        val items = reasons.take(3)
        val header = when (tone) {
            CoachingTone.SOFT -> "잠깐 쉬어가요 🌿\n눈이 많이 피로할 수 있어요!"
            CoachingTone.PLAYFUL -> "멍멍! 잠깐만! 🐾\n벌써 영상을 이만큼 봤어요!"
            CoachingTone.FIRM -> "🛑 시청 시간 초과!\n지금 바로 멈추고 쉬어야 해요!"
        }
        if (items.isEmpty()) {
            return "$header\n• 영상을 오래 보고 있어요.\n• 잠깐 화면을 쉬어주세요!"
        }
        return "$header\n" + items.joinToString("\n") { "• $it" }
    }

    fun feedback(onSubmit: (Int?) -> Unit): Pair<LinearLayout, WindowManager.LayoutParams> {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
            background = rounded(Color.rgb(244, 255, 244), Color.rgb(144, 182, 151))
        }
        root.addView(TextView(context).apply { text = "이번 개입은 적절했나요?"; textSize = 18f; setTextColor(Color.rgb(36, 61, 48)) })
        root.addView(TextView(context).apply { text = "0점 매우 부적절 · 5점 보통 · 10점 매우 적절"; textSize = 12f })
        val selected = TextView(context).apply { text = "점수를 선택해 주세요." }
        var score: Int? = null
        val submit = Button(context).apply { text = "평가 제출"; isEnabled = false; setOnClickListener { score?.let(onSubmit) } }
        val slider = SeekBar(context).apply { max = 10; progress = 5; contentDescription = "개입 적절성 점수 0부터 10" }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) { score = value; selected.text = "선택: ${value}점"; submit.isEnabled = true }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        root.addView(slider, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(selected); root.addView(submit)
        root.addView(Button(context).apply { text = "건너뛰기"; setOnClickListener { onSubmit(null) } })
        val params = WindowManager.LayoutParams(
            minOf(dp(360), context.resources.displayMetrics.widthPixels - dp(28)),
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(72)
        }
        return root to params
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke)
    }

    private fun startBubbleFollow(s: Session, width: Int, height: Int) {
        stopBubbleFollow(s)
        val follow = object : Runnable {
            override fun run() {
                if (s.stopped) return
                s.speechParams?.let { sp ->
                    val cx = s.dogParams.x + s.dogParams.width / 2
                    val above = s.dogParams.y >= sp.height + dp(12)
                    val tx = (cx - sp.width / 2).coerceIn(0, (width - sp.width).coerceAtLeast(0))
                    val ty = (if (above) s.dogParams.y - sp.height - dp(4) else s.dogParams.y + s.dogParams.height + dp(4)).coerceIn(0, (height - sp.height).coerceAtLeast(0))
                    sp.x += ((tx - sp.x) * 0.38f).toInt().let { if (it == 0) tx - sp.x else it }
                    sp.y += ((ty - sp.y) * 0.38f).toInt().let { if (it == 0) ty - sp.y else it }
                    (s.speech.background as? com.example.itv.presentation.character.PuppyBubbleDrawable)?.pointToward((cx - sp.x).toFloat() / sp.width, above)
                    if (s.speech.visibility == View.VISIBLE) try { wm.updateViewLayout(s.speech, sp) } catch (_: Exception) {}
                }
                handler.postDelayed(this, 16L)
            }
        }
        s.bubbleFollow = follow; handler.post(follow)
    }

    private fun installDrag(s: Session, width: Int, height: Int, size: Int) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        s.dog.setOnTouchListener { _, event ->
            if (s.committed || s.stopped || !s.ready) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    s.tailWagAnimator?.cancel()
                    s.tailWagAnimator = null
                    s.dog.rotation = 0f
                    s.onInteraction?.invoke(true)
                    downX = event.rawX; downY = event.rawY; startX = s.dogParams.x; startY = s.dogParams.y; dragging = false
                    s.hold = Runnable {
                        if (!s.stopped && !s.committed) {
                            dragging = true
                            s.onInteraction?.invoke(true)
                            startBubbleFollow(s, width, height)
                            startY -= dp(50)
                            s.dogParams.y = startY.coerceAtLeast(0)
                            try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                            s.animator?.cancel()
                            s.dog.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            s.speech.text = "집: 쉬기\n좌우·아래 가장자리: 나중에\n철창: 닫기"
                        }
                    }
                    handler.postDelayed(s.hold!!, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) s.hold?.let { handler.removeCallbacks(it) }
                    if (dragging) {
                        s.onInteraction?.invoke(true)
                        s.dogParams.x = (startX + dx).toInt().coerceIn(0, (width - size).coerceAtLeast(0))
                        s.dogParams.y = (startY + dy).toInt().coerceIn(0, (height - size).coerceAtLeast(0))
                        val over = s.cage.contains(s.dogParams.x + size / 2, s.dogParams.y + size / 2)
                        val home = s.house.contains(s.dogParams.x + size / 2, s.dogParams.y + size / 2)
                        val edge = s.dogParams.x <= dp(48) || s.dogParams.x + size >= width - dp(48) || s.dogParams.y + size >= height - dp(48)
                        val zone = if (over) "pen" else if (home) "home" else if (edge) "edge" else "none"
                        if (zone != s.hoverZone) {
                            s.hoverZone = zone
                            if (zone == "home") {
                                s.shake?.cancel(); s.dog.rotation = 0f
                                com.example.itv.presentation.character.CompanionHover.sleepy(s.dog)
                                s.speech.text = "Zzz....졸려..."
                            } else if (zone == "edge") {
                                s.shake?.cancel(); s.dog.rotation = 0f; s.dog.setImageResource(R.drawable.itv_motion_sad)
                                s.speech.text = "진짜 안 쉴거야?"
                            } else if (zone == "none") {
                                s.dog.setImageResource(if (s.retry) R.drawable.itv_motion_angry else R.drawable.itv_motion_turn)
                                s.speech.text = if (s.retry) "화면 끄고 쉬기로 했잖아!\n왜 안 쉬어?! 🐾" else "나랑 같이 쉬자!"
                            }
                        }
                        if (over != s.hoveringPen) {
                            s.hoveringPen = over; s.shake?.cancel(); s.dog.rotation = 0f
                            if (over) {
                                s.dog.setImageResource(R.drawable.itv_motion_sad)
                                s.speech.text = "거긴 가기 싫어…"
                                s.shake = ValueAnimator.ofFloat(-6f, 6f, -6f, 6f, 0f).apply {
                                    duration = 1000; repeatCount = 2
                                    addUpdateListener { if (!s.stopped) s.dog.rotation = it.animatedValue as Float }
                                    start()
                                }
                            } else {
                                if (s.hoverZone == "home") {
                                    com.example.itv.presentation.character.CompanionHover.sleepy(s.dog); s.speech.text = "Zzz....졸려..."
                                } else if (s.hoverZone == "edge") {
                                    s.dog.setImageResource(R.drawable.itv_motion_sad); s.speech.text = "진짜 안 쉴거야?"
                                } else {
                                    s.dog.setImageResource(if (s.retry) R.drawable.itv_motion_angry else R.drawable.itv_motion_turn)
                                    s.speech.text = if (s.retry) "쉬러 가기로 했잖아!" else "나랑 같이 쉬자!"
                                }
                            }
                        }
                        try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    s.hold?.let { handler.removeCallbacks(it) }
                    s.shake?.cancel(); s.dog.rotation = 0f; s.hoveringPen = false
                    s.onInteraction?.invoke(false)
                    if (dragging) {
                        val cx = s.dogParams.x + size / 2; val cy = s.dogParams.y + size / 2; val edge = dp(48)
                        val title = when {
                            s.cage.contains(cx, cy) -> "닫기"
                            s.house.contains(cx, cy) -> "쉬기"
                            (s.dogParams.x <= edge || s.dogParams.x + size >= width - edge || s.dogParams.y + size >= height - edge) -> "나중에"
                            else -> null
                        }
                        val prepSec = com.example.itv.domain.coaching.TestCoachingPolicy.MISSION_START_MS / 1000L
                        val restSec = com.example.itv.domain.coaching.TestCoachingPolicy.REST_MS / 1000L
                        if (title != null) {
                            s.committed = true
                            s.choice?.invoke(
                                title,
                                when (title) {
                                    "쉬기" -> "휴식 미션: ${prepSec}초 안에 화면을 끄고 연속 ${restSec}초 쉬어 주세요."
                                    "나중에" -> "알겠어. 다음에 다시 찾아올게!"
                                    else -> "닫았어요."
                                }
                            )
                        } else {
                            s.dogParams.x = s.homeX; s.dogParams.y = s.homeY
                            try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                            s.speech.text = "목표 영역에 놓아 주세요."
                            startTailWagging(s)
                        }
                    } else {
                        startTailWagging(s)
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    s.hold?.let { handler.removeCallbacks(it) }
                    dragging = false
                    s.onInteraction?.invoke(false)
                    s.dogParams.x = s.homeX; s.dogParams.y = s.homeY
                    try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                    startTailWagging(s)
                }
            }
            true
        }
    }

    fun playReaction(
        root: View,
        title: String,
        reminderSeconds: Long = com.example.itv.domain.coaching.TestCoachingPolicy.REMINDER_MS / 1000L,
        prepareSeconds: Long = com.example.itv.domain.coaching.TestCoachingPolicy.MISSION_START_MS / 1000L,
        restSeconds: Long = com.example.itv.domain.coaching.TestCoachingPolicy.REST_MS / 1000L,
        finished: () -> Unit
    ) {
        val s = sessions[root]
        if (s == null || s.stopped) { finished(); return }
        s.committed = true
        s.tailWagAnimator?.cancel()
        s.tailWagAnimator = null
        s.shake?.cancel()
        s.dog.rotation = 0f
        s.animator?.cancel()
        s.hold?.let { handler.removeCallbacks(it) }
        s.reactionFinished = finished
        when (title) {
            "쉬기" -> {
                s.dogParams.x = s.house.centerX() - s.dogParams.width / 2
                s.dogParams.y = s.house.bottom - s.dogParams.height * 2 / 3
                s.dog.setImageResource(R.drawable.itv_motion_sleep)
                s.speech.text = if (s.retry) "이번엔 진짜지?\n창이 사라지면 ${prepareSeconds}초 안에 끄고\n${restSeconds}초 이상 쉬자!" else "Zzz… 이 창이 사라지면 ${prepareSeconds}초 안에\n폰을 끄고 ${restSeconds}초 이상 같이 쉬자!"
                s.animator = ValueAnimator.ofFloat(1f, 1.035f, 1f, 1.035f, 1f).apply {
                    duration = 2700
                    addUpdateListener { if (!s.stopped) { s.dog.scaleY = it.animatedValue as Float } }
                    start()
                }
            }
            "나중에" -> {
                s.dog.setImageResource(R.drawable.itv_motion_wave)
                s.speech.text = if (reminderSeconds % 60L == 0L) "${reminderSeconds / 60L}분 뒤에 다시 보자!" else "${reminderSeconds}초 뒤에 다시 보자!"
            }
            else -> {
                s.dogParams.x = s.cage.centerX() - s.dogParams.width / 2
                s.dogParams.y = s.cage.bottom - s.dogParams.height - dp(8)
                s.dog.setImageResource(R.drawable.itv_motion_sad)
                s.speech.text = "다음에 보자...."
                val fenceView = s.fence
                val fenceLayout = s.fenceParams
                if (fenceView != null && fenceLayout != null) {
                    try {
                        wm.removeView(fenceView)
                        wm.addView(fenceView, fenceLayout)
                    } catch (_: Exception) {}
                }
            }
        }
        s.speechParams?.let { sp ->
            sp.y = (s.dogParams.y + s.dogParams.height + dp(6)).coerceAtMost(context.resources.displayMetrics.heightPixels - sp.height - dp(40))
            try { wm.updateViewLayout(s.speech, sp) } catch (_: Exception) {}
        }
        try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
        s.reaction = Runnable {
            if (!s.stopped) {
                val callback = s.reactionFinished
                s.reactionFinished = null
                callback?.invoke()
            }
        }
        handler.postDelayed(s.reaction!!, 2800L)
    }

    fun disableControls(root: View) { sessions[root]?.committed = true }
    fun removeControls(root: View) { sessions.remove(root)?.cleanup() }
}
