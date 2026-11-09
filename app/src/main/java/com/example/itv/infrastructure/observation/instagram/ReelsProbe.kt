package com.example.itv.infrastructure.observation.instagram

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.itv.domain.records.ShortformApp
import com.example.itv.infrastructure.observation.SwipeDynamicsTracker
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore

/** Structure-only detection. Never reads message text, images or video identities. */
internal object ReelsProbe {
    // Main-thread callback: no broadcast, polling delay or persisted video identity.
    var onTransition: (() -> Unit)? = null
    private var frameAt = 0L
    private var frameKnown = false

    // 기본 릴스 식별 ID (표본 미수집 시 보조용)
    private val DEFAULT_REELS_MARKERS = setOf(
        "com.instagram.android:id/clips_viewer_view_pager",
        "com.instagram.android:id/clips_video_container",
        "com.instagram.android:id/clips_viewer_content_layout",
        "com.instagram.android:id/reel_viewer_root"
    )

    // ITV is a known non-Instagram foreground; preserve samples and counts.
    fun onOwnApp() {
        frameAt = SystemClock.elapsedRealtime()
        frameKnown = true
        valid = false
        currentInstagram = false
        lastTick = frameAt
        status = "ITV 내부 · 탐색 생략"
        serviceContext?.let { ctx ->
            com.example.itv.composition.shortformObservationService(ctx).presence(ShortformApp.INSTAGRAM, false)
        }
    }

    fun screenState(): Int {
        if (SystemClock.elapsedRealtime() - frameAt > 1500L || !frameKnown) return -1
        return if (valid) 1 else 0
    }

    private var serviceContext: Context? = null
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
    /** Swipe count of the most recent viewing run; survives the 15s idle reset for test inspection. */
    var lastRunCandidates = 0
    var lastScroll = 0L
    var candidateMs = 0L
    private var lastTick = 0L
    private var lastScan = 0L

    fun markers(): Set<String> {
        val positive = reels?.takeIf { it.isNotEmpty() } ?: DEFAULT_REELS_MARKERS
        val negatives = feed.orEmpty() + dm.orEmpty() + comments.orEmpty()
        return positive - negatives
    }

    fun sample(): Set<String>? =
        latest.takeIf { it.isNotEmpty() && SystemClock.elapsedRealtime() - latestAt < 60_000L }

    fun reset() {
        frameKnown = false
        frameAt = 0L
        latest = emptySet()
        latestAt = 0L
        currentInstagram = false
        reels = null
        feed = null
        dm = null
        comments = null
        events = 0
        candidates = 0
        lastRunCandidates = 0
        candidateMs = 0
        lastTick = 0
        lastScan = 0
        lastScroll = 0
        valid = false
        SwipeDynamicsTracker.reset(ShortformApp.INSTAGRAM)
    }

    fun dmScreen(): Boolean {
        val reference = dm ?: return false
        val distinct = reference - (reels ?: emptySet()) - (feed ?: emptySet()) - (comments ?: emptySet())
        return currentInstagram &&
                SystemClock.elapsedRealtime() - latestAt < 1500L &&
                distinct.size >= 2 &&
                (latest intersect distinct).size >= 2
    }

