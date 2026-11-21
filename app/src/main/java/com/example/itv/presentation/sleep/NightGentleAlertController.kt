package com.example.itv.presentation.sleep

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.itv.R
import com.example.itv.presentation.coaching.LongformDiagnosticHud

/**
 * 게임 또는 롱폼 시청 중 개입 대기(유예) 상태에서 야간 연속 사용 예산(정규 30분, 테스트 30초)이
 * 초과되었을 때 화면 터치를 전혀 방해하지 않고 최상단에 2~3초간 나타났다 사라지는 비침해 배너 알림 컨트롤러.
 *
 * 1. WindowManager FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE 로 게임 터치 100% 관통.
 * 2. HUD가 활성화되어 있다면 HUD 강아지 아이콘을 수면(눈감은 강아지) 모드로 전환.
 * 3. HUD가 없다면 최상단 얇은 배너만 띄우고 자동 소멸.
 */
@SuppressLint("StaticFieldLeak")
object NightGentleAlertController {
    private var windowManager: WindowManager? = null
    private var bannerView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    var isBannerShowing: Boolean = false
        private set

    @SuppressLint("SetTextI18n")
    fun showGentleBanner(
        context: Context,
        isTestMode: Boolean,
        onDismissed: (() -> Unit)? = null
    ): Boolean {
        val appCtx = context.applicationContext
        if (!Settings.canDrawOverlays(appCtx)) return false

        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null

        val wm = (appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return false
        windowManager = wm

        bannerView?.let { old ->
            runCatching { wm.removeView(old) }
            bannerView = null
        }

        // 1. HUD가 표시 중이라면 강아지 얼굴을 눈 감은 수면 모드로 전환
        if (LongformDiagnosticHud.isShowing) {
            LongformDiagnosticHud.setSleepingState(true)
        }

        val dp = appCtx.resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()

        // 2. 얇은 최상단 티커/캡슐 배너 뷰 구성 (터치 관통)
        val bannerContainer = FrameLayout(appCtx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }

        val pillLayout = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 8.dp(), 16.dp(), 8.dp())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F214171E"))
                cornerRadius = 20f * dp
                setStroke(1.dp(), Color.parseColor("#77B197FC"))
            }
            elevation = 10f * dp
        }

        // 아이콘: 눈 감은 강아지
        val sleepIcon = ImageView(appCtx).apply {
            layoutParams = LinearLayout.LayoutParams(22.dp(), 22.dp()).apply {
                rightMargin = 8.dp()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.itv_motion_sleep)
        }
        pillLayout.addView(sleepIcon)

        // 안내 문구: 테스트 환경(30초) vs 정규 환경(30분)
        val bannerText = TextView(appCtx).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F3F5"))
            text = if (isTestMode) {
                "🌙 취침 시간 30초 경과 · 이번 판/영상 끝나면 쉬어볼까요?"
            } else {
                "🌙 취침 전 30분 연속 이용 중 · 이번 판/영상 끝나면 쉬어볼까요?"
            }
        }
        pillLayout.addView(bannerText)
        bannerContainer.addView(pillLayout)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 28.dp() // 상태바 아래 최상단 여백
        }

        return try {
            bannerContainer.alpha = 0f
            bannerContainer.translationY = -35f * dp
            wm.addView(bannerContainer, layoutParams)
            bannerView = bannerContainer
            isBannerShowing = true

            // 등장 애니메이션 (Fade-in + Slide-down)
            bannerContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280L)
                .setInterpolator(DecelerateInterpolator())
                .start()

            // 2.8초간 표시 후 부드럽게 Fade-out 및 자동 제거
            val runnable = Runnable {
                bannerContainer.animate()
                    .alpha(0f)
                    .translationY(-30f * dp)
                    .setDuration(250L)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            runCatching {
                                if (bannerContainer.isAttachedToWindow) {
                                    wm.removeView(bannerContainer)
                                }
                            }
                            if (bannerView == bannerContainer) {
                                bannerView = null
                                isBannerShowing = false
                            }
                            onDismissed?.invoke()
                        }
                    })
                    .start()
            }
            dismissRunnable = runnable
            mainHandler.postDelayed(runnable, 2800L)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            isBannerShowing = false
            false
        }
    }

    fun dismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        val view = bannerView ?: return
        val wm = windowManager ?: return
        runCatching {
            if (view.isAttachedToWindow) {
                wm.removeView(view)
            }
        }
        bannerView = null
        isBannerShowing = false
    }
}
