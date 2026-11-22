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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
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
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class BedtimePhase {
    ACTIVE_HOURS,   // 평소 취침 시간과 먼 일상 활동 시간대
    APPROACHING,    // 평소 취침 시각 120분 이내 (취침 임박)
    PAST_BEDTIME,   // 평소 취침 시각 경과 (심야 수면 시간대)
    UNKNOWN         // 추정 취침 시각 정보 없음
}

enum class UserEnvironmentState {
    OUTDOOR_BRIGHT,       // 야외/강한 햇빛
    INDOOR_ACTIVE,        // 주간/실내 일상 활동
    INDOOR_DARK_ACTIVE,   // 일상 시간대 암실 사용
    PRE_SLEEP_REST,       // 취침 전 불 끄고 수면 준비 (어두운 조도 + 어두운 화면)
    BEDTIME_DELAY_WATCH,  // 취침 시간대 수면 지연 (어두운 조도 + 밝은 화면 응시)
    NIGHT_ACTIVE,         // 취침 시간대이나 조명 켜고 활동 중
    SENSOR_OCCLUDED,      // 센서 가림 (손/침구류 차단)
    UNKNOWN
}

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
    val lastTriggerReason: String = "초기 상태",
    val estimatedBedtimeHour: Int? = null,
    val estimatedBedtimeMinute: Int? = null,
    val bedtimeDistanceMinutes: Int? = null,
    val bedtimePhase: BedtimePhase = BedtimePhase.UNKNOWN,
    val userEnvironmentState: UserEnvironmentState = UserEnvironmentState.INDOOR_ACTIVE,
    val estimatedBedtimeSource: String = "미측정",
    val posture: com.example.itv.domain.coaching.UserPosture = com.example.itv.domain.coaching.UserPosture.UNKNOWN,
    val pitchDegrees: Float? = null,
    val rollDegrees: Float? = null,
    val gravityZ: Float? = null,
    val postureExplanation: String = "자세 판별 대기 중",
    val isStationary: Boolean = false,
    val isCharging: Boolean? = null,
    val isWifiConnected: Boolean? = null,
    val bedtimeScore: Int = 0,
    val isBedtimeCandidate: Boolean = false,
    val bedtimeScoreBreakdown: String = ""
)

