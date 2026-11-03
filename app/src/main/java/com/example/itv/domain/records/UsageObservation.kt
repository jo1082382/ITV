package com.example.itv.domain.records

internal enum class ObservationKind {
    ACTIVITY_RESUMED, ACTIVITY_PAUSED, SCREEN_INTERACTIVE, SCREEN_NON_INTERACTIVE,
    KEYGUARD_SHOWN, KEYGUARD_HIDDEN, DEVICE_STARTUP, DEVICE_SHUTDOWN, OTHER
}
internal data class UsageObservation(val timeStamp: Long, val eventType: ObservationKind, val packageName: String?)
internal interface RecordSource {
    fun requireUsagePermission()
    fun requireCollectionConsent()
    fun events(from: Long, to: Long): List<UsageObservation>
    fun appLabel(packageName: String): String
}
