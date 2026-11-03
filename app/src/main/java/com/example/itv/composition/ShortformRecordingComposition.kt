package com.example.itv.composition

import android.content.Context
import com.example.itv.application.records.ShortformRecordingCoordinator

internal fun shortformRecordingCoordinator(context:Context):ShortformRecordingCoordinator {
    val c=context.applicationContext
    return ShortformRecordingCoordinator(
        persistenceFactory = { shortformPersistenceService(c) },
        elapsedNow = { android.os.SystemClock.elapsedRealtime() },
        consentGranted = { com.example.itv.infrastructure.coaching.CoachingConsentStore.granted(c) })
}
