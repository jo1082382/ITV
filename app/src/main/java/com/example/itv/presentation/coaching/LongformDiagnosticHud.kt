package com.example.itv.presentation.coaching

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.itv.R
import com.example.itv.domain.jev.JevCandidatePolicy
import com.example.itv.domain.jev.JevSettings
import com.example.itv.infrastructure.jev.JevFeature
import com.example.itv.infrastructure.jev.JevObservationHub
import com.example.itv.infrastructure.jev.JevRuntime
import com.example.itv.infrastructure.observation.AudioPipelineKind
import com.example.itv.infrastructure.observation.GeneralAppTracker
import com.example.itv.infrastructure.observation.InferredAppCategory
import com.example.itv.infrastructure.observation.InterventionEligibility
import com.example.itv.infrastructure.observation.accessibility.WritingGuard
import com.example.itv.infrastructure.observation.instagram.InstagramScreenMode
import com.example.itv.infrastructure.observation.instagram.InstagramTracker
import com.example.itv.domain.records.ShortformApp
import com.example.itv.infrastructure.observation.SessionCategoryTracker
import com.example.itv.infrastructure.observation.SwipeDynamicsTracker
import com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeWatchMode
import com.example.itv.presentation.components.PuppyMood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * YouTube, Instagram 및 휴대폰의 모든 앱 화면 위에서 실시간 감지 상태
 * (앱 카테고리 확정, 게임/영상/음성 오디오 파이프라인, 앱 내 세부 화면, 시청/체류 시간, 세션 카테고리별 누적 시간)를
 * 확인할 수 있는 드래그 가능한 플로팅 HUD 위젯.
 *
 * - 기본 상태: 우리 앱(ITV) 아이콘 모양의 작은 플로팅 버튼으로 떠 있어 화면을 가리지 않음
 * - 아이콘 터치 시: 상세 분석 HUD 카드로 즉시 펼쳐짐 (다시 터치하거나 '작게' 버튼을 누르면 아이콘으로 축소)
 */
@SuppressLint("StaticFieldLeak")
object LongformDiagnosticHud {

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null
    private var collapsedView: FrameLayout? = null
    private var collapsedBorderDrawable: GradientDrawable? = null
    private var collapsedStatusDotDrawable: GradientDrawable? = null
    private var expandedView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var scope: CoroutineScope? = null

    private var collapsedIconImageView: ImageView? = null
    private var miniIconImageView: ImageView? = null
    private var defaultAppIconDrawable: android.graphics.drawable.Drawable? = null

    private var modeText: TextView? = null
    private var titleText: TextView? = null
    private var basisText: TextView? = null
    private var timerText: TextView? = null
    private var categoryUsageText: TextView? = null
    private var totalUsageText: TextView? = null
    private var jevProbText: TextView? = null

    private var lastAppliedIconKey: String? = null
    private var lastAppliedStatusColor: Int? = null
    private var lastOutsideObserveElapsed: Long = 0L
    private var outsideTouchWatchEnabled: Boolean = true
    @Volatile private var dormantOwnAppRendered: Boolean = false
    private var lastAppContext: Context? = null
    private var cachedPowerManager: PowerManager? = null
    private var cachedKeyguardManager: KeyguardManager? = null

    fun notifyOwnAppExited() {
        dormantOwnAppRendered = false
        val appCtx = lastAppContext ?: return
        if (!isShowing) return
        scope?.launch {
            if (isShowing) {
                renderHud(appCtx)
            }
        }
    }

    private fun TextView?.setTextIfChanged(newText: CharSequence) {
        if (this != null && this.text?.toString() != newText.toString()) {
            this.text = newText
        }
    }

    private fun TextView?.setTextColorIfChanged(color: Int) {
        if (this != null && this.currentTextColor != color) {
            this.setTextColor(color)
        }
    }

    private fun TextView?.setVisibilityIfChanged(vis: Int) {
        if (this != null && this.visibility != vis) {
            this.visibility = vis
        }
    }

    var previewMoodOverride: PuppyMood? = null
        private set

    var isShowing: Boolean = false
        private set

    var isExpanded: Boolean = false
        private set

    var isSleeping: Boolean = false
        private set

    var currentProbabilityMood: PuppyMood? = null
        private set

    /**
     * 테스트/미리보기용으로 5단계 표정을 강제 지정하거나 해제(null)합니다.
     */
    fun setPreviewMoodOverride(context: Context, mood: PuppyMood?) {
        previewMoodOverride = mood
        lastAppliedIconKey = null
        if (isShowing) {
            renderHud(context.applicationContext)
        }
    }

    /**
     * 야간 연속 사용 예산 초과 시 눈 감은 강아지(수면) 얼굴로 아이콘 및 테마를 전환합니다.
     */
    fun setSleepingState(sleeping: Boolean) {
        isSleeping = sleeping
        lastAppliedIconKey = null
        val icon = collapsedIconImageView
        val mini = miniIconImageView
        val statusDot = collapsedStatusDotDrawable
        val border = collapsedBorderDrawable
        val density = rootContainer?.resources?.displayMetrics?.density ?: 2f
        if (sleeping) {
            icon?.setImageResource(R.drawable.itv_motion_sleep)
            mini?.setImageResource(R.drawable.itv_motion_sleep)
            lastAppliedIconKey = "SLEEP"
            val sleepColor = Color.parseColor("#B197FC")
            if (lastAppliedStatusColor != sleepColor) {
                statusDot?.setColor(sleepColor)
                border?.setStroke((2.5f * density).toInt(), sleepColor)
                lastAppliedStatusColor = sleepColor
            }
            if (isExpanded) {
                modeText.setTextIfChanged("🌙 취침 권장 시간 초과 (휴식 대기)")
                modeText.setTextColorIfChanged(sleepColor)
            }
        } else {
            val ctx = rootContainer?.context?.applicationContext
            if (ctx != null && isShowing) {
                renderHud(ctx)
            } else if (ctx != null) {
                val fallbackMood = previewMoodOverride ?: PuppyMood.BRIGHT
                val bmp = HudPuppyFaceRenderer.getFaceBitmap(ctx, fallbackMood)
                icon?.setImageBitmap(bmp)
                mini?.setImageBitmap(bmp)
                lastAppliedIconKey = "MOOD_${fallbackMood.name}"
            } else if (defaultAppIconDrawable != null) {
                icon?.setImageDrawable(defaultAppIconDrawable)
                mini?.setImageDrawable(defaultAppIconDrawable)
                lastAppliedIconKey = "DEFAULT"
            } else {
                icon?.setImageResource(R.drawable.itv_puppy_face)
                mini?.setImageResource(R.drawable.itv_puppy_face)
                lastAppliedIconKey = "DEFAULT"
            }
        }
    }

