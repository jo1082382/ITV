package com.example.itv.presentation.character

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.itv.domain.coaching.CharacterIntensity
import com.example.itv.domain.coaching.InterventionReasonResolver
import com.example.itv.domain.coaching.RestMissionCatalog
import com.example.itv.domain.coaching.RestRecommendation
import com.example.itv.domain.coaching.UserPosture
import com.example.itv.infrastructure.coaching.UserCoachingSettingsStore
import com.example.itv.infrastructure.observation.LightAndBrightnessTracker
import com.example.itv.presentation.coaching.CoachingOverlayFactory
import com.example.itv.presentation.coaching.HudPuppyFaceRenderer
import com.example.itv.presentation.coaching.LongformDiagnosticHud
import com.example.itv.presentation.components.PuppyMood
import com.example.itv.presentation.sleep.BedtimeCareController

/**
 * Shares the production coaching overlay scene in ONE Activity window.
 * Dynamically reacts to real-time phone pitch, roll, and lux values,
 * generating customized intervention reasons and stretching missions.
 */
class CompanionPreviewActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var host: FrameLayout
    private lateinit var factory: CoachingOverlayFactory
    private lateinit var statusText: TextView
    private lateinit var rewardText: TextView
    private lateinit var tracker: LightAndBrightnessTracker
    private lateinit var hudTestStatusText: TextView
    private lateinit var hudPowerBtn: Button
    private lateinit var hudLiveBtn: Button
    private val hudMoodButtons = mutableListOf<Pair<PuppyMood, Button>>()

    private var current: View? = null
    private var selected = false
    private var activeRecommendation: RestRecommendation? = null

    private var missionActive = false
    private var missionOffAt = 0L
    private var missionAccumulatedRestMs = 0L
    private var missionResumeTimeout: Runnable? = null
    private val missionRestDurationMs = 15_000L
    private var lastIntensity: CharacterIntensity = CharacterIntensity.NORMAL

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    missionResumeTimeout?.let { handler.removeCallbacks(it) }
                    factory.dismissMissionCountdownHud()
                    if (missionActive && missionOffAt == 0L) {
                        missionOffAt = SystemClock.elapsedRealtime()
                        if (activeRecommendation?.isFacedownRequired == true) {
                            tracker.onMissionScreenOff()
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (missionActive && missionOffAt > 0L) {
                        handler.post { checkMissionWake() }
                    }
                }
            }
        }
    }

    private val sensorUpdater = object : Runnable {
        override fun run() {
            updateStatusDisplay()
            handler.postDelayed(this, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenReceiver, filter)
            }
        } catch (_: Exception) {}

        tracker = LightAndBrightnessTracker.getInstance(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Color.rgb(245, 247, 248))
        }

        // 1. Real-time sensor status header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        statusText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(33, 33, 33))
            text = "실시간 센서 측정 중..."
        }
        header.addView(statusText)

        rewardText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(46, 125, 50))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
            text = "성장 현황: 로딩 중..."
        }
        header.addView(rewardText)

        val guideText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(4), 0, 0)
            text = "※ 폰 각도(Pitch)나 조도를 바꾼 뒤 아래 버튼을 누르면 사유와 미션이 동적으로 바뀝니다."
        }
        header.addView(guideText)
        column.addView(header)

        // 2. Action buttons bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(Color.rgb(238, 242, 246))
        }
        fun button(text: String, isPrimary: Boolean = false, action: () -> Unit) {
            val btn = Button(this).apply {
                this.text = text
                textSize = 11.5f
                setPadding(dp(2), dp(2), dp(2), dp(2))
                if (isPrimary) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(46, 125, 50))
                }
                setOnClickListener { action() }
            }
            bar.addView(btn, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }

        button("일반 개입", isPrimary = true) { show("offer_normal") }
        button("강한 개입") { show("offer_strong") }
        button("재약속") { show("retry") }
        button("완료") { show("complete") }
        button("닫기") { finish() }
        column.addView(bar)

        // 야간 케어 전용 제어 바 (3분기, 흑백, 흑백 취소)
        val nightBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        fun nightButton(text: String, bgColor: Int, action: () -> Unit) {
            val btn = Button(this).apply {
                this.text = text
                textSize = 11.5f
                setTextColor(Color.WHITE)
                setBackgroundColor(bgColor)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                setOnClickListener { action() }
            }
            nightBar.addView(btn, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        nightButton("🌙 야간 3분기", Color.parseColor("#334155")) { show("night_care") }
        nightButton("🖤 흑백", Color.parseColor("#475569")) {
            com.example.itv.presentation.sleep.BedtimeCareController.getInstance(this).startGrayscaleMode(forceManual = true)
        }
        nightButton("🤍 흑백 취소", Color.parseColor("#0F766E")) {
            com.example.itv.presentation.sleep.BedtimeCareController.getInstance(this).stopGrayscaleMode()
        }
        column.addView(nightBar)

        // 플로팅 HUD 5단계 표정 테스트 제어 바
        val hudTestPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val hudTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hudTestStatusText = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            maxLines = 1
        }
        hudTopRow.addView(
            hudTestStatusText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        )

        hudLiveBtn = Button(this).apply {
            text = "실시간"
            textSize = 11f
            isAllCaps = false
            setPadding(dp(8), 0, dp(8), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setOnClickListener {
                if (!ensureHudReady()) return@setOnClickListener
                if (LongformDiagnosticHud.isSleeping) {
                    LongformDiagnosticHud.setSleepingState(false)
                }
                LongformDiagnosticHud.setPreviewMoodOverride(this@CompanionPreviewActivity, null)
                refreshHudTestControls()
            }
        }
        hudTopRow.addView(
            hudLiveBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                marginEnd = dp(4)
            }
        )

        hudPowerBtn = Button(this).apply {
            textSize = 11f
            isAllCaps = false
            setPadding(dp(10), 0, dp(10), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setOnClickListener {
                if (LongformDiagnosticHud.isShowing) {
                    LongformDiagnosticHud.setPreviewMoodOverride(this@CompanionPreviewActivity, null)
                    LongformDiagnosticHud.hide()
                } else {
                    ensureHudReady()
                }
                refreshHudTestControls()
            }
        }
        hudTopRow.addView(
            hudPowerBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32))
        )
        hudTestPanel.addView(hudTopRow)

        val hudMoodRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, 0)
        }
        val moodDefs = listOf(
            PuppyMood.BRIGHT to "해맑\n≥30%p",
            PuppyMood.SMILE to "웃음\n15~30",
            PuppyMood.SAD to "슬픔\n5~15",
            PuppyMood.ANGRY to "화남\n0~5",
            PuppyMood.SICK to "아픔\n≤0%p"
        )
        hudMoodButtons.clear()
        moodDefs.forEach { (mood, label) ->
            val btn = Button(this).apply {
                text = label
                textSize = 10.5f
                isAllCaps = false
                setLineSpacing(0f, 0.92f)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setOnClickListener {
                    if (!ensureHudReady()) return@setOnClickListener
                    if (LongformDiagnosticHud.isSleeping) {
                        LongformDiagnosticHud.setSleepingState(false)
                    }
                    LongformDiagnosticHud.setPreviewMoodOverride(this@CompanionPreviewActivity, mood)
                    refreshHudTestControls()
                }
            }
            hudMoodButtons += mood to btn
            hudMoodRow.addView(
                btn,
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            )
        }
        hudTestPanel.addView(hudMoodRow)
        column.addView(hudTestPanel)
        refreshHudTestControls()

        // 3. Companion display host area
        host = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(221, 236, 225))
            clipChildren = false
            clipToPadding = false
        }
        column.addView(host, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)

        factory = CoachingOverlayFactory(this, host)
        host.post {
            if (!isFinishing) show("offer_normal")
        }
    }

    private fun makeChipBg(fillColor: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }

    private fun ensureHudReady(): Boolean {
        if (!LongformDiagnosticHud.hasRequiredPermissions(this)) {
            val summary = LongformDiagnosticHud.missingPermissionSummary(this)
            Toast.makeText(this, summary, Toast.LENGTH_SHORT).show()
            if (!Settings.canDrawOverlays(this)) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            return false
        }
        if (!LongformDiagnosticHud.isShowing) {
            LongformDiagnosticHud.show(this)
        }
        return LongformDiagnosticHud.isShowing
    }

    private fun refreshHudTestControls() {
        val hasPerms = LongformDiagnosticHud.hasRequiredPermissions(this)
        val showing = LongformDiagnosticHud.isShowing
        val overrideMood = LongformDiagnosticHud.previewMoodOverride

        hudTestStatusText.text = when {
            !hasPerms -> "🐾 HUD 테스트 · 권한 필요"
            !showing -> "🐾 HUD 테스트 · 5단계 표정 선택 시 HUD 표시"
            overrideMood != null -> "🐾 HUD 테스트 · ${HudPuppyFaceRenderer.stageLabel(overrideMood)} (${HudPuppyFaceRenderer.gapRangeLabel(overrideMood)})"
            else -> "🐾 HUD 테스트 · Jev 확률차 실시간 연동"
        }

        hudPowerBtn.text = if (showing) "HUD 끄기" else "HUD 켜기"
        hudPowerBtn.alpha = if (hasPerms) 1f else 0.55f
        hudPowerBtn.setTextColor(Color.WHITE)
        hudPowerBtn.background = makeChipBg(
            fillColor = if (showing) Color.parseColor("#DC2626") else Color.parseColor("#2563EB"),
            strokeColor = Color.TRANSPARENT
        )

        val liveSelected = showing && overrideMood == null && !LongformDiagnosticHud.isSleeping
        hudLiveBtn.alpha = if (hasPerms) 1f else 0.55f
        hudLiveBtn.setTextColor(if (liveSelected) Color.parseColor("#0F172A") else Color.parseColor("#E2E8F0"))
        hudLiveBtn.background = makeChipBg(
            fillColor = if (liveSelected) Color.parseColor("#38BDF8") else Color.parseColor("#1E293B"),
            strokeColor = if (liveSelected) Color.parseColor("#38BDF8") else Color.parseColor("#475569")
        )

        hudMoodButtons.forEach { (mood, btn) ->
            val selected = showing && overrideMood == mood
            val accent = HudPuppyFaceRenderer.accentColorInt(mood)
            btn.alpha = if (hasPerms) 1f else 0.55f
            btn.setTextColor(if (selected) Color.parseColor("#0F172A") else Color.parseColor("#E2E8F0"))
            btn.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            btn.background = makeChipBg(
                fillColor = if (selected) accent else Color.parseColor("#1E293B"),
                strokeColor = if (selected) accent else Color.parseColor("#334155")
            )
        }
    }

    override fun onResume() {
        super.onResume()
        tracker.start()
        tracker.startContinuousPostureListening()
        refreshHudTestControls()
        handler.removeCallbacks(sensorUpdater)
        handler.post(sensorUpdater)
        if (missionActive && missionOffAt > 0L) {
            handler.post { checkMissionWake() }
        }
    }

    private fun checkMissionWake() {
        if (!missionActive || missionOffAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        val sessionDuration = (now - missionOffAt).coerceAtLeast(0L)
        missionOffAt = 0L
        val needFacedown = activeRecommendation?.isFacedownRequired == true
        val facedownOk = if (needFacedown) tracker.stopFacedownMonitoring() else true

        if (facedownOk) {
            missionAccumulatedRestMs += sessionDuration
        }

        val totalRestMs = missionAccumulatedRestMs
        if (totalRestMs >= missionRestDurationMs) {
            missionActive = false
            missionAccumulatedRestMs = 0L
            factory.dismissMissionCountdownHud()
            missionResumeTimeout?.let { handler.removeCallbacks(it) }
            statusText.text = "휴식 미션 성공! 뼈다귀 보상을 받아보세요."
            show("complete")
        } else {
            val remainingMs = (missionRestDurationMs - totalRestMs).coerceAtLeast(0L)
            val remainingSec = (remainingMs + 999L) / 1000L
            val rec = activeRecommendation ?: RestMissionCatalog.pick(tracker.snapshot.value.posture, lastIntensity == CharacterIntensity.STRONG)

            val prepareSeconds = 10L
            val targetTimeCal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.SECOND, (prepareSeconds + remainingSec).toInt())
                if (get(java.util.Calendar.SECOND) > 0) {
                    add(java.util.Calendar.MINUTE, 1)
                    set(java.util.Calendar.SECOND, 0)
                }
            }
            val targetHour = targetTimeCal.get(java.util.Calendar.HOUR_OF_DAY)
            val targetMinute = targetTimeCal.get(java.util.Calendar.MINUTE)
            val targetTimeStr = String.format(java.util.Locale.KOREA, "%02d시 %02d분", targetHour, targetMinute)
            val restMinutes = (remainingSec + 59L) / 60L
            val restDisplayDesc = if (remainingSec >= 60L) "${restMinutes}분" else "${remainingSec}초"

            val facedownReason = if (needFacedown && !facedownOk) " (엎어두기 미유지)" else ""
            statusText.text = "휴식 일시정지${facedownReason} · ${totalRestMs / 1000L}초 인정 / 10초 내 다시 화면을 끄면 이어집니다 (남은 시간: ${remainingSec}초)"

            if (lastIntensity == CharacterIntensity.STRONG) {
                current?.let { factory.detachPreview(it) }
                val scene = factory.retryOffer(
                    onChoice = { title, _ -> choose(title, true) },
                    intensityOverride = CharacterIntensity.STRONG,
                    recommendation = rec,
                    remainingRestSeconds = remainingSec,
                    prepareSeconds = prepareSeconds
                )
                current = scene.first
                factory.attachPreview(scene.first, scene.second)
            } else {
                factory.showMissionCountdownHud(
                    rec = rec,
                    targetTimeStr = targetTimeStr,
                    restDisplayDesc = restDisplayDesc,
                    prepareSeconds = prepareSeconds,
                    isResume = true,
                    remainingRestSeconds = remainingSec
                )
            }

            if (lastIntensity == CharacterIntensity.STRONG || rec.isFacedownRequired) {
                tracker.onFacedownDetectedWhileScreenOn = {
                    handler.post {
                        val locked = com.example.itv.infrastructure.observation.accessibility.WritingGuardService.lockScreen()
                        if (!locked) {
                            android.widget.Toast.makeText(applicationContext, "엎어둠 감지! 전원 버튼을 눌러 화면을 꺼주세요.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                tracker.startFacedownMonitoring()
            }

            missionResumeTimeout?.let { handler.removeCallbacks(it) }
            val timeoutRunnable = Runnable {
                if (missionActive && missionOffAt == 0L) {
                    missionActive = false
                    missionAccumulatedRestMs = 0L
                    factory.dismissMissionCountdownHud()
                    tracker.stopFacedownMonitoring()
                    statusText.text = "10초 내 화면을 다시 끄지 않아 미션 실패! 다시 도전해보세요."
                    show("retry")
                }
            }
            missionResumeTimeout = timeoutRunnable
            handler.postDelayed(timeoutRunnable, prepareSeconds * 1000L)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(sensorUpdater)
        tracker.stopContinuousPostureListening()
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isInteractive = pm?.isInteractive ?: true
        if (!isInteractive && missionActive && missionOffAt == 0L) {
            missionResumeTimeout?.let { handler.removeCallbacks(it) }
            factory.dismissMissionCountdownHud()
            missionOffAt = SystemClock.elapsedRealtime()
            if (activeRecommendation?.isFacedownRequired == true) {
                tracker.onMissionScreenOff()
            }
        }
        if (!missionActive || activeRecommendation?.isFacedownRequired != true) {
            tracker.stop()
        }
    }

    private fun updateStatusDisplay(overrideMsg: String? = null) {
        val snap = tracker.snapshot.value
        val postureName = when (snap.posture) {
            UserPosture.LYING_DOWN -> "수평/하향 각도 (목 하중 부담)"
            UserPosture.SITTING_OR_STANDING -> "정면 파지 각도 (어깨 뭉침)"
            UserPosture.TABLE_OR_DESK -> "식탁/책상 거치 (고개 숙임)"
            UserPosture.FACEDOWN -> "화면 엎어둠 (완전 차단)"
            UserPosture.UNKNOWN -> "각도 감지 중"
        }
        val pitchStr = snap.pitchDegrees?.let { "%.1f°".format(it) } ?: "-"
        val rollStr = snap.rollDegrees?.let { "%.1f°".format(it) } ?: "-"
        val luxStr = snap.lux?.let { "%.1f lx".format(it) } ?: "-"
        val bedtimeScoreStr = "${snap.bedtimeScore}점(${if (snap.isBedtimeCandidate) "취침확정" else "일상"})"

        val currentRec = activeRecommendation ?: RestMissionCatalog.pick(snap.posture, isStrong = false)
        if (overrideMsg != null) {
            statusText.text = overrideMsg
        } else if (!missionActive) {
            statusText.text = "현재 감지: [$postureName | P:${pitchStr} R:${rollStr} L:${luxStr}]\n취침확신도: $bedtimeScoreStr · 추천 미션: [${currentRec.category}] ${currentRec.title}"
        }

        val reward = com.example.itv.infrastructure.reward.UserRewardStore.getReward(this)
        rewardText.text = "성장 현황: Lv.${reward.currentLevel} (${reward.currentLevelExp}/${reward.nextLevelExpRequired} EXP) · ${reward.currentPoints} P (미션 완료: ${reward.totalCompletedMissions}회)"

        val color = when (snap.posture) {
            UserPosture.LYING_DOWN -> Color.rgb(198, 40, 40)
            UserPosture.SITTING_OR_STANDING -> Color.rgb(46, 125, 50)
            UserPosture.TABLE_OR_DESK -> Color.rgb(21, 101, 192)
            UserPosture.FACEDOWN -> Color.rgb(156, 39, 176)
            UserPosture.UNKNOWN -> Color.rgb(117, 117, 117)
        }
        statusText.setTextColor(color)
    }

    private fun clear() {
        missionResumeTimeout?.let { handler.removeCallbacks(it) }
        factory.dismissMissionCountdownHud()
        handler.removeCallbacks(sensorUpdater)
        handler.post(sensorUpdater)
        current?.let { factory.detachPreview(it) }
        current = null
        selected = false
        BedtimeCareController.getInstance(this).dismissCurrentDialog()
    }

    private fun show(mode: String) {
        if (!::factory.isInitialized || host.width == 0) return
        clear()
        if (mode != "retry" && mode != "complete") {
            missionActive = false
            missionAccumulatedRestMs = 0L
            missionOffAt = 0L
        }

        if (mode == "night_care") {
            BedtimeCareController.getInstance(this).showBedtimeCareDialog(containerHost = host) {
                updateStatusDisplay()
            }
            return
        }

        val snap = tracker.snapshot.value
        val userSettingIntensity = UserCoachingSettingsStore.getInstance(this).loadSettings().characterIntensity
        val isStrong = (mode == "offer_strong" || (mode == "retry" && lastIntensity == CharacterIntensity.STRONG))
        val intensity = if (isStrong) CharacterIntensity.STRONG else userSettingIntensity
        lastIntensity = intensity
        val rec = RestMissionCatalog.pick(snap.posture, isStrong)
        activeRecommendation = rec

        val scene = when (mode) {
            "retry" -> factory.retryOffer(
                onChoice = { title, _ -> choose(title, true) },
                intensityOverride = intensity,
                recommendation = rec
            )
            "complete" -> factory.completion(recommendation = rec) {
                updateStatusDisplay()
            }
            else -> {
                val reasons = InterventionReasonResolver.resolveReasons(
                    isJev = false,
                    jevContext = null,
                    continuousUsedMs = 1_800_000L,
                    ambient = snap,
                    shortformAppName = "숏폼 시청"
                )
                factory.offer(
                    onChoice = { title, _ -> choose(title, false) },
                    reasons = reasons,
                    intensityOverride = intensity,
                    recommendation = rec
                )
            }
        }
        current = scene.first
        factory.attachPreview(scene.first, scene.second)
        if (intensity == CharacterIntensity.STRONG || rec.isFacedownRequired) {
            tracker.onFacedownDetectedWhileScreenOn = {
                handler.post {
                    if (current != null && !missionActive) {
                        missionActive = true
                        missionAccumulatedRestMs = 0L
                        missionOffAt = 0L
                        current?.let { factory.detachPreview(it) }
                        current = null
                        selected = false
                        statusText.text = "스마트폰을 바닥에 엎어두면 화면이 꺼지고 휴식이 시작돼요!"
                    }
                    val locked = com.example.itv.infrastructure.observation.accessibility.WritingGuardService.lockScreen()
                    if (!locked) {
                        android.widget.Toast.makeText(applicationContext, "엎어둠 감지! 전원 버튼을 눌러 화면을 꺼주세요.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            tracker.startFacedownMonitoring()
        }
    }

    private fun choose(title: String, isRetry: Boolean) {
        if (selected) return
        selected = true
        val root = current ?: return
        factory.disableControls(root)

        if (title == "쉬기") {
            val rec = activeRecommendation ?: RestMissionCatalog.pick(tracker.snapshot.value.posture, lastIntensity == CharacterIntensity.STRONG)
            activeRecommendation = rec
            if (rec.isFacedownRequired) {
                tracker.onFacedownDetectedWhileScreenOn = {
                    com.example.itv.infrastructure.observation.accessibility.WritingGuardService.lockScreen()
                }
                tracker.startFacedownMonitoring()
            }
            val restSec = missionRestDurationMs / 1000L
            factory.playReaction(root, "쉬기", 10L, 10L, restSec, rec) {
                // 사용자가 "미션 확인" 버튼을 눌렀을 때
                missionActive = true
                missionAccumulatedRestMs = 0L
                missionOffAt = 0L
                missionResumeTimeout?.let { handler.removeCallbacks(it) }
                current?.let { factory.detachPreview(it) }
                current = null
                selected = false
                if (rec.isFacedownRequired) {
                    tracker.onFacedownDetectedWhileScreenOn = {
                        com.example.itv.infrastructure.observation.accessibility.WritingGuardService.lockScreen()
                    }
                    tracker.startFacedownMonitoring()
                }
                statusText.text = if (rec.isFacedownRequired) {
                    "스마트폰을 바닥에 엎어두면 화면이 꺼지고 휴식이 시작돼요! (${restSec}초 목표)"
                } else {
                    "10초 내 화면을 끄고 휴식을 시작해주세요! (${restSec}초 목표)"
                }
            }
            return
        }

        handler.postDelayed({
            if (!isFinishing) show("complete")
        }, 1200L)
    }

    override fun onDestroy() {
        super.onDestroy()
        missionResumeTimeout?.let { handler.removeCallbacks(it) }
        factory.dismissMissionCountdownHud()
        clear()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
