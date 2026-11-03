package com.example.itv.application.goals

import com.example.itv.domain.goals.*

/** Validation and use-case entry points. Room controls atomic persistence behind GoalStore. */
class GoalService(private val store: GoalStore) {
    fun observeHomeGoals(ownerId: String) = store.observeHomeGoals(ownerId)
    suspend fun createLocalOwner(ownerId: String, authUid: String? = null) {
        require(ownerId.isNotBlank()); require(authUid == null || authUid.isNotBlank())
        store.createLocalOwner(ownerId, authUid)
    }
    suspend fun saveSettings(ownerId: String, coachingEnabled: Boolean,
        snoozeSeconds: Long = 300, intensity: String = "DEFAULT", displayMode: String = "DEFAULT") {
        require(snoozeSeconds > 0)
        store.saveSettings(ownerId, coachingEnabled, snoozeSeconds, intensity, displayMode)
    }
    suspend fun saveDailyUsageGoal(ownerId: String, seconds: Long, enabled: Boolean = true) {
        require(seconds >= 0); store.saveDailyUsageGoal(ownerId, seconds, enabled)
    }
    suspend fun saveBedtimeGoal(ownerId: String, hour: Int, minute: Int, enabled: Boolean = true) {
        require(hour in 0..23 && minute in 0..59); store.saveBedtimeGoal(ownerId, hour, minute, enabled)
    }
    suspend fun saveUsageBreakGoal(ownerId: String, usageSeconds: Long, breakSeconds: Long,
        metric: String = "CONFIRMED_SHORTFORM", packageName: String? = "com.instagram.android", enabled: Boolean = true) {
        require(usageSeconds > 0 && breakSeconds > 0)
        require(metric in setOf("PHONE_USAGE", "TARGET_APP_USAGE", "CONFIRMED_SHORTFORM"))
        require(if (metric == "PHONE_USAGE") packageName == null else !packageName.isNullOrBlank())
        store.saveUsageBreakGoal(ownerId, usageSeconds, breakSeconds, metric, packageName, enabled)
    }
    suspend fun initialSetupCompleted(ownerId: String) = store.initialSetupCompleted(ownerId)
    suspend fun completeSetup(ownerId: String) = store.completeSetup(ownerId)
    suspend fun disableGoal(ownerId: String, type: String) = store.disableGoal(ownerId, type)
    suspend fun snapshot(ownerId: String) = store.snapshot(ownerId)
    suspend fun saveSetupAtomically(ownerId: String, writeGoals: suspend () -> Unit) =
        store.saveSetupAtomically(ownerId, writeGoals)
}
