package com.example.itv.domain.jev

import com.example.itv.domain.coaching.UsageWeightTier

/** Test policy, independently versioned from the existing 30-second rest mission. */
data class JevSettings(
    val enabled: Boolean = false,
    val collectionConsent: Boolean = false,
    val actualIntervention: Boolean = false,
    val testerId: String = "T01",
    val instagram: Boolean = true,
    val youtube: Boolean = true,
    val allApps: Boolean = true,
    val continuousEnabled: Boolean = true,
    val continuousSeconds: Int = 900,
    val returnsEnabled: Boolean = true,
    val returnsThreshold: Int = 3,
    val bedtimeEnabled: Boolean = true,
    val bedtimeDelayMinutes: Int = 0,
    val bedtimeWindowMinutes: Int = 480,
    val probabilityThreshold: Double = 0.75,
    val reevaluateSeconds: Int = 300,
    val additionalViewingSeconds: Int = 300,
    val resultTtlSeconds: Int = 30,
    val laterSeconds: Int = 300,
    val dailyLimit: Int = 999999,
    val personalizedEnabled: Boolean = true,
    val revision: Long = 1
) {
    fun validated(): JevSettings = apply {
        require(Regex("T0[1-7]").matches(testerId)) { "참가자는 T01~T07입니다." }
        require(continuousSeconds in 1..86400) { "시청 기준은 1~86400초입니다." }
        require(returnsThreshold in 1..1440) { "재진입 기준은 1~1440회입니다." }
        require(bedtimeWindowMinutes in 1..1440 && bedtimeDelayMinutes in 0 until bedtimeWindowMinutes) { "취침 지연은 적용 시간보다 작아야 합니다." }
        require(probabilityThreshold.isFinite() && probabilityThreshold in 0.0..1.0) { "확률은 0~100%입니다." }
        require(reevaluateSeconds in 1..86400 && additionalViewingSeconds in 1..86400) { "재평가 간격과 추가 시청은 1~86400초입니다." }
        require(resultTtlSeconds in 1..300 && laterSeconds in 1..86400) { "응답 유효시간은 1~300초, 보류는 1~86400초입니다." }
        require(dailyLimit in 1..999999) { "기기 일일 한도는 1~999999회입니다." }
    }
    fun targets(pkg: String): Boolean {
        if (pkg == "ALL") return true
        if (pkg == "LOCKED" || pkg == "GUARD_BLOCKED") return false
        if (pkg.startsWith("com.team.itv") || pkg.startsWith("com.example.itv")) return false
        if (isLauncherPackage(pkg)) return false
        if (isExcludedSystemPackage(pkg)) return false
        if (pkg == INSTAGRAM) return instagram
        if (pkg == YOUTUBE) return youtube
        return allApps
    }
    companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val YOUTUBE = "com.google.android.youtube"

        fun isLauncherPackage(pkg: String?): Boolean {
            if (pkg == null) return false
            val p = pkg.lowercase()
            return p == "com.sec.android.app.launcher" ||
                   p == "com.google.android.apps.nexuslauncher" ||
                   p == "com.miui.home" ||
                   p == "com.teslacoilsw.launcher" ||
                   p == "com.microsoft.launcher" ||
                   p == "ginlemon.flowerfree" ||
                   p.endsWith(".launcher") ||
                   p.endsWith(".launcher3") ||
                   p.contains(".launcher.") ||
                   p.endsWith(".home") ||
                   p.contains("homescreen")
        }

        fun isExcludedSystemPackage(pkg: String?): Boolean {
            if (pkg == null) return true
            val p = pkg.lowercase()
            return p == "android" ||
                   p == "com.android.systemui" ||
                   p == "com.android.settings" ||
                   p == "com.samsung.android.settings" ||
                   p.contains("incallui") ||
                   p.contains("systemui") ||
                   p.contains("smartcapture") ||
                   p.contains("screenrecorder") ||
                   p.contains("honeyboard") ||
                   p.contains("cocktailbarservice") ||
                   p.contains("gametools")
        }

        fun isDocumentOrMessagingPackage(pkg: String?): Boolean {
            if (pkg == null) return false
            val p = pkg.lowercase()
            return p == "com.kakao.talk" ||
                   p == "com.samsung.android.messaging" ||
                   p == "com.google.android.apps.messaging" ||
                   p == "org.telegram.messenger" ||
                   p == "com.discord" ||
                   p == "com.slack" ||
                   p == "com.whatsapp" ||
                   p.contains(".messaging") ||
                   p.contains(".messenger") ||
                   p.contains("kakaotalk") ||
                   p == "com.google.android.apps.docs" ||
                   p == "com.google.android.apps.docs.editors.docs" ||
                   p == "com.google.android.apps.docs.editors.sheets" ||
                   p == "com.google.android.apps.docs.editors.slides" ||
                   p == "com.microsoft.office.word" ||
                   p == "com.microsoft.office.onenote" ||
                   p == "com.microsoft.office.powerpoint" ||
                   p == "com.microsoft.office.excel" ||
                   p.contains("notion")
        }
    }
}

