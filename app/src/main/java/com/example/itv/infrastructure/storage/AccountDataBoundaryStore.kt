package com.example.itv.infrastructure.storage

import android.content.Context
import com.example.itv.domain.records.NightReport
import com.example.itv.domain.records.NightSegment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tracks the local fresh-start cutoff timestamp set after account deletion or sign-out.
 *
 * Android OS retains system UsageStats/UsageEvents for ~7–35 days even after an app wipes
 * its own local files. Without a fresh-start boundary, re-registering or signing into a
 * different account on the same device would immediately re-import the previous account's
 * historical OS phone usage and sleep segments.
 *
 * - Brand-new installs have no boundary (`null`), preserving the normal 30-day OS bootstrap.
 * - Account deletion / sign-out sets the boundary to `now`, blocking prior OS history.
 * - Future cloud restore (Step 4) can call [clear] to restore a cloud account's full range.
 */
internal object AccountDataBoundaryStore {

    private const val PREF_NAME = "itv_account_boundary_v1"
    private const val KEY_FRESH_START_EPOCH_MS = "fresh_start_epoch_ms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun markFreshStart(context: Context, epochMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_FRESH_START_EPOCH_MS, epochMs).commit()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_FRESH_START_EPOCH_MS).commit()
    }

    fun getFreshStartEpochMs(context: Context): Long? {
        val v = prefs(context).getLong(KEY_FRESH_START_EPOCH_MS, 0L)
        return v.takeIf { it > 0L }
    }

    fun getFreshStartDate(context: Context, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        val ms = getFreshStartEpochMs(context) ?: return null
        return epochMsToLocalDate(ms, zone)
    }

    internal fun epochMsToLocalDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    internal fun isDateBeforeBoundary(date: LocalDate, boundaryDate: LocalDate?): Boolean =
        boundaryDate != null && date.isBefore(boundaryDate)

    internal fun clampStartWallMs(startWallMs: Long, boundaryMs: Long?): Long =
        if (boundaryMs != null && boundaryMs > startWallMs) boundaryMs else startWallMs

    internal fun isSegmentAllowed(segment: NightSegment, boundaryMs: Long?): Boolean {
        if (boundaryMs == null) return true
        val effectiveEnd = segment.end ?: segment.start
        return effectiveEnd >= boundaryMs && segment.start >= boundaryMs - 24L * 60L * 60_000L
    }

    internal fun filterNightReport(report: NightReport?, boundaryMs: Long?): NightReport? {
        if (report == null || boundaryMs == null) return report
        val filteredSegments = report.segments.filter { isSegmentAllowed(it, boundaryMs) }
        val filteredCandidates = report.candidates.filter { isSegmentAllowed(it, boundaryMs) }
        return report.copy(
            segments = filteredSegments,
            candidates = filteredCandidates
        )
    }
}
