package com.example.itv.infrastructure.observation.instagram

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Instagram 세부 화면 모드
 */
enum class InstagramScreenMode(val label: String) {
    UNKNOWN("판별 대기"),
    HOME_FEED("인스타 홈 / 피드"),
    REELS("인스타 릴스 시청"),
    COMMENTS("인스타 댓글 열람"),
    STORY("인스타 스토리 시청"),
    DM("인스타 DM / 메시지"),
    EXPLORE("인스타 검색 / 탐색"),
    PROFILE("인스타 프로필"),
    BACKGROUND("백그라운드 / 외부 화면")
}

/**
 * Instagram 실시간 관측 스냅샷
 */
data class InstagramSnapshot(
    val mode: InstagramScreenMode = InstagramScreenMode.UNKNOWN,
    val isInInstagram: Boolean = false,
    val isReelsActive: Boolean = false,
    val isDmProtected: Boolean = false,
    val isPowerSaveScanMode: Boolean = false,
    val reelsWatchDurationMs: Long = 0L,
    val currentModeDurationMs: Long = 0L,
    val reelsSwipeCount: Int = 0,
    val matchedIdCount: Int = 0,
    val lastDetectedAtWallMs: Long = 0L,
    val detail: String = "인스타그램 관측 대기 중"
)

/**
 * Instagram 화면 전환 및 앱 복귀 이벤트 기록
 */
data class InstagramTransitionEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampWallMs: Long = System.currentTimeMillis(),
    val eventType: String,
    val fromMode: InstagramScreenMode,
    val toMode: InstagramScreenMode,
    val reelsWatchDurationMs: Long,
    val modeDurationMs: Long,
    val reelsSwipeCount: Int,
    val description: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(timestampWallMs))
}

/**
 * Instagram 화면 상태(홈/피드, 릴스, 댓글, 스토리, DM, 검색/탐색, 프로필) 실시간 판별,
 * 체류/시청 시간 누적, 전환 이벤트 기록 및 절전 스캔 판정을 담당하는 싱글톤 트래커.
 */
object InstagramTracker {

    private enum class IgAppLocation {
        UNKNOWN,
        INSTAGRAM,
        ITV,
        HOME_OR_OTHER
    }

    private val _snapshot = MutableStateFlow(InstagramSnapshot())
    val snapshot: StateFlow<InstagramSnapshot> = _snapshot.asStateFlow()

    private val _history = MutableStateFlow<List<InstagramTransitionEvent>>(emptyList())
    val history: StateFlow<List<InstagramTransitionEvent>> = _history.asStateFlow()

    var lastSessionEvent: InstagramTransitionEvent? = null
        private set

    var onInstagramTransition: ((InstagramTransitionEvent) -> Unit)? = null

    private var currentLocation: IgAppLocation = IgAppLocation.UNKNOWN
    private var lastMode: InstagramScreenMode = InstagramScreenMode.UNKNOWN
    private var lastInstagramMode: InstagramScreenMode = InstagramScreenMode.UNKNOWN

    // 누적 시간 추적 (3시간 이내 동일 세션 유지)
    private var accumulatedReelsWatchMs: Long = 0L
    private var currentModeDurationMs: Long = 0L
    private var lastTickElapsed: Long = 0L
    private var lastSessionWallMs: Long = 0L
    private var lastUserInteractionElapsed: Long = 0L

    // 전환 디바운스
    private var pendingMode: InstagramScreenMode? = null
    private var pendingModeFirstSeenElapsed: Long = 0L

    // 앱 간 전환 추적
    private var cameFromItv: Boolean = false
    private var hasUnreportedInstagramSession: Boolean = false
    private var pendingHomeExitElapsed: Long = 0L
    private var homeExitLogged: Boolean = false
    private var lastHomeExitEventId: String? = null

