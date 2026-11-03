package com.example.itv.domain.coaching

/** Device-local development switch; NOT the user's Room coaching preference. */
internal interface DeveloperCoachingSettings {
    fun autoEnabled(): Boolean
    fun setAutoEnabled(value: Boolean)
}
/** Diagnostic output is not a user setting or a mission result. */
internal interface CoachingDiagnosticStatus {
    fun read(defaultValue: String = ""): String
    fun write(value: String)
}
