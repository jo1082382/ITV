package com.example.itv.presentation.coaching

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import com.example.itv.R

/** Independent local windows: never installs a full-screen touch interceptor. */
internal class CoachingOverlayFactory(private val context:Context,private val previewHost:FrameLayout?=null) {
    private val wm=object {
        private fun local(p:WindowManager.LayoutParams)=FrameLayout.LayoutParams(p.width,p.height).apply {
            leftMargin=p.x;topMargin=p.y;gravity=Gravity.TOP or Gravity.LEFT
        }
        fun addView(view:View,p:WindowManager.LayoutParams) {
            val host=previewHost
            if(host==null)context.getSystemService(WindowManager::class.java).addView(view,p)
            else {host.addView(view,local(p));view.visibility=if(view.visibility==View.INVISIBLE)View.INVISIBLE else View.VISIBLE}
        }
        fun updateViewLayout(view:View,p:WindowManager.LayoutParams) {
            val host=previewHost
            if(host==null)context.getSystemService(WindowManager::class.java).updateViewLayout(view,p)
            else {view.layoutParams=local(p)}
        }
        fun removeView(view:View) {
            val host=previewHost
            if(host==null)context.getSystemService(WindowManager::class.java).removeView(view)
            else host.removeView(view)
        }
    }
    fun attachPreview(root:View,params:WindowManager.LayoutParams) {
        check(previewHost!=null)
        wm.addView(root,params);attachControls(root)
    }
    fun detachPreview(root:View) {removeControls(root);wm.removeView(root)}

