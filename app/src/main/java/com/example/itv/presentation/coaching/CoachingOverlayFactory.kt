package com.example.itv.presentation.coaching

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import com.example.itv.R

/** Builds Views only. Knows no response deadlines, mission state or persistence. */
internal class CoachingOverlayFactory(private val context: Context) {
    fun offer(onChoice: (String, String) -> Unit): Pair<LinearLayout, WindowManager.LayoutParams> {
        val ink = Color.rgb(36, 61, 48)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Color.rgb(252, 255, 251), Color.rgb(144, 182, 151), 24)
            elevation = dp(12).toFloat()
        }
        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(context).apply {
            setImageResource(R.drawable.itv_puppy_face); contentDescription = "강아지 친구"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(76), dp(76)))
        heading.addView(TextView(context).apply {
            text = "잠깐 쉬어갈 시간이야!\n나랑 잠깐 쉬어갈까?"; textSize = 17f
            setTextColor(ink); setTypeface(null, Typeface.BOLD); setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(heading)
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        fun choice(title: String, fill: Int, message: String) {
            val button = Button(context).apply {
                text = title; textSize = 14f; isAllCaps = false; setTextColor(ink)
                background = rounded(fill, Color.rgb(113, 155, 124), 14)
                setOnClickListener {
                    onChoice(title, message)
                }
            }
            actions.addView(button, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                if (actions.childCount > 0) marginStart = dp(10)
            })
        }
        choice("쉬기", Color.rgb(222, 241, 222), "휴식 미션: 15초 안에 화면을 끄고 연속 30초 쉬어 주세요. 일찍 켜면 다시 30초가 필요해요.")
        choice("나중에", Color.rgb(238, 244, 255), "5분 뒤에도 개입이 필요하고 안전한 상태이면 다시 알려줄게.")
        choice("닫기", Color.WHITE, "닫았어요. 충분히 쉬어 새 구간이 시작되기 전에는 다시 제안하지 않아요.")
        root.addView(actions)
        val width = minOf(dp(360), context.resources.displayMetrics.widthPixels - dp(28)).coerceAtLeast(dp(180))
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(72)
        }
        return root to params
    }
    fun completion(onDismiss: () -> Unit): Pair<LinearLayout, WindowManager.LayoutParams> {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = rounded(Color.rgb(244, 255, 244), Color.rgb(144, 182, 151))
        }
        box.addView(TextView(context).apply {
            text = "휴식 미션 완료!\n화면을 30초 이상 끄고 쉬었어요."
            textSize = 18f; setTextColor(Color.rgb(36, 61, 48))
        })
        box.addView(Button(context).apply {
            text = "확인"
            setOnClickListener { onDismiss() }
        })
        val params = WindowManager.LayoutParams(
            minOf(dp(360), context.resources.displayMetrics.widthPixels - dp(28)),
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(72)
        }
        return box to params
    }
    fun feedback(onSubmit: (Int?) -> Unit): Pair<LinearLayout, WindowManager.LayoutParams> {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(16),dp(20),dp(16))
            background = rounded(Color.rgb(244,255,244),Color.rgb(144,182,151))
        }
        root.addView(TextView(context).apply { text="이번 개입은 적절했나요?";textSize=18f;setTextColor(Color.rgb(36,61,48)) })
        root.addView(TextView(context).apply { text="0점 매우 부적절 · 5점 보통 · 10점 매우 적절";textSize=12f })
        val selected = TextView(context).apply { text="점수를 선택해 주세요." }
        var score: Int? = null
        val submit = Button(context).apply { text="평가 제출";isEnabled=false;setOnClickListener { score?.let(onSubmit) } }
        val slider = SeekBar(context).apply { max=10;progress=5;contentDescription="개입 적절성 점수 0부터 10" }
        slider.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?,value: Int,fromUser: Boolean) {
                if(fromUser) { score=value;selected.text="선택: ${value}점";submit.isEnabled=true }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        root.addView(slider,LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(selected);root.addView(submit)
        root.addView(Button(context).apply { text="건너뛰기";setOnClickListener { onSubmit(null) } })
        val params = WindowManager.LayoutParams(
            minOf(dp(360),context.resources.displayMetrics.widthPixels-dp(28)),
            WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;y=dp(72)
        }
        return root to params
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun rounded(fill: Int, stroke: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke)
    }
}