data class JevContext(
    val sessionId: String,
    val packageName: String,
    val sessionMs: Long,
    val continuousMs: Long,
    val returns10m: Int,
    val returnsObservedSeconds: Int,
    val bedtimePassedMinutes: Int?,
    val observedAt: Long,
    val rawShortformMs: Long = 0L,
    val rawGeneralMs: Long = 0L,
    val rawMidWeightMs: Long = 0L,
    val quality: String = "VALID",
    val personalAvgSessionMinutes: Double? = null,
    val sessionVsAvgRatio: Double? = null,
    val todayShortformMinutes: Double? = null,
    val avg7dDailyMinutes: Double? = null,
    val todayVs7dRatio: Double? = null,
    val effectiveSpm1m: Double? = null,
    val effectiveSessionSpm: Double? = null,
    val personalAvgSpm: Double? = null,
    val currentVsPersonalSpmRatio: Double? = null,
    val swipeAccel1m: Double? = null,
    val fastSwipeStreak: Int = 0,
    val swipeOverheated: Boolean = false,
    val shortformShareOfPhoneRatio: Double? = null,
    val ambientLux: Float? = null,
    val screenBrightness: Int? = null,
    val isNearOrOccluded: Boolean = false,
    val bedtimeDistanceMinutes: Int? = null,
    val userEnvironmentState: String? = null,
    val estimatedDayType: String? = null,
    val dayTypeReason: String? = null,
    // YouTube 롱폼 시청 맥락 (Jev 서버 전송용)
    val longformWatchMs: Long = 0L,
    val currentVideoWatchMs: Long = 0L,
    val completedLongformCount: Int = 0,
    val youtubeWatchMode: String? = null,
    // 일반 앱 / 게임 / 미디어 세부 맥락 (Jev 서버 전송용)
    val appCategory: String? = null,
    val activityPhase: String? = null,
    val audioPipelineKind: String? = null,
    val generalAppIdleSeconds: Long? = null,
    // 유튜브 롱폼 영상 전환 횟수 & 숏폼 누적 스와이프 수 (우리 앱 내부 기준점 보정용)
    val consecutiveLongformChainCount: Int = 0,
    val shortformSwipeCount: Int = 0
) {
    val isRestDay: Boolean get() = estimatedDayType == "REST_DAY"
}

object JevCandidatePolicy {
    fun isSwipeOverheated(c: JevContext): Boolean {
        val spm = c.effectiveSpm1m ?: 0.0
        val accel = c.swipeAccel1m ?: 0.0
        return (c.fastSwipeStreak >= 3 && spm >= 10.0) || (spm >= 12.0 && accel >= 3.0)
    }

    /**
     * Computes continuous bedtime & environmental lighting adjustment (-30%p ~ +15%p).
     *
     * Principles:
     * 1. Proximity occlusion guard: if proximity is 0cm (or near with low lux), the sensor
     *    is physically covered by hand/pillow/bedding. It is NOT genuine dark bedroom usage (returns 0%p).
     * 2. Pre-bedtime dark usage (d < 0, lights off, lux <= 25):
     *    During pre-bedtime window (up to 3~4 hours before bedtime),
     *    lowers intervention threshold linearly down to -15%p.
     * 3. Post-bedtime dark usage (d >= 0 or bedtimePassedMinutes >= 0, lights off, lux <= 25):
     *    Once target bedtime has passed in a dark room, strengthens protection smoothly
     *    from -25%p (at bedtime) to -30%p (30+ minutes past bedtime) so short-form usage
     *    triggers intervention around ~10 minutes.
     * 4. Normal / daytime active usage:
     *    During daytime, or when room lights are on (lux >= 40) and screen is bright,
     *    raises threshold up to +15%p.
     */
    fun computeBedtimeEnvironmentDelta(c: JevContext): Double {
        val lux = c.ambientLux
        val brightness = c.screenBrightness
        val occluded = c.isNearOrOccluded
        val d = c.bedtimeDistanceMinutes ?: c.bedtimePassedMinutes

        if (lux == null && brightness == null && d == null) return 0.0

        // 1. Proximity Sensor Occlusion Check
        if (occluded) {
            return 0.0
        }

        // 2. Time Window Factor
        // d: minutes difference from target bedtime (circularMinuteDiff)
        val bedtimeWeight = when {
            d == null -> 0.0
            d in -60..240 -> 1.0 // Core bedtime (1h before ~ 4h after)
            d in -180 until -60 -> 0.70 + 0.30 * ((d - (-180)).toDouble() / 120.0) // 2~3h before bedtime: 0.70 ~ 1.0
            d in -240 until -180 -> 0.70 * ((d - (-240)).toDouble() / 60.0) // 3~4h before bedtime: 0.0 ~ 0.70
            d in 241..360 -> 1.0 - 0.70 * ((d - 240).toDouble() / 120.0) // Dawn: 1.0 ~ 0.30
            else -> 0.0
        }

        val daytimeWeight = when {
            d == null -> 0.5
            d in -240..360 -> 0.0
            d in -420 until -240 -> 1.0 - ((d - (-420)).toDouble() / 180.0) // Transition from mid-day to evening
            else -> 1.0 // Daytime active hours (4+ hours away from bedtime)
        }

        // 3. Ambient Darkness vs Brightness (공통 암실 임계값 ContextualPreInterventionPolicy.DARK_LUX_THRESHOLD = 20 lx 기준)
        val darkCap = com.example.itv.domain.coaching.ContextualPreInterventionPolicy.DARK_LUX_THRESHOLD
        val fullDark = com.example.itv.domain.coaching.ContextualPreInterventionPolicy.FULL_DARK_LUX_THRESHOLD
        val darknessLevel = when {
            lux == null -> 0.0
            lux <= fullDark -> 1.0
            lux <= darkCap -> ((darkCap - lux) / (darkCap - fullDark)).toDouble().coerceIn(0.0, 1.0)
            else -> 0.0
        }

        val brightnessLevel = when {
            lux == null -> 0.0
            lux <= 40.0f -> 0.0
            lux <= 200.0f -> ((lux - 40.0f) / 160.0).coerceIn(0.0, 1.0)
            else -> 1.0
        }

        // 4. Screen Brightness Factor (0..255)
        val normBrightness = (brightness ?: 128) / 255.0
        val dimScreenFactor = when {
            brightness == null -> 0.5
            normBrightness <= 0.25 -> 1.0
            normBrightness <= 0.45 -> (0.45 - normBrightness) / 0.20
            else -> 0.0
        }
        val brightScreenFactor = when {
            brightness == null -> 0.5
            normBrightness <= 0.40 -> 0.0
            normBrightness <= 0.65 -> (normBrightness - 0.40) / 0.25
            else -> 1.0
        }

        // 5. Bedtime Dark Usage Score (P_sleep in [0.0, 1.0])
        val sleepDarkScore = if (darknessLevel > 0.0 && bedtimeWeight > 0.0) {
            val screenFactor = 0.75 + 0.25 * dimScreenFactor
            (bedtimeWeight * darknessLevel * screenFactor).coerceIn(0.0, 1.0)
        } else 0.0

        // 6. Active / Daytime Usage Score (P_active in [0.0, 1.0])
        val litRoomActiveScore = if (brightnessLevel > 0.0 && brightScreenFactor > 0.0) {
            (brightnessLevel * brightScreenFactor).coerceIn(0.0, 1.0)
        } else 0.0

        val daytimeActiveScore = if (daytimeWeight > 0.0) {
            (daytimeWeight * (0.6 + 0.4 * brightnessLevel)).coerceIn(0.0, 1.0)
        } else 0.0

        val activeScore = maxOf(litRoomActiveScore, daytimeActiveScore)

        // 7. Calculate continuous linear delta:
        // - Pre-bedtime dark (d < 0): up to -15%p
        // - Post-bedtime dark (d >= 0): smoothly strengthened from -25%p (d=0) to -30%p (d>=30)
        // - Daytime / bright room: up to +15%p
        val isPostBedtime = (d != null && d >= 0) || (c.bedtimePassedMinutes != null && c.bedtimePassedMinutes >= 0)
        val maxDarkPenalty = if (isPostBedtime) {
            val passedMin = maxOf(d ?: 0, c.bedtimePassedMinutes ?: 0).coerceAtLeast(0)
            0.25 + 0.05 * (passedMin / 30.0).coerceIn(0.0, 1.0)
        } else {
            0.15
        }
        val delta = if (sleepDarkScore > 0.0) {
            -maxDarkPenalty * sleepDarkScore
        } else if (activeScore > 0.0) {
            0.15 * activeScore
        } else {
            0.0
        }

        return kotlin.math.round(delta.coerceIn(-0.30, 0.15) * 1000.0) / 1000.0
    }

