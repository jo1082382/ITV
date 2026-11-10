package com.example.itv.infrastructure.observation.youtube

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.itv.domain.records.ShortformApp
import com.example.itv.infrastructure.observation.SwipeDynamicsTracker
import com.example.itv.infrastructure.observation.accessibility.findActiveApplicationWindow
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore

/** Row-position diagnostic only. No content, image or video-identity access. */
internal object YoutubeProbe {
    var onTransition: (() -> Unit)? = null
    var coachingObservation = false
    private var appContext: Context? = null
    private var stateAt = 0L
    private var state = -1
    // 1 shorts, 0 another app, -1 unknown or YouTube non-shorts (conservative hold).
    // Clear comparison baseline on exit, never count ITV entry as a transition.
    fun onOwnApp() {
        stateAt = SystemClock.elapsedRealtime()
        state = 0
        clearBaseline()
        status = "ITV 내부 · 탐색 생략 · 횟수/로그 보존"
        appContext?.let { ctx ->
            com.example.itv.composition.shortformObservationService(ctx).presence(ShortformApp.YOUTUBE, false)
        }
    }

    fun screenState(): Int = if (SystemClock.elapsedRealtime() - stateAt <= 1500L) state else -1
    var enabled = false
    var status = "감시 중지"
    var candidates = 0
    /** Swipe count of the most recent viewing run; survives the 15s idle reset for test inspection. */
    var lastRunCandidates = 0
    var row: Int? = null
    var detail = "관측 대기"
    private var pending: Int? = null
    private var pendingAt = 0L
    private var previousAt = 0L
    private var lastScan = 0L
    private val log = java.util.ArrayDeque<String>()

    private val SHORTS_SURFACE_IDS = setOf(
        "com.google.android.youtube:id/reel_recycler",
        "com.google.android.youtube:id/reel_watch_fragment_root",
        "com.google.android.youtube:id/shorts_container"
    )

    private val SHORTS_PAGE_IDS = setOf(
        "com.google.android.youtube:id/reel_player_page_container",
        "com.google.android.youtube:id/reel_player_view"
    )

    fun clearBaseline() {
        row = null
        pending = null
        previousAt = 0L
    }

    fun reset() {
        candidates = 0
        lastRunCandidates = 0
        clearBaseline()
        log.clear()
        detail = "초기화됨"
        SwipeDynamicsTracker.reset(ShortformApp.YOUTUBE)
    }

    private fun record(s: String) {
        log.addLast("${SystemClock.elapsedRealtime()}ms$s")
        while (log.size > 60) log.removeFirst()
    }

    fun report(): String =
        "$status\n현재 항목 위치: $row\n전환 후보: $candidates\n$detail\n" + log.joinToString("\n")

    private fun unavailable(s: String) {
        status = s
        clearBaseline()
    }

    private var nonShortsSince = 0L

