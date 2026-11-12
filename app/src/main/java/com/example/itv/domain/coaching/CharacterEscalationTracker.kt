package com.example.itv.domain.coaching

/**
 * 캐릭터 개입 강도 자동 스케일업(Dynamic Escalation) 정책:
 * - 사용자가 제안에 대해 '나중에'를 선택하거나 '무응답(타임아웃)'이 연속 2회 이상 발생하면
 *   기본 설정과 관계없이 캐릭터 개입 강도를 자동으로 [CharacterIntensity.STRONG]으로 격상합니다.
 * - 사용자가 화면을 끄고 휴식을 취하면 저항 횟수가 리셋되어 원래 기본 설정 강도로 복귀합니다.
 * - 스케일다운(보통 -> 약하게)은 수행하지 않습니다.
 */
class CharacterEscalationTracker(
    private val threshold: Int = 2
) {
    var consecutiveResistanceCount: Int = 0
        private set

    val isEscalated: Boolean
        get() = consecutiveResistanceCount >= threshold

    /**
     * '나중에' 선택 또는 '무응답' 발생 시 호출하여 저항 횟수를 누적합니다.
     */
    fun recordResistance() {
        consecutiveResistanceCount++
    }

    /**
     * 화면 끄기(휴식 시작) 또는 휴식 시 저항 횟수를 초기화합니다.
     */
    fun reset() {
        consecutiveResistanceCount = 0
    }

    /**
     * 현재 저항 횟수에 따른 유효 캐릭터 강도를 계산합니다.
     * 연속 저항이 [threshold](기본 2회) 이상이면 무조건 STRONG으로 격상되며,
     * 그렇지 않으면 [baseIntensity]를 그대로 반환합니다.
     */
    fun resolveEffectiveIntensity(baseIntensity: CharacterIntensity): CharacterIntensity {
        return if (isEscalated) {
            CharacterIntensity.STRONG
        } else {
            baseIntensity
        }
    }
}
