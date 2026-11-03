package com.example.itv.application.coaching

import com.example.itv.domain.coaching.DeveloperCoachingSettings
import com.example.itv.domain.coaching.CoachingDiagnosticStatus

/** Only typed access. Runtime start/stop remains in the existing Android adapter. */
internal class DeveloperCoachingSettingsService(private val settings: DeveloperCoachingSettings) {
    fun autoEnabled(): Boolean = settings.autoEnabled()
    fun setAutoEnabled(value: Boolean) { settings.setAutoEnabled(value) }
}
internal class CoachingDiagnosticStatusService(private val status: CoachingDiagnosticStatus) {
    fun read(defaultValue: String = ""): String = status.read(defaultValue)
    fun write(value: String) { status.write(value) }
}