    /**
     * 현재 컨텍스트가 실제 숏폼 시청 화면(유튜브 쇼츠 · 인스타 릴스)인지 판별합니다.
     * - [JevContext.appCategory]가 채워진 경우 `"SHORTFORM"`일 때만 true (유튜브 롱폼/홈, 인스타 피드/DM 제외)
     * - [JevContext.youtubeWatchMode]가 채워진 경우 `"SHORTS"`일 때만 true
     * - 두 필드가 모두 없는 레거시/단위테스트 컨텍스트에서는 패키지명 기준으로 폴백
     */
    fun isShortformContext(c: JevContext): Boolean {
        if (c.appCategory != null) return c.appCategory == "SHORTFORM"
        if (c.youtubeWatchMode != null) return c.youtubeWatchMode == "SHORTS"
        return c.packageName == JevSettings.INSTAGRAM || c.packageName == JevSettings.YOUTUBE
    }

    fun reasons(c: JevContext, s: JevSettings): List<String> = buildList {
        val isAll = c.packageName == "ALL"
        val isShortform = isShortformContext(c)
        if (!isAll && (!s.targets(c.packageName) || c.quality != "VALID")) return@buildList
        if (s.continuousEnabled && c.continuousMs >= s.continuousSeconds * 1000L) add("CONTINUOUS")
        if (s.returnsEnabled && c.returns10m >= s.returnsThreshold) add("REPEATED_RETURNS")
        if (s.bedtimeEnabled && c.bedtimePassedMinutes != null &&
            c.bedtimePassedMinutes >= s.bedtimeDelayMinutes && c.continuousMs > 0) add("BEDTIME")
        if (s.personalizedEnabled && s.continuousEnabled) {
            val minPersonalMs = minOf(180_000L, s.continuousSeconds * 1000L)
            if (c.continuousMs >= minPersonalMs && (c.sessionVsAvgRatio ?: 0.0) >= 1.25 && "CONTINUOUS" !in this) {
                add("PERSONAL_SESSION_EXCEEDED")
            }
            if (isShortform) {
                val minSwipeMs = minOf(120_000L, s.continuousSeconds * 1000L)
                if (c.continuousMs >= minSwipeMs && isSwipeOverheated(c)) {
                    add("RAPID_SWIPE_OVERHEAT")
                }
            }
            val minBedtimeDarkMs = minOf(120_000L, s.continuousSeconds * 1000L)
            if (c.continuousMs >= minBedtimeDarkMs && computeBedtimeEnvironmentDelta(c) <= -0.08 &&
                "CONTINUOUS" !in this && "BEDTIME" !in this) {
                add("PRE_BEDTIME_DARK_USAGE")
            }
            // 뇌과학적 연속 사용 피로 누적 (등가 시간 30분 이상 무중단 사용)
            if (c.continuousMs >= 1800_000L) {
                add("CONTINUOUS_FATIGUE")
            }
            // 유튜브 롱폼 연속 영상 전환(3회 이상) / 숏폼 누적 스와이프(30회 이상)
            val minSwitchMs = minOf(120_000L, s.continuousSeconds * 1000L)
            if (c.continuousMs >= minSwitchMs && c.consecutiveLongformChainCount >= 3) {
                add("LONGFORM_BINGE_CHAIN")
            }
            if (c.continuousMs >= minSwitchMs && c.shortformSwipeCount >= 30) {
                add("SHORTFORM_SWIPE_COUNT")
            }
        }
    }

