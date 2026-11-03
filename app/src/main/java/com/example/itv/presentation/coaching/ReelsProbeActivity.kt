package com.example.itv.presentation.coaching
import com.example.itv.domain.records.ReelsSampleKind


import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*

class ReelsProbeActivity : ComponentActivity() {
    private val diagnostic by lazy { com.example.itv.composition.reelsDiagnosticService(this) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var info: TextView
    private val update = object : Runnable {
        override fun run() {
            val snapshot = diagnostic.snapshot()
            val state = diagnostic.state.value
            info.text = (if (state.loading) "처리 중…\n" else "") +
                (state.error?.let { "오류: $it\n" } ?: "") + "${snapshot.status}\n후보 체류시간: ${snapshot.candidateMs / 1000}초\n스크롤 이벤트: ${snapshot.events} / 전환 후보: ${snapshot.candidates}\n" +
                "릴스 ${snapshot.samples.reels?.size ?: 0} · 피드 ${snapshot.samples.feed?.size ?: 0} · DM ${snapshot.samples.dm?.size ?: 0} · 댓글 ${snapshot.samples.comments?.size ?: 0}\n구분 ID: ${snapshot.markerCount}\n최근 Instagram ID:\n" + snapshot.latestIds.joinToString("\n")
            handler.postDelayed(this,1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { diagnostic.initializeAsync() }
        val column = LinearLayout(this).apply { orientation = 1; setPadding(24,24,24,24) }
        column.addView(TextView(this).apply { text = "Instagram 릴스 구조 테스트\n본문·이미지를 읽지 않습니다. UI ID 표본과 날짜별 통계를 기기에 저장합니다. 본문은 저장하지 않습니다.\n시작 후 Instagram의 각 화면에 2초 이상 머무른 뒤 즉시 돌아와 해당 표본을 저장하세요.\n일반 피드는 Reels 추천이 보이는 화면도 별도 검증하세요. 네 표본 등록 전에는 판별하지 않습니다. 댓글은 키보드를 닫은 상태로 등록하세요.\n스크롤 후보는 영상 개수가 아닙니다. 뒤로 넘기기·반복 시청·빠른 넘김을 구분하지 못합니다." })
        fun button(label: String, action: () -> Unit) { column.addView(Button(this).apply { text=label; setOnClickListener { action() } }) }
        button("진단 시작") { diagnostic.start() }
        button("진단 표시 중지 (기록은 계속)") { diagnostic.stopDisplay() }
        fun capture(kind: ReelsSampleKind) {
            lifecycleScope.launch { diagnostic.capture(kind) }
        }
        button("최근 화면 → 릴스 표본") { capture(ReelsSampleKind.REELS) }
        button("최근 화면 → 일반 피드 표본") { capture(ReelsSampleKind.FEED) }
        button("최근 화면 → DM 표본") { capture(ReelsSampleKind.DM) }
        button("최근 화면 → 릴스 댓글 표본") { capture(ReelsSampleKind.COMMENTS) }
        button("표본·카운터 초기화") { lifecycleScope.launch { diagnostic.resetSamplesAndCounters() } }
        button("릴스 누적 기록 삭제 (표본 유지)") {
            android.app.AlertDialog.Builder(this)
                .setTitle("릴스 기록 삭제")
                .setMessage("기기에 저장된 모든 날짜의 릴스 시간과 추정 횟수를 삭제합니다. 화면 구분 표본과 다른 앱 기록은 유지됩니다. 관측은 계속되므로 이후 릴스는 새로 기록됩니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제") { _, _ ->
                    lifecycleScope.launch {
                        if (diagnostic.clearRecords()) Toast.makeText(this@ReelsProbeActivity,
                            "릴스 진단 기록을 삭제했습니다", Toast.LENGTH_SHORT).show()
                    }
                }.show()
        }
        info=TextView(this); column.addView(info)
        setContentView(ScrollView(this).apply { addView(column) })
    }
    override fun onResume() { super.onResume(); handler.post(update) }
    override fun onPause() { handler.removeCallbacks(update); super.onPause() }
}