    fun tick(service: AccessibilityService) {
        appContext = service.applicationContext
        if (!com.example.itv.infrastructure.coaching.CoachingConsentStore.granted(service)) {
            com.example.itv.composition.shortformObservationService(service).presence(ShortformApp.YOUTUBE, false)
            unavailable("수집 동의 없음")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan < 100L) return
        lastScan = now
        stateAt = now
        state = -1
        try {
            val power = service.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val lock = service.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (!power.isInteractive || lock.isKeyguardLocked) {
                candidates = 0
                nonShortsSince = 0L
                unavailable("화면 꺼짐·잠금")
                return
            }
            try {
                val windows = service.windows.orEmpty()
                if (windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                    unavailable("키보드 표시 · 비교 보류")
                    return
                }
                val root = findActiveApplicationWindow(windows)?.root ?: run {
                    unavailable("현재 화면 판별 불가")
                    return
                }
                try {
                    val pkg = root.packageName?.toString()
                    if (pkg != "com.google.android.youtube") {
                        state = if (pkg.isNullOrBlank()) -1 else 0
                        if (pkg == service.packageName) {
                            nonShortsSince = 0L
                        } else if (!pkg.isNullOrBlank()) {
                            if (nonShortsSince == 0L) nonShortsSince = now
                            if (now - nonShortsSince >= 15_000L) candidates = 0
                        }
                        unavailable("YouTube 전면 아님")
                        return
                    }
                    val viewport = Rect()
                    root.getBoundsInScreen(viewport)
                    var panel = false
                    var recycler = false
                    var dominantShortsSurface = false
                    var visited = 0
                    var truncated = false
                    val pages = mutableListOf<Pair<Int, Long>>()

                    fun walk(n: AccessibilityNodeInfo, depth: Int, inheritedRow: Int?) {
                        if (!n.isVisibleToUser) return
                        if (++visited > 1500 || depth > 45) {
                            truncated = true
                            return
                        }
                        val id = n.viewIdResourceName
                        val nodeRow = n.collectionItemInfo?.rowIndex ?: inheritedRow

                        if (id == "com.google.android.youtube:id/app_engagement_panel") {
                            panel = true
                        }
                        if (id in SHORTS_SURFACE_IDS) {
                            recycler = true
                            val r = Rect()
                            n.getBoundsInScreen(r)
                            if (r.intersect(viewport) &&
                                r.width() >= viewport.width() * 0.8 &&
                                r.height() >= viewport.height() * 0.70
                            ) {
                                dominantShortsSurface = true
                            }
                        }
                        if (id in SHORTS_PAGE_IDS) {
                            val r = Rect()
                            n.getBoundsInScreen(r)
                            if (r.intersect(viewport) &&
                                r.width() >= viewport.width() * 0.8 &&
                                r.height() >= viewport.height() * 0.70
                            ) {
                                dominantShortsSurface = true
                                if (nodeRow != null && nodeRow >= 0) {
                                    pages.add(nodeRow to r.width().toLong() * r.height())
                                }
                            }
                        }
                        for (i in 0 until n.childCount) {
                            if (visited >= 1500) {
                                truncated = true
                                break
                            }
                            val child = n.getChild(i) ?: continue
                            try {
                                walk(child, depth + 1, nodeRow)
                            } finally {
                                @Suppress("DEPRECATION")
                                child.recycle()
                            }
                        }
                    }

                    walk(root, 0, null)

                    if (panel) {
                        if (nonShortsSince == 0L) nonShortsSince = now
                        state = if (now - nonShortsSince < 15_000L && previousAt != 0L && now - previousAt <= 15_000L) {
                            1
                        } else {
                            0
                        }
                        status = "댓글·패널 표시 · 시청 유지"
                        return
                    }
                    if (!recycler && !dominantShortsSurface) {
                        // Inside YouTube (Home / Feed / Long-form): preserve candidates while continuously using YouTube
                        state = 0
                        if (truncated) unavailable("검사 한도 초과 · 비교 제외")
                        else unavailable("쇼츠 플레이어 미감지 (롱폼/홈)")
                        return
                    }

                    nonShortsSince = 0L
                    // 쇼츠 플레이어 화면이 활성화된 상태이므로 스와이프 중에도 시청 상태(state = 1)를 유지함
                    state = 1

                    if (previousAt != 0L && now - previousAt > 2000L) clearBaseline()
                    previousAt = now

                    val sorted = pages.sortedByDescending { it.second }
                    if (sorted.isEmpty()) {
                        pending = null
                        status = "페이지 이동 중 또는 항목 위치 비공개 (시청 유지)"
                        return
                    }
                    if (sorted.size > 1 && sorted[1].second >= sorted[0].second * 0.8) {
                        pending = null
                        status = "페이지 겹침 · 안정화 대기 (시청 유지)"
                        return
                    }

                    val index = sorted[0].first
                    detail = "row=$index · 유효 페이지 ${pages.size} · 안정화 180ms 이상 확인"
                    if (pending != index) {
                        pending = index
                        pendingAt = now
                        status = "새 위치 확인 중"
                        return
                    }
                    if (now - pendingAt < 180L) {
                        status = "위치 안정화 대기"
                        return
                    }
                    val before = row
                    row = index
                    status = "쇼츠 항목 위치 관측 중"
                    if (before == null) {
                        record("기준 위치 $index (진입은 전환으로 세지 않음)")
                    } else if (before != index) {
                        candidates++
                        lastRunCandidates = candidates
                        SwipeDynamicsTracker.onTransition(ShortformApp.YOUTUBE, now)
                        ShortformPersonalStatsStore.onTransition(service, ShortformApp.YOUTUBE, now)
                        record("전환 후보 $before → $index · 합계 $candidates")
                        com.example.itv.composition.shortformObservationService(service).transition(ShortformApp.YOUTUBE)
                        onTransition?.invoke()
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            } catch (_: Exception) {
                unavailable("구조 검사 실패 · 비교 보류")
            }
        } finally {
            val sameAppSafe = state >= 0 && nonShortsSince == 0L
            SwipeDynamicsTracker.onPresence(ShortformApp.YOUTUBE, state == 1, now, sameAppSafe = sameAppSafe)
            ShortformPersonalStatsStore.onPresence(service, ShortformApp.YOUTUBE, state == 1, now, sameAppSafe = sameAppSafe)
            com.example.itv.composition.shortformObservationService(service).presence(ShortformApp.YOUTUBE, state == 1)
        }
    }
}