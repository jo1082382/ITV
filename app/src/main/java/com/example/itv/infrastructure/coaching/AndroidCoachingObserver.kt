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
    fun snapshot(): CoachingObservation {
        val rawYt = YoutubeProbe.screenState()
        val effectiveYt = if (rawYt == 0 && com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments()) {
            1
        } else {
            rawYt
        }
        val rawReels = ReelsProbe.screenState()
        val effectiveReels = if (rawReels == 0 && com.example.itv.infrastructure.observation.instagram.InstagramTracker.isActivelyInReelsCommentsOrStory()) {
            1
        } else {
            rawReels
        }
        val longformWatching = com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyWatchingLongform()
        val fgPkg = com.example.itv.infrastructure.jev.JevObservationHub.foregroundPackage
        val genDeferredReason = com.example.itv.infrastructure.observation.GeneralAppTracker.blockingOrWaitingReason(fgPkg)
        return CoachingObservation(
            using = unlocked(),
            guardReason = WritingGuard.blockReason(),
            youtubeState = effectiveYt,
            reelsState = effectiveReels,
            youtubeLongformWatching = longformWatching,
            generalAppDeferredReason = genDeferredReason
        )
    }
}
