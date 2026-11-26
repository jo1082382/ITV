package com.example.itv.infrastructure.observation.youtube

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.itv.domain.records.ShortformApp
import com.example.itv.infrastructure.observation.SwipeDynamicsTracker
import com.example.itv.infrastructure.observation.accessibility.findActiveApplicationWindow
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore

/** Row-position diagnostic & YouTube longform state observer. */
internal object YoutubeProbe {
    @Volatile var onTransition: (() -> Unit)? = null
    @Volatile var coachingObservation = false
    @Volatile private var appContext: Context? = null
    @Volatile private var stateAt = 0L
    @Volatile private var state = -1
    // 1 shorts, 0 another app, -1 unknown or YouTube non-shorts (conservative hold).
    // Clear comparison baseline on exit, never count ITV entry as a transition.
    fun onOwnApp() {
        stateAt = SystemClock.elapsedRealtime()
        state = 0
        clearBaseline()
        status = "ITV 내부 · 탐색 생략 · 횟수/로그 보존"
        YoutubeLongformTracker.onReturnedToItv()
        appContext?.let { ctx ->
            com.example.itv.composition.shortformObservationService(ctx).presence(ShortformApp.YOUTUBE, false)
        }
    }

    fun screenState(): Int = if (SystemClock.elapsedRealtime() - stateAt <= 4000L) state else -1
    @Volatile var enabled = false
    @Volatile var status = "감시 중지"
    @Volatile var candidates = 0
    /** Swipe count of the most recent viewing run; survives the 15s idle reset for test inspection. */
    @Volatile var lastRunCandidates = 0
    @Volatile var row: Int? = null
    @Volatile var detail = "관측 대기"
    @Volatile private var pending: Int? = null
    @Volatile private var pendingAt = 0L
    @Volatile private var previousAt = 0L
    @Volatile private var lastScan = 0L
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

    private val LONGFORM_PLAYER_IDS = setOf(
        "com.google.android.youtube:id/watch_player",
        "com.google.android.youtube:id/player_view",
        "com.google.android.youtube:id/player_fragment_container",
        "com.google.android.youtube:id/movie_player"
    )

    private val TITLE_IDS = setOf(
        "com.google.android.youtube:id/video_title",
        "com.google.android.youtube:id/title",
        "com.google.android.youtube:id/watch_title",
        "com.google.android.youtube:id/player_video_title_view",
        "com.google.android.youtube:id/video_metadata_title"
    )

    private val ENDSCREEN_IDS = setOf(
        "com.google.android.youtube:id/autonav_preview",
        "com.google.android.youtube:id/endscreen_element",
        "com.google.android.youtube:id/autonav_overlay",
        "com.google.android.youtube:id/autonav_title"
    )

    private val BROWSE_FEED_IDS = setOf(
        "com.google.android.youtube:id/results_recycler",
        "com.google.android.youtube:id/pane_fragment_container",
        "com.google.android.youtube:id/bottom_bar_container",
        "com.google.android.youtube:id/pivot_bar",
        "com.google.android.youtube:id/feed_filter_bar"
    )

    private val BOTTOM_NAV_LABELS = setOf(
        "홈", "shorts", "구독", "내 페이지", "보관함", "나", "home", "subscriptions", "you", "library"
    )

    private val INLINE_MUTE_LABELS = setOf(
        "음소거", "음소거 해제", "소리 켜기", "소리 끄기",
        "오디오 음소거", "오디오 음소거 해제", "동영상 음소거", "동영상 음소거 해제",
        "mute", "unmute", "mute video", "unmute video", "mute audio", "unmute audio"
    )

    private val WATCH_EXCLUSIVE_CONTROL_DESCS = setOf(
        "축소", "최소화", "동영상 최소화", "동영상 축소", "minimize", "collapse",
        "자동재생 사용", "자동재생 사용 안함", "자동재생 사용 안 함",
        "전체화면", "전체화면으로 전환", "전체 화면", "enter fullscreen", "fullscreen"
    )

    private val IGNORED_EXACT_TITLES = setOf(
        "youtube", "홈", "shorts", "구독", "보관함", "내 페이지", "공유", "오프라인 저장", "다운로드",
        "좋아요", "싫어요", "댓글", "클립", "저장", "탐색", "리믹스", "만들기", "알림", "검색",
        "전체", "게임", "음악", "믹스", "실시간", "뉴스", "스포츠", "최근에 업로드된 동영상", "감상한 동영상",
        "추천", "관련 콘텐츠", "동영상 플레이어", "자막", "설정", "자동재생", "전체화면", "전체화면 종료",
        "탐색 닫기", "창 닫기", "축소", "확대", "이전 동영상", "다음 동영상", "동영상 재생", "동영상 일시중지",
        "일시중지", "재생", "다시 재생", "동영상 다시 재생", "더보기", "동영상 더보기", "숨기기", "취소", "지금 재생",
        "설명", "챕터", "실시간 채팅", "가입", "쇼핑", "축하", "thanks", "모두"
    )

