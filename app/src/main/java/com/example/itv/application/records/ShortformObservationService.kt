package com.example.itv.application.records

import com.example.itv.domain.records.ShortformApp
import com.example.itv.domain.records.ShortformObservationSink

/** Routes confirmed observations; no Android, JSON, clock or UI dependency. */
internal class ShortformObservationService(private val sink: ShortformObservationSink) {
    fun presence(app: ShortformApp, confirmed: Boolean) {
        if (app == ShortformApp.INSTAGRAM) sink.recordLegacyInstagramPresence(confirmed)
        sink.recordDailyPresence(app, confirmed)
    }
    fun transition(app: ShortformApp) {
        if (app == ShortformApp.INSTAGRAM) sink.recordLegacyInstagramTransition()
        sink.recordDailyTransition(app)
    }
}
