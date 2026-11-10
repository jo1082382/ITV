package com.example.itv.presentation.character

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.View
import android.widget.*
import com.example.itv.presentation.coaching.CoachingOverlayFactory

/** Shares the production scene; hosts it in ONE Activity window. No permissions or records. */
class CompanionPreviewActivity:Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var host:FrameLayout
    private lateinit var factory:CoachingOverlayFactory
    private var current:View?=null
    private var selected=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val column=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;fitsSystemWindows=true}
        val bar=LinearLayout(this)
        fun button(text:String,action:()->Unit){bar.addView(Button(this).apply{this.text=text;setOnClickListener{action()}},LinearLayout.LayoutParams(0,-2,1f))}
        button("처음부터"){show("offer")}
        button("재약속"){show("retry")}
        button("완료"){show("complete")}
        button("종료"){finish()}
        column.addView(bar)
        host=FrameLayout(this).apply {setBackgroundColor(Color.rgb(221,236,225));clipChildren=false;clipToPadding=false}
        column.addView(host,LinearLayout.LayoutParams(-1,0,1f));setContentView(column)
        factory=CoachingOverlayFactory(this,host)
        host.post {if(!isFinishing)show("offer")}
    }
    private fun clear() {
        handler.removeCallbacksAndMessages(null)
        current?.let{factory.detachPreview(it)};current=null;selected=false
    }
    private fun show(mode:String) {
        if(!::factory.isInitialized || host.width==0)return
        clear()
        val scene=when(mode){
            "retry"->factory.retryOffer{title,_->choose(title,true)}
            "complete"->factory.completion{}
            else->factory.offer({title,_->choose(title,false)}, listOf("취침 목표 시간보다 15분 지났어!", "주변이 어두운데 화면을 보고 있어!", "쉬지 않고 20분 넘게 영상을 보고 있어!"))
        }
        current=scene.first;factory.attachPreview(scene.first,scene.second)
        if(mode=="complete")handler.postDelayed({clear()},10_000L)
    }
    private fun choose(title:String,retry:Boolean) {
        val root=current?:return
        if(selected)return
        selected=true
        factory.disableControls(root)
        factory.playReaction(root,title) {
            if(current===root) {
                clear()
                // Test-only deadline simulation; no service/history/database changes.
                if(title=="쉬기")handler.postDelayed({show("retry")},com.example.itv.domain.coaching.TestCoachingPolicy.MISSION_START_MS)
            }
        }
    }
    override fun onDestroy(){if(::factory.isInitialized)clear();super.onDestroy()}
}