    private val TIME_SLASH_REGEX = Regex("""\b(\d{1,2}:\d{2}(?::\d{2})?\s*/\s*\d{1,2}:\d{2}(?::\d{2})?)\b""")
    private val ONLY_TIME_REGEX = Regex("""^[\d:\s/.-]+$""")
    private val KOREAN_SEEKBAR_REGEX = Regex("""(?:(\d+)시간\s*)?(?:(\d+)분\s*)?(?:(\d+)초)?\s*중\s*(?:(\d+)시간\s*)?(?:(\d+)분\s*)?(?:(\d+)초)?""")

    private data class TitleCandidate(
        val text: String,
        val rect: Rect,
        val isExplicitTitleId: Boolean
    )

    private fun isIgnoredTitle(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower in IGNORED_EXACT_TITLES) return true
        if (ONLY_TIME_REGEX.matches(lower)) return true
        if (lower.startsWith("다음 동영상") || lower.startsWith("조회수 ") || lower.startsWith("구독자 ") || lower.contains("구독자 ")) return true
        if (lower.startsWith("@")) return true // 채널 핸들 (예: @RainbowFlavor_) 제외
        if (lower.startsWith("댓글 ") || lower.startsWith("좋아요 ") || lower.startsWith("싫어요 ")) return true
        if (lower.endsWith("초 전") || lower.endsWith("분 전") || lower.endsWith("시간 전") || lower.endsWith("일 전") || lower.endsWith("주 전") || lower.endsWith("개월 전") || lower.endsWith("년 전")) return true
        if (KOREAN_SEEKBAR_REGEX.matches(lower)) return true
        return false
    }

    /**
     * Litho 통합 설명문("영상제목 조회수 1.2만회 3일 전 ...더보기")이나 멀티라인 텍스트에서
     * 순수 영상 제목만 깔끔하게 추출.
     */
    private fun cleanVideoTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmedRaw = raw.trim()
        // 영상 종료 시 뜨는 '다음 동영상\n제목\n채널명' 카드 텍스트는 현재 영상 제목이 아니므로 즉시 제외
        if (trimmedRaw.startsWith("다음 동영상")) return null

        val lines = trimmedRaw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("@") }
        var candidate = lines.firstOrNull() ?: return null

        // Litho 통합 설명문 뒤쪽의 조회수/업로드시각/더보기 제거
        candidate = candidate
            .replace(Regex("""\s*[·•,-]?\s*조회수\s*[\d.,만천억회]+.*$"""), "")
            .replace(Regex("""\s*[·•,-]?\s*[\d.,]+[KMBkmb]?\s*views.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\.{2,}\s*더보기.*$"""), "")
            .replace(Regex("""\s*더보기$"""), "")
            .replace(Regex("""\s*-\s*YouTube$""", RegexOption.IGNORE_CASE), "")
            .trim()

        if (candidate.length !in 2..150) return null
        if (isIgnoredTitle(candidate)) return null
        return candidate
    }

    private fun parseTimePartSeconds(part: String): Int? {
        val nums = part.trim().split(":").map { it.toIntOrNull() ?: return null }
        return when (nums.size) {
            2 -> nums[0] * 60 + nums[1]
            3 -> nums[0] * 3600 + nums[1] * 60 + nums[2]
            else -> null
        }
    }

    private fun parseSlashTimeSeconds(slashStr: String): Pair<Int, Int>? {
        val parts = slashStr.split("/")
        if (parts.size != 2) return null
        val cur = parseTimePartSeconds(parts[0]) ?: return null
        val tot = parseTimePartSeconds(parts[1]) ?: return null
        if (tot <= 0) return null
        return cur to tot
    }

    private fun parseKoreanSeekBar(desc: String): Pair<String, Pair<Int, Int>>? {
        val m = KOREAN_SEEKBAR_REGEX.find(desc) ?: return null
        val totH = m.groupValues[1].toIntOrNull() ?: 0
        val totM = m.groupValues[2].toIntOrNull() ?: 0
        val totS = m.groupValues[3].toIntOrNull() ?: 0
        val curH = m.groupValues[4].toIntOrNull() ?: 0
        val curM = m.groupValues[5].toIntOrNull() ?: 0
        val curS = m.groupValues[6].toIntOrNull() ?: 0
        val totalSec = totH * 3600 + totM * 60 + totS
        val currentSec = curH * 3600 + curM * 60 + curS
        if (totalSec <= 0) return null
        fun fmt(sec: Int): String {
            val h = sec / 3600
            val min = (sec % 3600) / 60
            val s = sec % 60
            return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, min, s) else String.format(java.util.Locale.US, "%d:%02d", min, s)
        }
        return "${fmt(currentSec)} / ${fmt(totalSec)}" to (currentSec to totalSec)
    }

    fun clearBaseline() {
        row = null
        pending = null
        previousAt = 0L
    }

    fun resetSwipeCounts() {
        candidates = 0
        lastRunCandidates = 0
        SwipeDynamicsTracker.reset(ShortformApp.YOUTUBE)
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
        val minIntervalMs = if (YoutubeLongformTracker.isStableLongformPlayback(now)) 1350L else 100L
        if (now - lastScan < minIntervalMs) return
        lastScan = now
        stateAt = now
        state = -1
        try {
            val power = service.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val lock = service.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (!power.isInteractive || lock.isKeyguardLocked) {
                candidates = 0
                nonShortsSince = 0L
                YoutubeLongformTracker.onExternalApp(now, System.currentTimeMillis(), "SCREEN_OFF")
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
                            YoutubeLongformTracker.onReturnedToItv(now, System.currentTimeMillis())
                        } else if (!pkg.isNullOrBlank()) {
                            if (nonShortsSince == 0L) nonShortsSince = now
                            if (now - nonShortsSince >= 15_000L) candidates = 0
                            if (!com.example.itv.infrastructure.jev.JevObservationHub.isTransientSystemPackage(pkg)) {
                                YoutubeLongformTracker.onExternalApp(now, System.currentTimeMillis(), pkg)
                            }
                        }
                        unavailable("YouTube 전면 아님")
                        return
                    }
                    val viewport = Rect()
                    root.getBoundsInScreen(viewport)
                    var panel = false
                    var hasCommentPanelSignal = false
                    var anyShortsPlayerNode = false
                    var recycler = false
                    var dominantShortsSurface = false
                    var hasTopWatchPlayer = false
                    var hasFullscreenPlayer = false
                    var hasWatchPlayerExplicit = false
                    var hasWatchControls = false
                    var hasWatchMetadata = false
                    var hasWatchPageExclusiveControl = false
                    val watchActionKinds = mutableSetOf<String>()
                    var hasInlinePlayerSignal = false
                    var hasFeedHeader = false
                    val feedTopToolbarIcons = mutableSetOf<String>()
                    val playerRect = Rect()
                    var hasEndscreenOverlay = false
                    var hasNextVideoPreviewCard = false
                    var hasBottomNavBar = false
                    val bottomNavTabsSeen = mutableSetOf<String>()
                    var uiPaused = false
                    var uiPlaying = false
                    var uiReplay = false
                    var playbackPositionText: String? = null
                    var currentPlaySec: Int? = null
                    var totalPlaySec: Int? = null
                    val titleCandidates = mutableListOf<TitleCandidate>()
                    var visited = 0
                    var truncated = false
                    val pages = mutableListOf<Pair<Int, Long>>()

                    fun walk(
                        n: AccessibilityNodeInfo,
                        depth: Int,
                        inheritedRow: Int?,
                        inEndscreenSubtree: Boolean,
                        engagementPanelDepth: Int
                    ) {
                        if (!n.isVisibleToUser) return
                        if (++visited > 1500 || depth > 45) {
                            truncated = true
                            return
                        }
                        val id = n.viewIdResourceName
                        val nodeRow = n.collectionItemInfo?.rowIndex ?: inheritedRow
                        val r = Rect()
                        n.getBoundsInScreen(r)

                        val rawText = n.text?.toString()?.trim()
                        val rawDesc = n.contentDescription?.toString()?.trim()

                        val isEngagementPanelNode = id == "com.google.android.youtube:id/app_engagement_panel" ||
                            (id != null && (id.contains("engagement_panel") || id.contains("comment_composer") || id.contains("comments_header")))
                        if (isEngagementPanelNode) {
                            panel = true
                            hasCommentPanelSignal = true
                        }

                        // 댓글 시트 내부 UI 시그널 감지 (헤더의 좌측 '댓글'/'답글', 정렬 칩 '인기순'/'최신순', 입력창 '댓글 추가...')
                        // 일반 쇼츠 우측 액션바의 댓글 아이콘(r.left >= 0.70 * width)과 구분하기 위해 좌측 영역 조건 적용
                        if (rawText in setOf("댓글 추가...", "공개 댓글 추가...", "답글 추가...", "Add a comment...", "인기순", "최신순") ||
                            rawDesc in setOf("댓글 추가...", "공개 댓글 추가...", "답글 추가...", "Add a comment...", "인기순", "최신순")) {
                            hasCommentPanelSignal = true
                        } else if ((rawText == "댓글" || rawText == "답글" || rawText?.startsWith("댓글 ") == true) &&
                            r.left < viewport.width() * 0.50 && r.top < viewport.height() * 0.65) {
                            hasCommentPanelSignal = true
                        }

                        // 쇼츠 플레이어 관련 노드 존재 여부 기록 (댓글 열람 시 상단으로 축소된 쇼츠 플레이어 포함, 홈/피드의 쇼츠 선반은 제외)
                        if (id in SHORTS_SURFACE_IDS || id in SHORTS_PAGE_IDS ||
                            (id != null && (id.startsWith("com.google.android.youtube:id/reel_watch") ||
                                id.startsWith("com.google.android.youtube:id/reel_player") ||
                                id.startsWith("com.google.android.youtube:id/shorts_")) &&
                                !id.contains("shelf") && !id.contains("item"))) {
                            anyShortsPlayerNode = true
                        }

                        if (id in SHORTS_SURFACE_IDS) {
                            recycler = true
                            if (r.intersect(viewport) &&
                                r.width() >= viewport.width() * 0.8 &&
                                r.height() >= viewport.height() * 0.70
                            ) {
                                dominantShortsSurface = true
                            }
                        }
                        if (id in SHORTS_PAGE_IDS) {
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

                        // 배터리 및 스크롤 성능 보호:
                        // 댓글/참여 패널(app_engagement_panel) 내부의 수백 개 댓글 아이템 트리는 순회하지 않고 즉시 생략!
                        val nextEngagementDepth = when {
                            engagementPanelDepth > 0 -> engagementPanelDepth + 1
                            isEngagementPanelNode -> 1
                            else -> 0
                        }
                        if (nextEngagementDepth >= 2) {
                            return
                        }

                        // 하단 내비게이션 탭 바 감지 (홈 / Shorts / 구독 / 내 페이지) - 실제 화면 내에 보이는 경우만 인정
                        val isOnScreen = r.width() > 0 && r.height() > 0 && Rect.intersects(r, viewport)
                        if (isOnScreen && r.top >= viewport.height() * 0.80 && r.top < viewport.height()) {
                            if (id != null && (id.contains("pivot_bar") || id.contains("bottom_bar") || id.contains("bottom_navigation"))) {
                                hasBottomNavBar = true
                            }
                            val labelLower = (rawText ?: rawDesc)?.lowercase()
                            if (labelLower != null && labelLower in BOTTOM_NAV_LABELS && r.width() <= viewport.width() * 0.35) {
                                bottomNavTabsSeen.add(labelLower)
                                if (bottomNavTabsSeen.size >= 2) hasBottomNavBar = true
                            }
                        }

                        // 홈/구독 피드 상단 툴바 및 필터 칩 바 감지 (실제 롱폼 시청 화면 상단은 플레이어가 덮으므로 미노출)
                        if (isOnScreen && r.top <= viewport.height() * 0.24) {
                            if (id != null && (id.contains("feed_filter_bar") || id.contains("youtube_logo") || id.contains("menu_search"))) {
                                hasFeedHeader = true
                            }
                            if (r.top <= viewport.height() * 0.14) {
                                val topDescLower = (rawDesc ?: rawText)?.lowercase()
                                when {
                                    topDescLower == "검색" || topDescLower == "search" -> feedTopToolbarIcons.add("search")
                                    topDescLower == "알림" || topDescLower == "notifications" -> feedTopToolbarIcons.add("noti")
                                    topDescLower == "youtube" -> feedTopToolbarIcons.add("logo")
                                }
                                if (feedTopToolbarIcons.size >= 2) hasFeedHeader = true
                            }
                        }

                        // 홈 피드 인라인 자동재생(피드에서 재생) 전용 시그널 감지
                        val isInlineNode = id != null && (
                            id.contains("inline_player") ||
                                id.contains("inline_time_bar") ||
                                id.contains("inline_playback") ||
                                id.contains("inline_audio") ||
                                id.contains("inline_muted") ||
                                id.contains("inline_subtitle") ||
                                id.contains("feed_player") ||
                                id.contains("muted_autoplay")
                            )
                        if (isInlineNode) {
                            hasInlinePlayerSignal = true
                        }
                        val descOrTextLower = (rawDesc ?: rawText)?.lowercase()
                        if (isOnScreen && descOrTextLower != null && r.width() <= viewport.width() * 0.35 && (
                                descOrTextLower in INLINE_MUTE_LABELS ||
                                    descOrTextLower.startsWith("오디오 음소거") ||
                                    descOrTextLower.startsWith("음소거 해제") ||
                                    descOrTextLower.startsWith("동영상 음소거")
                                )
                        ) {
                            hasInlinePlayerSignal = true
                        }

                        // 롱폼 플레이어 영역 감지 (하단 미니플레이어, 쇼츠 플레이어, 홈 인라인 자동재생 플레이어는 제외)
                        val isMiniplayer = id != null && (id.contains("miniplayer") || id.contains("floaty_bar"))
                        val isShortsNode = id in SHORTS_SURFACE_IDS || id in SHORTS_PAGE_IDS ||
                            (id != null && (id.startsWith("com.google.android.youtube:id/reel_") || id.startsWith("com.google.android.youtube:id/shorts_")))
                        val isLongformCandidate = !isMiniplayer && !isShortsNode && !isInlineNode &&
                            (id in LONGFORM_PLAYER_IDS || (id != null && (id.contains("player_view") || id.contains("watch_player") || id.contains("movie_player"))) ||
                             n.className?.contains("SurfaceView") == true || n.className?.contains("TextureView") == true)
                        if (isLongformCandidate) {
                            if (id in LONGFORM_PLAYER_IDS || id?.contains("watch_player") == true || id?.contains("movie_player") == true) {
                                hasWatchPlayerExplicit = true
                            }
                            if (r.width() >= viewport.width() * 0.80 && r.height() >= viewport.height() * 0.70) {
                                hasFullscreenPlayer = true
                                if (r.width() * r.height() >= playerRect.width() * playerRect.height()) {
                                    playerRect.set(r)
                                }
                            } else if (r.top <= viewport.height() * 0.28 && r.width() >= viewport.width() * 0.75 && r.height() >= viewport.height() * 0.12) {
                                hasTopWatchPlayer = true
                                if (playerRect.isEmpty || r.height() > playerRect.height()) {
                                    playerRect.set(r)
                                }
                            }
                        }

                        // 플레이어 컨트롤 및 시청 페이지 전용 메타데이터/액션 바 감지
                        if (id != null && !isInlineNode && (id.contains("player_control_") || id.contains("time_bar") || id.contains("timebar") || id.contains("fullscreen_button") || id.contains("expand_button"))) {
                            hasWatchControls = true
                        }
                        if (id != null && (id.contains("fullscreen_button") || id.contains("player_collapse") || id.contains("autonav_toggle") || id.contains("next_gen_watch"))) {
                            hasWatchPageExclusiveControl = true
                        }
                        if (isOnScreen && r.top <= viewport.height() * 0.45 && descOrTextLower != null && descOrTextLower in WATCH_EXCLUSIVE_CONTROL_DESCS) {
                            hasWatchPageExclusiveControl = true
                        }
                        if (id != null && (id.contains("watch_metadata") || id.contains("video_metadata") || id.contains("watch_title") || id.contains("player_video_title") || id.contains("subscribe_button"))) {
                            hasWatchMetadata = true
                        }

                        // 세로 시청 페이지 하단 전용 액션 버튼(좋아요/싫어요/공유/리믹스/오프라인 저장/구독) 수집
                        // 하단 내비게이션 탭(r.top >= 0.80 * height)은 제외하여 '구독' 탭 오인 방지
                        if (isOnScreen && r.top >= viewport.height() * 0.18 && r.top < viewport.height() * 0.80 && r.width() <= viewport.width() * 0.50) {
                            val tLower = rawText?.lowercase()
                            val dLower = rawDesc?.lowercase()
                            when {
                                dLower != null && (dLower.contains("이 동영상을 좋아함") || dLower.startsWith("좋아요") || dLower.contains("like this video")) || tLower == "좋아요" ->
                                    watchActionKinds.add("like")
                                dLower != null && (dLower.contains("마음에 들지 않음") || dLower.startsWith("싫어요") || dLower.contains("dislike this video")) || tLower == "싫어요" ->
                                    watchActionKinds.add("dislike")
                                tLower == "공유" || dLower == "공유" || dLower == "동영상 공유" || tLower == "share" || dLower == "share" ->
                                    watchActionKinds.add("share")
                                tLower == "리믹스" || dLower == "리믹스" || tLower == "remix" ->
                                    watchActionKinds.add("remix")
                                tLower == "오프라인 저장" || dLower == "오프라인 저장" || tLower == "download" ->
                                    watchActionKinds.add("download")
                                tLower == "구독" || tLower == "구독중" || tLower == "구독 중" || tLower == "subscribe" || tLower == "subscribed" ||
                                    (dLower != null && (dLower.startsWith("구독") || dLower.contains("채널 구독"))) ->
                                    watchActionKinds.add("subscribe")
                            }
                        }

                        // 엔드스크린 / 종료 추천 카드 감지:
                        // 주의! 일반 컨트롤 오버레이의 '⏭ 다음 동영상' 스킵 버튼(id=player_control_next_button, rawText=null, rawDesc="다음 동영상")은 제외!
                        val isEndscreenNode = id in ENDSCREEN_IDS ||
                            (id != null && (id.contains("endscreen") || id.contains("autonav_preview") || id.contains("autonav_overlay")))

                        // 영상 끝나갈 때 우측 상단에 뜨는 '👁‍🗨 숨기기' 버튼 (3번째 사진의 추천 영상 오버레이 시그니처)
                        val isHideEndscreenBtn = rawText == "숨기기" || rawDesc == "숨기기" || rawDesc?.contains("종료 화면") == true

                        // 영상이 완전히 끝났을 때 상단에 뜨는 '다음 동영상' 프리뷰 카드 (2번째 사진)
                        val isNextVideoCard = id != "com.google.android.youtube:id/player_control_next_button" &&
                            r.top <= viewport.height() * 0.30 &&
                            (rawText == "다음 동영상" || rawText?.startsWith("다음 동영상") == true || rawDesc?.startsWith("다음 동영상\n") == true)

                        if (isEndscreenNode || isHideEndscreenBtn) {
                            hasEndscreenOverlay = true
                        }
                        if (isNextVideoCard) {
                            hasNextVideoPreviewCard = true
                            hasEndscreenOverlay = true
                        }

                        // 재생 / 일시정지 / 다시 재생 버튼 상태 감지
                        if (rawDesc in setOf("다시 재생", "동영상 다시 재생", "Replay video", "Replay")) {
                            uiReplay = true
                            uiPaused = true
                            hasEndscreenOverlay = true
                        } else if (rawDesc in setOf("동영상 재생", "재생", "Play video", "Play")) {
                            uiPaused = true
                        } else if (rawDesc in setOf("동영상 일시중지", "일시중지", "Pause video", "Pause")) {
                            uiPlaying = true
                        }

                        // 재생바(TimeBar / SeekBar) 시간 추출 (예: 7:38 / 12:55 또는 12:53 / 12:55)
                        if (playbackPositionText == null) {
                            val combined = rawText ?: rawDesc
                            if (!combined.isNullOrBlank()) {
                                val slashMatch = TIME_SLASH_REGEX.find(combined)
                                if (slashMatch != null) {
                                    val matchedStr = slashMatch.groupValues[1]
                                    playbackPositionText = matchedStr
                                    parseSlashTimeSeconds(matchedStr)?.let { (cur, tot) ->
                                        currentPlaySec = cur
                                        totalPlaySec = tot
                                    }
                                } else if (id?.contains("time_bar") == true || id?.contains("timebar") == true || n.className == "android.widget.SeekBar") {
                                    val parsed = parseKoreanSeekBar(combined)
                                    if (parsed != null) {
                                        playbackPositionText = parsed.first
                                        currentPlaySec = parsed.second.first
                                        totalPlaySec = parsed.second.second
                                    }
                                }
                            }
                        }

                        val childInEndscreen = inEndscreenSubtree || isEndscreenNode || isNextVideoCard

                        // 영상 제목 후보 수집 (단, 엔드스크린 추천 카드, 다음 동영상 프리뷰 카드, 댓글 패널 내부는 제외!)
                        if (!childInEndscreen && nextEngagementDepth == 0 && titleCandidates.size < 80 && (!rawText.isNullOrBlank() || !rawDesc.isNullOrBlank())) {
                            val isExplicit = id in TITLE_IDS || id?.endsWith("/video_title") == true || id?.contains("player_video_title") == true
                            val cleaned = cleanVideoTitle(rawText ?: rawDesc)
                            if (cleaned != null) {
                                titleCandidates.add(
                                    TitleCandidate(
                                        text = cleaned,
                                        rect = Rect(r),
                                        isExplicitTitleId = isExplicit
                                    )
                                )
                            }
                        }

                        for (i in 0 until n.childCount) {
                            if (visited >= 1500) {
                                truncated = true
                                break
                            }
                            val child = n.getChild(i) ?: continue
                            try {
                                walk(child, depth + 1, nodeRow, childInEndscreen, nextEngagementDepth)
                            } finally {
                                @Suppress("DEPRECATION")
                                child.recycle()
                            }
                        }
                    }

                    walk(root, 0, null, false, 0)

                    val isLandscape = service.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
                        viewport.width() > viewport.height()

                    // 숏폼 댓글 모드 판별:
                    // 1) 쇼츠 컨텍스트(현재 화면에 쇼츠 플레이어 노드가 있거나 직전 모드가 쇼츠/숏폼댓글)에서 댓글 패널이 열렸거나,
                    // 2) 쇼츠 플레이어 노드가 존재하지만 댓글창 때문에 상단으로 축소되어(!dominantShortsSurface) 하단 탭바 없이 떠 있는 경우
                    val inShortsContext = anyShortsPlayerNode || dominantShortsSurface || recycler || YoutubeLongformTracker.isShortsContext()
                    val isShortsCommentMode = (inShortsContext && (panel || hasCommentPanelSignal)) ||
                        (anyShortsPlayerNode && !dominantShortsSurface && !hasBottomNavBar)

                    // 홈/피드 화면 여부와 실제 세로 롱폼 시청 페이지 전용 UI 분리 판별
                    // 홈 피드의 '피드에서 재생(인라인 자동재생)'은 하단 탭(홈/Shorts/구독/내 페이지), 상단 툴바/필터바,
                    // 또는 인라인 음소거 버튼과 함께 나타나므로 실제 롱폼 시청(PORTRAIT_WATCH)과 엄격히 구분함.
                    val hasVisibleBottomNavTabs = bottomNavTabsSeen.size >= 2
                    val isFeedContext = hasInlinePlayerSignal || hasFeedHeader || hasVisibleBottomNavTabs
                    val hasWatchPageUi = hasWatchMetadata || hasWatchPageExclusiveControl || watchActionKinds.size >= 2

                    // 화면 모드 정확 판별
                    val longformMode = when {
                        isShortsCommentMode -> YoutubeWatchMode.SHORTS_COMMENTS
                        dominantShortsSurface || recycler -> YoutubeWatchMode.SHORTS
                        isLandscape && (hasFullscreenPlayer || hasTopWatchPlayer || hasWatchPageUi || hasWatchControls || playbackPositionText != null) ->
                            YoutubeWatchMode.LANDSCAPE_FULLSCREEN
                        hasFullscreenPlayer && !isFeedContext ->
                            YoutubeWatchMode.LANDSCAPE_FULLSCREEN
                        hasInlinePlayerSignal -> YoutubeWatchMode.BROWSE_FEED
                        (hasFeedHeader || hasVisibleBottomNavTabs) && !hasWatchPageExclusiveControl ->
                            YoutubeWatchMode.BROWSE_FEED
                        hasWatchPageUi -> YoutubeWatchMode.PORTRAIT_WATCH
                        !hasBottomNavBar && (hasTopWatchPlayer || hasWatchPlayerExplicit || hasWatchControls || playbackPositionText != null || hasEndscreenOverlay || uiReplay) ->
                            YoutubeWatchMode.PORTRAIT_WATCH
                        else -> YoutubeWatchMode.BROWSE_FEED
                    }

                    // 재생바 시간 기반 종료 여부 보조 판별:
                    val curSec = currentPlaySec
                    val totSec = totalPlaySec
                    val isTimeNearEnd = curSec != null && totSec != null && totSec >= 15 && (totSec - curSec) in 0..3
                    val isDefinitelyMidVideo = curSec != null && totSec != null && totSec >= 15 && (totSec - curSec) > 30 && !uiReplay

                    val hasEndscreen = when {
                        longformMode == YoutubeWatchMode.BROWSE_FEED -> false
                        isDefinitelyMidVideo -> false
                        hasEndscreenOverlay || isTimeNearEnd || uiReplay -> true
                        else -> false
                    }

                    // 엔드스크린(추천 영상 카드 노출) 또는 다음 동영상 프리뷰 상태일 때는
                    // 화면에 뜬 추천 영상/다음 동영상 제목을 현재 영상 제목으로 오인하지 않도록 새 제목 추출을 잠금(null 반환 -> 기존 제목 유지)
                    val detectedTitle: String? = if (hasEndscreen || hasNextVideoPreviewCard) {
                        null
                    } else {
                        when (longformMode) {
                            YoutubeWatchMode.PORTRAIT_WATCH -> {
                                val pBottom = if (!playerRect.isEmpty) playerRect.bottom else (viewport.height() * 0.32).toInt()
                                val maxTitleTop = pBottom + (viewport.height() * 0.18).toInt()
                                val minTitleTop = pBottom - 40

                                val belowPlayer = titleCandidates.filter { c ->
                                    c.rect.top in minTitleTop..maxTitleTop &&
                                        (c.isExplicitTitleId || (c.rect.width() >= viewport.width() * 0.30 && c.rect.left <= viewport.width() * 0.25))
                                }.sortedWith(
                                    compareByDescending<TitleCandidate> { it.isExplicitTitleId }
                                        .thenBy { it.rect.top }
                                        .thenByDescending { it.rect.width() }
                                )

                                val insidePlayer = titleCandidates.filter { c ->
                                    c.rect.top in 0..pBottom &&
                                        (c.isExplicitTitleId || (c.rect.width() >= viewport.width() * 0.30 && c.rect.left <= viewport.width() * 0.25))
                                }.sortedWith(
                                    compareByDescending<TitleCandidate> { it.isExplicitTitleId }
                                        .thenBy { it.rect.top }
                                )

                                (belowPlayer.firstOrNull() ?: insidePlayer.firstOrNull())?.text
                            }

                            YoutubeWatchMode.LANDSCAPE_FULLSCREEN -> {
                                val topLimit = (viewport.height() * 0.22).toInt()
                                titleCandidates.filter { c ->
                                    c.rect.top in 0..topLimit &&
                                        (c.isExplicitTitleId || (c.rect.width() >= viewport.width() * 0.18 && c.rect.left <= viewport.width() * 0.22))
                                }.sortedWith(
                                    compareByDescending<TitleCandidate> { it.isExplicitTitleId }
                                        .thenBy { it.rect.top }
                                        .thenByDescending { it.rect.width() }
                                ).firstOrNull()?.text
                            }

                            else -> null
                        }
                    }

                    // 실제 영상 재생 중 여부 판단 (일시정지 버튼 및 시스템 오디오 출력 결합)
                    val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val isAudioActive = audioManager?.isMusicActive == true
                    val isVideoPlaying = when {
                        longformMode == YoutubeWatchMode.SHORTS -> !uiPaused
                        longformMode != YoutubeWatchMode.PORTRAIT_WATCH && longformMode != YoutubeWatchMode.LANDSCAPE_FULLSCREEN -> false
                        uiPaused -> false
                        uiPlaying -> true
                        else -> isAudioActive
                    }

                    YoutubeLongformTracker.onObserve(
                        nowElapsed = now,
                        nowWall = System.currentTimeMillis(),
                        mode = longformMode,
                        title = detectedTitle,
                        channel = null,
                        hasEndscreen = hasEndscreen,
                        isLandscape = isLandscape,
                        playerBounds = if (longformMode != YoutubeWatchMode.BROWSE_FEED && !playerRect.isEmpty) playerRect else null,
                        isPlaying = isVideoPlaying,
                        playbackPositionText = if (longformMode == YoutubeWatchMode.BROWSE_FEED) null else playbackPositionText
                    )

                    if (longformMode == YoutubeWatchMode.SHORTS_COMMENTS || panel) {
                        if (nonShortsSince == 0L) nonShortsSince = now
                        state = if (now - nonShortsSince < 15_000L && previousAt != 0L && now - previousAt <= 15_000L) {
                            1
                        } else {
                            0
                        }
                        status = "숏폼 댓글·패널 표시 · 시청 유지"
                        return
                    }
                    if (!recycler && !dominantShortsSurface) {
                        // Inside YouTube (Home / Feed / Long-form): preserve candidates while continuously using YouTube
                        state = 0
                        if (truncated) unavailable("검사 한도 초과 · 비교 제외")
                        else unavailable("쇼츠 플레이어 미감지 (롱폼/홈: ${longformMode.label})")
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
