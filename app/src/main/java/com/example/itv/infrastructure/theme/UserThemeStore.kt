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

object UserThemeStore {
    private const val PREF_NAME = "itv_theme_prefs_v1"
    private const val KEY_DARK_MODE = "key_dark_mode"

    private val _darkModeState = MutableStateFlow(false)
    val darkModeState: StateFlow<Boolean> = _darkModeState.asStateFlow()
    private var initialized = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun initialize(context: Context) {
        if (!initialized) {
            val p = prefs(context)
            _darkModeState.value = p.getBoolean(KEY_DARK_MODE, false)
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

    fun reset(context: Context) {
        prefs(context).edit().clear().commit()
        _darkModeState.value = false
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
}
