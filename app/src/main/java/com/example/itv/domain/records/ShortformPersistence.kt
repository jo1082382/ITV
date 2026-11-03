package com.example.itv.domain.records

internal data class StoredShortformDay(val packageName: String, val date: String,
    val zoneKey: String, val source: String, val ms: Long,
    val transitions: Long, val observed: Boolean, val calculationVersion: String)
internal interface ShortformPersistence {
    suspend fun prepare(): List<StoredShortformDay>
    suspend fun save(records: List<StoredShortformDay>)
    suspend fun hours(): List<StoredShortformHour> = emptyList()
    suspend fun saveWithHours(records:List<StoredShortformDay>,hours:List<StoredShortformHour>) {
        check(hours.isEmpty()) { "시간대 저장소 구현 필요" }; save(records)
    }
    suspend fun clear()
}
