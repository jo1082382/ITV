package com.example.itv.domain.records

internal enum class ReelsSampleKind { REELS, FEED, DM, COMMENTS }
internal data class ReelsDiagnosticSnapshot(val status: String, val candidateMs: Long,
    val events: Int, val candidates: Int, val samples: ReelsCalibrationSamples,
    val markerCount: Int, val latestIds: List<String>)
internal interface ReelsDiagnosticPort {
    suspend fun initializeAsync()
    suspend fun commitSample(kind: ReelsSampleKind, ids: Set<String>)
    suspend fun commitReset()
    suspend fun commitClearRecords()
    fun initialize()
    fun snapshot(): ReelsDiagnosticSnapshot
    fun start()
    fun stopDisplay()
    fun recentSample(): Set<String>?
    fun applySample(kind: ReelsSampleKind, ids: Set<String>)
    fun saveSamples()
    fun resetSamplesAndCounters()
    fun clearRecords()
    fun clearAll()
}
