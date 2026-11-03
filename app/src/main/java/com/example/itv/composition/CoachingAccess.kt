package com.example.itv.composition

import android.content.Context
import com.example.itv.application.coaching.CoachingAccessService
import com.example.itv.infrastructure.coaching.*

/** Compatibility API assembled here; actual responsibilities live in separate classes. */
internal object CoachingAccess {
    const val MONITOR = CoachingNotifications.MONITOR
    const val ALERT = CoachingNotifications.ALERT
    fun consent(c: Context): Boolean = CoachingConsentStore.granted(c)
    fun channels(c: Context): Unit = CoachingNotifications.channels(c)
    fun notifications(c: Context): Boolean = CoachingNotifications.allowed(c)
    fun ready(c: Context): Boolean = CoachingAccessService(AndroidCoachingAccess(c)).ready()
    private fun developerSettings(c: Context) =
        com.example.itv.application.coaching.DeveloperCoachingSettingsService(PreferencesDeveloperCoachingSettings(c))
    private fun diagnosticStatus(c: Context) =
        com.example.itv.application.coaching.CoachingDiagnosticStatusService(PreferencesCoachingDiagnosticStatus(c))
    fun developerAutoEnabled(c: Context): Boolean = developerSettings(c).autoEnabled()
    fun setDeveloperAutoEnabled(c: Context, value: Boolean): Unit = developerSettings(c).setAutoEnabled(value)
    fun status(c: Context, defaultValue: String = ""): String = diagnosticStatus(c).read(defaultValue)
    fun state(c: Context, value: String): Unit = diagnosticStatus(c).write(value)
}
