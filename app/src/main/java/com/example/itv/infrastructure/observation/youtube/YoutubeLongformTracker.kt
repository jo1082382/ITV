package com.example.itv.infrastructure.observation.youtube

import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * YouTube 화면 시청 모드
 */
enum class YoutubeWatchMode(val label: String) {
    UNKNOWN("판별 대기"),
    BROWSE_FEED("유튜브 홈 / 피드"),
    PORTRAIT_WATCH("세로 영상 시청 (댓글·설명)"),
    LANDSCAPE_FULLSCREEN("가로 전체화면 시청"),
    SHORTS("쇼츠 시청"),
    SHORTS_COMMENTS("숏폼 댓글"),
    BACKGROUND("백그라운드 / 외부 화면")
}

/**
 * 현재 사용자의 포그라운드 앱 위치
 */
enum class AppLocation {
    UNKNOWN,
    YOUTUBE,
    ITV,
    HOME_OR_OTHER
}

/**
 * YouTube 롱폼 실시간 관측 스냅샷
 */
data class YoutubeLongformSnapshot(
    val mode: YoutubeWatchMode = YoutubeWatchMode.UNKNOWN,
    val currentTitle: String? = null,
    val channelName: String? = null,
    val isPlaying: Boolean = false,
    val playbackPositionText: String? = null,
    val isEndscreenDetected: Boolean = false,
    val isLandscape: Boolean = false,
    val isPowerSaveScanMode: Boolean = false,
    val playerBounds: Rect? = null,
    val currentVideoStartElapsed: Long = 0L,
    val currentVideoWatchDurationMs: Long = 0L,
    val currentModeDurationMs: Long = 0L,
    val shortsWatchDurationMs: Long = 0L,
    val lastDetectedAtWallMs: Long = 0L,
    val detail: String = "관측 대기 중"
)

/**
 * 롱폼 상태 변화 및 전환 이벤트 기록
 */
data class LongformTransitionEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampWallMs: Long = System.currentTimeMillis(),
    val eventType: String,
    val fromMode: YoutubeWatchMode,
    val toMode: YoutubeWatchMode,
    val previousTitle: String?,
    val currentTitle: String?,
    val watchedDurationMs: Long,
    val description: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(timestampWallMs))
}

/**
 * Jev 평가 및 로컬 연속 시청 연쇄 보정에 사용할 롱폼 세션 지표
 * - [totalLongformWatchMs], [currentVideoWatchMs], [completedLongformCount], [youtubeWatchMode]는 Jev 서버 맥락에 포함
 * - [consecutiveChainCount](추천 영상을 타고 연속으로 본 횟수)는 Jev 서버로 전송하지 않고 우리 앱 내부 기준점 보정에만 사용
 */
data class JevLongformMetrics(
    val totalLongformWatchMs: Long = 0L,
    val currentVideoWatchMs: Long = 0L,
    val completedLongformCount: Int = 0,
    val consecutiveChainCount: Int = 0,
    val youtubeWatchMode: String? = null
)

/**
 * YouTube 롱폼 시청 상태 판별, 동일 영상 세션 누적 시청 시간(일시정지·타앱 이동 제외) 관리,
 * 다양한 시나리오별 전환 이벤트 포착 및 앱 전환 기록을 담당하는 싱글톤 트래커.
 */
object YoutubeLongformTracker {

    private data class VideoWatchSession(
        val title: String,
        var accumulatedWatchMs: Long = 0L,
        var firstStartedAtWallMs: Long,
        var lastUpdatedAtWallMs: Long,
        var endscreenLogged: Boolean = false,
        var countedInCompletion: Boolean = false
    )

    private val _snapshot = MutableStateFlow(YoutubeLongformSnapshot())
    val snapshot: StateFlow<YoutubeLongformSnapshot> = _snapshot.asStateFlow()

    private val _history = MutableStateFlow<List<LongformTransitionEvent>>(emptyList())
    val history: StateFlow<List<LongformTransitionEvent>> = _history.asStateFlow()

    var lastWatchedSession: LongformTransitionEvent? = null
        private set

    var onLongformTransition: ((LongformTransitionEvent) -> Unit)? = null

    // 내부 상태 추적 변수
    private var currentLocation: AppLocation = AppLocation.UNKNOWN
    private var lastMode: YoutubeWatchMode = YoutubeWatchMode.UNKNOWN
    private var lastYoutubeMode: YoutubeWatchMode = YoutubeWatchMode.UNKNOWN
    private var lastTitle: String? = null
    private var lastPlaybackPositionText: String? = null
    private var lastHasEndscreen: Boolean = false

    // 화면 모드별 체류 시간 및 쇼츠 누적 시청 시간 추적
    private var currentModeDurationMs: Long = 0L
    private var shortsWatchDurationMs: Long = 0L
    private var lastObserveElapsed: Long = 0L

    // 영상별 누적 시청 시간 세션 맵 (동일 제목 영상은 3시간 이내 재시청/화면전환 시 누적 유지)
    private val videoSessions = LinkedHashMap<String, VideoWatchSession>()
    private var pendingUnassignedWatchMs: Long = 0L
    private var videoStartElapsed: Long = 0L
    private var lastActivePlayTickElapsed: Long = 0L
    private var lastUserInteractionElapsed: Long = 0L

