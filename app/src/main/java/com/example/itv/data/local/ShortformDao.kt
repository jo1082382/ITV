package com.example.itv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ShortformDao {
    @Query("SELECT * FROM shortform_daily_records WHERE ownerId = :ownerId AND localDate = :date ORDER BY packageName, zoneKey, source")
    suspend fun day(ownerId: String, date: String): List<ShortformDailyRecordEntity>
    @Query("SELECT * FROM local_import_receipts WHERE importKey = :key LIMIT 1")
    suspend fun receipt(key: String): LocalImportReceiptEntity?
    @Query("SELECT * FROM shortform_daily_records WHERE ownerId = :ownerId")
    suspend fun all(ownerId: String): List<ShortformDailyRecordEntity>
    @androidx.room.Upsert
    suspend fun saveRecords(records: List<ShortformDailyRecordEntity>)
    @Query("DELETE FROM shortform_daily_records WHERE ownerId = :ownerId")
    suspend fun deleteOwnerRecords(ownerId: String)
    @Query("DELETE FROM shortform_daily_records WHERE ownerId = :ownerId AND localDate < :cutoffDate")
    suspend fun deleteOlderThan(ownerId: String, cutoffDate: String)
    // ABORT is deliberate: an import must not silently replace existing rows.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecords(records: List<ShortformDailyRecordEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: LocalImportReceiptEntity)
}