    data class ThresholdAdjustment(
        val sessionDelta: Double,
        val swipeDelta: Double,
        val phoneShareDelta: Double = 0.0,
        val bedtimeDelta: Double = 0.0,
        val fatigueDelta: Double = 0.0,
        val longformSwitchDelta: Double = 0.0,
        val shortformSwipeCountDelta: Double = 0.0,
        val contentSwitchDelta: Double = 0.0,
        val totalDelta: Double,
        val effectiveThreshold: Double
    )

    /**
     * 연속 시청 피로도 누적 선형 보간 (뇌과학적 전두엽 자제력 저하 반영 및 Jev 모델의 연속 시청 가중치와 이중 증폭 방지 미세 조정):
     * 15분 미만: 0.0 (정상 집중)
     * 15분 ~ 30분: 0%p -> -2%p (선형 증가)
     * 30분 ~ 45분 이상: -2%p -> 최대 -4%p (선형 증가)
     */
    fun computeContinuousFatigueDelta(c: JevContext): Double {
        val min = c.continuousMs / 60000.0
        val delta = when {
            min < 15.0 -> 0.0
            min <= 30.0 -> {
                val r = (min - 15.0) / 15.0
                r * -0.02
            }
            else -> {
                val r = ((min - 30.0) / 15.0).coerceIn(0.0, 1.0)
                -0.02 + (r * -0.02)
            }
        }
        return kotlin.math.round(delta.coerceIn(-0.04, 0.0) * 1000.0) / 1000.0
    }

    /**
     * 유튜브 롱폼 영상 전환 횟수 기반 기준선 보정:
     * - 0~1회: 0%p (정상 시청/단발 탐색)
     * - 2회~5회 이상: 0%p -> 최대 -10%p 선형 보간 (전환 1회당 -2.5%p)
     */
    fun computeLongformSwitchDelta(c: JevContext): Double {
        val switches = c.consecutiveLongformChainCount
        if (switches <= 1) return 0.0
        val ratio = ((switches - 1) / 4.0).coerceIn(0.0, 1.0)
        val delta = ratio * -0.10
        return kotlin.math.round(delta.coerceIn(-0.10, 0.0) * 1000.0) / 1000.0
    }

    /**
     * 숏폼 누적 스와이프 수 기반 기준선 보정:
     * - 0~10회: 0%p (가벼운 탐색)
     * - 11~50회 이상: 0%p -> 최대 -10%p 선형 보간 (10회 초과 10회당 -2.5%p)
     */
    fun computeShortformSwipeCountDelta(c: JevContext): Double {
        val swipes = c.shortformSwipeCount
        if (swipes <= 10) return 0.0
        val ratio = ((swipes - 10) / 40.0).coerceIn(0.0, 1.0)
        val delta = ratio * -0.10
        return kotlin.math.round(delta.coerceIn(-0.10, 0.0) * 1000.0) / 1000.0
    }

