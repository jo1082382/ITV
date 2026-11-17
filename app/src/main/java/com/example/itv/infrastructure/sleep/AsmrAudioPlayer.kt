package com.example.itv.infrastructure.sleep

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.example.itv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Random

enum class AsmrType(val title: String, val description: String) {
    RAIN("포근한 밤 빗소리", "창가에 맺혀 톡톡 떨어지는 맑은 빗방울과 잔잔한 빗소리"),
    FIREPLACE("따스한 모닥불 장작소리", "타닥타닥 장작이 타오르는 소리와 아늑한 온기의 불길 소리"),
    DEEP_BREATH("강아지의 편안한 숨소리", "옆에서 새근새근 숨 쉬며 전해지는 편안한 호흡과 부드러운 심장 박동"),
    WAVE("잔잔한 밤바다 파도", "천천히 밀려왔다 모래를 적시며 빠져나가는 포근한 밤바다 파도 소리")
}

/**
 * High-fidelity, noise-free ASMR audio player with distinct acoustic models
 * and notification drawer controls for quick stop outside the app.
 */
class AsmrAudioPlayer private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null

    var currentPlayingType: AsmrType? = null
        private set

    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    fun isPlaying(): Boolean = playJob?.isActive == true

    fun play(type: AsmrType) {
        stop()
        currentPlayingType = type
        showNotification(type)
        onPlaybackStateChanged?.invoke(true)

        playJob = scope.launch {
            val sampleRate = 44100
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBufSize.coerceAtLeast(sampleRate / 2)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            try {
                track.play()
            } catch (_: Exception) {
                return@launch
            }

            val buffer = ShortArray(2048)
            val sampleRateD = sampleRate.toDouble()

            // Initialize distinct sound generator based on requested ASMR type
            val rainGen = if (type == AsmrType.RAIN) RainGenerator(sampleRateD) else null
            val fireGen = if (type == AsmrType.FIREPLACE) FireplaceGenerator(sampleRateD) else null
            val breathGen = if (type == AsmrType.DEEP_BREATH) DeepBreathGenerator(sampleRateD) else null
            val waveGen = if (type == AsmrType.WAVE) WaveGenerator(sampleRateD) else null

            while (isActive) {
                for (i in buffer.indices) {
                    val sampleValue: Double = when (type) {
                        AsmrType.RAIN -> rainGen?.nextSample() ?: 0.0
                        AsmrType.FIREPLACE -> fireGen?.nextSample() ?: 0.0
                        AsmrType.DEEP_BREATH -> breathGen?.nextSample() ?: 0.0
                        AsmrType.WAVE -> waveGen?.nextSample() ?: 0.0
                    }
                    val pcm = (sampleValue * 32767.0).coerceIn(-32767.0, 32767.0)
                    buffer[i] = pcm.toInt().toShort()
                }
                track.write(buffer, 0, buffer.size)
            }

            try {
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        currentPlayingType = null
        cancelNotification()
        onPlaybackStateChanged?.invoke(false)

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    private fun showNotification(type: AsmrType) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "수면 유도 사운드 (ASMR)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "재생 중인 수면 유도 사운드 제어 알림입니다."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(context, AsmrStopReceiver::class.java).apply {
            action = ACTION_STOP_ASMR
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_coaching_notification)
            .setContentTitle("수면 사운드 재생 중: ${type.title}")
            .setContentText("알림을 탭하거나 아래 [소리 끄기]를 눌러 바로 종료할 수 있습니다.")
            .setOngoing(true)
            .setContentIntent(stopPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "소리 끄기",
                    stopPendingIntent
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try {
                nm.notify(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private fun cancelNotification() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL_ID = "itv_sleep_sound_v1"
        const val NOTIFICATION_ID = 7104
        const val ACTION_STOP_ASMR = "com.example.itv.ACTION_STOP_ASMR"

        @Volatile
        private var instance: AsmrAudioPlayer? = null

        fun getInstance(context: Context): AsmrAudioPlayer {
            return instance ?: synchronized(this) {
                instance ?: AsmrAudioPlayer(context.applicationContext).also { instance = it }
            }
        }
    }

    // =========================================================================
    // DSP Acoustic Synthesis Helpers
    // =========================================================================

    /**
     * Standard 2nd-order Biquad filter (12dB/octave roll-off)
     */
    class Biquad {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0

        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun setLowPass(cutoff: Double, q: Double, sampleRate: Double = 44100.0) {
            val w0 = 2.0 * Math.PI * cutoff.coerceIn(20.0, sampleRate * 0.45) / sampleRate
            val cosW0 = Math.cos(w0)
            val alpha = Math.sin(w0) / (2.0 * q.coerceAtLeast(0.1))
            val a0 = 1.0 + alpha
            b0 = ((1.0 - cosW0) / 2.0) / a0
            b1 = (1.0 - cosW0) / a0
            b2 = ((1.0 - cosW0) / 2.0) / a0
            a1 = (-2.0 * cosW0) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun setBandPass(center: Double, q: Double, sampleRate: Double = 44100.0) {
            val w0 = 2.0 * Math.PI * center.coerceIn(20.0, sampleRate * 0.45) / sampleRate
            val cosW0 = Math.cos(w0)
            val alpha = Math.sin(w0) / (2.0 * q.coerceAtLeast(0.1))
            val a0 = 1.0 + alpha
            b0 = alpha / a0
            b1 = 0.0
            b2 = -alpha / a0
            a1 = (-2.0 * cosW0) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(input: Double): Double {
            val out = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = out
            return out
        }
    }

    /**
     * Paul Kellet's filter: Generates genuine 3dB/octave Pink Noise
     * with zero artificial resonance or harsh digital hiss.
     */
    class PinkNoise {
        private var b0 = 0.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var b3 = 0.0
        private var b4 = 0.0
        private var b5 = 0.0
        private var b6 = 0.0

        fun nextSample(white: Double): Double {
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
            b6 = white * 0.115926
            return pink * 0.11
        }
    }

    /**
     * Integrated Brown Noise: Deep, warm sub-bass body.
     */
    class BrownNoise {
        private var last = 0.0

        fun nextSample(white: Double): Double {
            last = (last + (0.02 * white)) / 1.02
            return last * 3.5
        }
    }

    /**
     * 1. RAIN GENERATOR:
     * Warm velvety rain bed (Pink noise + 1000Hz low-pass)
     * + Damped impact resonant raindrops (700Hz ~ 1400Hz decaying pings).
     */
    class RainGenerator(private val sampleRate: Double) {
        private val pink = PinkNoise()
        private val rainFilter = Biquad().apply { setLowPass(950.0, 0.707, sampleRate) }
        private val random = Random()

        private var dropSampleCount = 0
        private var dropDuration = 0
        private var dropFreq = 1000.0
        private var dropAmp = 0.0
        private var dropPhase = 0.0

        fun nextSample(): Double {
            val white = random.nextDouble() * 2.0 - 1.0
            val rainBed = rainFilter.process(pink.nextSample(white)) * 0.45

            // Random droplets (~3.5 per second on average)
            if (dropSampleCount >= dropDuration && random.nextInt((sampleRate / 3.5).toInt()) == 0) {
                dropSampleCount = 0
                dropDuration = (sampleRate * (0.015 + random.nextDouble() * 0.022)).toInt()
                dropFreq = 750.0 + random.nextDouble() * 650.0
                dropAmp = 0.20 + random.nextDouble() * 0.30
                dropPhase = 0.0
            }

            var droplet = 0.0
            if (dropSampleCount < dropDuration) {
                val progress = dropSampleCount.toDouble() / dropDuration
                val envelope = Math.sin(progress * Math.PI) * Math.exp(-progress * 3.5)
                val curFreq = dropFreq * (1.0 - 0.18 * progress)
                dropPhase += 2.0 * Math.PI * curFreq / sampleRate
                droplet = Math.sin(dropPhase) * envelope * dropAmp
                dropSampleCount++
            }

            return (rainBed + droplet).coerceIn(-0.95, 0.95)
        }
    }

    /**
     * 2. FIREPLACE GENERATOR:
     * Deep roaring convection flame draft + physical wood log acoustic pops
     * (sharp bandpassed snap + low log cavity resonance thump) + gentle ember sparks.
     */
    class FireplaceGenerator(private val sampleRate: Double) {
        private val brown = BrownNoise()
        private val flameFilter = Biquad().apply { setLowPass(240.0, 0.8, sampleRate) }
        private val snapFilter = Biquad().apply { setBandPass(2100.0, 2.5, sampleRate) }
        private val random = Random()

        private var lfoPhase = 0.0
        private var snapSampleCount = 0
        private var snapDuration = 0
        private var snapAmp = 0.0
        private var thumpPhase = 0.0
        private var thumpFreq = 220.0

        fun nextSample(): Double {
            val white = random.nextDouble() * 2.0 - 1.0
            lfoPhase += 2.0 * Math.PI * 4.8 / sampleRate
            val flameFlutter = 0.75 + 0.25 * Math.sin(lfoPhase) + 0.08 * (random.nextDouble() - 0.5)

            // Warm, deep roaring embers
            val flameBody = flameFilter.process(brown.nextSample(white)) * 0.50 * flameFlutter

            // Wood snap trigger (average 2~3 snaps per second)
            if (snapSampleCount >= snapDuration && random.nextInt((sampleRate / 2.8).toInt()) == 0) {
                snapSampleCount = 0
                snapDuration = (sampleRate * (0.010 + random.nextDouble() * 0.016)).toInt()
                snapAmp = 0.35 + random.nextDouble() * 0.45
                thumpFreq = 180.0 + random.nextDouble() * 80.0
                thumpPhase = 0.0
            }

            var snap = 0.0
            if (snapSampleCount < snapDuration) {
                val progress = snapSampleCount.toDouble() / snapDuration
                val snapEnv = Math.exp(-progress * 6.0)
                val snapNoise = snapFilter.process(white) * snapEnv * snapAmp

                // Wood log cavity acoustic "thump"
                thumpPhase += 2.0 * Math.PI * thumpFreq / sampleRate
                val thumpEnv = Math.exp(-progress * 7.0)
                val thump = Math.sin(thumpPhase) * thumpEnv * snapAmp * 0.45

                snap = snapNoise + thump
                snapSampleCount++
            }

            // Occasional micro-crackles
            val microCrackle = if (random.nextInt(4000) == 0) (random.nextDouble() - 0.5) * 0.12 else 0.0

            return (flameBody + snap + microCrackle).coerceIn(-0.95, 0.95)
        }
    }

    /**
     * 3. DEEP BREATH GENERATOR:
     * Realistic puppy respiratory cycle (4.8s):
     * Smooth nasal inhalation (620Hz bandpass) -> brief pause ->
     * warm soft exhalation (380Hz bandpass) -> resting pause
     * + Subtle rhythmic heartbeat (54 BPM "lub-dub").
     */
    class DeepBreathGenerator(private val sampleRate: Double) {
        private val pink = PinkNoise()
        private val breathFilterInhale = Biquad().apply { setBandPass(580.0, 1.8, sampleRate) }
        private val breathFilterExhale = Biquad().apply { setBandPass(360.0, 1.4, sampleRate) }
        private val random = Random()

        private val cycleLengthSamples = (4.8 * sampleRate).toInt()
        private var cycleSample = 0

        private val beatIntervalSamples = (1.11 * sampleRate).toInt()
        private var beatSample = 0
        private var heartPhase = 0.0

        fun nextSample(): Double {
            val white = random.nextDouble() * 2.0 - 1.0
            val pinkVal = pink.nextSample(white)

            val cyclePos = cycleSample.toDouble() / cycleLengthSamples
            cycleSample = (cycleSample + 1) % cycleLengthSamples

            var breathSound = 0.0

            // Phase 1: Inhalation (0.0 to 0.35, ~1.68s)
            if (cyclePos < 0.35) {
                val p = cyclePos / 0.35
                val env = Math.sin(p * Math.PI) * 0.46
                breathSound = breathFilterInhale.process(pinkVal) * env
            }
            // Phase 2: Pause (0.35 to 0.40, ~0.24s)
            else if (cyclePos < 0.40) {
                breathSound = 0.0
            }
            // Phase 3: Exhalation (0.40 to 0.82, ~2.01s) - longer, softer, warmer
            else if (cyclePos < 0.82) {
                val p = (cyclePos - 0.40) / 0.42
                val env = Math.sin(p * Math.PI) * Math.pow(1.0 - p * 0.35, 1.5) * 0.50
                breathSound = breathFilterExhale.process(pinkVal) * env
            }
            // Phase 4: Rest pause (0.82 to 1.0, ~0.86s)
            else {
                breathSound = 0.0
            }

            // Sub-bass heartbeat ("lub-dub" at 54 BPM)
            var heartSound = 0.0
            val beatPos = beatSample % beatIntervalSamples
            beatSample++

            val lubDuration = (0.045 * sampleRate).toInt()
            val dubOffset = (0.16 * sampleRate).toInt()
            val dubDuration = (0.035 * sampleRate).toInt()

            if (beatPos < lubDuration) {
                val p = beatPos.toDouble() / lubDuration
                val env = Math.sin(p * Math.PI) * Math.exp(-p * 3.0)
                heartPhase += 2.0 * Math.PI * 66.0 / sampleRate
                heartSound = Math.sin(heartPhase) * env * 0.15
            } else if (beatPos in dubOffset until (dubOffset + dubDuration)) {
                val p = (beatPos - dubOffset).toDouble() / dubDuration
                val env = Math.sin(p * Math.PI) * Math.exp(-p * 3.5)
                heartPhase += 2.0 * Math.PI * 60.0 / sampleRate
                heartSound = Math.sin(heartPhase) * env * 0.10
            }

            return (breathSound + heartSound).coerceIn(-0.95, 0.95)
        }
    }

    /**
     * 4. WAVE GENERATOR:
     * Dynamic oceanic surf cycle (8.8s):
     * Swell rising (cutoff sweeps 180Hz -> 750Hz) ->
     * Crest & Foam shore wash (820Hz + 950Hz foam bandpass) ->
     * Receding undertow (cutoff sweeps 650Hz -> 160Hz).
     */
    class WaveGenerator(private val sampleRate: Double) {
        private val brown = BrownNoise()
        private val pink = PinkNoise()
        private val waveFilter = Biquad()
        private val foamFilter = Biquad().apply { setBandPass(900.0, 1.2, sampleRate) }
        private val random = Random()

        private val wavePeriodSamples = (8.8 * sampleRate).toInt()
        private var waveSample = 0

        fun nextSample(): Double {
            val white = random.nextDouble() * 2.0 - 1.0
            val brownVal = brown.nextSample(white)
            val pinkVal = pink.nextSample(white)

            val pos = waveSample.toDouble() / wavePeriodSamples
            waveSample = (waveSample + 1) % wavePeriodSamples

            var waveSound = 0.0

            // Phase 1: Swell rising (0.0 to 0.42, ~3.7s)
            if (pos < 0.42) {
                val p = pos / 0.42
                val cutoff = 180.0 + 560.0 * (p * p)
                waveFilter.setLowPass(cutoff, 0.85, sampleRate)
                val volume = 0.14 + 0.42 * Math.sin(p * (Math.PI / 2.0))
                waveSound = waveFilter.process(brownVal) * volume
            }
            // Phase 2: Crest & Shore foam wash (0.42 to 0.56, ~1.2s)
            else if (pos < 0.56) {
                val p = (pos - 0.42) / 0.14
                waveFilter.setLowPass(800.0, 0.707, sampleRate)
                val oceanBody = waveFilter.process(brownVal) * (0.56 - 0.15 * p)
                val foamEnv = Math.sin(p * Math.PI) * 0.22
                val foam = foamFilter.process(pinkVal) * foamEnv
                waveSound = oceanBody + foam
            }
            // Phase 3: Receding undertow (0.56 to 1.0, ~3.9s)
            else {
                val p = (pos - 0.56) / 0.44
                val cutoff = 650.0 - 480.0 * Math.sqrt(p)
                waveFilter.setLowPass(cutoff.coerceAtLeast(160.0), 0.8, sampleRate)
                val volume = 0.14 + 0.28 * Math.pow(1.0 - p, 1.4)
                waveSound = waveFilter.process(brownVal) * volume
            }

            return waveSound.coerceIn(-0.95, 0.95)
        }
    }
}
