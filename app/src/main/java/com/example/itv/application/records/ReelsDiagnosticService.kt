package com.example.itv.application.records

import com.example.itv.domain.records.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ReelsDiagnosticState(val loading: Boolean = false, val error: String? = null)

/** Synchronous legacy diagnostic operations. Android scheduling remains in the UI adapter. */
internal class ReelsDiagnosticService(private val port: ReelsDiagnosticPort) {
    fun initialize() { port.initialize() }
    fun snapshot(): ReelsDiagnosticSnapshot = port.snapshot()
    fun start() { port.start() }
    fun stopDisplay() { port.stopDisplay() }
    private val mutableState = MutableStateFlow(ReelsDiagnosticState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private suspend fun execute(action: suspend () -> Unit): Boolean = mutex.withLock {
        mutableState.value = ReelsDiagnosticState(loading = true)
        try { action(); true }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            mutableState.value = mutableState.value.copy(error = e.message ?: "릴스 진단 처리 실패")
            false
        } finally { mutableState.value = mutableState.value.copy(loading = false) }
    }
    suspend fun initializeAsync(): Boolean = execute { port.initializeAsync() }
    suspend fun capture(kind: ReelsSampleKind): Boolean {
        // Capture at button time, not after a queued IO delay.
        val ids = port.recentSample()
        return execute {
            check(ids != null) { "60초 내 Instagram 화면 표본이 없습니다." }
            port.initializeAsync(); port.commitSample(kind, ids)
        }
    }
    suspend fun resetSamplesAndCounters(): Boolean = execute { port.initializeAsync(); port.commitReset() }
    suspend fun clearRecords(): Boolean = execute { port.initializeAsync(); port.commitClearRecords() }
    fun saveSamples() { port.saveSamples() }
    fun clearAll() { port.initialize(); port.clearAll() }
}
