package com.example.itv.domain.jev

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
    val probabilityThreshold: Double = 0.8,
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
        if (pkg == "com.team.itv" || pkg == "com.example.itv") return false
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
    val lastInterventionOutcome: String? = null,
    val postInterventionActivity: Boolean = false,
    val resistanceStreak: Int = 0,
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
    // 추천 영상을 타고 연속으로 본 횟수 (Jev 서버에는 전송하지 않고 우리 앱 내부 기준점 보정에만 사용)
    val consecutiveLongformChainCount: Int = 0
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
     * Computes continuous bedtime & environmental lighting adjustment (-15%p ~ +15%p).
     *
     * Principles:
     * 1. Proximity occlusion guard: if proximity is 0cm (or near with low lux), the sensor
     *    is physically covered by hand/pillow/bedding. It is NOT genuine dark bedroom usage (returns 0%p).
     * 2. Pre-bedtime dark usage (lights off, lux <= 25, screen dimmed or watching in dark):
     *    During bedtime window (including 2~3 hours before bedtime up to late night),
     *    lowers intervention threshold linearly down to -15%p to strictly prevent bedtime sleep disruption.
     * 3. Normal / daytime active usage:
     *    During daytime, or even when approaching bedtime if room lights are on (lux >= 40)
     *    and screen is bright, the user is active/awake, raising threshold up to +15%p.
     */
    fun computeBedtimeEnvironmentDelta(c: JevContext): Double {
        val lux = c.ambientLux
        val brightness = c.screenBrightness
        val occluded = c.isNearOrOccluded
        val d = c.bedtimeDistanceMinutes

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

        // 3. Ambient Darkness vs Brightness
        val darknessLevel = when {
            lux == null -> 0.0
            lux <= 5.0f -> 1.0
            lux <= 25.0f -> ((25.0f - lux) / 20.0).coerceIn(0.0, 1.0)
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

        // 5. Pre-bedtime Dark Usage Score (P_sleep in [0.0, 1.0])
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

        // 7. Calculate continuous linear delta in [-0.15, +0.15]
        val delta = if (sleepDarkScore > 0.0) {
            -0.15 * sleepDarkScore
        } else if (activeScore > 0.0) {
            0.15 * activeScore
        } else {
            0.0
        }

        return kotlin.math.round(delta.coerceIn(-0.15, 0.15) * 1000.0) / 1000.0
    }

    fun reasons(c: JevContext, s: JevSettings): List<String> = buildList {
        val isAll = c.packageName == "ALL"
        val isShortform = c.packageName == JevSettings.INSTAGRAM || c.packageName == JevSettings.YOUTUBE
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
            // 직전 개입 미룸 / 무응답 후 시청 지속
            if (c.lastInterventionOutcome in setOf("LATER", "NO_RESPONSE") && c.postInterventionActivity) {
                add("INTERVENTION_RESISTANCE")
            }
        }
    }

    data class ThresholdAdjustment(
        val sessionDelta: Double,
        val swipeDelta: Double,
        val phoneShareDelta: Double = 0.0,
        val bedtimeDelta: Double = 0.0,
        val fatigueDelta: Double = 0.0,
        val resistanceDelta: Double = 0.0,
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
     * 직전 개입 반응 이력 (개입 저항 및 시청 지속):
     * - '나중에(LATER)' 선택 후 시청 지속: 1회 -5%p, 2회 이상 -8%p ~ -10%p
     * - '무응답(NO_RESPONSE)' 후 스와이프 등 시청 활동 지속 (무시하고 시청): 1회 -6%p, 2회 이상 -10%p
     */
    fun computeInterventionResistanceDelta(c: JevContext): Double {
        val outcome = c.lastInterventionOutcome ?: return 0.0
        val streak = c.resistanceStreak.coerceAtLeast(1)
        val delta = when (outcome) {
            "LATER" -> {
                if (streak >= 2) -0.08 - (0.02 * (streak - 2).coerceIn(0, 1))
                else -0.05
            }
            "NO_RESPONSE" -> {
                if (c.postInterventionActivity) {
                    if (streak >= 2) -0.10
                    else -0.06
                } else 0.0
            }
            else -> 0.0
        }
        return kotlin.math.round(delta.coerceIn(-0.10, 0.0) * 1000.0) / 1000.0
    }

    fun computeThresholdAdjustment(s: JevSettings, c: JevContext? = null): ThresholdAdjustment {
        if (c == null || !s.personalizedEnabled) {
            return ThresholdAdjustment(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, s.probabilityThreshold)
        }
        val hasAnySignal = c.sessionVsAvgRatio != null || c.effectiveSpm1m != null ||
            c.swipeAccel1m != null || c.fastSwipeStreak > 0 ||
            c.ambientLux != null || c.screenBrightness != null || c.bedtimeDistanceMinutes != null ||
            c.continuousMs >= 900_000L || c.lastInterventionOutcome != null
        if (!hasAnySignal) {
            return ThresholdAdjustment(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, s.probabilityThreshold)
        }

        // 1. 단일 세션 과몰입 선형 보간: 0.5배 (+10%p) ~ 1.0배 (0%p) ~ 1.5배 이상 (-10%p)
        val sessionRatio = c.sessionVsAvgRatio
        val sessionDelta = if (sessionRatio != null && sessionRatio > 0.0) {
            val clamped = sessionRatio.coerceIn(0.5, 1.5)
            kotlin.math.round(((1.0 - clamped) * 0.20) * 1000.0) / 1000.0
        } else 0.0

        // 2. 스와이프 과열 선형 보간: SPM 5.0 (0%p) ~ SPM 15.0 (-8%p) + 스트릭/가속도 (-2%p) => 최대 -10%p (숏폼 외 일반 앱 및 전체 시 0%p)
        val isShortform = c.packageName == JevSettings.INSTAGRAM || c.packageName == JevSettings.YOUTUBE
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

        // 3. 취침 연계 환경 조도 및 화면 밝기 선형 증감: 취침전암실 (-15%p) ~ 평상시실내 (0%p) ~ 주간/밝은환경 (+15%p)
        val bedtimeDelta = computeBedtimeEnvironmentDelta(c)

        // 4. 연속 시청 피로도 누적: 15분 미만(0%p) ~ 30분(-2%p) ~ 45분 이상(-4%p)
        val fatigueDelta = computeContinuousFatigueDelta(c)

        // 5. 직전 개입 반응 이력 (나중에/무응답 후 시청 지속): -5%p ~ -10%p
        val resistanceDelta = computeInterventionResistanceDelta(c)

        val totalDelta = kotlin.math.round((sessionDelta + swipeDelta + bedtimeDelta + fatigueDelta + resistanceDelta).coerceIn(-0.40, 0.25) * 1000.0) / 1000.0
        val eff = kotlin.math.round((s.probabilityThreshold + totalDelta).coerceIn(0.15, 0.95) * 1000.0) / 1000.0
        return ThresholdAdjustment(sessionDelta, swipeDelta, phoneShareDelta, bedtimeDelta, fatigueDelta, resistanceDelta, totalDelta, eff)
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
            val envDesc = when {
                c.isNearOrOccluded -> "센서가림"
                adj.bedtimeDelta < 0 -> "취침전암실"
                else -> "주간/밝은환경"
            }
            factors.add("$envDesc (${sign}${"%.0f".format(adj.bedtimeDelta * 100)}%p)")
        }
        if (kotlin.math.abs(adj.fatigueDelta) >= 0.005) {
            val contMin = "%.0f".format(c.continuousMs / 60000.0)
            factors.add("연속피로 ${contMin}분 (${"%.0f".format(adj.fatigueDelta * 100)}%p)")
        }
        if (kotlin.math.abs(adj.resistanceDelta) >= 0.005) {
            val tag = if (c.lastInterventionOutcome == "LATER") "나중에미룸" else "무응답시청지속"
            factors.add("$tag (${"%.0f".format(adj.resistanceDelta * 100)}%p)")
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
        var rawGeneralMs: Long = 0,
        var totalShortformMs: Long = 0,
        var totalGeneralMs: Long = 0,
        /** Continuous viewing of the most recent run; kept after reset for test inspection only. */
        var lastRunContinuousMs: Long = 0,
        val returns: MutableList<Long> = mutableListOf()
    )
    val states = linkedMapOf<String, AppState>()
    private var previousAt: Long? = null
    private var previousPkg: String? = null
    private var previousConfirmed: String? = null
    private var coverageStart: Long? = null

    fun observe(now: Long, pkg: String?, confirmedPkg: String?) {
        val before = previousAt
        val delta = if (before == null) 0 else now - before
        val contiguous = before != null && delta in 1..1500
        if (coverageStart == null || !contiguous || pkg == null) coverageStart = now
        val inspectingItv = pkg == "com.team.itv" || pkg == "com.example.itv"
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
                        state.id = newId(); state.totalMs = 0
                        state.rawShortformMs = 0L; state.rawGeneralMs = 0L
                        state.totalShortformMs = 0L; state.totalGeneralMs = 0L
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
                    state.continuousMs = state.rawShortformMs + (state.rawGeneralMs / 3L)
                    state.totalMs = state.totalShortformMs + (state.totalGeneralMs / 3L)
                    state.nonConfirmedMs = 0L
                } else if (inspectingItv) {
                    // Preserve continuousMs while checking ITV app
                } else if (isTargetPkg && contiguous) {
                    state.rawGeneralMs += delta
                    state.totalGeneralMs += delta
                    state.continuousMs = state.rawShortformMs + (state.rawGeneralMs / 3L)
                    state.totalMs = state.totalShortformMs + (state.totalGeneralMs / 3L)
                    state.nonConfirmedMs = 0L
                } else if (!contiguous && previousPkg != "LOCKED" && pkg != "LOCKED") {
                    state.continuousMs = 0L; state.rawShortformMs = 0L; state.rawGeneralMs = 0L; state.nonConfirmedMs = 0L
                } else {
                    state.nonConfirmedMs += delta
                    if (state.nonConfirmedMs >= INTERRUPTION_TOLERANCE_MS) {
                        state.continuousMs = 0L
                        state.rawShortformMs = 0L
                        state.rawGeneralMs = 0L
                    }
                }
                if (state.continuousMs > 0L) state.lastRunContinuousMs = state.continuousMs
                state.returns.removeAll { now - it >= SESSION_TIMEOUT_MS || it > now }
            } else {
                if (pkg == app && previousPkg != app) {
                    val verifiedAway = state.awayMs + if (contiguous && previousPkg != null) delta else 0L
                    val verifiedScreenOff = state.screenOffMs + if (contiguous && previousPkg == "LOCKED") delta else 0L
                    if (verifiedAway >= SESSION_TIMEOUT_MS || verifiedScreenOff >= RECOGNIZED_REST_MS) {
                        state.id = newId(); state.totalMs = 0
                        state.rawShortformMs = 0L; state.rawGeneralMs = 0L
                        state.totalShortformMs = 0L; state.totalGeneralMs = 0L
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
                        state.continuousMs = state.rawShortformMs + (state.rawGeneralMs / 3L)
                        state.totalMs = state.totalShortformMs + (state.totalGeneralMs / 3L)
                    }
                    state.nonConfirmedMs = 0L
                } else if (confirmedPkg == app) {
                    state.nonConfirmedMs = 0L
                } else if (inspectingItv) {
                    // Preserve continuousMs while checking ITV app
                } else if (pkg == app && contiguous) {
                    // Continuous usage within the target app (e.g. YouTube longform/home, Instagram feed, or user app):
                    // 1:3 ratio (1 second equivalent per 3 seconds of general app viewing)
                    state.rawGeneralMs += delta
                    state.totalGeneralMs += delta
                    state.continuousMs = state.rawShortformMs + (state.rawGeneralMs / 3L)
                    state.totalMs = state.totalShortformMs + (state.totalGeneralMs / 3L)
                    state.nonConfirmedMs = 0L
                } else if (!contiguous && previousPkg != "LOCKED" && pkg != "LOCKED") {
                    // Unobserved/unknown gap while active: reset continuous viewing
                    state.continuousMs = 0L; state.rawShortformMs = 0L; state.rawGeneralMs = 0L; state.nonConfirmedMs = 0L
                } else {
                    // In another app, or screen turned off/locked:
                    // Only reset continuous viewing if nonConfirmedMs exceeds INTERRUPTION_TOLERANCE_MS (90초)
                    state.nonConfirmedMs += delta
                    if (state.nonConfirmedMs >= INTERRUPTION_TOLERANCE_MS) {
                        state.continuousMs = 0L
                        state.rawShortformMs = 0L
                        state.rawGeneralMs = 0L
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
            rawGeneralMs = rawGen
        )
    }
    /** Restore totals only. Never restore continuity or absence from a wall-clock difference. */
    fun restore(pkg: String, id: String, totalMs: Long, entered: Boolean) {
        val coerced = totalMs.coerceAtLeast(0)
        states[pkg] = AppState(id, coerced, totalShortformMs = coerced, entered = entered)
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
            it.continuousMs = 0L
            it.rawShortformMs = 0L
            it.rawGeneralMs = 0L
            it.nonConfirmedMs = 0L
        }
        if (pkg != "ALL") {
            states["ALL"]?.let {
                it.continuousMs = 0L
                it.rawShortformMs = 0L
                it.rawGeneralMs = 0L
                it.nonConfirmedMs = 0L
            }
        }
    }
}
