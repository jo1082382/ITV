package com.example.itv.domain.records

import java.time.Instant
import java.time.ZoneId

object RecordPolicy {
    // Calendar six months, anchored to local start of day; not a fixed 180 days.
    fun retentionStart(now: Instant, zone: ZoneId): Long =
        now.atZone(zone).toLocalDate().minusMonths(6).atStartOfDay(zone).toInstant().toEpochMilli()

    // Half-open intervals [start, end). Overlap is clipped to the requested period.
    fun overlapMillis(start: Long, end: Long, from: Long, to: Long): Long {
        require(end > start && to >= from)
        return (minOf(end, to) - maxOf(start, from)).coerceAtLeast(0)
    }
}
