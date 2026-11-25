package com.example.itv.presentation.jev

import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.domain.coaching.CharacterIntensity
import com.example.itv.domain.coaching.CoachingTone
import com.example.itv.domain.coaching.InterventionReasonResolver
import com.example.itv.domain.coaching.KoreanTextWrapUtils
import com.example.itv.domain.coaching.ReasonBadge
import com.example.itv.domain.coaching.UsageWeightTier
import com.example.itv.domain.jev.JevCandidatePolicy
import com.example.itv.domain.jev.JevContext
import com.example.itv.domain.jev.JevSettings
import com.example.itv.infrastructure.coaching.UserCoachingSettingsStore
import com.example.itv.infrastructure.observation.AmbientSnapshot
import com.example.itv.infrastructure.observation.BedtimePhase
import com.example.itv.infrastructure.observation.LightAndBrightnessTracker
import com.example.itv.infrastructure.observation.SessionAppCategory
import com.example.itv.infrastructure.observation.UserEnvironmentState
import com.example.itv.presentation.coaching.CoachingMonitorService
import com.example.itv.presentation.coaching.CoachingOverlayFactory
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.ItvLine
import com.example.itv.presentation.components.ItvMint
import java.time.LocalTime
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

internal enum class InterventionTestCategory(
    val label: String,
    val speechLabel: String,
    val weightTier: UsageWeightTier,
    val packageName: String,
    val appCategory: String,
    val activityPhase: String,
    val youtubeWatchMode: String?
) {
    YOUTUBE_SHORTS(
        label = "유튜브 쇼츠",
        speechLabel = "YouTube 쇼츠",
        weightTier = UsageWeightTier.FULL_1_TO_1,
        packageName = JevSettings.YOUTUBE,
        appCategory = "SHORTFORM",
        activityPhase = "BROWSING",
        youtubeWatchMode = "SHORTS"
    ),
    INSTAGRAM_REELS(
        label = "인스타 릴스",
        speechLabel = "Instagram 릴스",
        weightTier = UsageWeightTier.FULL_1_TO_1,
        packageName = JevSettings.INSTAGRAM,
        appCategory = "SHORTFORM",
        activityPhase = "BROWSING",
        youtubeWatchMode = null
    ),
    FEED_SNS(
        label = "피드·SNS",
        speechLabel = "피드·SNS",
        weightTier = UsageWeightTier.MID_1_TO_2,
        packageName = JevSettings.INSTAGRAM,
        appCategory = "COMMUNITY_SNS",
        activityPhase = "BROWSING",
        youtubeWatchMode = null
    ),
    GAME(
        label = "모바일 게임",
        speechLabel = "모바일 게임",
        weightTier = UsageWeightTier.MID_1_TO_2,
        packageName = "com.example.game",
        appCategory = "GAME",
        activityPhase = "ACTIVE_GAMEPLAY",
        youtubeWatchMode = null
    ),
    YOUTUBE_LONGFORM(
        label = "유튜브 롱폼",
        speechLabel = "유튜브 영상",
        weightTier = UsageWeightTier.LOW_1_TO_3,
        packageName = JevSettings.YOUTUBE,
        appCategory = "LONGFORM_VIDEO",
        activityPhase = "VIDEO_PLAYING",
        youtubeWatchMode = "LONGFORM"
    ),
    WEB_GENERAL(
        label = "웹·일반 앱",
        speechLabel = "웹·일반 앱",
        weightTier = UsageWeightTier.LOW_1_TO_3,
        packageName = "com.android.chrome",
        appCategory = "BROWSER_SEARCH",
        activityPhase = "ACTIVE",
        youtubeWatchMode = null
    ),
    DOC_MESSENGER(
        label = "문서·메신저",
        speechLabel = "문서·메신저",
        weightTier = UsageWeightTier.LOW_1_TO_3,
        packageName = "com.kakao.talk",
        appCategory = "DOCUMENT",
        activityPhase = "ACTIVE",
        youtubeWatchMode = null
    )
}

internal data class CategoryMinutesInput(
    val youtubeShorts: Int = 0,
    val instaReels: Int = 0,
    val feedSns: Int = 0,
    val game: Int = 0,
    val youtubeLongform: Int = 0,
    val webGeneral: Int = 0,
    val docMessenger: Int = 0
) {
    fun minutesOf(category: InterventionTestCategory): Int = when (category) {
        InterventionTestCategory.YOUTUBE_SHORTS -> youtubeShorts
        InterventionTestCategory.INSTAGRAM_REELS -> instaReels
        InterventionTestCategory.FEED_SNS -> feedSns
        InterventionTestCategory.GAME -> game
        InterventionTestCategory.YOUTUBE_LONGFORM -> youtubeLongform
        InterventionTestCategory.WEB_GENERAL -> webGeneral
        InterventionTestCategory.DOC_MESSENGER -> docMessenger
    }

    val shortformMinutes: Int get() = youtubeShorts + instaReels
    val midWeightMinutes: Int get() = feedSns + game
    val lowWeightMinutes: Int get() = youtubeLongform + webGeneral + docMessenger
    val totalRawMinutes: Int get() = shortformMinutes + midWeightMinutes + lowWeightMinutes

    val rawShortformMs: Long get() = shortformMinutes * 60_000L
    val rawMidWeightMs: Long get() = midWeightMinutes * 60_000L
    val rawLowWeightMs: Long get() = lowWeightMinutes * 60_000L
    val rawGeneralMs: Long get() = rawMidWeightMs + rawLowWeightMs

    /** RestUsageClock과 동일한 3단계 가중치 환산 공식: 1:1 + 1:2 + 1:3 */
    val equivalentMs: Long get() = rawShortformMs + (rawMidWeightMs / 2L) + (rawLowWeightMs / 3L)
    val equivalentMinutes: Double get() = equivalentMs / 60_000.0

    fun activeCategories(): List<InterventionTestCategory> =
        InterventionTestCategory.entries.filter { minutesOf(it) > 0 }

    fun toCategoryDurationsMs(): Map<SessionAppCategory, Long> = buildMap {
        if (youtubeShorts > 0) put(SessionAppCategory.YOUTUBE_SHORTS, youtubeShorts * 60_000L)
        if (instaReels > 0) put(SessionAppCategory.INSTA_REELS, instaReels * 60_000L)
        if (feedSns > 0) put(SessionAppCategory.FEED_SNS, feedSns * 60_000L)
        if (game > 0) put(SessionAppCategory.GAME, game * 60_000L)
        if (youtubeLongform > 0) put(SessionAppCategory.YOUTUBE_LONGFORM, youtubeLongform * 60_000L)
        if (webGeneral > 0) put(SessionAppCategory.WEB_GENERAL, webGeneral * 60_000L)
        if (docMessenger > 0) put(SessionAppCategory.DOC_MESSENGER, docMessenger * 60_000L)
    }

    fun dominantCategory(override: InterventionTestCategory? = null): InterventionTestCategory {
        if (override != null && minutesOf(override) > 0) return override
        return InterventionTestCategory.entries
            .filter { minutesOf(it) > 0 }
            .maxWithOrNull(
                compareBy<InterventionTestCategory> { minutesOf(it).toDouble() / it.weightTier.divisor.toDouble() }
                    .thenBy { minutesOf(it) }
            )
            ?: InterventionTestCategory.YOUTUBE_SHORTS
    }
}

