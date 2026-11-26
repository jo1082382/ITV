package com.example.itv.infrastructure.reward

import android.content.Context
import android.content.SharedPreferences
import com.example.itv.domain.reward.LevelCalculator
import com.example.itv.domain.reward.RewardGrantResult
import com.example.itv.domain.reward.UserReward
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object UserRewardStore {
    private const val PREF_NAME = "itv_user_reward_store_v1"
    private const val KEY_TOTAL_EXP = "key_total_exp"
    private const val KEY_POINTS = "key_points"
    private const val KEY_TOTAL_MISSIONS = "key_total_missions"

    private val _rewardState = MutableStateFlow<UserReward?>(null)
    val rewardState: StateFlow<UserReward?> = _rewardState.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 현재 유저의 레벨 및 보상 상태를 조회합니다.
     */
    fun getReward(context: Context): UserReward {
        val p = prefs(context)
        val totalExp = p.getLong(KEY_TOTAL_EXP, 0L)
        val points = p.getLong(KEY_POINTS, 0L)
        val totalMissions = p.getInt(KEY_TOTAL_MISSIONS, 0)
        val current = LevelCalculator.calculate(totalExp, points, totalMissions)
        _rewardState.value = current
        return current
    }

    /**
     * 미션 성공 시 보상을 지급하고 레벨업 여부를 반환합니다.
     * @param isStrong 강한 개입 (화면 엎어두기 미션) 여부 -> 추가 보상 지급
     */
    fun grantMissionReward(context: Context, isStrong: Boolean): RewardGrantResult {
        val p = prefs(context)
        val oldExp = p.getLong(KEY_TOTAL_EXP, 0L)
        val oldPoints = p.getLong(KEY_POINTS, 0L)
        val oldMissions = p.getInt(KEY_TOTAL_MISSIONS, 0)
        val oldReward = LevelCalculator.calculate(oldExp, oldPoints, oldMissions)

        val expGain = if (isStrong) 50L else 30L
        val pointGain = if (isStrong) 100L else 50L

        val newExp = oldExp + expGain
        val newPoints = oldPoints + pointGain
        val newMissions = oldMissions + 1

        p.edit()
            .putLong(KEY_TOTAL_EXP, newExp)
            .putLong(KEY_POINTS, newPoints)
            .putInt(KEY_TOTAL_MISSIONS, newMissions)
            .apply()

        val newReward = LevelCalculator.calculate(newExp, newPoints, newMissions)
        _rewardState.value = newReward

        val didLevelUp = newReward.currentLevel > oldReward.currentLevel
        return RewardGrantResult(
            expGained = expGain,
            pointsGained = pointGain,
            didLevelUp = didLevelUp,
            oldLevel = oldReward.currentLevel,
            newLevel = newReward.currentLevel,
            currentLevelExp = newReward.currentLevelExp,
            nextLevelExpRequired = newReward.nextLevelExpRequired,
            totalExp = newExp,
            currentPoints = newPoints
        )
    }

    /**
     * 월간 보물상자 등 추가 보상 지급 (완료 미션 횟수는 변경하지 않고 경험치·포인트만 적립합니다).
     */
    fun grantBonusReward(context: Context, expGain: Long, pointGain: Long): RewardGrantResult {
        val p = prefs(context)
        val oldExp = p.getLong(KEY_TOTAL_EXP, 0L)
        val oldPoints = p.getLong(KEY_POINTS, 0L)
        val missions = p.getInt(KEY_TOTAL_MISSIONS, 0)
        val oldReward = LevelCalculator.calculate(oldExp, oldPoints, missions)

        val safeExp = expGain.coerceAtLeast(0L)
        val safePoints = pointGain.coerceAtLeast(0L)
        val newExp = oldExp + safeExp
        val newPoints = oldPoints + safePoints

        p.edit()
            .putLong(KEY_TOTAL_EXP, newExp)
            .putLong(KEY_POINTS, newPoints)
            .apply()

        val newReward = LevelCalculator.calculate(newExp, newPoints, missions)
        _rewardState.value = newReward

        return RewardGrantResult(
            expGained = safeExp,
            pointsGained = safePoints,
            didLevelUp = newReward.currentLevel > oldReward.currentLevel,
            oldLevel = oldReward.currentLevel,
            newLevel = newReward.currentLevel,
            currentLevelExp = newReward.currentLevelExp,
            nextLevelExpRequired = newReward.nextLevelExpRequired,
            totalExp = newExp,
            currentPoints = newPoints
        )
    }

    /**
     * 스트릭 보호권 구매 등에 포인트를 차감합니다. 보유 포인트가 부족하면 false를 반환합니다.
     */
    fun spendPoints(context: Context, cost: Long): Boolean {
        if (cost <= 0L) return true
        val p = prefs(context)
        val currentExp = p.getLong(KEY_TOTAL_EXP, 0L)
        val currentPoints = p.getLong(KEY_POINTS, 0L)
        val missions = p.getInt(KEY_TOTAL_MISSIONS, 0)
        if (currentPoints < cost) return false

        val nextPoints = (currentPoints - cost).coerceAtLeast(0L)
        p.edit()
            .putLong(KEY_POINTS, nextPoints)
            .apply()

        _rewardState.value = LevelCalculator.calculate(currentExp, nextPoints, missions)
        return true
    }

    fun restoreState(
        context: Context,
        totalExp: Long,
        points: Long,
        totalMissions: Int,
        mergeWithLocal: Boolean
    ) {
        val p = prefs(context)
        val nextExp = if (mergeWithLocal) maxOf(p.getLong(KEY_TOTAL_EXP, 0L), totalExp) else totalExp
        val nextPoints = if (mergeWithLocal) maxOf(p.getLong(KEY_POINTS, 0L), points) else points
        val nextMissions = if (mergeWithLocal) maxOf(p.getInt(KEY_TOTAL_MISSIONS, 0), totalMissions) else totalMissions
        p.edit()
            .putLong(KEY_TOTAL_EXP, nextExp.coerceAtLeast(0L))
            .putLong(KEY_POINTS, nextPoints.coerceAtLeast(0L))
            .putInt(KEY_TOTAL_MISSIONS, nextMissions.coerceAtLeast(0))
            .commit()
        _rewardState.value = LevelCalculator.calculate(
            nextExp.coerceAtLeast(0L),
            nextPoints.coerceAtLeast(0L),
            nextMissions.coerceAtLeast(0)
        )
    }

    /**
     * 테스트용 초기화
     */
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
        _rewardState.value = LevelCalculator.calculate(0L, 0L, 0)
    }
}
