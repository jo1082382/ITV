package com.example.itv.domain.records

import java.time.*

internal fun calculateNightReport(events: List<UsageObservation>, days: Int, zone: ZoneId, now: Long): NightReport {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val tracker = NightRestTracker()
    var earliest: Long? = null
    var interactive = false
    var locked: Boolean? = null
    for (e in events) {
        tracker.advance(e.timeStamp)
        if (earliest == null) earliest = e.timeStamp
        when (e.eventType) {
            ObservationKind.SCREEN_NON_INTERACTIVE -> {
                interactive = false
                // A new wake cycle needs fresh unlock evidence, not yesterday's unlocked state.
                locked = null
                tracker.screenOff(e.timeStamp)
            }
            ObservationKind.SCREEN_INTERACTIVE -> interactive = true
            ObservationKind.KEYGUARD_SHOWN -> locked = true
            ObservationKind.KEYGUARD_HIDDEN -> {
                locked = false
                if (interactive) tracker.confirmedUse(e.timeStamp)
            }
            ObservationKind.ACTIVITY_RESUMED -> {
                val pkg = e.packageName.orEmpty()
                // A foreground app while locked can be an incoming call/alarm.
                // Unknown lock state is not treated as proof of deliberate use.
                if (interactive && locked == false && pkg.isNotEmpty() &&
                    pkg != "com.android.systemui" && pkg != "android") tracker.confirmedUse(e.timeStamp)
            }
            ObservationKind.DEVICE_SHUTDOWN, ObservationKind.DEVICE_STARTUP -> {
                tracker.deviceBoundary(e.timeStamp); locked = null; interactive = false
            }
            else -> Unit
        }
    }
    val intervals = tracker.snapshot(now)
    val rows = mutableListOf<NightSegment>()
    val candidates = mutableListOf<NightSegment>()
    var first = if (days <= 1) today else today.minusDays(days.toLong())
    if (days == 0) first = earliest?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today
    val count = if (days == 0) java.time.temporal.ChronoUnit.DAYS.between(first, today).toInt() + 1 else days
    for (i in 0 until count) {
        val date = first.plusDays(i.toLong())
        // Attribute the whole interval to its actual end date; never clip at midnight/noon.
        val choices = intervals.filter { interval ->
            val end = interval.end
            end != null && end - interval.start >= 3*60*60*1000L &&
                Instant.ofEpochMilli(end).atZone(zone).toLocalDate() == date
        }
        candidates.addAll(choices.map { interval ->
            NightSegment(date, interval.start, interval.end,
                (interval.end!! - interval.start) / 60000.0, interval.briefWakeCount)
        })
        // Equal lengths: prefer the earlier start for a stable, explicit tie-break.
        val chosen = choices.sortedBy { it.start }.maxByOrNull { (it.end ?: now) - it.start }
        if (chosen != null) rows.add(NightSegment(date, chosen.start, chosen.end, ((chosen.end ?: now)-chosen.start)/60000.0, chosen.briefWakeCount))
    }
    return NightReport(count, rows, zone, now, candidates)
}