internal data class BuiltInterventionTestContext(
    val jevContext: JevContext,
    val ambient: AmbientSnapshot,
    val shortformAppName: String?,
    val simulatedLocalTime: LocalTime,
    val activeCategory: InterventionTestCategory,
    val summaryTag: String,
    val categoryDurationsMs: Map<SessionAppCategory, Long> = emptyMap()
)

internal object InterventionContextTestBuilder {
    fun build(
        minutes: CategoryMinutesInput,
        foregroundOverride: InterventionTestCategory? = null,
        lux: Float,
        screenBrightness: Int,
        isOccluded: Boolean = false,
        baseBedtimeHour: Int,
        baseBedtimeMinute: Int,
        bedtimeOffsetMinutes: Int,
        personalAvgSessionMinutes: Int? = null,
        effectiveSpm1m: Double = 0.0,
        shortformSwipeCount: Int = 0,
        consecutiveLongformChainCount: Int = 0,
        returns10m: Int = 0,
        nowMs: Long = System.currentTimeMillis()
    ): BuiltInterventionTestContext {
        val activeCategory = minutes.dominantCategory(foregroundOverride)
        val clampedHour = baseBedtimeHour.coerceIn(0, 23)
        val clampedMinute = baseBedtimeMinute.coerceIn(0, 59)
        val clampedOffset = bedtimeOffsetMinutes.coerceIn(-480, 240)
        val clampedLux = lux.coerceIn(0f, 1000f)
        val clampedBrightness = screenBrightness.coerceIn(0, 255)

        val baseTotalMin = clampedHour * 60 + clampedMinute
        val simTotalMin = ((baseTotalMin + clampedOffset) % 1440 + 1440) % 1440
        val simulatedTime = LocalTime.of(simTotalMin / 60, simTotalMin % 60)

        val bedtimePhase = when {
            clampedOffset >= 0 -> BedtimePhase.PAST_BEDTIME
            clampedOffset >= -120 -> BedtimePhase.APPROACHING
            else -> BedtimePhase.ACTIVE_HOURS
        }
        val bedtimePassed = if (clampedOffset >= 0) clampedOffset else null

        val isDarkAmbient = !isOccluded && clampedLux <= 25f
        val isDarkScreen = clampedBrightness <= 100
        val userEnvState = when {
            isOccluded -> UserEnvironmentState.SENSOR_OCCLUDED
            bedtimePhase == BedtimePhase.PAST_BEDTIME || bedtimePhase == BedtimePhase.APPROACHING -> when {
                isDarkAmbient && isDarkScreen -> UserEnvironmentState.PRE_SLEEP_REST
                isDarkAmbient && !isDarkScreen -> UserEnvironmentState.BEDTIME_DELAY_WATCH
                else -> UserEnvironmentState.NIGHT_ACTIVE
            }
            clampedLux >= 300f -> UserEnvironmentState.OUTDOOR_BRIGHT
            isDarkAmbient -> UserEnvironmentState.INDOOR_DARK_ACTIVE
            else -> UserEnvironmentState.INDOOR_ACTIVE
        }

        val isLongformActive = activeCategory == InterventionTestCategory.YOUTUBE_LONGFORM && minutes.youtubeLongform > 0
        val isShortformActive = activeCategory.weightTier == UsageWeightTier.FULL_1_TO_1 && minutes.shortformMinutes > 0
        val shortformAppName = when {
            !isShortformActive -> null
            foregroundOverride == InterventionTestCategory.YOUTUBE_SHORTS -> InterventionTestCategory.YOUTUBE_SHORTS.speechLabel
            foregroundOverride == InterventionTestCategory.INSTAGRAM_REELS -> InterventionTestCategory.INSTAGRAM_REELS.speechLabel
            minutes.youtubeShorts > 0 && minutes.instaReels > 0 -> "숏폼"
            minutes.instaReels > 0 -> InterventionTestCategory.INSTAGRAM_REELS.speechLabel
            else -> InterventionTestCategory.YOUTUBE_SHORTS.speechLabel
        }

        val eqMs = minutes.equivalentMs
        val eqMinutes = minutes.equivalentMinutes
        val avgMinDouble = personalAvgSessionMinutes?.takeIf { it > 0 }?.toDouble()
        val sessionRatio = if (avgMinDouble != null && eqMinutes > 0.0) {
            eqMinutes / avgMinDouble
        } else null

        val spm = effectiveSpm1m.takeIf { it > 0.0 }
        val swipeOverheated = (spm ?: 0.0) >= 12.0
        val fastSwipeStreak = when {
            (spm ?: 0.0) >= 12.0 -> 4
            (spm ?: 0.0) >= 8.0 -> 2
            else -> 0
        }
        val swipeAccel = when {
            (spm ?: 0.0) >= 12.0 -> 3.5
            (spm ?: 0.0) >= 8.0 -> 1.5
            else -> 0.0
        }

        val longformWatchMs = minutes.youtubeLongform * 60_000L
        val pkgName = if (minutes.activeCategories().size > 1 && !isShortformActive && !isLongformActive) {
            "ALL"
        } else {
            activeCategory.packageName
        }

        val jevContext = JevContext(
            sessionId = "ctx-test-${UUID.randomUUID().toString().take(8)}",
            packageName = pkgName,
            sessionMs = eqMs,
            continuousMs = eqMs,
            returns10m = returns10m.coerceIn(0, 20),
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = bedtimePassed,
            observedAt = nowMs,
            rawShortformMs = minutes.rawShortformMs,
            rawGeneralMs = minutes.rawGeneralMs,
            rawMidWeightMs = minutes.rawMidWeightMs,
            quality = "VALID",
            personalAvgSessionMinutes = avgMinDouble,
            sessionVsAvgRatio = sessionRatio,
            effectiveSpm1m = spm,
            effectiveSessionSpm = spm,
            swipeAccel1m = spm?.let { swipeAccel },
            fastSwipeStreak = fastSwipeStreak,
            swipeOverheated = swipeOverheated,
            ambientLux = clampedLux,
            screenBrightness = clampedBrightness,
            isNearOrOccluded = isOccluded,
            bedtimeDistanceMinutes = clampedOffset,
            userEnvironmentState = userEnvState.name,
            longformWatchMs = longformWatchMs,
            currentVideoWatchMs = longformWatchMs,
            completedLongformCount = (consecutiveLongformChainCount - 1).coerceAtLeast(0),
            youtubeWatchMode = if (isLongformActive) "LONGFORM" else activeCategory.youtubeWatchMode,
            appCategory = activeCategory.appCategory,
            activityPhase = activeCategory.activityPhase,
            consecutiveLongformChainCount = consecutiveLongformChainCount.coerceIn(0, 20),
            shortformSwipeCount = shortformSwipeCount.coerceIn(0, 200)
        )

        val ambient = AmbientSnapshot(
            lux = clampedLux,
            proximityDistanceCm = if (isOccluded) 0f else 5f,
            proximityMaxRangeCm = 5f,
            isNear = isOccluded,
            isOccluded = isOccluded,
            screenBrightness = clampedBrightness,
            nightModeActive = isDarkAmbient,
            stateLabel = userEnvState.name,
            explanation = "개입 맥락 테스트 가상 환경",
            estimatedBedtimeHour = clampedHour,
            estimatedBedtimeMinute = clampedMinute,
            bedtimeDistanceMinutes = clampedOffset,
            bedtimePhase = bedtimePhase,
            userEnvironmentState = userEnvState,
            estimatedBedtimeSource = "테스트 설정"
        )

        val offsetText = if (clampedOffset >= 0) "+${clampedOffset}분" else "${clampedOffset}분"
        val summaryTag = "총 ${minutes.totalRawMinutes}분(등가 ${"%.1f".format(Locale.KOREA, eqMinutes)}분) · ${clampedLux.roundToInt()}lx · 취침 $offsetText"

        return BuiltInterventionTestContext(
            jevContext = jevContext,
            ambient = ambient,
            shortformAppName = shortformAppName,
            simulatedLocalTime = simulatedTime,
            activeCategory = activeCategory,
            summaryTag = summaryTag,
            categoryDurationsMs = minutes.toCategoryDurationsMs()
        )
    }
}

