package com.example.itv.infrastructure.sleep

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * Controls system-wide pure hardware monochrome/grayscale mode via
 * Android's Accessibility Display Daltonizer settings (SurfaceFlinger HWC matrix).
 *
 * This strips all color from every app, launcher, and video on screen.
 */
class GrayscaleOverlayController private constructor(private val context: Context) {
    private val resolver = context.contentResolver
    private var isGrayscaleActive = false
    private var isManualForced = false

    fun isActive(): Boolean = isGrayscaleActive
    fun isManual(): Boolean = isManualForced

    /**
     * Enables hardware monochrome/grayscale mode across the entire device.
     * @param forceManual If true, stays in grayscale even if the room is bright,
     *                    and only releases when manually canceled.
     */
    fun enableGrayscale(forceManual: Boolean = false, onComplete: (() -> Unit)? = null): Boolean {
        isManualForced = forceManual

        if (!hasPermission(context)) {
            Log.w("GrayscaleController", "WRITE_SECURE_SETTINGS not granted, redirecting to permission settings tab")
            Toast.makeText(context, "흑백 모드를 사용하려면 권한 설정이 필요합니다. 권한 설정 탭으로 이동합니다.", Toast.LENGTH_SHORT).show()
            openPermissionTab(context)
            return false
        }

        return try {
            // 0: Monochrome (흑백/그레이스케일)
            Settings.Secure.putInt(
                resolver,
                SETTING_DALTONIZER,
                0
            )
            Settings.Secure.putInt(
                resolver,
                SETTING_DALTONIZER_ENABLED,
                1
            )
            isGrayscaleActive = true
            Log.d("GrayscaleController", "Grayscale enabled successfully (forceManual=$forceManual)")
            showNotification(isAmber = false)
            onComplete?.invoke()
            true
        } catch (e: SecurityException) {
            Log.e("GrayscaleController", "WRITE_SECURE_SETTINGS security exception", e)
            Toast.makeText(context, "흑백 모드를 사용하려면 권한 설정이 필요합니다. 권한 설정 탭으로 이동합니다.", Toast.LENGTH_SHORT).show()
            openPermissionTab(context)
            false
        }
    }

    /**
     * Enables hardware warm color (blue light reduction / amber simulation).
     * Uses deuteranomaly simulation or overlay matrix where available.
     */
    fun enableWarmFilter(onComplete: (() -> Unit)? = null): Boolean {
        isManualForced = true

        if (!hasPermission(context)) {
            Log.w("GrayscaleController", "WRITE_SECURE_SETTINGS not granted, redirecting to permission settings tab")
            Toast.makeText(context, "화면 색상 모드를 사용하려면 권한 설정이 필요합니다.", Toast.LENGTH_SHORT).show()
            openPermissionTab(context)
            return false
        }

        return try {
            // 11: Deuteranomaly (녹색약/황색조 보정 - 블루라이트 성분을 대폭 억제하는 따뜻한 색감)
            Settings.Secure.putInt(
                resolver,
                SETTING_DALTONIZER,
                11
            )
            Settings.Secure.putInt(
                resolver,
                SETTING_DALTONIZER_ENABLED,
                1
            )
            isGrayscaleActive = true
            Log.d("GrayscaleController", "Warm blue-light filter enabled successfully")
            showNotification(isAmber = true)
            onComplete?.invoke()
            true
        } catch (e: SecurityException) {
            Log.e("GrayscaleController", "WRITE_SECURE_SETTINGS security exception", e)
            false
        }
    }

    /**
     * Disables monochrome/grayscale mode and restores normal full color display.
     */
    fun disableGrayscale(onComplete: (() -> Unit)? = null): Boolean {
        isManualForced = false
        cancelNotification()
        return try {
            Settings.Secure.putInt(
                resolver,
                SETTING_DALTONIZER_ENABLED,
                0
            )
            isGrayscaleActive = false
            Log.d("GrayscaleController", "Grayscale disabled successfully, color restored")
            onComplete?.invoke()
            true
        } catch (e: SecurityException) {
            Log.e("GrayscaleController", "WRITE_SECURE_SETTINGS not granted", e)
            false
        }
    }

    /**
     * Restores color (alias for disableGrayscale).
     */
    fun restoreColor(onComplete: (() -> Unit)? = null) {
        disableGrayscale(onComplete)
    }

    private fun showNotification(isAmber: Boolean) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = android.app.NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "수면 화면 모드 제어",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "야간 흑백 / 블루라이트 차단 화면 모드 제어 알림입니다."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(context, GrayscaleStopReceiver::class.java).apply {
            action = ACTION_STOP_GRAYSCALE
        }
        val stopPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            1,
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isAmber) "야간 블루라이트 차단 모드 켜짐" else "수면 흑백 모드 켜짐"
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(com.example.itv.R.drawable.ic_coaching_notification)
            .setContentTitle(title)
            .setContentText("알림을 탭하거나 아래 [원래 색상으로 복원]을 누르면 즉시 해제됩니다.")
            .setOngoing(true)
            .setContentIntent(stopPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .addAction(
                android.app.Notification.Action.Builder(
                    null,
                    "원래 색상으로 복원",
                    stopPendingIntent
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try {
                nm.notify(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private fun cancelNotification() {
        val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        try {
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_STOP_GRAYSCALE = "com.example.itv.ACTION_STOP_GRAYSCALE"
        const val NOTIFICATION_CHANNEL_ID = "itv_screen_filter_channel"
        const val NOTIFICATION_ID = 7105

        const val SETTING_DALTONIZER = "accessibility_display_daltonizer"
        const val SETTING_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"

        @Volatile
        private var instance: GrayscaleOverlayController? = null

        fun getInstance(context: Context): GrayscaleOverlayController {
            return instance ?: synchronized(this) {
                instance ?: GrayscaleOverlayController(context.applicationContext).also { instance = it }
            }
        }

        fun hasPermission(context: Context): Boolean {
            return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        }

        fun openPermissionTab(context: Context) {
            try {
                val appIntent = Intent(context, com.example.itv.presentation.permissions.PermissionSettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("initial_page", 1)
                    putExtra("permissions_only", true)
                }
                context.startActivity(appIntent)
            } catch (e: Exception) {
                Log.e("GrayscaleController", "Failed to open permission tab", e)
                openSystemSettings(context)
            }
        }

        fun openSystemSettings(context: Context) {
            // 1. Samsung One UI: 직접 '시인성 향상' (색상 조정/흑백) 페이지 열기
            val samsungIntent = Intent("com.samsung.accessibility.action.VISIBILITYENHANCEMENT").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (context.packageManager.resolveActivity(samsungIntent, 0) != null) {
                try {
                    context.startActivity(samsungIntent)
                    return
                } catch (_: Exception) {}
            }

            // 2. 표준 안드로이드 접근성 설정
            try {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                return
            } catch (_: Exception) {}

            // 3. 앱 자체 권한 설정 화면
            try {
                val appIntent = Intent(context, com.example.itv.presentation.permissions.PermissionSettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("initial_page", 1)
                }
                context.startActivity(appIntent)
            } catch (_: Exception) {}
        }

        fun openGrayscaleSettings(context: Context) {
            openSystemSettings(context)
        }
    }
}
