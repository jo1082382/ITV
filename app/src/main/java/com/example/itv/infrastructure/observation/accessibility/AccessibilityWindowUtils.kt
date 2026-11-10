package com.example.itv.infrastructure.observation.accessibility

import android.view.accessibility.AccessibilityWindowInfo
import com.example.itv.infrastructure.jev.JevObservationHub

internal fun AccessibilityWindowInfo.isTransientSystemWindow(): Boolean {
    val r = root ?: return false
    return try {
        JevObservationHub.isTransientSystemPackage(r.packageName?.toString())
    } finally {
        @Suppress("DEPRECATION") r.recycle()
    }
}

internal fun findActiveApplicationWindow(windows: List<AccessibilityWindowInfo>?): AccessibilityWindowInfo? {
    if (windows.isNullOrEmpty()) return null
    val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    return appWindows.firstOrNull { it.isActive && !it.isTransientSystemWindow() }
        ?: appWindows.firstOrNull { it.isFocused && !it.isTransientSystemWindow() }
        ?: appWindows.firstOrNull { !it.isTransientSystemWindow() }
        ?: appWindows.firstOrNull { it.isActive }
        ?: appWindows.firstOrNull { it.isFocused }
        ?: appWindows.firstOrNull()
}
