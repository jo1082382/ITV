package com.example.itv.infrastructure.observation.accessibility
import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe


import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicBoolean

/** Reads UI structure only: never accesses node.text, hints, descriptions or event text. */
@android.annotation.SuppressLint("AccessibilityPolicy")
open class WritingGuardService : AccessibilityService() {
    private val observers = com.example.itv.infrastructure.observation.ObservationRegistry()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var workerThread: HandlerThread? = null
    @Volatile private var workerHandler: Handler? = null

    private val messengerPackages = setOf(
        "com.kakao.talk", "com.discord",
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "org.telegram.messenger", "jp.naver.line.android"
    )
    @Volatile private var ownScreen = false
    @Volatile private var dormantInfoApplied = false
    @Volatile private var lastOwnCheck = 0L
    @Volatile private var lastFullScanAt = 0L
    private val pendingScan = AtomicBoolean(false)

    private val eventScan = Runnable {
        pendingScan.set(false)
        scan()
    }

    private fun ensureWorkerHandler(): Handler {
        workerHandler?.let { existing ->
            if (workerThread?.isAlive == true) return existing
        }
        synchronized(this) {
            workerHandler?.let { existing ->
                if (workerThread?.isAlive == true) return existing
            }
            val thread = HandlerThread("ItvA11yWorker", Process.THREAD_PRIORITY_BACKGROUND).apply {
                start()
            }
            val handler = Handler(thread.looper)
            workerThread = thread
            workerHandler = handler
            return handler
        }
    }

    /**
     * Dynamically tightens or restores AccessibilityServiceInfo in system_server:
     * - While ITV itself is in the foreground: clear eventTypes, feedbackType, flags, and scope
     *   packageNames to a dormant noop sentinel.
     *   1) Clearing feedbackType (0) excludes WritingGuardService from
     *      AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK), which lets
     *      Jetpack Compose's AndroidComposeViewAccessibilityDelegateCompat shut down its expensive
     *      100ms main-thread semantics diffing & bounds-update loop inside ITV (while leaving real
     *      screen readers like TalkBack 100% untouched).
     *   2) Clearing eventTypes/flags and setting a non-matching packageNames filter guarantees
     *      system_server dispatches ZERO AccessibilityEvent IPCs from ITV to this service.
     * - While external apps are in the foreground: immediately restore the minimal 5 event types,
     *   FEEDBACK_GENERIC, window/view-id flags, and null packageNames (all packages) needed for
     *   writing protection, Reels/Shorts/Longform detection, and foreground app tracking.
     */
    private fun applyServiceInfo(inOwnApp: Boolean) {
        if (dormantInfoApplied == inOwnApp && serviceInfo != null) return
        try {
            val info = serviceInfo ?: return
            WritingGuardServiceInfoPolicy.configure(info, inOwnApp = inOwnApp)
            serviceInfo = info
            dormantInfoApplied = inOwnApp
        } catch (_: Exception) {
            // Ignore transient binder errors while service is connecting/unbinding
        }
    }

