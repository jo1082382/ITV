package com.example.itv.infrastructure.storage.shortform

/** Shared lifecycle operation; each store retains its own format and responsibility. */
internal object ShortformStores {
    fun pause(): Unit {
        ShortformDailyStore.pause()
        ReelsUsageStore.pause()
    }
}
