package com.example.itv.infrastructure.observation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AmbientSnapshot(
    val lux: Float?,
    val proximityDistanceCm: Float?,
    val proximityMaxRangeCm: Float?,
    val isNear: Boolean,
    val isOccluded: Boolean, // 센서가 물체/손에 의해 가려짐
    val screenBrightness: Int?, // 0..255
    val nightModeActive: Boolean,
    val stateLabel: String,
    val explanation: String,
    val isSampling: Boolean = false, // 현재 센서 측정 중(윈도우 동작) 여부
    val lastTriggerReason: String = "초기 상태"
)

class LightAndBrightnessTracker(context: Context) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sm = appContext.getSystemService(SensorManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val lightSensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _snapshot = MutableStateFlow(
        readCurrent(
            lux = null,
            proximity = null,
            isSampling = false,
            triggerReason = "초기 상태"
        )
    )
    val snapshot = _snapshot.asStateFlow()

    private var currentLux: Float? = null
    private var currentProximity: Float? = null

    private var refCount = 0
    private var isScreenOn = powerManager?.isInteractive ?: true
    private var isSensorsRegistered = false

    private var sampleJob: Job? = null
    private var periodicJob: Job? = null
    private var lastObservedBrightness: Int? = null

    companion object {
        @Volatile
        private var defaultInstance: LightAndBrightnessTracker? = null

        fun getInstance(context: Context): LightAndBrightnessTracker {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: LightAndBrightnessTracker(context.applicationContext).also {
                    defaultInstance = it
                }
            }
        }

        fun notifyAppSwitched() {
            defaultInstance?.onAppSwitched()
        }
    }

    init {
        if (defaultInstance == null) {
            defaultInstance = this
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
            }
        }
    }

    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            onBrightnessChanged()
        }
    }

    fun start() {
        refCount++
        if (refCount > 1) return

        // 1. 화면 On/Off 브로드캐스트 리시버 등록
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(screenReceiver, filter)
            }
        } catch (_: Exception) {}

        // 2. 화면 밝기 변경 ContentObserver 등록
        try {
            appContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                brightnessObserver
            )
        } catch (_: Exception) {}

        // 3. 현재 화면 상태 체크
        isScreenOn = powerManager?.isInteractive ?: true
        if (isScreenOn) {
            triggerSampleWindow(durationMs = 3500L, reason = "감지 시작 (초기 샘플링)", force = true)
            schedulePeriodicSampling()
        } else {
            stopSensors()
        }
    }

    fun stop() {
        if (refCount <= 0) return
        refCount--
        if (refCount > 0) return

        try {
            appContext.unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}

        try {
            appContext.contentResolver.unregisterContentObserver(brightnessObserver)
        } catch (_: Exception) {}

        periodicJob?.cancel()
        periodicJob = null
        sampleJob?.cancel()
        sampleJob = null

        stopSensors()
    }

    fun refresh() {
        triggerSampleWindow(durationMs = 3500L, reason = "수동 새로고침", force = true)
    }

    fun onAppSwitched() {
        if (refCount <= 0 || !isScreenOn) return
        triggerSampleWindow(durationMs = 3500L, reason = "앱 전환 감지", force = false)
    }

    private fun onScreenOff() {
        isScreenOn = false
        // 화면이 꺼지면 즉시 센서 등록 해제 및 타이머 취소 (배터리 소모 0)
        periodicJob?.cancel()
        periodicJob = null
        sampleJob?.cancel()
        sampleJob = null
        stopSensors()
        _snapshot.value = _snapshot.value.copy(
            isSampling = false,
            lastTriggerReason = "화면 꺼짐 (센서 즉시 해제됨)"
        )
    }

    private fun onScreenOn() {
        isScreenOn = true
        triggerSampleWindow(durationMs = 3500L, reason = "화면 켜짐 감지", force = false)
        schedulePeriodicSampling()
    }

    private fun onBrightnessChanged() {
        if (refCount <= 0 || !isScreenOn) return
        val currentBrightness = readScreenBrightness()
        val last = lastObservedBrightness
        lastObservedBrightness = currentBrightness
        if (last == null || abs(currentBrightness - last) >= 15) {
            triggerSampleWindow(durationMs = 3500L, reason = "화면 밝기 변경 감지", force = false)
        }
    }

    private fun schedulePeriodicSampling() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (isActive && isScreenOn) {
                delay(5 * 60 * 1000L) // 5분
                if (isActive && isScreenOn) {
                    triggerSampleWindow(durationMs = 3500L, reason = "5분 정기 절전 측정", force = false)
                }
            }
        }
    }

    fun triggerSampleWindow(durationMs: Long = 3500L, reason: String = "측정", force: Boolean = false) {
        if (!isScreenOn) return

        startSensors()
        _snapshot.value = _snapshot.value.copy(
            isSampling = true,
            lastTriggerReason = reason
        )

        sampleJob?.cancel()
        sampleJob = scope.launch {
            delay(durationMs)
            stopSensors()
            update(force = force, isSampling = false, triggerReason = "$reason (측정 완료)")
        }
    }

    private fun startSensors() {
        if (isSensorsRegistered) return
        if (sm != null) {
            lightSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            proximitySensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            isSensorsRegistered = true
        }
    }

    private fun stopSensors() {
        if (!isSensorsRegistered) return
        sm?.unregisterListener(this)
        isSensorsRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_LIGHT -> {
                currentLux = event.values.firstOrNull()
                update(force = false, isSampling = true, triggerReason = _snapshot.value.lastTriggerReason)
            }
            Sensor.TYPE_PROXIMITY -> {
                currentProximity = event.values.firstOrNull()
                update(force = false, isSampling = true, triggerReason = _snapshot.value.lastTriggerReason)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun update(force: Boolean, isSampling: Boolean, triggerReason: String) {
        val candidate = readCurrent(currentLux, currentProximity, isSampling, triggerReason)
        val current = _snapshot.value

        if (force || shouldPublishUpdate(current, candidate) || current.isSampling != isSampling) {
            _snapshot.value = candidate
        }
    }

    private fun shouldPublishUpdate(old: AmbientSnapshot, new: AmbientSnapshot): Boolean {
        // 1. 센서 최초 감지 (대기 상태에서 첫 값이 들어온 경우)
        if (old.lux == null && new.lux != null) return true
        if (old.proximityDistanceCm == null && new.proximityDistanceCm != null) return true

        // 2. 가림 판정 여부 변경 (손/물체 가림 또는 가림 해제)
        if (old.isOccluded != new.isOccluded) return true

        // 3. 근접 상태 변경 (원거리 ↔ 근접)
        if (old.isNear != new.isNear) return true

        // 4. 환경 상태 판별 라벨 변경 (취침 전, 야간 시청, 일반 실내, 주간 활동 등)
        if (old.stateLabel != new.stateLabel) return true

        // 5. 다크 모드 또는 화면 밝기 대폭 변경
        if (old.nightModeActive != new.nightModeActive) return true
        if (old.screenBrightness != new.screenBrightness &&
            abs((old.screenBrightness ?: 0) - (new.screenBrightness ?: 0)) >= 30
        ) return true

        // 6. 조도 센서: 판단이 바뀌지 않는 미세 변동(예: 2~5 lux 플리커링)은 초기값 유지
        // 환경이 실질적으로 변했다고 볼 수 있는 큰 폭의 조도 변화(30 lux 이상 및 50% 이상 변화) 시에만 갱신
        val oldLux = old.lux
        val newLux = new.lux
        if (oldLux != null && newLux != null) {
            val diff = abs(newLux - oldLux)
            val relDiff = diff / max(oldLux, 1f)
            if (diff >= 30f && relDiff >= 0.5f) {
                return true
            }
        }

        return false
    }

    private fun readScreenBrightness(): Int {
        return try {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            0
        }
    }

    private fun readCurrent(
        lux: Float?,
        proximity: Float?,
        isSampling: Boolean,
        triggerReason: String
    ): AmbientSnapshot {
        val brightness = try {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            null
        }

        val nightModeFlags = appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val nightMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

        val maxRange = proximitySensor?.maximumRange ?: 5f
        val isNear = proximity != null && (proximity < maxRange || proximity == 0f)
        val isOccluded = isNear && (lux == null || lux <= 10f)

        val isDarkAmbient = lux != null && lux <= 15f
        val isBrightAmbient = lux != null && lux > 200f
        val isDarkScreen = brightness != null && brightness <= 75
        val isBrightScreen = brightness != null && brightness > 180

        val (label, explanation) = when {
            isOccluded -> "센서 가림 감지 (손/물체 차단 의심)" to
                "근접 센서가 근접 상태(${proximity}cm)이고 조도가 극도로 낮습니다(${lux?.toInt() ?: 0} lux). 손이나 이불, 거치대 등으로 상단 센서가 가려져 조도가 낮게 측정되었을 가능성이 높습니다."
            isNear && !isDarkAmbient -> "근접 감지 (물체 접근 중)" to
                "상단에 물체/손이 근접해 있으나(${proximity}cm) 주변 조도(${lux?.toInt() ?: 0} lux)가 남아있는 상태입니다."
            lux == null -> "조도 센서 대기 중" to "센서 샘플링 대기 중이거나 측정값을 수신하는 중입니다."
            isDarkAmbient && isDarkScreen -> "취침 전 상태 (어두운 조도 + 어두운 화면)" to
                "상단 가림 없이 주변이 어둡고(조도 ${lux.toInt()} lux) 화면 밝기도 낮아(${brightness}/255, ${if (nightMode) "다크모드 ON" else "다크모드 OFF"}), 불을 끄고 누워 있거나 취침 준비 중일 가능성이 높습니다."
            isDarkAmbient && !isDarkScreen -> "야간 시청 상태 (어두운 조도 + 보통/밝은 화면)" to
                "상단 가림 없이 주변은 어두우나(조도 ${lux.toInt()} lux) 화면이 밝습니다. 눈의 피로도가 높고 수면 지연 중일 가능성이 있습니다."
            isBrightAmbient && isBrightScreen -> "주간/밝은 활동 상태 (밝은 조도 + 밝은 화면)" to
                "주변 조도가 밝고(${lux.toInt()} lux) 화면도 밝아 활발한 일상 활동 상태입니다."
            !isDarkAmbient && !isBrightAmbient -> "일반 실내 활동 상태 (보통 조도)" to
                "실내 조명 환경(조도 ${lux.toInt()} lux, 밝기 ${brightness ?: "-"} / 255)에서의 일반적인 사용 상태입니다."
            else -> "일반 사용 상태" to
                "조도 ${lux.toInt()} lux, 화면 밝기 ${brightness ?: "-"} / 255, ${if (nightMode) "다크모드 ON" else "다크모드 OFF"}"
        }

        return AmbientSnapshot(
            lux = lux,
            proximityDistanceCm = proximity,
            proximityMaxRangeCm = proximitySensor?.maximumRange,
            isNear = isNear,
            isOccluded = isOccluded,
            screenBrightness = brightness,
            nightModeActive = nightMode,
            stateLabel = label,
            explanation = explanation,
            isSampling = isSampling,
            lastTriggerReason = triggerReason
        )
    }
}
