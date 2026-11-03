package com.example.itv.domain.permissions

data class PermissionSnapshot(val guardConnected: Boolean = false, val notifications: Boolean? = null,
    val monitorVisible: Boolean? = null, val autoEnabled: Boolean = true, val consent: Boolean = false,
    val usage: Boolean? = null, val overlay: Boolean? = null, val history: List<String> = emptyList(), val notice: String? = null)
interface PermissionSettingsPort {
    suspend fun initialize()
    suspend fun refresh()
    suspend fun poll()
    suspend fun updateCollectionConsent(value: Boolean)
    suspend fun diagnostics(includeJournal: Boolean): PermissionDiagnostics
    suspend fun clearJournal()
    suspend fun setAutoEnabled(value: Boolean)
    suspend fun snapshot(): PermissionSnapshot
}

 data class PermissionMissionCounts(val assigned: Long = 0, val success: Long = 0,
    val failed: Long = 0, val interrupted: Long = 0)
 data class PermissionCoachSample(val at: Long = 0, val running: Boolean = false,
    val usedMs: Long = 0, val restMs: Long = 0, val interventionMs: Long = 60000,
    val requiredRestMs: Long = 30000, val retryRemainingMs: Long = 0,
    val decision: String = "코칭 서비스 실행 관측 없음")
 data class PermissionDiagnostics(val sample: PermissionCoachSample = PermissionCoachSample(),
    val connected: Boolean = false, val checkedAt: Long = 0, val guardReason: String? = null,
    val youtubeState: Int = -1, val reelsState: Int = -1,
    val missionCounts: PermissionMissionCounts = PermissionMissionCounts(),
    val journal: List<String> = emptyList(), val now: Long = 0)
