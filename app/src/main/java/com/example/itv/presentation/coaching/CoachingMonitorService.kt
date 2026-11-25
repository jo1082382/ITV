package com.example.itv.presentation.coaching
import com.example.itv.infrastructure.coaching.InterventionJournal
import com.example.itv.infrastructure.coaching.MissionStats

import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe
import com.example.itv.infrastructure.observation.accessibility.WritingGuard


import kotlinx.coroutines.*
import androidx.room.withTransaction
import com.example.itv.infrastructure.jev.*
import com.example.itv.domain.jev.JevSettings
import com.example.itv.domain.jev.JevContext
import com.example.itv.domain.jev.JevCandidatePolicy
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.*
import android.provider.Settings
import android.view.*
import com.example.itv.domain.coaching.DeveloperStatus
import com.example.itv.domain.coaching.DeveloperSnapshot
import com.example.itv.R
import com.example.itv.application.coaching.*
import com.example.itv.domain.coaching.TestCoachingPolicy
import com.example.itv.domain.coaching.CharacterEscalationTracker
import com.example.itv.domain.coaching.CharacterIntensity
import com.example.itv.infrastructure.coaching.*

class CoachingMonitorService : Service() {
    companion object {
        const val ACTION_TRIGGER_TEST = "com.example.itv.TRIGGER_TEST"
        const val EXTRA_FORCE_STRONG = "extra_force_strong"
        const val EXTRA_DELAY_MS = "extra_delay_ms"
        const val EXTRA_CUSTOM_REASONS = "extra_custom_reasons"
        const val EXTRA_INTENSITY_OVERRIDE = "extra_intensity_override"
        const val EXTRA_TONE_OVERRIDE = "extra_tone_override"

        @Volatile
        private var instanceRef: java.lang.ref.WeakReference<CoachingMonitorService>? = null

        fun notifyUserActivity(reason: String) {
            instanceRef?.get()?.let { service ->
                service.handler.post { service.onUserActivity(reason) }
            }
        }

        fun triggerTest(
            context: android.content.Context,
            forceStrong: Boolean = false,
            delayMs: Long = 0L,
            customReasons: List<String>? = null,
            intensityOverride: CharacterIntensity? = null,
            toneOverride: com.example.itv.domain.coaching.CoachingTone? = null
        ) {
            val intent = Intent(context, CoachingMonitorService::class.java).apply {
                action = ACTION_TRIGGER_TEST
                putExtra(EXTRA_FORCE_STRONG, forceStrong)
                putExtra(EXTRA_DELAY_MS, delayMs)
                if (!customReasons.isNullOrEmpty()) {
                    putStringArrayListExtra(EXTRA_CUSTOM_REASONS, ArrayList(customReasons))
                }
                if (intensityOverride != null) {
                    putExtra(EXTRA_INTENSITY_OVERRIDE, intensityOverride.name)
                }
                if (toneOverride != null) {
                    putExtra(EXTRA_TONE_OVERRIDE, toneOverride.name)
                }
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
    private val jev by lazy { JevRuntime.get(this) }
    private val bedtimeCareController by lazy { com.example.itv.presentation.sleep.BedtimeCareController.getInstance(this) }
    private fun testMode() = CoachingAutoStart.testModeEnabled(this)
    private fun jevMode() = !testMode() && JevFeature.available() && jev.enabled
    private val jevUiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var feedbackView: View? = null
    private var feedbackQueryPending = false
    private var feedbackSaving = false
    private var lastFeedbackQueryElapsed = 0L


    private val journal: AndroidCoachingJournal by lazy { AndroidCoachingJournal(this) }
    private val observer: AndroidCoachingObserver by lazy { AndroidCoachingObserver(this) }
    private val responseController: CoachingResponseController by lazy { CoachingResponseController(journal) }
    private val missionController: TestMissionController by lazy {
        TestMissionController(journal, AndroidMissionHistory(this), object: MissionScheduler {
            override fun cancelStartTimeout() { handler.removeCallbacks(missionStartTimeout) }
            override fun scheduleStartTimeout(delayMs: Long) { handler.postDelayed(missionStartTimeout, delayMs) }
        })
    }
    private val progress: CoachingProgressCoordinator by lazy {
        CoachingProgressCoordinator(responseController, object : CoachingProgressEffects {
            override fun status(value: String) { com.example.itv.composition.CoachingAccess.state(this@CoachingMonitorService, value) }
            override fun hideOffer() {
                if (overlayFactory.isStrong(overlay) && unlocked()) return
                if (overlay != null && !observer.snapshot().isActivelyWriting() && unlocked()) return
                removeOverlay()
                nm.cancel(7102)
            }
            override fun showOffer() { showPuppy(transition = false) }
            override fun onRestCompleted() {
                com.example.itv.infrastructure.observation.SessionCategoryTracker.clear()
                resetPreInterventionSession()
                nightGentleAlertFired = false
                nightBedtimeCarePending = false
                if (jevMode()) {
                    jev.resetProbability("CONFIRMED_NATURAL_REST")
                }
            }
        })
    }
    private val overlayFactory: CoachingOverlayFactory by lazy { CoachingOverlayFactory(this) }
    private val escalationTracker = CharacterEscalationTracker()
    private var retryPending=false
    private var retryVisible=false
    private var offerFromJev=false
    private var missionFromJev=false
    private var lastJevMode: Boolean? = null
    private var missionJevSettings: JevSettings?=null
    private var missionJevContext: JevContext?=null
    private var lastOfferedIntensity: CharacterIntensity? = null
    private var activeMissionIntensity: CharacterIntensity? = null
    private var preInterventionTriggeredInSession = false
    private var preInterventionShownAtElapsed = 0L
    private var darkUsageAccumulatedMs = 0L
    private var lastDarkTickElapsed: Long? = null
    private var offerIsPreIntervention = false
    private var screenOffSinceWallMs: Long? = null
    private var lastUnlockedElapsed: Long = 0L

    private fun resetPreInterventionSession() {
        preInterventionTriggeredInSession = false
        preInterventionShownAtElapsed = 0L
        darkUsageAccumulatedMs = 0L
        lastDarkTickElapsed = null
        offerIsPreIntervention = false
    }

    /**
     * 자동 상황별 개입 강도:
     * - 사전 개입(약하게): 취침 전/경과 암실·어두운 환경에서 [ContextualPreInterventionPolicy]가 자동 실행
     * - 본 개입(보통): 기본 개입 강도
     * - 자동 격상(강하게): '나중에'/무응답 연속 2회 이상 시 [CharacterEscalationTracker]가 자동 격상
     */
    private fun getEffectiveIntensity(): CharacterIntensity {
        return escalationTracker.resolveEffectiveIntensity(CharacterIntensity.NORMAL)
    }
    private fun reminderText(ms: Long): String {
        val sec = ms / 1000L
        return if (sec % 60L == 0L) "${sec / 60L}분" else "${sec}초"
    }
    private fun onChoice(title: String, @Suppress("UNUSED_PARAMETER") message: String) {
        if(reactionInProgress)return
        if(retryVisible) {
            if(missionFromJev && !canContinueJevRetry()) { removeOverlay();return }
            when (title) {
                "쉬기" -> {
                    val retryId = missionController.state.id ?: java.util.UUID.randomUUID().toString().take(8)
                    startRestMission(retryId)
                    selectedAnimation("쉬기") {
                        removeOverlay()
                        missionController.refreshStartDeadline(SystemClock.elapsedRealtime(), missionFromJev)
                    }
                    com.example.itv.composition.CoachingAccess.state(this, "$title 선택 · 충분한 휴식 전까지 재제안 안 함")
                }
                "나중에" -> {
                    escalationTracker.recordResistance()
                    val delayMs = TestCoachingPolicy.reminderMs(missionFromJev)
                    responseController.suppressed = false
                    responseController.offered = false
                    responseController.retryAt = SystemClock.elapsedRealtime() + delayMs
                    selectedAnimation("나중에") { removeOverlay() }
                    com.example.itv.composition.CoachingAccess.state(this, "나중에 선택 · ${reminderText(delayMs)} 뒤 조건 재확인")
                }
                "닫기" -> {
                    responseController.suppressed = true
                    responseController.retryAt = null
                    selectedAnimation("닫기") { removeOverlay() }
                    com.example.itv.composition.CoachingAccess.state(this, "$title 선택 · 충분한 휴식 전까지 재제안 안 함")
                }
            }
            return
        }
        if (offerIsPreIntervention) {
            val preId = responseController.offerId ?: ("pre-" + java.util.UUID.randomUUID().toString().take(4))
            offerIsPreIntervention = false
            handler.removeCallbacks(offerTimeout)
            if (title == "쉬기") {
                missionFromJev = false
                missionJevSettings = null
                missionJevContext = null
                responseController.suppressed = true
                responseController.offered = false
                responseController.offerId = null
                responseController.retryAt = null
                startRestMission(preId)
                selectedAnimation("쉬기") {
                    removeOverlay()
                    missionController.refreshStartDeadline(SystemClock.elapsedRealtime(), false)
                }
                com.example.itv.composition.CoachingAccess.state(this, "사전 개입 쉬기 선택 · 휴식 미션 시작")
            } else {
                // 약한 사전 개입은 6초 자동 퇴장 시 저항 횟수/억제/보류를 걸지 않고, 계속 시청 시 본 개입(취침 케어 등)으로 이어지도록 둡니다.
                responseController.offered = false
                responseController.offerId = null
                removeOverlay()
                com.example.itv.composition.CoachingAccess.state(this, "사전 개입 완료 · 계속 사용 시 본 개입 진행")
            }
            return
        }
        val selectedId = responseController.offerId ?: return
        val isJev = offerFromJev && JevFeature.available() && jev.hasActiveOffer()
        val reminderMs = TestCoachingPolicy.reminderMs(isJev)
        val outcome = responseController.choose(title, SystemClock.elapsedRealtime(), reminderMs)
        isRestMissionFailure = false
        if (isJev && outcome !in setOf(ResponseOutcome.IGNORED, ResponseOutcome.EXPIRED)) {
            jev.respond(when (outcome) {
                ResponseOutcome.REST -> "REST"
                ResponseOutcome.LATER -> "LATER"
                else -> "DISMISS"
            })
            if (outcome == ResponseOutcome.LATER) responseController.retryAt = SystemClock.elapsedRealtime() + reminderMs
        }
        when (outcome) {
            ResponseOutcome.IGNORED -> return
            ResponseOutcome.EXPIRED -> { offerTimeout.run(); return }
            ResponseOutcome.REST -> {
                handler.removeCallbacks(offerTimeout)
                missionFromJev=isJev
                missionJevSettings=if(isJev)jev.store.settings.value else null
                missionJevContext=if(isJev)jev.state.value.context else null
                startRestMission(selectedId)
                selectedAnimation("쉬기") {
                    removeOverlay(); nm.cancel(7102)
                    missionController.refreshStartDeadline(SystemClock.elapsedRealtime(), missionFromJev)
                }
                com.example.itv.composition.CoachingAccess.state(this, "$title 선택 · 충분한 휴식 전까지 재제안 안 함")
            }
            ResponseOutcome.LATER -> {
                handler.removeCallbacks(offerTimeout)
                escalationTracker.recordResistance()
                activeMissionIntensity = null
                currentRecommendation = null
                missionController.state.clear()
                selectedAnimation("나중에") { removeOverlay(); nm.cancel(7102) }
                com.example.itv.composition.CoachingAccess.state(this, "나중에 선택 · ${reminderText(reminderMs)} 뒤 조건 재확인")
            }
            ResponseOutcome.DISMISS -> {
                handler.removeCallbacks(offerTimeout)
                activeMissionIntensity = null
                currentRecommendation = null
                missionController.state.clear()
                selectedAnimation("닫기") { removeOverlay(); nm.cancel(7102) }
                com.example.itv.composition.CoachingAccess.state(this, "$title 선택 · 충분한 휴식 전까지 재제안 안 함")
            }
        }
        // Feedback waits until the reaction windows and any rest mission have finished.
    }

    private var currentRecommendation: com.example.itv.domain.coaching.RestRecommendation? = null

    private fun buildMissionSelectionContext(
        ambient: com.example.itv.infrastructure.observation.AmbientSnapshot,
        posture: com.example.itv.domain.coaching.UserPosture = ambient.posture,
        shortformAppName: String? = null
    ): com.example.itv.domain.coaching.MissionSelectionContext {
        val genSnapshot = com.example.itv.infrastructure.observation.GeneralAppTracker.snapshot.value
        val isGame = genSnapshot.category == com.example.itv.infrastructure.observation.InferredAppCategory.GAME ||
            genSnapshot.gamePhase != com.example.itv.infrastructure.observation.GamePhase.NONE
        val isShortsOrReels = shortformAppName != null ||
            YoutubeProbe.screenState() == 1 ||
            ReelsProbe.screenState() == 1
        val isLongform = com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyWatchingLongform()
        val isDarkOrBedtime = ambient.isBedtimeCandidate || (ambient.lux ?: 100f) < 10f

        return com.example.itv.domain.coaching.MissionSelectionContext(
            appCategory = genSnapshot.category,
            isShortform = isShortsOrReels,
            isLongform = isLongform,
            isGame = isGame,
            isStationary = ambient.isStationary,
            isDarkOrBedtime = isDarkOrBedtime,
            continuousUsedMs = usageClock.usedMs,
            currentHour = java.time.LocalTime.now().hour,
            posture = posture
        )
    }

    private fun startRestMission(id: String) {
        if(!running || !com.example.itv.composition.CoachingAccess.ready(this))return
        val tracker = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this)
        val posture = tracker.currentPosture()
        val isStrong = activeMissionIntensity == CharacterIntensity.STRONG ||
            lastOfferedIntensity == CharacterIntensity.STRONG ||
            missionController.state.isStrong ||
            currentRecommendation?.isFacedownRequired == true ||
            (getEffectiveIntensity() == CharacterIntensity.STRONG)
        val effectiveIntensity = if (isStrong) CharacterIntensity.STRONG else getEffectiveIntensity()
        activeMissionIntensity = effectiveIntensity
        lastOfferedIntensity = effectiveIntensity
        val missionContext = buildMissionSelectionContext(tracker.snapshot.value, posture)
        val rec = currentRecommendation ?: com.example.itv.domain.coaching.RestMissionCatalog.pick(posture, isStrong, missionContext).also {
            currentRecommendation = it
        }
        if (rec.isFacedownRequired) {
            tracker.onFacedownDetectedWhileScreenOn = {
                com.example.itv.infrastructure.observation.accessibility.WritingGuardService.lockScreen()
            }
            tracker.startFacedownMonitoring()
        }
        val calculatedRestMs = TestCoachingPolicy.calculateRestMs(missionFromJev, usageClock.usedMs)
        missionController.begin(
            id = id,
            now = SystemClock.elapsedRealtime(),
            isJev = missionFromJev,
            requireFacedown = rec.isFacedownRequired,
            isStrong = isStrong,
            continuousUsedMs = usageClock.usedMs,
            customRestMs = calculatedRestMs
        )
    }
    private fun canContinueJevRetry(): Boolean {
        val original=missionJevContext ?: return false
        val settings=missionJevSettings ?: return false
        val now=SystemClock.elapsedRealtime()
        val currentFg=JevObservationHub.foregroundPackage
        val sameForeground=(currentFg==original.packageName || JevObservationHub.isTransientSystemPackage(currentFg)) && now-JevObservationHub.checkedAt in 0..1500
        val currentContext=(jev.state.value.context ?: original.takeIf { sameForeground })?.let {
            if(sameForeground && it.packageName==original.packageName && it.sessionId==original.sessionId) it.copy(quality="VALID") else it
        }
        val isWriting = observer.snapshot().isActivelyWriting()
        return jevMode() && jev.store.tokenConfigured.value && !isWriting &&
            sameForeground &&
            JevCandidatePolicy.canContinueDisplayed(settings.revision,jev.store.settings.value,original,currentContext)
    }
    private var reactionInProgress=false
    private fun selectedAnimation(title: String, finished: () -> Unit) {
        val root=overlay ?: return
        if(reactionInProgress)return
        reactionInProgress=true
        overlayFactory.disableControls(root)
        val useJev=if(retryVisible)missionFromJev else offerFromJev
        val reminderSec=TestCoachingPolicy.reminderMs(useJev)/1000L
        val prepSec=TestCoachingPolicy.missionStartMs(useJev)/1000L
        val restMs = if (title == "쉬기" && missionController.state.id != null) {
            missionController.state.requiredRestMs
        } else {
            TestCoachingPolicy.calculateRestMs(useJev, usageClock.usedMs)
        }
        val restSec = restMs / 1000L
        overlayFactory.playReaction(root,title,reminderSec,prepSec,restSec,currentRecommendation) {
            if(overlay===root && running) {
                reactionInProgress=false
                finished()
            }
        }
    }

    private var isRestMissionFailure = false
    private var closingCause = "표시 중단 · 안전 보호/화면 전환"
    private val missionStartTimeout: Runnable = Runnable { onMissionStartTimeout() }
    private fun onMissionStartTimeout() {
        if (missionController.state.id != null && !missionController.state.started && !missionController.state.pending) {
            if (!pm.isInteractive) missionScreenOff()
            val isStrong = missionController.state.isStrong || missionController.state.requireFacedown
            if (!isStrong) {
                // 약한/보통 개입: 준비 시간 만료 시 미션 시작 실패 처리
                if (!missionController.state.started) failMissionStart()
            }
            // 강한 개입: 사용자가 휴식을 완료하기 전까지는 미션 창이 자동으로 닫히거나 취소되지 않고 유지됨
        }
    }
    private val missionResumeTimeout: Runnable = Runnable { onMissionResumeTimeout() }
    private fun onMissionResumeTimeout() {
        if (missionController.state.id != null && missionController.state.offAt == null && !missionController.state.pending) {
            if (!pm.isInteractive) missionScreenOff()
            val isStrong = missionController.state.isStrong || missionController.state.requireFacedown
            if (!isStrong) {
                // 약한/보통 개입: 재개 준비 시간(카운트다운 HUD) 만료 시 HUD 종료 및 재개 실패 처리
                overlayFactory.dismissMissionCountdownHud()
                com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).stopFacedownMonitoring()
                missionController.failResume()
                retryPending = true
                isRestMissionFailure = true
                checkRetry(transition = true)
            }
            // 강한 개입: 사용자가 휴식을 완료하기 전까지는 미션 창이 자동으로 닫히거나 취소되지 않고 유지됨
        }
    }
    private fun failMissionStart() {
        overlayFactory.dismissMissionCountdownHud()
        val failed = missionController.state.id != null && !missionController.state.started && !missionController.state.pending
        if (failed) {
            com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).stopFacedownMonitoring()
        }
        missionController.failStart()
        if (failed) {
            retryPending = true
            isRestMissionFailure = true
            checkRetry(transition = true)
        }
    }
    private fun checkRetry(transition: Boolean = false) {
        if (!retryPending || !running) return
        if (!isRestMissionFailure && missionFromJev && !canContinueJevRetry()) { retryPending = false; return }
        val inActiveRest = missionController.state.id != null && (missionController.state.offAt != null || missionController.state.pending)
        if (overlay != null || feedbackView != null || completionView != null || inActiveRest || !unlocked()) return
        if (!com.example.itv.composition.CoachingAccess.ready(this) || !Settings.canDrawOverlays(this)) return
        if (com.example.itv.infrastructure.observation.GeneralAppTracker.isFinancialPackage(JevObservationHub.foregroundPackage)) return
        val blockReason = WritingGuard.blockReason()
        if (blockReason != null && (!isRestMissionFailure || blockReason.contains("키보드"))) return
        val inVideo = ReelsProbe.screenState() == 1 ||
            YoutubeProbe.screenState() == 1 ||
            com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments() ||
            com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyWatchingLongform() ||
            com.example.itv.infrastructure.observation.instagram.InstagramTracker.isActivelyInReelsCommentsOrStory() ||
            com.example.itv.infrastructure.observation.GeneralAppTracker.isInterventionDeferredOrBlocked(JevObservationHub.foregroundPackage)
        if (!isRestMissionFailure && !transition && inVideo) return
        val isStrong = activeMissionIntensity == CharacterIntensity.STRONG ||
            missionController.state.isStrong ||
            lastOfferedIntensity == CharacterIntensity.STRONG ||
            currentRecommendation?.isFacedownRequired == true ||
            missionController.state.requireFacedown ||
            getEffectiveIntensity() == CharacterIntensity.STRONG
        if (isLandscapeNow() && !isStrong) {
            landscapeDeferred = true
            return
        }
        landscapeDeferred = false
        val effectiveIntensity = if (isStrong) CharacterIntensity.STRONG else getEffectiveIntensity()
        activeMissionIntensity = effectiveIntensity
        lastOfferedIntensity = effectiveIntensity
        val tracker = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this)
        val posture = tracker.currentPosture()
        val missionContext = buildMissionSelectionContext(tracker.snapshot.value, posture)
        val rec = currentRecommendation ?: com.example.itv.domain.coaching.RestMissionCatalog.pick(
            posture,
            isStrong,
            missionContext
        ).also { currentRecommendation = it }
        val remainingSec = (missionController.state.remainingRestMs / 1000L).takeIf { it > 0L }
        val (root, params) = overlayFactory.retryOffer(
            ::onChoice,
            intensityOverride = effectiveIntensity,
            recommendation = rec,
            remainingRestSeconds = remainingSec,
            prepareSeconds = 10L
        )
        try {
            wm.addView(root, params); overlay = root; retryVisible = true
            displayedPackage = if (isRestMissionFailure) null else JevObservationHub.foregroundPackage
            overlayFactory.attachControls(root); retryPending = false
            if (isStrong || rec.isFacedownRequired) {
                val tracker = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this)
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
            handler.postDelayed({
                if (overlay === root) {
                    if (overlayFactory.isStrong(overlay) && unlocked()) {
                        escalationTracker.recordResistance()
                    } else {
                        escalationTracker.recordResistance(); retryVisible = false; removeOverlay()
                    }
                }
            }, 45_000L)
        } catch (_: Exception) { retryVisible = false; removeOverlay() }
    }
    private var completionView: View? = null
    private val offerTimeout: Runnable = Runnable {
        if (responseController.offerId != null && overlay != null) {
            val isStrong = overlayFactory.isStrong(overlay)
            if (isStrong && unlocked()) {
                escalationTracker.recordResistance()
                val isJev = offerFromJev && JevFeature.available() && jev.hasActiveOffer()
                val reminderMs = TestCoachingPolicy.reminderMs(isJev)
                handler.postDelayed(offerTimeout, reminderMs)
                return@Runnable
            }
            val isJev = offerFromJev && JevFeature.available() && jev.hasActiveOffer()
            val responseMs = TestCoachingPolicy.responseMs(isJev)
            val reminderMs = TestCoachingPolicy.reminderMs(isJev)
            if (isJev) jev.respond("NO_RESPONSE")
            responseController.timeout(SystemClock.elapsedRealtime(), responseMs, reminderMs)
            if (isJev) responseController.retryAt = SystemClock.elapsedRealtime() + reminderMs
            escalationTracker.recordResistance()
            removeOverlay(); nm.cancel(7102)
            com.example.itv.composition.CoachingAccess.state(this, "무응답 · ${reminderText(reminderMs)} 뒤 조건 재확인")
        }
    }
    private fun finishOffer(outcome: String) {
        responseController.finish(outcome)
        handler.removeCallbacks(offerTimeout)
    }
    private fun missionScreenOff() {
        handler.removeCallbacks(missionResumeTimeout)
        overlayFactory.dismissMissionCountdownHud()
        if (missionController.state.id != null && !missionController.state.pending && missionController.state.offAt == null) {
            if (missionController.state.requireFacedown || currentRecommendation?.isFacedownRequired == true) {
                com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).onMissionScreenOff()
            }
        }
        missionController.screenOff(SystemClock.elapsedRealtime())
    }
    private fun missionScreenOn() {
        // 화면이 실제로 꺼져서 휴식 측정이 시작된 상태(offAt != null)가 아니면 절대 facedown 감지를 중지하지 않음
        if (missionController.state.id == null || missionController.state.offAt == null || missionController.state.pending) return
        val tracker = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this)
        val needFacedown = missionController.state.requireFacedown || currentRecommendation?.isFacedownRequired == true
        val facedownObserved = if (needFacedown) {
            tracker.stopFacedownMonitoring()
        } else true
        val wake = missionController.screenOn(SystemClock.elapsedRealtime(), isFacedownObserved = facedownObserved)
        if (wake == com.example.itv.domain.coaching.TestMissionWake.INCOMPLETE) {
            val remainingMs = missionController.state.remainingRestMs
            val remainingSec = (remainingMs + 999L) / 1000L
            val isStrong = missionController.state.isStrong || missionController.state.requireFacedown
            val rec = currentRecommendation ?: run {
                val ambient = tracker.snapshot.value
                val posture = ambient.posture
                com.example.itv.domain.coaching.RestMissionCatalog.pick(posture, isStrong, buildMissionSelectionContext(ambient, posture))
            }
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

            if (isStrong) {
                // [강한 개입 전용]: 휴식을 다 채우지 않고 다시 킨 경우 카운트다운 없이 어두운 배경 + 남은 휴식 안내 창 + 캐릭터를 다시 띄워 휴식을 완료하도록 유도
                removeOverlay()
                val (root, params) = overlayFactory.retryOffer(
                    onChoice = ::onChoice,
                    intensityOverride = CharacterIntensity.STRONG,
                    recommendation = rec,
                    remainingRestSeconds = remainingSec,
                    prepareSeconds = prepareSeconds
                )
                try {
                    wm.addView(root, params)
                    overlay = root
                    retryVisible = true
                    overlayFactory.attachControls(root)
                } catch (_: Exception) {}

                tracker.onFacedownDetectedWhileScreenOn = {
                    com.example.itv.infrastructure.observation.accessibility.WritingGuardService.lockScreen()
                }
                tracker.startFacedownMonitoring()
            } else {
                // [약한/보통 개입]: 화면을 어둡게 막지 않고 상단 카운트다운 HUD만 띄우며, 카운트다운 만료 시 사라짐
                overlayFactory.showMissionCountdownHud(
                    rec = rec,
                    targetTimeStr = targetTimeStr,
                    restDisplayDesc = restDisplayDesc,
                    prepareSeconds = prepareSeconds,
                    isResume = true,
                    remainingRestSeconds = remainingSec
                )
                if (rec.isFacedownRequired) {
                    tracker.onFacedownDetectedWhileScreenOn = {
                        com.example.itv.infrastructure.observation.accessibility.WritingGuardService.lockScreen()
                    }
                    tracker.startFacedownMonitoring()
                }
                handler.removeCallbacks(missionResumeTimeout)
                handler.postDelayed(missionResumeTimeout, prepareSeconds * 1000L)
            }
        } else if (wake == com.example.itv.domain.coaching.TestMissionWake.SUCCESS) {
            handler.removeCallbacks(missionResumeTimeout)
            overlayFactory.dismissMissionCountdownHud()
            onMissionRestSuccess()
        }
    }
    private fun onMissionRestSuccess() {
        handler.removeCallbacks(missionResumeTimeout)
        overlayFactory.dismissMissionCountdownHud()
        retryPending = false
        isRestMissionFailure = false
        usageClock.clear()
        com.example.itv.infrastructure.observation.SessionCategoryTracker.clear()
        resetOfferState()
        resetPreInterventionSession()
        activeMissionIntensity = null
        lastOfferedIntensity = null
        currentRecommendation = null
        landscapeDeferred = false
        escalationTracker.reset()
        if (jevMode()) {
            jev.onMissionRestSuccess()
        }
    }
    private fun checkMission() {
        if (!pm.isInteractive) missionScreenOff() else missionScreenOn()
        if (!missionController.state.pending || !unlocked() || completionView != null) return
        if (!com.example.itv.composition.CoachingAccess.ready(this) || !Settings.canDrawOverlays(this)) return
        val reason = WritingGuard.blockReason()
        if (reason != null && reason.contains("작성 중")) return
        if (missionController.state.id == null) return
        val (box, params) = overlayFactory.completion(currentRecommendation, ::dismissCompletion)
        try {
            wm.addView(box, params); completionView = box
            missionController.completionDisplayed()
            overlayFactory.attachControls(box)
        } catch (_: Exception) {
            // Keep pending to retry when permission/window state permits it.
        }
    }
    private fun dismissCompletion() {
        completionView?.let { overlayFactory.removeControls(it); try { wm.removeView(it) } catch (_: Exception) {} }
        completionView = null
        if (!missionController.state.pending && missionController.state.id == null) {
            currentRecommendation = null
            activeMissionIntensity = null
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val usageClock: com.example.itv.domain.coaching.RestUsageClock get() = progress.usageClock
    private var waitingYoutube: Boolean
        get() = progress.waitingYoutube
        set(value) { progress.waitingYoutube = value }
    private var waitingTransition: Boolean
        get() = progress.waitingTransition
        set(value) { progress.waitingTransition = value }
    private val interventionMs = TestCoachingPolicy.INTERVENTION_MS
    private var overlay: View? = null
    private var displayedPackage: String? = null
    private var receiverRegistered = false
    private var running = false
    private var foregroundStarted = false
    private var nightGentleAlertFired = false
    private var nightBedtimeCarePending = false
    private var wasLandscape = false
    private var landscapeDeferred = false
    private val portraitReturnRunnable = Runnable { onOrientationReturnedToPortrait() }
    private var safeSince: Long?
        get() = progress.safeSince
        set(value) { progress.safeSince = value }
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val pm by lazy { getSystemService(PowerManager::class.java) }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private fun unlocked() = observer.unlocked()
    private fun isLandscapeNow(config: Configuration = resources.configuration): Boolean {
        return when (config.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> true
            Configuration.ORIENTATION_PORTRAIT -> false
            else -> try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val b = wm.currentWindowMetrics.bounds
                    b.width() > b.height()
                } else {
                    val dm = resources.displayMetrics
                    dm.widthPixels > dm.heightPixels
                }
            } catch (_: Exception) {
                false
            }
        }
    }
    private fun resetOfferState() {
        progress.reset()
        resetPreInterventionSession()
        landscapeDeferred = false
    }
    private fun observeUsage(): Boolean {
        val isUnlocked = unlocked()
        val nowElapsed = SystemClock.elapsedRealtime()
        val rawFgPkg = JevObservationHub.foregroundPackage
        val fgPkg = rawFgPkg.takeIf { nowElapsed - JevObservationHub.checkedAt in 0..4000 }
        val activePkg = fgPkg ?: rawFgPkg
        val isOwnApp = activePkg != null && (
            activePkg == packageName ||
            activePkg.startsWith("com.team.itv") ||
            activePkg.startsWith("com.example.itv")
        )
        val isNonCountedSystem = fgPkg != null && (
            JevSettings.isLauncherPackage(fgPkg) ||
            JevSettings.isExcludedSystemPackage(fgPkg)
        )
        // 3단계 가중치 적용:
        // - 1:1 (FULL_1_TO_1): YouTube 쇼츠, Instagram 릴스 등 확정 숏폼
        // - 1:2 (MID_1_TO_2): 피드·SNS (인스타 피드/탐색, 유튜브 홈/피드, 커뮤니티/SNS) 및 게임
        // - 1:3 (LOW_1_TO_3): 유튜브 롱폼 영상, 인스타 DM, 웹/검색, 문서/메신저, 기타 일반 앱
        val weightTier = if (isUnlocked && !isOwnApp && !isNonCountedSystem) {
            com.example.itv.infrastructure.observation.UsageWeightResolver.resolveTier(fgPkg)
        } else {
            com.example.itv.domain.coaching.UsageWeightTier.LOW_1_TO_3
        }
        val isShortform = weightTier == com.example.itv.domain.coaching.UsageWeightTier.FULL_1_TO_1
        // 우리 앱(ITV)이나 홈 런처·시스템 UI 체류 중에는 사용 시간 증가를 일시정지하고 기존 누적값을 보존한다.
        val paused = isUnlocked && (isOwnApp || isNonCountedSystem)
        com.example.itv.infrastructure.observation.SessionCategoryTracker.observe(
            nowElapsed = nowElapsed,
            isUnlocked = isUnlocked,
            fgPkg = activePkg,
            paused = paused
        )
        return progress.observe(
            now = nowElapsed,
            using = isUnlocked,
            offerVisible = overlay != null,
            isShortform = isShortform,
            paused = paused,
            weightTier = weightTier,
            requiredRestMs = TestCoachingPolicy.naturalRestMs(jevMode())
        )
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // SCREEN_OFF is definitive even if PowerManager has not updated yet.
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                if (screenOffSinceWallMs == null) {
                    screenOffSinceWallMs = System.currentTimeMillis()
                }
                missionScreenOff()
                if (overlayFactory.isStrong(overlay)) {
                    removeOverlay()
                }
                progress.observe(
                    now = SystemClock.elapsedRealtime(),
                    using = false,
                    offerVisible = overlay != null,
                    isShortform = false,
                    requiredRestMs = TestCoachingPolicy.naturalRestMs(jevMode())
                )
            } else {
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    lastUnlockedElapsed = SystemClock.elapsedRealtime()
                    applyWallClockScreenOffRestIfNeeded()
                    missionScreenOn()
                    bedtimeCareController.showMorningBriefingIfNeeded(JevObservationHub.foregroundPackage)
                }
                observeUsage()
                checkMission()
            }
        }
    }

    /**
     * Doze/화면 꺼짐 중 1초 핸들러가 지연되더라도 실제 경과 시간(wall-clock) 기준으로
     * 자발적 휴식(Jev 3분 / 테스트 15초)이 충족되었으면 사용량·암실 누적·사전 개입 상태를 깔끔히 초기화합니다.
     */
    private fun applyWallClockScreenOffRestIfNeeded() {
        val offAt = screenOffSinceWallMs ?: return
        val nowWall = System.currentTimeMillis()
        val naturalRestMs = TestCoachingPolicy.naturalRestMs(jevMode())
        if (nowWall - offAt >= naturalRestMs) {
            usageClock.clear()
            com.example.itv.infrastructure.observation.SessionCategoryTracker.clear()
            resetOfferState()
            nightGentleAlertFired = false
            nightBedtimeCarePending = false
            if (com.example.itv.presentation.coaching.LongformDiagnosticHud.isShowing) {
                com.example.itv.presentation.coaching.LongformDiagnosticHud.setSleepingState(false)
            }
            if (jevMode()) {
                jev.resetProbability("SCREEN_OFF_NATURAL_REST")
            }
        }
        if (unlocked()) {
            screenOffSinceWallMs = null
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!CoachingAutoStart.enabled(this@CoachingMonitorService) || !com.example.itv.composition.CoachingAccess.ready(this@CoachingMonitorService)) {
                com.example.itv.composition.CoachingAccess.state(this@CoachingMonitorService, "동의 또는 권한이 해제되어 중지됨")
                running = false; stopSelf(); return
            }
            val currentJevMode = jevMode()
            if (lastJevMode != null && lastJevMode != currentJevMode) {
                if (overlay == null && missionController.state.id == null) {
                    usageClock.clear()
                    resetOfferState()
                }
            }
            lastJevMode = currentJevMode
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowLandscape = isLandscapeNow()
            if (wasLandscape && !nowLandscape) {
                handler.removeCallbacks(portraitReturnRunnable)
                handler.postDelayed(portraitReturnRunnable, 350L)
            }
            wasLandscape = nowLandscape
            if (!unlocked()) {
                if (screenOffSinceWallMs == null) {
                    screenOffSinceWallMs = System.currentTimeMillis()
                }
            } else {
                if (screenOffSinceWallMs != null) {
                    lastUnlockedElapsed = nowElapsed
                    applyWallClockScreenOffRestIfNeeded()
                }
            }
            checkMission()
            val isStrong = overlayFactory.isStrong(overlay)
            val usageObserved = observeUsage()
            val using = if (currentJevMode) unlocked() else (if (isStrong) unlocked() else usageObserved)
            val observation = observer.snapshot()
            val fgPkg = JevObservationHub.foregroundPackage.takeIf { nowElapsed - JevObservationHub.checkedAt in 0..JevRuntime.FOREGROUND_FRESHNESS_MS }
            val leftDisplayedApp = !isRestMissionFailure && displayedPackage != null && fgPkg != null && fgPkg != displayedPackage && !JevObservationHub.isTransientSystemPackage(fgPkg)
            val isWriting = observation.isActivelyWriting()
            if (overlay != null) {
                if (isStrong) {
                    if (!unlocked()) removeOverlay()
                } else {
                    if (!using || isWriting || leftDisplayedApp) {
                        val isAppSwitch = leftDisplayedApp && offerFromJev && !offerIsPreIntervention
                        if (currentJevMode && isAppSwitch) {
                            jev.transferOnAppSwitch()
                        }
                        removeOverlay(preserveForAppSwitch = currentJevMode && isAppSwitch)
                    }
                }
            }
            if (jevMode()) {
                jev.tick(using, missionController.state.id != null || completionView != null || reactionInProgress || retryPending || retryVisible, overlay != null)
                val current = when {
                    offerIsPreIntervention -> true
                    retryVisible -> if (isRestMissionFailure) true else canContinueJevRetry()
                    else -> jev.canKeepDisplayed()
                }
                if (overlay != null) {
                    if (isStrong) {
                        if (!unlocked()) removeOverlay()
                    } else {
                        if (!using || isWriting || !current) {
                            val isAppSwitch = !current && leftDisplayedApp && offerFromJev && !offerIsPreIntervention
                            if (isAppSwitch) {
                                jev.transferOnAppSwitch()
                            }
                            removeOverlay(preserveForAppSwitch = isAppSwitch)
                        }
                    }
                }
                if (overlay == null && !responseController.suppressed && !bedtimeCareController.isDialogShowing() && jev.canShow(transition = false)) showPuppy(transition = false)
            } else if (!reactionInProgress) {
                if (!isStrong) {
                    progress.tick(nowElapsed, using, observation, overlay != null)
                }
            }
            updateDarkUsageAccumulation(nowElapsed, using, fgPkg)
            maybeShowContextualPreIntervention(nowElapsed, transition = false)
            checkNightContinuousBudget(nowElapsed)
            checkRetry()
            if (feedbackView != null && (!using || !canShowJevFeedback())) dismissJevFeedback()
            maybeShowJevFeedback()
            publishDiagnostics()
            if (running) handler.postDelayed(this, 1000)
        }
    }

    /**
     * 어두운 환경(lux <= 15, 센서 가림 제외) 체류 시간을 누적하고,
     * 자발적 휴식이 확인되면 세션 사전 개입 상태를 초기화합니다.
     */
    private fun updateDarkUsageAccumulation(now: Long, using: Boolean, fgPkg: String?) {
        val naturalRestMs = TestCoachingPolicy.naturalRestMs(jevMode())
        val currentUsedMs = if (jevMode()) {
            jev.sessionState("ALL")?.continuousMs ?: jev.state.value.context?.continuousMs ?: 0L
        } else {
            usageClock.usedMs
        }
        if (currentUsedMs == 0L || usageClock.restMs >= naturalRestMs) {
            resetPreInterventionSession()
            return
        }
        val isOwnOrSystem = fgPkg == null ||
            fgPkg == packageName ||
            fgPkg.startsWith("com.team.itv") ||
            fgPkg.startsWith("com.example.itv") ||
            JevSettings.isLauncherPackage(fgPkg) ||
            JevSettings.isExcludedSystemPackage(fgPkg)
        val ambient = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).snapshot.value
        val isOccluded = ambient.isOccluded || (ambient.isNear && (ambient.proximityDistanceCm ?: 5f) <= 1.0f)
        val isDark = com.example.itv.domain.coaching.ContextualPreInterventionPolicy.isDarkRoom(ambient.lux, isOccluded)

        if (using && !isOwnOrSystem && isDark) {
            val prev = lastDarkTickElapsed
            if (prev != null) {
                val delta = now - prev
                if (delta in 1..5000L) {
                    darkUsageAccumulatedMs += delta
                }
            }
            lastDarkTickElapsed = now
        } else {
            lastDarkTickElapsed = null
        }
    }

    /**
     * 취침 전/경과 암실 또는 어두운 환경에서 일정 시간 사용 시
     * 본 개입(보통 개입 또는 취침 케어) 이전에 화면 구석의 약한 개입(GENTLE)을 사전 개입으로 자동 실행합니다.
     */
    private fun maybeShowContextualPreIntervention(now: Long, transition: Boolean): Boolean {
        if (!running || preInterventionTriggeredInSession) return false
        if (!transition && lastUnlockedElapsed > 0L && (now - lastUnlockedElapsed) < 5000L) return false
        if (overlay != null || feedbackView != null || completionView != null ||
            missionController.state.id != null || bedtimeCareController.isDialogShowing()
        ) return false
        if (responseController.offered || responseController.suppressed) return false
        if (responseController.retryAt != null && now < (responseController.retryAt ?: 0L)) return false

        val ambient = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).snapshot.value
        val isOccluded = ambient.isOccluded || (ambient.isNear && (ambient.proximityDistanceCm ?: 5f) <= 1.0f)
        val currentContinuousMs = if (jevMode()) {
            jev.sessionState("ALL")?.continuousMs ?: jev.state.value.context?.continuousMs ?: 0L
        } else {
            usageClock.usedMs
        }
        val resolvedBedtimeDistance = ambient.bedtimeDistanceMinutes ?: jev.state.value.context?.bedtimePassedMinutes
        val decision = com.example.itv.domain.coaching.ContextualPreInterventionPolicy.evaluate(
            ambientLux = ambient.lux,
            isOccluded = isOccluded,
            bedtimeDistanceMinutes = resolvedBedtimeDistance,
            isPastBedtime = ambient.bedtimePhase == com.example.itv.infrastructure.observation.BedtimePhase.PAST_BEDTIME,
            isApproachingBedtime = ambient.bedtimePhase == com.example.itv.infrastructure.observation.BedtimePhase.APPROACHING,
            continuousUsedMs = currentContinuousMs,
            darkAccumulatedMs = darkUsageAccumulatedMs,
            alreadyTriggeredInSession = preInterventionTriggeredInSession,
            isTestMode = testMode(),
            screenBrightness = ambient.screenBrightness
        ) ?: return false

        if (isLandscapeNow()) {
            landscapeDeferred = true
            return false
        }

        return showGentlePreIntervention(decision, transition = transition)
    }

    private fun showGentlePreIntervention(
        decision: com.example.itv.domain.coaching.PreInterventionDecision,
        transition: Boolean
    ): Boolean {
        if (overlay != null || feedbackView != null || completionView != null ||
            missionController.state.id != null || bedtimeCareController.isDialogShowing()
        ) return false
        if (!unlocked() || !com.example.itv.composition.CoachingAccess.ready(this) || !Settings.canDrawOverlays(this)) return false

        if (WritingGuard.blockReason() != null || observer.snapshot().isActivelyWriting()) return false
        val currentFg = JevObservationHub.foregroundPackage
        if (currentFg == null ||
            currentFg == packageName ||
            currentFg.startsWith("com.team.itv") ||
            currentFg.startsWith("com.example.itv") ||
            JevSettings.isLauncherPackage(currentFg) ||
            JevSettings.isExcludedSystemPackage(currentFg) ||
            JevSettings.isDocumentOrMessagingPackage(currentFg) ||
            com.example.itv.infrastructure.observation.GeneralAppTracker.isFinancialPackage(currentFg)
        ) return false

        val ytMode = com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.snapshot.value.mode
        if (currentFg == JevSettings.YOUTUBE && ytMode == com.example.itv.infrastructure.observation.youtube.YoutubeWatchMode.SHORTS_COMMENTS) {
            return false
        }
        val igMode = com.example.itv.infrastructure.observation.instagram.InstagramTracker.snapshot.value.mode
        if (currentFg == JevSettings.INSTAGRAM && (
                igMode == com.example.itv.infrastructure.observation.instagram.InstagramScreenMode.COMMENTS ||
                    igMode == com.example.itv.infrastructure.observation.instagram.InstagramScreenMode.STORY ||
                    igMode == com.example.itv.infrastructure.observation.instagram.InstagramScreenMode.DM
                )
        ) {
            return false
        }
        if (currentFg != JevSettings.YOUTUBE &&
            currentFg != JevSettings.INSTAGRAM &&
            com.example.itv.infrastructure.observation.GeneralAppTracker.isInterventionDeferredOrBlocked(currentFg)
        ) {
            return false
        }
        if (!transition) {
            val isWatchingShortform = YoutubeProbe.screenState() == 1 ||
                ReelsProbe.screenState() == 1 ||
                com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments() ||
                com.example.itv.infrastructure.observation.instagram.InstagramTracker.isActivelyInReelsCommentsOrStory()
            val isWatchingLongform = com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyWatchingLongform()
            if (isWatchingShortform || isWatchingLongform) return false
        }

        val ambient = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).snapshot.value
        val missionContext = buildMissionSelectionContext(ambient, ambient.posture)
        val rec = com.example.itv.domain.coaching.RestMissionCatalog.pick(ambient.posture, false, missionContext)
        currentRecommendation = rec
        lastOfferedIntensity = CharacterIntensity.GENTLE
        val (root, params) = overlayFactory.offer(
            ::onChoice,
            listOf(decision.reason),
            intensityOverride = CharacterIntensity.GENTLE,
            recommendation = rec,
            bubbleOverride = decision.bubbleContent
        )
        return try {
            val now = SystemClock.elapsedRealtime()
            wm.addView(root, params)
            overlay = root
            offerFromJev = false
            offerIsPreIntervention = true
            preInterventionTriggeredInSession = true
            preInterventionShownAtElapsed = now
            landscapeDeferred = false
            displayedPackage = currentFg
            responseController.offered = true
            responseController.offerId = "pre-" + java.util.UUID.randomUUID().toString().take(4)
            InterventionJournal.add(this, responseController.offerId!!, "사전 개입 표시 (약하게 · ${decision.kind.label})")
            overlayFactory.attachControls(root = root)
            com.example.itv.composition.CoachingAccess.state(this, "사전 개입(약하게) 표시 · ${decision.kind.label}")
            true
        } catch (_: Exception) {
            offerIsPreIntervention = false
            removeOverlay()
            false
        }
    }

    /**
     * 야간 취침 후보 시간대(암실/야간)에서 연속 사용 예산(정규 30분, 테스트 30초) 초과 여부를 감시합니다.
     * 게임이나 롱폼 시청 중이라면 큰 팝업으로 방해하지 않고 최상단 얇은 배너(터치 관통)와 HUD 수면 아이콘을 띄웁니다.
     * 활동이 종료되거나 안전한 전환 시점에 도달하면 대기 중인 전면 취침 케어를 제안합니다.
     */
    private fun checkNightContinuousBudget(now: Long) {
        val ambient = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).snapshot.value
        val naturalRestMs = TestCoachingPolicy.naturalRestMs(jevMode())
        val currentContinuousMs = if (jevMode()) {
            jev.sessionState("ALL")?.continuousMs ?: jev.state.value.context?.continuousMs ?: usageClock.usedMs
        } else {
            usageClock.usedMs
        }

        // 1. 자발적 휴식 또는 세션 리셋 확인 -> 수면 아이콘 및 경보 해제
        if (currentContinuousMs == 0L || usageClock.restMs >= naturalRestMs) {
            nightGentleAlertFired = false
            nightBedtimeCarePending = false
            if (com.example.itv.presentation.coaching.LongformDiagnosticHud.isShowing) {
                com.example.itv.presentation.coaching.LongformDiagnosticHud.setSleepingState(false)
            }
            return
        }

        // 2. 취침 후보 시간대(암실/야간 시간대) 및 취침 개입 설정 On 여부 검사
        if (!ambient.isBedtimeCandidate) return
        val coachingSettings = com.example.itv.infrastructure.coaching.UserCoachingSettingsStore.getInstance(this).loadSettings()
        if (!coachingSettings.bedtimeCareEnabled) return

        // 3. 야간 연속 사용 예산 산정 (테스트 환경: 30초, 정규 환경: 30분)
        val isTest = testMode()
        val nightBudgetMs = if (isTest) 30_000L else 30 * 60 * 1000L
        val remainingNightMs = (nightBudgetMs - currentContinuousMs).coerceAtLeast(0L)

        // 4. 남은 예산이 소진되었는지 확인 (Remaining == 0)
        if (remainingNightMs > 0L) return

        // 5. 현재 사용자가 게임, 롱폼 영상, 숏폼/댓글, 입력 등 몰입 중인지 판정
        val currentFg = JevObservationHub.foregroundPackage
        if (com.example.itv.infrastructure.observation.GeneralAppTracker.isFinancialPackage(currentFg)) return
        val isGamingOrDeferred = currentFg != JevSettings.YOUTUBE &&
            currentFg != JevSettings.INSTAGRAM &&
            com.example.itv.infrastructure.observation.GeneralAppTracker.isInterventionDeferredOrBlocked(currentFg)
        val isWatchingLongform = com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyWatchingLongform()
        val isWatchingShortsOrReels = YoutubeProbe.screenState() == 1 ||
            ReelsProbe.screenState() == 1 ||
            com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments() ||
            com.example.itv.infrastructure.observation.instagram.InstagramTracker.isActivelyInReelsCommentsOrStory()
        val isWriting = WritingGuard.blockReason() != null || observer.snapshot().isActivelyWriting()
        val isLandscape = isLandscapeNow()
        if (isLandscape) {
            landscapeDeferred = true
        }
        val isMidActivity = isGamingOrDeferred || isWatchingLongform || isWatchingShortsOrReels || isWriting || isLandscape

        if (isMidActivity) {
            // [비침해 배너 알림]: 게임·영상 플레이 중이므로 터치를 전혀 방해하지 않는 최상단 얇은 배너 + HUD 수면 모드 아이콘 변경
            if (!nightGentleAlertFired) {
                nightGentleAlertFired = true
                nightBedtimeCarePending = true
                com.example.itv.presentation.sleep.NightGentleAlertController.showGentleBanner(this, isTest)
                com.example.itv.infrastructure.coaching.InterventionJournal.add(
                    this,
                    "night-gentle",
                    "야간 비침해 배너 알림 표시 (터치 무방해 배너 2.8초 + HUD 수면 강아지 아이콘 전환)"
                )
            }
        } else {
            // 활동이 끝났거나(로비/홈 복귀), 대기 중이던 취침 케어가 있다면 안전한 전환 시점에 모달 취침 케어 다이얼로그 제안
            if (nightBedtimeCarePending || !nightGentleAlertFired) {
                if (overlay == null && !responseController.suppressed && !bedtimeCareController.isDialogShowing()) {
                    nightBedtimeCarePending = false
                    nightGentleAlertFired = true
                    showPuppy(transition = true, forceNightBedtimeCare = true)
                }
            }
        }
    }
    // Read-only diagnostics: never advances the clock or changes coaching decisions.
    private fun publishDiagnostics() {
        val now = SystemClock.elapsedRealtime()
        if (jevMode()) {
            val sample = jev.state.value
            val allContinuous = jev.sessionState("ALL")?.continuousMs ?: 0L
            val liveContinuous = if (allContinuous > 0L) allContinuous else usageClock.usedMs
            val displayedPkg = sample.context?.packageName ?: if (YoutubeProbe.screenState() == 1) "YouTube 쇼츠" else if (YoutubeProbe.screenState() == 0) "YouTube" else if (ReelsProbe.screenState() == 1) "Instagram 릴스" else "스마트폰 일반 사용"
            val diagStatus = if (isLandscapeNow() && (landscapeDeferred || jev.hasTransferrableApproval())) {
                "가로 모드 사용 중 · 세로 전환 시 개입 대기"
            } else {
                sample.status
            }
            DeveloperStatus.value = DeveloperSnapshot(now, running, liveContinuous, usageClock.restMs,
                jev.store.settings.value.continuousSeconds * 1000L, TestCoachingPolicy.naturalRestMs(jevMode()),
                0L, displayedPkg, diagStatus)
            return
        }
        val guard = WritingGuard.blockReason()
        val youtube = YoutubeProbe.screenState()
        val reels = ReelsProbe.screenState()
        val ytLongform = com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyWatchingLongform()
        val ytShortsOrComments = youtube == 1 || com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments()
        val genDeferred = com.example.itv.infrastructure.observation.GeneralAppTracker.blockingOrWaitingReason(JevObservationHub.foregroundPackage)
        val screen = when {
            !WritingGuard.connected || now - WritingGuard.checkedAt > 3000L -> "판별 불가 · 접근성 관측 없음/지연"
            ytShortsOrComments -> "확인된 YouTube 쇼츠/댓글"
            ytLongform -> "확인된 YouTube 롱폼 시청 중"
            reels == 1 -> "확인된 Instagram 릴스"
            genDeferred != null -> "개입 대기/보류 앱 ($genDeferred)"
            youtube == -1 || reels == -1 -> "숏폼 여부 판별 불가"
            else -> "확인된 릴스·쇼츠·롱폼 아님"
        }
        val decision = when {
            !running -> "코칭 서비스 중지"
            !unlocked() -> "화면 꺼짐/잠금 · 시계 일시정지"
            responseController.suppressed -> "쉬기/닫기 선택 · 현재 구간 재제안 안 함"
            overlay != null -> "개입 표시 중 · 선택 대기"
            responseController.offered -> com.example.itv.composition.CoachingAccess.status(this, "표시 요청 처리됨")
            guard != null -> guard
            isLandscapeNow() && landscapeDeferred -> "가로 모드 사용 중 · 세로 전환 시 개입 대기"
            safeSince == null || now - (safeSince ?: now) < 5000L -> "안전 상태 5초 확인 중"
            responseController.retryAt != null && now < (responseController.retryAt ?: now) -> "나중에 선택 · 재평가 대기"
            usageClock.usedMs < interventionMs -> "누적 사용시간 조건 미충족"
            ytShortsOrComments -> "다음 쇼츠 전환 대기"
            ytLongform -> "롱폼 영상 종료·전환 대기"
            genDeferred != null -> "개입 가능 전환 대기 ($genDeferred)"
            youtube == -1 -> "YouTube/현재 화면 확인 불가 · 보류"
            reels == 1 -> "다음 릴스 전환 대기"
            reels == -1 -> "현재 화면 판별 불가 · 보류"
            else -> "조건 재확인/표시 처리 중"
        }
        DeveloperStatus.value = DeveloperSnapshot(now, running, usageClock.usedMs,
            usageClock.restMs, interventionMs, TestCoachingPolicy.naturalRestMs(jevMode()),
            ((responseController.retryAt ?: now) - now).coerceAtLeast(0L), screen, decision)
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        instanceRef = java.lang.ref.WeakReference(this)
        com.example.itv.composition.CoachingAccess.channels(this)
        MissionStats.recoverInterrupted(this)
        ReelsProbe.onTransition = { onReelsTransition() }
        YoutubeProbe.coachingObservation = true
        YoutubeProbe.clearBaseline()
        YoutubeProbe.onTransition = { onYoutubeTransition() }
        com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.onLongformTransition = { event ->
            onYoutubeLongformTransition(event)
        }
        com.example.itv.infrastructure.observation.instagram.InstagramTracker.onInstagramTransition = { event ->
            onInstagramModeTransition(event)
        }
        com.example.itv.infrastructure.observation.GeneralAppTracker.onGeneralAppEligibleTransition = { event ->
            onGeneralAppEligibleTransition(event)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true
        wasLandscape = isLandscapeNow()
        com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowLandscape = isLandscapeNow(newConfig)
        val returnedToPortrait = wasLandscape && !nowLandscape
        wasLandscape = nowLandscape
        if (returnedToPortrait && running) {
            handler.removeCallbacks(portraitReturnRunnable)
            handler.postDelayed(portraitReturnRunnable, 350L)
        }
    }

    /**
     * 가로 모드에서 유예되었던 개입(재시도, 취침 케어, Jev/규칙 본 개입, 사전 개입)을
     * 세로 모드로 복귀한 직후 레이아웃이 안정되면 자동으로 실행합니다.
     */
    private fun onOrientationReturnedToPortrait() {
        val hadDeferred = landscapeDeferred
        landscapeDeferred = false
        if (!running || isLandscapeNow() || !unlocked()) return
        if (overlay != null || feedbackView != null || completionView != null ||
            missionController.state.id != null || bedtimeCareController.isDialogShowing()
        ) return
        if (retryPending) {
            checkRetry(transition = true)
            return
        }
        if (nightBedtimeCarePending && !responseController.suppressed) {
            nightBedtimeCarePending = false
            showPuppy(transition = true, forceNightBedtimeCare = true)
            return
        }
        if (responseController.offered || responseController.suppressed) return
        val now = SystemClock.elapsedRealtime()
        if (responseController.retryAt != null && now < (responseController.retryAt ?: 0L)) return

        if (jevMode()) {
            val fg = JevObservationHub.foregroundPackage
            if (jev.hasTransferrableApproval() || jev.canShow(transition = true)) {
                jev.promotePendingTransfer(fg)
                showPuppy(transition = true)
            }
        } else if (hadDeferred || waitingTransition || waitingYoutube) {
            if (observeUsage() && WritingGuard.blockReason() == null) {
                val safeAt = safeSince
                if (safeAt != null && now - safeAt >= 5000L && usageClock.usedMs >= interventionMs) {
                    showPuppy(transition = true)
                }
            }
        }
        if (overlay == null && !bedtimeCareController.isDialogShowing()) {
            maybeShowContextualPreIntervention(now, transition = true)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            com.example.itv.composition.CoachingAccess.setDeveloperAutoEnabled(this, false)
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TRIGGER_TEST) {
            val forceStrong = intent.getBooleanExtra(EXTRA_FORCE_STRONG, false)
            val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 0L)
            val customReasons = intent.getStringArrayListExtra(EXTRA_CUSTOM_REASONS)
            val intensityOverride = intent.getStringExtra(EXTRA_INTENSITY_OVERRIDE)
                ?.let { name -> runCatching { CharacterIntensity.valueOf(name) }.getOrNull() }
            val toneOverride = intent.getStringExtra(EXTRA_TONE_OVERRIDE)
                ?.let { name -> runCatching { com.example.itv.domain.coaching.CoachingTone.valueOf(name) }.getOrNull() }
            if (!foregroundStarted) {
                try {
                    val open = PendingIntent.getActivity(this, 7100, Intent(this, com.example.itv.presentation.permissions.PermissionSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val stop = PendingIntent.getService(this, 7101, Intent(this, CoachingMonitorService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val notification = Notification.Builder(this, com.example.itv.composition.CoachingAccess.MONITOR)
                        .setSmallIcon(R.drawable.ic_coaching_notification)
                        .setContentTitle("강아지 휴식 제안 켜짐")
                        .setContentText("개입 테스트 모드 동작 중")
                        .setContentIntent(open).setOngoing(true)
                        .addAction(Notification.Action.Builder(null, "감시 중지", stop).build()).build()
                    if (Build.VERSION.SDK_INT >= 34) startForeground(7101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    else startForeground(7101, notification)
                    foregroundStarted = true
                } catch (_: Exception) {}
            }
            if (delayMs > 0) {
                handler.postDelayed(
                    { triggerTestIntervention(forceStrong, customReasons, intensityOverride, toneOverride) },
                    delayMs
                )
            } else {
                triggerTestIntervention(forceStrong, customReasons, intensityOverride, toneOverride)
            }
            return START_NOT_STICKY
        }
        if (!CoachingAutoStart.enabled(this) || !com.example.itv.composition.CoachingAccess.ready(this)) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        try {
            val open = PendingIntent.getActivity(this, 7100, Intent(this, com.example.itv.presentation.permissions.PermissionSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 7101, Intent(this, CoachingMonitorService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, com.example.itv.composition.CoachingAccess.MONITOR)
                .setSmallIcon(R.drawable.ic_coaching_notification)
                .setContentTitle("강아지 휴식 제안 켜짐")
                .setContentText(if (jevMode()) "숏폼 관측 · Jev 조건 판단 · 눌러서 관리" else "휴식 이후 누적 사용 ${interventionMs / 1000L}초 감지 · 눌러서 관리")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "감시 중지", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(7101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(7101, notification)
            foregroundStarted = true
        } catch (_: Exception) {
            com.example.itv.composition.CoachingAccess.state(this, "전경 서비스 실행 실패"); stopSelf(); return START_NOT_STICKY
        }
        usageClock.clear(); resetOfferState(); running = true
        if (JevFeature.available()) jev.start()
        observeUsage()
        handler.removeCallbacks(tick); handler.post(tick)
        return START_NOT_STICKY
    }

    fun triggerTestIntervention(
        forceStrong: Boolean = false,
        customReasons: List<String>? = null,
        intensityOverride: CharacterIntensity? = null,
        toneOverride: com.example.itv.domain.coaching.CoachingTone? = null
    ) {
        if (!Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(this, "다른 앱 위에 표시(오버레이) 권한을 먼저 허용해 주세요.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val currentFg = JevObservationHub.foregroundPackage
        if (com.example.itv.infrastructure.observation.GeneralAppTracker.isFinancialPackage(currentFg)) {
            android.widget.Toast.makeText(this, "금융/뱅킹 앱 사용 중에는 개입이 발생하지 않습니다.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        removeOverlay()
        dismissJevFeedback()
        dismissCompletion()
        val tracker = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this)
        val ambient = tracker.snapshot.value
        val reasons = if (!customReasons.isNullOrEmpty()) {
            customReasons
        } else {
            com.example.itv.domain.coaching.InterventionReasonResolver.resolveReasons(
                isJev = false,
                jevContext = null,
                continuousUsedMs = 1_800_000L,
                ambient = ambient,
                shortformAppName = null
            )
        }
        val effectiveIntensity = intensityOverride
            ?: if (forceStrong) CharacterIntensity.STRONG else getEffectiveIntensity()
        lastOfferedIntensity = effectiveIntensity
        val isStrong = effectiveIntensity == CharacterIntensity.STRONG
        val missionContext = buildMissionSelectionContext(ambient, ambient.posture)
        val rec = com.example.itv.domain.coaching.RestMissionCatalog.pick(ambient.posture, isStrong, missionContext)
        currentRecommendation = rec
        val (root, params) = overlayFactory.offer(
            onChoice = ::onChoice,
            reasons = reasons,
            intensityOverride = effectiveIntensity,
            toneOverride = toneOverride,
            recommendation = rec
        )
        try {
            wm.addView(root, params); overlay = root; offerFromJev = false
            responseController.offered = true; waitingTransition = false; waitingYoutube = false
            responseController.offerId = "test-" + java.util.UUID.randomUUID().toString().take(4)
            overlayFactory.attachControls(
                root = root,
                onReady = { resetOfferTimeout("테스트 애니메이션 완료") },
                onInteraction = { isInteracting -> onPuppyInteractionStateChanged(isInteracting) }
            )
            handler.removeCallbacks(offerTimeout); handler.postDelayed(offerTimeout, 30_000L)
            if (isStrong || rec.isFacedownRequired) {
                val tracker = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this)
                tracker.onFacedownDetectedWhileScreenOn = {
                    handler.post {
                        if (overlay === root) {
                            val offerId = responseController.offerId ?: java.util.UUID.randomUUID().toString().take(8)
                            startRestMission(offerId)
                            removeOverlay()
                            missionController.refreshStartDeadline(SystemClock.elapsedRealtime(), missionFromJev)
                        }
                        val locked = com.example.itv.infrastructure.observation.accessibility.WritingGuardService.lockScreen()
                        if (!locked) {
                            android.widget.Toast.makeText(applicationContext, "엎어둠 감지! 전원 버튼을 눌러 화면을 꺼주세요.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                tracker.startFacedownMonitoring()
            }
        } catch (_: Exception) {
            removeOverlay()
        }
    }

    private fun onYoutubeTransition() {
        onUserActivity("YouTube 쇼츠 전환")
        if (retryPending) { checkRetry(true); return }
        if (!running || responseController.offered || responseController.suppressed || bedtimeCareController.isDialogShowing()) return
        val now = SystemClock.elapsedRealtime()
        if (responseController.retryAt != null && now < (responseController.retryAt ?: 0L)) return
        if (nightBedtimeCarePending) {
            nightBedtimeCarePending = false
            showPuppy(transition = true, forceNightBedtimeCare = true)
            return
        }
        if (jevMode()) {
            val hasApproval = jev.hasTransferrableApproval()
            if (running && hasApproval) {
                jev.promotePendingTransfer(JevSettings.YOUTUBE)
                showPuppy(transition = true)
            }
            if (overlay == null && !bedtimeCareController.isDialogShowing()) {
                maybeShowContextualPreIntervention(now, transition = true)
            }
            return
        }
        if (!waitingYoutube) {
            maybeShowContextualPreIntervention(now, transition = true)
            return
        }
        if (!observeUsage() || !waitingYoutube) return
        if (WritingGuard.blockReason() != null) { safeSince = null; return }
        val safeAt = safeSince ?: return
        if (now - safeAt < 5000L || usageClock.usedMs < interventionMs) return
        if (YoutubeProbe.screenState() != 1 || !CoachingAutoStart.enabled(this)) return
        showPuppy(transition = true)
    }
    private fun onYoutubeLongformTransition(event: com.example.itv.infrastructure.observation.youtube.LongformTransitionEvent) {
        onUserActivity("YouTube 롱폼 전환 (${event.eventType})")
        if (retryPending) { checkRetry(true); return }
        if (!running || responseController.offered || responseController.suppressed || bedtimeCareController.isDialogShowing()) return
        val now = SystemClock.elapsedRealtime()
        if (responseController.retryAt != null && now < (responseController.retryAt ?: 0L)) return
        if (nightBedtimeCarePending) {
            nightBedtimeCarePending = false
            showPuppy(transition = true, forceNightBedtimeCare = true)
            return
        }
        if (jevMode()) {
            val hasApproval = jev.hasTransferrableApproval()
            if (running && hasApproval) {
                jev.promotePendingTransfer(JevSettings.YOUTUBE)
                showPuppy(transition = true)
            }
            if (overlay == null && !bedtimeCareController.isDialogShowing()) {
                maybeShowContextualPreIntervention(now, transition = true)
            }
            return
        }
        if (!waitingTransition && !waitingYoutube) {
            maybeShowContextualPreIntervention(now, transition = true)
            return
        }
        if (!observeUsage() || (!waitingTransition && !waitingYoutube)) return
        val safeAt = safeSince ?: return
        if (WritingGuard.blockReason() != null) { safeSince = null; return }
        if (now - safeAt < 5000L || usageClock.usedMs < interventionMs) return
        if (!CoachingAutoStart.enabled(this)) return
        showPuppy(transition = true)
    }
    private fun onReelsTransition() {
        onUserActivity("Instagram 릴스 전환")
        if (retryPending) { checkRetry(true); return }
        if (!running || responseController.offered || responseController.suppressed || bedtimeCareController.isDialogShowing()) return
        val now = SystemClock.elapsedRealtime()
        if (responseController.retryAt != null && now < (responseController.retryAt ?: 0L)) return
        if (nightBedtimeCarePending) {
            nightBedtimeCarePending = false
            showPuppy(transition = true, forceNightBedtimeCare = true)
            return
        }
        if (jevMode()) {
            val hasApproval = jev.hasTransferrableApproval()
            if (running && hasApproval) {
                jev.promotePendingTransfer(JevSettings.INSTAGRAM)
                showPuppy(transition = true)
            }
            if (overlay == null && !bedtimeCareController.isDialogShowing()) {
                maybeShowContextualPreIntervention(now, transition = true)
            }
            return
        }
        // Only a transition AFTER arming can trigger this path.
        if (!waitingTransition) {
            maybeShowContextualPreIntervention(now, transition = true)
            return
        }
        if (!observeUsage() || !waitingTransition) return
        val safeAt = safeSince ?: return
        if (WritingGuard.blockReason() != null) { safeSince = null; return }
        if (now - safeAt < 5000L || usageClock.usedMs < interventionMs) return
        if (ReelsProbe.screenState() != 1 || !CoachingAutoStart.enabled(this)) return
        showPuppy(transition = true)
    }
    private fun onInstagramModeTransition(event: com.example.itv.infrastructure.observation.instagram.InstagramTransitionEvent) {
        onUserActivity("Instagram 화면 전환 (${event.eventType})")
        if (retryPending) { checkRetry(true); return }
        if (!running || responseController.offered || responseController.suppressed || bedtimeCareController.isDialogShowing()) return
        val now = SystemClock.elapsedRealtime()
        if (responseController.retryAt != null && now < (responseController.retryAt ?: 0L)) return
        if (nightBedtimeCarePending) {
            nightBedtimeCarePending = false
            showPuppy(transition = true, forceNightBedtimeCare = true)
            return
        }
        if (jevMode()) {
            val hasApproval = jev.hasTransferrableApproval()
            if (running && hasApproval) {
                jev.promotePendingTransfer(JevSettings.INSTAGRAM)
                showPuppy(transition = true)
            }
            if (overlay == null && !bedtimeCareController.isDialogShowing()) {
                maybeShowContextualPreIntervention(now, transition = true)
            }
            return
        }
        if (!waitingTransition) {
            maybeShowContextualPreIntervention(now, transition = true)
            return
        }
        if (!observeUsage() || !waitingTransition) return
        val safeAt = safeSince ?: return
        if (WritingGuard.blockReason() != null) { safeSince = null; return }
        if (now - safeAt < 5000L || usageClock.usedMs < interventionMs) return
        if (!CoachingAutoStart.enabled(this)) return
        showPuppy(transition = true)
    }
    private fun onGeneralAppEligibleTransition(event: com.example.itv.infrastructure.observation.GeneralAppTransitionEvent) {
        onUserActivity("일반/게임 앱 개입 가능 전환 (${event.appName})")
        if (retryPending) { checkRetry(true); return }
        if (!running || responseController.offered || responseController.suppressed || bedtimeCareController.isDialogShowing()) return
        val now = SystemClock.elapsedRealtime()
        if (responseController.retryAt != null && now < (responseController.retryAt ?: 0L)) return
        if (nightBedtimeCarePending) {
            nightBedtimeCarePending = false
            showPuppy(transition = true, forceNightBedtimeCare = true)
            return
        }
        if (jevMode()) {
            val hasApproval = jev.hasTransferrableApproval()
            if (running && hasApproval) {
                jev.promotePendingTransfer(event.packageName)
                showPuppy(transition = true)
            }
            if (overlay == null && !bedtimeCareController.isDialogShowing()) {
                maybeShowContextualPreIntervention(now, transition = true)
            }
            return
        }
        if (!waitingTransition) {
            maybeShowContextualPreIntervention(now, transition = true)
            return
        }
        if (!observeUsage() || !waitingTransition) return
        val safeAt = safeSince ?: return
        if (WritingGuard.blockReason() != null) { safeSince = null; return }
        if (now - safeAt < 5000L || usageClock.usedMs < interventionMs) return
        if (!CoachingAutoStart.enabled(this)) return
        showPuppy(transition = true)
    }
    private fun showPuppy(transition: Boolean = true, forceNightBedtimeCare: Boolean = false) {
        // 0. 취침 케어 또는 이전 개입에서 닫기/완료(suppressed), '나중에' 유예시간(retryAt) 대기, 또는 취침 케어가 표시 중이면 무조건 차단 (Jev/일반 모드 공통)
        if (responseController.suppressed) return
        val now = SystemClock.elapsedRealtime()
        if (responseController.retryAt != null && now < (responseController.retryAt ?: 0L)) return
        if (bedtimeCareController.isDialogShowing()) return

        val isJev = jevMode()
        // 1. 메시지·문서·키보드 입력·인스타 DM·금융/뱅킹 앱 중 개입 절대 금지
        if (WritingGuard.blockReason() != null || observer.snapshot().isActivelyWriting()) return
        val currentFg = JevObservationHub.foregroundPackage
        if (JevSettings.isDocumentOrMessagingPackage(currentFg) ||
            com.example.itv.infrastructure.observation.GeneralAppTracker.isFinancialPackage(currentFg)
        ) return

        // 2. 유튜브 숏폼 댓글 또는 인스타그램 댓글·스토리·DM 열람 중에는 절대 개입하지 않음
        val ytMode = com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.snapshot.value.mode
        if (currentFg == JevSettings.YOUTUBE && ytMode == com.example.itv.infrastructure.observation.youtube.YoutubeWatchMode.SHORTS_COMMENTS) {
            return
        }
        val igMode = com.example.itv.infrastructure.observation.instagram.InstagramTracker.snapshot.value.mode
        if (currentFg == JevSettings.INSTAGRAM && (
                igMode == com.example.itv.infrastructure.observation.instagram.InstagramScreenMode.COMMENTS ||
                    igMode == com.example.itv.infrastructure.observation.instagram.InstagramScreenMode.STORY ||
                    igMode == com.example.itv.infrastructure.observation.instagram.InstagramScreenMode.DM
                )
        ) {
            return
        }

        // 2-1. 유튜브·인스타그램 외 모든 앱(게임, 외부 OTT 영상, 음성통화 등)에서도 HUD 판정이 개입 가능(🟢 ELIGIBLE)이 아니면 절대 개입하지 않고 대기
        if (currentFg != JevSettings.YOUTUBE &&
            currentFg != JevSettings.INSTAGRAM &&
            com.example.itv.infrastructure.observation.GeneralAppTracker.isInterventionDeferredOrBlocked(currentFg)
        ) {
            return
        }

        // 3. 숏폼(쇼츠·릴스) 및 롱폼 영상 시청 중에는 비전환(transition == false) 타이머 틱에서 절대 중간 개입 금지
        if (!transition) {
            val isWatchingShortform = YoutubeProbe.screenState() == 1 ||
                ReelsProbe.screenState() == 1 ||
                com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments() ||
                com.example.itv.infrastructure.observation.instagram.InstagramTracker.isActivelyInReelsCommentsOrStory()
            val isWatchingLongform = com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyWatchingLongform()
            if (isWatchingShortform || isWatchingLongform) return
        }

        val ambient = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).snapshot.value
        val coachingSettings = com.example.itv.infrastructure.coaching.UserCoachingSettingsStore.getInstance(this).loadSettings()
        val isTest = testMode()
        val nightBudgetMs = if (isTest) 30_000L else 30 * 60 * 1000L
        val currentContinuousMs = if (isJev) {
            jev.sessionState("ALL")?.continuousMs ?: jev.state.value.context?.continuousMs ?: usageClock.usedMs
        } else {
            usageClock.usedMs
        }
        val isPreBedtime = ambient.bedtimeDistanceMinutes != null && ambient.bedtimeDistanceMinutes < 0
        val isLongUsageBeforeBed = currentContinuousMs >= nightBudgetMs

        // 취침 개입(3분기 케어) 조건:
        // 1) ambient.isBedtimeCandidate && coachingSettings.bedtimeCareEnabled
        // 2) 평소 취침 시각 전(1시간 이내, isPreBedtime)이라면 어두운 방에서 30분(테스트 30초) 이상 연속 사용했을 때만 취침 케어로 전환
        //    (취침 전 연속 사용 시간이 부족하다면 일반 강아지 코칭으로 진행)
        val shouldTriggerBedtimeCare = ambient.isBedtimeCandidate && coachingSettings.bedtimeCareEnabled &&
            (!isPreBedtime || isLongUsageBeforeBed)
        val bypassJevGateForNightBudget = forceNightBedtimeCare && shouldTriggerBedtimeCare

        var jevApprovedForShow = false
        if (isJev) {
            jev.promotePendingTransfer(currentFg)
            jevApprovedForShow = jev.canShow(transition)
            if (!jevApprovedForShow && !bypassJevGateForNightBudget) return
        }
        else if (!bypassJevGateForNightBudget) {
            if (usageClock.usedMs < interventionMs) return
            val safeAt = safeSince ?: return
            if (SystemClock.elapsedRealtime() - safeAt < 5000L) return
        }
        if (feedbackView != null || completionView != null || missionController.state.id != null || overlay != null || !unlocked() || !com.example.itv.composition.CoachingAccess.ready(this) || !Settings.canDrawOverlays(this)) return

        // 가로 모드(전체화면 영상·게임 등)에서는 자동 개입을 보류하고 세로 모드 전환 시 재평가·표시합니다.
        if (isLandscapeNow()) {
            landscapeDeferred = true
            if (shouldTriggerBedtimeCare || forceNightBedtimeCare) {
                nightBedtimeCarePending = true
            }
            if (!isJev) {
                waitingTransition = true
            }
            return
        }
        landscapeDeferred = false

        val jevOfferId = if (isJev && jevApprovedForShow) jev.approvedId() else null
        val responseMs = TestCoachingPolicy.responseMs(isJev)
        val shortformApp = when {
            YoutubeProbe.screenState() == 1 ||
                (currentFg == JevSettings.YOUTUBE && com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments()) -> "YouTube 쇼츠"
            ReelsProbe.screenState() == 1 ||
                (currentFg == JevSettings.INSTAGRAM && igMode == com.example.itv.infrastructure.observation.instagram.InstagramScreenMode.REELS) -> "Instagram 릴스"
            else -> null
        }

        if (shouldTriggerBedtimeCare) {
            val isOccluded = ambient.isOccluded || (ambient.isNear && (ambient.proximityDistanceCm ?: 5f) <= 1.0f)
            val isPastBedtime = !isPreBedtime || ambient.bedtimePhase == com.example.itv.infrastructure.observation.BedtimePhase.PAST_BEDTIME
            if (com.example.itv.domain.coaching.ContextualPreInterventionPolicy.shouldPrecedeBedtimeCare(
                    isPastBedtime = isPastBedtime,
                    ambientLux = ambient.lux,
                    isOccluded = isOccluded,
                    alreadyTriggeredInSession = preInterventionTriggeredInSession
                )
            ) {
                // 취침 시간 경과 시 전면 취침 케어 전에 약한 개입(GENTLE)을 사전 개입으로 먼저 보여줍니다.
                val passedMin = ambient.bedtimeDistanceMinutes?.takeIf { it > 0 }
                    ?: jev.state.value.context?.bedtimePassedMinutes?.takeIf { it > 0 }
                val isDark = com.example.itv.domain.coaching.ContextualPreInterventionPolicy.isDarkRoom(ambient.lux, isOccluded)
                showGentlePreIntervention(
                    decision = com.example.itv.domain.coaching.ContextualPreInterventionPolicy.bedtimeCarePrecedingDecision(
                        bedtimePassedMinutes = passedMin,
                        isDarkRoom = isDark
                    ),
                    transition = transition
                )
                return
            }
            val graceMs = com.example.itv.domain.coaching.ContextualPreInterventionPolicy.graceWindowMs(isTest)
            if (preInterventionTriggeredInSession &&
                preInterventionShownAtElapsed > 0L &&
                (now - preInterventionShownAtElapsed) < graceMs
            ) {
                // 사전 개입 직후 짧은 유예 시간 동안에는 사용자가 스스로 멈출 수 있도록 전면 취침 케어를 잠시 대기합니다.
                return
            }
            removeOverlay()
            responseController.offered = true
            val offerId = jevOfferId ?: java.util.UUID.randomUUID().toString().take(8)
            responseController.offerId = offerId
            if (isJev && jevApprovedForShow) jev.shown()
            InterventionJournal.add(this, offerId, "취침 케어 개입 표시 (3분기)")
            val ctx = if (isJev) jev.state.value.context else null
            val continuousMin = (ctx?.continuousMs ?: usageClock.usedMs) / 60000.0
            val bedtimePassedMin = ctx?.bedtimePassedMinutes ?: ambient.bedtimeDistanceMinutes?.takeIf { it > 0 }
            val jevProb = if (isJev) jev.state.value.probability else null
            val baseThreshold = jev.store.settings.value.probabilityThreshold
            val effThreshold = if (ctx != null) com.example.itv.domain.jev.JevCandidatePolicy.effectiveThreshold(jev.store.settings.value, ctx) else baseThreshold
            val probDiff = if (jevProb != null) (jevProb - effThreshold).coerceAtLeast(0.0) else null

            nightBedtimeCarePending = false
            if (com.example.itv.presentation.coaching.LongformDiagnosticHud.isShowing) {
                com.example.itv.presentation.coaching.LongformDiagnosticHud.setSleepingState(false)
            }

            bedtimeCareController.showBedtimeCareDialog(
                containerHost = null,
                bedtimePassedMinutes = bedtimePassedMin,
                continuousMinutes = continuousMin,
                jevProbabilityDiff = probDiff
            ) {
                responseController.offered = false
                responseController.suppressed = true
                responseController.retryAt = null
                nightBedtimeCarePending = false
                if (com.example.itv.presentation.coaching.LongformDiagnosticHud.isShowing) {
                    com.example.itv.presentation.coaching.LongformDiagnosticHud.setSleepingState(false)
                }
                if (isJev) {
                    if (jevApprovedForShow) jev.respond("DISMISS")
                    jev.suppressCurrentSession("BEDTIME_CARE")
                }
                com.example.itv.composition.CoachingAccess.state(this, "취침 케어 닫기/완료 · 충분한 휴식 전까지 재제안 안 함")
            }
            return
        }
        val reasons = com.example.itv.domain.coaching.InterventionReasonResolver.resolveReasons(
            isJev = isJev,
            jevContext = if (isJev) jev.state.value.context else null,
            continuousUsedMs = usageClock.usedMs,
            ambient = ambient,
            shortformAppName = shortformApp,
            categoryDurationsMs = com.example.itv.infrastructure.observation.SessionCategoryTracker.getCategoryDurationsSnapshot()
        )
        val effectiveIntensity = getEffectiveIntensity()
        lastOfferedIntensity = effectiveIntensity
        val isStrong = effectiveIntensity == CharacterIntensity.STRONG
        val missionContext = buildMissionSelectionContext(ambient, ambient.posture, shortformApp)
        val rec = com.example.itv.domain.coaching.RestMissionCatalog.pick(ambient.posture, isStrong, missionContext)
        currentRecommendation = rec
        val (root, params) = overlayFactory.offer(::onChoice, reasons, intensityOverride = effectiveIntensity, recommendation = rec)
        try {
            wm.addView(root, params); overlay = root; offerFromJev = isJev
            displayedPackage = JevObservationHub.foregroundPackage.takeIf { SystemClock.elapsedRealtime() - JevObservationHub.checkedAt in 0..4000 }
            responseController.offered = true; waitingTransition = false; waitingYoutube = false; responseController.retryAt = null
            responseController.offerId = jevOfferId ?: java.util.UUID.randomUUID().toString().take(8)
            journal.engine=if(isJev)"JEV" else "RULE"
            journal.policyVersion=if(isJev)"jev-candidate-v1" else "test-coaching-v2"
            journal.onStructured=if(isJev && jevOfferId!=null) { _,kind ->
                if(kind in setOf("MISSION_REQUESTED","START_NOT_OBSERVED","REST_STARTED","REST_COMPLETED","INTERRUPTED"))
                    jev.recordMission(jevOfferId,kind)
            } else null
            journal.structured(responseController.offerId!!,"OFFER_SHOWN")
            if (isJev) jev.shown()
            responseController.deadline = SystemClock.elapsedRealtime() + 7400L + responseMs
            InterventionJournal.add(this, responseController.offerId!!, "개입 표시 성공 · " + when {
                YoutubeProbe.screenState() == 1 -> "쇼츠"
                ReelsProbe.screenState() == 1 -> "릴스"
                currentFg == JevSettings.YOUTUBE -> "YouTube 롱폼/전환"
                else -> "일반 화면"
            })
            handler.removeCallbacks(offerTimeout); handler.postDelayed(offerTimeout, 7400L + responseMs)
            overlayFactory.attachControls(
                root = root,
                onReady = {
                    if (overlay === root && responseController.offerId != null) {
                        resetOfferTimeout("등장 애니메이션 완료")
                    }
                },
                onInteraction = { isInteracting ->
                    onPuppyInteractionStateChanged(isInteracting)
                }
            )
            if (isStrong || rec.isFacedownRequired) {
                val tracker = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this)
                tracker.onFacedownDetectedWhileScreenOn = {
                    handler.post {
                        if (overlay === root) {
                            val offerId = responseController.offerId ?: java.util.UUID.randomUUID().toString().take(8)
                            startRestMission(offerId)
                            removeOverlay()
                            missionController.refreshStartDeadline(SystemClock.elapsedRealtime(), missionFromJev)
                        }
                        val locked = com.example.itv.infrastructure.observation.accessibility.WritingGuardService.lockScreen()
                        if (!locked) {
                            android.widget.Toast.makeText(applicationContext, "엎어둠 감지! 전원 버튼을 눌러 화면을 꺼주세요.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                tracker.startFacedownMonitoring()
            }
            com.example.itv.composition.CoachingAccess.state(this, "강아지 제안 표시됨 · 선택 대기")
            val open = PendingIntent.getActivity(this, 7102, Intent(this, com.example.itv.presentation.permissions.PermissionSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            try { nm.notify(7102, Notification.Builder(this, com.example.itv.composition.CoachingAccess.ALERT)
                .setSmallIcon(R.drawable.ic_coaching_notification)
                .setContentTitle("강아지 친구가 쉬어가자고 해요")
                .setContentText("화면을 계속 사용하고 있어요. 잠깐 쉬어갈까요?")
                .setContentIntent(open).setAutoCancel(true).build())
            } catch (_: Exception) {
                InterventionJournal.add(this, responseController.offerId ?: "-", "제안 알림 전송 실패 · 오버레이는 표시됨")
            }
        } catch (_: Exception) {
            if (isJev) jev.displayFailed()
            InterventionJournal.add(this, responseController.offerId ?: "-", "개입 표시 실패")
            removeOverlay(); responseController.offered = true
            com.example.itv.composition.CoachingAccess.state(this, "표시 실패 · 앱 보안 설정/오버레이 권한 확인 후 감시를 다시 시작하세요")
        }
    }
    private fun canShowJevFeedback(): Boolean {
        if (!JevFeature.available() || !jev.store.settings.value.collectionConsent ||
            overlay != null || completionView != null || missionController.state.id != null || reactionInProgress || retryPending ||
            !unlocked() || !Settings.canDrawOverlays(this)) return false
        val reason = WritingGuard.blockReason()
        return reason == null || (WritingGuard.connected &&
            SystemClock.elapsedRealtime() - WritingGuard.checkedAt <= 3000L &&
            reason == "ITV 내부 화면 · 구조 탐색 생략 / 개입 보류")
    }
    private fun dismissJevFeedback() {
        feedbackView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        feedbackView = null
    }
    private fun maybeShowJevFeedback() {
        if (feedbackView != null || feedbackQueryPending || feedbackSaving || !canShowJevFeedback()) return
        val now = SystemClock.elapsedRealtime()
        if (lastFeedbackQueryElapsed > 0L && now - lastFeedbackQueryElapsed < 15_000L) return
        lastFeedbackQueryElapsed = now
        feedbackQueryPending = true
        jevUiScope.launch {
            try {
                val trial = withContext(Dispatchers.IO) { jev.store.dao.nextFeedback() } ?: return@launch
                if (!canShowJevFeedback() || feedbackView != null) return@launch
                val (view, params) = overlayFactory.feedback { score ->
                    dismissJevFeedback()
                    feedbackSaving = true
                    jevUiScope.launch {
                        try { jev.repository.feedback(trial.id, score) }
                        catch (_: Exception) { jev.store.notice.value = "평가 저장 실패 · Jev 기록 화면에서 다시 제출해 주세요." }
                        finally { feedbackSaving = false }
                    }
                }
                try {
                    wm.addView(view, params); feedbackView = view
                    withContext(Dispatchers.IO) {
                        jev.store.db.withTransaction {
                            val latest = jev.store.dao.trial(trial.id)
                            latest?.let { jev.store.dao.saveTrial(it.copy(feedbackPrompted = true)) }
                        }
                    }
                } catch (_: Exception) { dismissJevFeedback() }
            } finally { feedbackQueryPending = false }
        }
    }
    private fun removeOverlay(preserveForAppSwitch: Boolean = false) {
        val wasPreIntervention = offerIsPreIntervention
        if (overlay != null && JevFeature.available() && offerFromJev && !wasPreIntervention) {
            if (!preserveForAppSwitch) {
                jev.cancelled(closingCause)
            }
        }
        if (responseController.offerId != null) {
            if (!preserveForAppSwitch && !wasPreIntervention) {
                finishOffer(closingCause)
            } else {
                handler.removeCallbacks(offerTimeout)
                responseController.offerId = null
                responseController.deadline = 0L
                responseController.offered = false
            }
        }
        // Clear the reference before detach callbacks to avoid reentrant removal.
        val previousOverlay = overlay
        overlay = null;displayedPackage=null;retryVisible=false;reactionInProgress=false;isRestMissionFailure=false;offerIsPreIntervention=false;offerFromJev=false
        lastFeedbackQueryElapsed = 0L
        previousOverlay?.let { overlayFactory.removeControls(it); try { wm.removeView(it) } catch (_: Exception) {} }
        if (missionController.state.id == null) {
            com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).stopFacedownMonitoring()
        }
    }
    private var puppyInteracting = false
    private fun onPuppyInteractionStateChanged(isInteracting: Boolean) {
        puppyInteracting = isInteracting
        if (isInteracting) {
            handler.removeCallbacks(offerTimeout)
        } else {
            if (overlay != null && !reactionInProgress && !offerIsPreIntervention && responseController.offerId != null) {
                resetOfferTimeout("강아지 조작 종료")
            }
        }
    }
    fun onUserActivity(reason: String) {
        if (overlay == null || reactionInProgress || offerIsPreIntervention || responseController.offerId == null) return
        if (puppyInteracting) return
        resetOfferTimeout(reason)
    }
    private fun resetOfferTimeout(reason: String = "") {
        if (overlay == null || reactionInProgress || offerIsPreIntervention || responseController.offerId == null) return
        val isJev = offerFromJev && JevFeature.available() && jev.hasActiveOffer()
        val responseMs = TestCoachingPolicy.responseMs(isJev)
        responseController.deadline = SystemClock.elapsedRealtime() + responseMs
        handler.removeCallbacks(offerTimeout)
        handler.postDelayed(offerTimeout, responseMs)
        if (reason.isNotEmpty() && responseController.offerId != null) {
            InterventionJournal.add(this, responseController.offerId ?: "-", "무응답 타이머 연장 · $reason")
        }
    }
    override fun onDestroy() {
        if (instanceRef?.get() === this) instanceRef = null
        closingCause = "표시 중단 · 서비스 종료"
        if (JevFeature.available()) jev.stop()
        dismissJevFeedback(); jevUiScope.cancel()
        missionController.stop()
        dismissCompletion()
        ReelsProbe.onTransition = null
        YoutubeProbe.onTransition = null
        com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.onLongformTransition = null
        com.example.itv.infrastructure.observation.instagram.InstagramTracker.onInstagramTransition = null
        com.example.itv.infrastructure.observation.GeneralAppTracker.onGeneralAppEligibleTransition = null
        YoutubeProbe.coachingObservation = false
        running = false; foregroundStarted = false; handler.removeCallbacksAndMessages(null); removeOverlay(); nm.cancel(7102)
        if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false }
        com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).stop()
        val status = com.example.itv.composition.CoachingAccess.status(this)
        if (!status.contains("실패") && !status.contains("해제")) com.example.itv.composition.CoachingAccess.state(this, "중지됨")
        publishDiagnostics()
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
