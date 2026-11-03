package com.example.itv.application.permissions

import com.example.itv.domain.permissions.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PermissionScreenState(val data: PermissionSnapshot = PermissionSnapshot(),
    val loading: Boolean = false, val error: String? = null,
    val diagnostics: PermissionDiagnostics = PermissionDiagnostics())

/** One queue owns all port access. The caller supplies a lifecycle-bound main scope. */
class PermissionSettingsService(private val port: PermissionSettingsPort, scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(PermissionScreenState())
    val state: StateFlow<PermissionScreenState> = mutableState.asStateFlow()
    private data class Request(val foreground: Boolean,
        val action: suspend () -> PermissionDiagnostics?)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private var pollPending = false

    init {
        val worker = scope.launch {
            for (request in requests) {
                if (request.foreground) mutableState.value = mutableState.value.copy(loading = true, error = null)
                try {
                    val diagnostics = request.action()
                    val data = port.snapshot()
                    mutableState.value = mutableState.value.copy(data = data,
                        diagnostics = diagnostics ?: mutableState.value.diagnostics,
                        loading = false,
                        error = data.notice ?: if (request.foreground) null else mutableState.value.error)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Do not replace the last confirmed data with a speculative value.
                    mutableState.value = mutableState.value.copy(loading = false,
                        error = e.message ?: "설정을 처리하지 못했습니다.")
                } finally {
                    if (!request.foreground) pollPending = false
                }
            }
        }
        worker.invokeOnCompletion { requests.cancel() }
    }
    private fun submit(action: suspend () -> PermissionDiagnostics?) {
        requests.trySend(Request(true, action))
    }
    fun initialize() = submit { port.initialize(); port.refresh(); port.diagnostics(false) }
    fun refresh() = submit { port.refresh(); null }
    fun refreshDiagnostics(includeJournal: Boolean) = submit {
        port.refresh(); port.diagnostics(includeJournal)
    }
    fun poll() { pollDiagnostics(false) }
    fun pollDiagnostics(includeJournal: Boolean) {
        // Collapse background ticks only. Explicit user writes are never dropped.
        if (pollPending) return
        pollPending = true
        if (requests.trySend(Request(false) {
            port.poll(); port.diagnostics(includeJournal)
        }).isFailure) pollPending = false
    }
    fun clearJournal() = submit { port.clearJournal(); port.diagnostics(true) }
    fun setAutoEnabled(value: Boolean) = submit { port.setAutoEnabled(value); null }
    fun updateCollectionConsent(value: Boolean) = submit { port.updateCollectionConsent(value); null }
    fun showMessage(message: String) {
        mutableState.value = mutableState.value.copy(error = message)
    }
}