    fun computeThresholdAdjustment(s: JevSettings, c: JevContext? = null): ThresholdAdjustment {
        if (c == null || !s.personalizedEnabled) {
            return ThresholdAdjustment(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, s.probabilityThreshold)
        }
        val hasAnySignal = c.sessionVsAvgRatio != null || c.effectiveSpm1m != null ||
            c.swipeAccel1m != null || c.fastSwipeStreak > 0 ||
            c.ambientLux != null || c.screenBrightness != null || c.bedtimeDistanceMinutes != null ||
            c.continuousMs >= 900_000L || c.consecutiveLongformChainCount >= 2 || c.shortformSwipeCount > 10
        if (!hasAnySignal) {
            return ThresholdAdjustment(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, s.probabilityThreshold)
        }

        // 1. 단일 세션 과몰입 선형 보간: 0.5배 (+10%p) ~ 1.0배 (0%p) ~ 1.5배 이상 (-10%p)
        val sessionRatio = c.sessionVsAvgRatio
        val sessionDelta = if (sessionRatio != null && sessionRatio > 0.0) {
            val clamped = sessionRatio.coerceIn(0.5, 1.5)
            kotlin.math.round(((1.0 - clamped) * 0.20) * 1000.0) / 1000.0
        } else 0.0

        // 2. 스와이프 과열 선형 보간: SPM 5.0 (0%p) ~ SPM 15.0 (-8%p) + 스트릭/가속도 (-2%p) => 최대 -10%p (실제 숏폼 화면 외 롱폼·피드·일반 앱은 0%p)
        val isShortform = isShortformContext(c)
        val swipeDelta = if (!isShortform) 0.0 else run {
            val spm = c.effectiveSpm1m ?: 0.0
            val streak = c.fastSwipeStreak
            val accel = (c.swipeAccel1m ?: 0.0).coerceAtLeast(0.0)
            if (spm <= 5.0 && streak < 2) return@run 0.0
            val spmRatio = ((spm - 5.0) / 10.0).coerceIn(0.0, 1.0)
            var heat = spmRatio * -0.08
            if (streak >= 3) {
                val streakBonus = ((streak - 2) * 0.01).coerceIn(0.0, 0.02)
                heat -= streakBonus
            } else if (accel >= 2.0) {
                heat -= 0.01
            }
            kotlin.math.round(heat.coerceIn(-0.10, 0.0) * 1000.0) / 1000.0
        }

        // [제거됨] 폰 사용 중 숏폼 비중은 실시간 몰입도 판단 지표로 부적합하므로 보정 계산에서 제외 (0%p)
        val phoneShareDelta = 0.0

        // 3. 취침 연계 환경 조도 및 화면 밝기 선형 증감: 취침경과암실 (-30%p) ~ 취침전암실 (-15%p) ~ 평상시실내 (0%p) ~ 주간/밝은환경 (+15%p)
        val bedtimeDelta = computeBedtimeEnvironmentDelta(c)

        // 4. 연속 시청 피로도 누적: 15분 미만(0%p) ~ 30분(-2%p) ~ 45분 이상(-4%p)
        val fatigueDelta = computeContinuousFatigueDelta(c)

        // 5. 유튜브 롱폼 영상 전환 횟수 & 숏폼 누적 스와이프 수: 최대 -10%p
        val longformSwitchDelta = computeLongformSwitchDelta(c)
        val shortformSwipeCountDelta = computeShortformSwipeCountDelta(c)
        val contentSwitchDelta = kotlin.math.round((longformSwitchDelta + shortformSwipeCountDelta).coerceIn(-0.10, 0.0) * 1000.0) / 1000.0

        val totalDelta = kotlin.math.round((sessionDelta + swipeDelta + bedtimeDelta + fatigueDelta + contentSwitchDelta).coerceIn(-0.40, 0.25) * 1000.0) / 1000.0
        val eff = kotlin.math.round((s.probabilityThreshold + totalDelta).coerceIn(0.15, 0.95) * 1000.0) / 1000.0
        return ThresholdAdjustment(
            sessionDelta = sessionDelta,
            swipeDelta = swipeDelta,
            phoneShareDelta = phoneShareDelta,
            bedtimeDelta = bedtimeDelta,
            fatigueDelta = fatigueDelta,
            longformSwitchDelta = longformSwitchDelta,
            shortformSwipeCountDelta = shortformSwipeCountDelta,
            contentSwitchDelta = contentSwitchDelta,
            totalDelta = totalDelta,
            effectiveThreshold = eff
        )
    }

    /**
     * Computes the effective intervention probability threshold by applying bounded
     * personalization, session immersion, and swipe-dynamics adjustments when personalizedEnabled is true.
     */
    fun effectiveThreshold(s: JevSettings, c: JevContext? = null): Double {
        return computeThresholdAdjustment(s, c).effectiveThreshold
    }

    /**
     * Calculates the dynamic re-evaluation interval (and required additional viewing)
     * based on the probability gap (threshold - probability).
     *
     * Short-form mode (isGeneralOrLongform = false):
     * - Small gap (<= 15%p): 30s ~ 90s
     * - Medium gap (15%p ~ 30%p): 90s ~ 210s
     * - Large gap (30%p ~ 50%p+): 210s ~ 360s
     *
     * Long-form / General mode (isGeneralOrLongform = true):
     * - Small gap (<= 15%p): 60s ~ 120s (실사용 3분 ~ 6분)
     * - Medium gap (15%p ~ 30%p): 120s ~ 240s (실사용 6분 ~ 12분)
     * - Large gap (30%p ~ 50%p+): 240s ~ 360s (실사용 12분 ~ 18분)
     */
    fun calculateDynamicInterval(threshold: Double, probability: Double?, isGeneralOrLongform: Boolean = false): Int {
        if (probability == null || !probability.isFinite()) return if (isGeneralOrLongform) 360 else 300
        val gap = (threshold - probability).coerceAtLeast(0.0)
        val sec = if (isGeneralOrLongform) {
            when {
                gap <= 0.0 -> 60.0
                gap <= 0.15 -> {
                    val r = gap / 0.15
                    60.0 + (120.0 - 60.0) * r
                }
                gap <= 0.30 -> {
                    val r = (gap - 0.15) / 0.15
                    120.0 + (240.0 - 120.0) * r
                }
                gap <= 0.50 -> {
                    val r = (gap - 0.30) / 0.20
                    240.0 + (360.0 - 240.0) * r
                }
                else -> 360.0
            }
        } else {
            when {
                gap <= 0.0 -> 30.0
                gap <= 0.15 -> {
                    val r = gap / 0.15
                    30.0 + (90.0 - 30.0) * r
                }
                gap <= 0.30 -> {
                    val r = (gap - 0.15) / 0.15
                    90.0 + (210.0 - 90.0) * r
                }
                gap <= 0.50 -> {
                    val r = (gap - 0.30) / 0.20
                    210.0 + (360.0 - 210.0) * r
                }
                else -> 360.0
            }
        }
        return kotlin.math.round(sec).toInt()
    }

