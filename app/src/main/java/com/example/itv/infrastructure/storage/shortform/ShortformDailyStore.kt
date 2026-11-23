package com.example.itv.infrastructure.storage.shortform

import android.content.Context
import com.example.itv.application.records.ShortformRecordingCoordinator
import com.example.itv.domain.records.*
import kotlinx.coroutines.withTimeoutOrNull

/** Android compatibility adapter. No accumulated records, queue or retry policy here. */
internal object ShortformDailyStore {
    data class Day(val ms:Long=0,val transitions:Int=0,val observed:Boolean=false)
    @Volatile private var coordinator: ShortformRecordingCoordinator? = null
    private fun instance(c:Context): ShortformRecordingCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: com.example.itv.composition.shortformRecordingCoordinator(c).also { coordinator=it }
    }
    private fun time() = ShortformObservationTime(android.os.SystemClock.elapsedRealtime(),
        System.currentTimeMillis(), java.time.ZoneId.systemDefault().id)
    fun observe(c:Context,app:String,valid:Boolean,time:ShortformObservationTime=time()) { instance(c).observe(app,valid,time) }
    fun transition(c:Context,app:String,time:ShortformObservationTime=time()) { instance(c).transition(app,time) }
    fun today(c:Context,app:String):Day {
        val d=instance(c).today(app);return Day(d.ms,d.transitions,d.observed)
    }
    fun recentSessionSnapshot() = coordinator?.recentSessionSnapshot(android.os.SystemClock.elapsedRealtime()) ?: ShortformSessionSnapshot()
    fun sessionSnapshot() = coordinator?.sessionSnapshot(android.os.SystemClock.elapsedRealtime())
        ?: ShortformSessionSnapshot()
    fun isReady()=coordinator?.isReady() ?: false
    fun storageStatus()=coordinator?.storageStatus() ?: ShortformStorageStatus()
    fun pause() { coordinator?.pause() }
    suspend fun deleteAllAndAwait(c:Context) {
        instance(c).deleteAllAndAwait()
        ShortformPersonalStatsStore.clear(c)
    }
    suspend fun resetForAccountDeletion(c: Context) {
        val existing = synchronized(this) {
            coordinator.also { coordinator = null }
        }
        try {
            runCatching { existing?.pause() }
            withTimeoutOrNull(2_000L) {
                runCatching { existing?.deleteAllAndAwait() }
            }
        } finally {
            runCatching { existing?.close() }
        }
        ShortformPersonalStatsStore.deleteAllAndReset(c)
    }
    fun reloadAfterCloudRestore() {
        val existing = synchronized(this) {
            coordinator.also { coordinator = null }
        }
        runCatching { existing?.close() }
    }
    fun clear(c:Context) {
        instance(c).clear()
        ShortformPersonalStatsStore.clear(c)
    }
}
