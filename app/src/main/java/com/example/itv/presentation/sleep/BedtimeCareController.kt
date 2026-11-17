package com.example.itv.presentation.sleep

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.itv.R
import com.example.itv.infrastructure.observation.LightAndBrightnessTracker
import com.example.itv.infrastructure.observation.accessibility.WritingGuardService
import com.example.itv.infrastructure.sleep.AsmrAudioPlayer
import com.example.itv.infrastructure.sleep.AsmrType
import com.example.itv.infrastructure.sleep.BedtimeMemoStore
import com.example.itv.infrastructure.sleep.GrayscaleOverlayController
import com.example.itv.presentation.coaching.EquippedDogView

private val frontGallopFrames = intArrayOf(
    R.drawable.itv_gallop_front_01,
    R.drawable.itv_gallop_front_02,
    R.drawable.itv_gallop_front_03,
    R.drawable.itv_gallop_front_04,
    R.drawable.itv_gallop_front_05,
    R.drawable.itv_gallop_front_06
)

/**
 * 큰 말풍선 전용 커스텀 드로어블:
 * 둥근 사각형 본체와 하단 중앙에 강아지를 가리키는 뾰족한 말풍선 포인터(Tail)를 함께 렌더링합니다.
 */
private class BedtimeSpeechBubbleDrawable(
    private val bgColor: Int,
    private val strokeColor: Int,
    private val cornerRadius: Float,
    private val strokeWidth: Float,
    private val pointerWidth: Float,
    private val pointerHeight: Float
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bgColor
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        this.strokeWidth = this@BedtimeSpeechBubbleDrawable.strokeWidth
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val bodyBottom = h - pointerHeight - strokeWidth / 2f
        val rectTop = strokeWidth / 2f
        val rectLeft = strokeWidth / 2f
        val rectRight = w - strokeWidth / 2f

        val path = Path().apply {
            val r = cornerRadius
            moveTo(rectLeft + r, rectTop)
            lineTo(rectRight - r, rectTop)
            quadTo(rectRight, rectTop, rectRight, rectTop + r)
            lineTo(rectRight, bodyBottom - r)
            quadTo(rectRight, bodyBottom, rectRight - r, bodyBottom)

            val centerX = w / 2f
            val halfPtr = pointerWidth / 2f
            lineTo(centerX + halfPtr, bodyBottom)
            lineTo(centerX, h - strokeWidth / 2f)
            lineTo(centerX - halfPtr, bodyBottom)

            lineTo(rectLeft + r, bodyBottom)
            quadTo(rectLeft, bodyBottom, rectLeft, bodyBottom - r)
            lineTo(rectLeft, rectTop + r)
            quadTo(rectLeft, rectTop, rectLeft + r, rectTop)
            close()
        }

        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Bedtime Care Interventions:
 * 1. Choice triage dialog (3 options)
 * 2. Cognitive offloading notepad + screen off
 * 3. Grayscale mode with ambient light recovery
 * 4. Sound-only ASMR player
 * 5. Morning wake-up to-do briefing card
 */
class BedtimeCareController private constructor(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val memoStore = BedtimeMemoStore.getInstance(context)
    private val grayscaleController = GrayscaleOverlayController.getInstance(context)
    private val asmrPlayer = AsmrAudioPlayer.getInstance(context)
    private val lightTracker = LightAndBrightnessTracker.getInstance(context)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recoveryRunnable: Runnable? = null
    private var isObservingLightForRecovery = false

    private var currentDialogView: View? = null
    private var morningBriefingView: View? = null
    private var currentAnimator: ValueAnimator? = null

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun roundedDrawable(bgColor: Int, cornerRadiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidthDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerRadiusDp).toFloat()
            setColor(bgColor)
            if (strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }

    private fun overlayParams(focusable: Boolean = true): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (focusable) {
                dimAmount = 0.72f
            }
        }
    }

    fun dismissCurrentDialog() {
        currentAnimator?.cancel()
        currentAnimator = null
        currentDialogView?.let { v ->
            val parent = v.parent
            if (parent is ViewGroup) {
                parent.removeView(v)
            } else {
                try { wm.removeView(v) } catch (_: Exception) {}
            }
            currentDialogView = null
        }
    }

    fun dismissMorningBriefing() {
        morningBriefingView?.let { v ->
            val parent = v.parent
            if (parent is ViewGroup) {
                parent.removeView(v)
            } else {
                try { wm.removeView(v) } catch (_: Exception) {}
            }
            morningBriefingView = null
        }
    }

    /**
     * Shows the 3-choice Bedtime Care Dialog.
     * 야간 3분기: 강아지가 평소보다 천천히 조심스럽게 다가와 바닥에 눕고,
     * 주변 배경이 서서히 어두워지며 강아지가 강조됩니다.
     * 강아지를 터치하면 서서히 눈을 뜨며 큰 말풍선 안에 선택지 창이 나타납니다.
     */
    fun showBedtimeCareDialog(containerHost: FrameLayout? = null, onChoiceDone: (() -> Unit)? = null) {
        dismissCurrentDialog()

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
        }

        // 1. 점진적으로 어두워지는 밤하늘 딤 뷰 (주변 배경 서서히 암전)
        val dimView = View(context).apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(dimView)

        // 2. 바닥에 누운 강아지를 은은하게 비추는 원형 달빛 스포트라이트
        val spotlightView = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(110).toFloat()
                colors = intArrayOf(Color.parseColor("#4D38BDF8"), Color.TRANSPARENT)
            }
            alpha = 0f
        }
        root.addView(spotlightView)

        // 3. 조심스럽게 다가올 강아지 뷰 (EquippedDogView: 코디 아이템 및 프레임 지원)
        val dogView = EquippedDogView(context).apply {
            setImageResource(frontGallopFrames[0])
            isClickable = false
            isFocusable = true
        }
        root.addView(dogView)

        // 4. 자고 있는 강아지 위 안내 힌트 텍스트 칩
        val dogHint = TextView(context).apply {
            text = "토닥토닥 터치해 보세요"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            background = roundedDrawable(Color.parseColor("#4D1E293B"), 14, Color.parseColor("#475569"), 1)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            gravity = Gravity.CENTER
            alpha = 0f
            visibility = View.GONE
        }
        root.addView(dogHint)

        // 5. 터치 시 나타날 큰 말풍선 창 (Large Speech Bubble)
        val bubbleCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = BedtimeSpeechBubbleDrawable(
                bgColor = Color.parseColor("#1E293B"),
                strokeColor = Color.parseColor("#334155"),
                cornerRadius = dp(22).toFloat(),
                strokeWidth = dp(2).toFloat(),
                pointerWidth = dp(24).toFloat(),
                pointerHeight = dp(14).toFloat()
            )
            visibility = View.GONE
            alpha = 0f
        }

        // 말풍선 내부 스크롤 뷰 (내용이 말풍선 경계 밖으로 벗어나지 않도록 완벽 보장)
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        val bubbleContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18) + dp(14)) // 포인터 높이만큼 바닥 여백 확보
        }

        // 헤더: 타이틀과 우상단 ✕ 닫기 버튼
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(context).apply {
            text = "편안한 수면 준비 시간"
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtnTop = TextView(context).apply {
            text = "✕"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(8), dp(4), dp(4), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissCurrentDialog()
                onChoiceDone?.invoke()
            }
        }
        titleRow.addView(titleText)
        titleRow.addView(closeBtnTop)
        bubbleContent.addView(titleRow)

        val desc = TextView(context).apply {
            text = "지금 숏폼을 보시는 이유에 맞춰 편안한 밤을 도와드릴게요."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(8), 0, dp(14))
        }
        bubbleContent.addView(desc)

        // 이모티콘 없이 정돈된 선택지 버튼 생성 헬퍼
        fun createChoiceButton(mainTitle: String, subTitle: String, onClick: () -> Unit): View {
            val btnLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(Color.parseColor("#0F172A"), 14, Color.parseColor("#334155"), 1)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            val mainView = TextView(context).apply {
                text = mainTitle
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#F8FAFC"))
            }
            val subView = TextView(context).apply {
                text = subTitle
                textSize = 11.5f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(3), 0, 0)
            }
            btnLayout.addView(mainView)
            btnLayout.addView(subView)
            return btnLayout
        }

        // 선택지 1: 불안과 잡념 비우기 (이모티콘 제거)
        val choice1 = createChoiceButton("불안과 잡념 비우기", "내일 할 일 2~3가지를 적고 머리를 비워요") {
            dismissCurrentDialog()
            showCognitiveOffloadingNotepad(containerHost, onChoiceDone)
        }
        bubbleContent.addView(choice1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        // 선택지 2: 흑백 화면으로 자극 줄이기 (이모티콘 제거)
        val choice2 = createChoiceButton("흑백 화면으로 자극 줄이기", "화면의 색을 빼 흑백으로 낮추고 자극을 줄여요") {
            dismissCurrentDialog()
            startGrayscaleMode(containerHost, forceManual = false)
            onChoiceDone?.invoke()
        }
        bubbleContent.addView(choice2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        // 선택지 3: 편안한 수면 사운드 듣기 (이모티콘 제거)
        val choice3 = createChoiceButton("편안한 수면 사운드 듣기", "시각 자극을 끄고 편안한 ASMR을 들어요") {
            dismissCurrentDialog()
            showAsmrSelectorDialog(containerHost, onChoiceDone)
        }
        bubbleContent.addView(choice3, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // 하단 닫기 버튼
        val cancelBtn = Button(context).apply {
            text = "그냥 조금만 더 볼래요 (닫기)"
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            background = roundedDrawable(Color.parseColor("#0F172A"), 12, Color.parseColor("#334155"), 1)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissCurrentDialog()
                onChoiceDone?.invoke()
            }
        }
        bubbleContent.addView(cancelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        scrollView.addView(bubbleContent)
        bubbleCard.addView(scrollView)
        root.addView(bubbleCard)

        if (containerHost != null) {
            containerHost.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            currentDialogView = root
        } else {
            try {
                wm.addView(root, overlayParams(true).apply { dimAmount = 0f })
                currentDialogView = root
            } catch (_: Exception) {}
        }

        root.post {
            if (root.parent == null) return@post
            val w = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            val h = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels

            val targetDogSize = dp(120)
            val startY = dp(40)
            val endY = h - targetDogSize - dp(45)

            dogView.layoutParams = FrameLayout.LayoutParams(targetDogSize, targetDogSize)
            dogView.translationX = (w - targetDogSize) / 2f
            dogView.translationY = startY.toFloat()
            dogView.scaleX = 0.5f
            dogView.scaleY = 0.5f

            spotlightView.layoutParams = FrameLayout.LayoutParams(dp(220), dp(130))
            spotlightView.translationX = (w - dp(220)) / 2f
            spotlightView.translationY = (endY - dp(5)).toFloat()

            dogHint.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val hintW = dogHint.measuredWidth
            dogHint.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            dogHint.translationX = (w - hintW) / 2f
            dogHint.translationY = (endY - dp(32)).toFloat()

            val bubbleW = minOf(dp(330), w - dp(32))
            bubbleCard.layoutParams = FrameLayout.LayoutParams(bubbleW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (h - endY) + dp(4)
            }

            var arrived = false

            // 평소보다 더 천천히, 조심스럽게 다가오는 애니메이션 (3200ms)
            currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3200L
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { anim ->
                    if (root.parent == null) { anim.cancel(); return@addUpdateListener }
                    val f = anim.animatedValue as Float

                    // 주변 배경이 서서히 어두워짐
                    dimView.alpha = f * 0.88f

                    // 강아지가 서서히 커지며 조심스럽게 바닥으로 다가옴
                    dogView.scaleX = 0.5f + 0.5f * f
                    dogView.scaleY = 0.5f + 0.5f * f
                    dogView.translationY = startY + (endY - startY) * f

                    if (f < 0.96f) {
                        val frame = frontGallopFrames[((anim.currentPlayTime / 130L) % frontGallopFrames.size).toInt()]
                        dogView.setImageResource(frame)
                    } else if (!arrived) {
                        arrived = true
                        dogView.scaleX = 1f
                        dogView.scaleY = 1f
                        // 바닥에 조용히 눕기
                        dogView.setImageResource(R.drawable.itv_motion_sleep)
                        spotlightView.animate().alpha(0.35f).setDuration(600).start()
                        dogHint.visibility = View.VISIBLE
                        dogHint.animate().alpha(1f).setDuration(600).start()
                        dogView.isClickable = true
                    }
                }
                start()
            }

            // 바닥에 누운 강아지 터치 이벤트
            dogView.setOnClickListener {
                if (!arrived) return@setOnClickListener
                dogView.isClickable = false
                dogHint.animate().alpha(0f).setDuration(200).withEndAction { dogHint.visibility = View.GONE }.start()

                // 캐릭터는 계속 평화롭게 수면 상태 유지 (itv_motion_sleep)
                // 터치 시 편안하게 숨을 고르는 부드러운 반응만 연출
                dogView.setImageResource(R.drawable.itv_motion_sleep)
                dogView.animate().scaleX(1.04f).scaleY(1.04f).setDuration(300).withEndAction {
                    dogView.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
                }.start()

                // 큰 말풍선 창 팝업 등장 (말풍선 밖으로 벗어나지 않도록 완벽 패딩)
                bubbleCard.visibility = View.VISIBLE
                bubbleCard.alpha = 0f
                bubbleCard.scaleX = 0.85f
                bubbleCard.scaleY = 0.85f
                bubbleCard.translationY = dp(20).toFloat()
                bubbleCard.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(400)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.05f))
                    .start()
            }
        }
    }

    /**
     * 1. Cognitive Offloading Notepad:
     * User jots down tomorrow's tasks or thoughts.
     * On clicking "Done", writes to store, shows warm puppy message, and locks/turns off the screen.
     */
    fun showCognitiveOffloadingNotepad(containerHost: FrameLayout? = null, onComplete: (() -> Unit)? = null) {
        dismissCurrentDialog()

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#CC0B0F17"))
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(Color.parseColor("#1E293B"), 20, Color.parseColor("#3B82F6"), 1)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            val lp = FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
        }

        val headerText = TextView(context).apply {
            text = "📝 내일 할 일 & 머릿속 생각 털어내기"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val guideText = TextView(context).apply {
            text = "머릿속에서 맴도는 할 일 2~3가지를 가볍게 적어보세요.\n적어둔 내용은 강아지가 안전하게 보관했다가,\n내일 아침 기상 시 첫 화면으로 알려줄게요!"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(8), 0, dp(12))
        }
        card.addView(headerText)
        card.addView(guideText)

        val editText = EditText(context).apply {
            hint = "예: 1. 내일 아침 보고서 확인\n2. 퇴근길 세탁물 찾기\n3. 친구에게 답장 보내기"
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            minLines = 4
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            background = roundedDrawable(Color.parseColor("#0F172A"), 12, Color.parseColor("#334155"), 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(editText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelBtn = Button(context).apply {
            text = "취소"
            setTextColor(Color.parseColor("#94A3B8"))
            background = roundedDrawable(Color.TRANSPARENT, 10)
            setOnClickListener {
                dismissCurrentDialog()
                onComplete?.invoke()
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(dp(70), dp(44)).apply {
            marginEnd = dp(8)
        })

        val submitBtn = Button(context).apply {
            text = "정리 완료 & 화면 끄기"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedDrawable(Color.parseColor("#2563EB"), 10)
            setOnClickListener {
                val content = editText.text.toString().trim()
                if (content.isEmpty()) {
                    Toast.makeText(context, "털어놓을 내용을 1줄 이상 적어주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                memoStore.saveMemo(content)
                dismissCurrentDialog()
                showComfortAndLockScreen(containerHost, onComplete)
            }
        }
        btnRow.addView(submitBtn, LinearLayout.LayoutParams(dp(180), dp(44)))
        card.addView(btnRow)

        root.addView(card)

        if (containerHost != null) {
            containerHost.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            currentDialogView = root
        } else {
            try {
                wm.addView(root, overlayParams(true))
                currentDialogView = root
            } catch (_: Exception) {}
        }
    }

    private fun showComfortAndLockScreen(containerHost: FrameLayout? = null, onComplete: (() -> Unit)? = null) {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#F0000000"))
            isClickable = true
            isFocusable = true
        }

        val comfortCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
        }

        val puppyIcon = ImageView(context).apply {
            setImageResource(R.drawable.itv_gallop_bone_00)
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(80)).apply {
                bottomMargin = dp(16)
            }
        }
        val comfortText = TextView(context).apply {
            text = "오늘 하루도 정말 고생 많았어!\n머릿속 걱정은 내가 밤새 안전하게 보관할게.\n이제 푹 쉬고, 내일 아침에 만나자! 🌙"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        comfortCard.addView(puppyIcon)
        comfortCard.addView(comfortText)
        root.addView(comfortCard)

        val attachToHost = containerHost != null
        if (attachToHost) {
            containerHost!!.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            currentDialogView = root
        } else {
            try {
                wm.addView(root, overlayParams(false))
                currentDialogView = root
            } catch (_: Exception) {}
        }

        // Wait 2.2s for user to read comfort message, then lock screen
        root.postDelayed({
            dismissCurrentDialog()
            onComplete?.invoke()
            val locked = WritingGuardService.lockScreen()
            if (!locked) {
                Toast.makeText(context, "화면을 끄고 편안히 쉬어보세요.", Toast.LENGTH_SHORT).show()
            }
        }, 2200L)
    }

    /**
     * 2. Grayscale Mode:
     * Strips all colors from the screen to reduce stimulation.
     * When forceManual = false, listens to ambient light; when light returns (lux > 40), recovers color.
     * When forceManual = true, stays grayscale until manually canceled.
     */
    fun startGrayscaleMode(containerHost: FrameLayout? = null, forceManual: Boolean = false) {
        val success = grayscaleController.enableGrayscale(forceManual = forceManual)
        if (success) {
            if (forceManual) {
                stopLightObserverForRecovery()
                Toast.makeText(context, "흑백 모드가 켜졌습니다. (수동 테스트: 밝아도 유지됨)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "화면 자극을 줄이기 위해 흑백 모드로 전환합니다.", Toast.LENGTH_SHORT).show()
                startLightObserverForRecovery()
            }
        }
    }

    /**
     * Disables grayscale and restores color.
     */
    fun stopGrayscaleMode() {
        stopLightObserverForRecovery()
        grayscaleController.disableGrayscale()
        Toast.makeText(context, "흑백 모드가 해제되고 색상이 복원되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun startLightObserverForRecovery() {
        if (isObservingLightForRecovery) return
        isObservingLightForRecovery = true

        // Keep light tracking active continuously
        lightTracker.startContinuousPostureListening()

        recoveryRunnable = object : Runnable {
            override fun run() {
                if (!grayscaleController.isActive() || grayscaleController.isManual()) {
                    stopLightObserverForRecovery()
                    return
                }
                val snap = lightTracker.snapshot.value
                val lux = snap.lux
                // If light is turned on (lux > 40 lx), recover full color
                if (lux != null && lux > 40f) {
                    Toast.makeText(context, "주변 조명이 밝아져 흑백 모드를 해제합니다. (조도: ${lux.toInt()} lx)", Toast.LENGTH_SHORT).show()
                    stopGrayscaleMode()
                    return
                }
                // Continue observing every 1.5 seconds on mainHandler
                mainHandler.postDelayed(this, 1500L)
            }
        }.also {
            mainHandler.postDelayed(it, 1500L)
        }
    }

    private fun stopLightObserverForRecovery() {
        recoveryRunnable?.let { mainHandler.removeCallbacks(it) }
        recoveryRunnable = null
        if (isObservingLightForRecovery) {
            isObservingLightForRecovery = false
            lightTracker.stopContinuousPostureListening()
        }
    }

    /**
     * 3. ASMR Sound Selection Dialog:
     * Provides choices: Rain, Fireplace, Puppy Breathing, Wave.
     */
    fun showAsmrSelectorDialog(containerHost: FrameLayout? = null, onChoiceDone: (() -> Unit)? = null) {
        dismissCurrentDialog()

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#B30F172A"))
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(Color.parseColor("#1E293B"), 20, Color.parseColor("#10B981"), 1)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            val lp = FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.itv_gallop_front_01)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(8)
            }
        }
        val titleText = TextView(context).apply {
            text = "수면 유도 사운드"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtnTop = TextView(context).apply {
            text = "✕"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(8), dp(4), dp(4), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissCurrentDialog()
                onChoiceDone?.invoke()
            }
        }
        titleRow.addView(icon)
        titleRow.addView(titleText)
        titleRow.addView(closeBtnTop)
        card.addView(titleRow)

        val desc = TextView(context).apply {
            text = "방 안의 적적함을 달래주는 편안한 자연/수면 소리입니다.\n소리 재생 중에는 상단 알림창에서 앱을 켜지 않고도 바로 정지할 수 있습니다."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(8), 0, dp(14))
        }
        card.addView(desc)

        val currentPlaying = asmrPlayer.currentPlayingType

        AsmrType.values().forEach { type ->
            val isCurrent = (currentPlaying == type)
            val itemLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(
                    if (isCurrent) Color.parseColor("#064E3B") else Color.parseColor("#0F172A"),
                    12,
                    if (isCurrent) Color.parseColor("#10B981") else Color.parseColor("#334155"),
                    1
                )
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (isCurrent) {
                        asmrPlayer.stop()
                        Toast.makeText(context, "사운드 재생을 멈췄습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        asmrPlayer.play(type)
                        Toast.makeText(context, "${type.title} 재생을 시작했습니다.", Toast.LENGTH_SHORT).show()
                    }
                    dismissCurrentDialog()
                    onChoiceDone?.invoke()
                }
            }
            val titleView = TextView(context).apply {
                text = (if (isCurrent) "[재생 중] " else "") + type.title
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isCurrent) Color.parseColor("#6EE7B7") else Color.WHITE)
            }
            val descView = TextView(context).apply {
                text = type.description
                textSize = 11.5f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(2), 0, 0)
            }
            itemLayout.addView(titleView)
            itemLayout.addView(descView)

            card.addView(itemLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(6), 0, 0)
        }
        if (asmrPlayer.isPlaying()) {
            val stopBtn = Button(context).apply {
                text = "재생 중지"
                textSize = 12f
                setTextColor(Color.parseColor("#EF4444"))
                background = roundedDrawable(Color.TRANSPARENT, 8)
                setOnClickListener {
                    asmrPlayer.stop()
                    Toast.makeText(context, "ASMR 사운드를 정지했습니다.", Toast.LENGTH_SHORT).show()
                    dismissCurrentDialog()
                    onChoiceDone?.invoke()
                }
            }
            btnRow.addView(stopBtn)
        }
        val closeBtn = Button(context).apply {
            text = "닫기"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            background = roundedDrawable(Color.TRANSPARENT, 8)
            setOnClickListener {
                dismissCurrentDialog()
                onChoiceDone?.invoke()
            }
        }
        btnRow.addView(closeBtn)
        card.addView(btnRow)

        root.addView(card)

        if (containerHost != null) {
            containerHost.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            currentDialogView = root
        } else {
            try {
                wm.addView(root, overlayParams(true))
                currentDialogView = root
            } catch (_: Exception) {}
        }
    }

    /**
     * 4. Morning Wake-Up Briefing:
     * Shows yesterday's saved to-do list. Excludes messaging screens.
     * Has a close button that dismisses and marks briefing as completed.
     */
    fun showMorningBriefingIfNeeded(currentPkg: String? = null, containerHost: FrameLayout? = null) {
        val messengerPackages = setOf(
            "com.kakao.talk", "com.discord",
            "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.android.mms", "org.telegram.messenger", "jp.naver.line.android"
        )
        if (currentPkg != null && currentPkg in messengerPackages) {
            return // Skip messaging apps as requested
        }

        val pendingMemo = memoStore.getPendingMemo() ?: return
        if (morningBriefingView != null) return

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(Color.WHITE, 20, Color.parseColor("#E2E8F0"), 1)
            setPadding(dp(22), dp(22), dp(22), dp(22))
            val lp = FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sunIcon = ImageView(context).apply {
            setImageResource(R.drawable.itv_gallop_bone_00)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(10)
            }
        }
        val titleText = TextView(context).apply {
            text = "☀️ 좋은 아침이에요!"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtnTop = TextView(context).apply {
            text = "✕"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(8), dp(4), dp(4), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissMorningBriefing()
            }
        }
        headerRow.addView(sunIcon)
        headerRow.addView(titleText)
        headerRow.addView(closeBtnTop)
        card.addView(headerRow)

        val subText = TextView(context).apply {
            text = "어젯밤 강아지에게 맡겨두었던 오늘의 할 일 목록이에요."
            textSize = 12.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(8), 0, dp(12))
        }
        card.addView(subText)

        val scrollBox = ScrollView(context).apply {
            background = roundedDrawable(Color.parseColor("#F8FAFC"), 12, Color.parseColor("#E2E8F0"), 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140)).apply {
                bottomMargin = dp(16)
            }
        }
        val memoText = TextView(context).apply {
            text = pendingMemo.content
            textSize = 14f
            setTextColor(Color.parseColor("#0F172A"))
            setLineSpacing(0f, 1.25f)
        }
        scrollBox.addView(memoText)
        card.addView(scrollBox)

        val closeBtn = Button(context).apply {
            text = "확인 완료 · 닫기"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedDrawable(Color.parseColor("#2563EB"), 12)
            setOnClickListener {
                memoStore.dismissBriefing()
                dismissMorningBriefing()
            }
        }
        card.addView(closeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

        root.addView(card)

        if (containerHost != null) {
            containerHost.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            morningBriefingView = root
        } else {
            try {
                wm.addView(root, overlayParams(true))
                morningBriefingView = root
            } catch (_: Exception) {}
        }
    }

    companion object {
        @Volatile
        private var instance: BedtimeCareController? = null

        fun getInstance(context: Context): BedtimeCareController {
            return instance ?: synchronized(this) {
                instance ?: BedtimeCareController(context.applicationContext).also { instance = it }
            }
        }
    }
}
