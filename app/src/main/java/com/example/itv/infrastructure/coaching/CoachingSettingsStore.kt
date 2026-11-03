package com.example.itv.infrastructure.coaching

import android.content.Context
import com.example.itv.domain.coaching.DeveloperCoachingSettings
import com.example.itv.domain.coaching.CoachingDiagnosticStatus

// Keep the existing physical file and keys. Separate contracts, not a data migration.
private const val LEGACY_FILE = "coaching_test"
internal class PreferencesDeveloperCoachingSettings(context: Context) : DeveloperCoachingSettings {
    private val prefs = context.applicationContext.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
    override fun autoEnabled(): Boolean = prefs.getBoolean("autoEnabled", true)
    override fun setAutoEnabled(value: Boolean) { prefs.edit().putBoolean("autoEnabled", value).apply() }
}
internal class PreferencesCoachingDiagnosticStatus(context: Context) : CoachingDiagnosticStatus {
    private val prefs = context.applicationContext.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
    override fun read(defaultValue: String): String = prefs.getString("status", defaultValue).orEmpty()
    override fun write(value: String) { prefs.edit().putString("status", value).apply() }
}
