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
    suspend fun clear() { store.clear() }
}
