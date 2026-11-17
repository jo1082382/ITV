package com.example.itv.infrastructure.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * BroadcastReceiver triggered from notification action or notification tap
 * to immediately stop ASMR sound playback without opening the app.
 */
class AsmrStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val player = AsmrAudioPlayer.getInstance(context)
        if (player.isPlaying()) {
            player.stop()
            Toast.makeText(context, "수면 사운드를 종료했습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}
