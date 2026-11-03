package com.example.itv.domain.goals

import kotlinx.coroutines.flow.Flow

interface GoalStore {
    fun observeHomeGoals(ownerId: String): Flow<List<HomeGoalRow>>
    suspend fun createLocalOwner(ownerId: String, authUid: String?)
    suspend fun saveSettings(ownerId: String, coachingEnabled: Boolean, snoozeSeconds: Long, intensity: String, displayMode: String)
    suspend fun saveDailyUsageGoal(ownerId: String, seconds: Long, enabled: Boolean)
    suspend fun saveBedtimeGoal(ownerId: String, hour: Int, minute: Int, enabled: Boolean)
    suspend fun saveUsageBreakGoal(ownerId: String, usageSeconds: Long, breakSeconds: Long, metric: String, packageName: String?, enabled: Boolean)
    suspend fun initialSetupCompleted(ownerId: String): Boolean
    suspend fun completeSetup(ownerId: String)
    suspend fun disableGoal(ownerId: String, type: String)
    suspend fun snapshot(ownerId: String): GoalSnapshot
    suspend fun saveSetupAtomically(ownerId: String, writeGoals: suspend () -> Unit)
}