    /** Concise, human-readable summary of how and why the effective probability threshold changed. */
    fun thresholdBreakdown(s: JevSettings, c: JevContext? = null): String {
        val basePct = s.probabilityThreshold * 100.0
        if (!s.personalizedEnabled) return "기본 ${"%.0f".format(basePct)}% (보정 꺼짐)"
        if (c == null) return "기본 ${"%.0f".format(basePct)}% (관측 대기)"
        val adj = computeThresholdAdjustment(s, c)
        val effPct = adj.effectiveThreshold * 100.0
        val diffPct = effPct - basePct
        val factors = mutableListOf<String>()
        if (kotlin.math.abs(adj.sessionDelta) >= 0.005) {
            val sign = if (adj.sessionDelta > 0) "+" else ""
            val ratioPct = (c.sessionVsAvgRatio ?: 1.0) * 100.0
            factors.add("세션 ${"%.0f".format(ratioPct)}% (${sign}${"%.0f".format(adj.sessionDelta * 100)}%p)")
        }
        if (kotlin.math.abs(adj.swipeDelta) >= 0.005) {
            factors.add("스와이프 ${"%.0f".format(c.effectiveSpm1m ?: 0.0)}SPM (${"%.0f".format(adj.swipeDelta * 100)}%p)")
        }
        if (kotlin.math.abs(adj.bedtimeDelta) >= 0.005) {
            val sign = if (adj.bedtimeDelta > 0) "+" else ""
            val isPostBedtime = (c.bedtimeDistanceMinutes ?: -1) >= 0 || (c.bedtimePassedMinutes ?: -1) >= 0
            val envDesc = when {
                c.isNearOrOccluded -> "센서가림"
                adj.bedtimeDelta < 0 && isPostBedtime -> "취침경과암실"
                adj.bedtimeDelta < 0 -> "취침전암실"
                else -> "주간/밝은환경"
            }
            factors.add("$envDesc (${sign}${"%.0f".format(adj.bedtimeDelta * 100)}%p)")
        }
        if (kotlin.math.abs(adj.fatigueDelta) >= 0.005) {
            val contMin = "%.0f".format(c.continuousMs / 60000.0)
            factors.add("연속피로 ${contMin}분 (${"%.0f".format(adj.fatigueDelta * 100)}%p)")
        }
        if (kotlin.math.abs(adj.contentSwitchDelta) >= 0.005) {
            val deltaStr = "${"%.0f".format(adj.contentSwitchDelta * 100)}%p"
            when {
                adj.longformSwitchDelta < 0.0 && adj.shortformSwipeCountDelta < 0.0 ->
                    factors.add("롱폼전환 ${c.consecutiveLongformChainCount}회·스와이프 ${c.shortformSwipeCount}회 ($deltaStr)")
                adj.longformSwitchDelta < 0.0 ->
                    factors.add("롱폼 영상전환 ${c.consecutiveLongformChainCount}회 ($deltaStr)")
                else ->
                    factors.add("숏폼 스와이프 ${c.shortformSwipeCount}회 ($deltaStr)")
            }
        }
        return if (kotlin.math.abs(diffPct) >= 0.5 || factors.isNotEmpty()) {
            val sign = if (diffPct > 0) "+" else ""
            val detail = if (factors.isNotEmpty()) " · ${factors.joinToString(", ")}" else ""
            "기본 ${"%.0f".format(basePct)}% → ${"%.0f".format(effPct)}% (${sign}${"%.0f".format(diffPct)}%p$detail)"
        } else {
            "기본 ${"%.0f".format(basePct)}% (변화 0%p)"
        }
    }

    fun passes(probability: Double, s: JevSettings, c: JevContext? = null): Boolean {
        val threshold = effectiveThreshold(s, c)
        return probability.isFinite() && probability in 0.0..1.0 && probability >= threshold
    }
    /** A displayed offer/reaction is no longer limited by the pre-display response TTL. */
    fun canContinueDisplayed(requestRevision: Long, settings: JevSettings, requested: JevContext,
                             current: JevContext?) =
        settings.enabled && settings.collectionConsent && settings.actualIntervention &&
        requestRevision == settings.revision && current?.quality == "VALID" &&
        current.sessionId == requested.sessionId && current.packageName == requested.packageName &&
        settings.targets(current.packageName)
    fun resultIsCurrent(requestRevision: Long, settings: JevSettings, requested: JevContext,
                        current: JevContext?, responseAgeMs: Long) =
        requestRevision == settings.revision && current?.quality == "VALID" &&
        current.sessionId == requested.sessionId && current.packageName == requested.packageName &&
        responseAgeMs in 0..settings.resultTtlSeconds * 1000L
}

/** No interpolation across unknown observations. Session boundary is governed by standard timeout and rest rules. */
class JevSessionTracker(private val newId: () -> String) {
    companion object {
        const val INTERRUPTION_TOLERANCE_MS = 90_000L  // 90초 이탈 유예 시간
        const val RECOGNIZED_REST_MS = 180_000L         // 3분 완전 휴식 인정 시간 (세션 종료/리셋)
        const val SESSION_TIMEOUT_MS = 900_000L         // 15분 부재 세션 만료 시간
    }
    data class AppState(
        var id: String, var totalMs: Long = 0, var continuousMs: Long = 0,
        var awayMs: Long = 0, var entered: Boolean = false,
        var nonConfirmedMs: Long = 0,
        var screenOffMs: Long = 0,
        var rawShortformMs: Long = 0,
        var rawMidMs: Long = 0,
        var rawLowMs: Long = 0,
        var rawGeneralMs: Long = 0,
        var totalShortformMs: Long = 0,
        var totalMidMs: Long = 0,
        var totalLowMs: Long = 0,
        var totalGeneralMs: Long = 0,
        /** Continuous viewing of the most recent run; kept after reset for test inspection only. */
        var lastRunContinuousMs: Long = 0,
        val returns: MutableList<Long> = mutableListOf()
    ) {
        val totalRawMs: Long
            get() = totalShortformMs + totalMidMs + totalLowMs

        internal fun recomputeWeighted() {
            rawGeneralMs = rawMidMs + rawLowMs
            totalGeneralMs = totalMidMs + totalLowMs
            continuousMs = rawShortformMs + (rawMidMs / 2L) + (rawLowMs / 3L)
            totalMs = totalShortformMs + (totalMidMs / 2L) + (totalLowMs / 3L)
        }

        internal fun clearContinuousRaw() {
            continuousMs = 0L
            rawShortformMs = 0L
            rawMidMs = 0L
            rawLowMs = 0L
            rawGeneralMs = 0L
        }

        internal fun clearSessionTotals() {
            totalMs = 0L
            rawShortformMs = 0L; rawMidMs = 0L; rawLowMs = 0L; rawGeneralMs = 0L
            totalShortformMs = 0L; totalMidMs = 0L; totalLowMs = 0L; totalGeneralMs = 0L
        }
    }
    val states = linkedMapOf<String, AppState>()
    private var previousAt: Long? = null
    private var previousPkg: String? = null
    private var previousConfirmed: String? = null
    private var coverageStart: Long? = null

