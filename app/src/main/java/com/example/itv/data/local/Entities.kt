package com.example.itv.data.local

import androidx.room.*

// Times are UTC epoch milliseconds; durations carry units in their names.
@Entity(tableName = "owners", indices = [Index(value = ["authUid"], unique = true)])
data class OwnerEntity(@PrimaryKey val ownerId: String, val ownerType: String,
    val authUid: String? = null, val createdAt: Long)

@Entity(tableName = "user_settings", foreignKeys = [ForeignKey(entity = OwnerEntity::class,
    parentColumns = ["ownerId"], childColumns = ["ownerId"], onDelete = ForeignKey.RESTRICT)])
data class UserSettingsEntity(@PrimaryKey val ownerId: String,
    val coachingEnabled: Boolean = false, val coachingIntensity: String = "DEFAULT",
    val displayMode: String = "DEFAULT", val snoozeSeconds: Long = 300,
    val updatedAt: Long, val localRevision: Long = 1,
    @ColumnInfo(defaultValue = "0") val goalSetupCompleted: Boolean = false)

@Entity(tableName = "user_goals", indices = [Index(value = ["ownerId", "goalType"], unique = true)],
    foreignKeys = [ForeignKey(entity = OwnerEntity::class, parentColumns = ["ownerId"],
        childColumns = ["ownerId"], onDelete = ForeignKey.RESTRICT)])
data class UserGoalEntity(@PrimaryKey val goalId: String, val ownerId: String,
    val goalType: String, val enabled: Boolean, val createdAt: Long,
    val updatedAt: Long, val localRevision: Long)

@Entity(tableName = "daily_usage_goal_details", foreignKeys = [ForeignKey(
    entity = UserGoalEntity::class, parentColumns = ["goalId"], childColumns = ["goalId"],
    onDelete = ForeignKey.CASCADE)])
data class DailyUsageGoalEntity(@PrimaryKey val goalId: String, val dailyLimitSeconds: Long)

@Entity(tableName = "bedtime_goal_details", foreignKeys = [ForeignKey(
    entity = UserGoalEntity::class, parentColumns = ["goalId"], childColumns = ["goalId"],
    onDelete = ForeignKey.CASCADE)])
data class BedtimeGoalEntity(@PrimaryKey val goalId: String, val localMinuteOfDay: Int)

@Entity(tableName = "usage_break_goal_details", foreignKeys = [ForeignKey(
    entity = UserGoalEntity::class, parentColumns = ["goalId"], childColumns = ["goalId"],
    onDelete = ForeignKey.CASCADE)])
data class UsageBreakGoalEntity(@PrimaryKey val goalId: String, val usageThresholdSeconds: Long,
    val breakDurationSeconds: Long, val usageMetric: String,
    val scopePackageName: String?, val accumulationPolicy: String = "CURRENT_SESSION")

@Entity(tableName = "usage_sessions", primaryKeys = ["ownerId", "sessionId"],
    indices = [Index(value = ["ownerId", "startedAt"])],
    foreignKeys = [ForeignKey(entity = OwnerEntity::class, parentColumns = ["ownerId"],
        childColumns = ["ownerId"], onDelete = ForeignKey.RESTRICT)])
data class UsageSessionEntity(val ownerId: String, val sessionId: String,
    val packageName: String, val startedAt: Long, val endedAt: Long?,
    val status: String, val quality: String, val updatedAt: Long)

@Entity(tableName = "observation_intervals", primaryKeys = ["ownerId", "observationId"],
    indices = [Index(value = ["ownerId", "startedAt"]), Index(value = ["ownerId", "sessionId"])],
    foreignKeys = [ForeignKey(entity = OwnerEntity::class, parentColumns = ["ownerId"],
        childColumns = ["ownerId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = UsageSessionEntity::class, parentColumns = ["ownerId", "sessionId"],
        childColumns = ["ownerId", "sessionId"], onDelete = ForeignKey.RESTRICT)])
data class ObservationIntervalEntity(val ownerId: String, val observationId: String,
    val sessionId: String?, val packageName: String, val startedAt: Long, val endedAt: Long,
    val activityType: String, val quality: String, val sourceType: String,
    val sourceRecordKey: String?, val ruleVersion: String, val updatedAt: Long)

@Entity(tableName = "daily_usage_summaries", primaryKeys = ["ownerId", "localDate", "zoneId", "metricType", "scopeKey"],
    foreignKeys = [ForeignKey(entity = OwnerEntity::class, parentColumns = ["ownerId"],
        childColumns = ["ownerId"], onDelete = ForeignKey.RESTRICT)])
data class DailyUsageSummaryEntity(val ownerId: String, val localDate: String, val zoneId: String,
    val metricType: String, val scopeKey: String, val usageSeconds: Long?,
    val quality: String, val calculationVersion: String, val updatedAt: Long)

@Entity(tableName = "consent_events", indices = [Index(value = ["ownerId", "occurredAt"])],
    foreignKeys = [ForeignKey(entity = OwnerEntity::class, parentColumns = ["ownerId"],
        childColumns = ["ownerId"], onDelete = ForeignKey.RESTRICT)])
data class ConsentEventEntity(@PrimaryKey val eventId: String, val ownerId: String,
    val consentType: String, val noticeVersion: String, val action: String,
    val occurredAt: Long, val origin: String)

@Entity(tableName = "backup_state", foreignKeys = [ForeignKey(entity = OwnerEntity::class,
    parentColumns = ["ownerId"], childColumns = ["ownerId"], onDelete = ForeignKey.RESTRICT)])
data class BackupStateEntity(@PrimaryKey val ownerId: String,
    val restoreState: String = "NOT_CHECKED", val schemaVersion: Int = 1,
    val lastBackupAt: Long? = null, val lastRestoreAt: Long? = null)

@Entity(tableName = "sync_outbox", indices = [Index(value = ["ownerId", "state"])],
    foreignKeys = [ForeignKey(entity = OwnerEntity::class, parentColumns = ["ownerId"],
        childColumns = ["ownerId"], onDelete = ForeignKey.RESTRICT)])
data class SyncOutboxEntity(@PrimaryKey val operationId: String, val ownerId: String,
    val entityType: String, val entityId: String, val operationType: String,
    val entityRevision: Long, val state: String = "PENDING", val retryCount: Int = 0,
    val createdAt: Long)
