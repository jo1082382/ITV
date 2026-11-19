package com.example.itv.ui.theme

import androidx.compose.runtime.Composable
import com.example.itv.infrastructure.theme.UserThemeStore
import com.example.itv.presentation.components.ItvVisualTheme

@Composable
fun ITVTheme(
    darkTheme: Boolean = UserThemeStore.rememberDarkMode(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    ItvVisualTheme(
        darkTheme = darkTheme,
        content = content
    )
}
