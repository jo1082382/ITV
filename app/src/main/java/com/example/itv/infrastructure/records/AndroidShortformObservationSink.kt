package com.example.itv.infrastructure.records

import android.content.Context
import com.example.itv.domain.records.ShortformApp
import com.example.itv.domain.records.ShortformObservationSink
import com.example.itv.infrastructure.storage.shortform.ReelsUsageStore
import com.example.itv.infrastructure.storage.shortform.ShortformDailyStore

/** Delegates to unchanged legacy accumulation and persistence implementations. */
internal class AndroidShortformObservationSink(context: Context) : ShortformObservationSink {
    private val context = context.applicationContext
    // This adapter is assembled for each existing observation/transition callback.
    private val observationTime = com.example.itv.domain.records.ShortformObservationTime(
        android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis(), java.time.ZoneId.systemDefault().id)
    private fun key(app: ShortformApp): String = when (app) {
        ShortformApp.INSTAGRAM -> "instagram"
        ShortformApp.YOUTUBE -> "youtube"
    }
    override fun recordLegacyInstagramPresence(confirmed: Boolean) { ReelsUsageStore.observe(confirmed, context) }
    override fun recordDailyPresence(app: ShortformApp, confirmed: Boolean) {
        ShortformDailyStore.observe(context, key(app), confirmed, observationTime)
    }
    override fun recordLegacyInstagramTransition() { ReelsUsageStore.transition() }
    override fun recordDailyTransition(app: ShortformApp) { ShortformDailyStore.transition(context, key(app), observationTime) }
}