    // Jev 연동 및 앱 내부 연쇄 시청 추적 변수 (15분 미사용 또는 휴식 미션 완료 시 초기화)
    private var sessionLongformWatchMs: Long = 0L
    private var completedLongformCount: Int = 0
    private var consecutiveLongformChainCount: Int = 0
    private var lastYoutubeActiveWallMs: Long = 0L
    private var justEndedPreviousVideo: Boolean = false

    // 앱 간 전환 추적용
    private var cameFromItv: Boolean = false
    private var hasUnreportedYoutubeSession: Boolean = false
    private var pendingHomeExitElapsed: Long = 0L
    private var homeExitLogged: Boolean = false
    private var lastHomeExitEventId: String? = null

    // 디바운스 타이머 (제목 변경 및 회전 중 일시적 피드 전환 오판 방지)
    private var pendingCandidateTitle: String? = null
    private var candidateFirstSeenElapsed: Long = 0L
    private var pendingFeedModeElapsed: Long = 0L

    private const val MAX_HISTORY = 100
    private const val MAX_VIDEO_SESSIONS = 50
    private const val TITLE_DEBOUNCE_MS = 600L
    private const val FEED_EXIT_DEBOUNCE_MS = 650L
    private const val HOME_EXIT_DEBOUNCE_MS = 1200L
    private const val STABLE_PLAYBACK_IDLE_MS = 3000L
    private const val IDLE_COMMENT_OR_FEED_MS = 2500L
    // 완전히 다른 세션으로 판단하는 기준 시간 (3시간)
    private const val VIDEO_SESSION_EXPIRE_WALL_MS = 3L * 60L * 60L * 1000L

    /**
     * 사용자의 화면 터치, 스크롤, 창 전환 등 능동적 상호작용이 발생했을 때 호출.
     */
    @Synchronized
    fun onUserInteraction(nowElapsed: Long = SystemClock.elapsedRealtime()) {
        lastUserInteractionElapsed = nowElapsed
    }

    /**
     * 직전 유튜브 화면 모드가 쇼츠(또는 숏폼 댓글)였는지 여부.
     */
    @Synchronized
    fun isShortsContext(): Boolean =
        lastYoutubeMode == YoutubeWatchMode.SHORTS || lastYoutubeMode == YoutubeWatchMode.SHORTS_COMMENTS

    /**
     * 현재 사용자가 유튜브에서 롱폼 영상을 시청 중(세로/가로 시청 화면이며 아직 영상 종료 엔드스크린이 뜨지 않은 상태)인지 여부.
     * 영상 시청 중 개입 금지 원칙에 따라 이 상태에서는 개입을 보류하고 영상 종료(추천 화면)·다음 영상 전환·유튜브 홈 이동 시점에 개입합니다.
     */
    @Synchronized
    fun isActivelyWatchingLongform(): Boolean {
        if (currentLocation != AppLocation.YOUTUBE) return false
        val snap = _snapshot.value
        val isWatchMode = snap.mode == YoutubeWatchMode.PORTRAIT_WATCH || snap.mode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN
        return isWatchMode && !snap.isEndscreenDetected
    }

    /**
     * 현재 사용자가 유튜브에서 쇼츠 또는 숏폼 댓글을 보고 있는지 여부.
     */
    @Synchronized
    fun isActivelyInShortsOrComments(): Boolean {
        if (currentLocation != AppLocation.YOUTUBE) return false
        val snap = _snapshot.value
        return snap.mode == YoutubeWatchMode.SHORTS || snap.mode == YoutubeWatchMode.SHORTS_COMMENTS
    }

