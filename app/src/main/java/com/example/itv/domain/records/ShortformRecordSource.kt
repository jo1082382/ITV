package com.example.itv.domain.records

/** Confirmed shortform aggregate; never add OS app usage or legacy reel totals. */
data class ShortformDay(val ms: Long = 0, val transitions: Int = 0, val observed: Boolean = false)
data class ShortformToday(val instagram: ShortformDay = ShortformDay(),
    val youtube: ShortformDay = ShortformDay(), val notice: String? = null, val ready: Boolean = false, val storage: ShortformStorageStatus = ShortformStorageStatus())
interface ShortformRecordSource {
    suspend fun deleteAll()
    suspend fun today(): ShortformToday
}