class LightAndBrightnessTracker(context: Context) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sm = appContext.getSystemService(SensorManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val lightSensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val gravitySensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentLux: Float? = null
    private var currentProximity: Float? = null
    private var currentGx: Float? = null
    private var currentGy: Float? = null
    private var currentGz: Float? = null
    private var facedownMonitoring = false
    private var facedownObserved = false
    private var facedownAccumulatedMs = 0L
    private var lastFacedownSampleAt = 0L
    private var lastSampleWasFacedown = false

    private val motionSamples = java.util.ArrayDeque<FloatArray>(20)
    private var isCurrentlyStationary = false

    private fun trackMotion(gx: Float, gy: Float, gz: Float) {
        synchronized(motionSamples) {
            motionSamples.addLast(floatArrayOf(gx, gy, gz))
            if (motionSamples.size > 15) {
                motionSamples.removeFirst()
            }
            if (motionSamples.size >= 8) {
                var sumX = 0f; var sumY = 0f; var sumZ = 0f
                for (s in motionSamples) {
                    sumX += s[0]; sumY += s[1]; sumZ += s[2]
                }
                val n = motionSamples.size.toFloat()
                val meanX = sumX / n; val meanY = sumY / n; val meanZ = sumZ / n
                var varX = 0f; var varY = 0f; var varZ = 0f
                for (s in motionSamples) {
                    val dx = s[0] - meanX; val dy = s[1] - meanY; val dz = s[2] - meanZ
                    varX += dx * dx; varY += dy * dy; varZ += dz * dz
                }
                val totalVariance = (varX + varY + varZ) / n
                isCurrentlyStationary = totalVariance < 0.035f
            }
        }
    }

    private var refCount = 0
    private var isScreenOn = powerManager?.isInteractive ?: true
    private var isSensorsRegistered = false

    private var sampleJob: Job? = null
    private var periodicJob: Job? = null
    private var lastObservedBrightness: Int? = null

    private var estimatedStartHour: Int? = null
    private var estimatedStartMinute: Int? = null
    private var estimatedSource: String = "미측정"

    private val _snapshot = MutableStateFlow(
        readCurrent(
            lux = null,
            proximity = null,
            isSampling = false,
            triggerReason = "초기 상태"
        )
    )
    val snapshot = _snapshot.asStateFlow()

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
        loadBedtimeEstimates()
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
        loadBedtimeEstimates()
        triggerSampleWindow(durationMs = 3500L, reason = "수동 새로고침", force = true)
    }

    private fun loadBedtimeEstimates() {
        scope.launch(Dispatchers.IO) {
            try {
                val db = com.example.itv.data.local.ItvDatabase.get(appContext)
                val today = LocalDate.now()
                val dates = (0L..30L).map { today.minusDays(it).toString() }
                val confirmationStore = com.example.itv.infrastructure.records.RoomSleepConfirmationStore(db, "demo-guest")
                val confirmations = dates.mapNotNull { d -> confirmationStore.read(d)?.let { d to it } }.toMap()
                val nightReport = try {
                    com.example.itv.composition.queryNightReport(appContext, 30)
                } catch (_: Exception) { null }

                val coachingSettings = com.example.itv.infrastructure.coaching.UserCoachingSettingsStore(appContext).loadSettings()

                var h: Int? = null
                var m: Int? = null
                var src = "기본값 (23:30)"

                if (coachingSettings.customBedtimeHour != null && coachingSettings.customBedtimeMinute != null) {
                    h = coachingSettings.customBedtimeHour
                    m = coachingSettings.customBedtimeMinute
                    src = "직접 지정한 평소 취침 시각"
                } else {
                    val zone = nightReport?.zone ?: ZoneId.systemDefault()
                    val weeklyProfile = com.example.itv.domain.records.SleepSummaryPolicy.estimateWeeklySleepProfile(
                        confirmations = confirmations,
                        candidates = nightReport?.candidates.orEmpty(),
                        zone = zone
                    )

                    if (weeklyProfile != null) {
                        val adaptiveTarget = com.example.itv.domain.records.SleepSummaryPolicy.getAdaptiveBedtimeTarget(
                            weeklyProfile = weeklyProfile,
                            now = ZonedDateTime.now(zone)
                        )
                        h = adaptiveTarget.targetHour
                        m = adaptiveTarget.targetMinute
                        src = adaptiveTarget.sourceDescription
                    } else {
                        val goal = db.dao().goal("demo-guest", "BEDTIME")
                        val goalDetail = goal?.goalId?.let { db.dao().bedtimeGoal(it) }
                        if (goal?.enabled == true && goalDetail != null) {
                            h = goalDetail.localMinuteOfDay / 60
                            m = goalDetail.localMinuteOfDay % 60
                            src = "설정된 취침 목표"
                        } else {
                            h = 23
                            m = 30
                            src = "기본 기준 시각 (23:30)"
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    estimatedStartHour = h
                    estimatedStartMinute = m
                    estimatedSource = src
                    update(force = true, isSampling = _snapshot.value.isSampling, triggerReason = "취침 추정 정보 갱신")
                }
            } catch (_: Exception) {}
        }
    }

    fun refreshBedtimeEstimates() {
        loadBedtimeEstimates()
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
            gravitySensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            isSensorsRegistered = true
        }
    }

    private var continuousPostureClients = 0

    fun startContinuousPostureListening() {
        continuousPostureClients++
        startSensors()
    }

    fun stopContinuousPostureListening() {
        continuousPostureClients = maxOf(0, continuousPostureClients - 1)
        if (continuousPostureClients == 0 && sampleJob?.isActive != true && !facedownMonitoring) {
            stopSensors()
        }
    }

    private fun stopSensors() {
        if (continuousPostureClients > 0 || facedownMonitoring) return
        if (!isSensorsRegistered) return
        sm?.unregisterListener(this)
        isSensorsRegistered = false
    }

    var onFacedownDetectedWhileScreenOn: (() -> Unit)? = null

    fun currentPosture(): com.example.itv.domain.coaching.UserPosture = _snapshot.value.posture

    private var facedownOnscreenStartTime = 0L
    private var facedownTickerJob: Job? = null

    fun isFacedown(): Boolean {
        val gz = currentGz ?: _snapshot.value.gravityZ ?: return false
        val gy = currentGy ?: 0f
        val gx = currentGx ?: 0f

        val prox = currentProximity ?: _snapshot.value.proximityDistanceCm
        val maxRange = proximitySensor?.maximumRange ?: 5f
        val hasProximitySensor = proximitySensor != null
        val isNear = if (hasProximitySensor && prox != null) (prox < maxRange || prox == 0f) else _snapshot.value.isNear
        val lux = currentLux ?: _snapshot.value.lux
        val screenInteractive = powerManager?.isInteractive ?: isScreenOn

        // 울퉁불퉁한 바닥(침대, 소파, 카펫)이나 두꺼운 케이스(카드 수납, 링, 카툭튀, 돌출 턱)로 인해
        // 완전 밀착되지 않아 빛이 새어 들어오거나 기울어져 있어도 화면이 아래를 향해 엎어져 있다면 안정적으로 감지
        // 1. 화면이 꺼져 있는 경우 (휴식 미션 실행 중):
        if (!screenInteractive) {
            val isAngleFacedown = gz <= -2.5f && abs(gy) < 9.5f && abs(gx) < 9.5f
            val isRestingOrDark = isNear || isCurrentlyStationary || (lux != null && lux <= 35f)
            return isAngleFacedown && isRestingOrDark
        }

        // 2. 화면이 켜져 있는 경우 (바닥에 엎어두면 자동 화면 끄기용):
        // 누워서 폰을 위로 든 채로 볼 때(고개를 하늘로 하고 폰을 위로 든 상태)는 허공 상태(isNear == false)이므로
        // 화면이 절대 꺼지지 않아야 함! 사용자가 화면을 확실히 안 보고 표면에 엎어두었을 때만 감지.
        val isAngleFacedown = gz <= -3.5f && abs(gy) < 9.0f && abs(gx) < 9.0f
        if (!isAngleFacedown) return false

        // (1) 근접 센서 감지: 바닥/침구류/책상 등에 화면을 엎어두면 0~5cm 이내로 밀착되어 isNear == true가 됨.
        // 누워서 폰을 위로 든 상태(얼굴과 20~40cm 거리)는 isNear == false이므로 화면이 꺼지지 않음!
        val nearFacedown = isNear && (lux == null || lux <= 120f)

        // (2) 근접 센서가 없는 기기이거나 특수 상황:
        // 손을 떼고 바닥에 완전히 올려둔 정지 상태(isCurrentlyStationary) + 완전 수평 엎어둠(gz <= -7.5f) + 조도 완벽 차단(lux <= 2f)만 제한적 허용
        val strictStationaryFacedown = !hasProximitySensor && isCurrentlyStationary && gz <= -7.5f && abs(gy) < 6.0f && abs(gx) < 6.0f && (lux != null && lux <= 2f)

        return nearFacedown || strictStationaryFacedown
    }

    fun startFacedownMonitoring() {
        facedownMonitoring = true
        facedownObserved = false
        facedownAccumulatedMs = 0L
        facedownOnscreenStartTime = 0L
        lastFacedownSampleAt = android.os.SystemClock.elapsedRealtime()
        lastSampleWasFacedown = !(powerManager?.isInteractive ?: isScreenOn) && isFacedown()
        startSensors()
        facedownTickerJob?.cancel()
        facedownTickerJob = scope.launch {
            while (isActive && facedownMonitoring) {
                checkFacedownProgress(android.os.SystemClock.elapsedRealtime())
                delay(80L)
            }
        }
    }

    fun onMissionScreenOff() {
        facedownMonitoring = true
        facedownObserved = false
        facedownAccumulatedMs = 0L
        facedownOnscreenStartTime = 0L
        lastFacedownSampleAt = android.os.SystemClock.elapsedRealtime()
        lastSampleWasFacedown = isFacedown()
        startSensors()
        facedownTickerJob?.cancel()
        facedownTickerJob = scope.launch {
            while (isActive && facedownMonitoring) {
                checkFacedownProgress(android.os.SystemClock.elapsedRealtime())
                delay(80L)
            }
        }
    }

    fun stopFacedownMonitoring(): Boolean {
        facedownTickerJob?.cancel()
        facedownTickerJob = null
        onFacedownDetectedWhileScreenOn = null
        facedownOnscreenStartTime = 0L
        val now = android.os.SystemClock.elapsedRealtime()
        if (facedownMonitoring && lastSampleWasFacedown && isFacedown() && lastFacedownSampleAt > 0L) {
            val dt = (now - lastFacedownSampleAt).coerceIn(0L, 5000L)
            facedownAccumulatedMs += dt
        }
        // 화면이 꺼진 상태에서 최소 3초 이상 실제로 스마트폰이 바닥에 엎어져 있었으면 성공으로 인정
        val wasFacedown = facedownObserved || facedownAccumulatedMs >= 3_000L
        facedownMonitoring = false
        facedownObserved = false
        facedownAccumulatedMs = 0L
        lastFacedownSampleAt = 0L
        lastSampleWasFacedown = false
        if (!isScreenOn && continuousPostureClients == 0) stopSensors()
        return wasFacedown
    }

    private fun checkFacedownProgress(now: Long) {
        if (!facedownMonitoring) return
        val screenOff = !(powerManager?.isInteractive ?: isScreenOn)
        if (!screenOff) {
            if (isFacedown()) {
                if (facedownOnscreenStartTime == 0L) {
                    facedownOnscreenStartTime = now
                } else if (now - facedownOnscreenStartTime >= 1000L) {
                    onFacedownDetectedWhileScreenOn?.invoke()
                    facedownOnscreenStartTime = 0L
                }
            } else {
                facedownOnscreenStartTime = 0L
            }
        } else {
            facedownOnscreenStartTime = 0L
            val currentlyFacedown = isFacedown()
            if (currentlyFacedown) {
                val dt = if (lastFacedownSampleAt > 0L && lastSampleWasFacedown) {
                    (now - lastFacedownSampleAt).coerceIn(0L, 5000L)
                } else {
                    60L
                }
                facedownAccumulatedMs += dt
                if (facedownAccumulatedMs >= 3_000L) {
                    facedownObserved = true
                }
            }
            lastSampleWasFacedown = currentlyFacedown
            lastFacedownSampleAt = now
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val isContinuous = continuousPostureClients > 0
        val now = android.os.SystemClock.elapsedRealtime()
        when (event?.sensor?.type) {
            Sensor.TYPE_LIGHT -> {
                currentLux = event.values.firstOrNull()
                checkFacedownProgress(now)
                update(force = isContinuous, isSampling = true, triggerReason = if (isContinuous) "실시간 조도 갱신" else _snapshot.value.lastTriggerReason)
            }
            Sensor.TYPE_PROXIMITY -> {
                currentProximity = event.values.firstOrNull()
                checkFacedownProgress(now)
                update(force = isContinuous, isSampling = true, triggerReason = if (isContinuous) "실시간 근접 갱신" else _snapshot.value.lastTriggerReason)
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val gx = event.values.getOrNull(0)
                val gy = event.values.getOrNull(1)
                val gz = event.values.getOrNull(2)
                currentGx = gx
                currentGy = gy
                currentGz = gz
                if (gx != null && gy != null && gz != null) {
                    trackMotion(gx, gy, gz)
                }
                checkFacedownProgress(now)
                update(force = isContinuous, isSampling = true, triggerReason = if (isContinuous) "실시간 자세 감지" else _snapshot.value.lastTriggerReason)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private var lastContinuousUpdateMs = 0L

    private fun update(force: Boolean, isSampling: Boolean, triggerReason: String) {
        val candidate = readCurrent(currentLux, currentProximity, isSampling, triggerReason)
        val current = _snapshot.value
        val now = android.os.SystemClock.elapsedRealtime()

        val isContinuous = continuousPostureClients > 0
        if (isContinuous) {
            // 실시간 감지 중에는 최소 200ms 스로틀링 적용 (초당 50~60회 방출로 인한 Compose 전체 리컴포지션 렉 방지)
            if (now - lastContinuousUpdateMs < 200L) {
                return
            }
            if (shouldPublishUpdate(current, candidate) || (now - lastContinuousUpdateMs >= 1000L) || current.isSampling != isSampling) {
                lastContinuousUpdateMs = now
                _snapshot.value = candidate
            }
            return
        }

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

        // 4. 환경 상태 판별 라벨 또는 세부 상태 변경
        if (old.stateLabel != new.stateLabel) return true
        if (old.userEnvironmentState != new.userEnvironmentState) return true
        if (old.bedtimePhase != new.bedtimePhase) return true
        if (old.posture != new.posture) return true
        if (old.isStationary != new.isStationary) return true
        if (old.bedtimeScore != new.bedtimeScore) return true
        if (old.isBedtimeCandidate != new.isBedtimeCandidate) return true
        if (old.isCharging != new.isCharging) return true
        if (old.isWifiConnected != new.isWifiConnected) return true
        val oldPitch = old.pitchDegrees ?: 0f
        val newPitch = new.pitchDegrees ?: 0f
        if (abs(newPitch - oldPitch) >= 2f) return true
        val oldRoll = old.rollDegrees ?: 0f
        val newRoll = new.rollDegrees ?: 0f
        if (abs(newRoll - oldRoll) >= 2f) return true

        // 5. 다크 모드 또는 화면 밝기 대폭 변경
        if (old.nightModeActive != new.nightModeActive) return true
        if (old.screenBrightness != new.screenBrightness &&
            abs((old.screenBrightness ?: 0) - (new.screenBrightness ?: 0)) >= 30
        ) return true

        // 6. 조도 센서: 판단이 바뀌지 않는 미세 변동(예: 2~5 lux 플리커링)은 초기값 유지
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

    private fun circularMinuteDiff(currentMinute: Int, targetMinute: Int): Int {
        var diff = (currentMinute - targetMinute) % 1440
        if (diff > 720) diff -= 1440
        if (diff < -720) diff += 1440
        return diff
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
        val isBrightAmbient = lux != null && lux > 150f
        val isVeryBrightAmbient = lux != null && lux > 1000f
        val isDarkScreen = brightness != null && brightness <= 75
        val isBrightScreen = brightness != null && brightness > 180

        // 평소 추정 취침 시각 연계 계산
        val nowTime = LocalTime.now()
        val currentMinuteOfDay = nowTime.hour * 60 + nowTime.minute

        val h = estimatedStartHour
        val m = estimatedStartMinute
        val hasBedtime = h != null && m != null
        val targetMinuteOfDay = if (hasBedtime) (h!! * 60 + m!!) else (23 * 60 + 30)
        val diffMinutes = circularMinuteDiff(currentMinuteOfDay, targetMinuteOfDay)

        val bedtimePhase = when {
            !hasBedtime -> BedtimePhase.UNKNOWN
            diffMinutes in -60..-1 -> BedtimePhase.APPROACHING // 취침 1시간 이내(60분)만 임박으로 인정 (2시간 전 등 먼 시간대는 일상 활동)
            diffMinutes in 0..360 -> BedtimePhase.PAST_BEDTIME
            else -> BedtimePhase.ACTIVE_HOURS
        }

        val bedtimeText = if (hasBedtime) "%02d:%02d".format(h, m) else "23:30(기준)"
        val bedtimeDistanceText = when {
            diffMinutes < 0 -> {
                val remTotal = -diffMinutes
                val remH = remTotal / 60
                val remM = remTotal % 60
                if (remH > 0) "${remH}시간 ${remM}분 전" else "${remM}분 전"
            }
            diffMinutes == 0 -> "지금이 취침 시각"
            else -> {
                val passedH = diffMinutes / 60
                val passedM = diffMinutes % 60
                if (passedH > 0) "${passedH}시간 ${passedM}분 경과" else "${passedM}분 경과"
            }
        }

        val (state, label, explanation) = when {
            // [CASE 0] 상단 센서 가림 (물체/손/침구류 차단)
            isOccluded -> {
                val detail = if (bedtimePhase == BedtimePhase.PAST_BEDTIME || bedtimePhase == BedtimePhase.APPROACHING) {
                    "취침 시간대(평소 취침 $bedtimeText, $bedtimeDistanceText)에 근접 센서(${proximity}cm)와 조도 센서가 동시에 가려져 있습니다. 스마트폰이 침대/이불에 엎어져 있거나 손으로 상단을 덮고 있을 가능성이 높습니다."
                } else {
                    "활동 시간대(평소 취침 ${bedtimeText}까지 $bedtimeDistanceText)이나 근접 센서가 근접 상태(${proximity}cm)이고 조도가 극도로 낮습니다(${lux?.toInt() ?: 0} lux). 손이나 물체에 의해 상단 센서가 가려져 있을 가능성이 높습니다."
                }
                Triple(
                    UserEnvironmentState.SENSOR_OCCLUDED,
                    "센서 가림 감지 (손/침구류 차단 의심)",
                    detail
                )
            }

            // 센서 대기 상태
            lux == null -> {
                Triple(
                    UserEnvironmentState.UNKNOWN,
                    "조도 센서 대기 중",
                    "센서 샘플링 대기 중이거나 측정값을 수신하는 중입니다. (평소 추정 취침: $bedtimeText)"
                )
            }

            // [CASE 1] 평소 추정 취침 시각과 가깝거나 그 이후 (APPROACHING 또는 PAST_BEDTIME)
            bedtimePhase == BedtimePhase.APPROACHING || bedtimePhase == BedtimePhase.PAST_BEDTIME -> {
                val timePrefix = if (bedtimePhase == BedtimePhase.PAST_BEDTIME) {
                    "평소 추정 취침 시각($bedtimeText)을 $bedtimeDistanceText 넘긴 심야 시간대입니다."
                } else {
                    "평소 추정 취침 시각($bedtimeText)이 ${bedtimeDistanceText}으로 임박한 취침 준비 시간대입니다."
                }

                when {
                    // 1-1. 어두운 방 + 어두운 화면 (취침 준비 / 수면 전 누워서 사용)
                    isDarkAmbient && isDarkScreen -> {
                        Triple(
                            UserEnvironmentState.PRE_SLEEP_REST,
                            "취침 전 수면 준비 상태 (어두운 조도 + 어두운 화면)",
                            "$timePrefix 방 조명을 끄고(조도 ${lux.toInt()} lux) 화면 밝기도 낮춘(${brightness}/255, ${if (nightMode) "다크모드 ON" else "다크모드 OFF"}) 상태로, 곧 잠자리에 들 준비 중일 가능성이 높습니다."
                        )
                    }
                    // 1-2. 어두운 방 + 보통/밝은 화면 (수면 지연 / 베드타임 프로크래스티네이션)
                    isDarkAmbient && !isDarkScreen -> {
                        Triple(
                            UserEnvironmentState.BEDTIME_DELAY_WATCH,
                            "취침 지연 과몰입 상태 (어두운 조명 + 밝은 화면 응시)",
                            "$timePrefix 불을 끈 어두운 방(조도 ${lux.toInt()} lux)에서 밝은 화면(${brightness}/255)을 계속 응시하고 있어, 눈 피로도 증가와 멜라토닌 분비 억제로 인한 수면 지연(취침 미루기) 위험이 큽니다."
                        )
                    }
                    // 1-3. 조명 켜짐 (취침 시간대이나 실내 불을 켜두고 활동 중)
                    else -> {
                        val state = if (bedtimePhase == BedtimePhase.PAST_BEDTIME) {
                            UserEnvironmentState.NIGHT_ACTIVE
                        } else {
                            UserEnvironmentState.INDOOR_ACTIVE
                        }
                        val title = if (bedtimePhase == BedtimePhase.PAST_BEDTIME) {
                            "야간 활동 유지 상태 (취침 시간 경과 · 실내 조명 켜짐)"
                        } else {
                            "취침 전 일상 활동 상태 (실내 조명 켜짐)"
                        }
                        Triple(
                            state,
                            title,
                            "$timePrefix 아직 실내 조명(조도 ${lux.toInt()} lux)이 켜져 있고 화면(${brightness ?: "-"} / 255)을 사용 중으로, 조명을 켜둔 채 정상적인 활동을 이어가고 있습니다."
                        )
                    }
                }
            }

            // [CASE 2] 평소 추정 취침 시각과 멀 때 (ACTIVE_HOURS)
            else -> {
                val timePrefix = if (diffMinutes < 0) {
                    "평소 추정 취침 시각($bedtimeText)까지 아직 $bedtimeDistanceText 남아있는 일상 활동 시간대입니다."
                } else {
                    "평소 추정 취침 시각($bedtimeText)으로부터 $bedtimeDistanceText 지난 일상 활동 시간대입니다."
                }

                when {
                    // 2-1. 야외 / 매우 밝은 직사광선 환경
                    isVeryBrightAmbient && isBrightScreen -> {
                        Triple(
                            UserEnvironmentState.OUTDOOR_BRIGHT,
                            "야외 활동 상태 (직사광선/밝은 야외 + 고화면 밝기)",
                            "$timePrefix 주변 조도가 매우 밝은(${lux.toInt()} lux) 야외 환경이며, 화면 밝기(${brightness}/255)도 높아 활발한 야외 활동 상태입니다."
                        )
                    }
                    // 2-2. 주간 / 밝은 실내 환경
                    isBrightAmbient -> {
                        Triple(
                            UserEnvironmentState.INDOOR_ACTIVE,
                            "주간/밝은 실내 활동 상태 (밝은 조도 + 활동형 화면)",
                            "$timePrefix 주변 환경이 밝고(${lux.toInt()} lux) 화면도 적정 밝기(${brightness ?: "-"} / 255)인 정상적인 주간 활동 상태입니다."
                        )
                    }
                    // 2-3. 보통 실내 조명 환경
                    !isDarkAmbient -> {
                        Triple(
                            UserEnvironmentState.INDOOR_ACTIVE,
                            "실내 일상 활동 상태 (실내 조명 + 일반 화면)",
                            "$timePrefix 실내 조명 조도(${lux.toInt()} lux) 환경에서 일반적인 화면 밝기(${brightness ?: "-"} / 255, ${if (nightMode) "다크모드 ON" else "다크모드 OFF"})로 활동 중인 상태입니다."
                        )
                    }
                    // 2-4. 활동 시간대이나 어두운 실내 (암실 시청)
                    !isDarkScreen -> {
                        Triple(
                            UserEnvironmentState.INDOOR_DARK_ACTIVE,
                            "활동 시간대 암실 시청 상태 (어두운 실내 + 밝은 화면 응시)",
                            "$timePrefix 주변 조명이 어두우나(${lux.toInt()} lux) 밝은 화면(${brightness}/255)을 응시하고 있어 눈 피로도가 증가할 수 있습니다."
                        )
                    }
                    // 2-5. 활동 시간대 어두운 실내 절전
                    else -> {
                        Triple(
                            UserEnvironmentState.INDOOR_DARK_ACTIVE,
                            "활동 시간대 암실 절전 상태 (어두운 실내 + 어두운 화면)",
                            "$timePrefix 어두운 실내 공간(조도 ${lux.toInt()} lux)에서 화면 밝기를 낮추어(${brightness}/255) 사용 중인 절전 활동 상태입니다."
                        )
                    }
                }
            }
        }

        val (posture, pitchRoll, postureExplain) = computePosture(
            currentGx, currentGy, currentGz,
            isNear, lux, isCurrentlyStationary
        )

        // 배터리 소모 0을 위해 시간대가 취침 시간대(임박/경과)에 진입했을 때만 1회성 소프트 맥락 조회
        val isBedtimeTimeWindow = bedtimePhase == BedtimePhase.APPROACHING || bedtimePhase == BedtimePhase.PAST_BEDTIME
        val isLikelyNight = isBedtimeTimeWindow

        var isCharging: Boolean? = null
        var isWifi: Boolean? = null

        if (isLikelyNight) {
            try {
                val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                             plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                             plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
            } catch (_: Exception) {}

            try {
                val cm = appContext.getSystemService(ConnectivityManager::class.java)
                val activeNetwork = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(activeNetwork)
                isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } catch (_: Exception) {}
        }

        // 취침 확신도 점수 계산 (100점 만점)
        // 취침 케어는 '평소 취침 시각 전후에 불을 끈 어두운 방에서 잠에 들지 못하고 사용하는 수면 지연(베드타임 프로크래스티네이션)'을 방지하기 위함입니다.
        // 불을 켜고 생활 중인 경우(lux > 20f)에는 취침 개입 대상이 아니므로 조도 가중치를 엄격하게 반영하고,
        // 어두운 방(isDarkAmbient) 상태에서만 취침 확신도 후보(isBedtimeCandidate)로 인정합니다.
        var score = 0
        val breakdownList = mutableListOf<String>()

        // 1) 시간대 (최대 40점): 취침 1시간 이내 또는 심야 취침 경과 시간대
        when (bedtimePhase) {
            BedtimePhase.PAST_BEDTIME -> {
                score += 40
                breakdownList.add("심야취침(+40)")
            }
            BedtimePhase.APPROACHING -> {
                score += 30
                breakdownList.add("취침1시간내(+30)")
            }
            else -> {}
        }

        // 2) 암실/조도 (최대 30점): 불을 끈 어두운 환경이 필수
        if (lux != null) {
            when {
                lux <= 3f -> {
                    score += 30
                    breakdownList.add("완전암실(+30)")
                }
                lux <= 10f -> {
                    score += 25
                    breakdownList.add("어두운방(+25)")
                }
                lux <= 20f -> {
                    score += 15
                    breakdownList.add("미약조명(+15)")
                }
            }
        }

        // 3) 자세 (누움 최대 15점)
        if (posture == com.example.itv.domain.coaching.UserPosture.LYING_DOWN) {
            score += 15
            breakdownList.add("누운자세(+15)")
        }

        // 4) 정지 상태 (가만히 있음 최대 5점)
        if (isCurrentlyStationary) {
            score += 5
            breakdownList.add("정지상태(+5)")
        }

        // 5) [보조 소프트 맥락] 충전 중 (+5점)
        if (isCharging == true) {
            score += 5
            breakdownList.add("충전중(+5)")
        }

        // 6) [보조 소프트 맥락] Wi-Fi 연결 (+5점)
        if (isWifi == true) {
            score += 5
            breakdownList.add("Wi-Fi(+5)")
        }

        val bedtimeScore = score.coerceIn(0, 100)
        // 불을 켜고 생활 중(lux > 20f)이거나, 취침 1시간 이전(ACTIVE_HOURS)인 경우에는 절대 취침 후보로 인정하지 않음
        val isBedtimeCandidate = isDarkAmbient && isBedtimeTimeWindow && bedtimeScore >= 60
        val bedtimeScoreBreakdown = if (breakdownList.isEmpty()) "점수 없음 (0점)" else breakdownList.joinToString(" · ")

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
            lastTriggerReason = triggerReason,
            estimatedBedtimeHour = h,
            estimatedBedtimeMinute = m,
            bedtimeDistanceMinutes = diffMinutes,
            bedtimePhase = bedtimePhase,
            userEnvironmentState = state,
            estimatedBedtimeSource = (estimatedSource as String?).orEmpty().ifEmpty { "미측정" },
            posture = posture,
            pitchDegrees = pitchRoll.first,
            rollDegrees = pitchRoll.second,
            gravityZ = currentGz,
            postureExplanation = postureExplain,
            isStationary = isCurrentlyStationary,
            isCharging = isCharging,
            isWifiConnected = isWifi,
            bedtimeScore = bedtimeScore,
            isBedtimeCandidate = isBedtimeCandidate,
            bedtimeScoreBreakdown = bedtimeScoreBreakdown
        )
    }

    private fun computePosture(
        gx: Float?,
        gy: Float?,
        gz: Float?,
        isNear: Boolean,
        lux: Float?,
        isStationary: Boolean
    ): Triple<com.example.itv.domain.coaching.UserPosture, Pair<Float?, Float?>, String> {
        if (gx == null || gy == null || gz == null) {
            if ((isNear && (lux != null && lux <= 1.5f)) || (lux != null && lux <= 0.1f)) {
                return Triple(
                    com.example.itv.domain.coaching.UserPosture.FACEDOWN,
                    Pair(null, null),
                    "화면이 엎어져 있습니다 (조도 센서 차단 감지)"
                )
            }
            return Triple(
                com.example.itv.domain.coaching.UserPosture.UNKNOWN,
                Pair(null, null),
                "자세 판별 중 (중력 센서 대기)"
            )
        }

        val gMag = kotlin.math.sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat().coerceAtLeast(0.1f)

        // Pitch: 스마트폰 전면 거치 각도 (수평 0° ~ 수직 90°)
        // 세운 방향일수록 gy가 양수로 증가: arcsin(gy / |g|) -> 0°(바닥 평평) ~ 90°(직립)
        val pitch = Math.toDegrees(kotlin.math.asin((gy / gMag).coerceIn(-1f, 1f).toDouble())).toFloat()

        // Roll: 좌우 기울기 (-180° ~ +180°)
        // 바로 세움 = ~0°, 오른쪽 기울임 = +, 왼쪽 기울임 = -, 뒤집어 엎음 = ±180°
        val roll = Math.toDegrees(kotlin.math.atan2(gx.toDouble(), gz.toDouble())).toFloat()
        val absRoll = abs(roll)

        val screenInteractive = powerManager?.isInteractive ?: isScreenOn
        val isPitchBlack = lux != null && lux <= 0.8f
        val isFlatResting = gz < -8.5f && abs(gy) < 2.5f && abs(gx) < 2.5f

        val postureTriple = when {
            // 1. 스마트폰 화면 하향 각도 (Z축 음수 또는 반전 135° 이상)
            // -> 바닥/침대에 엎어둔 것인지 vs 얼굴 위로 들고 시청 중인 것인지 조도/근접/화면상태로 정밀 분기
            gz < -2.5f || (gz < -1.5f && absRoll > 135f && abs(gy) < 6.5f) -> {
                val isTrulyFacedown = when {
                    // 화면 꺼짐: 미션 중 화면을 끄고 실제로 바닥을 향해 엎어둔 각도만 인정
                    !screenInteractive -> gz <= -6.5f && abs(gy) < 6.0f
                    // 근접 센서가 밀착 가림 (침구류/베개/바닥) + 하향 각도
                    isNear && gz <= -6.0f && abs(gy) < 6.5f && (isPitchBlack || gz < -6.5f) -> true
                    // 조도 0 lux 차단 및 바닥 수평 거치
                    isPitchBlack && isFlatResting -> true
                    isPitchBlack && gz < -6.5f && absRoll > 150f && abs(gy) < 3.5f -> true
                    else -> false
                }

                if (isTrulyFacedown) {
                    Triple(
                        com.example.itv.domain.coaching.UserPosture.FACEDOWN,
                        Pair(pitch, roll),
                        "화면이 바닥에 엎어져 있습니다 (조도 차단 %.1f lux · Z축 %.1f m/s²). 강한 개입 차단 조건 충족.".format(lux ?: 0f, gz)
                    )
                } else {
                    // 화면이 켜져 있고 조도가 유입되거나 근접 센서가 열려 있는 경우:
                    // 얼굴 위로 스마트폰을 들고 있는 고하중 오버헤드 각도
                    Triple(
                        com.example.itv.domain.coaching.UserPosture.LYING_DOWN,
                        Pair(pitch, roll),
                        "얼굴 위로 폰을 들고 시청 중 (전면 %.0f° · 팔·목 근육 피로 누적 각도). 목·어깨 이완 필요.".format(pitch)
                    )
                }
            }

            // 2. 테이블/거치대 고정 각도 (TABLE_OR_DESK)
            // 특징: 기기가 미세 떨림 없이 완전 정지(isStationary) 상태
            isStationary && (
                (gz > 7.5f && pitch < 25f && absRoll < 30f) ||
                (pitch in 25f..85f && absRoll < 30f) ||
                (absRoll in 65f..115f && pitch < 35f)
            ) -> {
                val modeDesc = when {
                    absRoll in 65f..115f -> "가로 모드 거치"
                    gz > 7.5f && pitch < 25f -> "테이블 수평 내려놓음"
                    else -> "거치대에 세워둠"
                }
                Triple(
                    com.example.itv.domain.coaching.UserPosture.TABLE_OR_DESK,
                    Pair(pitch, roll),
                    "테이블 거치 상태 ($modeDesc · 고정 응시). 장시간 고개 숙임과 식사 중 시청에 주의하세요."
                )
            }

            // 3. 수평/하향 각도 (LYING_DOWN: 경추 고하중 거북목 각도)
            // 전면 거치 각도가 22° 미만으로 낮아 고개를 아래로 깊게 떨구거나 누워 올려다보는 각도
            pitch < 22f && gz > 5.5f -> {
                Triple(
                    com.example.itv.domain.coaching.UserPosture.LYING_DOWN,
                    Pair(pitch, roll),
                    "스마트폰이 수평으로 눕혀져 있어(전면 %.0f°) 목(경추)에 최대 27kg 하중이 실리는 각도입니다. 턱 당기기 스트레칭을 권유합니다.".format(pitch)
                )
            }

            // 4. 좌우 비대칭 기울임 파지 (한쪽으로 기울여 쥠)
            absRoll > 40f && pitch < 55f -> {
                Triple(
                    com.example.itv.domain.coaching.UserPosture.SITTING_OR_STANDING,
                    Pair(pitch, roll),
                    "스마트폰이 좌우로 기울어져 있어(기울기 %.0f°) 척추와 어깨 좌우 불균형이 발생할 수 있는 각도입니다. 어깨와 허리 스트레칭을 권유합니다.".format(roll)
                )
            }

            // 5. 정면 파지 시청 (SITTING_OR_STANDING)
            // 전면 거치 각도가 22°~88° 사이인 일반적인 파지 상태
            pitch in 22f..88f && absRoll <= 40f && gz > -3.5f -> {
                Triple(
                    com.example.itv.domain.coaching.UserPosture.SITTING_OR_STANDING,
                    Pair(pitch, roll),
                    "균형 잡힌 파지 각도(전면 %.0f°, 좌우 %.0f°)입니다. 다만 장시간 고정 시 목·어깨가 굳으므로 가벼운 스트레칭을 권유합니다.".format(pitch, roll)
                )
            }

            // Fallbacks:
            pitch >= 20f && gz > -2.0f -> {
                Triple(
                    com.example.itv.domain.coaching.UserPosture.SITTING_OR_STANDING,
                    Pair(pitch, roll),
                    "정면 파지 각도(전면 %.0f°)입니다. 가벼운 스트레칭을 권유합니다.".format(pitch)
                )
            }
            gz > 6.0f -> {
                Triple(
                    com.example.itv.domain.coaching.UserPosture.LYING_DOWN,
                    Pair(pitch, roll),
                    "수평에 가까운 각도(전면 %.0f°)로 경추 부담이 큽니다. 목 스트레칭을 권유합니다.".format(pitch)
                )
            }
            else -> {
                Triple(
                    com.example.itv.domain.coaching.UserPosture.UNKNOWN,
                    Pair(pitch, roll),
                    "각도 측정 중 (전면 %.0f°, 좌우 %.0f°)".format(pitch, roll)
                )
            }
        }

        return postureTriple
    }
}
