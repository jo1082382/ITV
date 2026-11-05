package com.example.itv.infrastructure.observation.accessibility
import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe


import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Reads UI structure only: never accesses node.text, hints, descriptions or event text. */
open class WritingGuardService : AccessibilityService() {
    private val observers = com.example.itv.infrastructure.observation.ObservationRegistry()
    private val handler = Handler(Looper.getMainLooper())
    private val messengerPackages = setOf(
        "com.kakao.talk", "com.discord", "com.instagram.android",
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "org.telegram.messenger", "jp.naver.line.android"
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
            val root = rootInActiveWindow
            if (root == null) null else try { root.packageName?.toString() }
            finally { @Suppress("DEPRECATION") root.recycle() }
        } catch (_: Exception) { null }
        if (com.example.itv.infrastructure.jev.JevFeature.available()) {
            com.example.itv.infrastructure.jev.JevObservationHub.foreground(pkg)
        }
        if (pkg == packageName) {
            if (!ownScreen) {
                // Pause once on entry; do not repeatedly write stores on scroll events.
                com.example.itv.infrastructure.storage.shortform.ShortformStores.pause()
            }
            ownScreen = true
            observers.onOwnApp()
            result(true, "ITV 내부 화면 · 구조 탐색 생략 / 개입 보류")
            return
        }
        ownScreen = false
        observers.beforeWritingCheck(this)
        inspect()
        observers.afterWritingCheck(this)
    }
    private val poll = object : Runnable {
        override fun run() {
            scan()
            handler.postDelayed(this, if (ownScreen) 1000L else 500L)
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        WritingGuard.connected = true
        handler.removeCallbacks(poll)
        handler.post(poll)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reels event counters/callbacks are kept synchronous; payload text is not read.
        observers.onEvent(event)
        val now = SystemClock.elapsedRealtime()
        val pkg = event?.packageName?.toString()
        if (ownScreen && pkg == packageName &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            now - lastOwnCheck < 1000L) return
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
            val window = available.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
            } ?: available.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            }
            if (window == null) {
                result(true, "전면 앱 확인 불가 · 개입 보류"); return
            }
            val root = window.root
            if (root == null) {
                result(true, "화면 구조 비공개 · 개입 보류"); return
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) {
                result(true, "앱 식별 불가 · 개입 보류"); return
            }
            var editable = false
            var focused = false
            var visited = 0
            var truncated = false
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (visited >= 700 || depth > 40) { truncated = true; return }
                visited++
                if (!node.isVisibleToUser) return
                if (node.isEditable) {
                    editable = true
                    if (node.isFocused) focused = true
                }
                for (i in 0 until node.childCount) {
                    if (visited >= 700) { truncated = true; break }
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
                pkg == "com.instagram.android" && ReelsProbe.dmScreen() -> result(true, "Instagram DM 화면 감지 · 개입 보류")
                focused -> result(true, "입력란 포커스 감지 · 개입 보류")
                pkg in messengerPackages && editable -> result(true, "메신저 입력란 감지 · 개입 보류")
                truncated -> result(true, "화면 구조 검사 제한 · 개입 보류")
                else -> result(false, "키보드·입력란 보호 조건 미감지")
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
}
