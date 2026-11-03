package com.example.itv.presentation.coaching
import com.example.itv.domain.coaching.RestUsageClock
import com.example.itv.infrastructure.coaching.InterventionJournal
import com.example.itv.infrastructure.coaching.MissionStats

import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe
import com.example.itv.infrastructure.observation.accessibility.WritingGuard


import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import com.example.itv.domain.coaching.DeveloperStatus
import com.example.itv.domain.coaching.DeveloperSnapshot
import com.example.itv.R
import com.example.itv.application.coaching.*
import com.example.itv.domain.coaching.TestCoachingPolicy
import com.example.itv.infrastructure.coaching.*

class CoachingMonitorService : Service() {

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
            override fun status(value: String): Unit { com.example.itv.composition.CoachingAccess.state(this@CoachingMonitorService, value) }
            override fun hideOffer(): Unit { removeOverlay(); nm.cancel(7102) }
            override fun showOffer(): Unit { showPuppy() }
        })
    }
    private val overlayFactory: CoachingOverlayFactory by lazy { CoachingOverlayFactory(this) }
    private fun onChoice(title: String, message: String) {
        val selectedId = responseController.offerId ?: return
        when (responseController.choose(title, SystemClock.elapsedRealtime())) {
            ResponseOutcome.IGNORED -> return
            ResponseOutcome.EXPIRED -> { offerTimeout.run(); return }
            ResponseOutcome.REST -> {
                handler.removeCallbacks(offerTimeout)
                removeOverlay(); nm.cancel(7102)
                missionController.begin(selectedId, SystemClock.elapsedRealtime())
                com.example.itv.composition.CoachingAccess.state(this, "$title 선택 · 충분한 휴식 전까지 재제안 안 함")
            }
            ResponseOutcome.LATER -> {
                handler.removeCallbacks(offerTimeout)
                removeOverlay(); nm.cancel(7102)
                com.example.itv.composition.CoachingAccess.state(this, "나중에 선택 · 5분 뒤 조건 재확인")
            }
            ResponseOutcome.DISMISS -> {
                handler.removeCallbacks(offerTimeout)
                removeOverlay(); nm.cancel(7102)
                com.example.itv.composition.CoachingAccess.state(this, "$title 선택 · 충분한 휴식 전까지 재제안 안 함")
            }
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private var closingCause = "표시 중단 · 안전 보호/화면 전환"
    private val missionStartTimeout: Runnable = Runnable { onMissionStartTimeout() }
    private fun onMissionStartTimeout(): Unit {
        if (missionController.state.id != null && !missionController.state.started && !missionController.state.pending) {
            if (!pm.isInteractive) missionScreenOff()
            if (!missionController.state.started) failMissionStart()
        }
    }
    private fun failMissionStart(): Unit { missionController.failStart() }
    private var completionView: View? = null
    private val offerTimeout: Runnable = Runnable {
        if (responseController.offerId != null && overlay != null) {
            responseController.timeout(SystemClock.elapsedRealtime())
            removeOverlay(); nm.cancel(7102)
            com.example.itv.composition.CoachingAccess.state(this, "무응답 · 5분 뒤 조건 재확인")
        }
    }
    private fun finishOffer(outcome: String) {
        responseController.finish(outcome)
        handler.removeCallbacks(offerTimeout)
    }
    private fun missionScreenOff() {
        dismissCompletion()
        missionController.screenOff(SystemClock.elapsedRealtime())
    }
    private fun missionScreenOn(): Unit { missionController.screenOn(SystemClock.elapsedRealtime()) }
    private fun checkMission() {
        if (!pm.isInteractive) missionScreenOff() else missionScreenOn()
        if (!missionController.state.pending || !unlocked() || completionView != null) return
        if (!com.example.itv.composition.CoachingAccess.ready(this) || !Settings.canDrawOverlays(this)) return
        val reason = WritingGuard.blockReason()
        // The ITV internal fast-path deliberately blocks coaching, not a requested mission result.
        if (reason != null && !(WritingGuard.connected &&
            SystemClock.elapsedRealtime() - WritingGuard.checkedAt <= 3000L &&
            reason == "ITV 내부 화면 · 구조 탐색 생략 / 개입 보류")) return
        val id = missionController.state.id ?: return
        val (box, params) = overlayFactory.completion(::dismissCompletion)
        try {
            wm.addView(box, params); completionView = box
            missionController.completionDisplayed()
            handler.postDelayed({ if (completionView === box) dismissCompletion() }, 10_000L)
        } catch (_: Exception) {
            // Keep pending to retry when permission/window state permits it.
        }
    }
    private fun dismissCompletion() {
        completionView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        completionView = null
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
    private val reminderMs = TestCoachingPolicy.REMINDER_MS
    private var overlay: View? = null
    private var receiverRegistered = false
    private var running = false
    private var safeSince: Long?
        get() = progress.safeSince
        set(value) { progress.safeSince = value }
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val pm by lazy { getSystemService(PowerManager::class.java) }
    private val km by lazy { getSystemService(KeyguardManager::class.java) }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private fun unlocked() = observer.unlocked()
    private fun resetOfferState(): Unit { progress.reset() }
    private fun observeUsage(): Boolean = progress.observe(SystemClock.elapsedRealtime(), unlocked(), overlay != null)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // SCREEN_OFF is definitive even if PowerManager has not updated yet.
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                missionScreenOff()
                progress.observe(SystemClock.elapsedRealtime(), false, overlay != null)
            } else {
                if (intent.action == Intent.ACTION_SCREEN_ON) missionScreenOn()
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
            checkMission()
            val using = observeUsage()
            val observation = observer.snapshot()
            progress.tick(SystemClock.elapsedRealtime(), using, observation, overlay != null)
            publishDiagnostics()
            if (running) handler.postDelayed(this, 1000)
        }
    }
    // Read-only diagnostics: never advances the clock or changes coaching decisions.
    private fun publishDiagnostics() {
        val now = SystemClock.elapsedRealtime()
        val guard = WritingGuard.blockReason()
        val youtube = YoutubeProbe.screenState()
        val reels = ReelsProbe.screenState()
        val screen = when {
            !WritingGuard.connected || now - WritingGuard.checkedAt > 3000L -> "판별 불가 · 접근성 관측 없음/지연"
            youtube == 1 -> "확인된 YouTube 쇼츠"
            reels == 1 -> "확인된 Instagram 릴스"
            youtube == -1 || reels == -1 -> "숏폼 여부 판별 불가"
            else -> "확인된 릴스·쇼츠 아님"
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
            youtube == 1 -> "다음 쇼츠 전환 대기"
            youtube == -1 -> "YouTube/현재 화면 확인 불가 · 보류"
            reels == 1 -> "다음 릴스 전환 대기"
            reels == -1 -> "현재 화면 판별 불가 · 보류"
            else -> "조건 재확인/표시 처리 중"
        }
        DeveloperStatus.value = DeveloperSnapshot(now, running, usageClock.usedMs,
            usageClock.restMs, interventionMs, 30_000L,
            ((responseController.retryAt ?: now) - now).coerceAtLeast(0L), screen, decision)
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); com.example.itv.composition.CoachingAccess.channels(this)
        MissionStats.recoverInterrupted(this)
        ReelsProbe.onTransition = { onReelsTransition() }
        YoutubeProbe.coachingObservation = true
        YoutubeProbe.clearBaseline()
        YoutubeProbe.onTransition = { onYoutubeTransition() }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            com.example.itv.composition.CoachingAccess.setDeveloperAutoEnabled(this, false)
            stopSelf(); return START_NOT_STICKY
        }
        if (!CoachingAutoStart.enabled(this) || !com.example.itv.composition.CoachingAccess.ready(this)) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        try {
            val open = PendingIntent.getActivity(this, 7100, Intent(this, com.example.itv.presentation.permissions.PermissionSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 7101, Intent(this, CoachingMonitorService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, com.example.itv.composition.CoachingAccess.MONITOR)
                .setSmallIcon(R.drawable.ic_coaching_notification)
                .setContentTitle("강아지 휴식 제안 켜짐")
                .setContentText("휴식 이후 누적 사용 ${interventionMs / 60_000L}분 감지 · 눌러서 관리")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "감시 중지", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(7101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(7101, notification)
        } catch (_: Exception) {
            com.example.itv.composition.CoachingAccess.state(this, "전경 서비스 실행 실패"); stopSelf(); return START_NOT_STICKY
        }
        usageClock.clear(); resetOfferState(); running = true
        observeUsage()
        handler.removeCallbacks(tick); handler.post(tick)
        return START_NOT_STICKY
    }

    private fun onYoutubeTransition() {
        if (!running || !waitingYoutube || responseController.offered || responseController.suppressed) return
        val now = SystemClock.elapsedRealtime()
        if (!observeUsage() || !waitingYoutube) return
        if (WritingGuard.blockReason() != null) { safeSince = null; return }
        val safeAt = safeSince ?: return
        if (now-safeAt < 5000L || usageClock.usedMs < interventionMs || now < (responseController.retryAt ?: 0L)) return
        if (YoutubeProbe.screenState()!=1 || !CoachingAutoStart.enabled(this)) return
        showPuppy()
    }
    private fun onReelsTransition() {
        // Only a transition AFTER arming can trigger this path.
        if (!running || !waitingTransition || responseController.offered || responseController.suppressed) return
        val now = SystemClock.elapsedRealtime()
        if (!observeUsage() || !waitingTransition) return
        val safeAt = safeSince ?: return
        if (WritingGuard.blockReason() != null) { safeSince = null; return }
        if (now - safeAt < 5000L || usageClock.usedMs < interventionMs || now < (responseController.retryAt ?: 0L)) return
        if (ReelsProbe.screenState() != 1 || !CoachingAutoStart.enabled(this)) return
        showPuppy()
    }
    private fun showPuppy() {
        if (WritingGuard.blockReason() != null || responseController.suppressed || usageClock.usedMs < interventionMs || SystemClock.elapsedRealtime() < (responseController.retryAt ?: 0L)) return
        val safeAt = safeSince ?: return
        if (SystemClock.elapsedRealtime() - safeAt < 5000L) return
        if (completionView != null || missionController.state.pending || overlay != null || !unlocked() || !com.example.itv.composition.CoachingAccess.ready(this) || !Settings.canDrawOverlays(this)) return
        val (root, params) = overlayFactory.offer(::onChoice)
        try {
            wm.addView(root, params); overlay = root; responseController.offered = true; waitingTransition = false; waitingYoutube = false; responseController.retryAt = null
            responseController.offerId = java.util.UUID.randomUUID().toString().take(8)
            responseController.deadline = SystemClock.elapsedRealtime() + TestCoachingPolicy.RESPONSE_MS
            InterventionJournal.add(this, responseController.offerId!!, "개입 표시 성공 · " + when {
                YoutubeProbe.screenState() == 1 -> "쇼츠"
                ReelsProbe.screenState() == 1 -> "릴스"
                else -> "일반 화면"
            })
            handler.removeCallbacks(offerTimeout); handler.postDelayed(offerTimeout, TestCoachingPolicy.RESPONSE_MS)
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
            InterventionJournal.add(this, responseController.offerId ?: "-", "개입 표시 실패")
            removeOverlay(); responseController.offered = true
            com.example.itv.composition.CoachingAccess.state(this, "표시 실패 · 앱 보안 설정/오버레이 권한 확인 후 감시를 다시 시작하세요")
        }
    }
    private fun removeOverlay() {
        if (responseController.offerId != null) finishOffer(closingCause)
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }; overlay = null
    }
    override fun onDestroy() {
        closingCause = "표시 중단 · 서비스 종료"
        missionController.stop()
        dismissCompletion()
        ReelsProbe.onTransition = null
        YoutubeProbe.onTransition = null
        YoutubeProbe.coachingObservation = false
        running = false; handler.removeCallbacksAndMessages(null); removeOverlay(); nm.cancel(7102)
        if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false }
        val status = com.example.itv.composition.CoachingAccess.status(this)
        if (!status.contains("실패") && !status.contains("해제")) com.example.itv.composition.CoachingAccess.state(this, "중지됨")
        publishDiagnostics()
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
