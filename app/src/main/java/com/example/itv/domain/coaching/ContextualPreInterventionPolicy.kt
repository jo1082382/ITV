package com.example.itv.domain.coaching

/**
 * 상황별 자동 사전 개입(약하게 · [CharacterIntensity.GENTLE]) 정책:
 * Jev 확률이 임계값을 넘어 주 개입(보통/강하게)이 실행되기 전에,
 * 시력과 수면 리듬을 해치는 4가지 핵심 위험 맥락을 먼저 감지하여
 * 화면 구석에서 간결한 경고 말풍선으로 위험성을 알려줍니다.
 *
 * 1. [PreInterventionKind.POST_BEDTIME]: 추정·추천·목표 취침 시각 이후 사용
 * 2. [PreInterventionKind.DARK_BRIGHT_SCREEN]: 어두운 곳(≤20 lx)에서 밝은 화면(>75/255) 사용
 * 3. [PreInterventionKind.PRE_BEDTIME_PREP]: 취침 준비 시간(취침 전 1시간 이내) 사용
 * 4. [PreInterventionKind.DARK_DIM_SCREEN]: 어두운 곳(≤20 lx)에서 어두운 화면(≤75/255) 사용
 */
enum class PreInterventionKind(val label: String) {
    /** 추정·추천·목표 취침 시각 이후: 수면 부족·리듬 붕괴 경고 */
    POST_BEDTIME("추정·추천 취침 시각 초과"),
    /** 어두운 환경 + 밝은 화면: 눈부심·시력 피로 급증 경고 */
    DARK_BRIGHT_SCREEN("어두운 곳 밝은 화면"),
    /** 취침 준비 시간(취침 전 60분 이내): 수면 골든타임 보호 경고 */
    PRE_BEDTIME_PREP("취침 준비 시간(전 1시간)"),
    /** 어두운 환경 + 어두운 화면: 암실 시청 눈 피로·수면 지연 경고 */
    DARK_DIM_SCREEN("어두운 곳 어두운 화면")
}

data class PreInterventionDecision(
    val kind: PreInterventionKind,
    val reason: String,
    val bubbleContent: OfferBubbleContent
)

object ContextualPreInterventionPolicy {
    /** 전 모듈(Jev 연속 암실 강도·호환 기준) 공통 암실 조도 상한 (20 lx 이하) */
    const val DARK_LUX_THRESHOLD = 20f
    /** 완전 암실 기준 (5 lx 이하 → 암실 강도 100%) */
    const val FULL_DARK_LUX_THRESHOLD = 5f
    /** 그림자 오판정 방지: 일상 시간대·비누움 자세의 실질 암실 기준 (8 lx 이하 — 10~20 lx 머리/책상 그림자 차단) */
    const val SHADOW_SAFE_DARK_LUX_DAYTIME = 8f
    /** 그림자 오판정 방지: 취침 전후(±1시간/경과) 또는 누운 자세의 실질 암실 기준 (12 lx 이하) */
    const val SHADOW_SAFE_DARK_LUX_BEDTIME = 12f
    /** 밝은 환경 복귀(히스테리시스 이탈) 기준 (35 lx 이상 감지 시 암실 누적 빠른 감쇠·초기화) */
    const val BRIGHT_EXIT_LUX_THRESHOLD = 35f
    /** 밝은 환경이 이 시간 이상 지속되면 누적 암실 시간을 즉시 0으로 초기화 (4초) */
    const val BRIGHT_RESET_HOLD_MS = 4_000L
    /** 밝은 환경에서 누적 암실 시간을 차감하는 배속 (1초당 4초 차감) */
    const val BRIGHT_DECAY_MULTIPLIER = 4L

    /** 어두운 화면 / 밝은 화면 경계 기준 (0..255 중 75 이하 = 어두운 화면, 76 이상 = 밝은 화면) */
    const val DARK_SCREEN_BRIGHTNESS_THRESHOLD = 75
    /** 취침 준비 시간 범위 (추정/추천/목표 취침 시각 전 60분 이내) */
    const val PRE_BEDTIME_PREP_WINDOW_MINUTES = 60

    // 정규 모드 기준 (연속 사용 시간 / 암실 체류 시간)
    const val POST_BEDTIME_DARK_MIN_USED_MS = 2 * 60_000L
    const val POST_BEDTIME_DARK_MIN_DARK_MS = 60_000L
    const val POST_BEDTIME_LIT_MIN_USED_MS = 3 * 60_000L

