package com.example.itv.infrastructure.observation

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import com.example.itv.domain.jev.JevSettings
import com.example.itv.infrastructure.observation.accessibility.WritingGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 확정/추론된 앱 활동 카테고리
 */
enum class InferredAppCategory(val badge: String, val label: String) {
    VOICE_CALL("📞 음성/보이스톡 통화", "음성 통화·보이스톡 진행 중"),
    WRITING("✍️ 글/메시지 작성", "메시지·텍스트 입력 중"),
    MESSENGER("💬 메신저/소통", "메시지 확인·대화 중"),
    DOCUMENT("📝 문서/업무", "문서·노트 열람/작성 중"),
    VIDEO_MEDIA("🎬 영상/미디어", "영상·스트리밍 시청 중"),
    GAME("🎮 게임", "게임 플레이 중"),
    BROWSER_SEARCH("🌐 웹/탐색", "웹·정보 탐색 중"),
    COMMUNITY_SNS("🗣 커뮤니티/SNS", "커뮤니티·SNS 열람 중"),
    SHOPPING_LIFE("🛍 쇼핑/생활", "쇼핑·배달·생활 서비스 이용 중"),
    FINANCE("💳 금융/결제", "금융·결제 서비스 이용 중"),
    MUSIC_AUDIO("🎵 음악/오디오", "음악·오디오 감상 중"),
    HOME_LAUNCHER("📱 휴대폰 홈 화면", "홈 화면 대기/탐색 중"),
    ITV_OWN("🐶 우리 앱 (ITV)", "ITV 앱 화면 확인 중"),
    SYSTEM("⚙️ 시스템/설정", "시스템·통화·설정 화면"),
    GENERAL_APP("📱 일반 앱", "일반 앱 사용 중")
}

/**
 * 실시간 오디오 파이프라인 종류 (AudioPlaybackConfiguration & AudioManager.mode 기반)
 * 휴대폰 미디어 볼륨을 0(무음)으로 줄여도 오디오 파이프라인이 열려 있으면 그대로 감지됨.
 */
enum class AudioPipelineKind(val label: String) {
    NONE("오디오 없음"),
    VOICE_CALL("📞 음성통화/보이스톡"),
    GAME_AUDIO("🎮 게임 사운드"),
    VIDEO_AUDIO("🎬 영상 스트림"),
    MEDIA_AUDIO("🔊 미디어/음악")
}

/**
 * 현재 화면의 개입 가능 여부 판정
 */
enum class InterventionEligibility(val badge: String) {
    ELIGIBLE("✅ 개입 가능 환경"),
    WAIT_FOR_TRANSITION("⏳ 전환/종료 대기"),
    BLOCKED("⛔ 개입 불가 환경")
}

/**
 * 게임 내 세부 상태 (인게임 플레이 중 vs 판 종료/일시정지 확인 중 vs 로비/결과/서브화면 개입 가능)
 */
enum class GamePhase(val badge: String, val label: String) {
    NONE("", ""),
    ACTIVE_GAMEPLAY("⚔️ 인게임 플레이 중", "전투·매치 조작 진행 중 (개입 금지)"),
    PAUSE_OR_MATCH_END_WAIT("⏸ 일시정지/판 종료 확인 중", "조작 멈춤 · 매치 종료/대기 확인 중"),
    LOBBY_OR_IDLE("🏠 로비/결과 대기", "로비 대기 또는 한 판 종료 상태 (개입 가능)"),
    SUB_OVERLAY_SCREEN("📋 공지/광고/상점 화면", "인게임 외 서브 화면 (개입 가능)")
}

/**
 * 일반 앱 실시간 관측 스냅샷
 */
data class GeneralAppSnapshot(
    val packageName: String? = null,
    val appName: String = "대기 중",
    val category: InferredAppCategory = InferredAppCategory.GENERAL_APP,
    val gamePhase: GamePhase = GamePhase.NONE,
    val inferredActivity: String = "앱 활동 추론 대기 중",
    val inferredScreen: String = "기본 화면",
    val currentActivityClass: String? = null,
    val classificationBasis: String = "대기 중",
    val audioPipeline: AudioPipelineKind = AudioPipelineKind.NONE,
    val isMutedPlayback: Boolean = false,
    val eligibility: InterventionEligibility = InterventionEligibility.BLOCKED,
    val eligibilityReason: String = "관측 대기 중",
    val isMediaPlaying: Boolean = false,
    val isKeyboardOrInputActive: Boolean = false,
    val isLandscape: Boolean = false,
    val isPowerSaveScanMode: Boolean = false,
    val idleSeconds: Long = 0L,
    val recentInteractions10s: Int = 0,
    val currentAppDurationMs: Long = 0L,
    val lastUpdatedWallMs: Long = 0L
)

/**
 * 일반 앱 전환 및 활동 변화 이벤트 로그
 */
data class GeneralAppTransitionEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampWallMs: Long = System.currentTimeMillis(),
    val packageName: String,
    val appName: String,
    val category: InferredAppCategory,
    val inferredActivity: String,
    val inferredScreen: String = "기본 화면",
    val classificationBasis: String = "",
    val eligibility: InterventionEligibility,
    val eligibilityReason: String,
    val durationMs: Long,
    val description: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(timestampWallMs))
}

/**
 * 유튜브·인스타그램 외 모든 포그라운드 앱의 종류와 활동을
 * 1) OS 공식 카테고리(`ApplicationInfo.category` & `FLAG_IS_GAME`)
 * 2) 앱 내부 컴포넌트/네이티브 엔진 시그니처(Unity, Unreal, Godot, Cocos2d, ExoPlayer 등 1회 캐시)
 * 3) 실시간 오디오 파이프라인(`AudioPlaybackConfiguration` — 볼륨 0 무음 재생 및 음성통화 포함)
 * 4) 앱 내 세부 `Activity` 화면 클래스명 + 터치/조작 밀도 기반 게임 플레이/로비 구분
 * 을 결합하여 UI 트리 순회 없이(배터리·용량 영향 0) 확정하는 트래커.
 */
object GeneralAppTracker {

    private data class CachedAppMeta(
        val appName: String,
        val baseCategory: InferredAppCategory,
        val categorySource: String,
        val hasVideoPlayerComponent: Boolean = false
    )

    private data class AudioObservation(
        val kind: AudioPipelineKind,
        val isActive: Boolean,
        val isMutedVolume: Boolean
    )

    private val appMetaCache = ConcurrentHashMap<String, CachedAppMeta>()
    private val lastActivityByPkg = ConcurrentHashMap<String, String>()

    private val _snapshot = MutableStateFlow(GeneralAppSnapshot())
    val snapshot: StateFlow<GeneralAppSnapshot> = _snapshot.asStateFlow()

    private val _history = MutableStateFlow<List<GeneralAppTransitionEvent>>(emptyList())
    val history: StateFlow<List<GeneralAppTransitionEvent>> = _history.asStateFlow()

    var lastExternalAppSession: GeneralAppTransitionEvent? = null
        private set

    /**
     * 외부 앱(게임·OTT 영상·일반 앱)에서 개입 불가/대기(BLOCKED/WAIT_FOR_TRANSITION) 상태였다가
     * 개입 가능(ELIGIBLE) 상태로 전환될 때 호출되는 콜백 (유튜브 롱폼 종료·인스타 피드 복귀와 동일한 역할)
     */
    var onGeneralAppEligibleTransition: ((GeneralAppTransitionEvent) -> Unit)? = null

