package com.example.itv.presentation.character

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.itv.R
import com.example.itv.domain.character.*
import kotlin.math.sin

/** A new animal supplies pose resources. Motion and coaching are independent of its artwork. */
data class CompanionDefinition(val id:String,val name:String,val invitation:String,val tapMessage:String,
    val front:Int,val runA:Int,val runB:Int,val turn:Int,val wave:Int,val sleep:Int,
    val frontRunA:Int = front,val frontRunB:Int = front)
object CompanionCatalog {
    val puppy=CompanionDefinition("puppy","강아지","나랑 같이 잠깐 쉬자!","멍! 같이 쉬러 갈래?",
        R.drawable.itv_motion_front,R.drawable.itv_motion_run_a,R.drawable.itv_motion_run_b,
        R.drawable.itv_motion_turn,R.drawable.itv_motion_wave,R.drawable.itv_motion_sleep,
        R.drawable.itv_motion_front_run_a,R.drawable.itv_motion_front_run_b)
}
class CompanionSceneView(context:Context,private val definition:CompanionDefinition=CompanionCatalog.puppy):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val images=mutableMapOf<Int,Bitmap>()
    private var elapsed=0L
    private var action=CompanionAction.INVITE
    private var animator:ValueAnimator?=null
    private var done=false
    private var completion:(()->Unit)?=null
    private var announced=false
    private var welcoming=false
    var fullScreenScene=false
    var fromRight=false
        set(value) {field=value;invalidate()}
    var onCompanionTap:(()->Unit)?=null
    var onInvitationReady:(()->Unit)?=null
    init {
        tag="itv_companion_scene"
        contentDescription="강아지가 구석에서 등장해 집으로 달려가 뒤돌아봅니다."
        isClickable=true
        setOnClickListener {onCompanionTap?.invoke()}
    }
    private fun image(id:Int)=images.getOrPut(id){BitmapFactory.decodeResource(resources,id)}
    private fun drawImage(c:Canvas,id:Int,l:Float,t:Float,r:Float,b:Float,alpha:Int=255) {
        paint.alpha=alpha.coerceIn(0,255);c.drawBitmap(image(id),null,RectF(l,t,r,b),paint);paint.alpha=255
    }
    private fun stopMotionAnimator() {animator?.removeAllUpdateListeners();animator?.cancel();animator=null}
    fun replay() {welcoming=false;finishCallback();start(CompanionAction.INVITE,null)}
    fun playRest(after:(()->Unit)?=null) {welcoming=false;start(CompanionAction.REST,after)}
    fun playLater(after:(()->Unit)?=null) {welcoming=false;start(CompanionAction.LATER,after)}
    fun showRest() {stopMotionAnimator();action=CompanionAction.REST;elapsed=950;done=true;invalidate()}
    fun showCelebration() { welcoming=true; start(CompanionAction.REST,null) }
    private fun start(next:CompanionAction,after:(()->Unit)?) {
        stopMotionAnimator();completion=after;action=next;elapsed=0;done=false;announced=false
        if(isAttachedToWindow && isShown) resumeMotion()
        invalidate()
    }
    private fun finishCallback() {val callback=completion;completion=null;callback?.invoke()}
    private fun resumeMotion() {
        if(done || animator?.isRunning==true) return
        val end=CompanionMotion.duration(action)
        animator=ValueAnimator.ofFloat(elapsed.toFloat(),end.toFloat()).apply {
            duration=(end-elapsed).coerceAtLeast(1L);interpolator=LinearInterpolator()
            addUpdateListener {
                elapsed=(it.animatedValue as Float).toLong();invalidate()
                if(elapsed>=end) {
                    done=true
                    if(action==CompanionAction.INVITE && !announced) {announced=true;onInvitationReady?.invoke()}
                    finishCallback()
                }
            };start()
        }
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();resumeMotion()}
    override fun onDetachedFromWindow(){stopMotionAnimator();finishCallback();super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(v:Int){super.onWindowVisibilityChanged(v);if(v==VISIBLE && isAttachedToWindow) resumeMotion() else {stopMotionAnimator();finishCallback()}}
    override fun onVisibilityChanged(changedView:View,v:Int){super.onVisibilityChanged(changedView,v);if(isShown && isAttachedToWindow) resumeMotion() else {stopMotionAnimator();finishCallback()}}
    override fun onDraw(c:Canvas) {
        super.onDraw(c);if(width==0 || height==0)return
        val stageHeight=if(fullScreenScene) 360f*height/width else 250f
        val k=if(fullScreenScene) width/360f else minOf(width/360f,height/250f)
        c.save();c.translate((width-360*k)/2,(height-stageHeight*k)/2);c.scale(k,k)
        if(fromRight){c.translate(360f,0f);c.scale(-1f,1f)}
        val houseX=180f
        val houseY=if(fullScreenScene) stageHeight*0.29f else 80f
        val targetX=houseX-5f
        val targetY=houseY+65f
        val startY=stageHeight+15f
        drawImage(c,R.drawable.itv_companion_shadow,houseX-70f,houseY+35f,houseX+70f,houseY+75f)
        drawImage(c,R.drawable.itv_companion_house_back,houseX-70f,houseY-65f,houseX+73f,houseY+58f)
        c.save()
        // Keep the existing animation's local target at the new house entrance.
        c.translate(targetX-262f,targetY-145f)
        val f=CompanionMotion.frame(elapsed,action);val p=f.progress
        var x=262f;var y=145f;var size=92f;var pose=definition.turn;var opacity=255
        when(f.phase) {
            CompanionPhase.APPEAR -> {x=262f-targetX-20f+p*48f;y=145f+startY-targetY;size=140f;pose=definition.front}
            CompanionPhase.WALK_HOME -> {
                x=262f-targetX+28f+(targetX-28f)*p;y=145f+(startY-targetY)*(1f-p);size=140f-48f*p
                y-=kotlin.math.abs(sin(elapsed/90.0)).toFloat()*5f
                pose=if((elapsed/130)%2==0L)definition.runA else definition.runB
            }
            CompanionPhase.TURN_BACK -> {pose=if(p<0.4f)definition.runB else definition.turn}
            CompanionPhase.INVITE -> {}
            CompanionPhase.ENTER_HOME -> {
                if(welcoming) {
                    x=277f-15f*p;y=115f+65f*p;size=61f+45f*p
                    pose=if((elapsed/140)%2==0L)definition.frontRunA else definition.frontRunB
                } else {
                    x=262f+15f*p;y=145f-30f*p;size=92f-31f*p;pose=definition.runA;opacity=((1f-p)*255).toInt()
                }
            }
            CompanionPhase.REST -> {if(welcoming) {x=262f;y=180f;size=106f;pose=definition.front} else {x=277f;y=115f;size=56f;pose=definition.sleep}}
            CompanionPhase.WAVE -> {pose=if((elapsed/150)%2==0L)definition.wave else definition.turn}
            CompanionPhase.LEAVE -> {
                // Turn to the viewer and run forward out of the lower corner, never backpedal.
                x=262f+(28f-targetX)*p;y=145f+(stageHeight+150f-targetY)*p;size=92f+90f*p
                y-=kotlin.math.abs(sin(elapsed/70.0)).toFloat()*7f
                pose=if((elapsed/110)%2==0L)definition.frontRunA else definition.frontRunB
                opacity=255
            }
            CompanionPhase.GONE -> {opacity=0}
        }
        drawImage(c,R.drawable.itv_companion_shadow,x-size*0.43f,y-10f,x+size*0.43f,y+12f,opacity/2)
        drawImage(c,pose,x-size/2,y-size,x+size/2,y,opacity)
        c.restore()
        c.restore()
    }
}