    const val DARK_BRIGHT_MIN_USED_MS = 3 * 60_000L
    const val DARK_BRIGHT_MIN_DARK_MS = 90_000L
    const val DARK_BRIGHT_PRE_BEDTIME_MIN_USED_MS = 2 * 60_000L
    const val DARK_BRIGHT_PRE_BEDTIME_MIN_DARK_MS = 60_000L

    const val PRE_BEDTIME_DARK_MIN_USED_MS = 4 * 60_000L
    const val PRE_BEDTIME_DARK_MIN_DARK_MS = 90_000L
    const val PRE_BEDTIME_LIT_MIN_USED_MS = 5 * 60_000L

    const val DARK_DIM_MIN_USED_MS = 6 * 60_000L
    const val DARK_DIM_MIN_DARK_MS = 3 * 60_000L

    // 테스트 모드 기준
    const val TEST_POST_BEDTIME_DARK_MIN_USED_MS = 10_000L
    const val TEST_POST_BEDTIME_DARK_MIN_DARK_MS = 5_000L
    const val TEST_POST_BEDTIME_LIT_MIN_USED_MS = 12_000L

    const val TEST_DARK_BRIGHT_MIN_USED_MS = 12_000L
    const val TEST_DARK_BRIGHT_MIN_DARK_MS = 6_000L

    const val TEST_PRE_BEDTIME_DARK_MIN_USED_MS = 15_000L
    const val TEST_PRE_BEDTIME_DARK_MIN_DARK_MS = 8_000L
    const val TEST_PRE_BEDTIME_LIT_MIN_USED_MS = 18_000L

    const val TEST_DARK_DIM_MIN_USED_MS = 18_000L
    const val TEST_DARK_DIM_MIN_DARK_MS = 10_000L

    // 취침 경과 상황에서 사전 개입(약하게) 표시 후 전면 취침 케어로 승격되기 전 최소 유예 시간
    const val BEDTIME_CARE_GRACE_MS = 2 * 60_000L
    const val TEST_BEDTIME_CARE_GRACE_MS = 15_000L

    /** 단일 조도 기준 호환 헬퍼 (Jev 연속 감점 모델 등과 기준 동기화) */
    fun isDarkRoom(ambientLux: Float?, isOccluded: Boolean): Boolean {
        return !isOccluded && ambientLux != null && ambientLux <= DARK_LUX_THRESHOLD
    }

    /**
     * 그림자 가드(최근 밝은 환경 이력) + 시간대 + 자세를 종합한 실질 암실 판정.
     * - 밝은 실내에서 머리/책상 그림자로 조도가 10~20 lx로 떨어져도 암실로 오판정하지 않습니다.
     * - 테스트 모드에서는 빠른 검증을 위해 기존 20 lx 기준을 유지하되, 그림자 가드가 명확한 경우만 제외합니다.
     */
    fun isEffectiveDarkRoom(
        ambientLux: Float?,
        isOccluded: Boolean,
        isLikelyShadow: Boolean = false,
        isBedtimeWindow: Boolean = false,
        isLyingDown: Boolean = false,
        isTestMode: Boolean = false
    ): Boolean {
        if (isOccluded || ambientLux == null || !ambientLux.isFinite()) return false
        if (isLikelyShadow) return false
        val threshold = when {
            isTestMode -> DARK_LUX_THRESHOLD
            isBedtimeWindow || isLyingDown -> SHADOW_SAFE_DARK_LUX_BEDTIME
            else -> SHADOW_SAFE_DARK_LUX_DAYTIME
        }
        return ambientLux <= threshold
    }

    data class DarkAccumulationStep(
        val darkAccumulatedMs: Long,
        val brightHoldMs: Long
    )