    /**
     * 화면 터치/움직임 없이 롱폼 영상을 3초 이상 안정적으로 시청 중이거나,
     * 숏폼 댓글 / 홈 피드를 터치·스크롤 없이 2.5초 이상 정적으로 보고 있는지 여부 (절전 스캔 대상).
     */
    @Synchronized
    fun isPowerSaveScanEligible(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean {
        if (currentLocation != AppLocation.YOUTUBE) return false
        val idleMs = nowElapsed - lastUserInteractionElapsed
        val isWatchingLongform = lastMode == YoutubeWatchMode.PORTRAIT_WATCH || lastMode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN
        if (isWatchingLongform) {
            return _snapshot.value.isPlaying &&
                !lastTitle.isNullOrBlank() &&
                !lastHasEndscreen &&
                pendingCandidateTitle == null &&
                idleMs >= STABLE_PLAYBACK_IDLE_MS
        }
        val isStaticCommentOrFeed = lastMode == YoutubeWatchMode.SHORTS_COMMENTS || lastMode == YoutubeWatchMode.BROWSE_FEED
        return isStaticCommentOrFeed && idleMs >= IDLE_COMMENT_OR_FEED_MS
    }

    @Synchronized
    fun isStableLongformPlayback(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean =
        isPowerSaveScanEligible(nowElapsed)

    private fun formatTitle(title: String?, fallback: String = "제목 미상"): String {
        val clean = title?.trim()
        return if (clean.isNullOrEmpty()) fallback else clean.take(30)
    }

    private fun getOrCreateSession(title: String, nowWall: Long): VideoWatchSession {
        val key = title.trim()
        val existing = videoSessions[key]
        if (existing != null && (nowWall - existing.lastUpdatedAtWallMs) <= VIDEO_SESSION_EXPIRE_WALL_MS) {
            existing.lastUpdatedAtWallMs = nowWall
            if (pendingUnassignedWatchMs > 0L) {
                existing.accumulatedWatchMs += pendingUnassignedWatchMs
                pendingUnassignedWatchMs = 0L
            }
            return existing
        }
        // 만료되었거나 최초 시청인 경우 새 세션 생성
        val initialMs = pendingUnassignedWatchMs
        pendingUnassignedWatchMs = 0L
        val created = VideoWatchSession(
            title = key,
            accumulatedWatchMs = initialMs,
            firstStartedAtWallMs = nowWall,
            lastUpdatedAtWallMs = nowWall,
            endscreenLogged = false
        )
        videoSessions[key] = created
        if (videoSessions.size > MAX_VIDEO_SESSIONS) {
            val oldestKey = videoSessions.keys.firstOrNull()
            if (oldestKey != null) videoSessions.remove(oldestKey)
        }
        return created
    }

    private fun currentAccumulatedWatchMs(nowWall: Long): Long {
        val t = lastTitle
        if (!t.isNullOrBlank()) {
            val s = videoSessions[t.trim()]
            if (s != null && (nowWall - s.lastUpdatedAtWallMs) <= VIDEO_SESSION_EXPIRE_WALL_MS) {
                return s.accumulatedWatchMs + pendingUnassignedWatchMs
            }
        }
        return pendingUnassignedWatchMs
    }

    @Synchronized
    fun onObserve(
        nowElapsed: Long,
        nowWall: Long,
        mode: YoutubeWatchMode,
        title: String?,
        channel: String?,
        hasEndscreen: Boolean,
        isLandscape: Boolean,
        playerBounds: Rect?,
        isPlaying: Boolean = true,
        playbackPositionText: String? = null
    ) {
        val prevLocation = currentLocation
        val prevMode = lastMode
        val prevYoutubeMode = lastYoutubeMode
        val prevTitle = lastTitle

        // 회전이나 순간적인 레이아웃 변경 시 1~2프레임 동안 BROWSE_FEED로 튀는 현상 방지 디바운스
        val wasWatchingPrev = prevMode == YoutubeWatchMode.PORTRAIT_WATCH || prevMode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN
        val effectiveMode = if (wasWatchingPrev && mode == YoutubeWatchMode.BROWSE_FEED) {
            if (pendingFeedModeElapsed == 0L) {
                pendingFeedModeElapsed = nowElapsed
                prevMode
            } else if (nowElapsed - pendingFeedModeElapsed < FEED_EXIT_DEBOUNCE_MS) {
                prevMode
            } else {
                pendingFeedModeElapsed = 0L
                mode
            }
        } else {
            pendingFeedModeElapsed = 0L
            mode
        }

        val isWatchingNow = effectiveMode == YoutubeWatchMode.PORTRAIT_WATCH || effectiveMode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN
        val effectivePrevMode = if (prevLocation != AppLocation.YOUTUBE) prevYoutubeMode else prevMode

        val deltaMs = if (lastObserveElapsed > 0L) {
            (nowElapsed - lastObserveElapsed).coerceIn(0L, 3500L)
        } else {
            0L
        }
        lastObserveElapsed = nowElapsed

        if (effectiveMode != effectivePrevMode) {
            currentModeDurationMs = 0L
        } else {
            currentModeDurationMs += deltaMs
        }

        if (effectiveMode == YoutubeWatchMode.SHORTS && isPlaying) {
            shortsWatchDurationMs += deltaMs
        }

        // 0. 외부 앱(우리 앱 ITV 또는 홈 화면)에서 유튜브로 복귀/진입 감지
        if (prevLocation != AppLocation.YOUTUBE) {
            // 외부에 있던 시간은 시청 시간에 절대 합산되지 않도록 재생 틱 기준 초기화
            lastActivePlayTickElapsed = 0L
            lastUserInteractionElapsed = nowElapsed

            val activeTitle = title ?: prevTitle
            val activeWatchMs = currentAccumulatedWatchMs(nowWall)

            if (prevLocation == AppLocation.ITV || cameFromItv) {
                val titleSuffix = if (!activeTitle.isNullOrBlank() && isWatchingNow) " · '${formatTitle(activeTitle)}' (누적 ${activeWatchMs / 1000L}초)" else ""
                val event = LongformTransitionEvent(
                    timestampWallMs = nowWall,
                    eventType = "ITV_TO_YOUTUBE",
                    fromMode = YoutubeWatchMode.BACKGROUND,
                    toMode = effectiveMode,
                    previousTitle = prevTitle,
                    currentTitle = activeTitle,
                    watchedDurationMs = activeWatchMs,
                    description = "우리 앱(ITV) → 유튜브 복귀: ${effectiveMode.label}$titleSuffix"
                )
                addEvent(event)
            } else if (prevLocation == AppLocation.HOME_OR_OTHER && homeExitLogged) {
                val titleSuffix = if (!activeTitle.isNullOrBlank() && isWatchingNow) " · '${formatTitle(activeTitle)}' (누적 ${activeWatchMs / 1000L}초)" else ""
                val event = LongformTransitionEvent(
                    timestampWallMs = nowWall,
                    eventType = "ENTERED_YOUTUBE",
                    fromMode = YoutubeWatchMode.BACKGROUND,
                    toMode = effectiveMode,
                    previousTitle = prevTitle,
                    currentTitle = activeTitle,
                    watchedDurationMs = activeWatchMs,
                    description = "홈/외부 화면 → 유튜브 복귀: ${effectiveMode.label}$titleSuffix"
                )
                addEvent(event)
            }

            currentLocation = AppLocation.YOUTUBE
            cameFromItv = false
            pendingHomeExitElapsed = 0L
            homeExitLogged = false
            lastHomeExitEventId = null
            hasUnreportedYoutubeSession = true
        }

        // 15분 이상 유튜브 부재 시 Jev 세션 롱폼 누적 및 연속 추천 시청 체인 초기화
        if (lastYoutubeActiveWallMs > 0L && (nowWall - lastYoutubeActiveWallMs) >= 15L * 60L * 1000L) {
            sessionLongformWatchMs = 0L
            completedLongformCount = 0
            consecutiveLongformChainCount = 0
            justEndedPreviousVideo = false
        }
        lastYoutubeActiveWallMs = nowWall

        // 1. 실제 재생 시간(Active Watch Duration) 델타 누적
        // 오직 유튜브 시청 모드(세로/가로)에서 실제로 영상이 재생 중(isPlaying == true)일 때만 시간을 누적함.
        // 일시정지, 유튜브 홈 피드, 숏폼, 숏폼 댓글, 타 앱/홈 화면에서는 즉시 타이머가 멈춤.
        val activelyPlaying = isWatchingNow && isPlaying
        if (activelyPlaying) {
            val deltaMs = if (lastActivePlayTickElapsed > 0L) {
                (nowElapsed - lastActivePlayTickElapsed).coerceIn(0L, 3500L)
            } else {
                0L
            }
            lastActivePlayTickElapsed = nowElapsed

            if (deltaMs > 0L) {
                sessionLongformWatchMs += deltaMs
                val currentKey = lastTitle ?: title
                if (!currentKey.isNullOrBlank()) {
                    val session = getOrCreateSession(currentKey, nowWall)
                    session.accumulatedWatchMs += deltaMs
                    session.lastUpdatedAtWallMs = nowWall
                } else {
                    pendingUnassignedWatchMs += deltaMs
                }
            }
        } else {
            lastActivePlayTickElapsed = 0L
        }

        if (!playbackPositionText.isNullOrBlank()) {
            lastPlaybackPositionText = playbackPositionText
        }

        // 2. 영상 제목 인식 및 다음 영상 전환 감지 (디바운스 적용)
        // 엔드스크린(추천 영상 화면 / 다음 동영상 프리뷰) 노출 중에는 추천 영상 제목을 새 영상 전환으로 오인하지 않도록 차단
        if (hasEndscreen) {
            pendingCandidateTitle = null
        } else if (isWatchingNow && !title.isNullOrBlank()) {
            if (lastTitle == null) {
                lastTitle = title
                val session = getOrCreateSession(title, nowWall)
                if (videoStartElapsed == 0L) videoStartElapsed = nowElapsed
                pendingCandidateTitle = null
                upgradeLatestWatchingEventTitle(title, session.accumulatedWatchMs, nowWall)
            } else if (title != lastTitle) {
                if (pendingCandidateTitle != title) {
                    pendingCandidateTitle = title
                    candidateFirstSeenElapsed = nowElapsed
                } else if (nowElapsed - candidateFirstSeenElapsed >= TITLE_DEBOUNCE_MS) {
                    val oldTitle = lastTitle
                    val prevWatchedMs = currentAccumulatedWatchMs(nowWall)
                    val oldSession = if (!oldTitle.isNullOrBlank()) videoSessions[oldTitle.trim()] else null
                    // 이전 영상을 최소 10초 이상 시청했거나 직전 영상 종료(엔드스크린) 직후 다음/추천 영상으로 넘어간 경우 연쇄 카운트 증가
                    if (prevWatchedMs >= 10_000L || justEndedPreviousVideo) {
                        consecutiveLongformChainCount += 1
                    }
                    if (prevWatchedMs >= 30_000L && oldSession != null && !oldSession.countedInCompletion) {
                        oldSession.countedInCompletion = true
                        completedLongformCount += 1
                    }
                    justEndedPreviousVideo = false

                    val newSession = getOrCreateSession(title, nowWall)
                    val event = LongformTransitionEvent(
                        timestampWallMs = nowWall,
                        eventType = "TITLE_CHANGED",
                        fromMode = prevMode,
                        toMode = effectiveMode,
                        previousTitle = oldTitle,
                        currentTitle = title,
                        watchedDurationMs = prevWatchedMs,
                        description = "다음 영상 전환 (연속 ${consecutiveLongformChainCount}회): '${formatTitle(oldTitle)}' (${prevWatchedMs / 1000L}초 시청) → 새 영상 '${formatTitle(title)}'"
                    )
                    addEvent(event)
                    onLongformTransition?.invoke(event)

                    lastTitle = title
                    videoStartElapsed = nowElapsed
                    lastPlaybackPositionText = null
                    pendingCandidateTitle = null
                    newSession.lastUpdatedAtWallMs = nowWall
                }
            } else {
                pendingCandidateTitle = null
                getOrCreateSession(title, nowWall)
            }
        }

        val currentWatchMs = currentAccumulatedWatchMs(nowWall)

        // 3. 유튜브 내부 화면/모드 전환 시나리오 감지 (세로 시청, 가로 시청, 유튜브 홈 이동, 쇼츠 진입, 숏폼 댓글)
        if (effectiveMode != effectivePrevMode) {
            when (effectiveMode) {
                YoutubeWatchMode.PORTRAIT_WATCH -> {
                    val t = lastTitle ?: title
                    if (effectivePrevMode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN) {
                        val event = LongformTransitionEvent(
                            timestampWallMs = nowWall,
                            eventType = "PORTRAIT_WATCH",
                            fromMode = effectivePrevMode,
                            toMode = effectiveMode,
                            previousTitle = t,
                            currentTitle = t,
                            watchedDurationMs = currentWatchMs,
                            description = "가로 전체화면 → 세로 시청 전환: '${formatTitle(t, "현재 영상")}' (누적 ${currentWatchMs / 1000L}초 시청)"
                        )
                        addEvent(event)
                    } else {
                        if (videoStartElapsed == 0L) videoStartElapsed = nowElapsed
                        val suffix = if (currentWatchMs > 0L) " (누적 ${currentWatchMs / 1000L}초)" else ""
                        val event = LongformTransitionEvent(
                            timestampWallMs = nowWall,
                            eventType = "PORTRAIT_WATCH",
                            fromMode = effectivePrevMode,
                            toMode = effectiveMode,
                            previousTitle = prevTitle,
                            currentTitle = t,
                            watchedDurationMs = currentWatchMs,
                            description = "세로 영상 시청 진입: '${formatTitle(t, "제목 인식 중…")}'$suffix"
                        )
                        addEvent(event)
                    }
                }

                YoutubeWatchMode.LANDSCAPE_FULLSCREEN -> {
                    val t = lastTitle ?: title
                    if (effectivePrevMode == YoutubeWatchMode.PORTRAIT_WATCH) {
                        val event = LongformTransitionEvent(
                            timestampWallMs = nowWall,
                            eventType = "LANDSCAPE_WATCH",
                            fromMode = effectivePrevMode,
                            toMode = effectiveMode,
                            previousTitle = t,
                            currentTitle = t,
                            watchedDurationMs = currentWatchMs,
                            description = "세로 시청 → 가로 전체화면 전환: '${formatTitle(t, "현재 영상")}' (누적 ${currentWatchMs / 1000L}초 시청)"
                        )
                        addEvent(event)
                    } else {
                        if (videoStartElapsed == 0L) videoStartElapsed = nowElapsed
                        val suffix = if (currentWatchMs > 0L) " (누적 ${currentWatchMs / 1000L}초)" else ""
                        val event = LongformTransitionEvent(
                            timestampWallMs = nowWall,
                            eventType = "LANDSCAPE_WATCH",
                            fromMode = effectivePrevMode,
                            toMode = effectiveMode,
                            previousTitle = prevTitle,
                            currentTitle = t,
                            watchedDurationMs = currentWatchMs,
                            description = "가로 전체화면 시청 진입: '${formatTitle(t, "제목 인식 중…")}'$suffix"
                        )
                        addEvent(event)
                    }
                }

                YoutubeWatchMode.BROWSE_FEED -> {
                    if (effectivePrevMode == YoutubeWatchMode.PORTRAIT_WATCH || effectivePrevMode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN) {
                        val event = LongformTransitionEvent(
                            timestampWallMs = nowWall,
                            eventType = "MOVED_TO_YT_HOME",
                            fromMode = effectivePrevMode,
                            toMode = effectiveMode,
                            previousTitle = prevTitle,
                            currentTitle = null,
                            watchedDurationMs = currentWatchMs,
                            description = "유튜브 홈 화면 이동: 직전 영상 '${formatTitle(prevTitle, "이전 영상")}' (누적 ${currentWatchMs / 1000L}초 시청 · 재시청 시 이어짐)"
                        )
                        addEvent(event)
                        onLongformTransition?.invoke(event)
                        lastActivePlayTickElapsed = 0L
                        lastPlaybackPositionText = null
                        pendingCandidateTitle = null
                    } else if (effectivePrevMode == YoutubeWatchMode.SHORTS || effectivePrevMode == YoutubeWatchMode.SHORTS_COMMENTS) {
                        val event = LongformTransitionEvent(
                            timestampWallMs = nowWall,
                            eventType = "MOVED_TO_YT_HOME",
                            fromMode = effectivePrevMode,
                            toMode = effectiveMode,
                            previousTitle = prevTitle,
                            currentTitle = null,
                            watchedDurationMs = 0L,
                            description = "쇼츠 종료 → 유튜브 홈 화면 이동"
                        )
                        addEvent(event)
                    }
                }

                YoutubeWatchMode.SHORTS -> {
                    lastActivePlayTickElapsed = 0L
                    val desc = if (effectivePrevMode == YoutubeWatchMode.SHORTS_COMMENTS) {
                        "숏폼 댓글 닫기 → 쇼츠(Shorts) 시청 복귀"
                    } else {
                        "유튜브 쇼츠(Shorts) 시청 진입"
                    }
                    val event = LongformTransitionEvent(
                        timestampWallMs = nowWall,
                        eventType = "SHORTS_WATCH",
                        fromMode = effectivePrevMode,
                        toMode = effectiveMode,
                        previousTitle = prevTitle,
                        currentTitle = title,
                        watchedDurationMs = currentWatchMs,
                        description = desc
                    )
                    addEvent(event)
                }

                YoutubeWatchMode.SHORTS_COMMENTS -> {
                    lastActivePlayTickElapsed = 0L
                    val event = LongformTransitionEvent(
                        timestampWallMs = nowWall,
                        eventType = "SHORTS_COMMENTS",
                        fromMode = effectivePrevMode,
                        toMode = effectiveMode,
                        previousTitle = prevTitle,
                        currentTitle = null,
                        watchedDurationMs = 0L,
                        description = "유튜브 숏폼 댓글 열람 진입"
                    )
                    addEvent(event)
                }

                else -> {}
            }
        }

        // 4. 유튜브 영상 종료(추천 영상 엔드스크린 / 한 영상 끝) 감지 (영상 세션당 1회)
        val activeTitleKey = (lastTitle ?: title)?.trim()
        val currentSession = if (!activeTitleKey.isNullOrBlank()) videoSessions[activeTitleKey] else null
        val alreadyLoggedEndscreen = currentSession?.endscreenLogged == true

        if (hasEndscreen && !alreadyLoggedEndscreen && isWatchingNow) {
            val currentVidTitle = lastTitle ?: title
            val event = LongformTransitionEvent(
                timestampWallMs = nowWall,
                eventType = "VIDEO_ENDED",
                fromMode = prevMode,
                toMode = effectiveMode,
                previousTitle = currentVidTitle,
                currentTitle = currentVidTitle,
                watchedDurationMs = currentWatchMs,
                description = "유튜브 영상 종료(추천 영상 화면) 감지: '${formatTitle(currentVidTitle, "현재 영상")}' (누적 ${currentWatchMs / 1000L}초 시청)"
            )
            addEvent(event)
            onLongformTransition?.invoke(event)
            currentSession?.endscreenLogged = true
            if (currentSession != null && !currentSession.countedInCompletion) {
                currentSession.countedInCompletion = true
                completedLongformCount += 1
            } else if (currentSession == null) {
                completedLongformCount += 1
            }
            justEndedPreviousVideo = true
        } else if (!hasEndscreen && currentSession != null && currentSession.endscreenLogged) {
            // 사용자가 영상 앞부분으로 되감기하여 다시 시청하는 경우 엔드스크린 플래그 해제
            currentSession.endscreenLogged = false
        }

        lastMode = effectiveMode
        lastYoutubeMode = effectiveMode
        lastHasEndscreen = hasEndscreen

        val playStateStr = if (isPlaying) "재생 중" else "일시정지"
        val powerSaveActive = isPowerSaveScanEligible(nowElapsed)
        val scanModeSuffix = if (powerSaveActive) " · 절전 스캔(1.5초)" else ""

        val detailMsg = when (effectiveMode) {
            YoutubeWatchMode.LANDSCAPE_FULLSCREEN -> "가로 전체화면 $playStateStr (누적 ${currentWatchMs / 1000L}초)$scanModeSuffix"
            YoutubeWatchMode.PORTRAIT_WATCH -> "세로 영상 $playStateStr (누적 ${currentWatchMs / 1000L}초)$scanModeSuffix"
            YoutubeWatchMode.BROWSE_FEED -> "유튜브 홈/피드 탐색 중 (시청 타이머 정지)$scanModeSuffix"
            YoutubeWatchMode.SHORTS -> "쇼츠 플레이어 시청 중"
            YoutubeWatchMode.SHORTS_COMMENTS -> "숏폼 댓글 열람 중 (롱폼 타이머 정지)$scanModeSuffix"
            else -> "판별 대기 중"
        }

        _snapshot.value = YoutubeLongformSnapshot(
            mode = effectiveMode,
            currentTitle = if (effectiveMode == YoutubeWatchMode.BROWSE_FEED || effectiveMode == YoutubeWatchMode.SHORTS_COMMENTS) null else (lastTitle ?: title),
            channelName = channel,
            isPlaying = (isWatchingNow || effectiveMode == YoutubeWatchMode.SHORTS) && isPlaying,
            playbackPositionText = if (isWatchingNow) (playbackPositionText ?: lastPlaybackPositionText) else null,
            isEndscreenDetected = hasEndscreen,
            isLandscape = isLandscape,
            isPowerSaveScanMode = powerSaveActive,
            playerBounds = playerBounds,
            currentVideoStartElapsed = videoStartElapsed,
            currentVideoWatchDurationMs = currentWatchMs,
            currentModeDurationMs = currentModeDurationMs,
            shortsWatchDurationMs = shortsWatchDurationMs,
            lastDetectedAtWallMs = nowWall,
            detail = detailMsg
        )
    }

    /**
     * 사용자가 유튜브 및 ITV가 아닌 휴대폰 홈 화면(런처)이나 타 앱에 있을 때 호출.
     * 시청 타이머를 즉시 멈추고, 최근 앱 전환(잠깐 스침)이 아닌 실제 홈 화면 복귀 시 이벤트를 1회 기록.
     */
    @Synchronized
    fun onExternalApp(
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis(),
        packageName: String? = null
    ) {
        // 외부 화면에서는 시청 시간 증가 즉시 정지
        lastActivePlayTickElapsed = 0L
        lastObserveElapsed = 0L
        currentModeDurationMs = 0L
        pendingFeedModeElapsed = 0L

        if (currentLocation == AppLocation.YOUTUBE) {
            if (pendingHomeExitElapsed == 0L) {
                pendingHomeExitElapsed = nowElapsed
            } else if (!homeExitLogged && nowElapsed - pendingHomeExitElapsed >= HOME_EXIT_DEBOUNCE_MS) {
                val watchedMs = currentAccumulatedWatchMs(nowWall)
                val isLongform = lastYoutubeMode == YoutubeWatchMode.PORTRAIT_WATCH || lastYoutubeMode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN
                val titleStr = if (isLongform && !lastTitle.isNullOrBlank()) "'${formatTitle(lastTitle)}', 누적 ${watchedMs / 1000L}초 시청" else lastYoutubeMode.label
                val event = LongformTransitionEvent(
                    timestampWallMs = nowWall,
                    eventType = "EXITED_TO_HOME",
                    fromMode = lastYoutubeMode,
                    toMode = YoutubeWatchMode.BACKGROUND,
                    previousTitle = if (isLongform) lastTitle else lastYoutubeMode.label,
                    currentTitle = null,
                    watchedDurationMs = watchedMs,
                    description = "홈 화면 복귀 (유튜브 이탈): 직전 $titleStr"
                )
                addEvent(event)
                lastHomeExitEventId = event.id
                homeExitLogged = true
                currentLocation = AppLocation.HOME_OR_OTHER
                lastMode = YoutubeWatchMode.BACKGROUND
            }
        }

        if (_snapshot.value.isPlaying || _snapshot.value.mode != YoutubeWatchMode.BACKGROUND) {
            _snapshot.value = _snapshot.value.copy(
                mode = YoutubeWatchMode.BACKGROUND,
                isPlaying = false,
                isPowerSaveScanMode = false,
                detail = "홈 화면 / 외부 앱 체류 중 (시청 타이머 정지)"
            )
        }
    }

    /**
     * 최근 앱 키(App Switcher) 등을 눌러 사용자가 ITV 앱 화면으로 복귀했을 때 호출.
     * 방금 전까지 YouTube에서 시청 중이던 영상 정보와 누적 시청 시간을 영구 보존하여 테스트 화면에 표시.
     */
    @Synchronized
    fun onReturnedToItv(
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis()
    ) {
        if (currentLocation == AppLocation.ITV) return

        // 시청 타이머 즉시 정지
        lastActivePlayTickElapsed = 0L
        pendingFeedModeElapsed = 0L

        if (currentLocation == AppLocation.YOUTUBE || hasUnreportedYoutubeSession) {
            lastHomeExitEventId?.let { exitId ->
                val currentList = _history.value
                val recentExit = currentList.firstOrNull { it.id == exitId && (nowWall - it.timestampWallMs) <= 4500L }
                if (recentExit != null) {
                    _history.value = currentList.filterNot { it.id == exitId }
                }
            }

            val watchedMs = currentAccumulatedWatchMs(nowWall)
            val videoTitle = lastTitle
            val fromYtMode = if (lastYoutubeMode != YoutubeWatchMode.UNKNOWN) lastYoutubeMode else lastMode
            val isLongform = fromYtMode == YoutubeWatchMode.PORTRAIT_WATCH || fromYtMode == YoutubeWatchMode.LANDSCAPE_FULLSCREEN

            val desc = when {
                fromYtMode == YoutubeWatchMode.SHORTS_COMMENTS ->
                    "유튜브 → 우리 앱(ITV) 복귀: 직전 '유튜브 숏폼 댓글' 열람 중 복귀"
                fromYtMode == YoutubeWatchMode.SHORTS ->
                    "유튜브 → 우리 앱(ITV) 복귀: 직전 '유튜브 쇼츠' 시청 중 복귀"
                fromYtMode == YoutubeWatchMode.BROWSE_FEED ->
                    "유튜브 → 우리 앱(ITV) 복귀: 직전 '유튜브 홈/피드 탐색' 중 복귀"
                !videoTitle.isNullOrBlank() ->
                    "유튜브 → 우리 앱(ITV) 복귀: 방금 보던 영상 '${formatTitle(videoTitle)}' (${fromYtMode.label}, 누적 ${watchedMs / 1000L}초 시청)"
                else ->
                    "유튜브 → 우리 앱(ITV) 복귀: 직전 '${fromYtMode.label}' (누적 ${watchedMs / 1000L}초 시청)"
            }

            val event = LongformTransitionEvent(
                timestampWallMs = nowWall,
                eventType = "YOUTUBE_TO_ITV",
                fromMode = fromYtMode,
                toMode = YoutubeWatchMode.BACKGROUND,
                previousTitle = if (isLongform && !videoTitle.isNullOrBlank()) videoTitle else fromYtMode.label,
                currentTitle = null,
                watchedDurationMs = if (isLongform) watchedMs else 0L,
                description = desc
            )
            lastWatchedSession = event
            addEvent(event)
            hasUnreportedYoutubeSession = false
        }

        currentLocation = AppLocation.ITV
        cameFromItv = true
        pendingHomeExitElapsed = 0L
        homeExitLogged = false
        lastHomeExitEventId = null
        lastMode = YoutubeWatchMode.BACKGROUND

        _snapshot.value = _snapshot.value.copy(
            mode = YoutubeWatchMode.BACKGROUND,
            isPlaying = false,
            isPowerSaveScanMode = false,
            detail = "우리 앱(ITV) 확인 중 · 시청 타이머 정지됨"
        )
    }

    private fun upgradeLatestWatchingEventTitle(resolvedTitle: String, watchedMs: Long, nowWall: Long) {
        val current = _history.value.toMutableList()
        val idx = current.indexOfFirst {
            (it.eventType == "PORTRAIT_WATCH" || it.eventType == "LANDSCAPE_WATCH") &&
                (it.currentTitle == null || it.description.contains("제목 인식 중")) &&
                (nowWall - it.timestampWallMs) <= 10_000L
        }
        if (idx >= 0) {
            val old = current[idx]
            val prefix = if (old.eventType == "PORTRAIT_WATCH") "세로 영상 시청 진입" else "가로 전체화면 시청 진입"
            val suffix = if (watchedMs > 0L) " (누적 ${watchedMs / 1000L}초)" else ""
            current[idx] = old.copy(
                currentTitle = resolvedTitle,
                watchedDurationMs = watchedMs,
                description = "$prefix: '${formatTitle(resolvedTitle)}'$suffix"
            )
            _history.value = current
        }
    }

    private fun addEvent(event: LongformTransitionEvent) {
        val current = _history.value.toMutableList()
        current.add(0, event)
        if (current.size > MAX_HISTORY) {
            _history.value = current.take(MAX_HISTORY)
        } else {
            _history.value = current
        }
    }

    /**
     * Jev 맥락 생성 및 우리 앱 로컬 연쇄 시청 판단을 위한 현재 롱폼 세션 지표를 반환합니다.
     */
    @Synchronized
    fun getJevLongformMetrics(nowWall: Long = System.currentTimeMillis()): JevLongformMetrics {
        if (lastYoutubeActiveWallMs > 0L && (nowWall - lastYoutubeActiveWallMs) >= 15L * 60L * 1000L) {
            return JevLongformMetrics()
        }
        val curVidWatchMs = currentAccumulatedWatchMs(nowWall)
        val totalWatchMs = maxOf(sessionLongformWatchMs, curVidWatchMs)
        val effectiveYtMode = if (currentLocation == AppLocation.YOUTUBE) {
            lastMode.takeIf { it != YoutubeWatchMode.UNKNOWN && it != YoutubeWatchMode.BACKGROUND }
        } else {
            lastYoutubeMode.takeIf { it != YoutubeWatchMode.UNKNOWN && it != YoutubeWatchMode.BACKGROUND }
        }
        return JevLongformMetrics(
            totalLongformWatchMs = totalWatchMs,
            currentVideoWatchMs = curVidWatchMs,
            completedLongformCount = completedLongformCount,
            consecutiveChainCount = consecutiveLongformChainCount,
            youtubeWatchMode = effectiveYtMode?.name
        )
    }

    /**
     * 휴식 미션 성공 또는 세션 만료 시 추천 영상 연속 시청 체인 및 세션 카운터를 초기화합니다.
     */
    @Synchronized
    fun resetSessionChain() {
        sessionLongformWatchMs = 0L
        completedLongformCount = 0
        consecutiveLongformChainCount = 0
        justEndedPreviousVideo = false
        shortsWatchDurationMs = 0L
        currentModeDurationMs = 0L
    }

    @Synchronized
    fun clearHistory() {
        _history.value = emptyList()
        lastWatchedSession = null
        videoSessions.clear()
        pendingUnassignedWatchMs = 0L
        resetSessionChain()
    }
}
