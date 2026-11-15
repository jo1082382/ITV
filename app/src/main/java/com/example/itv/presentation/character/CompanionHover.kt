package com.example.itv.presentation.character

import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import com.example.itv.R
import com.example.itv.presentation.coaching.EquippedDogView

object CompanionHover {
    fun sleepy(view: ImageView) {
        val open = view.context.getDrawable(R.drawable.itv_motion_turn)!!
        val closed = view.context.getDrawable(R.drawable.itv_motion_sleep)!!
        view.setImageDrawable(TransitionDrawable(arrayOf(open, closed)).apply {
            isCrossFadeEnabled = true
            startTransition(1400)
        })
    }

    fun sleepy(view: EquippedDogView) {
        sleepy(view.baseDog)
    }
}
