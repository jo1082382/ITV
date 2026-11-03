package com.example.itv.composition

import android.content.Context
import com.example.itv.application.records.ReelsDiagnosticService

/** Compatibility facade for the observer. Only assembles/delegates; no state manipulation. */
internal object ReelsCalibrationConnection {
    private var service: ReelsDiagnosticService? = null
    fun initialize(context: Context) {
        val current = service ?: reelsDiagnosticService(context).also { service = it }
        current.initialize()
    }
    fun saveSamples() { service?.saveSamples() }
    suspend fun clearRecords(context: Context) { reelsDiagnosticService(context).clearRecords() }
    fun clearAll(context: Context) { reelsDiagnosticService(context).clearAll() }
}
