package com.example.itv.data.local

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [OwnerEntity::class, UserSettingsEntity::class, UserGoalEntity::class,
    DailyUsageGoalEntity::class, BedtimeGoalEntity::class, UsageBreakGoalEntity::class,
    UsageSessionEntity::class, ObservationIntervalEntity::class, DailyUsageSummaryEntity::class,
    ConsentEventEntity::class, BackupStateEntity::class, SyncOutboxEntity::class,
    ShortformDailyRecordEntity::class, LocalImportReceiptEntity::class, SleepConfirmationEntity::class, ShortformHourEntity::class, InterventionEventEntity::class],
    version = 7, exportSchema = true)
abstract class ItvDatabase : RoomDatabase() {
    abstract fun interventionEventDao(): InterventionEventDao
    abstract fun shortformHourDao(): ShortformHourDao
    abstract fun sleepConfirmationDao(): SleepConfirmationDao
    abstract fun dao(): ItvDao
    abstract fun shortformDao(): ShortformDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_settings ADD COLUMN goalSetupCompleted INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `shortform_daily_records` (`ownerId` TEXT NOT NULL, `packageName` TEXT NOT NULL, `localDate` TEXT NOT NULL, `zoneKey` TEXT NOT NULL, `source` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `transitionCount` INTEGER NOT NULL, `observed` INTEGER NOT NULL, `calculationVersion` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `packageName`, `localDate`, `zoneKey`, `source`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `local_import_receipts` (`importKey` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `sourceName` TEXT NOT NULL, `importedRows` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, PRIMARY KEY(`importKey`))")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sleep_confirmations` (`ownerId` TEXT NOT NULL, `recordDate` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `choice` TEXT NOT NULL, `startMs` INTEGER, `endMs` INTEGER, `origin` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `recordDate`))")
            }
        }
        val MIGRATION_4_5 = object : Migration(4,5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `shortform_hourly_records` (`ownerId` TEXT NOT NULL, `packageName` TEXT NOT NULL, `localDate` TEXT NOT NULL, `hour` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`ownerId`,`packageName`,`localDate`,`hour`,`zoneId`))")
            }
        }
        val MIGRATION_5_6 = object : Migration(5,6) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `intervention_events` (`eventId` TEXT NOT NULL, `decisionId` TEXT NOT NULL, `wallMs` INTEGER NOT NULL, `kind` TEXT NOT NULL, `policyVersion` TEXT NOT NULL, `engine` TEXT NOT NULL, `sessionApp` TEXT NOT NULL, `sessionMs` INTEGER NOT NULL, PRIMARY KEY(`eventId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_intervention_events_decisionId` ON `intervention_events` (`decisionId`)")
            }
        }
        val MIGRATION_6_7 = object : Migration(6,7) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE intervention_events ADD COLUMN instagramTodayMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE intervention_events ADD COLUMN youtubeTodayMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE intervention_events ADD COLUMN previousOfferGapMs INTEGER")
            }
        }
        @Volatile private var instance: ItvDatabase? = null
        fun get(context: Context): ItvDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                ItvDatabase::class.java, "itv.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { instance = it }
        }
    }
}
