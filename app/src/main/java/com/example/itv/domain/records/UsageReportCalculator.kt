package com.example.itv.domain.records

import java.time.*

internal fun calculateUsageReport(events: List<UsageObservation>, days: Int, zone: ZoneId, start: Long, end: Long, labelFor: (String) -> String): Report {
        var pkg:String?=null; var active:Long?=null
        val buckets=DoubleArray(24); val dates=mutableSetOf<LocalDate>()
        val appBuckets = List(24) { mutableMapOf<String, Double>() }
        val dailyTotals = linkedMapOf<LocalDate, Double>()
        val entryBuckets = List(24) { mutableMapOf<String, Int>() }
        var lastResumePackage: String? = null
        var lastPauseAt: Long? = null
        var lastEventTime = Long.MIN_VALUE
        var lastEventType: ObservationKind? = null
        var lastEventPackage: String? = null
        var instagramMinutes = 0.0
        fun close(at:Long) {
            val a=active ?: return
            var t=maxOf(start,a); val stop=minOf(end,at)
            while(t<stop) {
                val local=Instant.ofEpochMilli(t).atZone(zone)
                val next=local.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val until=minOf(stop,next)
                if(until<=t) break
                val minutes = (until-t)/60000.0
                buckets[local.hour] += minutes
                val date = local.toLocalDate()
                dailyTotals[date] = (dailyTotals[date] ?: 0.0) + minutes
                pkg?.let { name ->
                    val apps = appBuckets[local.hour]
                    apps[name] = (apps[name] ?: 0.0) + minutes
                }
                if (pkg == "com.instagram.android") instagramMinutes += (until-t)/60000.0
                dates.add(local.toLocalDate());t=until
            }
            active=null;pkg=null
        }
        for (e in events) {
            // Some devices duplicate events. Count a byte-for-byte event key once.
            if (e.timeStamp == lastEventTime && e.eventType == lastEventType && e.packageName == lastEventPackage) continue
            lastEventTime = e.timeStamp; lastEventType = e.eventType; lastEventPackage = e.packageName
            when(e.eventType) {
                ObservationKind.ACTIVITY_RESUMED -> {
                    val name = e.packageName
                    val pause = lastPauseAt
                    // Same-package pause/resume under 1 second is treated as internal navigation.
                    val isEntry = name != null && (lastResumePackage != name ||
                        (pause != null && e.timeStamp - pause >= 1000L))
                    if (isEntry && name != null) {
                        val hour = Instant.ofEpochMilli(e.timeStamp).atZone(zone).hour
                        entryBuckets[hour][name] = (entryBuckets[hour][name] ?: 0) + 1
                    }
                    lastResumePackage = name; lastPauseAt = null
                    close(e.timeStamp)
                    pkg=name;active=e.timeStamp
                }
                ObservationKind.ACTIVITY_PAUSED -> {
                    if (lastResumePackage == e.packageName) lastPauseAt = e.timeStamp
                    if (pkg == e.packageName) close(e.timeStamp)
                }
                ObservationKind.SCREEN_NON_INTERACTIVE, ObservationKind.KEYGUARD_SHOWN -> {
                    close(e.timeStamp); lastResumePackage = null; lastPauseAt = null
                }
                else -> Unit
            }
        }
        // Do NOT invent an end for an unclosed foreground interval.
        val labels = mutableMapOf<String, String>()
        val hourlyApps = appBuckets.mapIndexed { hour, apps ->
            apps.map { (name, minutes) ->
                val label = labels.getOrPut(name) {
                    labelFor(name)
                }
                AppHourUsage(name, label, minutes / days,
                    entryCount = entryBuckets[hour][name] ?: 0,
                    entryAverage = if (days > 1) (entryBuckets[hour][name] ?: 0).toDouble() / days else null)
            }.sortedByDescending { it.minutes }
        }
        return Report(buckets.map { it/days },buckets.sum(),days,dates.size,instagramMinutes,hourlyApps,dailyTotals)
    }