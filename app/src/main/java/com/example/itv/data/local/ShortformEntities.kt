package com.example.itv.data.local

import androidx.room.Entity

/** zoneKey=LEGACY_UNKNOWN means the historical timezone was not recorded. */
@Entity(tableName = "shortform_daily_records", primaryKeys = ["ownerId", "packageName", "localDate", "zoneKey", "source"])
data class ShortformDailyRecordEntity(
    val ownerId: String, val packageName: String, val localDate: String,
    val zoneKey: String, val source: String,
    val durationMs: Long, val transitionCount: Long, val observed: Boolean,
    val calculationVersion: String, val updatedAt: Long)

/** Application import receipt, distinct from Room schema migration. */
@Entity(tableName = "local_import_receipts", primaryKeys = ["importKey"])
data class LocalImportReceiptEntity(val importKey: String, val ownerId: String,
    val sourceName: String, val importedRows: Long, val completedAt: Long)