    private const val MAX_HISTORY = 100
    private const val MODE_DEBOUNCE_MS = 250L
    private const val HOME_EXIT_DEBOUNCE_MS = 1200L
    private const val IDLE_POWER_SAVE_MS = 2500L
    private const val SESSION_EXPIRE_WALL_MS = 3L * 60L * 60L * 1000L

    @Synchronized
    fun onUserInteraction(nowElapsed: Long = SystemClock.elapsedRealtime()) {
        lastUserInteractionElapsed = nowElapsed
    }

    @Synchronized
    fun isCurrentlyInInstagram(): Boolean = currentLocation == IgAppLocation.INSTAGRAM

    @Synchronized
    fun isDmActive(): Boolean =
        currentLocation == IgAppLocation.INSTAGRAM && _snapshot.value.mode == InstagramScreenMode.DM

    /**
     * 현재 인스타그램에서 릴스, 댓글, 또는 스토리를 시청/열람 중인지 여부 (중간 개입 보류 대상).
     */
    @Synchronized
    fun isActivelyInReelsCommentsOrStory(): Boolean {
        if (currentLocation != IgAppLocation.INSTAGRAM) return false
        val m = _snapshot.value.mode
        return m == InstagramScreenMode.REELS ||
            m == InstagramScreenMode.COMMENTS ||
            m == InstagramScreenMode.STORY
    }

