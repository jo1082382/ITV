package com.example.itv.domain.coaching

internal data class CoachingAvailability(val consent: Boolean, val overlay: Boolean, val notifications: Boolean) {
    val ready: Boolean get() = consent && overlay && notifications
}
internal interface CoachingAccessSource {
    fun consent(): Boolean
    fun overlay(): Boolean
    fun notifications(): Boolean
}
