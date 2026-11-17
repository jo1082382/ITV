package com.example.itv.infrastructure.goals

import androidx.room.withTransaction
import com.example.itv.data.local.*
import com.example.itv.domain.goals.GoalStore
import com.example.itv.domain.goals.GoalSnapshot
import com.example.itv.domain.goals.GoalValue
import com.example.itv.domain.goals.DailyGoalValue
import com.example.itv.domain.goals.BedtimeGoalValue
import com.example.itv.domain.goals.BreakGoalValue
import java.util.UUID

class RoomGoalStore(private val db: ItvDatabase) : GoalStore {
    private val dao = db.dao()

    override fun observeHomeGoals(ownerId: String) = dao.observeHomeGoals(ownerId)

    // Call only after authentication/account-switch orchestration validates ownership.
    // Guest is ready locally; accounts must first finish server backup lookup/restore.
    override suspend fun createLocalOwner(ownerId: String, authUid: String?) {
        require(ownerId.isNotBlank())
        require(authUid == null || authUid.isNotBlank())
        db.withTransaction {
            val previous = dao.owner(ownerId)
            if (previous != null) {
                if (authUid != null && previous.authUid != null) {
                    require(previous.authUid == authUid) { "Owner/account mismatch" }
                }
            } else {
                dao.insertOwner(OwnerEntity(ownerId, if (authUid == null) "GUEST" else "ACCOUNT",
                    authUid, System.currentTimeMillis()))
                dao.saveBackupState(BackupStateEntity(ownerId, "READY"))
            }
        }
    }

    private suspend fun assertEditable(ownerId: String) {
        val owner = requireNotNull(dao.owner(ownerId)) { "Unknown owner" }
        if (owner.ownerType == "ACCOUNT") {
            val state = dao.backupState(ownerId)?.restoreState
            if (state != "READY") {
                dao.saveBackupState(BackupStateEntity(ownerId, "READY"))
            }
        }
    }

    private suspend fun queue(ownerId: String, type: String, id: String, revision: Long) {
        if (dao.owner(ownerId)?.ownerType == "ACCOUNT") {
            dao.enqueue(SyncOutboxEntity(UUID.randomUUID().toString(), ownerId, type, id,
                "UPSERT", revision, createdAt = System.currentTimeMillis()))
        }
    }

    override suspend fun saveSettings(ownerId: String, coachingEnabled: Boolean,
        snoozeSeconds: Long, intensity: String, displayMode: String) {
        require(snoozeSeconds > 0)
        db.withTransaction {
            assertEditable(ownerId)
            val revision = (dao.settings(ownerId)?.localRevision ?: 0) + 1
            dao.saveSettings(UserSettingsEntity(ownerId, coachingEnabled, intensity, displayMode,
                snoozeSeconds, System.currentTimeMillis(), revision,
                dao.settings(ownerId)?.goalSetupCompleted ?: false))
            queue(ownerId, "SETTINGS", ownerId, revision)
        }
    }

    private suspend fun saveGoal(ownerId: String, type: String, enabled: Boolean,
        details: suspend (String) -> Unit) {
        db.withTransaction {
            assertEditable(ownerId)
            val now = System.currentTimeMillis()
            val old = dao.goal(ownerId, type)
            val goal = UserGoalEntity(old?.goalId ?: UUID.randomUUID().toString(), ownerId,
                type, enabled, old?.createdAt ?: now, now, (old?.localRevision ?: 0) + 1)
            dao.saveGoal(goal)
            details(goal.goalId)
            queue(ownerId, "GOAL", goal.goalId, goal.localRevision)
        }
    }

    override suspend fun saveDailyUsageGoal(ownerId: String, seconds: Long, enabled: Boolean) {
        require(seconds >= 0)
        saveGoal(ownerId, "DAILY_PHONE_USAGE", enabled) { dao.saveDailyGoal(DailyUsageGoalEntity(it, seconds)) }
    }
    override suspend fun saveBedtimeGoal(ownerId: String, hour: Int, minute: Int, enabled: Boolean) {
        require(hour in 0..23 && minute in 0..59)
        saveGoal(ownerId, "BEDTIME", enabled) { dao.saveBedtimeGoal(BedtimeGoalEntity(it, hour * 60 + minute)) }
    }
    override suspend fun saveUsageBreakGoal(ownerId: String, usageSeconds: Long, breakSeconds: Long,
        metric: String, packageName: String?,
        enabled: Boolean) {
        require(usageSeconds > 0 && breakSeconds > 0)
        require(metric in setOf("PHONE_USAGE", "TARGET_APP_USAGE", "CONFIRMED_SHORTFORM"))
        require(if (metric == "PHONE_USAGE") packageName == null else !packageName.isNullOrBlank())
        saveGoal(ownerId, "USAGE_BREAK", enabled) {
            dao.saveBreakGoal(UsageBreakGoalEntity(it, usageSeconds, breakSeconds, metric, packageName))
        }
    }
    override suspend fun initialSetupCompleted(ownerId: String): Boolean =
        dao.settings(ownerId)?.goalSetupCompleted == true

    override suspend fun completeSetup(ownerId: String) {
        db.withTransaction {
            assertEditable(ownerId)
            val now = System.currentTimeMillis()
            val old = dao.settings(ownerId) ?: UserSettingsEntity(ownerId, updatedAt = now)
            val next = old.copy(goalSetupCompleted = true, updatedAt = now,
                localRevision = old.localRevision + 1)
            dao.saveSettings(next)
            queue(ownerId, "SETTINGS", ownerId, next.localRevision)
        }
    }

    override suspend fun disableGoal(ownerId: String, type: String) {
        db.withTransaction {
            assertEditable(ownerId)
            val old = dao.goal(ownerId, type)
            if (old != null && old.enabled) {
                val next = old.copy(enabled = false, updatedAt = System.currentTimeMillis(),
                    localRevision = old.localRevision + 1)
                dao.saveGoal(next)
                queue(ownerId, "GOAL", next.goalId, next.localRevision)
            }
        }
    }

    override suspend fun snapshot(ownerId: String): GoalSnapshot = db.withTransaction {
        requireNotNull(dao.owner(ownerId))
        val daily = dao.goal(ownerId, "DAILY_PHONE_USAGE")?.let { goal ->
            val detail = requireNotNull(dao.dailyGoal(goal.goalId)) { "하루 목표 상세 정보가 없습니다." }
            GoalValue(goal.enabled) to DailyGoalValue(detail.dailyLimitSeconds)
        }
        val bedtime = dao.goal(ownerId, "BEDTIME")?.let { goal ->
            val detail = requireNotNull(dao.bedtimeGoal(goal.goalId)) { "취침 목표 상세 정보가 없습니다." }
            GoalValue(goal.enabled) to BedtimeGoalValue(detail.localMinuteOfDay)
        }
        val usageBreak = dao.goal(ownerId, "USAGE_BREAK")?.let { goal ->
            val detail = requireNotNull(dao.breakGoal(goal.goalId)) { "휴식 목표 상세 정보가 없습니다." }
            GoalValue(goal.enabled) to BreakGoalValue(detail.usageThresholdSeconds,
                detail.breakDurationSeconds, detail.usageMetric, detail.scopePackageName, detail.accumulationPolicy)
        }
        GoalSnapshot(daily, bedtime, usageBreak)
    }

    override suspend fun saveSetupAtomically(ownerId: String, writeGoals: suspend () -> Unit) {
        db.withTransaction {
            assertEditable(ownerId)
            writeGoals()
            completeSetup(ownerId)
        }
    }

}
