package com.example.itv.infrastructure.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.view.KeyEvent

/**
 * 개입(강한 개입, 취침 케어 등) 발동 시
 * 현재 실제로 재생 중인 숏폼(릴스, 쇼츠), 롱폼(유튜브), 음악 등의 미디어만 안전하게 일시정지합니다.
 *
 * 주의사항:
 * - [KeyEvent.KEYCODE_HEADSETHOOK] / [KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE] 같은 토글 키는
 *   재생 중인 미디어가 없을 때 유튜브 뮤직 등 백그라운드 음악 앱을 새로 실행/재생시키므로 절대 전송하지 않습니다.
 * - 실제 미디어 오디오 파이프라인([AudioManager.isMusicActive] 또는 [AudioManager.getActivePlaybackConfigurations])이
 *   활성화된 경우에만 일시정지([KeyEvent.KEYCODE_MEDIA_PAUSE] + [AudioManager.AUDIOFOCUS_GAIN])를 수행합니다.
 */
object MediaPauseController {
    private var currentFocusRequest: AudioFocusRequest? = null
    private var silentTrack: AudioTrack? = null

    internal fun isMediaUsage(usage: Int): Boolean {
        return when (usage) {
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            -> true
            else -> false
        }
    }

    private fun hasActiveMediaPlayback(am: AudioManager): Boolean {
        val mode = am.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
            return false
        }
        if (am.isMusicActive) return true
        val configs = am.activePlaybackConfigurations
        return configs.any { cfg -> cfg.audioAttributes?.let { isMediaUsage(it.usage) } == true }
    }

    fun pause(context: Context) {
        try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            // 이전 무음 트랙이 남아 있다면 먼저 정리한 뒤 실제 외부 미디어 재생 여부를 검사
            stopSilentTrack()

            // 실제로 재생 중인 영상/미디어가 없으면 키 이벤트·포커스 요청을 보내지 않아
            // 유튜브 뮤직 등 백그라운드 미디어 앱이 의도치 않게 실행·재생되는 것을 원천 차단
            if (!hasActiveMediaPlayback(am)) return

            // 1. 순수 일시정지 키 이벤트(KEYCODE_MEDIA_PAUSE)만 전송 (토글 키 KEYCODE_HEADSETHOOK 절대 금지)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))

            // 2. 오디오 포커스 획득 (AUDIOFOCUS_GAIN + USAGE_MEDIA):
            // AUDIOFOCUS_GAIN_TRANSIENT 대신 AUDIOFOCUS_GAIN을 사용해,
            // 오버레이 종료(release) 시에도 일시정지된 영상/음악이 자동으로 다시 재생되지 않도록 함.
            currentFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* maintain focus */ }
                .build()
            currentFocusRequest = req
            am.requestAudioFocus(req)

            // 3. 실제 미디어 오디오 스트림을 활성화하기 위한 초경량 무음 트랙 구동
            // (인스타그램 릴스 등 KEYCODE_MEDIA_PAUSE를 무시하는 플레이어도 포커스 손실로 즉시 정지)
            val sampleRate = 8000
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, 1024))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            val silence = ByteArray(1024)
            track.write(silence, 0, silence.size)
            track.setVolume(0f)
            track.play()
            silentTrack = track
        } catch (_: Exception) {}
    }

    private fun stopSilentTrack() {
        silentTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        silentTrack = null
    }

    fun release(context: Context) {
        try {
            stopSilentTrack()

            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            currentFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            currentFocusRequest = null
        } catch (_: Exception) {}
    }
}