    private fun applyServiceInfoNowOrPost(inOwnApp: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyServiceInfo(inOwnApp)
        } else {
            mainHandler.post { applyServiceInfo(inOwnApp) }
        }
    }

    private fun enterDormantOwnAppMode() {
        com.example.itv.infrastructure.jev.JevObservationHub.foreground(packageName)
        workerHandler?.removeCallbacks(poll)
        workerHandler?.removeCallbacks(eventScan)
        pendingScan.set(false)
        if (!ownScreen) {
            // Pause once on entry; do not repeatedly write stores on scroll events.
            com.example.itv.infrastructure.storage.shortform.ShortformStores.pause()
            observers.onOwnApp()
            com.example.itv.infrastructure.observation.GeneralAppTracker.onObserve(this, packageName)
        }
        ownScreen = true
        result(true, "ITV 내부 화면 · 접근성 휴면 / 개입 보류")
        applyServiceInfoNowOrPost(inOwnApp = true)
    }

    private fun wakeForExternalApp() {
        ownScreen = false
        applyServiceInfoNowOrPost(inOwnApp = false)
        val worker = ensureWorkerHandler()
        worker.removeCallbacks(poll)
        worker.post(poll)
    }

    private fun scan() {
        // Fast-path: while any ITV Activity is in foreground, NEVER query `windows` or `root`
        // and stay completely dormant (no polling, no Binder IPC).
        if (WritingGuard.inAppForeground) {
            enterDormantOwnAppMode()
            return
        }
        // Determine only the foreground package. Never traverse ITV's Compose tree.
        var fromApplicationWindow = false
        val pkg = try {
            val target = findActiveApplicationWindow(windows)
            val r = target?.root
            val appPkg = r?.packageName?.toString()
            if (r != null) {
                @Suppress("DEPRECATION") r.recycle()
            }
            if (appPkg != null && !com.example.itv.infrastructure.jev.JevObservationHub.isTransientSystemPackage(appPkg)) {
                fromApplicationWindow = true
                appPkg
            } else {
                val root = rootInActiveWindow
                val rawPkg = root?.packageName?.toString()
                @Suppress("DEPRECATION") root?.recycle()
                rawPkg
            }
        } catch (_: Exception) { null }
        if (pkg == packageName) {
            if (fromApplicationWindow || WritingGuard.inAppForeground) {
                enterDormantOwnAppMode()
            }
            // Otherwise rawPkg came from an ITV overlay (e.g., HUD/coaching bubble) on top of an external app:
            // do NOT overwrite JevObservationHub.foregroundPackage or enter dormant mode.
            return
        }
        com.example.itv.infrastructure.jev.JevObservationHub.foreground(pkg)
        ownScreen = false
        if (dormantInfoApplied) {
            mainHandler.post { applyServiceInfo(inOwnApp = false) }
        }
        lastFullScanAt = SystemClock.elapsedRealtime()
        observers.beforeWritingCheck(this)
        inspect()
        observers.afterWritingCheck(this)
        com.example.itv.infrastructure.observation.GeneralAppTracker.onObserve(this, pkg)
    }

    private fun isPowerSaveEligible(now: Long = SystemClock.elapsedRealtime()): Boolean =
        com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isStableLongformPlayback(now) ||
            com.example.itv.infrastructure.observation.instagram.InstagramTracker.isPowerSaveScanEligible(now) ||
            com.example.itv.infrastructure.observation.GeneralAppTracker.isPowerSaveScanEligible(now)

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (WritingGuard.inAppForeground) {
                enterDormantOwnAppMode()
                return
            }
            scan()
            if (ownScreen || WritingGuard.inAppForeground) {
                // Dormant while inside ITV: TYPE_WINDOW_STATE_CHANGED or Activity lifecycle will wake us.
                return
            }
            val delayMs = if (isPowerSaveEligible()) 1500L else 600L
            workerHandler?.postDelayed(this, delayMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        WritingGuard.connected = true
        dormantInfoApplied = false
        val worker = ensureWorkerHandler()
        if (WritingGuard.inAppForeground) {
            applyServiceInfo(inOwnApp = true)
            worker.post { enterDormantOwnAppMode() }
        } else {
            applyServiceInfo(inOwnApp = false)
            worker.removeCallbacks(poll)
            worker.post(poll)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString()
        val eventType = e.eventType

        // Zero-overhead fast-path while user is inside ITV: ignore all self-generated events.
        if (WritingGuard.inAppForeground) {
            if (pkg == null || pkg == packageName) {
                if (!ownScreen) {
                    ensureWorkerHandler().post { enterDormantOwnAppMode() }
                }
                return
            }
            // Fallback: if a real external app window appeared while inAppForeground was stale, wake up.
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !com.example.itv.infrastructure.jev.JevObservationHub.isTransientSystemPackage(pkg)
            ) {
                WritingGuard.inAppForeground = false
                wakeForExternalApp()
            } else {
                return
            }
        }

        if (pkg == packageName) {
            // WritingGuard.inAppForeground is false here, so this accessibility event came from an
            // ITV overlay window (LongformDiagnosticHud or coaching overlay) over an external app.
            // Ignore it without entering dormant mode or overwriting JevObservationHub.foregroundPackage.
            return
        }

        // Only Instagram Reels swipe detection inspects event.source synchronously.
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && pkg == "com.instagram.android") {
            observers.onEvent(e)
        }

        val now = SystemClock.elapsedRealtime()
        val isActiveInteraction = eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED

        if (isActiveInteraction) {
            com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.onUserInteraction(now)
            com.example.itv.infrastructure.observation.instagram.InstagramTracker.onUserInteraction(now)
            com.example.itv.infrastructure.observation.GeneralAppTracker.onUserInteraction(now)
            if (eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                com.example.itv.presentation.coaching.CoachingMonitorService.notifyUserActivity("accessibility_$eventType")
            }
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            com.example.itv.infrastructure.observation.GeneralAppTracker.onWindowStateChanged(
                pkg = pkg,
                className = e.className?.toString()
            )
            // If we were dormant on ownScreen and just switched to an external app, wake polling cleanly.
            if (ownScreen || dormantInfoApplied) {
                wakeForExternalApp()
                return
            }
        }

        // Throttle redundant burst scans on background worker thread.
        if (isPowerSaveEligible(now) &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            now - lastFullScanAt < 1400L
        ) {
            return
        }

        lastOwnCheck = now
        if (pendingScan.compareAndSet(false, true)) {
            ensureWorkerHandler().postDelayed(eventScan, 120L)
        }
    }

    private fun result(block: Boolean, reason: String) {
        WritingGuard.reason = reason
        WritingGuard.blocked = block
        WritingGuard.checkedAt = SystemClock.elapsedRealtime()
    }

    private fun inspect() {
        if (!com.example.itv.infrastructure.coaching.CoachingConsentStore.granted(this)) {
            result(true, "수집 동의 없음 · 개입 보류"); return
        }
        try {
            val available = windows
            if (available.isNullOrEmpty()) {
                result(true, "화면 구조 확인 불가 · 개입 보류"); return
            }
            if (available.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                result(true, "키보드 표시 중 · 개입 보류"); return
            }
            val window = findActiveApplicationWindow(available)
            if (window == null) {
                result(true, "전면 앱 확인 불가 · 개입 보류"); return
            }
            val root = window.root
            if (root == null) {
                result(true, "화면 구조 비공개 · 개입 보류"); return
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) {
                @Suppress("DEPRECATION") root.recycle()
                result(true, "앱 식별 불가 · 개입 보류"); return
            }
            // 메신저 이외의 모든 앱(유튜브·인스타·웹브라우저·게임·일반앱)은 1,500노드 전체 트리 순회를 생략하고,
            // Android 프레임워크의 O(1) 단일 포커스 조회(findFocus)만 수행하여 앱 성능 저하 및 배터리 소모를 원천 차단
            if (pkg !in messengerPackages) {
                val inputFocus = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
                val isInputFocused = try { inputFocus?.isEditable == true } finally {
                    if (inputFocus != null) {
                        @Suppress("DEPRECATION") inputFocus.recycle()
                    }
                    @Suppress("DEPRECATION") root.recycle()
                }
                when {
                    com.example.itv.infrastructure.observation.GeneralAppTracker.isFinancialPackage(pkg) ->
                        result(true, "금융/뱅킹/결제 앱 보호 중 · 개입 금지")
                    pkg == "com.instagram.android" && ReelsProbe.dmScreen() ->
                        result(true, "Instagram DM 화면 감지 · 개입 보류")
                    isInputFocused ->
                        result(true, "입력란 포커스 감지 · 개입 보류")
                    com.example.itv.domain.jev.JevSettings.isDocumentOrMessagingPackage(pkg) ->
                        result(true, "문서/메신저 앱 감지 · 개입 보류")
                    else ->
                        result(false, "키보드·입력란 보호 조건 미감지 (경량 검사)")
                }
                return
            }
            var editable = false
            var focused = false
            var visited = 0
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (!node.isVisibleToUser) return
                if (visited >= 300 || depth > 35 || (editable && focused)) return
                visited++
                if (node.isEditable) {
                    editable = true
                    if (node.isFocused) focused = true
                }
                for (i in 0 until node.childCount) {
                    if (visited >= 300 || (editable && focused)) break
                    val child = node.getChild(i) ?: continue
                    try { walk(child, depth + 1) } finally {
                        @Suppress("DEPRECATION")
                        child.recycle()
                    }
                }
            }
            try { walk(root, 0) } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }
            when {
                focused -> result(true, "입력란 포커스 감지 · 개입 보류")
                editable -> result(true, "메신저 입력란 감지 · 개입 보류")
                else -> result(true, "메신저 화면 보호 · 개입 보류")
            }
        } catch (_: Exception) {
            result(true, "작성 상태 확인 오류 · 개입 보류")
        }
    }

    override fun onInterrupt() {
        com.example.itv.infrastructure.storage.shortform.ShortformStores.pause()
        WritingGuard.blocked = true
        WritingGuard.checkedAt = 0L
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect(); return super.onUnbind(intent)
    }

    private fun disconnect() {
        if (activeInstance === this) activeInstance = null
        YoutubeProbe.enabled = false
        YoutubeProbe.clearBaseline()
        com.example.itv.infrastructure.storage.shortform.ShortformStores.pause()
        pendingScan.set(false)
        dormantInfoApplied = false
        mainHandler.removeCallbacksAndMessages(null)
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        WritingGuard.connected = false
        WritingGuard.blocked = true
        WritingGuard.checkedAt = 0L
    }

    override fun onDestroy() { disconnect(); super.onDestroy() }

    companion object {
        @Volatile
        var activeInstance: AccessibilityService? = null

        fun onAppForegroundChanged(inForeground: Boolean) {
            val service = activeInstance as? WritingGuardService ?: return
            if (inForeground) {
                // Apply synchronously when on main thread so system_server updates feedbackType=0
                // BEFORE MainActivity resumes / Compose lazily queries enabled accessibility services.
                service.applyServiceInfoNowOrPost(inOwnApp = true)
                service.ensureWorkerHandler().post { service.enterDormantOwnAppMode() }
            } else {
                service.wakeForExternalApp()
            }
        }

        fun lockScreen(): Boolean {
            val service = activeInstance ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                false
            }
        }
    }
}