    /**
     * 암실 체류 시간 누적·감쇠·밝기 리셋을 한 번에 계산하는 순수 상태 전이 함수.
     * - 실질 암실 사용 중: `deltaMs` 누적, 밝은 유지 타이머 0 초기화
     * - 밝은 환경(>= 35 lx): 4초 이상 지속 시 즉시 0 리셋, 그 전에는 4배속 감쇠
     * - 그늘/중간 조도/센서 가림 등 비암실: 1배속 완만 감쇠 (간헐적 그림자 누적 방지)
     */
    fun stepDarkAccumulation(
        currentDarkMs: Long,
        currentBrightHoldMs: Long,
        deltaMs: Long,
        isActivelyUsingTargetApp: Boolean,
        isEffectiveDark: Boolean,
        ambientLux: Float?,
        isOccluded: Boolean
    ): DarkAccumulationStep {
        val dt = deltaMs.coerceIn(0L, 5_000L)
        if (dt == 0L) return DarkAccumulationStep(currentDarkMs, currentBrightHoldMs)

        if (isActivelyUsingTargetApp && isEffectiveDark) {
            return DarkAccumulationStep(
                darkAccumulatedMs = currentDarkMs + dt,
                brightHoldMs = 0L
            )
        }

        val isBrightAmbient = !isOccluded && ambientLux != null && ambientLux >= BRIGHT_EXIT_LUX_THRESHOLD
        if (isBrightAmbient) {
            val nextBright = currentBrightHoldMs + dt
            val nextDark = if (nextBright >= BRIGHT_RESET_HOLD_MS) {
                0L
            } else {
                (currentDarkMs - dt * BRIGHT_DECAY_MULTIPLIER).coerceAtLeast(0L)
            }
            return DarkAccumulationStep(
                darkAccumulatedMs = nextDark,
                brightHoldMs = nextBright
            )
        }

        // 그림자 가드 차단 / 중간 조도(9~34 lx) / 비대상 앱: 누적되지 않고 완만히 감쇠
        return DarkAccumulationStep(
            darkAccumulatedMs = (currentDarkMs - dt).coerceAtLeast(0L),
            brightHoldMs = 0L
        )
    }

    fun isBrightScreenInDark(screenBrightness: Int?): Boolean {
        return screenBrightness != null && screenBrightness > DARK_SCREEN_BRIGHTNESS_THRESHOLD
    }

    fun graceWindowMs(isTestMode: Boolean): Long {
        return if (isTestMode) TEST_BEDTIME_CARE_GRACE_MS else BEDTIME_CARE_GRACE_MS
    }

