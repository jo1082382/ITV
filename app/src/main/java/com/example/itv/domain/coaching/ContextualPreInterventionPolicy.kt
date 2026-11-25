package com.example.itv.domain.coaching

/**
 * 상황별 자동 사전 개입(약하게 · [CharacterIntensity.GENTLE]) 정책:
 * 사용자가 '약하게 / 보통 / 강하게'를 수동으로 고르는 대신,
 * 취침 전·경과 암실 또는 어두운 환경에서 일정 시간 사용 시
 * 화면 구석에서 조용히 등장했다가 6초 후 자동 퇴장하는 [CharacterIntensity.GENTLE]을
 * 본 개입(보통 개입 또는 취침 케어) 이전의 "사전 개입"으로 자동 실행합니다.
 */
enum class PreInterventionKind(val label: String) {
    /** 취침 시각 경과 + 암실: 짧은 사용 후 사전 개입을 먼저 보여주고, 계속 시청 시 취침 케어로 전환 */
    POST_BEDTIME_DARK("취침 경과 암실 사전 개입"),
    /** 취침 임박(전 3시간 이내) + 암실: 취침 전 어두운 환경 사전 개입 */
    PRE_BEDTIME_DARK("취침 전 암실 사전 개입"),
    /** 일상 시간대 어두운 환경: 어두운 곳에서 일정 시간 사용 시 사전 개입 */
    GENERAL_DARK("어두운 환경 사전 개입")
}

data class PreInterventionDecision(
    val kind: PreInterventionKind,
    val reason: String
)

object ContextualPreInterventionPolicy {
    /** 전 모듈(조도 트래커·사전 개입·Jev 임계값·말풍선 사유) 공통 암실 조도 기준 (20 lx 이하) */
    const val DARK_LUX_THRESHOLD = 20f
    /** 완전 암실 기준 (5 lx 이하 → 암실 강도 100%) */
    const val FULL_DARK_LUX_THRESHOLD = 5f

    // 정규 모드 기준 (등가 연속 사용 시간 / 암실 체류 시간)
    const val POST_BEDTIME_DARK_MIN_USED_MS = 3 * 60_000L
    const val POST_BEDTIME_DARK_MIN_DARK_MS = 60_000L

    const val PRE_BEDTIME_DARK_MIN_USED_MS = 5 * 60_000L
    const val PRE_BEDTIME_DARK_MIN_DARK_MS = 2 * 60_000L

    const val GENERAL_DARK_MIN_USED_MS = 8 * 60_000L
    const val GENERAL_DARK_MIN_DARK_MS = 5 * 60_000L

    // 테스트 모드 기준
    const val TEST_POST_BEDTIME_DARK_MIN_USED_MS = 10_000L
    const val TEST_POST_BEDTIME_DARK_MIN_DARK_MS = 5_000L

    const val TEST_PRE_BEDTIME_DARK_MIN_USED_MS = 15_000L
    const val TEST_PRE_BEDTIME_DARK_MIN_DARK_MS = 8_000L

    const val TEST_GENERAL_DARK_MIN_USED_MS = 20_000L
    const val TEST_GENERAL_DARK_MIN_DARK_MS = 12_000L

    // 취침 경과 암실에서 사전 개입(약하게) 표시 후 전면 취침 케어로 승격되기 전 최소 유예 시간
    const val BEDTIME_CARE_GRACE_MS = 2 * 60_000L
    const val TEST_BEDTIME_CARE_GRACE_MS = 15_000L

    fun isDarkRoom(ambientLux: Float?, isOccluded: Boolean): Boolean {
        return !isOccluded && ambientLux != null && ambientLux <= DARK_LUX_THRESHOLD
    }

    fun graceWindowMs(isTestMode: Boolean): Long {
        return if (isTestMode) TEST_BEDTIME_CARE_GRACE_MS else BEDTIME_CARE_GRACE_MS
    }

    /**
     * 현재 환경·시간대·누적 사용량을 평가해 약한 개입(사전 개입)을 띄울지 결정합니다.
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
        isTestMode: Boolean = false
    ): PreInterventionDecision? {
        if (alreadyTriggeredInSession) return null
        if (!isDarkRoom(ambientLux, isOccluded)) return null

        val postBedtime = isPastBedtime || (bedtimeDistanceMinutes != null && bedtimeDistanceMinutes >= 0)
        val preBedtime = !postBedtime && (isApproachingBedtime || (bedtimeDistanceMinutes != null && bedtimeDistanceMinutes in -180..-1))

        return when {
            postBedtime -> {
                val minUsed = if (isTestMode) TEST_POST_BEDTIME_DARK_MIN_USED_MS else POST_BEDTIME_DARK_MIN_USED_MS
                val minDark = if (isTestMode) TEST_POST_BEDTIME_DARK_MIN_DARK_MS else POST_BEDTIME_DARK_MIN_DARK_MS
                if (continuousUsedMs >= minUsed && darkAccumulatedMs >= minDark) {
                    PreInterventionDecision(
                        kind = PreInterventionKind.POST_BEDTIME_DARK,
                        reason = "취침 시간이 지났어요. 어두운 방에서 눈이 피로해지고 있어요 🌙"
                    )
                } else null
            }
            preBedtime -> {
                val minUsed = if (isTestMode) TEST_PRE_BEDTIME_DARK_MIN_USED_MS else PRE_BEDTIME_DARK_MIN_USED_MS
                val minDark = if (isTestMode) TEST_PRE_BEDTIME_DARK_MIN_DARK_MS else PRE_BEDTIME_DARK_MIN_DARK_MS
                if (continuousUsedMs >= minUsed && darkAccumulatedMs >= minDark) {
                    PreInterventionDecision(
                        kind = PreInterventionKind.PRE_BEDTIME_DARK,
                        reason = "취침 전 어두운 환경에서 화면을 보고 있어요 🌙"
                    )
                } else null
            }
            else -> {
                val minUsed = if (isTestMode) TEST_GENERAL_DARK_MIN_USED_MS else GENERAL_DARK_MIN_USED_MS
                val minDark = if (isTestMode) TEST_GENERAL_DARK_MIN_DARK_MS else GENERAL_DARK_MIN_DARK_MS
                if (continuousUsedMs >= minUsed && darkAccumulatedMs >= minDark) {
                    PreInterventionDecision(
                        kind = PreInterventionKind.GENERAL_DARK,
                        reason = "어두운 곳에서 화면을 꽤 오래 보고 있어요 🌙"
                    )
                } else null
            }
        }
    }

    /**
     * 취침 시간 경과 + 암실에서 전면 취침 케어가 바로 발동하려 할 때,
     * 아직 약한 사전 개입을 한 번도 주지 않았다면 약한 개입을 먼저 제공해야 하는지 판정합니다.
     */
    fun shouldPrecedeBedtimeCare(
        isPastBedtime: Boolean,
        ambientLux: Float?,
        isOccluded: Boolean,
        alreadyTriggeredInSession: Boolean
    ): Boolean {
        return isPastBedtime && isDarkRoom(ambientLux, isOccluded) && !alreadyTriggeredInSession
    }

    fun bedtimeCarePrecedingDecision(): PreInterventionDecision {
        return PreInterventionDecision(
            kind = PreInterventionKind.POST_BEDTIME_DARK,
            reason = "취침 시간이 지났어요. 어두운 방에서 눈이 피로해지고 있어요 🌙"
        )
    }
}
