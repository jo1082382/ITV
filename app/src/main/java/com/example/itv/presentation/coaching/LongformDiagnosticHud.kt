package com.example.itv.presentation.coaching

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.itv.R
import com.example.itv.domain.coaching.UserPosture
import com.example.itv.domain.jev.JevSettings
import com.example.itv.infrastructure.jev.JevObservationHub
import com.example.itv.infrastructure.observation.AudioPipelineKind
import com.example.itv.infrastructure.observation.GeneralAppTracker
import com.example.itv.infrastructure.observation.InferredAppCategory
import com.example.itv.infrastructure.observation.InterventionEligibility
import com.example.itv.infrastructure.observation.LightAndBrightnessTracker
import com.example.itv.infrastructure.observation.accessibility.WritingGuard
import com.example.itv.infrastructure.observation.instagram.InstagramScreenMode
import com.example.itv.infrastructure.observation.instagram.InstagramTracker
import com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker
import com.example.itv.infrastructure.observation.youtube.YoutubeWatchMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * YouTube, Instagram 및 휴대폰의 모든 앱 화면 위에서 실시간 감지 상태
 * (앱 카테고리 확정, 게임/영상/음성 오디오 파이프라인, 앱 내 세부 화면, 시청/체류 시간, 개입 가능/불가 환경 판정)를
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

    private var modeText: TextView? = null
    private var titleText: TextView? = null
    private var basisText: TextView? = null
    private var timerText: TextView? = null
    private var eligibilityText: TextView? = null
    private var eventText: TextView? = null

    var isShowing: Boolean = false
        private set

    var isExpanded: Boolean = false
        private set

    private fun formatDurationSec(sec: Long): String =
        if (sec >= 60) "${sec / 60}분 ${sec % 60}초" else "${sec}초"

    private fun shortPostureLabel(posture: UserPosture): String = when (posture) {
        UserPosture.SITTING_OR_STANDING -> "정면파지"
        UserPosture.LYING_DOWN -> "누움/수평"
        UserPosture.TABLE_OR_DESK -> "책상거치"
        UserPosture.FACEDOWN -> "엎어둠"
        UserPosture.UNKNOWN -> "측정중"
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun show(context: Context): Boolean {
        if (isShowing) return true
        if (!Settings.canDrawOverlays(context)) return false

        val appCtx = context.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        windowManager = wm

        val dp = appCtx.resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()

        val root = FrameLayout(appCtx).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        // =========================================================================
        // 1. 기본 축소 모드 뷰: 우리 앱(ITV) 아이콘 + 상태 테두리 링 + 우하단 상태 점
        // =========================================================================
        val iconCircleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#F21E2226"))
            setStroke(2.dp(), Color.parseColor("#48C774"))
        }
        collapsedBorderDrawable = iconCircleBg

        val iconBox = FrameLayout(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(52.dp(), 52.dp())
            background = iconCircleBg
            elevation = 10f * dp
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        }

        val appIconDrawable = runCatching {
            appCtx.packageManager.getApplicationIcon(appCtx.packageName)
        }.getOrNull()

        val iconImage = ImageView(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (appIconDrawable != null) {
                setImageDrawable(appIconDrawable)
            } else {
                setImageResource(R.drawable.itv_puppy_face)
            }
        }
        iconBox.addView(iconImage)

        val statusDotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#48C774"))
            setStroke(1.dp(), Color.parseColor("#16181A"))
        }
        collapsedStatusDotDrawable = statusDotBg

        val statusDot = View(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(12.dp(), 12.dp(), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = 1.dp()
                bottomMargin = 1.dp()
            }
            background = statusDotBg
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
            layoutParams = LinearLayout.LayoutParams(18.dp(), 18.dp()).apply {
                rightMargin = 6.dp()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (appIconDrawable != null) {
                setImageDrawable(appIconDrawable)
            } else {
                setImageResource(R.drawable.itv_puppy_face)
            }
        }

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

        // 2-5. 개입 가능 환경 vs 개입 불가 환경 + 센서(자세/조도) 판정 줄
        eligibilityText = TextView(appCtx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#48C774"))
            text = "개입 환경 판정 대기 중"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 1.dp(), 0, 2.dp())
        }
        card.addView(eligibilityText)

        // 2-6. 최근 전환 이벤트 한 줄 요약
        eventText = TextView(appCtx).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#99E6B8"))
            text = "최근 이벤트: 없음"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        card.addView(eventText)

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
                    // HUD 바깥 영역(게임 화면, 영상, 일반 앱 화면)을 사용자가 터치함!
                    // 게임에 터치 이벤트를 전혀 방해하지 않으면서 O(1)로 조작 발생을 즉시 기록하고 HUD 색상을 즉시 갱신
                    GeneralAppTracker.onUserInteraction()
                    val fg = JevObservationHub.foregroundPackage
                    if (!fg.isNullOrBlank()) {
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
                        params.x = (initialX + dx).coerceIn(0, (metrics.widthPixels - viewW).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(0, (metrics.heightPixels - viewH).coerceAtLeast(0))
                        try {
                            wm.updateViewLayout(root, params)
                        } catch (_: Exception) {}
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

        // 가벼운 UI 갱신 루프 (이미 수집된 메모리 내 StateFlow 값만 읽어 표시하므로 추가 배터리 소모 0)
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope?.launch {
            while (isActive) {
                renderHud(appCtx)
                // 아이콘으로 접혀 있을 때는 갱신 주기를 800ms로 늦춰 더욱 가볍게 유지
                delay(if (isExpanded) 400L else 800L)
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
            renderHud(appCtx)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderHud(appCtx: Context) {
        val fgPkg = JevObservationHub.foregroundPackage
        val yt = YoutubeLongformTracker.snapshot.value
        val ytEvent = YoutubeLongformTracker.history.value.firstOrNull()
        val ig = InstagramTracker.snapshot.value
        val igEvent = InstagramTracker.history.value.firstOrNull()
        val gen = GeneralAppTracker.snapshot.value
        val genEvent = GeneralAppTracker.history.value.firstOrNull()
        val guardReason = WritingGuard.blockReason()
        val isWritingBlocked = guardReason != null && !guardReason.startsWith("ITV 내부")

        // 먼저 현재 상태의 대표 색상(개입 가능=초록, 전환 대기=노랑, 개입 불가=빨강) 계산 -> 작은 아이콘 테두리/점에도 반영
        val statusColor = when {
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

        val dp = appCtx.resources.displayMetrics.density
        collapsedBorderDrawable?.setStroke((2f * dp).toInt(), statusColor)
        collapsedStatusDotDrawable?.setColor(statusColor)

        // 아이콘으로 접혀 있는 상태라면 텍스트 뷰 렌더링 생략 (배터리 절약)
        if (!isExpanded) return

        val ambient = try {
            LightAndBrightnessTracker.getInstance(appCtx).snapshot.value
        } catch (_: Exception) {
            null
        }
        val sensorSuffix = if (ambient != null) {
            val postureStr = shortPostureLabel(ambient.posture)
            val luxStr = ambient.lux?.toInt()?.let { "${it}lx" } ?: ""
            if (luxStr.isNotEmpty()) " · $postureStr/$luxStr" else " · $postureStr"
        } else ""

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
                modeText?.text = modeLabel
                modeText?.setTextColor(modeColor)

                titleText?.text = when {
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
                }

                basisText?.text = "🔎 구분: 인스타그램 ${ig.mode.label} (경량 뷰ID 확정)"

                val reelsStr = formatDurationSec(ig.reelsWatchDurationMs / 1000L)
                val modeStr = formatDurationSec(ig.currentModeDurationMs / 1000L)
                timerText?.text = "⏱ 현 화면: $modeStr · 누적 릴스: $reelsStr (스와이프 ${ig.reelsSwipeCount}회)"
                timerText?.setTextColor(Color.parseColor("#E0E0E0"))

                val (eligLine, eligColor) = when {
                    ig.mode == InstagramScreenMode.DM || isWritingBlocked ->
                        "⛔ 개입 불가 (DM·메시지 작성 보호)$sensorSuffix" to Color.parseColor("#FF6B6B")
                    ig.mode == InstagramScreenMode.REELS ->
                        "⏳ 개입 대기 (릴스 시청 중 → 다음 전환 시 개입)$sensorSuffix" to Color.parseColor("#FFD166")
                    ig.mode == InstagramScreenMode.COMMENTS ->
                        "⏳ 개입 대기 (댓글 열람 중 → 전환/피드 이동 시 개입)$sensorSuffix" to Color.parseColor("#FFD166")
                    ig.mode == InstagramScreenMode.STORY ->
                        "⏳ 개입 대기 (스토리 시청 중 → 피드 복귀 시 개입)$sensorSuffix" to Color.parseColor("#FFD166")
                    else ->
                        "✅ 개입 가능 환경 (인스타 피드/탐색 중)$sensorSuffix" to Color.parseColor("#48C774")
                }
                eligibilityText?.text = eligLine
                eligibilityText?.setTextColor(eligColor)

                eventText?.text = if (igEvent != null) {
                    "최근[${igEvent.formattedTime}]: ${igEvent.description}"
                } else {
                    "최근 이벤트: 아직 없음"
                }
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
                modeText?.text = modeLabel
                modeText?.setTextColor(modeColor)

                titleText?.text = when {
                    isWritingBlocked ->
                        "✍️ 유튜브에서 검색어·댓글 작성 중"
                    yt.mode == YoutubeWatchMode.PORTRAIT_WATCH || yt.mode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN ->
                        yt.currentTitle ?: "제목 인식 중… (화면 터치 시 즉시 인식)"
                    yt.mode == YoutubeWatchMode.BROWSE_FEED ->
                        "유튜브 홈/피드 둘러보는 중"
                    yt.mode == YoutubeWatchMode.SHORTS ->
                        "쇼츠(Shorts) 플레이어 재생 중"
                    yt.mode == YoutubeWatchMode.SHORTS_COMMENTS ->
                        "쇼츠(Shorts) 댓글 보는 중"
                    else ->
                        yt.currentTitle ?: "유튜브 화면 탐색 중"
                }

                val lfMetrics = YoutubeLongformTracker.getJevLongformMetrics()
                basisText?.text = "🔎 구분: ${yt.mode.label} · 연속전환 ${lfMetrics.consecutiveChainCount}회 · 완료 ${lfMetrics.completedLongformCount}편"

                val timeStr = formatDurationSec(yt.currentVideoWatchDurationMs / 1000L)
                val playBadge = when {
                    yt.isEndscreenDetected -> "🏁 영상 종료"
                    yt.mode == YoutubeWatchMode.SHORTS_COMMENTS -> "💬 댓글 열람 중"
                    !isWatching -> "⏹ 시청 정지"
                    yt.isPlaying -> "▶ 재생 중"
                    else -> "⏸ 일시정지(멈춤)"
                }
                val seekBarInfo = if (!yt.playbackPositionText.isNullOrBlank()) " · 재생바 ${yt.playbackPositionText}" else ""
                timerText?.text = "$playBadge · 실제 시청: $timeStr$seekBarInfo"
                timerText?.setTextColor(
                    when {
                        yt.isEndscreenDetected -> Color.parseColor("#FFD166")
                        isWatching && yt.isPlaying -> Color.parseColor("#E8F5E9")
                        isWatching && !yt.isPlaying -> Color.parseColor("#FFB74D")
                        else -> Color.parseColor("#CCCCCC")
                    }
                )

                val (eligLine, eligColor) = when {
                    isWritingBlocked ->
                        "⛔ 개입 불가 (검색어·댓글 작성 중)$sensorSuffix" to Color.parseColor("#FF6B6B")
                    yt.isEndscreenDetected ->
                        "✅ 개입 가능 타이밍! (영상 종료 · 추천 화면)$sensorSuffix" to Color.parseColor("#48C774")
                    isWatching ->
                        "⏳ 개입 대기 (롱폼 시청 중 → 종료/전환/홈 이동 시 개입)$sensorSuffix" to Color.parseColor("#FFD166")
                    yt.mode == YoutubeWatchMode.SHORTS || yt.mode == YoutubeWatchMode.SHORTS_COMMENTS ->
                        "⏳ 개입 대기 (쇼츠/댓글 중 → 다음 전환 시 개입)$sensorSuffix" to Color.parseColor("#FFD166")
                    else ->
                        "✅ 개입 가능 환경 (유튜브 홈/피드 탐색 중)$sensorSuffix" to Color.parseColor("#48C774")
                }
                eligibilityText?.text = eligLine
                eligibilityText?.setTextColor(eligColor)

                eventText?.text = if (ytEvent != null) {
                    "최근[${ytEvent.formattedTime}]: ${ytEvent.description}"
                } else {
                    "최근 이벤트: 아직 없음"
                }
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

                modeText?.text = "${gen.category.badge} · ${gen.appName}$powerTag"
                modeText?.setTextColor(headerColor)

                titleText?.text = gen.inferredActivity
                basisText?.text = "🔎 확정: ${gen.classificationBasis}"

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
                timerText?.text = "⏱ 체류: $stayStr · $audioTag · $kbOrScreenTag"
                timerText?.setTextColor(Color.parseColor("#DDDDDD"))

                val (eligLine, eligColor) = when (gen.eligibility) {
                    InterventionEligibility.ELIGIBLE ->
                        "✅ ${gen.eligibilityReason}$sensorSuffix" to Color.parseColor("#48C774")
                    InterventionEligibility.WAIT_FOR_TRANSITION ->
                        "⏳ 개입 대기 (${gen.eligibilityReason})$sensorSuffix" to Color.parseColor("#FFD166")
                    InterventionEligibility.BLOCKED ->
                        "⛔ 개입 불가 (${gen.eligibilityReason})$sensorSuffix" to Color.parseColor("#FF6B6B")
                }
                eligibilityText?.text = eligLine
                eligibilityText?.setTextColor(eligColor)

                val latestAny = listOfNotNull(
                    genEvent?.let { it.timestampWallMs to "최근[${it.formattedTime}]: ${it.description}" },
                    ytEvent?.let { it.timestampWallMs to "최근[${it.formattedTime}]: ${it.description}" },
                    igEvent?.let { it.timestampWallMs to "최근[${it.formattedTime}]: ${it.description}" }
                ).maxByOrNull { it.first }?.second

                eventText?.text = latestAny ?: "최근 이벤트: 아직 없음"
            }
        }
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
        expandedView = null
        layoutParams = null
        windowManager = null
        modeText = null
        titleText = null
        basisText = null
        timerText = null
        eligibilityText = null
        eventText = null
        isExpanded = false
        isShowing = false
    }
}
