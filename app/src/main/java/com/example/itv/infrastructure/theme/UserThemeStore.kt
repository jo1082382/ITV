package com.example.itv.infrastructure.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

enum class SeasonThemeMode(val label: String, val emoji: String) {
    AUTO("자동 (현재 계절)", "🗓️"),
    WINTER("겨울 · 크리스마스", "🎄"),
    SPRING("봄 · 벚꽃", "🌸"),
    SUMMER("여름 · 반짝임", "✨"),
    AUTUMN("가을 · 단풍", "🍂"),
    OFF("끄기 (기본 모눈)", "⬜")
}

object UserThemeStore {
    private const val PREF_NAME = "itv_theme_prefs_v1"
    private const val KEY_DARK_MODE = "key_dark_mode"
    private const val KEY_SEASON_MODE = "key_season_mode"

    private val _darkModeState = MutableStateFlow(false)
    val darkModeState: StateFlow<Boolean> = _darkModeState.asStateFlow()

    private val _seasonModeState = MutableStateFlow(SeasonThemeMode.AUTO)
    val seasonModeState: StateFlow<SeasonThemeMode> = _seasonModeState.asStateFlow()

    private var initialized = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun initialize(context: Context) {
        if (!initialized) {
            val p = prefs(context)
            _darkModeState.value = p.getBoolean(KEY_DARK_MODE, false)
            val savedSeason = p.getString(KEY_SEASON_MODE, SeasonThemeMode.AUTO.name)
            _seasonModeState.value = runCatching {
                SeasonThemeMode.valueOf(savedSeason ?: SeasonThemeMode.AUTO.name)
            }.getOrDefault(SeasonThemeMode.AUTO)
            initialized = true
        }
    }

    fun isDarkMode(context: Context): Boolean {
        initialize(context)
        return _darkModeState.value
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        initialize(context)
        prefs(context).edit().putBoolean(KEY_DARK_MODE, enabled).apply()
        _darkModeState.value = enabled
    }

    fun toggleDarkMode(context: Context): Boolean {
        initialize(context)
        val next = !_darkModeState.value
        setDarkMode(context, next)
        return next
    }

    fun getSeasonMode(context: Context): SeasonThemeMode {
        initialize(context)
        return _seasonModeState.value
    }

    fun setSeasonMode(context: Context, mode: SeasonThemeMode) {
        initialize(context)
        prefs(context).edit().putString(KEY_SEASON_MODE, mode.name).apply()
        _seasonModeState.value = mode
    }

    fun cycleSeasonMode(context: Context): SeasonThemeMode {
        initialize(context)
        val all = SeasonThemeMode.entries
        val nextIndex = (all.indexOf(_seasonModeState.value) + 1) % all.size
        val next = all[nextIndex]
        setSeasonMode(context, next)
        return next
    }

    /**
     * Resolves AUTO into the real calendar season (11~2월 = 겨울 · 크리스마스).
     */
    fun resolveActiveSeason(
        mode: SeasonThemeMode,
        month: Int = LocalDate.now().monthValue
    ): SeasonThemeMode {
        if (mode != SeasonThemeMode.AUTO) return mode
        return when (month) {
            3, 4, 5 -> SeasonThemeMode.SPRING
            6, 7, 8 -> SeasonThemeMode.SUMMER
            9, 10 -> SeasonThemeMode.AUTUMN
            else -> SeasonThemeMode.WINTER // 11, 12, 1, 2월
        }
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().commit()
        _darkModeState.value = false
        _seasonModeState.value = SeasonThemeMode.AUTO
        initialized = false
    }

    internal fun setDarkModeForTesting(enabled: Boolean) {
        _darkModeState.value = enabled
        initialized = true
    }

    @Composable
    fun rememberDarkMode(): Boolean {
        val context = LocalContext.current
        initialize(context)
        val isDark by darkModeState.collectAsState()
        return isDark
    }

    @Composable
    fun rememberSeasonMode(): SeasonThemeMode {
        val context = LocalContext.current
        initialize(context)
        val mode by seasonModeState.collectAsState()
        return mode
    }
}
