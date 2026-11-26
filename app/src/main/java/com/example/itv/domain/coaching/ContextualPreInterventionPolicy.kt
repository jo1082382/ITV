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
    /** 전 모듈(조도 트래커·사전 개입·Jev 임계값·말풍선 사유) 공통 암실 조도 기준 (20 lx 이하) */
    const val DARK_LUX_THRESHOLD = 20f
    /** 완전 암실 기준 (5 lx 이하 → 암실 강도 100%) */
    const val FULL_DARK_LUX_THRESHOLD = 5f
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

    fun isDarkRoom(ambientLux: Float?, isOccluded: Boolean): Boolean {
        return !isOccluded && ambientLux != null && ambientLux <= DARK_LUX_THRESHOLD
    }

    fun isBrightScreenInDark(screenBrightness: Int?): Boolean {
        return screenBrightness != null && screenBrightness > DARK_SCREEN_BRIGHTNESS_THRESHOLD
    }

    fun graceWindowMs(isTestMode: Boolean): Long {
        return if (isTestMode) TEST_BEDTIME_CARE_GRACE_MS else BEDTIME_CARE_GRACE_MS
    }

    /**
     * 현재 환경·화면 밝기·취침 시간대·누적 사용량을 평가해
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
        screenBrightness: Int? = null
    ): PreInterventionDecision? {
        if (alreadyTriggeredInSession) return null
        if (isOccluded) return null

        val isDark = isDarkRoom(ambientLux, isOccluded)
        val isBrightScreen = isBrightScreenInDark(screenBrightness)
        val maxPostMin = com.example.itv.domain.sleep.SleepWakeCyclePolicy.MAX_POST_BEDTIME_MINUTES
        val postBedtime = isPastBedtime ||
            (bedtimeDistanceMinutes != null && bedtimeDistanceMinutes in 0..maxPostMin)
        val preBedtimePrep = !postBedtime && (
            isApproachingBedtime ||
                (bedtimeDistanceMinutes != null && bedtimeDistanceMinutes in -PRE_BEDTIME_PREP_WINDOW_MINUTES..-1)
            )
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
