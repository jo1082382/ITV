package com.example.itv.domain.records

internal data class StoredShortformDay(val packageName: String, val date: String,
    val zoneKey: String, val source: String, val ms: Long,
    val transitions: Long, val observed: Boolean, val calculationVersion: String)
internal interface ShortformPersistence {
    suspend fun prepare(): List<StoredShortformDay>
    suspend fun save(records: List<StoredShortformDay>)
    suspend fun clear()
}
