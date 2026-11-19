package com.example.itv.infrastructure.observation.accessibility
import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe


import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Reads UI structure only: never accesses node.text, hints, descriptions or event text. */
@android.annotation.SuppressLint("AccessibilityPolicy")
open class WritingGuardService : AccessibilityService() {
    private val observers = com.example.itv.infrastructure.observation.ObservationRegistry()
    private val handler = Handler(Looper.getMainLooper())
    private val messengerPackages = setOf(
        "com.kakao.talk", "com.discord",
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "org.telegram.messenger", "jp.naver.line.android"
    )
    private val shortformPackages = setOf(
        "com.google.android.youtube", "com.instagram.android"
    )
    private var ownScreen = false
    private var lastOwnCheck = 0L
    private var pendingScan = false
    private val eventScan = Runnable {
        pendingScan = false
        scan()
    }
    private fun scan() {
        // Determine only the foreground package. Never traverse ITV's Compose tree.
        val pkg = try {
            val target = findActiveApplicationWindow(windows)
            val r = target?.root
            val appPkg = r?.packageName?.toString()
            if (r != null) {
                @Suppress("DEPRECATION") r.recycle()
            }
            if (appPkg != null && !com.example.itv.infrastructure.jev.JevObservationHub.isTransientSystemPackage(appPkg)) {
                appPkg
            } else {
                val root = rootInActiveWindow
                val rawPkg = root?.packageName?.toString()
                @Suppress("DEPRECATION") root?.recycle()
                rawPkg
            }
        } catch (_: Exception) { null }
        com.example.itv.infrastructure.jev.JevObservationHub.foreground(pkg)
        if (pkg == packageName) {
            if (!ownScreen) {
                // Pause once on entry; do not repeatedly write stores on scroll events.
                com.example.itv.infrastructure.storage.shortform.ShortformStores.pause()
                observers.onOwnApp()
            }
            ownScreen = true
            result(true, "ITV 내부 화면 · 구조 탐색 생략 / 개입 보류")
            com.example.itv.infrastructure.observation.GeneralAppTracker.onObserve(this, pkg)
            return
        }
        ownScreen = false
        lastFullScanAt = SystemClock.elapsedRealtime()
        observers.beforeWritingCheck(this)
        inspect()
        observers.afterWritingCheck(this)
        com.example.itv.infrastructure.observation.GeneralAppTracker.onObserve(this, pkg)
    }
    private var lastFullScanAt = 0L
    private fun isPowerSaveEligible(now: Long = SystemClock.elapsedRealtime()): Boolean =
        com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isStableLongformPlayback(now) ||
            com.example.itv.infrastructure.observation.instagram.InstagramTracker.isPowerSaveScanEligible(now) ||
            com.example.itv.infrastructure.observation.GeneralAppTracker.isPowerSaveScanEligible(now)

    private val poll = object : Runnable {
        override fun run() {
            scan()
            val delayMs = when {
                ownScreen -> 1000L
                isPowerSaveEligible() -> 1500L
                else -> 500L
            }
            handler.postDelayed(this, delayMs)
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        WritingGuard.connected = true
        handler.removeCallbacks(poll)
        handler.post(poll)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reels event counters/callbacks are kept synchronous; payload text is not read.
        observers.onEvent(event)
        val now = SystemClock.elapsedRealtime()
        val eventType = event?.eventType
        val pkg = event?.packageName?.toString()
        val isActiveInteraction = eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
            eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
            eventType == AccessibilityEvent.TYPE_GESTURE_DETECTION_START ||
            eventType == AccessibilityEvent.TYPE_GESTURE_DETECTION_END ||
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED

        if (isActiveInteraction) {
            com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.onUserInteraction(now)
            com.example.itv.infrastructure.observation.instagram.InstagramTracker.onUserInteraction(now)
            // 플로팅 HUD(우리 앱 패키지)를 터치/확장할 때는 외부 앱(게임 등)의 조작 타이머를 리셋하지 않음
            if (pkg != packageName) {
                com.example.itv.infrastructure.observation.GeneralAppTracker.onUserInteraction(now)
            }
            if (eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                com.example.itv.presentation.coaching.CoachingMonitorService.notifyUserActivity("accessibility_$eventType")
            }
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            com.example.itv.infrastructure.observation.GeneralAppTracker.onWindowStateChanged(
                pkg = pkg,
                className = event.className?.toString()
            )
        }
        if (ownScreen && pkg == packageName &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            now - lastOwnCheck < 1000L) return

        // 유튜브/인스타그램/일반앱 절전 스캔 대상 상태일 때는 단순 TYPE_WINDOW_CONTENT_CHANGED 버스트를 1.4초간 무시하여 배터리 절약
        if (!isActiveInteraction &&
            isPowerSaveEligible(now) &&
            now - lastFullScanAt < 1400L) {
            return
        }

        lastOwnCheck = now
        // Coalesce bursts without postponing an already scheduled scan (no starvation).
        if (!pendingScan) {
            pendingScan = true
            handler.postDelayed(eventScan, 100L)
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
        pendingScan = false
        handler.removeCallbacksAndMessages(null)
        WritingGuard.connected = false
        WritingGuard.blocked = true
        WritingGuard.checkedAt = 0L
    }
    override fun onDestroy() { disconnect(); super.onDestroy() }

    companion object {
        @Volatile
        var activeInstance: AccessibilityService? = null

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