    private fun formatDurationSec(sec: Long): String =
        if (sec >= 60) "${sec / 60}분 ${sec % 60}초" else "${sec}초"

    fun isOverlayPermissionGranted(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isAccessibilityPermissionGranted(context: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
            if (!enabled) return false
            val services = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (services != null) {
                services.contains(context.packageName)
            } else {
                WritingGuard.connected
            }
        } catch (_: Exception) {
            WritingGuard.connected
        }
    }

    fun hasRequiredPermissions(context: Context): Boolean =
        isOverlayPermissionGranted(context) && isAccessibilityPermissionGranted(context)

    fun missingPermissionSummary(context: Context): String {
        val overlayOk = isOverlayPermissionGranted(context)
        val accessOk = isAccessibilityPermissionGranted(context)
        return when {
            !overlayOk && !accessOk -> "접근성 서비스와 다른 앱 위에 표시 권한을 모두 켜야 HUD를 사용할 수 있습니다."
            !accessOk -> "접근성 서비스 권한을 켜야 HUD를 사용할 수 있습니다."
            !overlayOk -> "다른 앱 위에 표시 권한을 켜야 HUD를 사용할 수 있습니다."
            else -> ""
        }
    }

    fun hideIfPermissionsRevoked(context: Context): Boolean {
        if (isShowing && !hasRequiredPermissions(context)) {
            hide()
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun show(context: Context): Boolean {
        if (!hasRequiredPermissions(context)) {
            if (isShowing) hide()
            return false
        }
        if (isShowing) return true

        val appCtx = context.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        windowManager = wm
        cachedPowerManager = appCtx.getSystemService(PowerManager::class.java)
        cachedKeyguardManager = appCtx.getSystemService(KeyguardManager::class.java)
        lastAppliedStatusColor = if (isSleeping) Color.parseColor("#B197FC") else Color.parseColor("#48C774")
        lastOutsideObserveElapsed = 0L

        val dp = appCtx.resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()

        val root = FrameLayout(appCtx).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            background = null
            elevation = 0f
        }

        // =========================================================================
        // 1. 기본 축소 모드 뷰: 우리 앱(ITV) 아이콘 + 최상단 외곽 링 + 우하단 상태 점
        // =========================================================================
        val iconBoxBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#EAF5EF"))
        }

        val iconBox = FrameLayout(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(52.dp(), 52.dp())
            background = iconBoxBg
            elevation = 0f
            translationZ = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = Color.TRANSPARENT
                outlineSpotShadowColor = Color.TRANSPARENT
            }
        }

        val appIconDrawable = runCatching {
            appCtx.packageManager.getApplicationIcon(appCtx.packageName)
        }.getOrNull()
        defaultAppIconDrawable = appIconDrawable

        // 1-1. 앱 아이콘 뷰: 원형으로 클리핑하여 테두리 밖으로 번지지 않도록 함
        val iconImage = ImageView(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ).apply {
                val pad = 1.dp()
                setMargins(pad, pad, pad, pad)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EAF5EF"))
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (isSleeping) {
                setImageResource(R.drawable.itv_motion_sleep)
                lastAppliedIconKey = "SLEEP"
            } else {
                val initialMood = previewMoodOverride ?: PuppyMood.BRIGHT
                setImageBitmap(HudPuppyFaceRenderer.getFaceBitmap(appCtx, initialMood))
                lastAppliedIconKey = "MOOD_${initialMood.name}"
            }
        }
        collapsedIconImageView = iconImage
        iconBox.addView(iconImage)

