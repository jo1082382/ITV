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

        @Volatile
        private var instanceRef: java.lang.ref.WeakReference<CoachingMonitorService>? = null

        fun notifyUserActivity(reason: String) {
            instanceRef?.get()?.let { service ->
                service.handler.post { service.onUserActivity(reason) }
            }
        }

        fun triggerTest(context: android.content.Context, forceStrong: Boolean = false, delayMs: Long = 0L) {
            val intent = Intent(context, CoachingMonitorService::class.java).apply {
                action = ACTION_TRIGGER_TEST
                putExtra(EXTRA_FORCE_STRONG, forceStrong)
                putExtra(EXTRA_DELAY_MS, delayMs)
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

    private fun getEffectiveIntensity(): CharacterIntensity {
        val base = UserCoachingSettingsStore.getInstance(this).loadSettings().characterIntensity
        return escalationTracker.resolveEffectiveIntensity(base)
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
        val rec = currentRecommendation ?: com.example.itv.domain.coaching.RestMissionCatalog.pick(posture, isStrong).also {
            currentRecommendation = it
        }
        if (rec.isFacedownRequired) {
            tracker.startFacedownMonitoring()
        }
        missionController.begin(
            id = id,
            now = SystemClock.elapsedRealtime(),
            isJev = missionFromJev,
            requireFacedown = rec.isFacedownRequired,
            isStrong = isStrong
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
        val restSec=TestCoachingPolicy.restMs(useJev)/1000L
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
            if (!missionController.state.started) failMissionStart()
        }
    }
    private fun failMissionStart() {
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
        val effectiveIntensity = if (isStrong) CharacterIntensity.STRONG else getEffectiveIntensity()
        activeMissionIntensity = effectiveIntensity
        lastOfferedIntensity = effectiveIntensity
        val rec = currentRecommendation ?: com.example.itv.domain.coaching.RestMissionCatalog.pick(
            com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).currentPosture(),
            isStrong
        ).also { currentRecommendation = it }
        val (root, params) = overlayFactory.retryOffer(::onChoice, intensityOverride = effectiveIntensity, recommendation = rec)
        try {
            wm.addView(root, params); overlay = root; retryVisible = true
            displayedPackage = if (isRestMissionFailure) null else JevObservationHub.foregroundPackage
            overlayFactory.attachControls(root); retryPending = false
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
        dismissCompletion()
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
            retryPending = true
            isRestMissionFailure = true
            handler.postDelayed({ checkRetry(transition = true) }, 300L)
        } else if (wake == com.example.itv.domain.coaching.TestMissionWake.SUCCESS) {
            onMissionRestSuccess()
        }
    }
    private fun onMissionRestSuccess() {
        retryPending = false
        isRestMissionFailure = false
        usageClock.clear()
        resetOfferState()
        activeMissionIntensity = null
        lastOfferedIntensity = null
        currentRecommendation = null
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
            handler.postDelayed({ if (completionView === box) dismissCompletion() }, 10_000L)
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
    private var safeSince: Long?
        get() = progress.safeSince
        set(value) { progress.safeSince = value }
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val pm by lazy { getSystemService(PowerManager::class.java) }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private fun unlocked() = observer.unlocked()
    private fun resetOfferState() { progress.reset() }
    private fun observeUsage(): Boolean {
        val isActiveVideo = YoutubeProbe.screenState() == 1 ||
            ReelsProbe.screenState() == 1 ||
            com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments() ||
            com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyWatchingLongform() ||
            com.example.itv.infrastructure.observation.instagram.InstagramTracker.isActivelyInReelsCommentsOrStory() ||
            com.example.itv.infrastructure.observation.GeneralAppTracker.isInterventionDeferredOrBlocked(JevObservationHub.foregroundPackage)
        return progress.observe(SystemClock.elapsedRealtime(), unlocked(), overlay != null, isActiveVideo)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // SCREEN_OFF is definitive even if PowerManager has not updated yet.
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                missionScreenOff()
                if (overlayFactory.isStrong(overlay)) {
                    removeOverlay()
                }
                progress.observe(SystemClock.elapsedRealtime(), using = false, offerVisible = overlay != null, isShortform = false)
            } else {
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    missionScreenOn()
                    bedtimeCareController.showMorningBriefingIfNeeded(JevObservationHub.foregroundPackage)
                }
                observeUsage()
                checkMission()
            }
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
            checkMission()
            val isStrong = overlayFactory.isStrong(overlay)
            val usageObserved = observeUsage()
            val using = if (currentJevMode) unlocked() else (if (isStrong) unlocked() else usageObserved)
            val observation = observer.snapshot()
            val nowElapsed = SystemClock.elapsedRealtime()
            val fgPkg = JevObservationHub.foregroundPackage.takeIf { nowElapsed - JevObservationHub.checkedAt in 0..1500 }
            val leftDisplayedApp = !isRestMissionFailure && displayedPackage != null && fgPkg != null && fgPkg != displayedPackage && !JevObservationHub.isTransientSystemPackage(fgPkg)
            val isWriting = observation.isActivelyWriting()
            if (overlay != null) {
                if (isStrong) {
                    if (!unlocked()) removeOverlay()
                } else {
                    if (!using || isWriting || leftDisplayedApp) {
                        val isAppSwitch = leftDisplayedApp
                        if (currentJevMode && isAppSwitch) {
                            jev.transferOnAppSwitch()
                        }
                        removeOverlay(preserveForAppSwitch = currentJevMode && isAppSwitch)
                    }
                }
            }
            if (jevMode()) {
                jev.tick(using, missionController.state.id != null || completionView != null || reactionInProgress || retryPending || retryVisible, overlay != null)
                val current = if (retryVisible) (if (isRestMissionFailure) true else canContinueJevRetry()) else jev.canKeepDisplayed()
                if (overlay != null) {
                    if (isStrong) {
                        if (!unlocked()) removeOverlay()
                    } else {
                        if (!using || isWriting || !current) {
                            val isAppSwitch = !current && leftDisplayedApp
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
            checkRetry()
            if (feedbackView != null && (!using || !canShowJevFeedback())) dismissJevFeedback()
            maybeShowJevFeedback()
            publishDiagnostics()
            if (running) handler.postDelayed(this, 1000)
        }
    }
    // Read-only diagnostics: never advances the clock or changes coaching decisions.
    private fun publishDiagnostics() {
        val now = SystemClock.elapsedRealtime()
        if (jevMode()) {
            val sample = jev.state.value
            val liveContinuous = usageClock.usedMs
            val displayedPkg = sample.context?.packageName ?: if (YoutubeProbe.screenState() == 1) "YouTube 쇼츠" else if (YoutubeProbe.screenState() == 0) "YouTube" else if (ReelsProbe.screenState() == 1) "Instagram 릴스" else "스마트폰 일반 사용"
            DeveloperStatus.value = DeveloperSnapshot(now, running, liveContinuous, usageClock.restMs,
                jev.store.settings.value.continuousSeconds * 1000L, TestCoachingPolicy.naturalRestMs(jevMode()),
                0L, displayedPkg, sample.status)
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
        com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).start()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            com.example.itv.composition.CoachingAccess.setDeveloperAutoEnabled(this, false)
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TRIGGER_TEST) {
            val forceStrong = intent.getBooleanExtra(EXTRA_FORCE_STRONG, false)
            val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 0L)
            if (!running) {
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
                    running = true
                } catch (_: Exception) {}
            }
            if (delayMs > 0) {
                handler.postDelayed({ triggerTestIntervention(forceStrong) }, delayMs)
            } else {
                triggerTestIntervention(forceStrong)
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
        } catch (_: Exception) {
            com.example.itv.composition.CoachingAccess.state(this, "전경 서비스 실행 실패"); stopSelf(); return START_NOT_STICKY
        }
        usageClock.clear(); resetOfferState(); running = true
        if (JevFeature.available()) jev.start()
        observeUsage()
        handler.removeCallbacks(tick); handler.post(tick)
        return START_NOT_STICKY
    }

    fun triggerTestIntervention(forceStrong: Boolean = false) {
        if (!Settings.canDrawOverlays(this)) return
        removeOverlay()
        dismissJevFeedback()
        dismissCompletion()
        val tracker = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this)
        val ambient = tracker.snapshot.value
        val reasons = com.example.itv.domain.coaching.InterventionReasonResolver.resolveReasons(
            isJev = false,
            jevContext = null,
            continuousUsedMs = 1_800_000L,
            ambient = ambient,
            shortformAppName = null
        )
        val effectiveIntensity = if (forceStrong) CharacterIntensity.STRONG else getEffectiveIntensity()
        lastOfferedIntensity = effectiveIntensity
        val isStrong = effectiveIntensity == CharacterIntensity.STRONG
        val rec = com.example.itv.domain.coaching.RestMissionCatalog.pick(ambient.posture, isStrong)
        currentRecommendation = rec
        val (root, params) = overlayFactory.offer(::onChoice, reasons, intensityOverride = effectiveIntensity, recommendation = rec)
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
        if (jevMode()) {
            val targetPkg = jev.state.value.context?.packageName ?: jev.approvedPackage()
            val hasApproval = jev.hasTransferrableApproval() || targetPkg == JevSettings.YOUTUBE || targetPkg == "ALL"
            if (running && hasApproval) {
                jev.promotePendingTransfer(JevSettings.YOUTUBE)
                showPuppy(transition = true)
            }
            return
        }
        if (!waitingYoutube) return
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
        if (jevMode()) {
            val targetPkg = jev.state.value.context?.packageName ?: jev.approvedPackage()
            val hasApproval = jev.hasTransferrableApproval() || targetPkg == JevSettings.YOUTUBE || targetPkg == "ALL"
            if (running && hasApproval) {
                jev.promotePendingTransfer(JevSettings.YOUTUBE)
                showPuppy(transition = true)
            }
            return
        }
        if (!waitingTransition && !waitingYoutube) return
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
        if (jevMode()) {
            val targetPkg = jev.state.value.context?.packageName ?: jev.approvedPackage()
            val hasApproval = jev.hasTransferrableApproval() || targetPkg == JevSettings.INSTAGRAM || targetPkg == "ALL"
            if (running && hasApproval) {
                jev.promotePendingTransfer(JevSettings.INSTAGRAM)
                showPuppy(transition = true)
            }
            return
        }
        // Only a transition AFTER arming can trigger this path.
        if (!waitingTransition) return
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
        if (jevMode()) {
            val targetPkg = jev.state.value.context?.packageName ?: jev.approvedPackage()
            val hasApproval = jev.hasTransferrableApproval() || targetPkg == JevSettings.INSTAGRAM || targetPkg == "ALL"
            if (running && hasApproval) {
                jev.promotePendingTransfer(JevSettings.INSTAGRAM)
                showPuppy(transition = true)
            }
            return
        }
        if (!waitingTransition) return
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
        if (jevMode()) {
            val targetPkg = jev.state.value.context?.packageName ?: jev.approvedPackage()
            val hasApproval = jev.hasTransferrableApproval() || targetPkg == event.packageName || targetPkg == "ALL"
            if (running && hasApproval) {
                jev.promotePendingTransfer(event.packageName)
                showPuppy(transition = true)
            }
            return
        }
        if (!waitingTransition) return
        if (!observeUsage() || !waitingTransition) return
        val safeAt = safeSince ?: return
        if (WritingGuard.blockReason() != null) { safeSince = null; return }
        if (now - safeAt < 5000L || usageClock.usedMs < interventionMs) return
        if (!CoachingAutoStart.enabled(this)) return
        showPuppy(transition = true)
    }
    private fun showPuppy(transition: Boolean = true) {
        // 0. 취침 케어 또는 이전 개입에서 닫기/완료(suppressed), '나중에' 유예시간(retryAt) 대기, 또는 취침 케어가 표시 중이면 무조건 차단 (Jev/일반 모드 공통)
        if (responseController.suppressed) return
        val now = SystemClock.elapsedRealtime()
        if (responseController.retryAt != null && now < (responseController.retryAt ?: 0L)) return
        if (bedtimeCareController.isDialogShowing()) return

        val isJev = jevMode()
        // 1. 메시지·문서·키보드 입력·인스타 DM 중 개입 절대 금지
        if (WritingGuard.blockReason() != null || observer.snapshot().isActivelyWriting()) return
        val currentFg = JevObservationHub.foregroundPackage
        if (JevSettings.isDocumentOrMessagingPackage(currentFg)) return

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

        if (isJev) {
            jev.promotePendingTransfer(currentFg)
            if (!jev.canShow(transition)) return
        }
        else {
            if (usageClock.usedMs < interventionMs) return
            val safeAt = safeSince ?: return
            if (SystemClock.elapsedRealtime() - safeAt < 5000L) return
        }
        if (feedbackView != null || completionView != null || missionController.state.id != null || overlay != null || !unlocked() || !com.example.itv.composition.CoachingAccess.ready(this) || !Settings.canDrawOverlays(this)) return
        val jevOfferId = if (isJev) jev.approvedId() else null
        val responseMs = TestCoachingPolicy.responseMs(isJev)
        val shortformApp = when {
            YoutubeProbe.screenState() == 1 -> "YouTube 쇼츠"
            ReelsProbe.screenState() == 1 -> "Instagram 릴스"
            currentFg == JevSettings.YOUTUBE -> "YouTube"
            else -> null
        }
        val ambient = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).snapshot.value
        if (ambient.isBedtimeCandidate) {
            removeOverlay()
            responseController.offered = true
            val offerId = jevOfferId ?: java.util.UUID.randomUUID().toString().take(8)
            responseController.offerId = offerId
            if (isJev) jev.shown()
            InterventionJournal.add(this, offerId, "취침 케어 개입 표시 (3분기)")
            val ctx = if (isJev) jev.state.value.context else null
            val continuousMin = (ctx?.continuousMs ?: usageClock.usedMs) / 60000.0
            val bedtimePassedMin = ctx?.bedtimePassedMinutes ?: ambient.bedtimeDistanceMinutes?.takeIf { it > 0 }
            val jevProb = if (isJev) jev.state.value.probability else null
            val baseThreshold = jev.store.settings.value.probabilityThreshold
            val effThreshold = if (ctx != null) com.example.itv.domain.jev.JevCandidatePolicy.effectiveThreshold(jev.store.settings.value, ctx) else baseThreshold
            val probDiff = if (jevProb != null) (jevProb - effThreshold).coerceAtLeast(0.0) else null

            bedtimeCareController.showBedtimeCareDialog(
                containerHost = null,
                bedtimePassedMinutes = bedtimePassedMin,
                continuousMinutes = continuousMin,
                jevProbabilityDiff = probDiff
            ) {
                responseController.offered = false
                responseController.suppressed = true
                responseController.retryAt = null
                if (isJev) {
                    jev.respond("DISMISS")
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
            shortformAppName = shortformApp
        )
        val effectiveIntensity = getEffectiveIntensity()
        lastOfferedIntensity = effectiveIntensity
        val isStrong = effectiveIntensity == CharacterIntensity.STRONG
        val rec = com.example.itv.domain.coaching.RestMissionCatalog.pick(ambient.posture, isStrong)
        currentRecommendation = rec
        val (root, params) = overlayFactory.offer(::onChoice, reasons, intensityOverride = effectiveIntensity, recommendation = rec)
        try {
            wm.addView(root, params); overlay = root; offerFromJev = isJev
            displayedPackage = JevObservationHub.foregroundPackage.takeIf { SystemClock.elapsedRealtime() - JevObservationHub.checkedAt in 0..1500 }
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
        if (overlay != null && JevFeature.available()) {
            if (!preserveForAppSwitch) {
                jev.cancelled(closingCause)
            }
        }
        if (responseController.offerId != null) {
            if (!preserveForAppSwitch) {
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
        overlay = null;displayedPackage=null;retryVisible=false;reactionInProgress=false;isRestMissionFailure=false
        previousOverlay?.let { overlayFactory.removeControls(it); try { wm.removeView(it) } catch (_: Exception) {} }
    }
    private var puppyInteracting = false
    private fun onPuppyInteractionStateChanged(isInteracting: Boolean) {
        puppyInteracting = isInteracting
        if (isInteracting) {
            handler.removeCallbacks(offerTimeout)
        } else {
            if (overlay != null && !reactionInProgress && responseController.offerId != null) {
                resetOfferTimeout("강아지 조작 종료")
            }
        }
    }
    fun onUserActivity(reason: String) {
        if (overlay == null || reactionInProgress || responseController.offerId == null) return
        if (puppyInteracting) return
        resetOfferTimeout(reason)
    }
    private fun resetOfferTimeout(reason: String = "") {
        if (overlay == null || reactionInProgress || responseController.offerId == null) return
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
        running = false; handler.removeCallbacksAndMessages(null); removeOverlay(); nm.cancel(7102)
        if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false }
        com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(this).stop()
        val status = com.example.itv.composition.CoachingAccess.status(this)
        if (!status.contains("실패") && !status.contains("해제")) com.example.itv.composition.CoachingAccess.state(this, "중지됨")
        publishDiagnostics()
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
