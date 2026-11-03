package com.example.itv.infrastructure.coaching
import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe
import com.example.itv.infrastructure.observation.accessibility.WritingGuard


import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.example.itv.domain.coaching.CoachingObservation
import com.example.itv.presentation.coaching.*

/** Wraps legacy Android probes; implementations now live under infrastructure.observation. */
internal class AndroidCoachingObserver(context: Context) {
    private val context = context.applicationContext
    fun unlocked(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun snapshot() = CoachingObservation(unlocked(), WritingGuard.blockReason(),
        YoutubeProbe.screenState(), ReelsProbe.screenState())
}
