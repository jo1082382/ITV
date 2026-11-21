package com.example.itv.infrastructure.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent

/**
 * 개입(강한 개입, 보통 개입, 취침 케어) 발동 시
 * 실행 중인 숏폼(릴스, 쇼츠), 롱폼(유튜브), 음악/팟캐스트 등의 미디어 재생을 즉시 일시정지합니다.
 *
 * 사용자가 개입 화면에 가려진 채 영상 소리만 계속 흘러나와 반발심이나 불편함을 느끼지 않도록,
 * 1) 표준 미디어 일시정지 키 이벤트(KEYCODE_MEDIA_PAUSE) 전송
 * 2) 일시 오디오 포커스 획득(AUDIOFOCUS_GAIN_TRANSIENT)을 통해 외부 영상/음원 앱을 즉시 pause 시킵니다.
 */
object MediaPauseController {
    private var currentFocusRequest: AudioFocusRequest? = null

    fun pause(context: Context) {
        try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            // 1. 표준 미디어 일시정지 키 이벤트 전송 (MediaSession, ExoPlayer 등 즉시 pause)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))

            // 2. 오디오 포커스 일시 획득 (AUDIOFOCUS_GAIN_TRANSIENT):
            // 유튜브, 인스타그램 등 모든 비디오/음악 플레이어의 AudioFocusChangeListener가 작동하여 재생 일시정지
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()
                currentFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (_: Exception) {}
    }

    fun release(context: Context) {
        try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                currentFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                currentFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }
}