    private var currentPkg: String? = null
    private var lastCategory: InferredAppCategory? = null
    private var lastGamePhase: GamePhase = GamePhase.NONE
    private var lastScreenLabel: String? = null
    private var lastEligibility: InterventionEligibility? = null
    private var currentAppDurationMs: Long = 0L
    private var lastTickElapsed: Long = 0L
    private var lastUserInteractionElapsed: Long = 0L
    private var lastSubScreenReturnElapsed: Long = 0L
    private var videoPausedSinceElapsed: Long = 0L
    private val interactionTimestamps = ArrayDeque<Long>()

    private const val MAX_HISTORY = 100
    private const val IDLE_POWER_SAVE_MS = 2500L
    private const val GAME_ACTIVE_IDLE_MAX_MS = 5_000L
    private const val GAME_LOBBY_IDLE_CONFIRM_MS = 15_000L
    private const val GAME_MATCH_BURST_TOUCHES_5S = 4
    private const val GAME_LOBBY_MAX_TOUCHES_10S = 4
    private const val VIDEO_PAUSE_CONFIRM_MS = 15_000L
    @Volatile
    private var hudInteractionSuppressUntilElapsed: Long = 0L

    /**
     * 플로팅 HUD를 터치/드래그/축소·확장할 때 발생하는 윈도우/접근성 이벤트가
     * 게임이나 외부 앱의 실제 사용자 조작으로 오인되지 않도록 잠시(700ms) 무시합니다.
     */
    fun notifyHudInteraction(nowElapsed: Long = SystemClock.elapsedRealtime()) {
        hudInteractionSuppressUntilElapsed = nowElapsed + 700L
    }

    @Synchronized
    fun onUserInteraction(nowElapsed: Long = SystemClock.elapsedRealtime()) {
        if (nowElapsed < hudInteractionSuppressUntilElapsed) return
        // 너무 촘촘한(120ms 이내) 동일 제스처 이벤트 중복은 하나로 묶음
        if (nowElapsed - lastUserInteractionElapsed >= 120L) {
            interactionTimestamps.addLast(nowElapsed)
            while (interactionTimestamps.size > 50) {
                interactionTimestamps.removeFirst()
            }
        }
        lastUserInteractionElapsed = nowElapsed
    }

    @Synchronized
    private fun countRecentInteractions(nowElapsed: Long, windowMs: Long = 10_000L): Int {
        val cutoff = nowElapsed - windowMs
        while (interactionTimestamps.isNotEmpty() && interactionTimestamps.first() < nowElapsed - 25_000L) {
            interactionTimestamps.removeFirst()
        }
        return interactionTimestamps.count { it >= cutoff }
    }

    /**
     * 금융, 뱅킹, 카드, 결제, 증권, 가상자산 등 금융 앱 여부를 엄격하게 판별합니다.
     * 금융 거래/조회 중에는 사용자 자산 보호 및 집중 방해 방지를 위해 모든 개입이 완전히 차단됩니다.
     */
    fun isFinancialPackage(pkg: String?, appName: String? = null): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase()

        // 1. 한국 주요 은행 / 카드 / 증권 / 페이 / 가상자산 패키지 키워드
        if (lower.contains("kbstar") ||
            lower.contains("kbbank") ||
            lower.contains("kbcard") ||
            lower.contains("kbsec") ||
            lower.contains("kblife") ||
            lower.contains("kbank") ||
            lower.contains("kakaobank") ||
            lower.contains("kakaopay") ||
            lower.contains("shinhan") ||
            lower.contains("shcard") ||
            lower.contains("wooribank") ||
            lower.contains("wooricard") ||
            lower.contains("hanabank") ||
            lower.contains("hanaskcard") ||
            lower.contains("hanafn") ||
            lower.contains("ibk") ||
            lower.contains("nonghyup") ||
            lower.contains("nh.smart") ||
            lower.contains("nhbank") ||
            lower.contains("nhqv") ||
            lower.contains("scbank") ||
            lower.contains("standardchartered") ||
            lower.contains("epost.pser") ||
            lower.contains("kfcc") ||
            lower.contains("cu.sbanking") ||
            lower.contains("suhyup") ||
            lower.contains("fsb.sbk") ||
            lower.contains("viva.republica") ||
            lower.contains("toss") ||
            lower.contains("samsungpay") ||
            lower.contains("spay") ||
            lower.contains("payco") ||
            lower.contains("naver.pay") ||
            lower.contains("naverpay") ||
            lower.contains("hyundaicard") ||
            lower.contains("samsungcard") ||
            lower.contains("lottecard") ||
            lower.contains("lottesmartpay") ||
            lower.contains("bccard") ||
            lower.contains("kiwoom") ||
            lower.contains("miraeasset") ||
            lower.contains("samsung.pop") ||
            lower.contains("truefriend") ||
            lower.contains("daishin") ||
            lower.contains("upbit") ||
            lower.contains("bithumb") ||
            lower.contains("coinone") ||
            lower.contains("korbit")
        ) {
            return true
        }

        // 2. 범용 금융 키워드 (wallpaper, game 등 제외)
        val containsFinancialWord = lower.contains("bank") ||
            lower.contains("banking") ||
            lower.contains("card") ||
            lower.contains("credit") ||
            lower.contains("finance") ||
            lower.contains("stock")
        if (containsFinancialWord && !lower.contains("wallpaper") && !lower.contains("game")) {
            return true
        }

        // 3. 앱 레이블(이름)에 포함된 키워드
        val name = appName ?: _snapshot.value.takeIf { it.packageName == pkg }?.appName
        if (name != null) {
            val nameLower = name.lowercase()
            if (nameLower.contains("은행") ||
                nameLower.contains("뱅크") ||
                nameLower.contains("뱅킹") ||
                nameLower.contains("금융") ||
                nameLower.contains("카드") ||
                nameLower.contains("페이") ||
                nameLower.contains("증권") ||
                nameLower.contains("저축") ||
                nameLower.contains("결제") ||
                nameLower.contains("kb스타") ||
                nameLower.contains("k스타") ||
                nameLower.contains("국민은행") ||
                nameLower.contains("신한sol") ||
                nameLower.contains("우리won") ||
                nameLower.contains("하나원큐") ||
                nameLower.contains("nh스마트")
            ) {
                return true
            }
        }

