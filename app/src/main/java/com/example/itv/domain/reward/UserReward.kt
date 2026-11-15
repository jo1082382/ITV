package com.example.itv.domain.reward

/**
 * 사용자 캐릭터 성장 및 보상 상태 (레벨, 경험치, 포인트)
 */
data class UserReward(
    val totalExp: Long = 0L,
    val currentLevel: Int = 1,
    val currentLevelExp: Long = 0L,
    val nextLevelExpRequired: Long = 100L,
    val currentPoints: Long = 0L,
    val totalCompletedMissions: Int = 0
) {
    val progressPercent: Float
        get() = if (nextLevelExpRequired > 0) {
            (currentLevelExp.toFloat() / nextLevelExpRequired.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

/**
 * 미션 성공 보상 지급 결과
 */
data class RewardGrantResult(
    val expGained: Long,
    val pointsGained: Long,
    val didLevelUp: Boolean,
    val oldLevel: Int,
    val newLevel: Int,
    val currentLevelExp: Long,
    val nextLevelExpRequired: Long,
    val totalExp: Long,
    val currentPoints: Long
)

/**
 * 레벨 계산기
 * - 1레벨 -> 2레벨: 100 EXP 필요
 * - 레벨이 오를 때마다 필요 경험치 50씩 증가 (100, 150, 200, 250...)
 */
object LevelCalculator {
    fun expForLevel(level: Int): Long = 100L + (level - 1) * 50L

    fun calculate(totalExp: Long, points: Long, totalMissions: Int): UserReward {
        var remaining = maxOf(0L, totalExp)
        var lvl = 1
        while (true) {
            val needed = expForLevel(lvl)
            if (remaining >= needed) {
                remaining -= needed
                lvl++
            } else {
                return UserReward(
                    totalExp = totalExp,
                    currentLevel = lvl,
                    currentLevelExp = remaining,
                    nextLevelExpRequired = needed,
                    currentPoints = points,
                    totalCompletedMissions = totalMissions
                )
            }
        }
    }
}