    /**
     * 현재 환경·화면 밝기·취침 시간대·자세·그림자 여부·누적 사용량을 평가해
     * 주 개입 전 사전 개입(약하게)을 띄울지 결정합니다.
     * 한 세션(휴식 전)에서는 최대 1회만 발동합니다.
     */
    fun evaluate(
        ambientLux: Float?,
        isOccluded: Boolean,
        bedtimeDistanceMinutes: Int?,
        isPastBedtime: Boolean,
        isApproachingBedtime: Boolean,
        continuousUsedMs: Long,
        darkAccumulatedMs: Long,
        alreadyTriggeredInSession: Boolean,
        isTestMode: Boolean = false,
        screenBrightness: Int? = null,
        isLikelyShadow: Boolean = false,
        isLyingDown: Boolean = false
    ): PreInterventionDecision? {
        if (alreadyTriggeredInSession) return null
        if (isOccluded) return null

        val maxPostMin = com.example.itv.domain.sleep.SleepWakeCyclePolicy.MAX_POST_BEDTIME_MINUTES
        val postBedtime = isPastBedtime ||
            (bedtimeDistanceMinutes != null && bedtimeDistanceMinutes in 0..maxPostMin)
        val preBedtimePrep = !postBedtime && (
            isApproachingBedtime ||
                (bedtimeDistanceMinutes != null && bedtimeDistanceMinutes in -PRE_BEDTIME_PREP_WINDOW_MINUTES..-1)
            )
        val isDark = isEffectiveDarkRoom(
            ambientLux = ambientLux,
            isOccluded = false,
            isLikelyShadow = isLikelyShadow,
            isBedtimeWindow = postBedtime || preBedtimePrep,
            isLyingDown = isLyingDown,
            isTestMode = isTestMode
        )
        val isBrightScreen = isBrightScreenInDark(screenBrightness)
        val passedMin = if (postBedtime) bedtimeDistanceMinutes?.takeIf { it > 0 } else null
        val remainMin = bedtimeDistanceMinutes?.takeIf { it in -PRE_BEDTIME_PREP_WINDOW_MINUTES..-1 }?.let { -it }

        // 1순위: 추정·추천·목표 취침 시각 이후 (암실이면 더 빠르게, 밝은 방이어도 경고)
        if (postBedtime) {
            val ready = if (isDark) {
                val minUsed = if (isTestMode) TEST_POST_BEDTIME_DARK_MIN_USED_MS else POST_BEDTIME_DARK_MIN_USED_MS
                val minDark = if (isTestMode) TEST_POST_BEDTIME_DARK_MIN_DARK_MS else POST_BEDTIME_DARK_MIN_DARK_MS
                continuousUsedMs >= minUsed && darkAccumulatedMs >= minDark
            } else {
                val minUsed = if (isTestMode) TEST_POST_BEDTIME_LIT_MIN_USED_MS else POST_BEDTIME_LIT_MIN_USED_MS
                continuousUsedMs >= minUsed
            }
            if (ready) {
                return buildPostBedtimeDecision(passedMin = passedMin, isDark = isDark)
            }
        }

        // 2순위: 어두운 곳에서 밝은 화면 사용 (눈부심·망막 피로 급증)
        if (isDark && isBrightScreen) {
            val minUsed = when {
                isTestMode -> TEST_DARK_BRIGHT_MIN_USED_MS
                preBedtimePrep -> DARK_BRIGHT_PRE_BEDTIME_MIN_USED_MS
                else -> DARK_BRIGHT_MIN_USED_MS
            }
            val minDark = when {
                isTestMode -> TEST_DARK_BRIGHT_MIN_DARK_MS
                preBedtimePrep -> DARK_BRIGHT_PRE_BEDTIME_MIN_DARK_MS
                else -> DARK_BRIGHT_MIN_DARK_MS
            }
            if (continuousUsedMs >= minUsed && darkAccumulatedMs >= minDark) {
                return buildDarkBrightScreenDecision(remainBedtimeMin = remainMin)
            }
        }

        // 3순위: 취침 준비 시간 (취침 전 1시간 이내 · 암실/조명 모두 지원)
        if (preBedtimePrep) {
            val ready = if (isDark) {
                val minUsed = if (isTestMode) TEST_PRE_BEDTIME_DARK_MIN_USED_MS else PRE_BEDTIME_DARK_MIN_USED_MS
                val minDark = if (isTestMode) TEST_PRE_BEDTIME_DARK_MIN_DARK_MS else PRE_BEDTIME_DARK_MIN_DARK_MS
                continuousUsedMs >= minUsed && darkAccumulatedMs >= minDark
            } else {
                val minUsed = if (isTestMode) TEST_PRE_BEDTIME_LIT_MIN_USED_MS else PRE_BEDTIME_LIT_MIN_USED_MS
                continuousUsedMs >= minUsed
            }
            if (ready) {
                return buildPreBedtimePrepDecision(remainMin = remainMin, isDark = isDark)
            }
        }

        // 4순위: 어두운 곳에서 어두운 화면 사용 (장시간 암실 시청 피로·수면 지연)
        if (isDark) {
            val minUsed = if (isTestMode) TEST_DARK_DIM_MIN_USED_MS else DARK_DIM_MIN_USED_MS
            val minDark = if (isTestMode) TEST_DARK_DIM_MIN_DARK_MS else DARK_DIM_MIN_DARK_MS
            if (continuousUsedMs >= minUsed && darkAccumulatedMs >= minDark) {
                return buildDarkDimScreenDecision()
            }
        }

        return null
    }

    /**
     * 취침 시간 경과 상황에서 전면 취침 케어가 바로 발동하려 할 때,
     * 아직 약한 사전 개입을 한 번도 주지 않았다면 약한 개입을 먼저 제공해야 하는지 판정합니다.
     */
    fun shouldPrecedeBedtimeCare(
        isPastBedtime: Boolean,
        ambientLux: Float?,
        isOccluded: Boolean,
        alreadyTriggeredInSession: Boolean
    ): Boolean {
        return isPastBedtime && !isOccluded && !alreadyTriggeredInSession &&
            (ambientLux == null || isDarkRoom(ambientLux, isOccluded) || ambientLux >= 0f)
    }

    fun bedtimeCarePrecedingDecision(
        bedtimePassedMinutes: Int? = null,
        isDarkRoom: Boolean = true
    ): PreInterventionDecision = buildPostBedtimeDecision(
        passedMin = bedtimePassedMinutes?.takeIf { it > 0 },
        isDark = isDarkRoom
    )

