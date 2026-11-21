package com.example.itv.infrastructure.coaching

import android.content.Context
import android.content.SharedPreferences
import com.example.itv.domain.coaching.CharacterIntensity
import com.example.itv.domain.coaching.CoachingIntensity
import com.example.itv.domain.coaching.CoachingTone
import com.example.itv.domain.coaching.InterventionType
import com.example.itv.domain.coaching.UserCoachingSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserCoachingSettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserCoachingSettings> = _settings.asStateFlow()

    fun loadSettings(): UserCoachingSettings {
        val typeStr = prefs.getString(KEY_INTERVENTION_TYPE, InterventionType.CHARACTER_OVERLAY.name)
        val coachingIntensityStr = prefs.getString(KEY_COACHING_INTENSITY, CoachingIntensity.NORMAL.name)
        val characterIntensityStr = prefs.getString(KEY_CHARACTER_INTENSITY, CharacterIntensity.NORMAL.name)
        val toneStr = prefs.getString(KEY_TONE, CoachingTone.SOFT.name)

        val interventionType = runCatching {
            InterventionType.valueOf(typeStr ?: InterventionType.CHARACTER_OVERLAY.name)
        }.getOrDefault(InterventionType.CHARACTER_OVERLAY)

        val coachingIntensity = runCatching {
            CoachingIntensity.valueOf(coachingIntensityStr ?: CoachingIntensity.NORMAL.name)
        }.getOrDefault(CoachingIntensity.NORMAL)

        val characterIntensity = runCatching {
            CharacterIntensity.valueOf(characterIntensityStr ?: CharacterIntensity.NORMAL.name)
        }.getOrDefault(CharacterIntensity.NORMAL)

        val tone = runCatching {
            CoachingTone.valueOf(toneStr ?: CoachingTone.SOFT.name)
        }.getOrDefault(CoachingTone.SOFT)

        val bedtimeCareEnabled = prefs.getBoolean(KEY_BEDTIME_CARE_ENABLED, true)
        val customBedtimeHour = if (prefs.contains(KEY_CUSTOM_BEDTIME_HOUR)) prefs.getInt(KEY_CUSTOM_BEDTIME_HOUR, 23) else null
        val customBedtimeMinute = if (prefs.contains(KEY_CUSTOM_BEDTIME_MINUTE)) prefs.getInt(KEY_CUSTOM_BEDTIME_MINUTE, 30) else null

        return UserCoachingSettings(
            interventionType = interventionType,
            coachingIntensity = coachingIntensity,
            characterIntensity = characterIntensity,
            tone = tone,
            bedtimeCareEnabled = bedtimeCareEnabled,
            customBedtimeHour = customBedtimeHour,
            customBedtimeMinute = customBedtimeMinute
        )
    }

    fun updateInterventionType(type: InterventionType) {
        prefs.edit().putString(KEY_INTERVENTION_TYPE, type.name).apply()
        _settings.value = _settings.value.copy(interventionType = type)
    }

    fun updateCoachingIntensity(intensity: CoachingIntensity) {
        prefs.edit().putString(KEY_COACHING_INTENSITY, intensity.name).apply()
        _settings.value = _settings.value.copy(coachingIntensity = intensity)
    }

    fun updateIntensity(intensity: CoachingIntensity) = updateCoachingIntensity(intensity)

    fun updateCharacterIntensity(intensity: CharacterIntensity) {
        prefs.edit().putString(KEY_CHARACTER_INTENSITY, intensity.name).apply()
        _settings.value = _settings.value.copy(characterIntensity = intensity)
    }

    fun updateTone(tone: CoachingTone) {
        prefs.edit().putString(KEY_TONE, tone.name).apply()
        _settings.value = _settings.value.copy(tone = tone)
    }

    fun updateBedtimeCareEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BEDTIME_CARE_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(bedtimeCareEnabled = enabled)
    }

    fun updateCustomBedtime(hour: Int?, minute: Int?) {
        val editor = prefs.edit()
        if (hour != null && minute != null) {
            editor.putInt(KEY_CUSTOM_BEDTIME_HOUR, hour)
            editor.putInt(KEY_CUSTOM_BEDTIME_MINUTE, minute)
        } else {
            editor.remove(KEY_CUSTOM_BEDTIME_HOUR)
            editor.remove(KEY_CUSTOM_BEDTIME_MINUTE)
        }
        editor.apply()
        _settings.value = _settings.value.copy(customBedtimeHour = hour, customBedtimeMinute = minute)
    }

    companion object {
        private const val PREFS_NAME = "user_coaching_settings_prefs"
        private const val KEY_INTERVENTION_TYPE = "key_intervention_type"
        private const val KEY_COACHING_INTENSITY = "key_coaching_intensity"
        private const val KEY_CHARACTER_INTENSITY = "key_character_intensity"
        private const val KEY_TONE = "key_tone"
        private const val KEY_BEDTIME_CARE_ENABLED = "key_bedtime_care_enabled"
        private const val KEY_CUSTOM_BEDTIME_HOUR = "key_custom_bedtime_hour"
        private const val KEY_CUSTOM_BEDTIME_MINUTE = "key_custom_bedtime_minute"

        @Volatile
        private var instance: UserCoachingSettingsStore? = null

        fun getInstance(context: Context): UserCoachingSettingsStore {
            return instance ?: synchronized(this) {
                instance ?: UserCoachingSettingsStore(context).also { instance = it }
            }
        }
    }
}
