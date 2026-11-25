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
import com.example.itv.infrastructure.observation.accessibility.findActiveApplicationWindow
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore

/** Structure-only detection. Never reads message text, images or video identities. */
internal object ReelsProbe {
    // Main-thread callback: no broadcast, polling delay or persisted video identity.
    var onTransition: (() -> Unit)? = null
    private var frameAt = 0L
    private var frameKnown = false

    // 기본 릴스 식별 ID (표본 미수집 시 보조용 · clips 전용 ID)
    private val DEFAULT_REELS_MARKERS = setOf(
        "com.instagram.android:id/clips_viewer_view_pager",
        "com.instagram.android:id/clips_video_container",
        "com.instagram.android:id/clips_viewer_content_layout",
        "com.instagram.android:id/clips_item_overlay_component",
        "com.instagram.android:id/clips_author_info_component",
        "com.instagram.android:id/clips_ufi_component"
    )

    private val BOTTOM_TAB_IDS = setOf(
        "com.instagram.android:id/feed_tab",
        "com.instagram.android:id/search_tab",
        "com.instagram.android:id/clips_tab",
        "com.instagram.android:id/profile_tab",
        "com.instagram.android:id/direct_tab"
    )

    // ITV is a known non-Instagram foreground; preserve samples and counts.
    fun onOwnApp() {
        frameAt = SystemClock.elapsedRealtime()
        frameKnown = true
        valid = false
        currentInstagram = false
        lastTick = frameAt
        status = "ITV 내부 · 탐색 생략"
        InstagramTracker.onReturnedToItv(frameAt, System.currentTimeMillis())
        appContext?.let { ctx ->
            com.example.itv.composition.shortformObservationService(ctx).presence(ShortformApp.INSTAGRAM, false)
        }
    }

    fun screenState(): Int {
        if (SystemClock.elapsedRealtime() - frameAt > 4000L || !frameKnown) return -1
        return if (valid) 1 else 0
    }

    private var appContext: Context? = null
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

