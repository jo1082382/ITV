package com.example.itv.domain.records

import java.time.Instant
import java.time.ZoneId

/** Captured when the observation is handed to the application boundary, not at DB completion. */
internal data class ShortformObservationTime(val elapsedMs: Long, val wallMs: Long, val zoneId: String)
internal data class ShortformClock(val valid: Boolean = false, val elapsedMs: Long = 0, val wallMs: Long = 0)
internal data class ShortformContribution(val date: String, val zoneId: String, val ms: Long = 0)
internal data class ShortformIntervalResult(val clock: ShortformClock,
    val contributions: List<ShortformContribution>, val changed: Boolean)

/** Pure legacy policy: elapsed 1..1500ms and wall delta 1..2000ms; no gap interpolation. */
internal object ShortformIntervalCalculator {
    fun evaluate(previous: ShortformClock, confirmed: Boolean, time: ShortformObservationTime): ShortformIntervalResult {
        val next = ShortformClock(confirmed, time.elapsedMs, time.wallMs)
        if (!confirmed) return ShortformIntervalResult(next, emptyList(), previous.valid)
        val zone = ZoneId.of(time.zoneId)
        val date = Instant.ofEpochMilli(time.wallMs).atZone(zone).toLocalDate()
        val result = mutableListOf(ShortformContribution(date.toString(), time.zoneId))
        val dt = time.elapsedMs - previous.elapsedMs
        if (previous.valid && dt in 1L..1500L && time.wallMs - previous.wallMs in 1L..2000L) {
            val old = Instant.ofEpochMilli(previous.wallMs).atZone(zone).toLocalDate()
            if (old == date) result += ShortformContribution(date.toString(), time.zoneId, dt)
            else {
                val before = (date.atStartOfDay(zone).toInstant().toEpochMilli() - previous.wallMs).coerceIn(0L, dt)
                result += ShortformContribution(old.toString(), time.zoneId, before)
                result += ShortformContribution(date.toString(), time.zoneId, dt - before)
            }
        }
        return ShortformIntervalResult(next, result, previous.valid != confirmed)
    }
}
