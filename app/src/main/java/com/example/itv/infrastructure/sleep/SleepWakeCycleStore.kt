package com.example.itv.infrastructure.sleep

import android.content.Context
import com.example.itv.domain.sleep.SleepWakeCyclePolicy
import com.example.itv.infrastructure.storage.CalendarHealthLoader
import kotlin.math.abs

/**
 * Persists the most recently completed sleep cycle (start/end wall-clock timestamps)
 * with a zero-I/O in-memory volatile cache so background trackers, JevRuntime, and
 * CoachingMonitorService can immediately suppress stale bedtime interventions after wake-up
 * even across process restarts.
 */
object SleepWakeCycleStore {

    private const val PREF_NAME = "itv_sleep_wake_cycle_v1"
    private const val KEY_SLEEP_START_MS = "last_sleep_start_ms"
    private const val KEY_SLEEP_END_MS = "last_sleep_end_ms"
    private const val KEY_SOURCE = "last_sleep_source"

    data class LastSleepCycle(
        val sleepStartWallMs: Long,
        val sleepEndWallMs: Long,
        val source: String
    ) {
        val durationMs: Long get() = (sleepEndWallMs - sleepStartWallMs).coerceAtLeast(0L)
    }

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var cached: LastSleepCycle? = null

    private fun ensureLoaded(context: Context): LastSleepCycle? {
        if (loaded) return cached
        synchronized(this) {
            if (loaded) return cached
            val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val start = prefs.getLong(KEY_SLEEP_START_MS, -1L)
            val end = prefs.getLong(KEY_SLEEP_END_MS, -1L)
            val source = prefs.getString(KEY_SOURCE, "") ?: ""
            cached = if (start > 0L && end > start && (end - start) >= SleepWakeCyclePolicy.MIN_SLEEP_CYCLE_MS) {
                LastSleepCycle(start, end, source)
            } else {
                null
            }
            loaded = true
            return cached
        }
    }

    fun getLastCompletedSleep(context: Context): LastSleepCycle? = ensureLoaded(context)

    /**
     * Records a completed sleep cycle (>= 3 hours).
     * Returns `true` if the stored wake-up state was updated to a new or better sleep record.
     */
    fun recordWakeUp(
        context: Context,
        sleepStartWallMs: Long,
        sleepEndWallMs: Long,
        source: String
    ): Boolean {
        val duration = sleepEndWallMs - sleepStartWallMs
        if (sleepStartWallMs <= 0L || sleepEndWallMs <= sleepStartWallMs) return false
        if (duration < SleepWakeCyclePolicy.MIN_SLEEP_CYCLE_MS) return false

        val appCtx = context.applicationContext
        val updated = synchronized(this) {
            val prev = ensureLoaded(appCtx)
            if (prev != null) {
                // Keep a strictly newer sleep cycle; if ending in the same 5-min window, prefer longer/earlier-start record
                val isStrictlyOlder = sleepEndWallMs < prev.sleepEndWallMs - 5 * 60_000L
                val isSameWakeWindowNotBetter = abs(sleepEndWallMs - prev.sleepEndWallMs) <= 5 * 60_000L &&
                    duration <= prev.durationMs &&
                    sleepEndWallMs <= prev.sleepEndWallMs
                if (isStrictlyOlder || isSameWakeWindowNotBetter) {
                    return@synchronized false
                }
            }
            val next = LastSleepCycle(
                sleepStartWallMs = sleepStartWallMs,
                sleepEndWallMs = sleepEndWallMs,
                source = source
            )
            cached = next
            loaded = true
            appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_SLEEP_START_MS, sleepStartWallMs)
                .putLong(KEY_SLEEP_END_MS, sleepEndWallMs)
                .putString(KEY_SOURCE, source)
                .apply()
            true
        }

        if (updated) {
            // Invalidate any stale IN_PROGRESS calendar cache immediately when wake-up is detected
            CalendarHealthLoader.clearCache()
        }
        return updated
    }

    fun clear(context: Context) {
        synchronized(this) {
            cached = null
            loaded = true
            context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }
}
