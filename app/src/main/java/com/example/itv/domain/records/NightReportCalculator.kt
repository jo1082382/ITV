package com.example.itv.domain.records

import java.time.*

private const val MIN_SLEEP_MS = 3L * 60 * 60 * 1000L

private fun isLockScreenBypassPackage(pkg: String): Boolean {
    if (pkg.isEmpty()) return true
    val lower = pkg.lowercase()
    return lower == "com.android.systemui" ||
        lower == "android" ||
        lower.contains("incallui") ||
        lower.contains("telecom") ||
        lower.contains("dialer") ||
        lower.contains("clock") ||
        lower.contains("alarm") ||
        lower.contains("keyguard") ||
        lower.contains("lockscreen") ||
        lower.contains("aod") ||
        lower.contains("emergency")
}

internal fun calculateNightReport(
    events: List<UsageObservation>,
    days: Int,
    zone: ZoneId,
    now: Long,
    finalizeActiveWake: Boolean = false,
    fallbackLiveSleep: Pair<Long, Long>? = null
): NightReport {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val tracker = NightRestTracker()
    var earliest: Long? = null
    var interactive = false
    var locked: Boolean? = null
    var lastInteractiveAt: Long? = null
    for (e in events) {
        tracker.advance(e.timeStamp)
        if (earliest == null) earliest = e.timeStamp
        when (e.eventType) {
            ObservationKind.SCREEN_NON_INTERACTIVE -> {
                interactive = false
                // A new wake cycle needs fresh unlock evidence, not yesterday's unlocked state.
                locked = null
                lastInteractiveAt = null
                tracker.screenOff(e.timeStamp)
            }
            ObservationKind.SCREEN_INTERACTIVE -> {
                interactive = true
                if (lastInteractiveAt == null) lastInteractiveAt = e.timeStamp
                // Handles devices where KEYGUARD_HIDDEN arrives slightly before SCREEN_INTERACTIVE (e.g. fingerprint unlock).
                if (locked == false) tracker.confirmedUse(e.timeStamp)
            }
            ObservationKind.KEYGUARD_SHOWN -> locked = true
            ObservationKind.KEYGUARD_HIDDEN -> {
                locked = false
                if (interactive) tracker.confirmedUse(e.timeStamp)
            }
            ObservationKind.ACTIVITY_RESUMED -> {
                val pkg = e.packageName.orEmpty()
                // A foreground app while locked (KEYGUARD_SHOWN) can be an incoming call/alarm.
                // When lock state is explicitly unlocked (locked == false), any non-systemui app counts.
                // When lock state is unknown (locked == null, e.g. no lock screen / Smart Lock / dropped KEYGUARD_HIDDEN),
                // allow deliberate user apps while still ignoring call/alarm/clock/lockscreen packages.
                if (interactive && pkg.isNotEmpty()) {
                    val allowed = when (locked) {
                        false -> pkg != "com.android.systemui" && pkg != "android"
                        null -> !isLockScreenBypassPackage(pkg)
                        true -> false
                    }
                    if (allowed) tracker.confirmedUse(e.timeStamp)
                }
            }
            ObservationKind.DEVICE_SHUTDOWN, ObservationKind.DEVICE_STARTUP -> {
                tracker.deviceBoundary(e.timeStamp); locked = null; interactive = false; lastInteractiveAt = null
            }
            else -> Unit
        }
    }
    val snapshotIntervals = tracker.snapshot(now).toMutableList()
    if (finalizeActiveWake) {
        val pendingWake = tracker.pendingWakeInterval()
        if (pendingWake != null && pendingWake.end != null && (pendingWake.end - pendingWake.start) >= MIN_SLEEP_MS) {
            // The user already unlocked after >= 3h sleep and deliberately opened ITV right away (<60s after unlock).
            snapshotIntervals.removeAll { it.start == pendingWake.start && it.end == null }
            snapshotIntervals.add(pendingWake)
        } else {
            val openStart = tracker.openRestStart()
            if (openStart != null) {
                val wakeAt = (lastInteractiveAt ?: now).coerceAtMost(now)
                if (wakeAt - openStart >= MIN_SLEEP_MS) {
                    // UsageStatsManager has not yet flushed the latest unlock/resume event while ITV is already foreground.
                    snapshotIntervals.removeAll { it.start == openStart && it.end == null }
                    snapshotIntervals.add(RestInterval(openStart, wakeAt, tracker.openBriefCount()))
                }
            }
        }
    }

    val intervals = snapshotIntervals.toList()
    val rows = mutableListOf<NightSegment>()
    val candidates = mutableListOf<NightSegment>()
    var first = if (days <= 1) today else today.minusDays(days.toLong() - 1L)
    if (days == 0) first = earliest?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today
    val count = if (days == 0) java.time.temporal.ChronoUnit.DAYS.between(first, today).toInt() + 1 else days
    for (i in 0 until count) {
        val date = first.plusDays(i.toLong())
        // Attribute the whole interval to its actual end date; never clip at midnight/noon.
        val choices = intervals.filter { interval ->
            val end = interval.end
            end != null && end - interval.start >= MIN_SLEEP_MS &&
                Instant.ofEpochMilli(end).atZone(zone).toLocalDate() == date
        }.toMutableList()

        // Fallback only for today when OS UsageEvents missed/delayed screen-off events during Doze,
        // while ITV's own foreground service observed a >= 3h wall-clock screen-off -> unlock cycle.
        if (choices.isEmpty() && date == today && fallbackLiveSleep != null) {
            val (fbStart, fbEnd) = fallbackLiveSleep
            if (fbEnd > fbStart &&
                fbEnd - fbStart >= MIN_SLEEP_MS &&
                fbEnd <= now + 60_000L &&
                Instant.ofEpochMilli(fbEnd).atZone(zone).toLocalDate() == date
            ) {
                choices.add(RestInterval(fbStart, fbEnd, 0))
            }
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
