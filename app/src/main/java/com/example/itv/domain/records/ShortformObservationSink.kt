package com.example.itv.domain.records

internal enum class ShortformApp { INSTAGRAM, YOUTUBE }
/** Synchronous delivery preserves the existing observation callback order. */
internal interface ShortformObservationSink {
    fun recordLegacyInstagramPresence(confirmed: Boolean)
    fun recordDailyPresence(app: ShortformApp, confirmed: Boolean)
    fun recordLegacyInstagramTransition()
    fun recordDailyTransition(app: ShortformApp)
}
