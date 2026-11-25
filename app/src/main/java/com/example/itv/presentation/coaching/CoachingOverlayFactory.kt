package com.example.itv.presentation.coaching

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreakConfig
import android.graphics.text.LineBreaker
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.*
import android.widget.*
import com.example.itv.R
import com.example.itv.domain.coaching.CharacterIntensity
import com.example.itv.domain.coaching.CoachingTone
import com.example.itv.domain.coaching.CoachingSpeechCatalog
import com.example.itv.domain.coaching.InterventionBubbleFormatter
import com.example.itv.domain.coaching.OfferBubbleContent
import com.example.itv.domain.coaching.ReasonBadge
import com.example.itv.domain.coaching.SpeechSituation
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
    private val rewardFrames = intArrayOf(
        R.drawable.itv_gallop_bone_00, R.drawable.itv_gallop_bone_01, R.drawable.itv_gallop_bone_02, R.drawable.itv_gallop_bone_03,
        R.drawable.itv_gallop_bone_04, R.drawable.itv_gallop_bone_05, R.drawable.itv_gallop_bone_06, R.drawable.itv_gallop_bone_07
    )
    private val frontGallopFrames = intArrayOf(
        R.drawable.itv_gallop_front_01, R.drawable.itv_gallop_front_02, R.drawable.itv_gallop_front_03,
        R.drawable.itv_gallop_front_04, R.drawable.itv_gallop_front_05, R.drawable.itv_gallop_front_06
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
        var completionRecommendation: com.example.itv.domain.coaching.RestRecommendation? = null
        var recommendation: com.example.itv.domain.coaching.RestRecommendation? = null
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
        lateinit var dog: EquippedDogView
        lateinit var dogParams: WindowManager.LayoutParams
        lateinit var speech: PuppySpeechBubbleView
        var house = Rect()
        var cage = Rect()
        var homeX = 0; var homeY = 0
        var onInteraction: ((Boolean) -> Unit)? = null

        var remainingRestSeconds: Long? = null
        var prepareSeconds: Long = 10L
        var countdownTimerRunnable: Runnable? = null

        fun cleanup() {
            stopped = true
            com.example.itv.infrastructure.media.MediaPauseController.release(context)
            onInteraction?.invoke(false)
            countdownTimerRunnable?.let { handler.removeCallbacks(it) }
            countdownTimerRunnable = null
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

    /**
     * 1번(핵심 사유 칩 배지) + 2번(15자 이내 초단문 헤드라인 · 어절 단위 줄바꿈 · 연한 보조 안내)
     * 조합으로 렌더링되는 강아지 전용 말풍선 뷰.
     *
     * 단순 텍스트([text] 대입)일 때는 칩/보조줄을 숨기고 어절 단위 줄바꿈 텍스트만 표시하므로
     * 드래그 호버, 인사, 보상, 재시도 등 기존 모든 인터랙션과 100% 호환됩니다.
     */
    private inner class PuppySpeechBubbleView(ctx: Context) : LinearLayout(ctx) {
        private val badgeContainer: LinearLayout
        private val headlineView: TextView
        private val sublineView: TextView

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = com.example.itv.presentation.character.PuppyBubbleDrawable()
            setPadding(dp(18), dp(14), dp(18), dp(26))

            badgeContainer = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            addView(
                badgeContainer,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(7)
                }
            )

            headlineView = TextView(ctx).apply {
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(24, 34, 22))
                setLineSpacing(dp(2).toFloat(), 1.15f)
                applyKoreanPhraseBreak(this)
            }
            addView(headlineView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            sublineView = TextView(ctx).apply {
                textSize = 11.5f
                setTypeface(null, Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(92, 108, 95))
                setLineSpacing(dp(1).toFloat(), 1.12f)
                visibility = View.GONE
                applyKoreanPhraseBreak(this)
            }
            addView(
                sublineView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                }
            )
        }

        var text: CharSequence
            get() = headlineView.text
            set(value) {
                badgeContainer.visibility = View.GONE
                badgeContainer.removeAllViews()
                sublineView.visibility = View.GONE
                headlineView.text = value
            }

        fun setHeadlineTextSize(sp: Float) {
            headlineView.textSize = sp
        }

        fun setOfferContent(content: OfferBubbleContent) {
            badgeContainer.removeAllViews()
            if (content.badges.isNotEmpty()) {
                val totalChars = content.badges.sumOf { it.text.length }
                val stackVertically = content.badges.size > 1 && totalChars >= 21
                badgeContainer.orientation = if (stackVertically) VERTICAL else HORIZONTAL
                content.badges.forEachIndexed { idx, badge ->
                    val chip = createReasonBadgeView(badge)
                    val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        if (stackVertically) {
                            if (idx > 0) topMargin = dp(4)
                        } else {
                            if (idx > 0) marginStart = dp(6)
                        }
                    }
                    badgeContainer.addView(chip, lp)
                }
                badgeContainer.visibility = View.VISIBLE
            } else {
                badgeContainer.visibility = View.GONE
            }

            headlineView.text = content.headline

            if (!content.subline.isNullOrBlank()) {
                sublineView.text = content.subline
                sublineView.visibility = View.VISIBLE
            } else {
                sublineView.visibility = View.GONE
            }
        }

        private fun createReasonBadgeView(badge: ReasonBadge): TextView {
            val bgColor = if (badge.isAlert) Color.rgb(255, 240, 238) else Color.rgb(232, 246, 236)
            val strokeColor = if (badge.isAlert) Color.rgb(245, 183, 177) else Color.rgb(165, 214, 167)
            val baseTextColor = if (badge.isAlert) Color.rgb(180, 35, 24) else Color.rgb(27, 94, 32)
            val numAccentColor = if (badge.isAlert) Color.rgb(145, 20, 16) else Color.rgb(15, 81, 50)

            return TextView(context).apply {
                text = highlightNumbers(badge.text, numAccentColor)
                textSize = 11.5f
                setTextColor(baseTextColor)
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(3), dp(9), dp(3))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(99).toFloat()
                    setColor(bgColor)
                    setStroke(dp(1), strokeColor)
                }
            }
        }

        private fun highlightNumbers(text: String, accentColor: Int): CharSequence {
            val sp = SpannableString(text)
            val numPattern = Regex("""[+\-]?\d+(?:\.\d+)?[분초편회개배%]?""")
            numPattern.findAll(text).forEach { m ->
                sp.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(ForegroundColorSpan(accentColor), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return sp
        }

        private fun applyKoreanPhraseBreak(tv: TextView) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tv.breakStrategy = LineBreaker.BREAK_STRATEGY_BALANCED
            }
            tv.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                tv.lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
            }
        }
    }

    private fun label(text: String, customTextSize: Float = 14f) = PuppySpeechBubbleView(context).apply {
        setHeadlineTextSize(customTextSize)
        this.text = text
    }

    private fun offerBubbleLabel(content: OfferBubbleContent, headlineSize: Float = 14.5f) = PuppySpeechBubbleView(context).apply {
        setHeadlineTextSize(headlineSize)
        setOfferContent(content)
    }

    private fun root(
        welcome: Boolean,
        choice: ((String, String) -> Unit)?,
        reasons: List<String> = emptyList(),
        intensityOverride: CharacterIntensity? = null,
        toneOverride: CoachingTone? = null,
        recommendation: com.example.itv.domain.coaching.RestRecommendation? = null
    ): Pair<LinearLayout, WindowManager.LayoutParams> {
        val root = LinearLayout(context)
        val userSettings = UserCoachingSettingsStore.getInstance(context).loadSettings()
        val intensity = intensityOverride ?: userSettings.characterIntensity
        val tone = toneOverride ?: userSettings.tone
        val s = Session(root, welcome, choice, reasons, intensity, tone)
        s.recommendation = recommendation
        sessions[root] = s
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
        toneOverride: CoachingTone? = null,
        recommendation: com.example.itv.domain.coaching.RestRecommendation? = null,
        remainingRestSeconds: Long? = null,
        prepareSeconds: Long = 10L
    ): Pair<LinearLayout, WindowManager.LayoutParams> {
        val result = root(false, onChoice, intensityOverride = intensityOverride, toneOverride = toneOverride, recommendation = recommendation)
        val s = sessions[result.first]
        s?.retry = true
        s?.remainingRestSeconds = remainingRestSeconds
        s?.prepareSeconds = prepareSeconds
        return result
    }

    fun offer(
        onChoice: (String, String) -> Unit,
        reasons: List<String> = emptyList(),
        intensityOverride: CharacterIntensity? = null,
        toneOverride: CoachingTone? = null,
        recommendation: com.example.itv.domain.coaching.RestRecommendation? = null
    ) = root(false, onChoice, reasons, intensityOverride, toneOverride, recommendation)

    fun completion(onDismiss: () -> Unit): Pair<LinearLayout, WindowManager.LayoutParams> =
        completion(null, onDismiss)

    fun completion(
        recommendation: com.example.itv.domain.coaching.RestRecommendation?,
        onDismiss: () -> Unit
    ): Pair<LinearLayout, WindowManager.LayoutParams> {
        val result = root(true, null)
        sessions[result.first]?.onRewardDismiss = onDismiss
        if (recommendation != null) {
            sessions[result.first]?.completionRecommendation = recommendation
        }
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

            if (s.characterIntensity == CharacterIntensity.STRONG) {
                com.example.itv.infrastructure.media.MediaPauseController.pause(context)
            }

            if (s.retry && s.characterIntensity == CharacterIntensity.STRONG) {
                // [강한 개입 전용]: 휴식을 완료하지 않고 화면을 다시 켠 경우 취침 3분기 케어 스타일의 암전 화면 유지
                attachStrongControls(s, width, height, ::add)
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

        s.dog = EquippedDogView(context).apply {
            setImageResource(R.drawable.itv_motion_front)
            contentDescription = "화면 모서리로 걸어나와 선 강아지. 강아지를 터치하면 미션이 진행됩니다."
        }
        val startX = width + dp(4)
        s.dogParams = params(dogSize, dogSize, startX, s.homeY, true)
        s.dogParams.flags = s.dogParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        add(s.dog, s.dogParams)

        s.speech = if (s.retry) {
            label(CoachingSpeechCatalog.pickSpeech(SpeechSituation.RETRY_INCOMPLETE, s.tone), customTextSize = 13.5f)
        } else {
            offerBubbleLabel(buildOfferBubble(CharacterIntensity.GENTLE, s.reasons, s.tone), headlineSize = 14f)
        }
        s.speech.visibility = View.INVISIBLE
        val speechWidth = minOf(dp(304), width - dp(20))
        val speechY = (s.homeY - dp(122)).coerceAtLeast(dp(24))
        s.speechParams = params(
            speechWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            width - speechWidth - dp(10), speechY, false
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
    /**
     * [강하게] 모드: 완료 시의 강아지처럼 중앙 위쪽에서 부드럽게 달려와 화면 세로의 절반 이상을 가리고, 화면을 끄고 쉬어야만 사라짐
     */
    private fun attachStrongControls(s: Session, width: Int, height: Int, add: (View, WindowManager.LayoutParams) -> Unit) {
        val dimView = View(context).apply {
            setBackgroundColor(Color.argb(215, 10, 18, 12))
            alpha = 0f
        }
        add(dimView, params(width, height, 0, 0, true))

        // 화면 세로 길이의 절반 이상(약 54%)을 묵직하게 차지하는 초대형 크기
        val targetDogSize = maxOf((width * 0.95f).toInt(), (height * 0.54f).toInt())
        s.homeX = (width - targetDogSize) / 2
        s.homeY = height - targetDogSize - dp(8)

        if (s.retry) {
            // 정해진 시간 안에 다시 킨 경우:
            // 달려오기보단, 화면 아래쪽에서 팔을 벌린 채(강한 개입의 마지막 자세: itv_gallop_front_00) 점프하듯 위로 튀어오름!
            s.dog = EquippedDogView(context).apply {
                setImageResource(R.drawable.itv_gallop_front_00)
                contentDescription = "아직 시간 안 됐어요! 스마트폰을 엎어두면 화면이 꺼집니다."
            }
            val startY = height
            s.dogParams = params(targetDogSize, targetDogSize, s.homeX, startY, true)
            s.dogParams.flags = s.dogParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            add(s.dog, s.dogParams)

            val topHudWidth = minOf(dp(300), width - dp(32))
            val cardX = (width - topHudWidth) / 2
            val cardY = dp(24)

            val remSec = s.remainingRestSeconds?.takeIf { it > 0L } ?: 20L
            val prepSec = s.prepareSeconds
            val targetTimeCal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.SECOND, (prepSec + remSec).toInt())
                if (get(java.util.Calendar.SECOND) > 0) {
                    add(java.util.Calendar.MINUTE, 1)
                    set(java.util.Calendar.SECOND, 0)
                }
            }
            val targetHour = targetTimeCal.get(java.util.Calendar.HOUR_OF_DAY)
            val targetMinute = targetTimeCal.get(java.util.Calendar.MINUTE)
            val targetTimeStr = String.format(java.util.Locale.KOREA, "%02d시 %02d분", targetHour, targetMinute)
            val remMin = (remSec + 59L) / 60L
            val remStr = if (remSec >= 60L) "${remMin}분" else "${remSec}초"

            val topHudCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.argb(245, 255, 255, 255))
                    setStroke(dp(2), Color.rgb(46, 125, 50))
                    cornerRadius = dp(16).toFloat()
                }
                elevation = dp(14).toFloat()
            }

            val targetHeader = TextView(context).apply {
                text = "⏰ 남은 휴식: $remStr ($targetTimeStr)"
                textSize = 13.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.rgb(27, 94, 32))
                gravity = Gravity.CENTER_HORIZONTAL
            }
            topHudCard.addView(targetHeader)

            val rec = s.recommendation
            if (rec != null) {
                val missionTitle = TextView(context).apply {
                    text = "📋 ${rec.title}"
                    textSize = 12.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.rgb(30, 41, 59))
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(4), 0, dp(2))
                }
                topHudCard.addView(missionTitle)
            }

            val noteView = TextView(context).apply {
                text = "📱 스마트폰을 바닥에 엎어두면 화면이 꺼지고 휴식이 이어져요.\n휴식을 완료하면 개입이 자동으로 사라집니다."
                textSize = 12.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.rgb(194, 65, 12))
                gravity = Gravity.CENTER_HORIZONTAL
                setLineSpacing(dp(2).toFloat(), 1.2f)
                setPadding(0, dp(6), 0, dp(4))
            }
            topHudCard.addView(noteView)

            val topHudParams = params(topHudWidth, WindowManager.LayoutParams.WRAP_CONTENT, cardX, cardY, touch = false)
            add(topHudCard, topHudParams)

            val speechWidth = minOf(dp(320), width - dp(32))
            val speechY = (s.homeY - dp(120)).coerceAtLeast(cardY + dp(130))
            val retrySpeechText = CoachingSpeechCatalog.pickSpeech(SpeechSituation.RETRY_INCOMPLETE, s.tone)
            s.speech = label(retrySpeechText, customTextSize = 14f)
            s.speech.visibility = View.INVISIBLE
            s.speechParams = params(
                speechWidth, WindowManager.LayoutParams.WRAP_CONTENT,
                (width - speechWidth) / 2, speechY, true
            )
            add(s.speech, s.speechParams!!)

            dimView.animate().alpha(1f).setDuration(250).start()

            // 화면 아래쪽에서 팔을 벌린 채 위로 통통 튀어오르는 모션 (520ms, Overshoot)
            s.animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520L
                interpolator = android.view.animation.OvershootInterpolator(1.35f)
                addUpdateListener {
                    if (s.stopped) return@addUpdateListener
                    val f = it.animatedValue as Float
                    s.dogParams.y = (startY - (startY - s.homeY) * f).toInt()
                    try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                    if (f >= 0.99f) {
                        s.ready = true
                        s.speech.visibility = View.VISIBLE
                        startTailWagging(s)

                        // 터치 시 화면을 끄지 않고 안내 토스트 표시 (엎어두어야 화면이 꺼짐)
                        val guideAction = View.OnClickListener {
                            if (!s.stopped) {
                                android.widget.Toast.makeText(context, "📱 스마트폰을 바닥에 엎어두면 화면이 꺼지고 휴식이 이어져요", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        s.dog.setOnClickListener(guideAction)
                        s.speech.setOnClickListener(guideAction)
                        dimView.setOnClickListener(guideAction)
                        topHudCard.setOnClickListener(guideAction)

                        s.onReady?.invoke()
                        s.onReady = null
                    }
                }
                start()
            }
        } else {
            // 처음 강한 개입 (위에서 부드럽게 달려와서 앞을 가로막음)
            val startSize = (targetDogSize * 0.22f).toInt()
            val startX = (width - startSize) / 2
            val startY = dp(24)

            s.dog = EquippedDogView(context).apply {
                setImageResource(rewardFrames[0])
                contentDescription = "화면을 바라보며 달려와 앞을 막아선 강아지. 강아지를 터치하면 미션이 진행됩니다."
            }
            s.dogParams = params(startSize, startSize, startX, startY, true)
            s.dogParams.flags = s.dogParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            add(s.dog, s.dogParams)

            val strongBubble = buildOfferBubble(CharacterIntensity.STRONG, s.reasons, s.tone)
            s.speech = offerBubbleLabel(strongBubble, headlineSize = 14.5f)
            s.speech.visibility = View.INVISIBLE
            val speechWidth = minOf(dp(320), width - dp(32))
            val speechY = (s.homeY - dp(142)).coerceAtLeast(dp(32))
            s.speechParams = params(
                speechWidth, WindowManager.LayoutParams.WRAP_CONTENT,
                (width - speechWidth) / 2, speechY, true
            )
            add(s.speech, s.speechParams!!)

            s.animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1800L
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    if (s.stopped) return@addUpdateListener
                    val f = it.animatedValue as Float
                    val curSize = (startSize + (targetDogSize - startSize) * f).toInt()
                    s.dogParams.width = curSize
                    s.dogParams.height = curSize
                    s.dogParams.x = (width - curSize) / 2

                    dimView.alpha = (f * 1.05f).coerceIn(0f, 1f)

                    s.dog.scaleX = 1f
                    s.dog.scaleY = 1f
                    s.dog.rotation = 0f

                    val sprite = if (f >= 0.92f) {
                        R.drawable.itv_gallop_front_00
                    } else {
                        frontGallopFrames[((it.currentPlayTime / 70L) % frontGallopFrames.size).toInt()]
                    }
                    if (s.dog.tag != sprite) { s.dog.setImageResource(sprite); s.dog.tag = sprite }

                    val hop = if (f in 0.90f..0.99f) {
                        val hopF = (f - 0.90f) / 0.09f
                        (kotlin.math.sin(hopF * Math.PI) * dp(10)).toFloat()
                    } else 0f
                    s.dogParams.y = (startY + (s.homeY - startY) * f - hop).toInt()

                    try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                    if (f >= 0.99f) {
                        s.ready = true
                        s.dog.setImageResource(R.drawable.itv_gallop_front_00)
                        startTailWagging(s)
                        s.speech.visibility = View.VISIBLE
                        s.dog.setOnClickListener {
                            if (!s.committed && !s.stopped) {
                                val prepSec = com.example.itv.domain.coaching.TestCoachingPolicy.MISSION_START_MS / 1000L
                                val restSec = com.example.itv.domain.coaching.TestCoachingPolicy.REST_MS / 1000L
                                s.choice?.invoke("쉬기", "휴식 미션: ${prepSec}초 안에 화면을 끄고 연속 ${restSec}초 쉬어 주세요.")
                            }
                        }
                        s.speech.setOnClickListener { s.dog.performClick() }
                        s.onReady?.invoke()
                        s.onReady = null
                    }
                }
                start()
            }
        }

        // 화면 꺼짐 감지: ACTION_SCREEN_OFF가 들어오면 정리
        val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    if (!s.stopped) {
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
        houseView.setOnClickListener {
            if (s.ready && !s.committed && !s.stopped) {
                s.committed = true
                val prepSec = com.example.itv.domain.coaching.TestCoachingPolicy.MISSION_START_MS / 1000L
                val restSec = com.example.itv.domain.coaching.TestCoachingPolicy.REST_MS / 1000L
                s.choice?.invoke("쉬기", "휴식 미션: ${prepSec}초 안에 화면을 끄고 연속 ${restSec}초 쉬어 주세요.")
            }
        }
        add(houseView, params(houseW, houseH, hx, hy, true).apply { format = PixelFormat.OPAQUE; alpha = 1f })

        val normalBubble = if (!s.retry) buildOfferBubble(CharacterIntensity.NORMAL, s.reasons, s.tone) else null
        s.speech = if (s.retry) {
            label(CoachingSpeechCatalog.pickSpeech(SpeechSituation.RETRY_INCOMPLETE, s.tone))
        } else {
            offerBubbleLabel(normalBubble!!, headlineSize = 14.5f)
        }
        s.speech.visibility = View.INVISIBLE
        val speechWidth = minOf(dp(320), width - dp(24))
        val speechY = (s.homeY - dp(158)).coerceAtLeast(dp(32))
        s.speechParams = params(speechWidth, WindowManager.LayoutParams.WRAP_CONTENT, (width - speechWidth) / 2, speechY, false)
        add(s.speech, s.speechParams!!)

        val pen = ImageView(context).apply {
            setImageResource(R.drawable.itv_pen_fence)
            contentDescription = "닫기: 철창 펜스"
            visibility = View.INVISIBLE
        }
        pen.setOnClickListener {
            if (s.ready && !s.committed && !s.stopped) {
                s.committed = true
                s.choice?.invoke("닫기", "닫았어요.")
            }
        }
        val penParams = params(cageSize, cageSize, s.cage.left, s.cage.top, false)
        s.fence = pen
        s.fenceParams = penParams
        add(pen, penParams)

        s.dog = EquippedDogView(context).apply {
            setImageResource(R.drawable.itv_motion_front)
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
                    if (s.retry) {
                        s.speech.text = CoachingSpeechCatalog.pickSpeech(SpeechSituation.RETRY_INCOMPLETE, s.tone)
                    } else if (normalBubble != null) {
                        s.speech.setOfferContent(normalBubble)
                    }
                    s.speechParams?.let { sp ->
                        val bubbleH = s.speech.height.takeIf { it > 0 } ?: dp(110)
                        sp.y = (s.homeY - bubbleH - dp(12)).coerceAtLeast(dp(24))
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
            s.speech.text = CoachingSpeechCatalog.pickSpeech(SpeechSituation.RETRY_INCOMPLETE, s.tone)
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
        s.speech = label("잘 쉬었어! 반가워!", customTextSize = 13.5f)
        s.speech.visibility = View.INVISIBLE
        val speechWidth = minOf(dp(300), width - dp(24))
        s.speechParams = params(speechWidth, WindowManager.LayoutParams.WRAP_CONTENT, (width - speechWidth) / 2, dp(16), true)
        add(s.speech, s.speechParams!!)

        s.dog = EquippedDogView(context).apply {
            setImageResource(R.drawable.itv_motion_front)
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
                    s.speech.text = CoachingSpeechCatalog.pickSpeech(SpeechSituation.MISSION_COMPLETE, s.tone)
                }
            }
            start()
        }

        val claimRewardAction = View.OnClickListener {
            if (s.ready && !s.rewardClaimed && !s.stopped) {
                s.rewardClaimed = true
                s.dog.setImageResource(R.drawable.itv_motion_front)
                val isStrong = s.completionRecommendation?.isFacedownRequired == true
                val result = com.example.itv.infrastructure.reward.UserRewardStore.grantMissionReward(context, isStrong)
                s.speech.text = if (result.didLevelUp) {
                    CoachingSpeechCatalog.pickSpeech(
                        situation = SpeechSituation.LEVEL_UP,
                        tone = s.tone,
                        level = result.newLevel,
                        exp = result.expGained,
                        points = result.pointsGained
                    )
                } else {
                    CoachingSpeechCatalog.pickSpeech(
                        situation = SpeechSituation.REWARD_CLAIMED,
                        tone = s.tone,
                        level = result.newLevel,
                        exp = result.expGained,
                        points = result.pointsGained
                    )
                }
                s.reaction = Runnable { if (!s.stopped) s.onRewardDismiss?.invoke() }
                handler.postDelayed(s.reaction!!, 2400L)
            }
        }
        s.dog.setOnClickListener(claimRewardAction)
        s.speech.setOnClickListener(claimRewardAction)
        startBubbleFollow(s, width, height)
    }

    private fun stopBubbleFollow(s: Session) {
        s.bubbleFollow?.let { handler.removeCallbacks(it) }
        s.bubbleFollow = null
    }

    companion object {
        fun formatReasonLines(
            reasons: List<String>,
            maxLines: Int = 2
        ): String? {
            val picked = reasons.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(maxLines)
            return when (picked.size) {
                0 -> null
                1 -> picked[0]
                else -> picked.joinToString("\n") { "• $it" }
            }
        }

        fun buildOfferBubble(
            intensity: CharacterIntensity,
            reasons: List<String>,
            tone: CoachingTone = CoachingTone.SOFT,
            random: kotlin.random.Random = kotlin.random.Random.Default
        ): OfferBubbleContent = InterventionBubbleFormatter.buildOfferBubble(
            intensity = intensity,
            reasons = reasons,
            tone = tone,
            random = random
        )

        fun formatOfferSpeech(
            intensity: CharacterIntensity,
            reasons: List<String>,
            tone: CoachingTone = CoachingTone.SOFT,
            random: kotlin.random.Random = kotlin.random.Random.Default
        ): String = buildOfferBubble(intensity, reasons, tone, random).toPlainText()
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
                    val bubbleH = s.speech.height.takeIf { it > 0 } ?: dp(110)
                    val above = s.dogParams.y >= bubbleH + dp(12)
                    val tx = (cx - sp.width / 2).coerceIn(0, (width - sp.width).coerceAtLeast(0))
                    val ty = (if (above) s.dogParams.y - bubbleH - dp(8) else s.dogParams.y + s.dogParams.height + dp(8)).coerceIn(dp(16), (height - bubbleH - dp(16)).coerceAtLeast(0))
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
                                s.speech.text = CoachingSpeechCatalog.pickSpeech(SpeechSituation.HOVER_HOME, s.tone)
                            } else if (zone == "edge") {
                                s.shake?.cancel(); s.dog.rotation = 0f; s.dog.setImageResource(R.drawable.itv_motion_sad)
                                s.speech.text = CoachingSpeechCatalog.pickSpeech(SpeechSituation.HOVER_EDGE, s.tone)
                            } else if (zone == "none") {
                                s.dog.setImageResource(if (s.retry) R.drawable.itv_motion_angry else R.drawable.itv_motion_turn)
                                s.speech.text = if (s.retry) {
                                    CoachingSpeechCatalog.pickSpeech(SpeechSituation.RETRY_INCOMPLETE, s.tone)
                                } else {
                                    CoachingSpeechCatalog.pickSpeech(SpeechSituation.HOVER_NORMAL, s.tone)
                                }
                            }
                        }
                        if (over != s.hoveringPen) {
                            s.hoveringPen = over; s.shake?.cancel(); s.dog.rotation = 0f
                            if (over) {
                                s.dog.setImageResource(R.drawable.itv_motion_sad)
                                s.speech.text = CoachingSpeechCatalog.pickSpeech(SpeechSituation.HOVER_CAGE, s.tone)
                                s.shake = ValueAnimator.ofFloat(-6f, 6f, -6f, 6f, 0f).apply {
                                    duration = 1000; repeatCount = 2
                                    addUpdateListener { if (!s.stopped) s.dog.rotation = it.animatedValue as Float }
                                    start()
                                }
                            } else {
                                if (s.hoverZone == "home") {
                                    com.example.itv.presentation.character.CompanionHover.sleepy(s.dog)
                                    s.speech.text = CoachingSpeechCatalog.pickSpeech(SpeechSituation.HOVER_HOME, s.tone)
                                } else if (s.hoverZone == "edge") {
                                    s.dog.setImageResource(R.drawable.itv_motion_sad)
                                    s.speech.text = CoachingSpeechCatalog.pickSpeech(SpeechSituation.HOVER_EDGE, s.tone)
                                } else {
                                    s.dog.setImageResource(if (s.retry) R.drawable.itv_motion_angry else R.drawable.itv_motion_turn)
                                    s.speech.text = if (s.retry) {
                                        CoachingSpeechCatalog.pickSpeech(SpeechSituation.RETRY_INCOMPLETE, s.tone)
                                    } else {
                                        CoachingSpeechCatalog.pickSpeech(SpeechSituation.HOVER_NORMAL, s.tone)
                                    }
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
        recommendation: com.example.itv.domain.coaching.RestRecommendation? = null,
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
                val screenWidth = previewHost?.width?.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
                val screenHeight = previewHost?.height?.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
                val isStrong = s.characterIntensity == CharacterIntensity.STRONG

                // 1. [강한 개입 전용]: 취침 3분기 케어처럼 전체 화면을 서서히 어둡게 하는 백그라운드 딤 추가
                if (isStrong) {
                    val dimScrim = View(context).apply {
                        setBackgroundColor(Color.parseColor("#090D16"))
                        alpha = 0f
                    }
                    val scrimParams = params(screenWidth, screenHeight, 0, 0, false).apply {
                        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    }
                    wm.addView(dimScrim, scrimParams)
                    s.windows.add(dimScrim)
                    dimScrim.animate().alpha(0.85f).setDuration(800L).start()
                }

                // 2. 강아지 캐릭터 배치
                if (s.house.width() > 0) {
                    s.dogParams.x = s.house.centerX() - s.dogParams.width / 2
                    s.dogParams.y = s.house.bottom - s.dogParams.height * 2 / 3
                } else {
                    s.dogParams.x = (screenWidth - s.dogParams.width) / 2
                    s.dogParams.y = (screenHeight - s.dogParams.height - dp(24)).coerceAtLeast(dp(20))
                }
                s.dog.setImageResource(R.drawable.itv_motion_sleep)
                s.speech.visibility = View.GONE
                try {
                    wm.removeView(s.dog)
                    wm.addView(s.dog, s.dogParams)
                } catch (_: Exception) {
                    try { wm.updateViewLayout(s.dog, s.dogParams) } catch (_: Exception) {}
                }

                s.animator = ValueAnimator.ofFloat(1f, 1.035f, 1f, 1.035f, 1f).apply {
                    duration = 2700
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { if (!s.stopped) { s.dog.scaleY = it.animatedValue as Float } }
                    start()
                }

                val rec = recommendation ?: s.recommendation ?: com.example.itv.domain.coaching.RestMissionCatalog.pick(com.example.itv.domain.coaching.UserPosture.UNKNOWN, false)
                val cardWidth = minOf(dp(320), screenWidth - dp(32))

                val missionCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(18), dp(20), dp(18))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        setStroke(dp(2), Color.rgb(76, 175, 80))
                        cornerRadius = dp(20).toFloat()
                    }
                    elevation = dp(14).toFloat()
                }

                val titleView = TextView(context).apply {
                    text = "📋 ${rec.title}"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.rgb(27, 94, 32))
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                missionCard.addView(titleView)

                val actionSpannable = android.text.SpannableString(rec.action).apply {
                    for (phrase in rec.boldPhrases) {
                        val startIdx = rec.action.indexOf(phrase)
                        if (startIdx >= 0) {
                            setSpan(
                                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                startIdx,
                                startIdx + phrase.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            setSpan(
                                android.text.style.ForegroundColorSpan(Color.rgb(15, 23, 42)),
                                startIdx,
                                startIdx + phrase.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
                val actionView = TextView(context).apply {
                    text = actionSpannable
                    textSize = 14.5f
                    setTextColor(Color.rgb(51, 65, 85))
                    setLineSpacing(dp(3).toFloat(), 1.25f)
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(12), 0, dp(12))
                }
                missionCard.addView(actionView)

                val restMinutes = (restSeconds + 59L) / 60L
                val restDisplayDesc = if (restSeconds >= 60L) "${restMinutes}분" else "${restSeconds}초"

                val targetTimeCal = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.SECOND, (prepareSeconds + restSeconds).toInt())
                    if (get(java.util.Calendar.SECOND) > 0) {
                        add(java.util.Calendar.MINUTE, 1)
                        set(java.util.Calendar.SECOND, 0)
                    }
                }
                val targetHour = targetTimeCal.get(java.util.Calendar.HOUR_OF_DAY)
                val targetMinute = targetTimeCal.get(java.util.Calendar.MINUTE)
                val targetTimeStr = String.format(java.util.Locale.KOREA, "%02d시 %02d분", targetHour, targetMinute)

                if (isStrong) {
                    val guideBox = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        background = GradientDrawable().apply {
                            setColor(Color.rgb(241, 248, 233))
                            setStroke(dp(1), Color.rgb(200, 230, 201))
                            cornerRadius = dp(14).toFloat()
                        }
                    }
                    val guideTitle = TextView(context).apply {
                        text = "📱 스마트폰을 엎어두면 휴식이 시작돼요"
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Color.rgb(46, 125, 50))
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, 0, 0, dp(4))
                    }
                    guideBox.addView(guideTitle)

                    val guideDesc = TextView(context).apply {
                        text = "폰을 바닥에 엎어두면 화면이 꺼지며 ${restDisplayDesc} 휴식이 시작되고,\n휴식을 완료하면 개입이 자동으로 사라집니다."
                        textSize = 12.5f
                        setTextColor(Color.rgb(55, 65, 81))
                        gravity = Gravity.CENTER_HORIZONTAL
                        setLineSpacing(dp(2).toFloat(), 1.2f)
                    }
                    guideBox.addView(guideDesc)
                    missionCard.addView(guideBox)
                } else {
                    val conditionText = if (rec.isFacedownRequired) {
                        "※ 10초 내 폰을 바닥에 엎어두면 화면이 꺼지고 ${targetTimeStr}까지 (${restDisplayDesc}) 휴식돼요."
                    } else {
                        "※ 10초 내 화면을 끄고 ${targetTimeStr}까지 (${restDisplayDesc}) 쉬어주세요."
                    }
                    val conditionView = TextView(context).apply {
                        text = conditionText
                        textSize = 12.5f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Color.rgb(100, 116, 139))
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, 0, 0, dp(14))
                    }
                    missionCard.addView(conditionView)

                    val acceptButton = Button(context).apply {
                        text = "미션 확인"
                        textSize = 14.5f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        background = GradientDrawable().apply {
                            setColor(Color.rgb(46, 125, 50))
                            cornerRadius = dp(12).toFloat()
                        }
                        setOnClickListener {
                            s.reaction?.let { handler.removeCallbacks(it) }
                            s.reaction = null
                            try { wm.removeView(missionCard) } catch (_: Exception) {}
                            s.windows.remove(missionCard)

                            showMissionCountdownHud(
                                rec = rec,
                                targetTimeStr = targetTimeStr,
                                restDisplayDesc = restDisplayDesc,
                                prepareSeconds = prepareSeconds
                            )

                            val callback = s.reactionFinished
                            s.reactionFinished = null
                            callback?.invoke()
                        }
                    }
                    missionCard.addView(acceptButton, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                    ))
                }

                val cardX = (screenWidth - cardWidth) / 2
                val cardY = ((screenHeight - dp(260)) / 2).coerceAtLeast(dp(40))
                val cardParams = params(cardWidth, WindowManager.LayoutParams.WRAP_CONTENT, cardX, cardY, touch = true)
                wm.addView(missionCard, cardParams)
                s.windows.add(missionCard)

                if (isStrong) {
                    // 강한 개입: 화면을 끄고 휴식을 완료할 때까지 미션 창과 캐릭터를 유지 (카운트다운으로 사라지지 않음)
                    return
                } else {
                    // 약한 개입/보통 개입: 무응답 시 자동 정리 콜백
                    s.reaction = Runnable {
                        if (!s.stopped) {
                            try { wm.removeView(missionCard) } catch (_: Exception) {}
                            s.windows.remove(missionCard)
                            val callback = s.reactionFinished
                            s.reactionFinished = null
                            callback?.invoke()
                        }
                    }
                    handler.postDelayed(s.reaction!!, 60_000L)
                    return
                }
            }
            "나중에" -> {
                s.dog.setImageResource(R.drawable.itv_motion_wave)
                val timeStr = if (reminderSeconds % 60L == 0L) "${reminderSeconds / 60L}분" else "${reminderSeconds}초"
                s.speech.text = CoachingSpeechCatalog.pickSpeech(SpeechSituation.REACT_LATER, s.tone, timeText = timeStr)
            }
            else -> {
                if (s.cage.width() > 0) {
                    s.dogParams.x = s.cage.centerX() - s.dogParams.width / 2
                    s.dogParams.y = s.cage.bottom - s.dogParams.height - dp(8)
                }
                s.dog.setImageResource(R.drawable.itv_motion_sad)
                s.speech.text = CoachingSpeechCatalog.pickSpeech(SpeechSituation.REACT_DISMISS, s.tone)
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
        handler.postDelayed(s.reaction!!, 1600L)
    }

    private var activeCountdownHudView: View? = null
    private var activeCountdownReceiver: BroadcastReceiver? = null

    fun dismissMissionCountdownHud() {
        activeCountdownReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            activeCountdownReceiver = null
        }
        activeCountdownHudView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            activeCountdownHudView = null
        }
    }

    fun showMissionCountdownHud(
        rec: com.example.itv.domain.coaching.RestRecommendation,
        targetTimeStr: String,
        restDisplayDesc: String,
        prepareSeconds: Long,
        isResume: Boolean = false,
        remainingRestSeconds: Long? = null
    ) {
        dismissMissionCountdownHud()

        val screenWidth = previewHost?.width?.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val hudWidth = minOf(dp(300), screenWidth - dp(32))

        val hudCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 255, 255, 255))
                setStroke(dp(2), Color.rgb(46, 125, 50))
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(14).toFloat()
        }

        val headerText = if (isResume && remainingRestSeconds != null) {
            val remMin = (remainingRestSeconds + 59L) / 60L
            val remStr = if (remainingRestSeconds >= 60L) "${remMin}분" else "${remainingRestSeconds}초"
            "⏰ 남은 휴식: ${remStr} ($targetTimeStr)"
        } else {
            "⏰ 목표: $targetTimeStr ($restDisplayDesc 휴식)"
        }
        val targetHeader = TextView(context).apply {
            text = headerText
            textSize = 13.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(27, 94, 32))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        hudCard.addView(targetHeader)

        val missionTitle = TextView(context).apply {
            text = "📋 ${rec.title}"
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(30, 41, 59))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(2))
        }
        hudCard.addView(missionTitle)

        val missionDesc = TextView(context).apply {
            text = rec.action
            textSize = 12f
            setTextColor(Color.rgb(71, 85, 105))
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setPadding(0, 0, 0, dp(6))
        }
        hudCard.addView(missionDesc)

        if (rec.isFacedownRequired) {
            val facedownNote = TextView(context).apply {
                text = "📱 폰을 엎어두면 화면이 꺼져요"
                textSize = 11.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.rgb(194, 65, 12))
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            hudCard.addView(facedownNote)
        } else if (isResume) {
            val resumeNote = TextView(context).apply {
                text = "💡 화면을 끄면 휴식이 이어져요"
                textSize = 11.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.rgb(46, 125, 50))
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            hudCard.addView(resumeNote)
        }

        val divider = View(context).apply {
            setBackgroundColor(Color.rgb(226, 232, 240))
        }
        hudCard.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        val timerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val timerLabel = TextView(context).apply {
            text = "남은 시간 : "
            textSize = 13f
            setTextColor(Color.rgb(100, 116, 139))
        }
        val timerCount = TextView(context).apply {
            text = if (isResume && remainingRestSeconds != null) {
                if (remainingRestSeconds >= 60L) "${remainingRestSeconds / 60L}분 ${remainingRestSeconds % 60L}초" else "${remainingRestSeconds}초"
            } else {
                "${prepareSeconds}초"
            }
            textSize = 15.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(220, 38, 38))
        }
        timerRow.addView(timerLabel)
        timerRow.addView(timerCount)
        hudCard.addView(timerRow)

        val cardX = (screenWidth - hudWidth) / 2
        val cardY = dp(48)
        val hudParams = params(hudWidth, WindowManager.LayoutParams.WRAP_CONTENT, cardX, cardY, touch = false)
        try {
            wm.addView(hudCard, hudParams)
            activeCountdownHudView = hudCard
        } catch (_: Exception) {
            return
        }

        var remainingSeconds = prepareSeconds
        val timerRunnable = object : Runnable {
            override fun run() {
                if (activeCountdownHudView !== hudCard) return
                remainingSeconds -= 1
                if (remainingSeconds > 0) {
                    if (!isResume) {
                        timerCount.text = "${remainingSeconds}초"
                    }
                    handler.postDelayed(this, 1000L)
                } else {
                    if (!isResume) {
                        timerCount.text = "0초"
                    }
                    handler.postDelayed({
                        if (activeCountdownHudView === hudCard) {
                            dismissMissionCountdownHud()
                        }
                    }, 500L)
                }
            }
        }
        handler.postDelayed(timerRunnable, 1000L)

        // 화면이 꺼지면 즉시 HUD 제거
        val offReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    handler.removeCallbacks(timerRunnable)
                    dismissMissionCountdownHud()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(offReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(offReceiver, filter)
            }
            activeCountdownReceiver = offReceiver
        } catch (_: Exception) {}
    }

    fun disableControls(root: View) { sessions[root]?.committed = true }
    fun removeControls(root: View) { sessions.remove(root)?.cleanup() }
    fun isStrong(root: View?): Boolean = root != null && sessions[root]?.characterIntensity == CharacterIntensity.STRONG
}
