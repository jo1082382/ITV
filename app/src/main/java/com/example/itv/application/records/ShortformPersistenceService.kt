package com.example.itv.application.records

import com.example.itv.domain.records.ShortformPersistence
import com.example.itv.domain.records.StoredShortformDay

/** Called by the serialized legacy accumulator bridge. No Android/Room dependency. */
internal class ShortformPersistenceService(private val store: ShortformPersistence) {
    suspend fun prepare(): List<StoredShortformDay> = store.prepare()
    suspend fun save(records: List<StoredShortformDay>) {
        require(records.all { it.ms >= 0 && it.transitions >= 0 }) { "음수 숏폼 기록" }
        store.save(records)
    }
    suspend fun hours() = store.hours()
    suspend fun saveWithHours(records:List<StoredShortformDay>,hours:List<com.example.itv.domain.records.StoredShortformHour>) {
        require(records.all { it.ms>=0 && it.transitions>=0 } && hours.all { it.ms>=0 && it.hour in 0..23 })
        store.saveWithHours(records,hours)
    }
    suspend fun clear() { store.clear() }
}
