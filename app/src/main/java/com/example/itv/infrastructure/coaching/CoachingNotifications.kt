package com.example.itv.infrastructure.coaching

import android.Manifest
import android.app.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal object CoachingNotifications {
    const val MONITOR = "itv_coaching_monitor_v1"
    const val ALERT = "itv_coaching_alert_v1"
    const val MAILBOX = "itv_weekly_mailbox_v1"
    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(MONITOR, "강아지 테스트 감시", NotificationManager.IMPORTANCE_MIN).apply {
            description = "자동 관측 실행 상태입니다. 이 채널의 표시를 꺼도 관측은 유지됩니다."
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(ALERT, "강아지 휴식 제안", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(MAILBOX, "주간 코치 편지", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "매주 월요일 주간 코치 편지가 도착했을 때 방해되지 않는 타이밍에만 알려드려요."
        })
    }
    fun allowed(c: Context): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return nm.areNotificationsEnabled() &&
            (nm.getNotificationChannel(ALERT)?.importance ?: NotificationManager.IMPORTANCE_NONE) != NotificationManager.IMPORTANCE_NONE
    }
    fun mailboxAllowed(c: Context): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return nm.areNotificationsEnabled() &&
            (nm.getNotificationChannel(MAILBOX)?.importance ?: NotificationManager.IMPORTANCE_NONE) != NotificationManager.IMPORTANCE_NONE
    }
}
