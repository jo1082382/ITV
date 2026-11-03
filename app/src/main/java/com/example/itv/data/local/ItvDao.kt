package com.example.itv.data.local
import com.example.itv.domain.goals.HomeGoalRow


import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ItvDao {
    @Query("""
        SELECT g.goalType, g.enabled,
               d.dailyLimitSeconds, b.localMinuteOfDay,
               r.usageThresholdSeconds, r.breakDurationSeconds,
               r.usageMetric, r.scopePackageName, r.accumulationPolicy
        FROM user_goals AS g
        LEFT JOIN daily_usage_goal_details AS d ON d.goalId = g.goalId
        LEFT JOIN bedtime_goal_details AS b ON b.goalId = g.goalId
        LEFT JOIN usage_break_goal_details AS r ON r.goalId = g.goalId
        WHERE g.ownerId = :ownerId
        ORDER BY g.goalType
    """)
    fun observeHomeGoals(ownerId: String): Flow<List<HomeGoalRow>>


    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOwner(owner: OwnerEntity): Long
    @Query("SELECT * FROM owners WHERE ownerId = :ownerId")
    suspend fun owner(ownerId: String): OwnerEntity?
    @Upsert suspend fun saveSettings(value: UserSettingsEntity)
    @Query("SELECT * FROM user_settings WHERE ownerId = :ownerId")
    suspend fun settings(ownerId: String): UserSettingsEntity?
    @Query("SELECT * FROM user_settings WHERE ownerId = :ownerId")
    fun observeSettings(ownerId: String): Flow<UserSettingsEntity?>
    @Query("SELECT * FROM user_goals WHERE ownerId = :ownerId AND goalType = :type LIMIT 1")
    suspend fun goal(ownerId: String, type: String): UserGoalEntity?
    @Query("SELECT * FROM user_goals WHERE ownerId = :ownerId ORDER BY goalType")
    fun observeGoals(ownerId: String): Flow<List<UserGoalEntity>>
    @Upsert suspend fun saveGoal(value: UserGoalEntity)
    @Upsert suspend fun saveDailyGoal(value: DailyUsageGoalEntity)
    @Upsert suspend fun saveBedtimeGoal(value: BedtimeGoalEntity)
    @Upsert suspend fun saveBreakGoal(value: UsageBreakGoalEntity)
    @Query("SELECT * FROM daily_usage_goal_details WHERE goalId = :id")
    suspend fun dailyGoal(id: String): DailyUsageGoalEntity?
    @Query("SELECT * FROM bedtime_goal_details WHERE goalId = :id")
    suspend fun bedtimeGoal(id: String): BedtimeGoalEntity?
    @Query("SELECT * FROM usage_break_goal_details WHERE goalId = :id")
    suspend fun breakGoal(id: String): UsageBreakGoalEntity?
    @Upsert suspend fun saveBackupState(value: BackupStateEntity)
    @Query("SELECT * FROM backup_state WHERE ownerId = :ownerId")
    suspend fun backupState(ownerId: String): BackupStateEntity?
    @Insert suspend fun enqueue(value: SyncOutboxEntity)
    @Query("SELECT * FROM sync_outbox WHERE ownerId = :ownerId AND state = 'PENDING' ORDER BY createdAt LIMIT :limit")
    suspend fun pending(ownerId: String, limit: Int = 100): List<SyncOutboxEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(value: UsageSessionEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertObservation(value: ObservationIntervalEntity)
    @Query("SELECT * FROM observation_intervals WHERE ownerId = :ownerId AND packageName = :pkg AND startedAt < :end AND endedAt > :start ORDER BY startedAt")
    suspend fun overlapping(ownerId: String, pkg: String, start: Long, end: Long): List<ObservationIntervalEntity>
    @Query("SELECT * FROM observation_intervals WHERE ownerId = :ownerId AND startedAt < :toExclusive AND endedAt > :fromInclusive ORDER BY startedAt DESC, observationId LIMIT :limit OFFSET :offset")
    suspend fun observations(ownerId: String, fromInclusive: Long, toExclusive: Long,
        limit: Int = 100, offset: Int = 0): List<ObservationIntervalEntity>
    @Query("SELECT * FROM daily_usage_summaries WHERE ownerId = :ownerId AND zoneId = :zone AND localDate >= :fromDate AND localDate <= :toDate ORDER BY localDate")
    fun observeSummaries(ownerId: String, zone: String, fromDate: String, toDate: String): Flow<List<DailyUsageSummaryEntity>>
}