    fun observe(
        now: Long,
        pkg: String?,
        confirmedPkg: String?,
        nonShortformTier: UsageWeightTier = UsageWeightTier.LOW_1_TO_3
    ) {
        val before = previousAt
        val delta = if (before == null) 0 else now - before
        val contiguous = before != null && delta in 1..5000
        if (coverageStart == null || !contiguous || pkg == null) coverageStart = now
        val inspectingItv = pkg != null && (pkg.startsWith("com.team.itv") || pkg.startsWith("com.example.itv"))
        val isTargetPkg = pkg != null && pkg != "LOCKED" && pkg != "GUARD_BLOCKED" && !inspectingItv &&
            !JevSettings.isLauncherPackage(pkg) && !JevSettings.isExcludedSystemPackage(pkg)

        val targetApps = (listOf(JevSettings.INSTAGRAM, JevSettings.YOUTUBE, "ALL") +
            listOfNotNull(pkg).filter { it != "LOCKED" && it != "GUARD_BLOCKED" && !it.startsWith("com.team.itv") && !it.startsWith("com.example.itv") }).distinct()

        for (app in targetApps) {
            val state = states.getOrPut(app) { AppState(newId()) }
            if (app == "ALL") {
                val enteredAll = isTargetPkg && (previousPkg == null || previousPkg == "LOCKED" ||
                    JevSettings.isLauncherPackage(previousPkg) || JevSettings.isExcludedSystemPackage(previousPkg))
                if (enteredAll) {
                    val verifiedAway = state.awayMs + if (contiguous && previousPkg != null) delta else 0L
                    val verifiedScreenOff = state.screenOffMs + if (contiguous && previousPkg == "LOCKED") delta else 0L
                    if (verifiedAway >= SESSION_TIMEOUT_MS || verifiedScreenOff >= RECOGNIZED_REST_MS) {
                        state.id = newId()
                        state.clearSessionTotals()
                    }
                    if (state.entered && previousPkg != null) state.returns.add(now)
                    state.entered = true
                }
                if (!isTargetPkg && contiguous) {
                    state.awayMs += delta
                    if (pkg == "LOCKED") state.screenOffMs += delta else state.screenOffMs = 0L
                } else {
                    state.awayMs = 0L
                    state.screenOffMs = 0L
                }
                if (confirmedPkg != null && contiguous) {
                    state.rawShortformMs += delta
                    state.totalShortformMs += delta
                    state.recomputeWeighted()
                    state.nonConfirmedMs = 0L
                } else if (inspectingItv) {
                    // Preserve continuousMs while checking ITV app
                } else if (isTargetPkg && contiguous) {
                    when (nonShortformTier) {
                        UsageWeightTier.FULL_1_TO_1 -> {
                            state.rawShortformMs += delta
                            state.totalShortformMs += delta
                        }
                        UsageWeightTier.MID_1_TO_2 -> {
                            state.rawMidMs += delta
                            state.totalMidMs += delta
                        }
                        UsageWeightTier.LOW_1_TO_3 -> {
                            state.rawLowMs += delta
                            state.totalLowMs += delta
                        }
                    }
                    state.recomputeWeighted()
                    state.nonConfirmedMs = 0L
                } else if (!contiguous && previousPkg != "LOCKED" && pkg != "LOCKED") {
                    state.clearContinuousRaw(); state.nonConfirmedMs = 0L
                } else {
                    state.nonConfirmedMs += delta
                    if (state.nonConfirmedMs >= INTERRUPTION_TOLERANCE_MS) {
                        state.clearContinuousRaw()
                    }
                }
                if (state.continuousMs > 0L) state.lastRunContinuousMs = state.continuousMs
                state.returns.removeAll { now - it >= SESSION_TIMEOUT_MS || it > now }
            } else {
                if (pkg == app && previousPkg != app) {
                    val verifiedAway = state.awayMs + if (contiguous && previousPkg != null) delta else 0L
                    val verifiedScreenOff = state.screenOffMs + if (contiguous && previousPkg == "LOCKED") delta else 0L
                    if (verifiedAway >= SESSION_TIMEOUT_MS || verifiedScreenOff >= RECOGNIZED_REST_MS) {
                        state.id = newId()
                        state.clearSessionTotals()
                    }
                    if (state.entered && previousPkg != null) state.returns.add(now)
                    state.entered = true
                }
                if (pkg != null && pkg != app && previousPkg != null && previousPkg != app && contiguous) {
                    state.awayMs += delta
                    if (pkg == "LOCKED") state.screenOffMs += delta else state.screenOffMs = 0L
                } else if (pkg == app || pkg == null || !contiguous) {
                    state.awayMs = 0L
                    state.screenOffMs = 0L
                }
                if (confirmedPkg == app && contiguous) {
                    if (previousConfirmed == app || state.continuousMs > 0L) {
                        state.rawShortformMs += delta
                        state.totalShortformMs += delta
                        state.recomputeWeighted()
                    }
                    state.nonConfirmedMs = 0L
                } else if (confirmedPkg == app) {
                    state.nonConfirmedMs = 0L
                } else if (inspectingItv) {
                    // Preserve continuousMs while checking ITV app
                } else if (pkg == app && contiguous) {
                    // 3단계 가중치 적용: 숏폼은 1:1, 피드/SNS·게임은 1:2, 롱폼·일반 앱은 1:3
                    when (nonShortformTier) {
                        UsageWeightTier.FULL_1_TO_1 -> {
                            state.rawShortformMs += delta
                            state.totalShortformMs += delta
                        }
                        UsageWeightTier.MID_1_TO_2 -> {
                            state.rawMidMs += delta
                            state.totalMidMs += delta
                        }
                        UsageWeightTier.LOW_1_TO_3 -> {
                            state.rawLowMs += delta
                            state.totalLowMs += delta
                        }
                    }
                    state.recomputeWeighted()
                    state.nonConfirmedMs = 0L
                } else if (!contiguous && previousPkg != "LOCKED" && pkg != "LOCKED") {
                    // Unobserved/unknown gap while active: reset continuous viewing
                    state.clearContinuousRaw(); state.nonConfirmedMs = 0L
                } else {
                    // In another app, or screen turned off/locked:
                    // Only reset continuous viewing if nonConfirmedMs exceeds INTERRUPTION_TOLERANCE_MS (90초)
                    state.nonConfirmedMs += delta
                    if (state.nonConfirmedMs >= INTERRUPTION_TOLERANCE_MS) {
                        state.clearContinuousRaw()
                    }
                }
                if (state.continuousMs > 0L) state.lastRunContinuousMs = state.continuousMs
                state.returns.removeAll { now - it >= SESSION_TIMEOUT_MS || it > now }
            }
        }
        previousAt = now; previousPkg = pkg
        if (!inspectingItv) previousConfirmed = confirmedPkg
    }
    fun context(pkg: String, now: Long, wall: Long, bedtime: Int?): JevContext? {
        val appState = states[pkg]
        val allState = states["ALL"]
        val state = appState ?: allState ?: return null
        val continuous = if (pkg == "ALL") (allState?.continuousMs ?: 0L) else maxOf(appState?.continuousMs ?: 0L, allState?.continuousMs ?: 0L)
        val total = if (pkg == "ALL") (allState?.totalMs ?: 0L) else maxOf(appState?.totalMs ?: 0L, allState?.totalMs ?: 0L)
        val rawShort = if (pkg == "ALL") (allState?.rawShortformMs ?: 0L) else maxOf(appState?.rawShortformMs ?: 0L, allState?.rawShortformMs ?: 0L)
        val rawGen = if (pkg == "ALL") (allState?.rawGeneralMs ?: 0L) else maxOf(appState?.rawGeneralMs ?: 0L, allState?.rawGeneralMs ?: 0L)
        val rawMid = if (pkg == "ALL") (allState?.rawMidMs ?: 0L) else maxOf(appState?.rawMidMs ?: 0L, allState?.rawMidMs ?: 0L)
        val returnsCount = if (pkg == "ALL") (allState?.returns?.size ?: 0) else maxOf(appState?.returns?.size ?: 0, allState?.returns?.size ?: 0)

        return JevContext(
            sessionId = state.id,
            packageName = pkg,
            sessionMs = total,
            continuousMs = continuous,
            returns10m = returnsCount,
            returnsObservedSeconds = ((now - (coverageStart ?: now)) / 1000).coerceIn(0, 600).toInt(),
            bedtimePassedMinutes = bedtime,
            observedAt = wall,
            rawShortformMs = rawShort,
            rawGeneralMs = rawGen,
            rawMidWeightMs = rawMid
        )
    }
    /** Restore totals only (preserving per-tier raw split when available). Never restore continuity or absence from a wall-clock difference. */
    fun restore(
        pkg: String,
        id: String,
        totalMs: Long,
        entered: Boolean,
        totalShortformMs: Long = totalMs,
        totalMidMs: Long = 0L,
        totalLowMs: Long = 0L
    ) {
        val coerced = totalMs.coerceAtLeast(0)
        val sf = totalShortformMs.coerceAtLeast(0)
        val mid = totalMidMs.coerceAtLeast(0)
        val low = totalLowMs.coerceAtLeast(0)
        val hasSplit = sf > 0L || mid > 0L || low > 0L
        val st = AppState(
            id = id,
            totalMs = coerced,
            totalShortformMs = if (hasSplit) sf else coerced,
            totalMidMs = mid,
            totalLowMs = low,
            entered = entered
        )
        if (hasSplit) {
            st.recomputeWeighted()
        }
        states[pkg] = st
    }
    /** Reset session state for a package when a new calendar day starts or session timeout elapsed. */
    fun resetSession(pkg: String) {
        states[pkg] = AppState(newId())
        if (previousPkg == pkg) previousPkg = null
        if (previousConfirmed == pkg) previousConfirmed = null
    }
    /** Reset continuous viewing and non-confirmed counters while keeping total session time intact (e.g. on rest mission success). */
    fun resetContinuous(pkg: String) {
        states[pkg]?.let {
            it.clearContinuousRaw()
            it.nonConfirmedMs = 0L
        }
        if (pkg != "ALL") {
            states["ALL"]?.let {
                it.clearContinuousRaw()
                it.nonConfirmedMs = 0L
            }
        }
    }
}
