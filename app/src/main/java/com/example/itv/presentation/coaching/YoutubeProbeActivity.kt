package com.example.itv.presentation.coaching


import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*

class YoutubeProbeActivity:ComponentActivity() {
    private val diagnostic by lazy { com.example.itv.composition.youtubeDiagnosticService(this) }
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var info:TextView
    private val update=object:Runnable {
        override fun run(){
            val snapshot = diagnostic.snapshot()
            val state = diagnostic.state.value
            info.text = (if (state.loading) "처리 중…\n" else "") +
                (state.error?.let { "오류: $it\n" } ?: "") +
                "접근성 연결: ${snapshot.connected} · 감시: ${snapshot.enabled}\n" + snapshot.report
            handler.postDelayed(this,500)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        column.addView(TextView(this).apply{text="YouTube 쇼츠 항목 위치 테스트\n기존 표본·스크롤 테스트를 대체합니다. 표본 등록은 필요 없습니다.\n플레이어의 항목 위치만 비교하며 제목·본문·이미지는 읽지 않습니다. 전환 후보는 고유 영상 수가 아닙니다. 자동 개입이 켜져 있으면 조건 충족 후 다음 전환에서 개입합니다. 감시 중지는 진단용이며 자동 개입 중에는 안전 관측이 유지됩니다."})
        fun button(label:String,action:()->Unit){column.addView(Button(this).apply{text=label;setOnClickListener{action()}})}
        button("감시 시작"){
            lifecycleScope.launch { diagnostic.start() }
        }
        button("감시 중지"){lifecycleScope.launch { diagnostic.stop() }}
        button("카운터·로그 초기화"){lifecycleScope.launch { diagnostic.reset() }}
        button("결과 복사"){
            val clipboard=getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("쇼츠 항목 위치 테스트",diagnostic.report()))
            Toast.makeText(this,"결과를 복사했습니다",Toast.LENGTH_SHORT).show()
        }
        button("접근성 설정"){startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))}
        info=TextView(this).apply{setTextIsSelectable(true);textSize=12f};column.addView(info)
        setContentView(ScrollView(this).apply{addView(column)})
    }
    override fun onResume(){super.onResume();handler.post(update)}
    override fun onPause(){handler.removeCallbacks(update);super.onPause()}
}
