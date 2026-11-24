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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.domain.coaching.CharacterIntensity
import com.example.itv.domain.coaching.CoachingTone
import com.example.itv.domain.coaching.InterventionReasonResolver
import com.example.itv.domain.coaching.UsageWeightTier
import com.example.itv.domain.jev.JevCandidatePolicy
import com.example.itv.domain.jev.JevContext
import com.example.itv.domain.jev.JevSettings
import com.example.itv.infrastructure.coaching.UserCoachingSettingsStore
import com.example.itv.infrastructure.observation.AmbientSnapshot
import com.example.itv.infrastructure.observation.BedtimePhase
import com.example.itv.infrastructure.observation.LightAndBrightnessTracker
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

    fun dominantCategory(override: InterventionTestCategory? = null): InterventionTestCategory {
        if (override != null && minutesOf(override) > 0) return override
        return InterventionTestCategory.entries
            .maxByOrNull { minutesOf(it) }
            ?.takeIf { minutesOf(it) > 0 }
            ?: InterventionTestCategory.YOUTUBE_SHORTS
    }
}

internal data class BuiltInterventionTestContext(
    val jevContext: JevContext,
    val ambient: AmbientSnapshot,
    val shortformAppName: String?,
    val simulatedLocalTime: LocalTime,
    val activeCategory: InterventionTestCategory,
    val summaryTag: String
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
            summaryTag = summaryTag
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

    // 3. 추가 과몰입 · 세션 맥락 (임계치 마이너스 보정 요인)
    var personalAvgEnabled by rememberSaveable { mutableStateOf(false) }
    var personalAvgMin by rememberSaveable { mutableIntStateOf(15) }
    var effectiveSpm by rememberSaveable { mutableIntStateOf(0) }
    var shortformSwipes by rememberSaveable { mutableIntStateOf(0) }
    var longformChainCount by rememberSaveable { mutableIntStateOf(0) }
    var returns10m by rememberSaveable { mutableIntStateOf(0) }

    // 4. 말풍선 미리보기 · 실제 오버레이 옵션
    var previewIntensityOrdinal by rememberSaveable { mutableIntStateOf(userCoachingSettings.characterIntensity.ordinal) }
    var previewToneOrdinal by rememberSaveable { mutableIntStateOf(userCoachingSettings.tone.ordinal) }
    var speechSeed by rememberSaveable { mutableIntStateOf(0) }
    var overlayDelaySec by rememberSaveable { mutableIntStateOf(0) }

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
            localTime = built.simulatedLocalTime
        )
    }

    val selectedIntensity = CharacterIntensity.entries[previewIntensityOrdinal.coerceIn(0, CharacterIntensity.entries.lastIndex)]
    val selectedTone = CoachingTone.entries[previewToneOrdinal.coerceIn(0, CoachingTone.entries.lastIndex)]

    val previewBubbleText = remember(selectedIntensity, selectedTone, resolvedReasons, speechSeed) {
        CoachingOverlayFactory.formatOfferSpeech(
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

    // 1. 앱 카테고리별 사용 시간 설정 & 총합 계산
    TestSection("1. 앱 카테고리별 사용 시간 설정 (총합 계산)") {
        Text(
            "각 카테고리의 사용 시간(분)을 자유롭게 조합하면 3단계 가중치(숏폼 1:1 · 피드/게임 1:2 · 롱폼/웹/문서 1:3)로 총합하여 개입 확률 기준과 말풍선 사유를 즉시 테스트합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AssistChip(
                onClick = {
                    applyPreset(reels = 22, lux = 3f, brightness = 80, offsetMin = 35, avgOn = true, avgMin = 15, swipes = 28)
                },
                label = { Text("취침+암실+릴스 22분") }
            )
            AssistChip(
                onClick = {
                    applyPreset(longform = 36, lux = 12f, brightness = 130, offsetMin = -30, chain = 3)
                },
                label = { Text("롱폼 36분+전환 3편") }
            )
            AssistChip(
                onClick = {
                    applyPreset(shorts = 10, feed = 20, web = 15, lux = 18f, brightness = 140, offsetMin = 15, spm = 13)
                },
                label = { Text("복합(쇼츠10+피드20+웹15)") }
            )
            AssistChip(
                onClick = {
                    applyPreset(web = 12, doc = 10, lux = 220f, brightness = 200, offsetMin = -360)
                },
                label = { Text("주간 밝음+가벼운 사용") }
            )
            AssistChip(
                onClick = {
                    applyPreset(lux = 35f, brightness = 120, offsetMin = -180)
                },
                label = { Text("전체 0분 초기화") }
            )
        }

        CompactCheckGroup("카테고리별 사용 시간 (분)") {
            InterventionTestCategory.entries.forEach { cat ->
                val cur = minutesInput.minutesOf(cat)
                CategoryMinuteStepperRow(
                    category = cat,
                    minutes = cur,
                    onChange = { setCategoryMinutes(cat, it) }
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.5.dp, ItvGreen.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("총 실사용 시간", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${minutesInput.totalRawMinutes}분",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = ItvGreen
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("3단계 환산 등가 시간", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${"%.1f".format(Locale.KOREA, minutesInput.equivalentMinutes)}분",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    "내역: 숏폼(1:1) ${minutesInput.shortformMinutes}분 + 피드·게임(1:2) ${minutesInput.midWeightMinutes}분 + 롱폼·일반(1:3) ${minutesInput.lowWeightMinutes}분",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val activeCats = minutesInput.activeCategories()
                if (activeCats.size > 1) {
                    Text(
                        "현재 보고 있는 대표 화면 (복합 사용 시 말풍선 기준):",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ItvGreen
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = fgOverride == null,
                            onClick = { fgOverrideOrdinal = -1 },
                            label = { Text("자동(${built.activeCategory.label})") }
                        )
                        activeCats.forEach { cat ->
                            FilterChip(
                                selected = fgOverride == cat,
                                onClick = { fgOverrideOrdinal = cat.ordinal },
                                label = { Text("${cat.label}(${minutesInput.minutesOf(cat)}분)") }
                            )
                        }
                    }
                }
            }
        }
    }

    // 2. 조도(Lux) · 취침 추정 시각 (±) 설정
    TestSection("2. 조도(Lux) · 취침 추정 시각 (±) 설정") {
        val liveBedtimeLabel = if (liveSnap.estimatedBedtimeHour != null && liveSnap.estimatedBedtimeMinute != null) {
            String.format(Locale.KOREA, "%02d:%02d (%s)", liveSnap.estimatedBedtimeHour, liveSnap.estimatedBedtimeMinute, liveSnap.estimatedBedtimeSource)
        } else {
            "미측정 (기본 00:00 사용)"
        }
        val liveLuxLabel = liveSnap.lux?.let { "${"%.1f".format(Locale.KOREA, it)} lx" } ?: "측정 대기"

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "실측 조도: $liveLuxLabel · 앱 추정 취침: $liveBedtimeLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
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
                    Toast.makeText(context, "현재 기기 센서·추정 취침값을 불러왔습니다.", Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("실측값 불러오기", style = MaterialTheme.typography.labelMedium)
            }
        }

        val luxStateTag = when {
            isOccluded -> "센서 가림 (보정 제외)"
            luxValue <= 5f -> "완전 암실 (최대 보정)"
            luxValue <= 25f -> "어두운 환경 (암실 보정)"
            luxValue < 40f -> "약한 실내등 (중립)"
            luxValue < 200f -> "밝은 실내 (+보정)"
            else -> "매우 밝음 / 주간 (+보정)"
        }
        CompactCheckGroup("주변 조도 (Lux) ± 조절") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${luxValue.roundToInt()} lux",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (luxValue <= 25f && !isOccluded) MaterialTheme.colorScheme.error else ItvGreen
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (luxValue <= 25f && !isOccluded) MaterialTheme.colorScheme.errorContainer else ItvMint
                ) {
                    Text(
                        luxStateTag,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (luxValue <= 25f && !isOccluded) MaterialTheme.colorScheme.onErrorContainer else ItvGreen
                    )
                }
            }
            Slider(
                value = luxValue.coerceIn(0f, 300f),
                onValueChange = { luxValue = it.roundToInt().toFloat() },
                valueRange = 0f..300f
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(-20 to "-20", -5 to "-5", -1 to "-1", 1 to "+1", 5 to "+5", 20 to "+20").forEach { (delta, text) ->
                    OutlinedButton(
                        onClick = { luxValue = (luxValue + delta).coerceIn(0f, 500f) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                    ) {
                        Text(text, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(2f to "암실 2lx", 15f to "어두움 15lx", 35f to "실내 35lx", 100f to "밝은실내 100lx", 250f to "주간 250lx").forEach { (v, lbl) ->
                    FilterChip(
                        selected = abs(luxValue - v) < 1f && !isOccluded,
                        onClick = { luxValue = v; isOccluded = false },
                        label = { Text(lbl) }
                    )
                }
                FilterChip(
                    selected = isOccluded,
                    onClick = { isOccluded = !isOccluded },
                    label = { Text("센서 가림") }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "화면 밝기: $brightnessValue / 255 (${if (brightnessValue > 100) "밝음" else "어두움"})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { brightnessValue = (brightnessValue - 30).coerceIn(0, 255) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("-30", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = { brightnessValue = (brightnessValue + 30).coerceIn(0, 255) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("+30", fontSize = 11.sp) }
                }
            }
        }

        val baseBedtimeStr = String.format(Locale.KOREA, "%02d:%02d", baseBedtimeHour, baseBedtimeMinute)
        val simTimeStr = String.format(Locale.KOREA, "%02d:%02d", built.simulatedLocalTime.hour, built.simulatedLocalTime.minute)
        val offsetDesc = when {
            bedtimeOffsetMin > 0 -> "취침 시각 +${bedtimeOffsetMin}분 경과"
            bedtimeOffsetMin == 0 -> "정확히 취침 추정 시각 (0분)"
            bedtimeOffsetMin >= -120 -> "취침 ${abs(bedtimeOffsetMin)}분 전 (임박)"
            else -> "취침 ${abs(bedtimeOffsetMin)}분 전 (일상 활동)"
        }
        CompactCheckGroup("취침 추정 시각 기준 ± 조절") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "추정 취침: $baseBedtimeStr",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = {
                            val total = ((baseBedtimeHour * 60 + baseBedtimeMinute - 30) % 1440 + 1440) % 1440
                            baseBedtimeHour = total / 60
                            baseBedtimeMinute = total % 60
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("-30분", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = {
                            val total = (baseBedtimeHour * 60 + baseBedtimeMinute + 30) % 1440
                            baseBedtimeHour = total / 60
                            baseBedtimeMinute = total % 60
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("+30분", fontSize = 11.sp) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    offsetDesc,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (bedtimeOffsetMin >= 0) MaterialTheme.colorScheme.error else ItvGreen
                )
                Surface(shape = RoundedCornerShape(8.dp), color = ItvMint) {
                    Text(
                        "가상 시각 $simTimeStr",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ItvGreen
                    )
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
                listOf(-60 to "-60", -15 to "-15", -5 to "-5", 0 to "0", 5 to "+5", 15 to "+15", 60 to "+60").forEach { (delta, text) ->
                    OutlinedButton(
                        onClick = {
                            bedtimeOffsetMin = if (delta == 0) 0 else (bedtimeOffsetMin + delta).coerceIn(-480, 240)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 1.dp, vertical = 4.dp)
                    ) {
                        Text(text, fontSize = 10.5.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    // 3. 추가 과몰입 · 세션 초과 맥락 (선택 조절)
    TestSection("3. 추가 과몰입 · 세션 맥락 (선택 조절)") {
        Text(
            "기본 판단 확률을 마이너스 보정하는 개인 평균 세션 초과, 스와이프 과열, 누적 스와이프, 롱폼 연속 전환, 재접속도 함께 조합할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CompactCheckGroup("과몰입 · 세션 세부 신호") {
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
                            "평소 평균 세션 ${personalAvgMin}분$ratioText"
                        } else {
                            "평소 평균 세션 비교 미적용"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
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
                label = "분당 스와이프 속도 (SPM)",
                valueText = if (effectiveSpm == 0) "미적용" else "${effectiveSpm}회/분${if (effectiveSpm >= 12) " · 과열" else ""}",
                highlight = effectiveSpm >= 12,
                step = 3,
                onMinus = { effectiveSpm = (effectiveSpm - 3).coerceAtLeast(0) },
                onPlus = { effectiveSpm = (effectiveSpm + 3).coerceAtMost(24) }
            )
            SmallIntStepperRow(
                label = "숏폼 누적 스와이프 수",
                valueText = if (shortformSwipes == 0) "0회" else "${shortformSwipes}개",
                highlight = shortformSwipes > 10,
                step = 5,
                onMinus = { shortformSwipes = (shortformSwipes - 5).coerceAtLeast(0) },
                onPlus = { shortformSwipes = (shortformSwipes + 5).coerceAtMost(80) }
            )
            SmallIntStepperRow(
                label = "롱폼 연속 영상 전환",
                valueText = if (longformChainCount == 0) "0편" else "${longformChainCount}편",
                highlight = longformChainCount >= 2,
                step = 1,
                onMinus = { longformChainCount = (longformChainCount - 1).coerceAtLeast(0) },
                onPlus = { longformChainCount = (longformChainCount + 1).coerceAtMost(10) }
            )
            SmallIntStepperRow(
                label = "최근 10분 재접속 횟수",
                valueText = "${returns10m}회",
                highlight = returns10m >= 3,
                step = 1,
                onMinus = { returns10m = (returns10m - 1).coerceAtLeast(0) },
                onPlus = { returns10m = (returns10m + 1).coerceAtMost(10) }
            )
        }
    }

    // 4. 개입 기준 확률(마이너스 보정) 분석 & Jev 평가
    TestSection("4. 개입 기준 확률 분석 & Jev 평가") {
        val basePct = s.probabilityThreshold * 100.0
        val effPct = thresholdAdj.effectiveThreshold * 100.0
        val totalDeltaPct = thresholdAdj.totalDelta * 100.0

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.5.dp, if (totalDeltaPct < 0.0) MaterialTheme.colorScheme.error.copy(alpha = 0.6f) else ItvGreen)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("최종 개입 기준 확률", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${"%.1f".format(Locale.KOREA, effPct)}%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (totalDeltaPct < 0.0) MaterialTheme.colorScheme.error else ItvGreen
                    )
                }
                Text(
                    "기본 ${"%.0f".format(Locale.KOREA, basePct)}% ${formatSignedPct(totalDeltaPct)}p → 최종 ${"%.1f".format(Locale.KOREA, effPct)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = ItvLine)
                CompactCheckRow("취침 · 조도 보정", "${formatSignedPct(thresholdAdj.bedtimeDelta * 100.0)}p", highlight = thresholdAdj.bedtimeDelta < 0.0)
                CompactCheckRow("연속 사용 피로 보정", "${formatSignedPct(thresholdAdj.fatigueDelta * 100.0)}p", highlight = thresholdAdj.fatigueDelta < 0.0)
                CompactCheckRow("개인 평균 세션 보정", "${formatSignedPct(thresholdAdj.sessionDelta * 100.0)}p", highlight = thresholdAdj.sessionDelta < 0.0)
                CompactCheckRow("스와이프 속도 과열 보정", "${formatSignedPct(thresholdAdj.swipeDelta * 100.0)}p", highlight = thresholdAdj.swipeDelta < 0.0)
                CompactCheckRow("영상 전환 · 누적 스와이프 보정", "${formatSignedPct(thresholdAdj.contentSwitchDelta * 100.0)}p", highlight = thresholdAdj.contentSwitchDelta < 0.0)
                CompactCheckRow(
                    "후보 트리거 코드",
                    if (candidateTriggers.isEmpty()) "없음 (사용 시간/신호 부족)" else candidateTriggers.joinToString(", "),
                    highlight = candidateTriggers.isNotEmpty()
                )
            }
        }

        val canEvaluateJev = !busy && configured && s.collectionConsent && minutesInput.totalRawMinutes > 0
        Button(
            onClick = { vm.evaluateContextBubble(built.jevContext, built.summaryTag) },
            enabled = canEvaluateJev,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    minutesInput.totalRawMinutes == 0 -> "사용 시간을 1분 이상 설정해 주세요"
                    !configured || !s.collectionConsent -> "연결 탭에서 토큰/동의 저장 후 Jev 평가 가능"
                    else -> "설정한 총합 맥락으로 Jev 확률 평가하기"
                },
                fontWeight = FontWeight.Bold
            )
        }

        evalResult?.let { res ->
            val passed = res.passedEffective
            val badgeColor = if (passed) MaterialTheme.colorScheme.error else ItvGreen
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = badgeColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, badgeColor)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (passed) "개입 발동 조건 충족!" else "기준 미달 (개입 보류)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = badgeColor
                        )
                        Text(
                            "Jev ${"%.1f".format(Locale.KOREA, res.probability * 100.0)}% vs 기준 ${"%.1f".format(Locale.KOREA, res.effectiveThreshold * 100.0)}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                    }
                    Text(
                        "평가 조건: ${res.summaryTag} · ${res.latencyMs ?: "-"}ms (${res.model ?: "-"})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // 5. 개입 말풍선 미리보기 & 실제 오버레이 개입 테스트
    TestSection("5. 개입 말풍선 미리보기 & 실제 개입 테스트") {
        CompactCheckGroup("말풍선 강도 · 말투 선택") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    CharacterIntensity.GENTLE to "약하게 (1줄)",
                    CharacterIntensity.NORMAL to "보통 (최대 2줄)",
                    CharacterIntensity.STRONG to "강하게 (최대 2줄)"
                ).forEach { (intensity, label) ->
                    FilterChip(
                        selected = selectedIntensity == intensity,
                        onClick = { previewIntensityOrdinal = intensity.ordinal },
                        label = { Text(label, fontSize = 11.5.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    CoachingTone.SOFT to "부드럽게",
                    CoachingTone.PLAYFUL to "장난스럽게",
                    CoachingTone.FIRM to "단호하게"
                ).forEach { (tone, label) ->
                    FilterChip(
                        selected = selectedTone == tone,
                        onClick = { previewToneOrdinal = tone.ordinal },
                        label = { Text(label, fontSize = 11.5.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        CompactCheckGroup("추출된 개입 사유 (우선순위 순 · 자세 제외)") {
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
                        "${idx + 1}순위 · $reason",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (shownInBubble) FontWeight.Bold else FontWeight.Normal,
                        color = if (shownInBubble) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (shownInBubble) {
                        Surface(shape = RoundedCornerShape(6.dp), color = ItvMint) {
                            Text(
                                "말풍선 표시",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = ItvGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFFFFFDF7),
            border = BorderStroke(2.dp, ItvGreen)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🐾 실제 오버레이 말풍선 미리보기",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ItvGreen
                    )
                    TextButton(
                        onClick = { speechSeed++ },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("다른 문구 보기", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    text = previewBubbleText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF121910),
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("실행 시점:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            FilterChip(
                selected = overlayDelaySec == 0,
                onClick = { overlayDelaySec = 0 },
                label = { Text("즉시 띄우기") }
            )
            FilterChip(
                selected = overlayDelaySec == 3,
                onClick = { overlayDelaySec = 3 },
                label = { Text("3초 뒤 (타 앱 전환 테스트)") }
            )
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = { launchOverlay(CharacterIntensity.GENTLE) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
            ) {
                Text("약한 개입", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { launchOverlay(CharacterIntensity.NORMAL) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
            ) {
                Text("보통 개입", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { launchOverlay(CharacterIntensity.STRONG) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("강한 개입", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    category.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) ItvGreen else MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (active) ItvMint else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        category.weightTier.label,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = if (active) ItvGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniStepButton("-5") { onChange(minutes - 5) }
            MiniStepButton("-1") { onChange(minutes - 1) }
            Text(
                text = "${minutes}분",
                modifier = Modifier.width(44.dp),
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
        modifier = Modifier.height(30.dp),
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

private fun formatSignedPct(value: Double): String = when {
    value > 0.05 -> "+${"%.1f".format(Locale.KOREA, value)}%"
    value < -0.05 -> "${"%.1f".format(Locale.KOREA, value)}%"
    else -> "0.0%"
}