        // 1-2. 최상단 외곽 테두리 링: 아이콘 배경 위(최상단)에 배치하여 배경에 묻히거나 끊기지 않고 선명한 원형 유지
        val borderRingBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke((2.5f * dp).toInt(), if (isSleeping) Color.parseColor("#B197FC") else Color.parseColor("#48C774"))
        }
        collapsedBorderDrawable = borderRingBg

        val borderRingView = View(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = borderRingBg
            elevation = 0f
            isClickable = false
            isFocusable = false
        }
        iconBox.addView(borderRingView)

        // 1-3. 우하단 상태 인디케이터 점
        val statusDotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isSleeping) Color.parseColor("#B197FC") else Color.parseColor("#48C774"))
            setStroke((1.5f * dp).toInt(), Color.WHITE)
        }
        collapsedStatusDotDrawable = statusDotBg

        val statusDot = View(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(12.dp(), 12.dp(), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = 1.dp()
                bottomMargin = 1.dp()
            }
            background = statusDotBg
            elevation = 0f
            isClickable = false
            isFocusable = false
        }
        iconBox.addView(statusDot)
        collapsedView = iconBox

        // =========================================================================
        // 2. 펼침(상세 HUD) 모드 뷰: 카테고리·화면·오디오 확정 근거 및 개입 환경 표시
        // =========================================================================
        val card = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(305.dp(), FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE16181A"))
                cornerRadius = 14f * dp
                setStroke(1.dp(), Color.parseColor("#6648C774"))
            }
            elevation = 10f * dp
            visibility = View.GONE
        }

        // 2-1. 헤더: 미니 앱 아이콘 + 앱/모드 뱃지 + [작게] 버튼 + [✕] 버튼
        val header = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val miniIcon = ImageView(appCtx).apply {
            layoutParams = LinearLayout.LayoutParams(20.dp(), 20.dp()).apply {
                rightMargin = 6.dp()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EAF5EF"))
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (isSleeping) {
                setImageResource(R.drawable.itv_motion_sleep)
            } else {
                val initialMood = previewMoodOverride ?: PuppyMood.BRIGHT
                setImageBitmap(HudPuppyFaceRenderer.getFaceBitmap(appCtx, initialMood))
            }
        }
        miniIconImageView = miniIcon

        modeText = TextView(appCtx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#48C774"))
            text = "[실시간 통합 관측 대기]"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val shrinkBtn = TextView(appCtx).apply {
            text = "작게 —"
            textSize = 10.5f
            setTextColor(Color.parseColor("#B2F2BB"))
            setPadding(7.dp(), 2.dp(), 7.dp(), 2.dp())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3348C774"))
                cornerRadius = 8f * dp
            }
            setOnClickListener { setExpanded(appCtx, false) }
        }

        val closeBtn = TextView(appCtx).apply {
            text = " ✕ "
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(6.dp(), 2.dp(), 2.dp(), 2.dp())
            setOnClickListener { hide() }
        }

        header.addView(miniIcon)
        header.addView(modeText)
        header.addView(shrinkBtn)
        header.addView(closeBtn)
        card.addView(header)

        // 2-2. 영상 제목 / 확정된 활동 및 앱 내 화면
        titleText = TextView(appCtx).apply {
            textSize = 12.5f
            setTextColor(Color.WHITE)
            text = "앱을 실행하면 활동 내용을 실시간 분석합니다"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 5.dp(), 0, 2.dp())
        }
        card.addView(titleText)

        // 2-3. 판별 근거 줄 (OS 카테고리 · 게임엔진 · 앱 내 Activity 화면 · 오디오 파이프라인)
        basisText = TextView(appCtx).apply {
            textSize = 10.5f
            setTextColor(Color.parseColor("#80CBC4"))
            text = "🔎 판별 근거: 대기 중"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 1.dp(), 0, 2.dp())
        }
        card.addView(basisText)

        // 2-4. 타이머 & 오디오/입력 상태 텍스트
        timerText = TextView(appCtx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#DDDDDD"))
            text = "체류/시청: 0초"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 1.dp(), 0, 2.dp())
        }
        card.addView(timerText)

        // 2-5. 앱 카테고리별 누적 사용량 줄
        categoryUsageText = TextView(appCtx).apply {
            textSize = 10.5f
            setTextColor(Color.parseColor("#81D4FA"))
            text = "📊 카테고리: 집계 대기 중"
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 1.dp(), 0, 2.dp())
        }
        card.addView(categoryUsageText)

        // 2-6. 세션 총 사용시간 (실제 물리 총 시간 + 3단계 가중치 환산 시간)
        totalUsageText = TextView(appCtx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#FFD54F"))
            text = "⏱ 총 사용: 0초"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 1.dp(), 0, 2.dp())
        }
        card.addView(totalUsageText)

        // 2-7. Jev 확률 vs 우리 앱 기준 확률 차이 및 5단계 표정 상태 줄
        jevProbText = TextView(appCtx).apply {
            textSize = 10.5f
            setTextColor(Color.parseColor("#B0BEC5"))
            text = ""
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 1.dp(), 0, 2.dp())
            visibility = View.GONE
        }
        card.addView(jevProbText)

        expandedView = card

        root.addView(iconBox)
        root.addView(card)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 기본적으로는 작은 아이콘 모드(isExpanded = false)로 시작
        // FLAG_WATCH_OUTSIDE_TOUCH를 함께 설정하여, 접근성 이벤트를 전혀 발생시키지 않는
        // OpenGL/Vulkan/Unity/Unreal 게임 캔버스 위를 터치할 때도 ACTION_OUTSIDE로 즉시 터치 여부를 감지(터치는 게임으로 100% 그대로 통과)
        isExpanded = false
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16.dp()
            y = 120.dp()
        }
        layoutParams = params

        // 드래그 이동 및 터치(작게 ↔ 크게 전환) 리스너
        val touchSlopPx = 8.dp()
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        val dragAndTapListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    val fg = JevObservationHub.foregroundPackage
                    // ITV 앱 내부(홈 캘린더 스크롤 등)에서는 터치할 때마다 HUD 갱신/관측을 실행하지 않아 스크롤 끊김 원천 차단
                    if (fg.isNullOrBlank() || fg == appCtx.packageName) {
                        return@OnTouchListener false
                    }
                    // 게임 등 외부 앱의 조작 타이머만 O(1)로 즉시 기록
                    GeneralAppTracker.onUserInteraction()
                    val now = SystemClock.elapsedRealtime()
                    val isGame = GeneralAppTracker.snapshot.value.category == InferredAppCategory.GAME
                    // 게임이거나 HUD가 펼쳐진 상태에서 최소 500ms 간격으로만 즉시 관측·갱신 (유튜브/인스타 스크롤 중 프레임 스파이크 방지)
                    if ((isGame || isExpanded) && now - lastOutsideObserveElapsed >= 500L) {
                        lastOutsideObserveElapsed = now
                        GeneralAppTracker.onObserve(appCtx, fg)
                        renderHud(appCtx)
                    }
                    false
                }
                MotionEvent.ACTION_DOWN -> {
                    GeneralAppTracker.notifyHudInteraction()
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    GeneralAppTracker.notifyHudInteraction()
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val metrics = appCtx.resources.displayMetrics
                        val viewW = root.width.takeIf { it > 0 } ?: 52.dp()
                        val viewH = root.height.takeIf { it > 0 } ?: 52.dp()
                        val nextX = (initialX + dx).coerceIn(0, (metrics.widthPixels - viewW).coerceAtLeast(0))
                        val nextY = (initialY + dy).coerceIn(0, (metrics.heightPixels - viewH).coerceAtLeast(0))
                        if (nextX != params.x || nextY != params.y) {
                            params.x = nextX
                            params.y = nextY
                            try {
                                wm.updateViewLayout(root, params)
                            } catch (_: Exception) {}
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    GeneralAppTracker.notifyHudInteraction()
                    if (!isDragging) {
                        // 드래그가 아닌 가벼운 터치일 때: 아이콘 ↔ 상세 HUD 자유 전환
                        setExpanded(appCtx, !isExpanded)
                    }
                    true
                }
                else -> false
            }
        }

        root.setOnTouchListener(dragAndTapListener)
        iconBox.setOnTouchListener(dragAndTapListener)
        card.setOnTouchListener(dragAndTapListener)

        try {
            GeneralAppTracker.notifyHudInteraction()
            wm.addView(root, params)
            rootContainer = root
            isShowing = true
        } catch (_: Exception) {
            return false
        }

        // 가벼운 UI 갱신 루프 (dirty-check로 실제 변경이 없으면 오버레이 리드로우 0회)
        outsideTouchWatchEnabled = true
        dormantOwnAppRendered = false
        lastAppContext = appCtx
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope?.launch {
            while (isActive) {
                if (!hasRequiredPermissions(appCtx)) {
                    hide()
                    break
                }
                val inOwnApp = WritingGuard.inAppForeground ||
                    JevObservationHub.foregroundPackage == appCtx.packageName
                // ITV 내부 화면(홈/캘린더 스크롤 등)에서는 FLAG_WATCH_OUTSIDE_TOUCH 자체를 꺼서
                // 모든 터치마다 InputDispatcher -> HUD 오버레이 IPC(ACTION_OUTSIDE)가 발생하지 않도록 차단
                val shouldWatchOutside = !inOwnApp
                if (outsideTouchWatchEnabled != shouldWatchOutside) {
                    outsideTouchWatchEnabled = shouldWatchOutside
                    val curParams = layoutParams
                    val curRoot = rootContainer
                    val curWm = windowManager
                    if (curParams != null && curRoot != null && curWm != null) {
                        curParams.flags = if (shouldWatchOutside) {
                            curParams.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        } else {
                            curParams.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
                        }
                        try { curWm.updateViewLayout(curRoot, curParams) } catch (_: Exception) {}
                    }
                }
                if (inOwnApp && !isExpanded) {
                    // ITV 내부에서 접힌 작은 아이콘 상태일 때는 최초 1회만 그린 뒤
                    // 메인 스레드 currentEvaluationContext() / Binder IPC / 재드로우를 완전히 생략
                    if (!dormantOwnAppRendered) {
                        renderHud(appCtx)
                        dormantOwnAppRendered = true
                    }
                } else {
                    dormantOwnAppRendered = false
                    renderHud(appCtx)
                }
                val intervalMs = when {
                    inOwnApp && !isExpanded -> 3000L
                    inOwnApp && isExpanded -> 1000L
                    isExpanded -> 500L
                    else -> 1000L
                }
                delay(intervalMs)
            }
        }
        // 실제 Jev 호출로 새 확률이 도착하면 주기 대기 없이 즉시(0ms 지연) 표정·퍼센트 갱신
        scope?.launch {
            try {
                JevRuntime.get(appCtx).state
                    .map { it.probability }
                    .drop(1)
                    .distinctUntilChanged()
                    .collect {
                        if (isShowing) {
                            dormantOwnAppRendered = false
                            renderHud(appCtx)
                        }
                    }
            } catch (_: Exception) {
                // JevRuntime 미초기화 환경 무시
            }
        }

        return true
    }

    /**
     * 작은 앱 아이콘 모드(false)와 펼쳐진 상세 HUD 모드(true)를 전환합니다.
     */
    fun setExpanded(context: Context, expand: Boolean) {
        if (!isShowing) return
        GeneralAppTracker.notifyHudInteraction()
        isExpanded = expand
        val appCtx = context.applicationContext
        val dp = appCtx.resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()

        collapsedView?.visibility = if (expand) View.GONE else View.VISIBLE
        expandedView?.visibility = if (expand) View.VISIBLE else View.GONE

        val wm = windowManager
        val root = rootContainer
        val params = layoutParams
        if (wm != null && root != null && params != null) {
            val metrics = appCtx.resources.displayMetrics
            val targetWidth = if (expand) 305.dp() else 52.dp()
            params.x = params.x.coerceIn(0, (metrics.widthPixels - targetWidth).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (metrics.heightPixels - 80.dp()).coerceAtLeast(0))
            try {
                wm.updateViewLayout(root, params)
            } catch (_: Exception) {}
        }

        if (expand) {
            dormantOwnAppRendered = false
            renderHud(appCtx)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderHud(appCtx: Context) {
        val fgPkg = JevObservationHub.foregroundPackage
        val yt = YoutubeLongformTracker.snapshot.value
        val ig = InstagramTracker.snapshot.value
        val gen = GeneralAppTracker.snapshot.value
        val guardReason = WritingGuard.blockReason()
        val isWritingBlocked = guardReason != null && !guardReason.startsWith("ITV 내부")

        // 먼저 현재 상태의 대표 색상(개입 가능=초록, 전환 대기=노랑, 개입 불가=빨강, 수면=보라) 계산 -> 작은 아이콘 테두리/점에도 반영
        val statusColor = if (isSleeping) {
            Color.parseColor("#B197FC")
        } else {
            when {
                fgPkg == JevSettings.INSTAGRAM || ig.isInInstagram -> when {
                    ig.mode == InstagramScreenMode.DM || isWritingBlocked -> Color.parseColor("#FF6B6B")
                    ig.mode == InstagramScreenMode.REELS ||
                        ig.mode == InstagramScreenMode.COMMENTS ||
                        ig.mode == InstagramScreenMode.STORY -> Color.parseColor("#FFD166")
                    else -> Color.parseColor("#48C774")
                }
                fgPkg == JevSettings.YOUTUBE || (yt.mode != YoutubeWatchMode.BACKGROUND && yt.mode != YoutubeWatchMode.UNKNOWN) -> {
                    val isWatching = yt.mode == YoutubeWatchMode.PORTRAIT_WATCH || yt.mode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN
                    when {
                        isWritingBlocked -> Color.parseColor("#FF6B6B")
                        yt.isEndscreenDetected -> Color.parseColor("#48C774")
                        isWatching || yt.mode == YoutubeWatchMode.SHORTS || yt.mode == YoutubeWatchMode.SHORTS_COMMENTS ->
                            Color.parseColor("#FFD166")
                        else -> Color.parseColor("#48C774")
                    }
                }
                else -> when (gen.eligibility) {
                    InterventionEligibility.ELIGIBLE -> Color.parseColor("#48C774")
                    InterventionEligibility.WAIT_FOR_TRANSITION -> Color.parseColor("#FFD166")
                    InterventionEligibility.BLOCKED -> Color.parseColor("#FF6B6B")
                }
            }
        }

        if (lastAppliedStatusColor != statusColor) {
            val dp = appCtx.resources.displayMetrics.density
            collapsedBorderDrawable?.setStroke((2.5f * dp).toInt(), statusColor)
            collapsedStatusDotDrawable?.setColor(statusColor)
            lastAppliedStatusColor = statusColor
        }

        // Jev 확률 vs 우리 앱 보정 기준 확률 차이에 따른 5단계 표정 계산 (앱 내 테스트 창과 동일한 단일 기준 컨텍스트 사용)
        val jevRuntime = if (JevFeature.available()) runCatching { JevRuntime.get(appCtx) }.getOrNull() else null
        val jevEnabled = jevRuntime?.enabled == true
        val jevState = jevRuntime?.state?.value
        val jevSettings = jevRuntime?.store?.settings?.value
        val jevProb = jevState?.probability
        val evalCtx = if (jevEnabled) jevRuntime.currentEvaluationContext() else null
        val effThreshold = jevSettings?.let {
            JevCandidatePolicy.effectiveThreshold(it, evalCtx)
        }
        val liveProbabilityMood = if (jevEnabled && jevProb != null && effThreshold != null) {
            resolveHudMoodForProbabilityGap(jevProb, effThreshold)
        } else {
            null
        }
        // 기본 표정은 1단계 해맑(BRIGHT)으로 시작하고, Jev 확률이 측정되면 차이에 따라 5단계 자동 전환
        val activeMood = previewMoodOverride ?: liveProbabilityMood ?: PuppyMood.BRIGHT
        currentProbabilityMood = activeMood

        // 아이콘 우선순위: 1) 야간 수면 대기(isSleeping) -> 2) 5단계 표정(기본 BRIGHT / 실시간 Jev 차이 / 테스트)
        if (isSleeping) {
            if (lastAppliedIconKey != "SLEEP") {
                collapsedIconImageView?.setImageResource(R.drawable.itv_motion_sleep)
                miniIconImageView?.setImageResource(R.drawable.itv_motion_sleep)
                lastAppliedIconKey = "SLEEP"
            }
        } else {
            val key = "MOOD_${activeMood.name}"
            if (lastAppliedIconKey != key) {
                val faceBitmap = HudPuppyFaceRenderer.getFaceBitmap(appCtx, activeMood)
                collapsedIconImageView?.setImageBitmap(faceBitmap)
                miniIconImageView?.setImageBitmap(faceBitmap)
                lastAppliedIconKey = key
            }
        }

        // HUD가 아이콘으로 접혀 있는 상태에서도 카테고리별 누적 사용 시간은 끊기지 않고 계속 집계한다.
        val pm = cachedPowerManager ?: appCtx.getSystemService(PowerManager::class.java).also { cachedPowerManager = it }
        val km = cachedKeyguardManager ?: appCtx.getSystemService(KeyguardManager::class.java).also { cachedKeyguardManager = it }
        val isUnlockedNow = (pm?.isInteractive == true) && (km?.isKeyguardLocked != true)
        val isOwnOrSystemPkg = fgPkg != null && (
            fgPkg == appCtx.packageName ||
            fgPkg.startsWith("com.team.itv") ||
            fgPkg.startsWith("com.example.itv") ||
            JevSettings.isLauncherPackage(fgPkg) ||
            JevSettings.isExcludedSystemPackage(fgPkg)
        )
        SessionCategoryTracker.observe(
            nowElapsed = SystemClock.elapsedRealtime(),
            isUnlocked = isUnlockedNow,
            fgPkg = fgPkg,
            paused = isUnlockedNow && isOwnOrSystemPkg
        )

        // 아이콘으로 접혀 있는 상태라면 텍스트 뷰 렌더링만 생략 (배터리 절약)
        if (!isExpanded) return

        // 펼침 카드 내 Jev 확률 vs 우리 앱 기준 확률 차이 표시 갱신
        when {
            previewMoodOverride != null -> {
                val m = previewMoodOverride!!
                jevProbText.setVisibilityIfChanged(View.VISIBLE)
                jevProbText.setTextIfChanged("🐾 HUD 테스트: ${HudPuppyFaceRenderer.stageLabel(m)} (${HudPuppyFaceRenderer.gapRangeLabel(m)})")
                jevProbText.setTextColorIfChanged(HudPuppyFaceRenderer.accentColorInt(m))
            }
            jevEnabled && jevProb != null && effThreshold != null && liveProbabilityMood != null -> {
                val jevTenths = com.example.itv.domain.jev.JevPercentFormat.toTenthsPct(jevProb)
                val thrTenths = com.example.itv.domain.jev.JevPercentFormat.toTenthsPct(effThreshold)
                val gapTenths = thrTenths - jevTenths
                val jevStr = com.example.itv.domain.jev.JevPercentFormat.formatTenths(jevTenths)
                val thrStr = com.example.itv.domain.jev.JevPercentFormat.formatTenths(thrTenths)
                val gapDesc = when {
                    gapTenths > 0 -> "여유 ${com.example.itv.domain.jev.JevPercentFormat.formatTenths(gapTenths)}%p"
                    gapTenths == 0 -> "기준 도달 0%p"
                    else -> "초과 +${com.example.itv.domain.jev.JevPercentFormat.formatTenths(-gapTenths)}%p"
                }
                jevProbText.setVisibilityIfChanged(View.VISIBLE)
                jevProbText.setTextIfChanged("🐾 ${HudPuppyFaceRenderer.stageLabel(liveProbabilityMood)} · Jev $jevStr% vs 기준 $thrStr% ($gapDesc)")
                jevProbText.setTextColorIfChanged(HudPuppyFaceRenderer.accentColorInt(liveProbabilityMood))
            }
            jevEnabled && effThreshold != null -> {
                val thrStr = com.example.itv.domain.jev.JevPercentFormat.formatRatioPct(effThreshold)
                val tokenConfigured = jevRuntime.store.tokenConfigured.value
                val consentGranted = jevSettings.collectionConsent
                val liveStatus = jevState?.status.orEmpty()
                val statusHint = when {
                    !tokenConfigured -> "테스트 토큰 미등록 (Jev 테스트 > 연결에서 입력 필요)"
                    !consentGranted -> "외부 전송 동의 필요"
                    CoachingAutoStart.testModeEnabled(appCtx) -> "30초 테스트 모드 켜짐 (Jev 자동 호출 일시 중지)"
                    liveStatus.isNotBlank() && liveStatus != "Jev 꺼짐" -> liveStatus
                    else -> "첫 Jev 확률 측정 전"
                }
                val isBlockedHint = !tokenConfigured || !consentGranted || CoachingAutoStart.testModeEnabled(appCtx) ||
                    liveStatus.contains("보류")
                jevProbText.setVisibilityIfChanged(View.VISIBLE)
                jevProbText.setTextIfChanged("🐾 기준 $thrStr% · $statusHint")
                jevProbText.setTextColorIfChanged(Color.parseColor(if (isBlockedHint) "#FFD166" else "#B0BEC5"))
            }
            else -> {
                jevProbText.setVisibilityIfChanged(View.GONE)
            }
        }

        when {
            // 1. 인스타그램 전면 실행 중
            fgPkg == JevSettings.INSTAGRAM || ig.isInInstagram -> {
                val powerTag = if (ig.isPowerSaveScanMode) " · 🔋절전" else ""
                val (modeLabel, modeColor) = when (ig.mode) {
                    InstagramScreenMode.REELS -> "🎬 인스타 릴스 시청$powerTag" to Color.parseColor("#FF6B9D")
                    InstagramScreenMode.COMMENTS -> "💬 인스타 댓글 열람$powerTag" to Color.parseColor("#FFB74D")
                    InstagramScreenMode.HOME_FEED -> "🏠 인스타 홈 / 피드$powerTag" to Color.parseColor("#4DA3FF")
                    InstagramScreenMode.STORY -> "⭕ 인스타 스토리 시청$powerTag" to Color.parseColor("#E040FB")
                    InstagramScreenMode.DM -> "✉️ 인스타 DM / 메시지$powerTag" to Color.parseColor("#00E676")
                    InstagramScreenMode.EXPLORE -> "🔍 인스타 검색 / 탐색$powerTag" to Color.parseColor("#4DA3FF")
                    InstagramScreenMode.PROFILE -> "👤 인스타 프로필$powerTag" to Color.parseColor("#B39DDB")
                    else -> "📸 인스타: ${ig.mode.label}" to Color.parseColor("#AAAAAA")
                }
                modeText.setTextIfChanged(modeLabel)
                modeText.setTextColorIfChanged(modeColor)

                titleText.setTextIfChanged(when {
                    isWritingBlocked && ig.mode != InstagramScreenMode.DM ->
                        "✍️ 인스타그램에서 메시지·댓글 입력 중"
                    ig.mode == InstagramScreenMode.REELS ->
                        "릴스(Reels) 재생 중 · 스와이프 전환 ${ig.reelsSwipeCount}회"
                    ig.mode == InstagramScreenMode.COMMENTS ->
                        "인스타 댓글창 열람 중 (개입 보류)"
                    ig.mode == InstagramScreenMode.STORY ->
                        "인스타 스토리(Story) 시청 중 (개입 보류)"
                    ig.mode == InstagramScreenMode.DM ->
                        "DM 메시지/목록 화면 (메시지 보호 중)"
                    ig.mode == InstagramScreenMode.HOME_FEED ->
                        "인스타그램 홈/피드 둘러보는 중"
                    ig.mode == InstagramScreenMode.EXPLORE ->
                        "인스타그램 검색/탐색 탭 둘러보는 중"
                    ig.mode == InstagramScreenMode.PROFILE ->
                        "인스타그램 프로필 화면 확인 중"
                    else ->
                        "인스타그램 사용 중"
                })

                basisText.setTextIfChanged("🔎 구분: 인스타그램 ${ig.mode.label} (경량 뷰ID 확정)")

                val reelsStr = formatDurationSec(ig.reelsWatchDurationMs / 1000L)
                val modeStr = formatDurationSec(ig.currentModeDurationMs / 1000L)
                timerText.setTextIfChanged("⏱ 현 화면: $modeStr · 누적 릴스: $reelsStr (스와이프 ${ig.reelsSwipeCount}회)")
                timerText.setTextColorIfChanged(Color.parseColor("#E0E0E0"))
            }

            // 2. 유튜브 전면 실행 중
            fgPkg == JevSettings.YOUTUBE || (yt.mode != YoutubeWatchMode.BACKGROUND && yt.mode != YoutubeWatchMode.UNKNOWN) -> {
                val isWatching = yt.mode == YoutubeWatchMode.PORTRAIT_WATCH || yt.mode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN
                val powerTag = if (yt.isPowerSaveScanMode) " · 🔋절전" else ""
                val (modeLabel, modeColor) = when (yt.mode) {
                    YoutubeWatchMode.LANDSCAPE_FULLSCREEN -> "📺 유튜브 가로 전체화면$powerTag" to Color.parseColor("#48C774")
                    YoutubeWatchMode.PORTRAIT_WATCH -> "📱 유튜브 세로 영상 시청$powerTag" to Color.parseColor("#48C774")
                    YoutubeWatchMode.BROWSE_FEED -> "🏠 유튜브 홈 / 피드$powerTag" to Color.parseColor("#4DA3FF")
                    YoutubeWatchMode.SHORTS -> "⚡ 유튜브 쇼츠 시청" to Color.parseColor("#FF6B6B")
                    YoutubeWatchMode.SHORTS_COMMENTS -> "💬 유튜브 숏폼 댓글$powerTag" to Color.parseColor("#FFB74D")
                    else -> "📺 유튜브: ${yt.mode.label}" to Color.parseColor("#AAAAAA")
                }
                modeText.setTextIfChanged(modeLabel)
                modeText.setTextColorIfChanged(modeColor)

                val ytSwipes = maxOf(
                    SwipeDynamicsTracker.snapshot(ShortformApp.YOUTUBE).totalSwipesInRun,
                    YoutubeProbe.candidates
                )

                titleText.setTextIfChanged(when {
                    isWritingBlocked ->
                        "✍️ 유튜브에서 검색어·댓글 작성 중"
                    yt.mode == YoutubeWatchMode.PORTRAIT_WATCH || yt.mode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN ->
                        yt.currentTitle ?: "제목 인식 중… (화면 터치 시 즉시 인식)"
                    yt.mode == YoutubeWatchMode.BROWSE_FEED ->
                        "유튜브 홈/피드 둘러보는 중"
                    yt.mode == YoutubeWatchMode.SHORTS ->
                        "쇼츠(Shorts) 플레이어 재생 중 · 전환 ${ytSwipes}회"
                    yt.mode == YoutubeWatchMode.SHORTS_COMMENTS ->
                        "쇼츠(Shorts) 댓글 보는 중 (개입 보류)"
                    else ->
                        yt.currentTitle ?: "유튜브 화면 탐색 중"
                })

                val lfMetrics = YoutubeLongformTracker.getJevLongformMetrics()
                basisText.setTextIfChanged(when (yt.mode) {
                    YoutubeWatchMode.SHORTS -> "🔎 구분: 쇼츠 시청 · 스와이프 전환 ${ytSwipes}회"
                    YoutubeWatchMode.SHORTS_COMMENTS -> "🔎 구분: 쇼츠 댓글 열람 (개입 보류)"
                    YoutubeWatchMode.BROWSE_FEED -> "🔎 구분: 홈/피드 둘러보는 중 (시청 정지)"
                    else -> "🔎 구분: ${yt.mode.label} · 연속전환 ${lfMetrics.consecutiveChainCount}회 · 완료 ${lfMetrics.completedLongformCount}편"
                })

                val timeStr = formatDurationSec(yt.currentVideoWatchDurationMs / 1000L)
                val modeStr = formatDurationSec(yt.currentModeDurationMs / 1000L)
                val shortsStr = formatDurationSec(yt.shortsWatchDurationMs / 1000L)
                val seekBarInfo = if (!yt.playbackPositionText.isNullOrBlank()) " · 재생바 ${yt.playbackPositionText}" else ""

                val (_, timerLine) = when (yt.mode) {
                    YoutubeWatchMode.SHORTS -> {
                        val badge = if (yt.isPlaying) "▶ 쇼츠 재생 중" else "⏸ 일시정지(멈춤)"
                        badge to "$badge · 현 쇼츠: $modeStr · 누적 쇼츠: $shortsStr (전환 ${ytSwipes}회)"
                    }
                    YoutubeWatchMode.SHORTS_COMMENTS -> {
                        "💬 댓글 열람 중" to "💬 댓글 열람 중 · 체류: $modeStr · 누적 쇼츠: $shortsStr"
                    }
                    YoutubeWatchMode.BROWSE_FEED -> {
                        "⏹ 시청 정지" to "⏱ 홈 체류: $modeStr (시청 정지)"
                    }
                    YoutubeWatchMode.PORTRAIT_WATCH, YoutubeWatchMode.LANDSCAPE_FULLSCREEN -> {
                        val badge = when {
                            yt.isEndscreenDetected -> "🏁 영상 종료"
                            yt.isPlaying -> "▶ 재생 중"
                            else -> "⏸ 일시정지(멈춤)"
                        }
                        badge to "$badge · 실제 시청: $timeStr$seekBarInfo"
                    }
                    else -> {
                        "⏹ 관측 대기" to "⏹ 관측 대기 중"
                    }
                }
                timerText.setTextIfChanged(timerLine)
                timerText.setTextColorIfChanged(
                    when {
                        yt.isEndscreenDetected -> Color.parseColor("#FFD166")
                        yt.mode == YoutubeWatchMode.SHORTS && yt.isPlaying -> Color.parseColor("#E8F5E9")
                        isWatching && yt.isPlaying -> Color.parseColor("#E8F5E9")
                        isWatching && !yt.isPlaying -> Color.parseColor("#FFB74D")
                        yt.mode == YoutubeWatchMode.BROWSE_FEED -> Color.parseColor("#4DA3FF")
                        else -> Color.parseColor("#CCCCCC")
                    }
                )
            }

            // 3. 그 외 모든 앱 (게임, 영상/OTT, 메신저, 음성통화, 문서, 브라우저, SNS, 쇼핑, 홈 화면, 우리 앱 등)
            else -> {
                val powerTag = if (gen.isPowerSaveScanMode) " · 🔋절전" else ""
                val headerColor = when (gen.category) {
                    InferredAppCategory.VOICE_CALL -> Color.parseColor("#FF5252")
                    InferredAppCategory.WRITING -> Color.parseColor("#FF8A65")
                    InferredAppCategory.MESSENGER -> Color.parseColor("#FFD54F")
                    InferredAppCategory.DOCUMENT -> Color.parseColor("#81D4FA")
                    InferredAppCategory.VIDEO_MEDIA -> Color.parseColor("#CE93D8")
                    InferredAppCategory.GAME -> Color.parseColor("#80CBC4")
                    InferredAppCategory.ITV_OWN -> Color.parseColor("#48C774")
                    InferredAppCategory.HOME_LAUNCHER -> Color.parseColor("#B0BEC5")
                    else -> Color.parseColor("#64B5F6")
                }

                modeText.setTextIfChanged("${gen.category.badge} · ${gen.appName}$powerTag")
                modeText.setTextColorIfChanged(headerColor)

                titleText.setTextIfChanged(gen.inferredActivity)
                basisText.setTextIfChanged("🔎 확정: ${gen.classificationBasis}")

                val stayStr = formatDurationSec(gen.currentAppDurationMs / 1000L)
                val audioTag = when {
                    gen.isMutedPlayback -> "${gen.audioPipeline.label}(🔇무음)"
                    gen.audioPipeline != AudioPipelineKind.NONE -> gen.audioPipeline.label
                    else -> "오디오 없음"
                }
                val kbOrScreenTag = when {
                    gen.isKeyboardOrInputActive -> "⌨️입력 활성"
                    gen.category == InferredAppCategory.GAME ->
                        "무조작 ${gen.idleSeconds}초(10초간 ${gen.recentInteractions10s}회 터치) · ${gen.inferredScreen}"
                    else -> gen.inferredScreen
                }
                timerText.setTextIfChanged("⏱ 체류: $stayStr · $audioTag · $kbOrScreenTag")
                timerText.setTextColorIfChanged(Color.parseColor("#DDDDDD"))
            }
        }

        // 2-5. 앱 카테고리별 누적 사용량 및 세션 총 사용시간 표시 갱신 (집계는 접힘/펼침 무관하게 상단에서 수행됨)
        val activeCats = SessionCategoryTracker.getActiveCategories()
        val totalPhysMs = SessionCategoryTracker.getTotalPhysicalMs()
        val totalWeightMs = SessionCategoryTracker.getTotalWeightedMs()

        categoryUsageText.setTextIfChanged(if (activeCats.isNotEmpty()) {
            "📊 카테고리: " + activeCats.joinToString(" · ") { (cat, ms) ->
                "${cat.shortLabel} ${formatDurationSec(ms / 1000L)}"
            }
        } else {
            "📊 카테고리: 집계 대기 중"
        })

        val physStr = formatDurationSec(totalPhysMs / 1000L)
        val weightStr = formatDurationSec(totalWeightMs / 1000L)
        totalUsageText.setTextIfChanged("⏱ 총 사용: $physStr (환산 $weightStr · 세션)")
    }

    fun hide() {
        scope?.cancel()
        scope = null
        val wm = windowManager
        val v = rootContainer
        if (wm != null && v != null) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {}
        }
        rootContainer = null
        collapsedView = null
        collapsedBorderDrawable = null
        collapsedStatusDotDrawable = null
        collapsedIconImageView = null
        miniIconImageView = null
        defaultAppIconDrawable = null
        lastAppliedIconKey = null
        lastAppliedStatusColor = null
        lastOutsideObserveElapsed = 0L
        outsideTouchWatchEnabled = true
        cachedPowerManager = null
        cachedKeyguardManager = null
        currentProbabilityMood = null
        previewMoodOverride = null
        isSleeping = false
        expandedView = null
        layoutParams = null
        windowManager = null
        modeText = null
        titleText = null
        basisText = null
        timerText = null
        categoryUsageText = null
        totalUsageText = null
        jevProbText = null
        isExpanded = false
        isShowing = false
    }
}
