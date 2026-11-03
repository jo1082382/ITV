package com.example.itv.domain.records

internal data class YoutubeDiagnosticSnapshot(val connected: Boolean, val enabled: Boolean, val report: String)
internal interface YoutubeDiagnosticPort {
    fun snapshot(): YoutubeDiagnosticSnapshot
    suspend fun canStart(): Boolean
    fun start()
    fun stop()
    fun reset()
}
