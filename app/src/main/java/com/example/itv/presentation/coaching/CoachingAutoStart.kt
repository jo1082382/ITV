package com.example.itv.presentation.coaching

import android.content.Context
import android.content.Intent

internal object CoachingAutoStart {
    fun testModeEnabled(c: Context): Boolean =
        com.example.itv.composition.CoachingAccess.developerAutoEnabled(c)

    fun jevEnabled(c: Context): Boolean =
        com.example.itv.infrastructure.jev.JevFeature.available() &&
            com.example.itv.infrastructure.jev.JevRuntime.get(c).enabled

    fun enabled(c: Context): Boolean = testModeEnabled(c) || jevEnabled(c)

    fun sync(c: Context) {
        com.example.itv.composition.CoachingAccess.channels(c)
        val intent = Intent(c, CoachingMonitorService::class.java)
        if (!enabled(c) || !com.example.itv.composition.CoachingAccess.ready(c)) {
            c.stopService(intent)
            return
        }
        try { c.startForegroundService(intent) }
        catch (_: Exception) {
            com.example.itv.composition.CoachingAccess.state(c, "자동 시작 실패: 앱을 다시 열거나 권한 설정을 확인해 주세요.")
        }
    }
    fun setEnabled(c: Context, value: Boolean) {
        com.example.itv.composition.CoachingAccess.setDeveloperAutoEnabled(c, value)
        sync(c)
    }
}
