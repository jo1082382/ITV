package com.example.itv.infrastructure.observation.instagram
import com.example.itv.domain.records.ShortformApp


import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/** Structure-only detection. Never reads message text, images or video identities. */
internal object ReelsProbe {
    // Main-thread callback: no broadcast, polling delay or persisted video identity.
    var onTransition: (() -> Unit)? = null
    private var frameAt = 0L
    private var frameKnown = false
    // ITV is a known non-Instagram foreground; preserve samples and counts.
    fun onOwnApp() {
        frameAt = SystemClock.elapsedRealtime(); frameKnown = true
        valid = false; currentInstagram = false; lastTick = frameAt
        status = "ITV 내부 · 탐색 생략"
    }
    fun screenState(): Int {
        if (SystemClock.elapsedRealtime() - frameAt > 1500L || !frameKnown) return -1
        return if (valid) 1 else 0
    }
    private var serviceContext: android.content.Context? = null
    var enabled = false
    var latest = emptySet<String>()
    private var currentInstagram = false
    var latestAt = 0L
    var valid = false
    var status = "테스트 중지"
    var reels: Set<String>? = null
    var feed: Set<String>? = null
    var dm: Set<String>? = null
    var comments: Set<String>? = null
    var events = 0
    var candidates = 0
    var lastScroll = 0L
    var candidateMs = 0L
    private var lastTick = 0L
    fun markers(): Set<String> {
        val positive = reels ?: return emptySet()
        val negatives = (feed ?: return emptySet()) + (dm ?: return emptySet()) + (comments ?: return emptySet())
        return positive - negatives
    }
    fun sample(): Set<String>? = latest.takeIf { it.isNotEmpty() && SystemClock.elapsedRealtime() - latestAt < 60_000L }
    fun reset() {
        frameKnown = false; frameAt = 0L
        latest = emptySet(); latestAt = 0L; currentInstagram = false; reels = null; feed = null; dm = null; comments = null
        events = 0; candidates = 0; candidateMs = 0; lastTick = 0; lastScroll = 0; valid = false
    }
    fun dmScreen(): Boolean {
        val reference = dm ?: return false
        val distinct = reference - (reels ?: emptySet()) - (feed ?: emptySet()) - (comments ?: emptySet())
        return currentInstagram && SystemClock.elapsedRealtime() - latestAt < 1500L && distinct.size >= 2 &&
            (latest intersect distinct).size >= 2
    }
    fun tick(service: AccessibilityService) {
        serviceContext = service.applicationContext
        com.example.itv.composition.ReelsCalibrationConnection.initialize(service)
        try { inspectFrame(service) } finally {
            com.example.itv.composition.shortformObservationService(service).presence(ShortformApp.INSTAGRAM, valid)
        }
    }
    private fun inspectFrame(service: AccessibilityService) {
        val now = SystemClock.elapsedRealtime()
        frameAt = now; frameKnown = false
        val dt = if (lastTick == 0L) 0L else (now-lastTick).coerceIn(0L, 1000L)
        lastTick = now
        val previous = valid
        valid = false
        currentInstagram = false
        if (!com.example.itv.infrastructure.coaching.CoachingConsentStore.granted(service)) { status = "수집 동의 없음"; return }
        val power = service.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val keyguard = service.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!power.isInteractive || keyguard.isKeyguardLocked) { status = "화면 꺼짐 또는 잠금"; return }
        try {
            val windows = service.windows.orEmpty()
            val w = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            val root = w?.root ?: run { status = "화면 판별 불가"; return }
            try {
                if (root.packageName?.toString() != "com.instagram.android") { frameKnown = !root.packageName.isNullOrBlank(); status = "Instagram 전면 아님"; return }
                val ids = mutableSetOf<String>(); var visited = 0; var truncated = false
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                    if (++visited > 900 || depth > 40) { truncated = true; return }
                    if (!n.isVisibleToUser) return
                    n.viewIdResourceName?.takeIf { it.startsWith("com.instagram.android:id/") }?.let { ids.add(it) }
                    for (i in 0 until n.childCount) {
                        if (visited >= 900) { truncated = true; break }
                        val child = n.getChild(i) ?: continue
                        try { walk(child, depth+1) } finally { @Suppress("DEPRECATION") child.recycle() }
                    }
                }
                walk(root,0)
                if (truncated) { status = "검사 한도 초과 · 판별 불가"; return }
                latest = ids; latestAt = now; currentInstagram = true; frameKnown = ids.isNotEmpty()
                val markers = markers()
                val matched = (ids intersect markers).size
                val commentOnly = (comments ?: emptySet()) - (reels ?: emptySet()) - (feed ?: emptySet()) - (dm ?: emptySet())
                val commentScreen = commentOnly.isNotEmpty() && (ids intersect commentOnly).isNotEmpty()
                valid = markers.size >= 2 && matched >= 2 && !commentScreen && !dmScreen() && windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                status = when {
                    ids.isEmpty() -> "ID 비공개 · 이 방법으로 식별 불가"
                    markers.size < 2 -> "표본 부족 또는 구분 ID 부족 · 판별 불가"
                    valid -> "릴스 후보 · 일치 ID ${matched}개 (확정 아님)"
                    else -> "릴스 후보 미감지 또는 키보드 표시"
                }
                if (previous && valid) candidateMs += dt
            } finally { @Suppress("DEPRECATION") root.recycle() }
        } catch (_: Exception) { status = "화면 검사 실패 · 판별 불가" }
    }
    fun event(e: AccessibilityEvent?) {
        if (!valid || e?.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED || e.packageName?.toString() != "com.instagram.android") return
        events++
        val source = e.source ?: return
        try {
            val rect = Rect(); source.getBoundsInScreen(rect)
            // Only a large source whose ID was present in the positive sample is a transition candidate.
            val id = source.viewIdResourceName ?: return
            val now = SystemClock.elapsedRealtime()
            if (id in (reels ?: emptySet()) && rect.height() >= 500 && rect.height() > rect.width() && now-lastScroll >= 1200L) {
                candidates++; lastScroll = now; com.example.itv.composition.shortformObservationService(serviceContext ?: return).transition(ShortformApp.INSTAGRAM)
                onTransition?.invoke()
            }
        } finally { @Suppress("DEPRECATION") source.recycle() }
    }
}