        return false
    }

    /**
     * 유튜브·인스타그램 외의 현재 포그라운드 앱이 개입 가능(ELIGIBLE) 상태인지 판별합니다.
     * - false(대기/차단)일 때는 30초 테스트 모드 및 Jev 실제 개입이 보류되고,
     *   true(개입 가능)로 전환되는 시점에 개입이 수행됩니다.
     */
    fun isInterventionDeferredOrBlocked(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (isFinancialPackage(pkg)) return true
        if (pkg == JevSettings.YOUTUBE || pkg == JevSettings.INSTAGRAM) return false
        val snap = _snapshot.value
        if (snap.packageName != pkg) return false
        return snap.eligibility != InterventionEligibility.ELIGIBLE
    }

    /**
     * 현재 외부 앱에서 개입이 대기/보류 중인 구체적인 사유를 반환합니다 (개입 가능 상태이면 null).
     */
    fun blockingOrWaitingReason(pkg: String?): String? {
        if (!isInterventionDeferredOrBlocked(pkg)) return null
        if (isFinancialPackage(pkg)) return "금융/뱅킹 앱 보호 중 · 개입 금지"
        val snap = _snapshot.value
        return "${snap.appName}: ${snap.eligibilityReason}"
    }

    /**
     * AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED 발생 시 호출되어
     * 트리 순회 없이 현재 앱의 세부 Activity 클래스명을 O(1)로 기록합니다.
     */
    fun onWindowStateChanged(pkg: String?, className: String?) {
        if (pkg.isNullOrBlank() || className.isNullOrBlank()) return
        val cls = className.trim()
        // 단순 프레임워크 위젯/다이얼로그/팝업은 제외하고 실제 화면(Activity/FragmentWindow)만 기록
        if (cls.startsWith("android.widget.") ||
            cls.startsWith("android.view.") ||
            cls.startsWith("android.inputmethodservice.") ||
            cls == "android.app.Dialog" ||
            cls == "android.app.AlertDialog" ||
            cls.endsWith("PopupWindow") ||
            cls.endsWith("Toast")
        ) {
            return
        }
        lastActivityByPkg[pkg] = cls
    }

    @Synchronized
    fun isPowerSaveScanEligible(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean {
        val pkg = currentPkg ?: return false
        if (pkg == JevSettings.YOUTUBE || pkg == JevSettings.INSTAGRAM) return false
        return (nowElapsed - lastUserInteractionElapsed) >= IDLE_POWER_SAVE_MS
    }

    private fun resolveAppMeta(context: Context, pkg: String): CachedAppMeta {
        appMetaCache[pkg]?.let { return it }

        val lower = pkg.lowercase()
        val pm = context.packageManager
        var label = pkg.substringAfterLast('.')
        var osCategory = ApplicationInfo.CATEGORY_UNDEFINED
        var isFlagGame = false
        var engineDetected: String? = null
        var hasVideoActivity = false
        var hasChatActivity = false

        try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val resolvedLabel = pm.getApplicationLabel(appInfo)?.toString()?.trim()
            if (!resolvedLabel.isNullOrBlank()) {
                label = resolvedLabel
            }
            @Suppress("DEPRECATION")
            isFlagGame = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                osCategory = appInfo.category
            }

            // 1. 네이티브 라이브러리 디렉터리에서 게임 엔진 시그니처 확인 (소리를 꺼도 100% 게임 확정 가능)
            val nativeDir = appInfo.nativeLibraryDir
            if (!nativeDir.isNullOrBlank()) {
                val files = File(nativeDir).list()?.map { it.lowercase() }.orEmpty()
                engineDetected = when {
                    files.any { it.contains("libunity") || it.contains("libil2cpp") } -> "Unity 엔진 확정"
                    files.any { it.contains("libue4") || it.contains("libunreal") } -> "Unreal 엔진 확정"
                    files.any { it.contains("libcocos2d") } -> "Cocos2d 엔진 확정"
                    files.any { it.contains("libgodot") } -> "Godot 엔진 확정"
                    else -> null
                }
            }

            // 2. 앱에 등록된 Activity 컴포넌트 이름 시그니처 1회 확인
            @Suppress("DEPRECATION")
            val pkgInfo = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            val actNames = pkgInfo.activities?.mapNotNull { it.name?.lowercase() }.orEmpty()
            if (engineDetected == null) {
                engineDetected = when {
                    actNames.any { it.contains("unityplayer") } -> "Unity Activity 확정"
                    actNames.any { it.contains("unrealactivity") || it.contains("epicgames") } -> "Unreal Activity 확정"
                    actNames.any { it.contains("cocos2dx") } -> "Cocos2d Activity 확정"
                    actNames.any { it.contains("godot") } -> "Godot Activity 확정"
                    actNames.any { it.contains("sdlactivity") } -> "SDL 게임엔진 확정"
                    else -> null
                }
            }
            hasVideoActivity = actNames.any {
                it.contains("exoplayer") || it.contains("videoplayer") ||
                    it.contains("liveplayer") || it.contains("vodplayer")
            }
            hasChatActivity = actNames.any {
                it.contains("chatroom") || it.contains("conversationactivity") || it.contains("directmessage")
            }
        } catch (_: Exception) {
            label = fallbackAppName(lower)
        }

        val (baseCategory, source) = classifyBaseCategory(
            pkg = pkg,
            lower = lower,
            appName = label,
            osCategory = osCategory,
            isFlagGame = isFlagGame,
            engineSignature = engineDetected,
            hasVideoActivity = hasVideoActivity,
            hasChatActivity = hasChatActivity
        )
        val meta = CachedAppMeta(
            appName = label,
            baseCategory = baseCategory,
            categorySource = source,
            hasVideoPlayerComponent = hasVideoActivity
        )
        appMetaCache[pkg] = meta
        return meta
    }

    private fun fallbackAppName(lower: String): String = when {
        lower == "com.kakao.talk" -> "카카오톡"
        lower.contains("nexuslauncher") || lower.contains("sec.android.app.launcher") -> "홈 화면"
        lower.contains("chrome") -> "Chrome"
        lower.contains("sbrowser") -> "삼성 인터넷"
        lower.contains("naver") -> "NAVER"
        lower.contains("netflix") -> "넷플릭스"
        lower.contains("discord") -> "Discord"
        lower.contains("telegram") -> "텔레그램"
        lower.contains("messaging") || lower.contains("mms") -> "메시지"
        else -> lower.substringAfterLast('.')
    }

    private fun classifyBaseCategory(
        pkg: String,
        lower: String,
        appName: String?,
        osCategory: Int,
        isFlagGame: Boolean,
        engineSignature: String?,
        hasVideoActivity: Boolean,
        hasChatActivity: Boolean
    ): Pair<InferredAppCategory, String> {
        if (pkg == "com.example.itv" || pkg == "com.team.itv") {
            return InferredAppCategory.ITV_OWN to "ITV 자체 패키지"
        }
        if (JevSettings.isLauncherPackage(pkg)) {
            return InferredAppCategory.HOME_LAUNCHER to "OS 홈 런처"
        }
        if (JevSettings.isExcludedSystemPackage(pkg)) {
            return InferredAppCategory.SYSTEM to "OS 시스템 패키지"
        }

        // 1. 게임: OS 공식 카테고리, FLAG_IS_GAME, 또는 네이티브 게임 엔진(Unity/Unreal 등) 시그니처로 소리와 무관하게 100% 확정
        if (osCategory == ApplicationInfo.CATEGORY_GAME) {
            val detail = if (engineSignature != null) "OS 게임 카테고리 + $engineSignature" else "OS 공식 게임 카테고리 확정"
            return InferredAppCategory.GAME to detail
        }
        if (isFlagGame) {
            return InferredAppCategory.GAME to "OS 게임 플래그(FLAG_IS_GAME) 확정"
        }
        if (engineSignature != null) {
            return InferredAppCategory.GAME to engineSignature
        }
        if (lower.contains(".game") ||
            lower.contains("games.") ||
            lower.startsWith("com.supercell.") ||
            lower.startsWith("com.nexon.") ||
            lower.startsWith("com.netmarble.") ||
            lower.startsWith("com.kakaogames.") ||
            lower.startsWith("com.riotgames.") ||
            lower.startsWith("com.hoyoverse.") ||
            lower.startsWith("com.mihoyo.") ||
            lower.startsWith("com.roblox.") ||
            lower.startsWith("com.mojang.") ||
            lower.startsWith("com.pubg.") ||
            lower.startsWith("com.ea.game") ||
            lower.startsWith("com.devsisters.")
        ) {
            return InferredAppCategory.GAME to "게임사 패키지 확정"
        }

        // 2. 문서 / 노트 / 오피스
        if (lower.contains("docs.editors") ||
            lower == "com.google.android.apps.docs" ||
            lower.startsWith("com.microsoft.office") ||
            lower.contains("notion") ||
            lower.contains("sdoc") ||
            lower.contains("app.notes") ||
            lower.contains("keep") ||
            lower.contains("evernote") ||
            lower.contains("hancom") ||
            lower.contains("obsidian") ||
            lower.contains("goodnotes")
        ) {
            return InferredAppCategory.DOCUMENT to "문서·노트 앱 확정"
        }

        // 3. 메신저 / 이메일 / 소통
        if (JevSettings.isDocumentOrMessagingPackage(pkg) ||
            lower.contains("kakaotalk") ||
            lower.contains("messenger") ||
            lower.contains("messaging") ||
            lower == "com.android.mms" ||
            lower.contains("telegram") ||
            lower.contains("discord") ||
            lower.contains("slack") ||
            lower.contains("whatsapp") ||
            lower.contains("line.android") ||
            lower.contains("weverse") ||
            lower.contains("bubble") ||
            lower.contains("gm") ||
            lower.contains("email") ||
            lower.contains("outlook")
        ) {
            return InferredAppCategory.MESSENGER to "메신저·소통 앱 확정"
        }

        // 4. 영상 / OTT / 스트리밍
        if (osCategory == ApplicationInfo.CATEGORY_VIDEO) {
            return InferredAppCategory.VIDEO_MEDIA to "OS 공식 영상 카테고리 확정"
        }
        if (lower.contains("netflix") ||
            lower.contains("tving") ||
            lower.contains("coupangplay") ||
            lower.contains("wavve") ||
            lower.contains("disney") ||
            lower.contains("watcha") ||
            lower.contains("afreecatv") ||
            lower.contains("soop") ||
            lower.contains("twitch") ||
            lower.contains("chzzk") ||
            lower.contains("videoplayer") ||
            lower.contains("mxtech.videoplayer")
        ) {
            return InferredAppCategory.VIDEO_MEDIA to "영상/OTT 패키지 확정"
        }

        // 5. 음악 / 오디오
        if (osCategory == ApplicationInfo.CATEGORY_AUDIO ||
            lower.contains("melon") ||
            lower.contains("spotify") ||
            lower.contains("music") ||
            lower.contains("genie") ||
            lower.contains("flo") ||
            lower.contains("vibe") ||
            lower.contains("bugs")
        ) {
            return InferredAppCategory.MUSIC_AUDIO to "OS 오디오/음악 앱 확정"
        }

        // 6. 웹 브라우저 / 검색 포털
        if (lower.contains("chrome") ||
            lower.contains("sbrowser") ||
            lower.contains("firefox") ||
            lower.contains("whale") ||
            lower.contains("opera") ||
            lower.contains("brave") ||
            lower == "com.nhn.android.search" ||
            lower == "net.daum.android.daum" ||
            lower == "com.google.android.googlequicksearchbox"
        ) {
            return InferredAppCategory.BROWSER_SEARCH to "웹 브라우저/포털 확정"
        }

        // 7. 커뮤니티 / SNS / 웹툰
        if (osCategory == ApplicationInfo.CATEGORY_SOCIAL) {
            return InferredAppCategory.COMMUNITY_SNS to "OS 공식 소셜/SNS 카테고리 확정"
        }
        if (osCategory == ApplicationInfo.CATEGORY_NEWS) {
            return InferredAppCategory.COMMUNITY_SNS to "OS 뉴스/미디어 카테고리 확정"
        }
        if (lower.contains("everytime") ||
            lower.contains("dcinside") ||
            lower.contains("fmkorea") ||
            lower.contains("reddit") ||
            lower.contains("twitter") ||
            lower.contains("barcelona") ||
            lower.contains("tiktok") ||
            lower.contains("facebook") ||
            lower.contains("band") ||
            lower.contains("cafe") ||
            lower.contains("webtoon") ||
            lower.contains("kakaopage") ||
            lower.contains("series")
        ) {
            return InferredAppCategory.COMMUNITY_SNS to "SNS/커뮤니티/웹툰 패키지 확정"
        }

        // 8. 금융 / 뱅킹 / 결제
        if (isFinancialPackage(pkg, appName)) {
            return InferredAppCategory.FINANCE to "금융/결제 앱 확정"
        }

        // 9. 쇼핑 / 배달 / 생활
        if (lower.contains("coupang") ||
            lower.contains("baemin") ||
            lower.contains("yogiyo") ||
            lower.contains("musinsa") ||
            lower.contains("oliveyoung") ||
            lower.contains("karrot") ||
            lower.contains("daangn") ||
            lower.contains("zigzag") ||
            lower.contains("ably") ||
            lower.contains("naver.map") ||
            lower.contains("kakaomap") ||
            osCategory == ApplicationInfo.CATEGORY_MAPS
        ) {
            return InferredAppCategory.SHOPPING_LIFE to "쇼핑/배달/지도 앱 확정"
        }

        if (osCategory == ApplicationInfo.CATEGORY_PRODUCTIVITY) {
            return InferredAppCategory.DOCUMENT to "OS 생산성/업무 카테고리 확정"
        }
        if (hasChatActivity) {
            return InferredAppCategory.MESSENGER to "앱 내부 채팅 컴포넌트 감지"
        }
        if (hasVideoActivity) {
            return InferredAppCategory.VIDEO_MEDIA to "앱 내부 영상 플레이어 컴포넌트 감지"
        }

        return InferredAppCategory.GENERAL_APP to "일반 앱"
    }

    /**
     * AudioManager.getActivePlaybackConfigurations() 및 mode를 검사하여
     * 실제 음성통화/게임사운드/영상스트림/음악 재생 여부를 판별합니다.
     * 휴대폰 볼륨을 0(무음)으로 줄여도 오디오 파이프라인 자체는 활성 상태이므로 그대로 감지됩니다.
     */
    private fun inspectAudioPipeline(context: Context): AudioObservation {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return AudioObservation(AudioPipelineKind.NONE, false, false)

            val mode = am.mode
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                return AudioObservation(AudioPipelineKind.VOICE_CALL, true, false)
            }

            val mediaVolume = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(1)
            val isMutedVol = mediaVolume <= 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val configs = am.activePlaybackConfigurations.orEmpty()
                var hasVoice = false
                var hasGame = false
                var hasMovie = false
                var hasMedia = false

                for (cfg in configs) {
                    val attr = cfg.audioAttributes ?: continue
                    when (attr.usage) {
                        AudioAttributes.USAGE_VOICE_COMMUNICATION,
                        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> hasVoice = true
                        AudioAttributes.USAGE_GAME -> hasGame = true
                        AudioAttributes.USAGE_MEDIA,
                        AudioAttributes.USAGE_UNKNOWN -> {
                            if (attr.contentType == AudioAttributes.CONTENT_TYPE_MOVIE) {
                                hasMovie = true
                            } else {
                                hasMedia = true
                            }
                        }
                    }
                }

                when {
                    hasVoice -> return AudioObservation(AudioPipelineKind.VOICE_CALL, true, false)
                    hasGame -> return AudioObservation(AudioPipelineKind.GAME_AUDIO, true, isMutedVol)
                    hasMovie -> return AudioObservation(AudioPipelineKind.VIDEO_AUDIO, true, isMutedVol)
                    hasMedia -> return AudioObservation(AudioPipelineKind.MEDIA_AUDIO, true, isMutedVol)
                }
            }

            if (am.isMusicActive) {
                AudioObservation(AudioPipelineKind.MEDIA_AUDIO, true, isMutedVol)
            } else {
                AudioObservation(AudioPipelineKind.NONE, false, isMutedVol)
            }
        } catch (_: Exception) {
            AudioObservation(AudioPipelineKind.NONE, false, false)
        }
    }

    /**
     * 게임 내 서브/로비 전용 Activity(광고, 공지 웹뷰, 결제창, 설정 등)인지 판별합니다.
     * 전투/매치 도중에는 열리지 않는 화면이므로, 이 화면에 있으면 인게임 플레이 중이 아닌 로비/대기 상태로 확정할 수 있습니다.
     */
    private fun isGameLobbyOrSubOverlayActivity(activityClass: String?): Boolean {
        if (activityClass.isNullOrBlank()) return false
        val lower = activityClass.lowercase()
        return lower.contains("adactivity") ||
            lower.contains("unityads") ||
            lower.contains("admob") ||
            lower.contains("applovin") ||
            lower.contains("ironsource") ||
            lower.contains("vungle") ||
            lower.contains("rewarded") ||
            lower.contains("webview") ||
            lower.contains("browser") ||
            lower.contains("notice") ||
            lower.contains("lobby") ||
            lower.contains("billing") ||
            lower.contains("purchase") ||
            lower.contains("checkout") ||
            lower.contains("setting") ||
            lower.contains("login") ||
            lower.contains("auth") ||
            lower.contains("cafe")
    }

    /**
     * 게임(GAME) 카테고리일 때 터치/조작 밀도(이력제 Hysteresis 적용), 유휴 시간(Idle), 서브 Activity, 보이스챗 여부를 조합하여
     * 1) ACTIVE_GAMEPLAY (⛔ 개입 불가)
     * 2) PAUSE_OR_MATCH_END_WAIT (⏳ 5~15초 전환/종료 대기)
     * 3) LOBBY_OR_IDLE / SUB_OVERLAY_SCREEN (✅ 개입 가능 — 로비에서 단발성 버튼 클릭·보상 수령·탭 전환을 해도 초록불 유지!)
     * 3단계를 판별합니다.
     */
    private fun evaluateGamePhase(
        activityClass: String?,
        audio: AudioObservation,
        idleMs: Long,
        recentInteractions5s: Int,
        recentInteractions10s: Int,
        prevGamePhase: GamePhase,
        nowElapsed: Long
    ): Pair<GamePhase, String> {
        val idleSec = (idleMs / 1000L).coerceAtLeast(0L)

        // 1. 공지/웹뷰/보상형 광고/결제 등 인게임 외 서브 Activity가 열려 있는 경우 -> 즉시 개입 가능
        if (isGameLobbyOrSubOverlayActivity(activityClass)) {
            lastSubScreenReturnElapsed = nowElapsed
            val simple = activityClass?.substringAfterLast('.') ?: "서브화면"
            return GamePhase.SUB_OVERLAY_SCREEN to "게임 내 공지·광고·서브 화면($simple) 열람 중"
        }

        // 2. 인게임 보이스챗(음성통화 파이프라인)이 켜져 있으면 팀 매치 진행 중 -> 무조건 개입 불가
        if (audio.kind == AudioPipelineKind.VOICE_CALL) {
            return GamePhase.ACTIVE_GAMEPLAY to "인게임 보이스챗·팀 매치 진행 중 (개입 금지)"
        }

        // 3. 서브 화면(광고/공지)에서 메인 엔진 화면으로 방금 복귀한 직후(15초 이내)이고 고밀도 전투 연타가 아니면 로비 복귀 상태 유지
        if (lastSubScreenReturnElapsed > 0L &&
            (nowElapsed - lastSubScreenReturnElapsed) < 15_000L &&
            recentInteractions5s < GAME_MATCH_BURST_TOUCHES_5S &&
            recentInteractions10s <= GAME_LOBBY_MAX_TOUCHES_10S
        ) {
            return GamePhase.LOBBY_OR_IDLE to "서브 화면 종료 후 로비 탐색 중 (10초간 ${recentInteractions10s}회 터치)"
        }

        // 4. [핵심: 로비/결과 화면의 단발성 터치 허용 (Hysteresis)]
        // 이미 로비/결과 대기(LOBBY_OR_IDLE 또는 SUB_OVERLAY_SCREEN) 상태로 확정된 경우,
        // 결과창 '확인' 버튼 클릭, 보상 수령, 상점/로비 탭 이동 등 저밀도 터치(5초 내 3회 이하 & 10초 내 4회 이하)는
        // 빨간불(인게임 전투)로 바꾸지 않고 로비 탐색(🟢 개입 가능) 상태를 그대로 유지합니다!
        // 실제 새 매치/전투가 시작되어 빠른 연속 조작(5초 내 4회 이상 또는 10초 내 5회 이상)이 들어올 때만 인게임 플레이로 전환합니다.
        val wasInLobbyOrResult = prevGamePhase == GamePhase.LOBBY_OR_IDLE || prevGamePhase == GamePhase.SUB_OVERLAY_SCREEN
        if (wasInLobbyOrResult) {
            val isNewMatchBurst = recentInteractions5s >= GAME_MATCH_BURST_TOUCHES_5S ||
                recentInteractions10s > GAME_LOBBY_MAX_TOUCHES_10S
            if (!isNewMatchBurst) {
                val detail = if (idleMs < GAME_ACTIVE_IDLE_MAX_MS && recentInteractions10s > 0) {
                    "로비·결과창 메뉴 조작 중 (단발 터치 ${recentInteractions10s}회 · 개입 가능 유지)"
                } else {
                    val audioHint = if (!audio.isActive) " · 사운드 정지" else ""
                    "로비 대기 / 매치 종료 화면 (${idleSec}초 무조작$audioHint)"
                }
                return GamePhase.LOBBY_OR_IDLE to detail
            }
        }

        // 5. 인게임 플레이 상태이거나 새 매치의 고밀도 연속 조작이 감지된 경우: 최근 5초 이내 조작 시 개입 불가
        if (idleMs < GAME_ACTIVE_IDLE_MAX_MS) {
            val detail = if (recentInteractions10s >= 3) {
                "연속 조작 플레이 중 (최근10초 ${recentInteractions10s}회 입력)"
            } else {
                "실시간 인게임 조작 중 (${idleSec}초 전 터치 · 5초 무조작 시 대기 전환)"
            }
            return GamePhase.ACTIVE_GAMEPLAY to detail
        }

        // 6. 인게임 플레이 중 조작이 멈춘 지 5초 ~ 15초 사이: 한 판이 끝나 결과 화면을 보는 중인지 일시 멈춤인지 확인 대기
        if (idleMs < GAME_LOBBY_IDLE_CONFIRM_MS) {
            val remainSec = ((GAME_LOBBY_IDLE_CONFIRM_MS - idleMs + 999L) / 1000L).coerceAtLeast(1L)
            return GamePhase.PAUSE_OR_MATCH_END_WAIT to
                "조작 멈춤 ${idleSec}초 경과 (판 종료/로비 확정까지 ${remainSec}초 대기)"
        }

        // 7. 15초 이상 조작 없음: 한 판이 끝난 결과/종료 화면이거나 로비 대기 상태로 확정 -> 이후 단발성 로비 터치가 허용됨
        val audioHint = if (!audio.isActive) " · 사운드 정지" else ""
        return GamePhase.LOBBY_OR_IDLE to "로비 대기 / 매치 종료 화면 (${idleSec}초 무조작$audioHint)"
    }

    /**
     * 현재 열린 Activity 클래스명을 분석하여 소리가 꺼져 있어도 어떤 앱 내 화면인지 판별합니다.
     */
    private fun inferScreenFromActivity(
        activityClass: String?,
        baseCategory: InferredAppCategory,
        isLandscape: Boolean
    ): Pair<String, InferredAppCategory?> {
        if (activityClass.isNullOrBlank()) {
            val defaultScreen = when (baseCategory) {
                InferredAppCategory.GAME -> if (isLandscape) "가로 전체화면 게임 화면" else "게임 플레이 화면"
                InferredAppCategory.VIDEO_MEDIA -> if (isLandscape) "가로 전체화면 플레이어" else "영상/콘텐츠 화면"
                InferredAppCategory.MESSENGER -> "대화/메신저 화면"
                InferredAppCategory.DOCUMENT -> "문서/노트 화면"
                else -> if (isLandscape) "가로 전체화면" else "기본 실행 화면"
            }
            return defaultScreen to null
        }

        val simple = activityClass.substringAfterLast('.')
        val lower = activityClass.lowercase()

        return when {
            lower.contains("incall") || lower.contains("voip") || lower.contains("callactivity") || lower.contains("rtcactivity") ->
                "통화/보이스톡 화면 ($simple)" to InferredAppCategory.VOICE_CALL

            isGameLobbyOrSubOverlayActivity(activityClass) && baseCategory == InferredAppCategory.GAME ->
                "게임 로비/공지/광고 서브화면 ($simple)" to InferredAppCategory.GAME

            lower.contains("unityplayer") || lower.contains("unreal") || lower.contains("cocos2d") ||
                lower.contains("godot") || lower.contains("gameactivity") ->
                "게임 엔진 화면 ($simple)" to InferredAppCategory.GAME

            lower.contains("player") || lower.contains("video") || lower.contains("watch") ||
                lower.contains("fullscreen") || lower.contains("liveactivity") || lower.contains("vod") ->
                "영상 플레이어 화면 ($simple)" to InferredAppCategory.VIDEO_MEDIA

            lower.contains("chat") || lower.contains("conversation") || lower.contains("message") ||
                lower.contains("direct") || lower.contains("talk") ->
                "채팅·대화방 화면 ($simple)" to InferredAppCategory.MESSENGER

            lower.contains("editor") || lower.contains("write") || lower.contains("compose") ||
                lower.contains("note") || lower.contains("doc") ->
                "글·문서 작성 화면 ($simple)" to InferredAppCategory.DOCUMENT

            lower.contains("toon") || lower.contains("comic") || lower.contains("viewer") ||
                lower.contains("reader") || lower.contains("article") || lower.contains("detail") ->
                "콘텐츠/뷰어 열람 화면 ($simple)" to null

            lower.contains("search") || lower.contains("browser") || lower.contains("webview") ->
                "검색·웹 탐색 화면 ($simple)" to null

            lower.contains("pay") || lower.contains("order") || lower.contains("cart") || lower.contains("checkout") ->
                "주문·결제 화면 ($simple)" to null

            lower.contains("setting") || lower.contains("preference") ->
                "앱 설정 화면 ($simple)" to null

            lower.contains("main") || lower.contains("home") || lower.contains("feed") ||
                lower.contains("timeline") || lower.contains("lobby") ->
                "메인/홈 피드 화면 ($simple)" to null

            else -> "실행 화면 ($simple)" to null
        }
    }

    /**
     * WritingGuardService 스캔 주기마다 호출되어 현재 포그라운드 앱의 활동과 개입 가능 여부를 갱신.
     */
    @Synchronized
    fun onObserve(
        context: Context,
        pkg: String?,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis()
    ) {
        if (pkg.isNullOrBlank()) return

        // 유튜브 및 인스타그램은 전용 트래커가 상세 처리하되, 일반 앱 체류 타이머는 정지
        if (pkg == JevSettings.YOUTUBE || pkg == JevSettings.INSTAGRAM) {
            if (currentPkg != null && currentPkg != pkg) {
                recordExitIfMeaningful(nowWall)
            }
            currentPkg = pkg
            currentAppDurationMs = 0L
            lastTickElapsed = 0L
            videoPausedSinceElapsed = 0L
            return
        }

        val prevPkg = currentPkg
        if (prevPkg != pkg) {
            if (prevPkg != null && prevPkg != JevSettings.YOUTUBE && prevPkg != JevSettings.INSTAGRAM) {
                recordExitIfMeaningful(nowWall)
            }
            currentPkg = pkg
            currentAppDurationMs = 0L
            lastTickElapsed = nowElapsed
            lastUserInteractionElapsed = nowElapsed
            lastSubScreenReturnElapsed = 0L
            videoPausedSinceElapsed = 0L
            lastGamePhase = GamePhase.NONE
            interactionTimestamps.clear()
            interactionTimestamps.addLast(nowElapsed)
        } else {
            val delta = if (lastTickElapsed > 0L) (nowElapsed - lastTickElapsed).coerceIn(0L, 3500L) else 0L
            currentAppDurationMs += delta
            lastTickElapsed = nowElapsed
            // ITV 앱 내부에 머무는 동안에는 AudioManager/PackageManager Binder IPC를 반복 호출하지 않음
            if (pkg == context.packageName) {
                return
            }
        }

        val meta = resolveAppMeta(context, pkg)
        val guardReason = WritingGuard.blockReason()
        val isWritingActive = guardReason != null && (
            guardReason.contains("키보드") ||
                guardReason.contains("입력란") ||
                guardReason.contains("작성") ||
                guardReason.contains("메신저")
            )

        val isLandscape = try {
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        } catch (_: Exception) {
            false
        }

        val audio = inspectAudioPipeline(context)
        val isMediaPlaying = audio.isActive
        val activityClass = lastActivityByPkg[pkg]
        val (screenLabel, screenCategoryOverride) = inferScreenFromActivity(activityClass, meta.baseCategory, isLandscape)

        // 4중 신호(OS 카테고리/엔진 + 오디오 파이프라인 + Activity 화면 + 입력/회전) 결합으로 최종 카테고리 확정
        val effectiveCategory = when {
            meta.baseCategory == InferredAppCategory.ITV_OWN -> InferredAppCategory.ITV_OWN
            meta.baseCategory == InferredAppCategory.HOME_LAUNCHER -> InferredAppCategory.HOME_LAUNCHER
            meta.baseCategory == InferredAppCategory.SYSTEM -> InferredAppCategory.SYSTEM
            audio.kind == AudioPipelineKind.VOICE_CALL && meta.baseCategory != InferredAppCategory.GAME ->
                InferredAppCategory.VOICE_CALL
            screenCategoryOverride == InferredAppCategory.VOICE_CALL ->
                InferredAppCategory.VOICE_CALL
            meta.baseCategory == InferredAppCategory.DOCUMENT || screenCategoryOverride == InferredAppCategory.DOCUMENT ->
                InferredAppCategory.DOCUMENT
            isWritingActive -> InferredAppCategory.WRITING
            meta.baseCategory == InferredAppCategory.MESSENGER || screenCategoryOverride == InferredAppCategory.MESSENGER ->
                InferredAppCategory.MESSENGER
            meta.baseCategory == InferredAppCategory.GAME ||
                screenCategoryOverride == InferredAppCategory.GAME ||
                audio.kind == AudioPipelineKind.GAME_AUDIO ->
                InferredAppCategory.GAME
            meta.baseCategory == InferredAppCategory.VIDEO_MEDIA ||
                screenCategoryOverride == InferredAppCategory.VIDEO_MEDIA ||
                audio.kind == AudioPipelineKind.VIDEO_AUDIO ->
                InferredAppCategory.VIDEO_MEDIA
            (meta.baseCategory == InferredAppCategory.BROWSER_SEARCH ||
                meta.baseCategory == InferredAppCategory.GENERAL_APP ||
                meta.baseCategory == InferredAppCategory.COMMUNITY_SNS) &&
                isMediaPlaying && isLandscape ->
                InferredAppCategory.VIDEO_MEDIA
            else -> meta.baseCategory
        }

        val idleMs = if (lastUserInteractionElapsed > 0L) {
            (nowElapsed - lastUserInteractionElapsed).coerceAtLeast(0L)
        } else {
            0L
        }
        val idleSec = idleMs / 1000L
        val recentTouches5s = countRecentInteractions(nowElapsed, 5_000L)
        val recentTouches10s = countRecentInteractions(nowElapsed, 10_000L)

        // 게임 카테고리일 때 세부 단계(인게임 플레이 vs 판 종료 확인 대기 vs 로비/서브화면) 평가
        val (gamePhase, gamePhaseReason) = if (effectiveCategory == InferredAppCategory.GAME) {
            evaluateGamePhase(
                activityClass = activityClass,
                audio = audio,
                idleMs = idleMs,
                recentInteractions5s = recentTouches5s,
                recentInteractions10s = recentTouches10s,
                prevGamePhase = if (prevPkg == pkg) lastGamePhase else GamePhase.NONE,
                nowElapsed = nowElapsed
            )
        } else {
            GamePhase.NONE to ""
        }

        // 외부 영상/OTT 앱 일시정지 경과 시간 추적
        if (effectiveCategory == InferredAppCategory.VIDEO_MEDIA) {
            if (audio.isActive) {
                videoPausedSinceElapsed = 0L
            } else if (videoPausedSinceElapsed == 0L) {
                videoPausedSinceElapsed = nowElapsed
            }
        } else {
            videoPausedSinceElapsed = 0L
        }
        val videoPausedMs = if (videoPausedSinceElapsed > 0L) (nowElapsed - videoPausedSinceElapsed).coerceAtLeast(0L) else 0L

        // 확정 근거 요약 문자열 생성
        val basisParts = mutableListOf<String>()
        basisParts.add(meta.categorySource)
        if (!activityClass.isNullOrBlank()) {
            basisParts.add("화면:${activityClass.substringAfterLast('.')}")
        }
        when {
            audio.kind != AudioPipelineKind.NONE && audio.isMutedVolume ->
                basisParts.add("${audio.kind.label}(🔇볼륨0 무음감지)")
            audio.kind != AudioPipelineKind.NONE ->
                basisParts.add(audio.kind.label)
            effectiveCategory == InferredAppCategory.GAME || effectiveCategory == InferredAppCategory.VIDEO_MEDIA ->
                basisParts.add("무음/비오디오 확정")
        }
        if (effectiveCategory == InferredAppCategory.GAME && gamePhase != GamePhase.NONE) {
            basisParts.add("${gamePhase.badge}(10초터치 ${recentTouches10s}회·무조작 ${idleSec}초)")
        }
        val classificationBasis = basisParts.joinToString(" + ")

        val orientSuffix = if (isLandscape) " (가로)" else ""
        val muteSuffix = if (audio.isActive && audio.isMutedVolume) " [🔇볼륨0 무음 재생]" else ""
        val inferredActivity = when (effectiveCategory) {
            InferredAppCategory.ITV_OWN ->
                "우리 앱(ITV) 화면 확인 중"
            InferredAppCategory.HOME_LAUNCHER ->
                "휴대폰 바탕화면(홈) 대기·탐색 중"
            InferredAppCategory.SYSTEM ->
                "'${meta.appName}' 시스템·설정 화면 조작 중"
            InferredAppCategory.VOICE_CALL ->
                "'${meta.appName}'에서 음성 통화 / 보이스톡 진행 중"
            InferredAppCategory.WRITING -> {
                val targetDesc = when (meta.baseCategory) {
                    InferredAppCategory.MESSENGER -> "메시지·답장 작성 중"
                    InferredAppCategory.DOCUMENT -> "문서·노트 작성 중"
                    InferredAppCategory.BROWSER_SEARCH -> "검색어·글 입력 중"
                    InferredAppCategory.COMMUNITY_SNS -> "게시글·댓글 작성 중"
                    InferredAppCategory.GAME -> "게임 내 채팅 입력 중"
                    else -> "텍스트 입력·작성 중"
                }
                "'${meta.appName}'에서 $targetDesc · $screenLabel"
            }
            InferredAppCategory.MESSENGER ->
                "'${meta.appName}' · $screenLabel"
            InferredAppCategory.DOCUMENT ->
                "'${meta.appName}' · $screenLabel"
            InferredAppCategory.VIDEO_MEDIA -> {
                val playState = when {
                    audio.isActive && audio.isMutedVolume -> "영상 재생 시청 중 (🔇무음 볼륨0)"
                    audio.isActive -> "영상/미디어 재생 시청 중"
                    (screenCategoryOverride == InferredAppCategory.VIDEO_MEDIA || isLandscape) && videoPausedMs < VIDEO_PAUSE_CONFIRM_MS ->
                        "영상 일시정지 (${videoPausedMs / 1000L}초 경과)"
                    else -> "영상 종료·콘텐츠 목록 탐색 중"
                }
                "'${meta.appName}' $playState$orientSuffix"
            }
            InferredAppCategory.GAME -> {
                val audioTag = when {
                    audio.isActive && audio.isMutedVolume -> " · 🔇볼륨0"
                    audio.isActive -> " · 🔊사운드"
                    else -> " · 🔇무음"
                }
                "'${meta.appName}' ${gamePhase.badge}$orientSuffix$audioTag"
            }
            InferredAppCategory.BROWSER_SEARCH ->
                if (isMediaPlaying) "'${meta.appName}' 웹 미디어 재생·탐색 중$muteSuffix"
                else "'${meta.appName}' · $screenLabel"
            InferredAppCategory.COMMUNITY_SNS ->
                "'${meta.appName}' · $screenLabel"
            InferredAppCategory.SHOPPING_LIFE ->
                "'${meta.appName}' · $screenLabel"
            InferredAppCategory.FINANCE ->
                "'${meta.appName}' · $screenLabel"
            InferredAppCategory.MUSIC_AUDIO ->
                "'${meta.appName}' 음악·오디오 감상 중$muteSuffix"
            InferredAppCategory.GENERAL_APP ->
                "'${meta.appName}' · $screenLabel$orientSuffix"
        }

        // 개입 가능 환경 vs 전환 대기 vs 개입 불가 환경 판정 (게임 3단계 & 외부 영상 3단계 포함)
        val (eligibility, eligibilityReason) = when {
            effectiveCategory == InferredAppCategory.ITV_OWN ->
                InterventionEligibility.BLOCKED to "우리 앱(ITV) 내부 화면 (개입 제외)"
            effectiveCategory == InferredAppCategory.HOME_LAUNCHER ->
                InterventionEligibility.BLOCKED to "휴대폰 홈 화면 (개입 제외)"
            effectiveCategory == InferredAppCategory.SYSTEM ->
                InterventionEligibility.BLOCKED to "시스템/통화/설정 화면 (개입 제외)"
            effectiveCategory == InferredAppCategory.VOICE_CALL ->
                InterventionEligibility.BLOCKED to "음성 통화·보이스톡 진행 중 (개입 금지)"
            isWritingActive ->
                InterventionEligibility.BLOCKED to "키보드·입력창 활성 (${guardReason ?: "작성 중 개입 금지 원칙"})"
            effectiveCategory == InferredAppCategory.FINANCE || isFinancialPackage(pkg, meta.appName) ->
                InterventionEligibility.BLOCKED to "금융·뱅킹 앱 보호 중 (금융 작업 중 개입 금지 원칙)"
            JevSettings.isDocumentOrMessagingPackage(pkg) || effectiveCategory == InferredAppCategory.MESSENGER ->
                InterventionEligibility.BLOCKED to "메신저 앱 보호 중 (메시지 중 개입 금지 원칙)"
            effectiveCategory == InferredAppCategory.DOCUMENT ->
                InterventionEligibility.BLOCKED to "문서·노트 작업 보호 중 (작성 중 개입 금지 원칙)"
            effectiveCategory == InferredAppCategory.GAME -> when (gamePhase) {
                GamePhase.ACTIVE_GAMEPLAY ->
                    InterventionEligibility.BLOCKED to "인게임 플레이 중 개입 금지 ($gamePhaseReason)"
                GamePhase.PAUSE_OR_MATCH_END_WAIT ->
                    InterventionEligibility.WAIT_FOR_TRANSITION to gamePhaseReason
                GamePhase.LOBBY_OR_IDLE, GamePhase.SUB_OVERLAY_SCREEN ->
                    InterventionEligibility.ELIGIBLE to "게임 개입 가능 타이밍! ($gamePhaseReason)"
                GamePhase.NONE ->
                    InterventionEligibility.ELIGIBLE to "게임 대기 중"
            }
            effectiveCategory == InferredAppCategory.VIDEO_MEDIA -> when {
                audio.isActive ->
                    InterventionEligibility.BLOCKED to "영상 재생 시청 중 개입 금지 (일시정지·종료 시 개입)"
                (screenCategoryOverride == InferredAppCategory.VIDEO_MEDIA || isLandscape) && videoPausedMs < VIDEO_PAUSE_CONFIRM_MS -> {
                    val remainSec = ((VIDEO_PAUSE_CONFIRM_MS - videoPausedMs + 999L) / 1000L).coerceAtLeast(1L)
                    InterventionEligibility.WAIT_FOR_TRANSITION to "영상 일시정지/무음 (${remainSec}초 후 개입 가능 전환)"
                }
                else ->
                    InterventionEligibility.ELIGIBLE to "영상 시청 정지 / 탐색 화면 (개입 가능)"
            }
            guardReason != null ->
                InterventionEligibility.BLOCKED to "보호 조건 활성 ($guardReason)"
            else ->
                InterventionEligibility.ELIGIBLE to "개입 가능 환경 ($classificationBasis)"
        }

        val powerSave = isPowerSaveScanEligible(nowElapsed)

        val prevCat = lastCategory
        val prevPhase = lastGamePhase
        val prevScreen = lastScreenLabel
        val prevElig = lastEligibility
        var transitionedToEligibleEvent: GeneralAppTransitionEvent? = null

        if (prevPkg != pkg || prevCat != effectiveCategory || prevPhase != gamePhase || prevScreen != screenLabel || prevElig != eligibility) {
            if (effectiveCategory != InferredAppCategory.ITV_OWN) {
                val event = GeneralAppTransitionEvent(
                    timestampWallMs = nowWall,
                    packageName = pkg,
                    appName = meta.appName,
                    category = effectiveCategory,
                    inferredActivity = inferredActivity,
                    inferredScreen = screenLabel,
                    classificationBasis = classificationBasis,
                    eligibility = eligibility,
                    eligibilityReason = eligibilityReason,
                    durationMs = currentAppDurationMs,
                    description = "[${meta.appName}] $inferredActivity → ${eligibility.badge} ($eligibilityReason)"
                )
                addEvent(event)
                lastExternalAppSession = event

                // 동일 앱(예: 게임이나 OTT 영상) 안에서 BLOCKED/WAIT_FOR_TRANSITION -> ELIGIBLE로 전환된 순간 콜백 트리거
                if (prevPkg == pkg &&
                    prevElig != null &&
                    prevElig != InterventionEligibility.ELIGIBLE &&
                    eligibility == InterventionEligibility.ELIGIBLE
                ) {
                    transitionedToEligibleEvent = event
                }
            }
            lastCategory = effectiveCategory
            lastGamePhase = gamePhase
            lastScreenLabel = screenLabel
            lastEligibility = eligibility
        }

        _snapshot.value = GeneralAppSnapshot(
            packageName = pkg,
            appName = meta.appName,
            category = effectiveCategory,
            gamePhase = gamePhase,
            inferredActivity = inferredActivity,
            inferredScreen = screenLabel,
            currentActivityClass = activityClass,
            classificationBasis = classificationBasis,
            audioPipeline = audio.kind,
            isMutedPlayback = audio.isActive && audio.isMutedVolume,
            eligibility = eligibility,
            eligibilityReason = eligibilityReason,
            isMediaPlaying = isMediaPlaying,
            isKeyboardOrInputActive = isWritingActive,
            isLandscape = isLandscape,
            isPowerSaveScanMode = powerSave,
            idleSeconds = idleSec,
            recentInteractions10s = recentTouches10s,
            currentAppDurationMs = currentAppDurationMs,
            lastUpdatedWallMs = nowWall
        )

        if (transitionedToEligibleEvent != null) {
            onGeneralAppEligibleTransition?.invoke(transitionedToEligibleEvent)
        }
    }

    private fun recordExitIfMeaningful(nowWall: Long) {
        val snap = _snapshot.value
        val pkg = snap.packageName ?: return
        if (snap.category == InferredAppCategory.ITV_OWN || snap.currentAppDurationMs < 1500L) return
        val sec = snap.currentAppDurationMs / 1000L
        val event = GeneralAppTransitionEvent(
            timestampWallMs = nowWall,
            packageName = pkg,
            appName = snap.appName,
            category = snap.category,
            inferredActivity = snap.inferredActivity,
            inferredScreen = snap.inferredScreen,
            classificationBasis = snap.classificationBasis,
            eligibility = snap.eligibility,
            eligibilityReason = snap.eligibilityReason,
            durationMs = snap.currentAppDurationMs,
            description = "[${snap.appName}] 사용 종료 (${sec}초 체류 · ${snap.category.badge})"
        )
        lastExternalAppSession = event
        addEvent(event)
    }

    private fun addEvent(event: GeneralAppTransitionEvent) {
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
        lastExternalAppSession = null
    }
}