    private fun buildPostBedtimeDecision(passedMin: Int?, isDark: Boolean): PreInterventionDecision {
        val badges = buildList {
            if (passedMin != null && passedMin > 0) {
                add(ReasonBadge("⏰ 취침 +${passedMin}분", isAlert = true))
            } else {
                add(ReasonBadge("⏰ 취침 시간", isAlert = true))
            }
            if (isDark) {
                add(ReasonBadge("🌑 암실", isAlert = true))
            }
        }
        val reason = when {
            passedMin != null && passedMin > 0 && isDark -> "취침 시각보다 ${passedMin}분 지났고 어두운 방이에요 🌙"
            passedMin != null && passedMin > 0 -> "추정·추천 취침 시각보다 ${passedMin}분 지났어요 🌙"
            isDark -> "취침 시간이 지났고 어두운 방에서 보고 있어요 🌙"
            else -> "추정·추천 취침 시각이 지났어요 🌙"
        }
        return PreInterventionDecision(
            kind = PreInterventionKind.POST_BEDTIME,
            reason = reason,
            bubbleContent = OfferBubbleContent(
                badges = badges,
                headline = "취침 시간이 지났어요 💤",
                subline = "내일 피로가 쌓여요 · 터치해 쉬기"
            )
        )
    }

    private fun buildDarkBrightScreenDecision(remainBedtimeMin: Int?): PreInterventionDecision {
        val badges = buildList {
            add(ReasonBadge("⚠️ 암실·밝은 화면", isAlert = true))
            if (remainBedtimeMin != null) {
                add(ReasonBadge("🛌 취침 ${remainBedtimeMin}분 전", isAlert = true))
            }
        }
        val reason = if (remainBedtimeMin != null) {
            "취침 ${remainBedtimeMin}분 전 어두운 곳에서 밝은 화면을 보고 있어요"
        } else {
            "어두운 곳에서 밝은 화면을 보고 있어 눈이 피로해요"
        }
        return PreInterventionDecision(
            kind = PreInterventionKind.DARK_BRIGHT_SCREEN,
            reason = reason,
            bubbleContent = OfferBubbleContent(
                badges = badges,
                headline = "어두운데 화면이 밝아요 👀",
                subline = "눈 피로가 커져요 · 터치해 쉬기"
            )
        )
    }

    private fun buildPreBedtimePrepDecision(remainMin: Int?, isDark: Boolean): PreInterventionDecision {
        val badges = buildList {
            if (remainMin != null) {
                add(ReasonBadge("🛌 취침 ${remainMin}분 전", isAlert = true))
            } else {
                add(ReasonBadge("🛌 취침 준비", isAlert = true))
            }
            if (isDark) {
                add(ReasonBadge("🌑 암실", isAlert = true))
            }
        }
        val reason = when {
            remainMin != null && isDark -> "취침 ${remainMin}분 전 어두운 환경에서 보고 있어요 🌙"
            remainMin != null -> "취침 준비 시간(취침 ${remainMin}분 전)이에요 🌙"
            isDark -> "취침 준비 시간에 어두운 방에서 보고 있어요 🌙"
            else -> "취침 준비 시간(취침 1시간 전)이에요 🌙"
        }
        return PreInterventionDecision(
            kind = PreInterventionKind.PRE_BEDTIME_PREP,
            reason = reason,
            bubbleContent = OfferBubbleContent(
                badges = badges,
                headline = "곧 잘 시간이에요 🌙",
                subline = "화면 빛이 잠을 깨워요 · 터치해 쉬기"
            )
        )
    }

    private fun buildDarkDimScreenDecision(): PreInterventionDecision {
        return PreInterventionDecision(
            kind = PreInterventionKind.DARK_DIM_SCREEN,
            reason = "어두운 곳에서 어두운 화면을 오래 보고 있어요 🌙",
            bubbleContent = OfferBubbleContent(
                badges = listOf(ReasonBadge("🌙 암실·어두운 화면", isAlert = true)),
                headline = "어두운 곳 시청은 지쳐요 🌙",
                subline = "잠들기 어려워져요 · 터치해 쉬기"
            )
        )
    }
}