    private val runFrames=intArrayOf(R.drawable.itv_gallop_away_00,R.drawable.itv_gallop_away_01,R.drawable.itv_gallop_away_02,R.drawable.itv_gallop_away_03,R.drawable.itv_gallop_away_04,R.drawable.itv_gallop_away_05,R.drawable.itv_gallop_away_06,R.drawable.itv_gallop_away_07)
    private val rewardFrames=intArrayOf(R.drawable.itv_gallop_bone_00,R.drawable.itv_gallop_bone_01,R.drawable.itv_gallop_bone_02,R.drawable.itv_gallop_bone_03,R.drawable.itv_gallop_bone_04,R.drawable.itv_gallop_bone_05,R.drawable.itv_gallop_bone_06,R.drawable.itv_gallop_bone_07)
    private val sessions=mutableMapOf<View,Session>()
    private val handler=Handler(Looper.getMainLooper())
    private fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
    private inner class Session(
        val root: View,
        val welcome: Boolean,
        val choice: ((String, String) -> Unit)?,
        var reasons: List<String> = emptyList()
    ) {
        val windows=mutableListOf<View>()
        var animator:ValueAnimator?=null
        var hold:Runnable?=null
        var stopped=false
        var committed=false
        var onRewardDismiss:(()->Unit)?=null
        var rewardClaimed=false
        var bubbleFollow:Runnable?=null
        var ready=false
        var retry=false
        var hoveringPen=false
        var hoverZone=""
        var shake:ValueAnimator?=null
        var speechParams:WindowManager.LayoutParams?=null
        var reaction:Runnable?=null
        var reactionFinished:(()->Unit)?=null
        var onReady:(()->Unit)?=null
        var fence:ImageView?=null
        var fenceParams:WindowManager.LayoutParams?=null
        lateinit var dog:ImageView
        lateinit var dogParams:WindowManager.LayoutParams
        lateinit var speech:TextView
        lateinit var house:Rect
        lateinit var cage:Rect
        var homeX=0;var homeY=0
        var onInteraction: ((Boolean) -> Unit)? = null
        fun cleanup(){stopped=true;onInteraction?.invoke(false);bubbleFollow?.let{handler.removeCallbacks(it)};shake?.cancel();reaction?.let{handler.removeCallbacks(it)};reactionFinished=null;onReady=null;animator?.cancel();hold?.let{handler.removeCallbacks(it)};windows.toList().forEach{try{wm.removeView(it)}catch(_:Exception){}};windows.clear()}
    }
    private fun params(w:Int,h:Int,x:Int,y:Int,touch:Boolean)=WindowManager.LayoutParams(w,h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if(touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),PixelFormat.TRANSLUCENT).apply {
        gravity=Gravity.TOP or Gravity.LEFT;this.x=x;this.y=y
        alpha=1f
    }
    private fun label(text:String)=TextView(context).apply {
        this.text=text;textSize=14f;setTypeface(null,android.graphics.Typeface.BOLD);gravity=Gravity.CENTER;setTextColor(Color.rgb(18,25,16))
        setLineSpacing(dp(2).toFloat(), 1.15f)
        background=com.example.itv.presentation.character.PuppyBubbleDrawable()
        setPadding(dp(20),dp(10),dp(20),dp(20))
    }
    private fun root(
        welcome: Boolean,
        choice: ((String, String) -> Unit)?,
        reasons: List<String> = emptyList()
    ): Pair<LinearLayout, WindowManager.LayoutParams> {
        val root=LinearLayout(context)
        sessions[root]=Session(root,welcome,choice,reasons)
        root.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View) {if(welcome) attachControls(v)}
            override fun onViewDetachedFromWindow(v:View){removeControls(v)}
        })
        return root to params(1,1,0,0,false).apply{alpha=0f}
    }
    fun retryOffer(onChoice:(String,String)->Unit):Pair<LinearLayout,WindowManager.LayoutParams> {
        val result=root(false,onChoice)
        sessions[result.first]?.retry=true
        return result
    }
    fun offer(
        onChoice: (String, String) -> Unit,
        reasons: List<String> = emptyList()
    ) = root(false, onChoice, reasons)
    fun completion(onDismiss:()->Unit):Pair<LinearLayout,WindowManager.LayoutParams> {
        val result=root(true,null)
        sessions[result.first]?.onRewardDismiss=onDismiss
        return result
    }
    fun attachControls(root:View,onReady:(()->Unit)?=null,onInteraction:((Boolean)->Unit)?=null) {
        val s=sessions[root]?:return
        if(s.windows.isNotEmpty() || s.stopped)return
        s.onReady=onReady
        s.onInteraction=onInteraction
        val metrics=context.resources.displayMetrics
        val width=previewHost?.width?.takeIf{it>0} ?: metrics.widthPixels
        val height=previewHost?.height?.takeIf{it>0} ?: (metrics.heightPixels-dp(48))
        val dogSize=minOf(dp(112),width/3)
        val houseW=minOf(dp(160),width/2)
        val houseH=houseW*360/420
        val hx=(width-houseW)/2;val hy=height/5
        s.house=Rect(hx,hy,hx+houseW,hy+houseH)
        val right=kotlin.random.Random.nextBoolean()
        val cageSize=minOf(dp(164),width/2)
        val cageX=if(right)dp(14) else width-cageSize-dp(14)
        s.cage=Rect(cageX,height*2/3,cageX+cageSize,height*2/3+cageSize)
        fun add(v:View,p:WindowManager.LayoutParams){wm.addView(v,p);s.windows.add(v)}
        try {
            val houseView=ImageView(context).apply {setImageResource(R.drawable.itv_companion_house_back);scaleType=ImageView.ScaleType.FIT_CENTER;setBackgroundColor(Color.rgb(246,229,199));alpha=1f;imageAlpha=255;visibility=if(s.welcome)View.VISIBLE else View.INVISIBLE}
            add(houseView,params(houseW,houseH,hx,hy,true).apply {format=PixelFormat.OPAQUE;alpha=1f})
            s.speech=label(if(s.welcome) "잘 쉬었어! 반가워!" else "강아지를 길게 눌러 옮겨 주세요")
            s.speech.visibility=View.INVISIBLE
            s.speechParams=params(minOf(dp(300),width-dp(20)),dp(160),(width-minOf(dp(300),width-dp(20)))/2,dp(8),false)
            add(s.speech,s.speechParams!!)
            if(!s.welcome) {
                val pen=ImageView(context).apply {setImageResource(R.drawable.itv_pen_fence);contentDescription="닫기: 철창 펜스";visibility=View.INVISIBLE}
                val penParams=params(cageSize,cageSize,s.cage.left,s.cage.top,false)
                s.fence=pen;s.fenceParams=penParams
                add(pen,penParams)
            }
            s.dog=ImageView(context).apply {
                setImageResource(R.drawable.itv_motion_front);scaleType=ImageView.ScaleType.FIT_CENTER
                contentDescription="강아지. 길게 눌러 집으로 옮기면 쉬기, 화면 가장자리는 나중에, 닫기 구역은 닫기."
            }
            s.homeX=width/2-dogSize/2;s.homeY=if(s.welcome)minOf(hy+houseH+dp(65),height-dogSize-dp(30)) else hy+houseH-dogSize/3
            s.dogParams=params(dogSize,dogSize,if(s.welcome)s.homeX else if(right)width-dogSize/3 else -dogSize*2/3,
                if(s.welcome)hy+houseH-dogSize else height-dogSize-dp(155),true)
            s.dogParams.flags=s.dogParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            add(s.dog,s.dogParams)
            val sx=s.dogParams.x;val sy=s.dogParams.y
            s.animator=ValueAnimator.ofFloat(0f,1f).apply {
                duration=if(s.welcome)1800 else 7400
                interpolator=android.view.animation.LinearInterpolator()
                addUpdateListener {
                    if(s.stopped)return@addUpdateListener
                    val raw=it.animatedValue as Float
                    val peeking= !s.welcome && raw<(5000f/7400f)
                    val f=if(s.welcome)raw else ((raw-(5000f/7400f))/(2400f/7400f)).coerceIn(0f,1f)
                    if(peeking) {
                        val peek=raw/(5000f/7400f)
                        s.dog.pivotX=if(right)dogSize.toFloat() else 0f
                        s.dog.pivotY=dogSize*0.85f
                        s.dog.rotation=(if(right)-1f else 1f)*(8f+24f*peek)
                        s.dogParams.x=if(right)width-(dogSize*(0.1f+0.8f*peek)).toInt() else (-dogSize*(0.9f-0.8f*peek)).toInt()
                        s.dogParams.y=sy
                        s.dog.setImageResource(R.drawable.itv_motion_front)
                    } else {
                        s.dog.rotation=0f;s.dog.pivotX=dogSize/2f;s.dog.pivotY=dogSize/2f
                        houseView.visibility=View.VISIBLE;s.fence?.visibility=View.VISIBLE
                        val runStart=if(right)width-dogSize*7/10 else -dogSize*3/10
                        s.dogParams.x=if(s.welcome)(sx+(s.homeX-sx)*f).toInt() else (runStart+(s.homeX-runStart)*f).toInt()
                        s.dogParams.y=(sy+(s.homeY-sy)*f).toInt()
                        val frame=((it.currentPlayTime/(if(s.welcome)70L else 110L))%runFrames.size).toInt()
                        val sprite=if(f>=0.99f) {if(s.welcome)R.drawable.itv_motion_bone else R.drawable.itv_motion_turn}
                            else if(s.welcome)rewardFrames[frame] else runFrames[frame]
                        if(s.dog.tag!=sprite) {s.dog.setImageResource(sprite);s.dog.tag=sprite}
                        s.dog.scaleY=if(s.welcome)0.78f+0.22f*f else 1f
                    }
                    s.dog.scaleX=if(s.welcome)0.78f+0.22f*f else if(!peeking && f<0.99f && right)-1f else 1f

                    try{wm.updateViewLayout(s.dog,s.dogParams)}catch(_:Exception){}
                    if(raw>=0.999f) {
                        val firstReady=!s.ready
                        s.ready=true;s.speech.visibility=View.VISIBLE
                        if(firstReady){val cb=s.onReady;s.onReady=null;cb?.invoke()}
                        s.speech.text=if(s.welcome) "잘 쉬었어! 간식을 눌러 보상을 받아 줘!" else formatReasonsSpeech(s.reasons)
                        s.speechParams?.let {sp -> sp.y=(s.dogParams.y+s.dogParams.height+dp(8)).coerceAtMost(height-sp.height);try{wm.updateViewLayout(s.speech,sp)}catch(_:Exception){}}
                    }
                };start()
            }
            if(s.retry) {
                s.animator?.cancel();houseView.visibility=View.VISIBLE;s.fence?.visibility=View.VISIBLE;s.ready=true
                val cb=s.onReady;s.onReady=null;cb?.invoke()
                s.dogParams.x=s.homeX;s.dogParams.y=s.homeY
                s.dog.rotation=0f;s.dog.scaleX=1f;s.dog.scaleY=1f
                s.dog.setImageResource(R.drawable.itv_motion_angry)
                wm.updateViewLayout(s.dog,s.dogParams)
                s.speech.visibility=View.VISIBLE;s.speech.text="쉬러 가기로 했잖아!"
                s.speechParams?.let {sp ->sp.y=s.homeY+s.dogParams.height+dp(8);wm.updateViewLayout(s.speech,sp)}
                s.speechParams?.let {sp ->
                    sp.x=(s.dogParams.x+s.dogParams.width/2-sp.width/2).coerceIn(0,(width-sp.width).coerceAtLeast(0))
                    sp.y=(s.dogParams.y+s.dogParams.height+dp(8)).coerceAtMost((height-sp.height).coerceAtLeast(0))
                    (s.speech.background as? com.example.itv.presentation.character.PuppyBubbleDrawable)?.pointToward((s.dogParams.x+s.dogParams.width/2-sp.x).toFloat()/sp.width,false)
                    wm.updateViewLayout(s.speech,sp)
                }
            }
            if(s.welcome) {
                s.dog.setOnClickListener {
                    if(s.ready && !s.rewardClaimed && !s.stopped) {
                        s.rewardClaimed=true
                        s.dog.setImageResource(R.drawable.itv_motion_front)
                        s.speech.text="보상을 받았어! 잘 쉬어 줘서 고마워!"
                        s.reaction=Runnable{if(!s.stopped)s.onRewardDismiss?.invoke()}
                        handler.postDelayed(s.reaction!!,1000L)
                    }
                }
            } else installDrag(s,width,height,dogSize)
            startBubbleFollow(s,width,height)
        } catch(_:Exception) {s.cleanup();throw IllegalStateException("캐릭터 창 표시 실패")}
    }
    private fun stopBubbleFollow(s:Session) {
        s.bubbleFollow?.let {handler.removeCallbacks(it)}
        s.bubbleFollow=null
    }
    private fun formatReasonsSpeech(reasons: List<String>): String {
        val items = reasons.take(3)
        if (items.isEmpty()) {
            return "잠깐만! 내가 온 이유야 🐾\n• 쉬지 않고 영상을 계속 보고 있어!\n• 잠깐 눈을 쉬어주자!"
        }
        return "잠깐만! 내가 온 이유야 🐾\n" + items.joinToString("\n") { "• $it" }
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
    private fun rounded(fill: Int, stroke: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke)
    }
    private fun startBubbleFollow(s:Session,width:Int,height:Int) {
        stopBubbleFollow(s)
        val follow=object:Runnable {
            override fun run() {
                if(s.stopped)return
                s.speechParams?.let {sp ->
                    val cx=s.dogParams.x+s.dogParams.width/2
                    val above=s.dogParams.y>=sp.height+dp(12)
                    val tx=(cx-sp.width/2).coerceIn(0,(width-sp.width).coerceAtLeast(0))
                    val ty=(if(above)s.dogParams.y-sp.height-dp(4) else s.dogParams.y+s.dogParams.height+dp(4)).coerceIn(0,(height-sp.height).coerceAtLeast(0))
                    sp.x+=( (tx-sp.x)*0.38f).toInt().let {if(it==0)tx-sp.x else it}
                    sp.y+=( (ty-sp.y)*0.38f).toInt().let {if(it==0)ty-sp.y else it}
                    (s.speech.background as? com.example.itv.presentation.character.PuppyBubbleDrawable)?.pointToward((cx-sp.x).toFloat()/sp.width,above)
                    if(s.speech.visibility==View.VISIBLE)try{wm.updateViewLayout(s.speech,sp)}catch(_:Exception){}
                }
                handler.postDelayed(this,16L)
            }
        }
        s.bubbleFollow=follow;handler.post(follow)
    }
    private fun installDrag(s:Session,width:Int,height:Int,size:Int) {
        var downX=0f;var downY=0f;var startX=0;var startY=0;var dragging=false
        val slop=ViewConfiguration.get(context).scaledTouchSlop
        s.dog.setOnTouchListener {_,event ->
            if(s.committed || s.stopped || !s.ready)return@setOnTouchListener true
            when(event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    s.onInteraction?.invoke(true)
                    downX=event.rawX;downY=event.rawY;startX=s.dogParams.x;startY=s.dogParams.y;dragging=false
                    s.hold=Runnable {if(!s.stopped && !s.committed) {dragging=true;s.onInteraction?.invoke(true);startBubbleFollow(s,width,height);startY-=dp(50);s.dogParams.y=startY.coerceAtLeast(0);try{wm.updateViewLayout(s.dog,s.dogParams)}catch(_:Exception){};s.animator?.cancel();s.dog.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);s.speech.text="집: 쉬기\n좌우·아래 가장자리: 나중에\n철창: 닫기"}}
                    handler.postDelayed(s.hold!!,ViewConfiguration.getLongPressTimeout().toLong())
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx=event.rawX-downX;val dy=event.rawY-downY
                    if(!dragging && (kotlin.math.abs(dx)>slop || kotlin.math.abs(dy)>slop))s.hold?.let{handler.removeCallbacks(it)}
                    if(dragging){
                        s.onInteraction?.invoke(true)
                        s.dogParams.x=(startX+dx).toInt().coerceIn(0,(width-size).coerceAtLeast(0));s.dogParams.y=(startY+dy).toInt().coerceIn(0,(height-size).coerceAtLeast(0))
                        val over=s.cage.contains(s.dogParams.x+size/2,s.dogParams.y+size/2)
                        val home=s.house.contains(s.dogParams.x+size/2,s.dogParams.y+size/2)
                        val edge=s.dogParams.x<=dp(48) || s.dogParams.x+size>=width-dp(48) || s.dogParams.y+size>=height-dp(48)
                        val zone=if(over) "pen" else if(home) "home" else if(edge) "edge" else "none"
                        if(zone!=s.hoverZone) {
                            s.hoverZone=zone
                            if(zone=="home") {
                                s.shake?.cancel();s.dog.rotation=0f
                                com.example.itv.presentation.character.CompanionHover.sleepy(s.dog)
                                s.speech.text="Zzz....졸려..."
                            } else if(zone=="edge") {
                                s.shake?.cancel();s.dog.rotation=0f;s.dog.setImageResource(R.drawable.itv_motion_sad)
                                s.speech.text="진짜 안 쉴거야?"
                            } else if(zone=="none") {s.dog.setImageResource(if(s.retry)R.drawable.itv_motion_angry else R.drawable.itv_motion_turn);s.speech.text=if(s.retry)"쉬러 가기로 했잖아!" else "나랑 같이 쉬자!"}
                        }
                        if(over!=s.hoveringPen) {
                            s.hoveringPen=over;s.shake?.cancel();s.dog.rotation=0f
                            if(over) {
                                s.dog.setImageResource(R.drawable.itv_motion_sad)
                                s.speech.text="거긴 가기 싫어…"
                                s.shake=ValueAnimator.ofFloat(-6f,6f,-6f,6f,0f).apply {
                                    duration=1000;repeatCount=2
                                    addUpdateListener {if(!s.stopped)s.dog.rotation=it.animatedValue as Float}
                                    start()
                                }
                            } else {
                                if(s.hoverZone=="home") {
                                    com.example.itv.presentation.character.CompanionHover.sleepy(s.dog);s.speech.text="Zzz....졸려..."
                                } else if(s.hoverZone=="edge") {
                                    s.dog.setImageResource(R.drawable.itv_motion_sad);s.speech.text="진짜 안 쉴거야?"
                                } else {s.dog.setImageResource(if(s.retry)R.drawable.itv_motion_angry else R.drawable.itv_motion_turn);s.speech.text=if(s.retry)"쉬러 가기로 했잖아!" else "나랑 같이 쉬자!"}
                            }
                        }
                        try{wm.updateViewLayout(s.dog,s.dogParams)}catch(_:Exception){}
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    s.hold?.let{handler.removeCallbacks(it)}
                    s.shake?.cancel();s.dog.rotation=0f;s.hoveringPen=false
                    s.onInteraction?.invoke(false)
                    if(dragging) {
                        val cx=s.dogParams.x+size/2;val cy=s.dogParams.y+size/2;val edge=dp(48)
                        val title=when {
                            s.cage.contains(cx,cy)->"닫기"
                            s.house.contains(cx,cy)->"쉬기"
                            (s.dogParams.x<=edge || s.dogParams.x+size>=width-edge || s.dogParams.y+size>=height-edge)->"나중에"
                            else->null
                        }
                        val prepSec=com.example.itv.domain.coaching.TestCoachingPolicy.MISSION_START_MS/1000L
                        val restSec=com.example.itv.domain.coaching.TestCoachingPolicy.REST_MS/1000L
                        if(title!=null){s.committed=true;s.choice?.invoke(title,when(title){"쉬기"->"휴식 미션: ${prepSec}초 안에 화면을 끄고 연속 ${restSec}초 쉬어 주세요.";"나중에"->"알겠어. 다음에 다시 찾아올게!";else->"닫았어요."})}
                        else {s.dogParams.x=s.homeX;s.dogParams.y=s.homeY;try{wm.updateViewLayout(s.dog,s.dogParams)}catch(_:Exception){};s.speech.text="목표 영역에 놓아 주세요."}
                    }
                    dragging=false
                }
                android.view.MotionEvent.ACTION_CANCEL -> {s.hold?.let{handler.removeCallbacks(it)};dragging=false;s.onInteraction?.invoke(false);s.dogParams.x=s.homeX;s.dogParams.y=s.homeY;try{wm.updateViewLayout(s.dog,s.dogParams)}catch(_:Exception){}}
            }
            true
        }
    }
    fun playReaction(
        root:View,
        title:String,
        reminderSeconds:Long=com.example.itv.domain.coaching.TestCoachingPolicy.REMINDER_MS/1000L,
        prepareSeconds:Long=com.example.itv.domain.coaching.TestCoachingPolicy.MISSION_START_MS/1000L,
        restSeconds:Long=com.example.itv.domain.coaching.TestCoachingPolicy.REST_MS/1000L,
        finished:()->Unit
    ) {
        val s=sessions[root]
        if(s==null || s.stopped){finished();return}
        s.committed=true;s.shake?.cancel();s.dog.rotation=0f;s.animator?.cancel();s.hold?.let{handler.removeCallbacks(it)}
        s.reactionFinished=finished
        when(title) {
            "쉬기" -> {
                s.dogParams.x=s.house.centerX()-s.dogParams.width/2
                s.dogParams.y=s.house.bottom-s.dogParams.height*2/3
                s.dog.setImageResource(R.drawable.itv_motion_sleep)
                s.speech.text=if(s.retry) "이번엔 진짜지?\n창이 사라지면 ${prepareSeconds}초 안에 끄고\n${restSeconds}초 이상 쉬자!" else "Zzz… 이 창이 사라지면 ${prepareSeconds}초 안에\n폰을 끄고 ${restSeconds}초 이상 같이 쉬자!"
                // A gentle finite breathing cycle, not a permanently running animator.
                s.animator=ValueAnimator.ofFloat(1f,1.035f,1f,1.035f,1f).apply {
                    duration=2700
                    addUpdateListener {if(!s.stopped){s.dog.scaleY=it.animatedValue as Float}}
                    start()
                }
            }
            "나중에" -> {
                s.dog.setImageResource(R.drawable.itv_motion_wave)
                s.speech.text=if(reminderSeconds%60L==0L) "${reminderSeconds/60L}분 뒤에 다시 보자!" else "${reminderSeconds}초 뒤에 다시 보자!"
            }
            else -> {
                s.dogParams.x=s.cage.centerX()-s.dogParams.width/2
                s.dogParams.y=s.cage.bottom-s.dogParams.height-dp(8)
                s.dog.setImageResource(R.drawable.itv_motion_sad)
                s.speech.text="다음에 보자...."
                // Re-add the fence above the puppy so it reads as an enclosure.
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
        s.speechParams?.let {sp ->
            sp.y=(s.dogParams.y+s.dogParams.height+dp(6)).coerceAtMost(context.resources.displayMetrics.heightPixels-sp.height-dp(40))
            try{wm.updateViewLayout(s.speech,sp)}catch(_:Exception){}
        }
        try{wm.updateViewLayout(s.dog,s.dogParams)}catch(_:Exception){}
        s.reaction=Runnable {
            if(!s.stopped) {val callback=s.reactionFinished;s.reactionFinished=null;callback?.invoke()}
        }
        handler.postDelayed(s.reaction!!,2800L)
    }
    fun disableControls(root:View){sessions[root]?.committed=true}
    fun removeControls(root:View){sessions.remove(root)?.cleanup()}
}
