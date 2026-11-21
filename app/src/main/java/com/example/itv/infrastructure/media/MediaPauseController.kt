package com.example.itv.infrastructure.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.view.KeyEvent

/**
 * 개입(강한 개입, 보통 개입, 취침 케어) 발동 시
 * 실행 중인 숏폼(릴스, 쇼츠), 롱폼(유튜브), 음악/팟캐스트 등의 미디어 재생을 즉시 일시정지합니다.
 *
 * 사용자가 개입 화면에 가려진 채 영상 소리만 계속 흘러나와 반발심이나 불편함을 느끼지 않도록,
 * 1) 표준 미디어 일시정지 키 이벤트(KEYCODE_MEDIA_PAUSE / KEYCODE_HEADSETHOOK) 전송
 * 2) 미디어 스트림 오디오 포커스 획득(AUDIOFOCUS_GAIN_TRANSIENT + USAGE_MEDIA) 및 무음 트랙 구동을 통해
 *    유튜브, 인스타그램, 넷플릭스 등 외부 미디어 플레이어의 재생을 확실히 일시정지(pause)시킵니다.
 */
object MediaPauseController {
    private var currentFocusRequest: AudioFocusRequest? = null
    private var silentTrack: AudioTrack? = null

    fun pause(context: Context) {
        try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            // 1. 표준 미디어 일시정지 키 이벤트 전송
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))

            // 2. 오디오 포커스 일시 획득 (USAGE_MEDIA):
            // 유튜브 등 미디어 플레이어는 USAGE_MEDIA 요청을 수신해야만 소리만 줄이는 DUCK이 아닌 실제 PAUSE로 전환됩니다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setWillPauseWhenDucked(true)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* maintain focus */ }
                    .build()
                currentFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }

            // 3. 실제 미디어 오디오 스트림을 활성화하기 위한 초경량 무음 트랙 구동
            // (시스템이 빈 포커스를 무시하지 않고 유튜브/릴스의 오디오 파이프라인을 완전히 가로막도록 보장)
            if (silentTrack == null) {
                val sampleRate = 8000
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 1024))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                val silence = ByteArray(1024)
                track.write(silence, 0, silence.size)
                track.setVolume(0f)
                track.play()
                silentTrack = track
            }
        } catch (_: Exception) {}
    }

    fun release(context: Context) {
        try {
            silentTrack?.let {
                try {
                    it.stop()
                    it.release()
                } catch (_: Exception) {}
            }
            silentTrack = null

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