    fun resetSwipeCounts() {
        candidates = 0
        lastRunCandidates = 0
        SwipeDynamicsTracker.reset(ShortformApp.INSTAGRAM)
    }

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
        InstagramTracker.clearHistory()
    }

    private fun calibratedDmMatch(ids: Set<String> = latest): Boolean {
        val reference = dm ?: return false
        val distinct = reference - (reels ?: emptySet()) - (feed ?: emptySet()) - (comments ?: emptySet())
        return distinct.size >= 2 && (ids intersect distinct).size >= 2
    }

    fun dmScreen(): Boolean {
        if (!currentInstagram || SystemClock.elapsedRealtime() - latestAt >= 2200L) return false
        if (InstagramTracker.isDmActive()) return true
        return calibratedDmMatch(latest)
    }

    fun tick(service: AccessibilityService) {
        appContext = service.applicationContext
        com.example.itv.composition.ReelsCalibrationConnection.initialize(service)
        val now = SystemClock.elapsedRealtime()
        val minIntervalMs = if (InstagramTracker.isPowerSaveScanEligible(now)) 1350L else 100L
        if (now - lastScan < minIntervalMs) return
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
        val dt = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0L, 1600L)
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
            InstagramTracker.onExternalApp(now, System.currentTimeMillis())
            status = "화면 꺼짐 또는 잠금"
            return
        }
        try {
            val windows = service.windows.orEmpty()
            val w = findActiveApplicationWindow(windows)
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
                        InstagramTracker.onReturnedToItv(now, System.currentTimeMillis())
                    } else if (!pkg.isNullOrBlank()) {
                        if (nonReelsSince == 0L) nonReelsSince = now
                        if (now - nonReelsSince >= 15_000L) candidates = 0
                        InstagramTracker.onExternalApp(now, System.currentTimeMillis())
                    }
                    status = "Instagram 전면 아님"
                    return
                }
                val ids = mutableSetOf<String>()
                var selectedTabId: String? = null
                var visited = 0
                var truncated = false

                fun walk(n: AccessibilityNodeInfo, depth: Int, commentDepth: Int) {
                    if (!n.isVisibleToUser) return
                    if (++visited > 1500 || depth > 45) {
                        truncated = true
                        return
                    }
                    val id = n.viewIdResourceName?.takeIf { it.startsWith("com.instagram.android:id/") }
                    if (id != null) {
                        ids.add(id)
                        if (id in BOTTOM_TAB_IDS && n.isSelected) {
                            selectedTabId = id
                        }
                    }

                    // 댓글 리스트 컨테이너 내부의 수백 개 댓글 아이템 하위 노드는 깊이 2 이상 탐색 생략 (스크롤 성능 및 배터리 보호)
                    val isCommentList = id == "com.instagram.android:id/comments_list" ||
                        id == "com.instagram.android:id/caption_and_comments_list"
                    val nextCommentDepth = when {
                        isCommentList -> 1
                        commentDepth > 0 -> commentDepth + 1
                        else -> 0
                    }
                    if (nextCommentDepth >= 2) return

                    for (i in 0 until n.childCount) {
                        if (visited >= 1500) {
                            truncated = true
                            break
                        }
                        val child = n.getChild(i) ?: continue
                        try {
                            walk(child, depth + 1, nextCommentDepth)
                        } finally {
                            @Suppress("DEPRECATION")
                            child.recycle()
                        }
                    }
                }

                walk(root, 0, 0)
                if (truncated && ids.isEmpty()) {
                    status = "검사 한도 초과 · 판별 불가"
                    return
                }
                latest = ids
                latestAt = now
                currentInstagram = true
                frameKnown = ids.isNotEmpty() || selectedTabId != null

                val activeMarkers = markers()
                val matched = (ids intersect activeMarkers).size
                val isCalibrated = !reels.isNullOrEmpty()
                val minRequired = if (isCalibrated) 2 else 1

                val commentOnly = (comments ?: emptySet()) - (reels ?: emptySet()) - (feed ?: emptySet()) - (dm ?: emptySet())
                val calibratedComment = commentOnly.isNotEmpty() && (ids intersect commentOnly).isNotEmpty()

                val feedOnly = (feed ?: emptySet()) - (reels ?: emptySet()) - (dm ?: emptySet()) - (comments ?: emptySet())
                val calibratedFeed = feedOnly.size >= 2 && (ids intersect feedOnly).size >= 2

                val calibratedDm = calibratedDmMatch(ids)
                val calibratedReels = activeMarkers.size >= minRequired && matched >= minRequired

                val keyboardActive = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

                val igMode = InstagramTracker.classifyScreenMode(
                    ids = ids,
                    selectedTabId = selectedTabId,
                    calibratedReelsMatch = calibratedReels,
                    calibratedDmMatch = calibratedDm,
                    calibratedCommentMatch = calibratedComment,
                    calibratedFeedMatch = calibratedFeed
                )

                InstagramTracker.onObserve(
                    nowElapsed = now,
                    nowWall = System.currentTimeMillis(),
                    rawMode = igMode,
                    reelsSwipeCount = candidates,
                    matchedIdCount = matched,
                    keyboardActive = keyboardActive
                )

                val dmActive = igMode == InstagramScreenMode.DM || calibratedDm
                val commentScreen = igMode == InstagramScreenMode.COMMENTS || calibratedComment

                valid = (igMode == InstagramScreenMode.REELS || calibratedReels) &&
                        igMode != InstagramScreenMode.STORY &&
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
                    ids.isEmpty() && selectedTabId == null -> "ID 비공개 · 이 방법으로 식별 불가"
                    valid -> "인스타 릴스 시청 중 · 일치 ID ${matched}개"
                    else -> "${igMode.label} (${if (keyboardActive) "키보드 활성" else "비-릴스 화면"})"
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
            val id = source.viewIdResourceName ?: source.parent?.let { parent ->
                try {
                    parent.viewIdResourceName
                } finally {
                    @Suppress("DEPRECATION")
                    parent.recycle()
                }
            }
            val allowedIds = (reels ?: emptySet()) + DEFAULT_REELS_MARKERS + setOf("com.instagram.android:id/reel_viewer_root")
            val now = SystemClock.elapsedRealtime()
            val isDominantVertical = rect.height() >= 500 && rect.height() > rect.width()
            if (isDominantVertical && (id == null || id in allowedIds) && now - lastScroll >= 1200L) {
                candidates++
                lastRunCandidates = candidates
                lastScroll = now
                InstagramTracker.onReelsSwipe(candidates, System.currentTimeMillis())
                SwipeDynamicsTracker.onTransition(ShortformApp.INSTAGRAM, now)
                val ctx = appContext ?: return
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