/**
 * Pure configuration snapshot for [AccessibilityServiceInfo] dynamic updates.
 */
internal data class WritingGuardServiceConfig(
    val eventTypes: Int,
    val feedbackType: Int,
    val flags: Int,
    val packageNames: List<String>?,
    val notificationTimeoutMs: Long
)

/**
 * Pure configuration policy for dynamically switching [AccessibilityServiceInfo] between
 * zero-overhead own-app dormant mode and active external-app observation mode.
 */
internal object WritingGuardServiceInfoPolicy {
    internal const val DORMANT_NOOP_PACKAGE = "com.example.itv.dormant.noop"

    internal const val EXTERNAL_EVENT_TYPES =
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED

    internal const val EXTERNAL_FEEDBACK_TYPE = AccessibilityServiceInfo.FEEDBACK_GENERIC

    internal const val EXTERNAL_FLAGS =
        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS

    internal const val EXTERNAL_NOTIFICATION_TIMEOUT_MS = 250L
    internal const val OWN_APP_NOTIFICATION_TIMEOUT_MS = 1000L

    fun resolve(inOwnApp: Boolean): WritingGuardServiceConfig {
        return if (inOwnApp) {
            WritingGuardServiceConfig(
                eventTypes = 0,
                feedbackType = 0,
                flags = 0,
                packageNames = listOf(DORMANT_NOOP_PACKAGE),
                notificationTimeoutMs = OWN_APP_NOTIFICATION_TIMEOUT_MS
            )
        } else {
            WritingGuardServiceConfig(
                eventTypes = EXTERNAL_EVENT_TYPES,
                feedbackType = EXTERNAL_FEEDBACK_TYPE,
                flags = EXTERNAL_FLAGS,
                packageNames = null,
                notificationTimeoutMs = EXTERNAL_NOTIFICATION_TIMEOUT_MS
            )
        }
    }

    fun configure(info: AccessibilityServiceInfo, inOwnApp: Boolean) {
        val cfg = resolve(inOwnApp)
        info.eventTypes = cfg.eventTypes
        info.feedbackType = cfg.feedbackType
        info.flags = cfg.flags
        info.packageNames = cfg.packageNames?.toTypedArray()
        info.notificationTimeout = cfg.notificationTimeoutMs
    }
}
