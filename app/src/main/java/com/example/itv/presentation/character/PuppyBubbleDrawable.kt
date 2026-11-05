package com.example.itv.presentation.character
import android.graphics.*
import android.graphics.drawable.Drawable

/** Cream thought bubble, rounded brown outline matching the puppy artwork. */
class PuppyBubbleDrawable:Drawable() {
    private var targetX=150f
    private var below=true
    fun pointToward(fraction:Float,belowBubble:Boolean) {
        targetX=(fraction*300f).coerceIn(18f,282f);below=belowBubble;invalidateSelf()
    }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c:Canvas) {
        val w=bounds.width().toFloat();val h=bounds.height().toFloat()
        c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(w/300f,h/120f)
        val path=Path().apply {
            moveTo(28f,72f);cubicTo(2f,56f,10f,27f,39f,25f)
            cubicTo(45f,1f,86f,0f,108f,14f);cubicTo(130f,-1f,171f,0f,187f,14f)
            cubicTo(220f,-2f,260f,8f,265f,30f);cubicTo(302f,31f,306f,70f,276f,79f)
            cubicTo(263f,100f,225f,101f,207f,91f);cubicTo(178f,108f,139f,101f,123f,92f)
            cubicTo(83f,109f,45f,100f,28f,72f);close()
        }
        paint.color=Color.rgb(255,249,235);paint.style=Paint.Style.FILL;c.drawPath(path,paint)
        paint.color=Color.rgb(113,73,43);paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;c.drawPath(path,paint)
        for(v in listOf(floatArrayOf(targetX,if(below)105f else 10f,7f),floatArrayOf(targetX,if(below)116f else 3f,3f))) {
            paint.style=Paint.Style.FILL;paint.color=Color.rgb(255,249,235);c.drawCircle(v[0],v[1],v[2],paint)
            paint.style=Paint.Style.STROKE;paint.color=Color.rgb(113,73,43);c.drawCircle(v[0],v[1],v[2],paint)
        };c.restore()
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
