package com.example.itv.infrastructure.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * BroadcastReceiver triggered from notification action or notification tap
 * to immediately restore normal full color mode without opening the app.
 */
class GrayscaleStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = GrayscaleOverlayController.getInstance(context)
        if (controller.isActive()) {
            controller.disableGrayscale()
            Toast.makeText(context, "화면 색상이 원래대로 복원되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}