    fun tick(service: AccessibilityService) {
        serviceContext = service.applicationContext
        com.example.itv.composition.ReelsCalibrationConnection.initialize(service)
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan < 100L) return
        lastScan = now
        try {
            inspectFrame(service, now)
        } finally {
            val sameAppSafe = currentInstagram && nonReelsSince == 0L
            SwipeDynamicsTracker.onPresence(ShortformApp.INSTAGRAM, valid, now, sameAppSafe = sameAppSafe)
            ShortformPersonalStatsStore.onPresence(service, ShortformApp.INSTAGRAM, valid, now, sameAppSafe = sameAppSafe)
            com.example.itv.composition.shortformObservationService(service).presence(ShortformApp.INSTAGRAM, valid)
        }
    }

    private var nonReelsSince = 0L

    private fun inspectFrame(service: AccessibilityService, now: Long) {
        frameAt = now
        frameKnown = false
        val dt = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0L, 1000L)
        lastTick = now
        val previous = valid
        valid = false
        currentInstagram = false
        if (!com.example.itv.infrastructure.coaching.CoachingConsentStore.granted(service)) {
            status = "수집 동의 없음"
            return
        }
        val power = service.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val keyguard = service.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!power.isInteractive || keyguard.isKeyguardLocked) {
            candidates = 0
            nonReelsSince = 0L
            status = "화면 꺼짐 또는 잠금"
            return
        }
        try {
            val windows = service.windows.orEmpty()
            val w = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            val root = w?.root ?: run {
                status = "화면 판별 불가"
                return
            }
            try {
                val pkg = root.packageName?.toString()
                if (pkg != "com.instagram.android") {
                    frameKnown = !pkg.isNullOrBlank()
                    if (pkg == service.packageName) {
                        nonReelsSince = 0L
                    } else if (!pkg.isNullOrBlank()) {
                        if (nonReelsSince == 0L) nonReelsSince = now
                        if (now - nonReelsSince >= 15_000L) candidates = 0
                    }
                    status = "Instagram 전면 아님"
                    return
                }
                val ids = mutableSetOf<String>()
                var visited = 0
                var truncated = false

                fun walk(n: AccessibilityNodeInfo, depth: Int) {
                    if (!n.isVisibleToUser) return
                    if (++visited > 1500 || depth > 45) {
                        truncated = true
                        return
                    }
                    n.viewIdResourceName?.takeIf { it.startsWith("com.instagram.android:id/") }?.let {
                        ids.add(it)
                    }
                    for (i in 0 until n.childCount) {
                        if (visited >= 1500) {
                            truncated = true
                            break
                        }
                        val child = n.getChild(i) ?: continue
                        try {
                            walk(child, depth + 1)
                        } finally {
                            @Suppress("DEPRECATION")
                            child.recycle()
                        }
                    }
                }

                walk(root, 0)
                if (truncated && ids.isEmpty()) {
                    status = "검사 한도 초과 · 판별 불가"
                    return
                }
                latest = ids
                latestAt = now
                currentInstagram = true
                frameKnown = ids.isNotEmpty()

                val activeMarkers = markers()
                val matched = (ids intersect activeMarkers).size
                val isCalibrated = !reels.isNullOrEmpty()
                val minRequired = if (isCalibrated) 2 else 1

                val commentOnly = (comments ?: emptySet()) - (reels ?: emptySet()) - (feed ?: emptySet()) - (dm ?: emptySet())
                val commentScreen = commentOnly.isNotEmpty() && (ids intersect commentOnly).isNotEmpty()

                val dmActive = dmScreen()
                val keyboardActive = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                valid = activeMarkers.size >= minRequired &&
                        matched >= minRequired &&
                        !commentScreen &&
                        !dmActive &&
                        !keyboardActive

                if (valid) {
                    nonReelsSince = 0L
                } else if (dmActive || keyboardActive) {
                    if (nonReelsSince == 0L) nonReelsSince = now
                    if (now - nonReelsSince >= 15_000L) candidates = 0
                } else {
                    // Inside Instagram Feed / Stories / Profile: preserve candidates while continuously using Instagram
                    nonReelsSince = 0L
                }

                status = when {
                    ids.isEmpty() -> "ID 비공개 · 이 방법으로 식별 불가"
                    activeMarkers.size < minRequired -> "표본 부족 또는 구분 ID 부족 · 판별 불가"
                    valid -> "릴스 후보 · 일치 ID ${matched}개 (확정 아님)"
                    else -> "릴스 후보 미감지 또는 키보드 표시"
                }
                if (previous && valid) candidateMs += dt
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        } catch (_: Exception) {
            status = "화면 검사 실패 · 판별 불가"
        }
    }

    fun event(e: AccessibilityEvent?) {
        if (!valid || e?.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED || e.packageName?.toString() != "com.instagram.android") return
        events++
        val source = e.source ?: return
        try {
            val rect = Rect()
            source.getBoundsInScreen(rect)
            // source 자체에 ID가 없을 경우 부모 컨테이너의 ID까지 확인
            var id = source.viewIdResourceName
            if (id == null) {
                val parent = source.parent
                if (parent != null) {
                    try {
                        id = parent.viewIdResourceName
                    } finally {
                        @Suppress("DEPRECATION")
                        parent.recycle()
                    }
                }
            }
            val allowedIds = (reels ?: emptySet()) + DEFAULT_REELS_MARKERS
            val now = SystemClock.elapsedRealtime()
            val isDominantVertical = rect.height() >= 500 && rect.height() > rect.width()
            if (isDominantVertical && (id == null || id in allowedIds) && now - lastScroll >= 1200L) {
                candidates++
                lastRunCandidates = candidates
                lastScroll = now
                SwipeDynamicsTracker.onTransition(ShortformApp.INSTAGRAM, now)
                val ctx = serviceContext ?: return
                ShortformPersonalStatsStore.onTransition(ctx, ShortformApp.INSTAGRAM, now)
                com.example.itv.composition.shortformObservationService(ctx).transition(ShortformApp.INSTAGRAM)
                onTransition?.invoke()
            }
        } finally {
            @Suppress("DEPRECATION")
            source.recycle()
        }
    }
}