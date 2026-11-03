package com.example.itv.infrastructure.records

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import com.example.itv.domain.records.*
import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.storage.shortform.ReelsUsageStore

internal class AndroidReelsDiagnosticPort(context: Context) : ReelsDiagnosticPort {
    private val context = context.applicationContext
    companion object { private var restored = false }
    private fun restore(s: ReelsCalibrationSamples) {
        if (restored) return
        ReelsProbe.reels = s.reels; ReelsProbe.feed = s.feed
        ReelsProbe.dm = s.dm; ReelsProbe.comments = s.comments
        restored = true
    }
    override fun initialize() {
        ReelsUsageStore.initialize(context)
        if (restored) return
        // Existing observation path must not crash on corrupt calibration data.
        runCatching { ReelsUsageStore.readSamples() }.getOrNull()?.let { restore(it) }
    }
    override suspend fun initializeAsync() {
        val s = withContext(Dispatchers.IO) {
            ReelsUsageStore.initialize(context); ReelsUsageStore.readSamples()
        }
        restore(s)
    }
    override suspend fun commitSample(kind: ReelsSampleKind, ids: Set<String>): Unit = withContext(NonCancellable) {
        val old = samples()
        val next = when (kind) {
            ReelsSampleKind.REELS -> old.copy(reels = ids.toSet())
            ReelsSampleKind.FEED -> old.copy(feed = ids.toSet())
            ReelsSampleKind.DM -> old.copy(dm = ids.toSet())
            ReelsSampleKind.COMMENTS -> old.copy(comments = ids.toSet())
        }
        withContext(Dispatchers.IO) { ReelsUsageStore.saveSamplesConfirmed(next) }
        applySample(kind, ids)
    }
    override suspend fun commitReset(): Unit = withContext(NonCancellable) {
        withContext(Dispatchers.IO) { ReelsUsageStore.saveSamplesConfirmed(ReelsCalibrationSamples()) }
        ReelsProbe.reset()
    }
    override suspend fun commitClearRecords(): Unit = withContext(NonCancellable) {
        withContext(Dispatchers.IO) { ReelsUsageStore.clearRecordsConfirmed(context) }
        ReelsProbe.events = 0; ReelsProbe.candidates = 0
        ReelsProbe.candidateMs = 0L; ReelsProbe.lastScroll = 0L
    }
    private fun samples() = ReelsCalibrationSamples(ReelsProbe.reels?.toSet(),
        ReelsProbe.feed?.toSet(), ReelsProbe.dm?.toSet(), ReelsProbe.comments?.toSet())
    override fun snapshot() = ReelsDiagnosticSnapshot(ReelsProbe.status, ReelsProbe.candidateMs,
        ReelsProbe.events, ReelsProbe.candidates, samples(), ReelsProbe.markers().size,
        ReelsProbe.latest.sorted())
    override fun start() { ReelsProbe.enabled = true }
    override fun stopDisplay() { ReelsProbe.enabled = false; ReelsProbe.valid = false }
    override fun recentSample(): Set<String>? = ReelsProbe.sample()?.toSet()
    override fun applySample(kind: ReelsSampleKind, ids: Set<String>) {
        when (kind) {
            ReelsSampleKind.REELS -> ReelsProbe.reels = ids.toSet()
            ReelsSampleKind.FEED -> ReelsProbe.feed = ids.toSet()
            ReelsSampleKind.DM -> ReelsProbe.dm = ids.toSet()
            ReelsSampleKind.COMMENTS -> ReelsProbe.comments = ids.toSet()
        }
    }
    override fun saveSamples() { ReelsUsageStore.saveSamples(samples()) }
    override fun resetSamplesAndCounters() { ReelsProbe.reset() }
    override fun clearRecords() {
        ReelsUsageStore.clearRecords(context)
        ReelsProbe.events = 0; ReelsProbe.candidates = 0
        ReelsProbe.candidateMs = 0L; ReelsProbe.lastScroll = 0L
    }
    override fun clearAll() { ReelsProbe.reset(); ReelsUsageStore.clearAll(context) }
}