@Composable
internal fun InterventionContextBubbleTestPage(
    vm: JevTestViewModel,
    s: JevSettings,
    busy: Boolean
) {
    val context = LocalContext.current
    val tracker = remember { LightAndBrightnessTracker.getInstance(context) }
    val userCoachingSettings = remember { UserCoachingSettingsStore.getInstance(context).loadSettings() }

    DisposableEffect(Unit) {
        tracker.start()
        onDispose { tracker.stop() }
    }

    val liveSnap by tracker.snapshot.collectAsState()
    val configured by vm.store.tokenConfigured.collectAsState()
    val evalResult by vm.contextBubbleResult.collectAsState()

    // 1. 카테고리별 사용 시간 (분)
    var ytShortsMin by rememberSaveable { mutableIntStateOf(0) }
    var igReelsMin by rememberSaveable { mutableIntStateOf(22) }
    var feedSnsMin by rememberSaveable { mutableIntStateOf(0) }
    var gameMin by rememberSaveable { mutableIntStateOf(0) }
    var ytLongformMin by rememberSaveable { mutableIntStateOf(0) }
    var webGeneralMin by rememberSaveable { mutableIntStateOf(0) }
    var docMessengerMin by rememberSaveable { mutableIntStateOf(0) }
    var fgOverrideOrdinal by rememberSaveable { mutableIntStateOf(-1) }

    // 2. 조도 · 화면 밝기 · 취침 추정 시각 기준 ± 오프셋
    var initializedFromLive by rememberSaveable { mutableStateOf(false) }
    var luxValue by rememberSaveable { mutableFloatStateOf(3f) }
    var brightnessValue by rememberSaveable { mutableIntStateOf(80) }
    var isOccluded by rememberSaveable { mutableStateOf(false) }
    var baseBedtimeHour by rememberSaveable { mutableIntStateOf(0) }
    var baseBedtimeMinute by rememberSaveable { mutableIntStateOf(0) }
    var bedtimeOffsetMin by rememberSaveable { mutableIntStateOf(35) }

    LaunchedEffect(liveSnap.estimatedBedtimeHour, liveSnap.estimatedBedtimeMinute) {
        if (!initializedFromLive && liveSnap.estimatedBedtimeHour != null && liveSnap.estimatedBedtimeMinute != null) {
            baseBedtimeHour = liveSnap.estimatedBedtimeHour!!
            baseBedtimeMinute = liveSnap.estimatedBedtimeMinute!!
            initializedFromLive = true
        }
    }

    // 3. 추가 과몰입 · 세션 맥락
    var personalAvgEnabled by rememberSaveable { mutableStateOf(false) }
    var personalAvgMin by rememberSaveable { mutableIntStateOf(15) }
    var effectiveSpm by rememberSaveable { mutableIntStateOf(0) }
    var shortformSwipes by rememberSaveable { mutableIntStateOf(0) }
    var longformChainCount by rememberSaveable { mutableIntStateOf(0) }
    var returns10m by rememberSaveable { mutableIntStateOf(0) }

    // 4. 말풍선 미리보기 · 오버레이 옵션 · 하단 설정 탭 (0=사용시간, 1=조도/취침, 2=과몰입/상세)
    var previewIntensityOrdinal by rememberSaveable { mutableIntStateOf(userCoachingSettings.characterIntensity.ordinal) }
    var previewToneOrdinal by rememberSaveable { mutableIntStateOf(userCoachingSettings.tone.ordinal) }
    var speechSeed by rememberSaveable { mutableIntStateOf(0) }
    var overlayDelaySec by rememberSaveable { mutableIntStateOf(0) }
    var activeControlTab by rememberSaveable { mutableIntStateOf(0) }

    val minutesInput = remember(
        ytShortsMin, igReelsMin, feedSnsMin, gameMin, ytLongformMin, webGeneralMin, docMessengerMin
    ) {
        CategoryMinutesInput(
            youtubeShorts = ytShortsMin,
            instaReels = igReelsMin,
            feedSns = feedSnsMin,
            game = gameMin,
            youtubeLongform = ytLongformMin,
            webGeneral = webGeneralMin,
            docMessenger = docMessengerMin
        )
    }

    val fgOverride = remember(fgOverrideOrdinal, minutesInput) {
        if (fgOverrideOrdinal in InterventionTestCategory.entries.indices) {
            val picked = InterventionTestCategory.entries[fgOverrideOrdinal]
            if (minutesInput.minutesOf(picked) > 0) picked else null
        } else null
    }

    val built = remember(
        minutesInput, fgOverride, luxValue, brightnessValue, isOccluded,
        baseBedtimeHour, baseBedtimeMinute, bedtimeOffsetMin,
        personalAvgEnabled, personalAvgMin, effectiveSpm, shortformSwipes, longformChainCount, returns10m
    ) {
        InterventionContextTestBuilder.build(
            minutes = minutesInput,
            foregroundOverride = fgOverride,
            lux = luxValue,
            screenBrightness = brightnessValue,
            isOccluded = isOccluded,
            baseBedtimeHour = baseBedtimeHour,
            baseBedtimeMinute = baseBedtimeMinute,
            bedtimeOffsetMinutes = bedtimeOffsetMin,
            personalAvgSessionMinutes = if (personalAvgEnabled) personalAvgMin else null,
            effectiveSpm1m = effectiveSpm.toDouble(),
            shortformSwipeCount = shortformSwipes,
            consecutiveLongformChainCount = longformChainCount,
            returns10m = returns10m
        )
    }

    val thresholdAdj = remember(s, built) {
        JevCandidatePolicy.computeThresholdAdjustment(s, built.jevContext)
    }
    val candidateTriggers = remember(s, built) {
        JevCandidatePolicy.reasons(built.jevContext, s)
    }
    val resolvedReasons = remember(built) {
        InterventionReasonResolver.resolveReasons(
            isJev = true,
            jevContext = built.jevContext,
            continuousUsedMs = built.jevContext.continuousMs,
            ambient = built.ambient,
            shortformAppName = built.shortformAppName,
            localTime = built.simulatedLocalTime,
            categoryDurationsMs = built.categoryDurationsMs
        )
    }

    val selectedIntensity = CharacterIntensity.entries[previewIntensityOrdinal.coerceIn(0, CharacterIntensity.entries.lastIndex)]
    val selectedTone = CoachingTone.entries[previewToneOrdinal.coerceIn(0, CoachingTone.entries.lastIndex)]

    val previewBubble = remember(selectedIntensity, selectedTone, resolvedReasons, speechSeed) {
        CoachingOverlayFactory.buildOfferBubble(
            intensity = selectedIntensity,
            reasons = resolvedReasons,
            tone = selectedTone,
            random = Random(speechSeed.toLong() + 17L)
        )
    }

    fun setCategoryMinutes(cat: InterventionTestCategory, value: Int) {
        val clamped = value.coerceIn(0, 360)
        when (cat) {
            InterventionTestCategory.YOUTUBE_SHORTS -> ytShortsMin = clamped
            InterventionTestCategory.INSTAGRAM_REELS -> igReelsMin = clamped
            InterventionTestCategory.FEED_SNS -> feedSnsMin = clamped
            InterventionTestCategory.GAME -> gameMin = clamped
            InterventionTestCategory.YOUTUBE_LONGFORM -> ytLongformMin = clamped
            InterventionTestCategory.WEB_GENERAL -> webGeneralMin = clamped
            InterventionTestCategory.DOC_MESSENGER -> docMessengerMin = clamped
        }
    }

    fun applyPreset(
        shorts: Int = 0,
        reels: Int = 0,
        feed: Int = 0,
        game: Int = 0,
        longform: Int = 0,
        web: Int = 0,
        doc: Int = 0,
        lux: Float,
        brightness: Int,
        offsetMin: Int,
        avgOn: Boolean = false,
        avgMin: Int = 15,
        spm: Int = 0,
        swipes: Int = 0,
        chain: Int = 0,
        returns: Int = 0
    ) {
        ytShortsMin = shorts
        igReelsMin = reels
        feedSnsMin = feed
        gameMin = game
        ytLongformMin = longform
        webGeneralMin = web
        docMessengerMin = doc
        fgOverrideOrdinal = -1
        luxValue = lux
        brightnessValue = brightness
        isOccluded = false
        bedtimeOffsetMin = offsetMin
        personalAvgEnabled = avgOn
        personalAvgMin = avgMin
        effectiveSpm = spm
        shortformSwipes = swipes
        longformChainCount = chain
        returns10m = returns
        vm.clearContextBubbleResult()
    }

    fun launchOverlay(intensity: CharacterIntensity) {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "다른 앱 위에 표시(오버레이) 권한을 먼저 허용해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val delayMs = overlayDelaySec * 1000L
        val label = when (intensity) {
            CharacterIntensity.GENTLE -> "약한 개입"
            CharacterIntensity.NORMAL -> "보통 개입"
            CharacterIntensity.STRONG -> "강한 개입"
        }
        if (delayMs > 0L) {
            Toast.makeText(context, "3초 뒤 설정한 말풍선으로 ${label}이 뜹니다. 다른 앱으로 전환해보세요!", Toast.LENGTH_SHORT).show()
        }
        CoachingMonitorService.triggerTest(
            context = context,
            forceStrong = intensity == CharacterIntensity.STRONG,
            delayMs = delayMs,
            customReasons = resolvedReasons,
            intensityOverride = intensity,
            toneOverride = selectedTone
        )
    }

    val basePct = s.probabilityThreshold * 100.0
    val effPct = thresholdAdj.effectiveThreshold * 100.0
    val totalDeltaPct = thresholdAdj.totalDelta * 100.0
    val bedtimeChipLabel = when {
        bedtimeOffsetMin > 0 -> "취침 +${bedtimeOffsetMin}분"
        bedtimeOffsetMin == 0 -> "취침 정각"
        else -> "취침 ${bedtimeOffsetMin}분"
    }

    // -------------------------------------------------------------------------
    // 0. 빠른 프리셋 1줄
    // -------------------------------------------------------------------------
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AssistChip(
            onClick = { applyPreset(reels = 22, lux = 3f, brightness = 80, offsetMin = 35, avgOn = true, avgMin = 15, swipes = 28) },
            label = { Text("취침+암실+릴스22", fontSize = 11.5.sp) }
        )
        AssistChip(
            onClick = { applyPreset(longform = 36, lux = 12f, brightness = 130, offsetMin = -30, chain = 3) },
            label = { Text("롱폼36+전환3편", fontSize = 11.5.sp) }
        )
        AssistChip(
            onClick = { applyPreset(shorts = 10, feed = 20, web = 15, lux = 18f, brightness = 140, offsetMin = 15, spm = 13) },
            label = { Text("복합(쇼츠+피드+웹)", fontSize = 11.5.sp) }
        )
        AssistChip(
            onClick = { applyPreset(web = 12, doc = 10, lux = 220f, brightness = 200, offsetMin = -360) },
            label = { Text("주간·가벼움", fontSize = 11.5.sp) }
        )
        AssistChip(
            onClick = { applyPreset(lux = 35f, brightness = 120, offsetMin = -180) },
            label = { Text("초기화", fontSize = 11.5.sp) }
        )
    }

    // -------------------------------------------------------------------------
    // 1. 상단 통합 대시보드: 실시간 말풍선 미리보기 + 기준 확률 + 바로 실행
    // -------------------------------------------------------------------------
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, ItvGreen.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 현재 설정 요약 + 최종 기준 확률 한눈에 보기
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "총 ${minutesInput.totalRawMinutes}분 (환산 ${"%.1f".format(Locale.KOREA, minutesInput.equivalentMinutes)}분)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = ItvGreen
                    )
                    Text(
                        "${if (isOccluded) "센서가림" else "${luxValue.roundToInt()}lx"} · $bedtimeChipLabel · ${built.activeCategory.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (totalDeltaPct < 0.0) MaterialTheme.colorScheme.errorContainer else ItvMint
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            "기준 ${"%.0f".format(Locale.KOREA, effPct)}%",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (totalDeltaPct < 0.0) MaterialTheme.colorScheme.error else ItvGreen
                        )
                        Text(
                            "기본 ${"%.0f".format(Locale.KOREA, basePct)}% (${formatSignedPct(totalDeltaPct)}p)",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = if (totalDeltaPct < 0.0) MaterialTheme.colorScheme.onErrorContainer else ItvGreen
                        )
                    }
                }
            }

            // 말풍선 미리보기 카드 (실제 오버레이와 동일한 1번 배지 칩 + 2번 초단문 헤드라인·보조안내 구조)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFFFFF9EB),
                border = BorderStroke(1.8.dp, Color(0xFF71492B))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "🐾 말풍선 미리보기",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF71492B)
                        )
                        TextButton(
                            onClick = { speechSeed++ },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text("다른 문구", fontSize = 11.sp)
                        }
                    }

                    // 1번 요소: 핵심 이유 태그 칩 배지 (최대 2개)
                    if (previewBubble.badges.isNotEmpty()) {
                        val totalChars = previewBubble.badges.sumOf { it.text.length }
                        val stackVertically = previewBubble.badges.size > 1 && totalChars >= 21
                        if (stackVertically) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                previewBubble.badges.forEach { badge ->
                                    PreviewReasonBadgeChip(badge)
                                }
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                previewBubble.badges.forEach { badge ->
                                    PreviewReasonBadgeChip(badge)
                                }
                            }
                        }
                    }

                    // 2번 요소: 15자 이내 초단문 헤드라인 + 어절 줄바꿈 + NBSP 보호
                    Text(
                        text = KoreanTextWrapUtils.preventAwkwardWrap(previewBubble.headline),
                        style = MaterialTheme.typography.titleSmall.copy(
                            lineBreak = LineBreak.Heading
                        ),
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF182216),
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    // 2번 요소: 작고 연한 보조 안내 1줄
                    previewBubble.subline?.let { sub ->
                        Text(
                            text = KoreanTextWrapUtils.preventAwkwardWrap(sub),
                            style = MaterialTheme.typography.labelSmall.copy(
                                lineBreak = LineBreak.Heading
                            ),
                            color = Color(0xFF5C6C5F),
                            textAlign = TextAlign.Center,
                            fontSize = 11.5.sp
                        )
                    }

                    // 강도 / 말투 초간편 1줄 칩
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            CharacterIntensity.GENTLE to "약",
                            CharacterIntensity.NORMAL to "보통",
                            CharacterIntensity.STRONG to "강"
                        ).forEach { (intensity, lbl) ->
                            FilterChip(
                                selected = selectedIntensity == intensity,
                                onClick = { previewIntensityOrdinal = intensity.ordinal },
                                label = { Text(lbl, fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        listOf(
                            CoachingTone.SOFT to "부드럽게",
                            CoachingTone.PLAYFUL to "장난",
                            CoachingTone.FIRM to "단호"
                        ).forEach { (tone, lbl) ->
                            FilterChip(
                                selected = selectedTone == tone,
                                onClick = { previewToneOrdinal = tone.ordinal },
                                label = { Text(lbl, fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }
            }

            // 바로 실행 바: 오버레이 띄우기 (약/보통/강) + 즉시/3초 토글
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = overlayDelaySec == 3,
                    onClick = { overlayDelaySec = if (overlayDelaySec == 0) 3 else 0 },
                    label = { Text(if (overlayDelaySec == 3) "3초뒤" else "즉시", fontSize = 11.sp) },
                    modifier = Modifier.height(34.dp)
                )
                OutlinedButton(
                    onClick = { launchOverlay(CharacterIntensity.GENTLE) },
                    modifier = Modifier.weight(1f).height(34.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("약 띄우기", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Button(
                    onClick = { launchOverlay(CharacterIntensity.NORMAL) },
                    modifier = Modifier.weight(1f).height(34.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("보통 띄우기", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Button(
                    onClick = { launchOverlay(CharacterIntensity.STRONG) },
                    modifier = Modifier.weight(1f).height(34.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("강 띄우기", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }

            // Jev 확률 평가 버튼 + 결과 배지
            val canEvaluateJev = !busy && configured && s.collectionConsent && minutesInput.totalRawMinutes > 0
            OutlinedButton(
                onClick = { vm.evaluateContextBubble(built.jevContext, built.summaryTag) },
                enabled = canEvaluateJev,
                modifier = Modifier.fillMaxWidth().height(38.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Text(
                    when {
                        minutesInput.totalRawMinutes == 0 -> "사용 시간을 1분 이상 설정해 주세요"
                        !configured || !s.collectionConsent -> "연결 탭에서 토큰·동의 저장 후 Jev 평가 가능"
                        busy -> "Jev 평가 중..."
                        else -> "현재 설정으로 Jev 확률 평가하기"
                    },
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            evalResult?.let { res ->
                val passed = res.passedEffective
                val badgeColor = if (passed) MaterialTheme.colorScheme.error else ItvGreen
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = badgeColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, badgeColor)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (passed) "개입 발동 충족!" else "기준 미달 (보류)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = badgeColor
                            )
                            Text(
                                "${res.latencyMs ?: "-"}ms · ${res.model ?: "-"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "Jev ${"%.1f".format(Locale.KOREA, res.probability * 100.0)}% / 기준 ${"%.1f".format(Locale.KOREA, res.effectiveThreshold * 100.0)}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = badgeColor
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 2. 하단 3단 세그먼트 탭 (스크롤 없이 위 미리보기를 보며 바로 조절)
    // -------------------------------------------------------------------------
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val tabs = listOf(
            "⏱ 시간 (${minutesInput.totalRawMinutes}분)",
            "🌙 조도·취침",
            "⚡ 과몰입·상세"
        )
        tabs.forEachIndexed { idx, title ->
            FilterChip(
                selected = activeControlTab == idx,
                onClick = { activeControlTab = idx },
                label = {
                    Text(
                        title,
                        fontSize = 11.5.sp,
                        fontWeight = if (activeControlTab == idx) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }

    when (activeControlTab) {
        // 탭 0: 앱 카테고리별 사용 시간
        0 -> {
            CompactCheckGroup("앱 카테고리별 사용 시간 (분)") {
                InterventionTestCategory.entries.forEach { cat ->
                    CategoryMinuteStepperRow(
                        category = cat,
                        minutes = minutesInput.minutesOf(cat),
                        onChange = { setCategoryMinutes(cat, it) }
                    )
                }

                HorizontalDivider(color = ItvLine)
                Text(
                    "숏폼(1:1) ${minutesInput.shortformMinutes}분 · 피드·게임(1:2) ${minutesInput.midWeightMinutes}분 · 롱폼·일반(1:3) ${minutesInput.lowWeightMinutes}분",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val activeCats = minutesInput.activeCategories()
                if (activeCats.size > 1) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("대표 화면:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ItvGreen)
                        FilterChip(
                            selected = fgOverride == null,
                            onClick = { fgOverrideOrdinal = -1 },
                            label = { Text("자동(${built.activeCategory.label})", fontSize = 11.sp) }
                        )
                        activeCats.forEach { cat ->
                            FilterChip(
                                selected = fgOverride == cat,
                                onClick = { fgOverrideOrdinal = cat.ordinal },
                                label = { Text("${cat.label}(${minutesInput.minutesOf(cat)}분)", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        // 탭 1: 조도 & 취침 추정 시각 ±
        1 -> {
            val liveBedtimeLabel = if (liveSnap.estimatedBedtimeHour != null && liveSnap.estimatedBedtimeMinute != null) {
                String.format(Locale.KOREA, "%02d:%02d", liveSnap.estimatedBedtimeHour, liveSnap.estimatedBedtimeMinute)
            } else "00:00"
            val liveLuxLabel = liveSnap.lux?.let { "${it.roundToInt()}lx" } ?: "-"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "기기 실측: $liveLuxLabel · 추정 취침 $liveBedtimeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        liveSnap.lux?.let { luxValue = it.coerceIn(0f, 300f) }
                        liveSnap.screenBrightness?.let { brightnessValue = it.coerceIn(0, 255) }
                        if (liveSnap.estimatedBedtimeHour != null && liveSnap.estimatedBedtimeMinute != null) {
                            baseBedtimeHour = liveSnap.estimatedBedtimeHour!!
                            baseBedtimeMinute = liveSnap.estimatedBedtimeMinute!!
                        }
                        liveSnap.bedtimeDistanceMinutes?.let { bedtimeOffsetMin = it.coerceIn(-480, 240) }
                        Toast.makeText(context, "기기 실측 조도·추정 취침을 불러왔습니다.", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("실측값 적용", fontSize = 11.5.sp)
                }
            }

            val luxStateTag = when {
                isOccluded -> "센서 가림"
                luxValue <= 5f -> "완전 암실"
                luxValue <= 25f -> "어두움"
                luxValue < 40f -> "실내등"
                luxValue < 200f -> "밝은 실내"
                else -> "주간 밝음"
            }
            CompactCheckGroup("주변 조도 (Lux) & 화면 밝기") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${luxValue.roundToInt()} lux · $luxStateTag",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (luxValue <= 25f && !isOccluded) MaterialTheme.colorScheme.error else ItvGreen
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        MiniStepButton("-10") { luxValue = (luxValue - 10f).coerceIn(0f, 500f); isOccluded = false }
                        MiniStepButton("-1") { luxValue = (luxValue - 1f).coerceIn(0f, 500f); isOccluded = false }
                        MiniStepButton("+1") { luxValue = (luxValue + 1f).coerceIn(0f, 500f); isOccluded = false }
                        MiniStepButton("+10") { luxValue = (luxValue + 10f).coerceIn(0f, 500f); isOccluded = false }
                    }
                }
                Slider(
                    value = luxValue.coerceIn(0f, 300f),
                    onValueChange = { luxValue = it.roundToInt().toFloat(); isOccluded = false },
                    valueRange = 0f..300f
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    listOf(2f to "암실 2lx", 15f to "어둠 15lx", 35f to "실내 35lx", 150f to "밝음 150lx").forEach { (v, lbl) ->
                        FilterChip(
                            selected = abs(luxValue - v) < 1f && !isOccluded,
                            onClick = { luxValue = v; isOccluded = false },
                            label = { Text(lbl, fontSize = 11.sp) }
                        )
                    }
                    FilterChip(
                        selected = isOccluded,
                        onClick = { isOccluded = !isOccluded },
                        label = { Text("센서 가림", fontSize = 11.sp) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("화면 밝기 ($brightnessValue)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(60 to "어두움", 130 to "보통", 210 to "밝음").forEach { (v, lbl) ->
                            FilterChip(
                                selected = abs(brightnessValue - v) <= 30,
                                onClick = { brightnessValue = v },
                                label = { Text(lbl, fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }
            }

            val baseBedtimeStr = String.format(Locale.KOREA, "%02d:%02d", baseBedtimeHour, baseBedtimeMinute)
            val simTimeStr = String.format(Locale.KOREA, "%02d:%02d", built.simulatedLocalTime.hour, built.simulatedLocalTime.minute)
            CompactCheckGroup("취침 추정 시각 기준 ± 조절") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "$bedtimeChipLabel (가상 $simTimeStr)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (bedtimeOffsetMin >= 0) MaterialTheme.colorScheme.error else ItvGreen
                        )
                        Text("기준 취침 $baseBedtimeStr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MiniStepButton("기준-30") {
                            val total = ((baseBedtimeHour * 60 + baseBedtimeMinute - 30) % 1440 + 1440) % 1440
                            baseBedtimeHour = total / 60; baseBedtimeMinute = total % 60
                        }
                        MiniStepButton("기준+30") {
                            val total = (baseBedtimeHour * 60 + baseBedtimeMinute + 30) % 1440
                            baseBedtimeHour = total / 60; baseBedtimeMinute = total % 60
                        }
                    }
                }
                Slider(
                    value = bedtimeOffsetMin.toFloat().coerceIn(-480f, 240f),
                    onValueChange = { bedtimeOffsetMin = (it / 5f).roundToInt() * 5 },
                    valueRange = -480f..240f
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(-60 to "-60", -15 to "-15", 0 to "0분", 15 to "+15", 60 to "+60").forEach { (delta, text) ->
                        OutlinedButton(
                            onClick = { bedtimeOffsetMin = if (delta == 0) 0 else (bedtimeOffsetMin + delta).coerceIn(-480, 240) },
                            modifier = Modifier.weight(1f).height(30.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(text, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        // 탭 2: 추가 과몰입 신호 + 기준 확률 보정 내역 + 추출 사유 전체
        else -> {
            CompactCheckGroup("추가 과몰입 · 세션 신호") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = personalAvgEnabled, onCheckedChange = { personalAvgEnabled = it })
                        Text(
                            if (personalAvgEnabled) {
                                val ratioText = built.jevContext.sessionVsAvgRatio?.let { String.format(Locale.KOREA, " (%.1f배)", it) } ?: ""
                                "평소 평균 ${personalAvgMin}분$ratioText"
                            } else "평소 평균 세션 비교 끔",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (personalAvgEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            MiniStepButton("-5") { personalAvgMin = (personalAvgMin - 5).coerceIn(5, 120) }
                            MiniStepButton("+5") { personalAvgMin = (personalAvgMin + 5).coerceIn(5, 120) }
                        }
                    }
                }
                SmallIntStepperRow(
                    label = "스와이프 속도 (SPM)",
                    valueText = if (effectiveSpm == 0) "미적용" else "${effectiveSpm}회/분${if (effectiveSpm >= 12) " · 과열" else ""}",
                    highlight = effectiveSpm >= 12,
                    step = 3,
                    onMinus = { effectiveSpm = (effectiveSpm - 3).coerceAtLeast(0) },
                    onPlus = { effectiveSpm = (effectiveSpm + 3).coerceAtMost(24) }
                )
                SmallIntStepperRow(
                    label = "숏폼 누적 스와이프",
                    valueText = "${shortformSwipes}개",
                    highlight = shortformSwipes > 10,
                    step = 5,
                    onMinus = { shortformSwipes = (shortformSwipes - 5).coerceAtLeast(0) },
                    onPlus = { shortformSwipes = (shortformSwipes + 5).coerceAtMost(80) }
                )
                SmallIntStepperRow(
                    label = "롱폼 연속 전환",
                    valueText = "${longformChainCount}편",
                    highlight = longformChainCount >= 2,
                    step = 1,
                    onMinus = { longformChainCount = (longformChainCount - 1).coerceAtLeast(0) },
                    onPlus = { longformChainCount = (longformChainCount + 1).coerceAtMost(10) }
                )
                SmallIntStepperRow(
                    label = "10분 내 재접속",
                    valueText = "${returns10m}회",
                    highlight = returns10m >= 3,
                    step = 1,
                    onMinus = { returns10m = (returns10m - 1).coerceAtLeast(0) },
                    onPlus = { returns10m = (returns10m + 1).coerceAtMost(10) }
                )
            }

            CompactCheckGroup("기준 확률 보정 내역 (기본 ${"%.0f".format(Locale.KOREA, basePct)}% → ${"%.1f".format(Locale.KOREA, effPct)}%)") {
                CompactCheckRow("취침 · 조도", "${formatSignedPct(thresholdAdj.bedtimeDelta * 100.0)}p", highlight = thresholdAdj.bedtimeDelta < 0.0)
                CompactCheckRow("연속 사용 피로", "${formatSignedPct(thresholdAdj.fatigueDelta * 100.0)}p", highlight = thresholdAdj.fatigueDelta < 0.0)
                CompactCheckRow("개인 평균 초과", "${formatSignedPct(thresholdAdj.sessionDelta * 100.0)}p", highlight = thresholdAdj.sessionDelta < 0.0)
                CompactCheckRow("스와이프 과열", "${formatSignedPct(thresholdAdj.swipeDelta * 100.0)}p", highlight = thresholdAdj.swipeDelta < 0.0)
                CompactCheckRow("영상 전환·누적", "${formatSignedPct(thresholdAdj.contentSwitchDelta * 100.0)}p", highlight = thresholdAdj.contentSwitchDelta < 0.0)
                CompactCheckRow(
                    "후보 트리거",
                    if (candidateTriggers.isEmpty()) "없음" else candidateTriggers.joinToString(", "),
                    highlight = candidateTriggers.isNotEmpty()
                )
            }

            CompactCheckGroup("추출된 개입 사유 우선순위 (자세 제외)") {
                resolvedReasons.forEachIndexed { idx, reason ->
                    val shownInBubble = when (selectedIntensity) {
                        CharacterIntensity.GENTLE -> idx == 0
                        CharacterIntensity.NORMAL, CharacterIntensity.STRONG -> idx < 2
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "${idx + 1}. $reason",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (shownInBubble) FontWeight.Bold else FontWeight.Normal,
                            color = if (shownInBubble) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (shownInBubble) {
                            Surface(shape = RoundedCornerShape(6.dp), color = ItvMint) {
                                Text(
                                    "표시",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ItvGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryMinuteStepperRow(
    category: InterventionTestCategory,
    minutes: Int,
    onChange: (Int) -> Unit
) {
    val active = minutes > 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                category.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) ItvGreen else MaterialTheme.colorScheme.onSurface
            )
            Text(
                category.weightTier.label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = if (active) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniStepButton("-5") { onChange(minutes - 5) }
            MiniStepButton("-1") { onChange(minutes - 1) }
            Text(
                text = "${minutes}분",
                modifier = Modifier.width(42.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Normal,
                color = if (active) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
            MiniStepButton("+1") { onChange(minutes + 1) }
            MiniStepButton("+5") { onChange(minutes + 5) }
        }
    }
}

@Composable
private fun SmallIntStepperRow(
    label: String,
    valueText: String,
    highlight: Boolean,
    step: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
                color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MiniStepButton("-$step", onMinus)
            MiniStepButton("+$step", onPlus)
        }
    }
}

@Composable
private fun MiniStepButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(28.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun PreviewReasonBadgeChip(badge: ReasonBadge) {
    val bgColor = if (badge.isAlert) Color(0xFFFFF0EE) else Color(0xFFE8F6EC)
    val borderColor = if (badge.isAlert) Color(0xFFF5B7B1) else Color(0xFFA5D6A7)
    val baseColor = if (badge.isAlert) Color(0xFFB42318) else Color(0xFF1B5E20)
    val accentColor = if (badge.isAlert) Color(0xFF912018) else Color(0xFF0F5132)
    val wrappedBadgeText = remember(badge.text) { KoreanTextWrapUtils.preventAwkwardWrap(badge.text) }
    val annotated = remember(wrappedBadgeText, badge.isAlert) {
        val pattern = Regex("""[+\-]?\d+(?:\.\d+)?[분초편회개배%]?""")
        buildAnnotatedString {
            var cursor = 0
            pattern.findAll(wrappedBadgeText).forEach { m ->
                if (m.range.first > cursor) {
                    append(wrappedBadgeText.substring(cursor, m.range.first))
                }
                withStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.ExtraBold)) {
                    append(m.value)
                }
                cursor = m.range.last + 1
            }
            if (cursor < wrappedBadgeText.length) {
                append(wrappedBadgeText.substring(cursor))
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(99.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = annotated,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = baseColor,
            maxLines = 1
        )
    }
}

private fun formatSignedPct(value: Double): String = when {
    value > 0.05 -> "+${"%.1f".format(Locale.KOREA, value)}%"
    value < -0.05 -> "${"%.1f".format(Locale.KOREA, value)}%"
    else -> "0.0%"
}