    /**
     * 인스타그램 정적 화면(홈/피드, 댓글, DM, 프로필 등)에서 2.5초 이상 터치/스크롤이 없을 때 절전 스캔 허용.
     */
    @Synchronized
    fun isPowerSaveScanEligible(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean {
        if (currentLocation != IgAppLocation.INSTAGRAM) return false
        val idleMs = nowElapsed - lastUserInteractionElapsed
        val m = lastMode
        val isStaticScreen = m == InstagramScreenMode.HOME_FEED ||
            m == InstagramScreenMode.COMMENTS ||
            m == InstagramScreenMode.DM ||
            m == InstagramScreenMode.EXPLORE ||
            m == InstagramScreenMode.PROFILE
        return isStaticScreen && idleMs >= IDLE_POWER_SAVE_MS
    }

    /**
     * 수집된 `com.instagram.android:id/...` 리소스 ID 집합과 하단 탭 선택 상태, 표본(calibration) 결과를 종합하여
     * 인스타그램 세부 화면 모드를 분류합니다.
     */
    fun classifyScreenMode(
        ids: Set<String>,
        selectedTabId: String?,
        calibratedReelsMatch: Boolean,
        calibratedDmMatch: Boolean,
        calibratedCommentMatch: Boolean,
        calibratedFeedMatch: Boolean
    ): InstagramScreenMode {
        if (ids.isEmpty() && selectedTabId == null) return InstagramScreenMode.UNKNOWN

        val shortIds = ids.mapTo(HashSet(ids.size)) {
            it.removePrefix("com.instagram.android:id/")
        }
        val shortTab = selectedTabId?.removePrefix("com.instagram.android:id/")

        // 1. DM / 메시지 화면 판별 (최우선 보호)
        val hasDmId = calibratedDmMatch ||
            shortTab == "direct_tab" ||
            shortIds.any { id ->
                id.startsWith("row_thread_") ||
                    id.startsWith("direct_thread") ||
                    id.startsWith("direct_inbox") ||
                    id.startsWith("inbox_refreshable") ||
                    id.startsWith("row_inbox_") ||
                    id == "message_list" ||
                    id == "thread_list" ||
                    id == "thread_title" ||
                    id.startsWith("direct_private_share") ||
                    id.startsWith("direct_composer")
            }
        if (hasDmId) return InstagramScreenMode.DM

        // 2. 댓글 열람 화면 (릴스 댓글 / 피드 게시물 댓글 시트)
        val hasCommentId = calibratedCommentMatch ||
            shortIds.any { id ->
                id.startsWith("layout_comment_thread") ||
                    id.startsWith("comment_composer") ||
                    id.startsWith("row_comment_") ||
                    id == "comments_list" ||
                    id == "caption_and_comments_list"
            }
        if (hasCommentId) return InstagramScreenMode.COMMENTS

        // 3. 릴스(Clips) 전용 뷰어 ID 존재 여부 확인
        val hasClipsViewerId = shortIds.any { id ->
            id == "clips_viewer_view_pager" ||
                id == "clips_video_container" ||
                id == "clips_viewer_content_layout" ||
                id.startsWith("clips_item_overlay") ||
                id.startsWith("clips_author_info") ||
                id.startsWith("clips_ufi_")
        }

        // 4. 스토리(Stories) 뷰어 판별
        // 인스타그램 내부에서 스토리는 'reel_viewer_*' ID를 사용하며, 릴스는 'clips_*' ID를 사용함.
        val hasExplicitStoryId = shortIds.any { id ->
            id.startsWith("reel_viewer_header") ||
                id.startsWith("reel_viewer_progress") ||
                id.startsWith("reel_viewer_segment") ||
                id.startsWith("reel_viewer_toolbar") ||
                id.startsWith("reel_viewer_message_composer") ||
                id.startsWith("reel_viewer_media") ||
                id.startsWith("reel_viewer_text") ||
                id.startsWith("story_viewer") ||
                id.startsWith("story_progress")
        }
        val hasTabBar = "tab_bar" in shortIds || shortTab != null
        val isRootStoryViewer = "reel_viewer_root" in shortIds && !hasClipsViewerId && !hasTabBar
        if (hasExplicitStoryId || isRootStoryViewer) {
            return InstagramScreenMode.STORY
        }

        // 5. 릴스(Reels) 시청 화면 판별
        if (hasClipsViewerId || shortTab == "clips_tab" || calibratedReelsMatch) {
            return InstagramScreenMode.REELS
        }

        // 6. 검색 / 탐색(Explore) 화면 판별
        val hasExploreId = shortTab == "search_tab" ||
            shortIds.any { id ->
                id == "action_bar_search_edit_text" ||
                    id == "search_edit_text" ||
                    id.startsWith("explore_grid") ||
                    id.startsWith("search_row_")
            }
        if (hasExploreId) return InstagramScreenMode.EXPLORE

        // 7. 프로필 화면 판별
        val hasProfileId = shortTab == "profile_tab" ||
            shortIds.any { id ->
                id.startsWith("profile_header") ||
                    id.startsWith("row_profile_header") ||
                    id.startsWith("profile_action_bar") ||
                    id.startsWith("profile_tab_") ||
                    id == "profile_viewpager"
            }
        if (hasProfileId) return InstagramScreenMode.PROFILE

        // 8. 홈 / 피드 화면 판별 (기본 메인 화면 포함)
        val hasFeedId = shortTab == "feed_tab" ||
            calibratedFeedMatch ||
            shortIds.any { id ->
                id.startsWith("row_feed_") ||
                    id.startsWith("feed_tray") ||
                    id == "tray_recycler_view" ||
                    id.startsWith("main_feed") ||
                    id == "action_bar_inbox_button" ||
                    id == "title_logo"
            }
        if (hasFeedId || shortIds.isNotEmpty()) {
            return InstagramScreenMode.HOME_FEED
        }

        return InstagramScreenMode.UNKNOWN
    }

    @Synchronized
    fun onObserve(
        nowElapsed: Long,
        nowWall: Long,
        rawMode: InstagramScreenMode,
        reelsSwipeCount: Int,
        matchedIdCount: Int,
        keyboardActive: Boolean
    ) {
        if (lastSessionWallMs > 0L && (nowWall - lastSessionWallMs) > SESSION_EXPIRE_WALL_MS) {
            accumulatedReelsWatchMs = 0L
            currentModeDurationMs = 0L
        }
        lastSessionWallMs = nowWall

        val prevLocation = currentLocation
        val prevMode = lastMode
        val prevIgMode = lastInstagramMode

        // 순간적인 프레임 전환 노이즈 방지용 짧은 디바운스 (단, DM/댓글/릴스 진입은 즉각 반영)
        val isImmediatePriority = rawMode == InstagramScreenMode.DM ||
            rawMode == InstagramScreenMode.COMMENTS ||
            rawMode == InstagramScreenMode.REELS ||
            rawMode == InstagramScreenMode.STORY ||
            prevLocation != IgAppLocation.INSTAGRAM

        val effectiveMode = if (isImmediatePriority || prevMode == InstagramScreenMode.UNKNOWN || rawMode == prevMode) {
            pendingMode = null
            rawMode
        } else {
            if (pendingMode != rawMode) {
                pendingMode = rawMode
                pendingModeFirstSeenElapsed = nowElapsed
                prevMode
            } else if (nowElapsed - pendingModeFirstSeenElapsed >= MODE_DEBOUNCE_MS) {
                pendingMode = null
                rawMode
            } else {
                prevMode
            }
        }

        // 0. 외부 앱(우리 앱 ITV 또는 홈 화면)에서 인스타그램으로 진입/복귀 감지
        if (prevLocation != IgAppLocation.INSTAGRAM) {
            lastTickElapsed = 0L
            currentModeDurationMs = 0L
            lastUserInteractionElapsed = nowElapsed

            if (prevLocation == IgAppLocation.ITV || cameFromItv) {
                val reelsSuffix = if (accumulatedReelsWatchMs > 0L) " (누적 릴스 ${accumulatedReelsWatchMs / 1000L}초)" else ""
                val event = InstagramTransitionEvent(
                    timestampWallMs = nowWall,
                    eventType = "ITV_TO_IG",
                    fromMode = InstagramScreenMode.BACKGROUND,
                    toMode = effectiveMode,
                    reelsWatchDurationMs = accumulatedReelsWatchMs,
                    modeDurationMs = 0L,
                    reelsSwipeCount = reelsSwipeCount,
                    description = "우리 앱(ITV) → 인스타그램 복귀: ${effectiveMode.label}$reelsSuffix"
                )
                addEvent(event)
            } else if (prevLocation == IgAppLocation.HOME_OR_OTHER && homeExitLogged) {
                val reelsSuffix = if (accumulatedReelsWatchMs > 0L) " (누적 릴스 ${accumulatedReelsWatchMs / 1000L}초)" else ""
                val event = InstagramTransitionEvent(
                    timestampWallMs = nowWall,
                    eventType = "ENTERED_IG",
                    fromMode = InstagramScreenMode.BACKGROUND,
                    toMode = effectiveMode,
                    reelsWatchDurationMs = accumulatedReelsWatchMs,
                    modeDurationMs = 0L,
                    reelsSwipeCount = reelsSwipeCount,
                    description = "홈/외부 화면 → 인스타그램 진입: ${effectiveMode.label}$reelsSuffix"
                )
                addEvent(event)
            }

            currentLocation = IgAppLocation.INSTAGRAM
            cameFromItv = false
            pendingHomeExitElapsed = 0L
            homeExitLogged = false
            lastHomeExitEventId = null
            hasUnreportedInstagramSession = true
        }

        // 1. 시간 델타 계산 (릴스 시청 시간 & 현재 화면 체류 시간)
        val deltaMs = if (lastTickElapsed > 0L) {
            (nowElapsed - lastTickElapsed).coerceIn(0L, 3500L)
        } else {
            0L
        }
        lastTickElapsed = nowElapsed

        val effectivePrevMode = if (prevLocation != IgAppLocation.INSTAGRAM) prevIgMode else prevMode
        if (effectiveMode != effectivePrevMode && prevLocation == IgAppLocation.INSTAGRAM) {
            val prevModeStayMs = currentModeDurationMs
            currentModeDurationMs = 0L

            val desc = when (effectiveMode) {
                InstagramScreenMode.REELS -> {
                    if (effectivePrevMode == InstagramScreenMode.COMMENTS) {
                        "인스타 댓글 닫기 → 릴스(Reels) 시청 복귀 (누적 ${accumulatedReelsWatchMs / 1000L}초)"
                    } else {
                        "${effectivePrevMode.label} → 인스타 릴스(Reels) 시청 진입"
                    }
                }
                InstagramScreenMode.COMMENTS ->
                    "${effectivePrevMode.label} → 인스타 댓글 열람 진입"
                InstagramScreenMode.STORY ->
                    "${effectivePrevMode.label} → 인스타 스토리(Story) 시청 진입"
                InstagramScreenMode.DM ->
                    "${effectivePrevMode.label} → 인스타 DM(메시지) 진입 (개입 자동 보호)"
                InstagramScreenMode.HOME_FEED -> {
                    if (effectivePrevMode == InstagramScreenMode.REELS) {
                        "인스타 릴스 종료 → 홈/피드 이동 (누적 릴스 ${accumulatedReelsWatchMs / 1000L}초 · 스와이프 ${reelsSwipeCount}회)"
                    } else if (effectivePrevMode == InstagramScreenMode.STORY) {
                        "인스타 스토리 종료 → 홈/피드 복귀 (${prevModeStayMs / 1000L}초 시청)"
                    } else {
                        "${effectivePrevMode.label} → 인스타 홈/피드 이동"
                    }
                }
                InstagramScreenMode.EXPLORE ->
                    "${effectivePrevMode.label} → 인스타 검색/탐색 탭 이동"
                InstagramScreenMode.PROFILE ->
                    "${effectivePrevMode.label} → 인스타 프로필 화면 이동"
                else ->
                    "${effectivePrevMode.label} → ${effectiveMode.label}"
            }

            val eventType = when (effectiveMode) {
                InstagramScreenMode.REELS -> "IG_REELS"
                InstagramScreenMode.COMMENTS -> "IG_COMMENTS"
                InstagramScreenMode.STORY -> "IG_STORY"
                InstagramScreenMode.DM -> "IG_DM"
                InstagramScreenMode.HOME_FEED -> "IG_HOME_FEED"
                InstagramScreenMode.EXPLORE -> "IG_EXPLORE"
                InstagramScreenMode.PROFILE -> "IG_PROFILE"
                else -> "IG_MODE_CHANGE"
            }

            val event = InstagramTransitionEvent(
                timestampWallMs = nowWall,
                eventType = eventType,
                fromMode = effectivePrevMode,
                toMode = effectiveMode,
                reelsWatchDurationMs = accumulatedReelsWatchMs,
                modeDurationMs = prevModeStayMs,
                reelsSwipeCount = reelsSwipeCount,
                description = desc
            )
            addEvent(event)

            // 릴스/스토리/댓글을 보다가 홈/피드나 탐색 탭으로 나온 경우 대기 중인 개입 트리거 허용
            val exitedVideoToFeed = (effectivePrevMode == InstagramScreenMode.REELS ||
                effectivePrevMode == InstagramScreenMode.STORY ||
                effectivePrevMode == InstagramScreenMode.COMMENTS) &&
                (effectiveMode == InstagramScreenMode.HOME_FEED || effectiveMode == InstagramScreenMode.EXPLORE)
            if (exitedVideoToFeed) {
                onInstagramTransition?.invoke(event)
            }
        } else {
            currentModeDurationMs += deltaMs
        }

        if (effectiveMode == InstagramScreenMode.REELS && !keyboardActive) {
            accumulatedReelsWatchMs += deltaMs
        }

        lastMode = effectiveMode
        lastInstagramMode = effectiveMode

        val powerSave = isPowerSaveScanEligible(nowElapsed)
        val scanSuffix = if (powerSave) " · 절전 스캔(1.5초)" else ""
        val dmProtected = effectiveMode == InstagramScreenMode.DM || keyboardActive

        val detailMsg = when (effectiveMode) {
            InstagramScreenMode.REELS ->
                "릴스 시청 중 · 누적 ${accumulatedReelsWatchMs / 1000L}초 · 스와이프 ${reelsSwipeCount}회"
            InstagramScreenMode.COMMENTS ->
                "인스타 댓글 열람 중 (${currentModeDurationMs / 1000L}초 · 개입 보류)$scanSuffix"
            InstagramScreenMode.STORY ->
                "인스타 스토리 시청 중 (${currentModeDurationMs / 1000L}초 · 개입 보류)"
            InstagramScreenMode.DM ->
                "인스타 DM 대화/목록 (${currentModeDurationMs / 1000L}초 · 개입 차단 보호 중)$scanSuffix"
            InstagramScreenMode.HOME_FEED ->
                "인스타 홈/피드 탐색 중 (${currentModeDurationMs / 1000L}초)$scanSuffix"
            InstagramScreenMode.EXPLORE ->
                "인스타 검색/탐색 탭 (${currentModeDurationMs / 1000L}초)$scanSuffix"
            InstagramScreenMode.PROFILE ->
                "인스타 프로필 확인 중 (${currentModeDurationMs / 1000L}초)$scanSuffix"
            else ->
                "인스타그램 화면 판별 중"
        }

        _snapshot.value = InstagramSnapshot(
            mode = effectiveMode,
            isInInstagram = true,
            isReelsActive = effectiveMode == InstagramScreenMode.REELS && !keyboardActive,
            isDmProtected = dmProtected,
            isPowerSaveScanMode = powerSave,
            reelsWatchDurationMs = accumulatedReelsWatchMs,
            currentModeDurationMs = currentModeDurationMs,
            reelsSwipeCount = reelsSwipeCount,
            matchedIdCount = matchedIdCount,
            lastDetectedAtWallMs = nowWall,
            detail = detailMsg
        )
    }

    /**
     * 릴스 스와이프 전환 발생 시 이벤트 기록
     */
    @Synchronized
    fun onReelsSwipe(reelsSwipeCount: Int, nowWall: Long = System.currentTimeMillis()) {
        val event = InstagramTransitionEvent(
            timestampWallMs = nowWall,
            eventType = "IG_REELS_SWIPE",
            fromMode = InstagramScreenMode.REELS,
            toMode = InstagramScreenMode.REELS,
            reelsWatchDurationMs = accumulatedReelsWatchMs,
            modeDurationMs = currentModeDurationMs,
            reelsSwipeCount = reelsSwipeCount,
            description = "인스타 릴스 스와이프 전환 (#${reelsSwipeCount}회 · 누적 ${accumulatedReelsWatchMs / 1000L}초 시청)"
        )
        addEvent(event)
        _snapshot.value = _snapshot.value.copy(
            reelsSwipeCount = reelsSwipeCount,
            lastDetectedAtWallMs = nowWall
        )
    }

    /**
     * 인스타그램 및 ITV가 아닌 휴대폰 홈 화면이나 타 앱에 있을 때 호출.
     */
    @Synchronized
    fun onExternalApp(
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis()
    ) {
        lastTickElapsed = 0L
        pendingMode = null

        if (currentLocation == IgAppLocation.INSTAGRAM) {
            if (pendingHomeExitElapsed == 0L) {
                pendingHomeExitElapsed = nowElapsed
            } else if (!homeExitLogged && nowElapsed - pendingHomeExitElapsed >= HOME_EXIT_DEBOUNCE_MS) {
                val event = InstagramTransitionEvent(
                    timestampWallMs = nowWall,
                    eventType = "IG_EXITED_TO_HOME",
                    fromMode = lastInstagramMode,
                    toMode = InstagramScreenMode.BACKGROUND,
                    reelsWatchDurationMs = accumulatedReelsWatchMs,
                    modeDurationMs = currentModeDurationMs,
                    reelsSwipeCount = _snapshot.value.reelsSwipeCount,
                    description = "홈/외부 화면 이동 (인스타 이탈): 직전 '${lastInstagramMode.label}' (누적 릴스 ${accumulatedReelsWatchMs / 1000L}초)"
                )
                addEvent(event)
                lastHomeExitEventId = event.id
                homeExitLogged = true
                currentLocation = IgAppLocation.HOME_OR_OTHER
                lastMode = InstagramScreenMode.BACKGROUND
            }
        }

        if (_snapshot.value.isInInstagram || _snapshot.value.mode != InstagramScreenMode.BACKGROUND) {
            _snapshot.value = _snapshot.value.copy(
                mode = InstagramScreenMode.BACKGROUND,
                isInInstagram = false,
                isReelsActive = false,
                isPowerSaveScanMode = false,
                detail = "인스타그램 외 화면 (체류 타이머 정지)"
            )
        }
    }

    /**
     * 최근 앱 키 등을 눌러 ITV 앱으로 복귀했을 때 호출.
     */
    @Synchronized
    fun onReturnedToItv(
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis()
    ) {
        if (currentLocation == IgAppLocation.ITV) return

        lastTickElapsed = 0L
        pendingMode = null

        if (currentLocation == IgAppLocation.INSTAGRAM || hasUnreportedInstagramSession) {
            lastHomeExitEventId?.let { exitId ->
                val currentList = _history.value
                val recentExit = currentList.firstOrNull { it.id == exitId && (nowWall - it.timestampWallMs) <= 4500L }
                if (recentExit != null) {
                    _history.value = currentList.filterNot { it.id == exitId }
                }
            }

            val fromIgMode = if (lastInstagramMode != InstagramScreenMode.UNKNOWN) lastInstagramMode else lastMode
            val swipeCount = _snapshot.value.reelsSwipeCount
            val desc = "인스타그램 → 우리 앱(ITV) 복귀: 직전 '${fromIgMode.label}' (누적 릴스 ${accumulatedReelsWatchMs / 1000L}초 · 스와이프 ${swipeCount}회)"

            val event = InstagramTransitionEvent(
                timestampWallMs = nowWall,
                eventType = "IG_TO_ITV",
                fromMode = fromIgMode,
                toMode = InstagramScreenMode.BACKGROUND,
                reelsWatchDurationMs = accumulatedReelsWatchMs,
                modeDurationMs = currentModeDurationMs,
                reelsSwipeCount = swipeCount,
                description = desc
            )
            lastSessionEvent = event
            addEvent(event)
            hasUnreportedInstagramSession = false
        }

        currentLocation = IgAppLocation.ITV
        cameFromItv = true
        pendingHomeExitElapsed = 0L
        homeExitLogged = false
        lastHomeExitEventId = null
        lastMode = InstagramScreenMode.BACKGROUND

        _snapshot.value = _snapshot.value.copy(
            mode = InstagramScreenMode.BACKGROUND,
            isInInstagram = false,
            isReelsActive = false,
            isPowerSaveScanMode = false,
            detail = "우리 앱(ITV) 확인 중 · 인스타 타이머 정지됨"
        )
    }

    private fun addEvent(event: InstagramTransitionEvent) {
        val current = _history.value.toMutableList()
        current.add(0, event)
        if (current.size > MAX_HISTORY) {
            _history.value = current.take(MAX_HISTORY)
        } else {
            _history.value = current
        }
    }

    @Synchronized
    fun clearHistory() {
        _history.value = emptyList()
        lastSessionEvent = null
        accumulatedReelsWatchMs = 0L
        currentModeDurationMs = 0L
    }
}
