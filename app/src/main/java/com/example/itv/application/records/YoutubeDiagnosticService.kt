package com.example.itv.application.records

import com.example.itv.domain.records.YoutubeDiagnosticPort
import com.example.itv.domain.records.YoutubeDiagnosticSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class YoutubeDiagnosticState(val snapshot: YoutubeDiagnosticSnapshot =
    YoutubeDiagnosticSnapshot(false, false, "진단 조회 대기"),
    val loading: Boolean = false, val error: String? = null)

/** UI invokes on its main lifecycle scope. Only port IO switches dispatchers. */
internal class YoutubeDiagnosticService(private val port: YoutubeDiagnosticPort) {
    private val mutableState = MutableStateFlow(YoutubeDiagnosticState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    fun snapshot(): YoutubeDiagnosticSnapshot {
        try { mutableState.value = mutableState.value.copy(snapshot = port.snapshot()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableState.value = mutableState.value.copy(error = e.message ?: "진단 조회 실패") }
        return mutableState.value.snapshot
    }
    private suspend fun execute(action: suspend () -> Unit): Unit = mutex.withLock {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        try { action(); snapshot() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            mutableState.value = mutableState.value.copy(error = e.message ?: "진단 요청 처리 실패")
        } finally { mutableState.value = mutableState.value.copy(loading = false) }
    }
    suspend fun start(): Unit = execute {
        check(port.canStart()) { "수집 동의와 접근성 연결을 먼저 확인해 주세요." }
        port.start()
    }
    suspend fun stop(): Unit = execute { port.stop() }
    suspend fun reset(): Unit = execute { port.reset() }
    fun report(): String = snapshot().report
}
