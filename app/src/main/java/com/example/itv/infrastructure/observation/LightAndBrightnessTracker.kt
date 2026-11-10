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
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

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
    val estimatedBedtimeSource: String = "미측정"
)

class LightAndBrightnessTracker(context: Context) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sm = appContext.getSystemService(SensorManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val lightSensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentLux: Float? = null
    private var currentProximity: Float? = null

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
                val window = com.example.itv.domain.records.SleepSummaryPolicy.estimateBedtimeWindow(confirmations)

                var h: Int? = null
                var m: Int? = null
                var src = "기본값 (23:30)"

                if (window != null) {
                    h = window.avgStartHour
                    m = window.avgStartMinute
                    src = "수면 확인 기록 (${window.validCount}일 평균)"
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

                withContext(Dispatchers.Main) {
                    estimatedStartHour = h
                    estimatedStartMinute = m
                    estimatedSource = src
                    update(force = true, isSampling = _snapshot.value.isSampling, triggerReason = "취침 추정 정보 갱신")
                }
            } catch (_: Exception) {}
        }
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

        // 4. 환경 상태 판별 라벨 또는 세부 상태 변경
        if (old.stateLabel != new.stateLabel) return true
        if (old.userEnvironmentState != new.userEnvironmentState) return true
        if (old.bedtimePhase != new.bedtimePhase) return true

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
            diffMinutes in -120..-1 -> BedtimePhase.APPROACHING
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
                        Triple(
                            UserEnvironmentState.NIGHT_ACTIVE,
                            "야간 활동 유지 상태 (취침 시간대 실내 조명 켜짐)",
                            "$timePrefix 아직 실내 조명(조도 ${lux.toInt()} lux)이 켜져 있고 화면(${brightness ?: "-"} / 255)을 사용 중으로, 잠자리에 들지 않고 야간 활동을 이어가고 있습니다."
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
            estimatedBedtimeSource = (estimatedSource as String?).orEmpty().ifEmpty { "미측정" }
        )
    }
}
