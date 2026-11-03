package com.example.itv.application.records

import com.example.itv.domain.records.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ShortformScreenState(val data: ShortformToday = ShortformToday(),
    val loading: Boolean = false, val error: String? = null, val message: String? = null)
class ShortformRecordService(private val source: ShortformRecordSource) {
    private val mutableState = MutableStateFlow(ShortformScreenState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    suspend fun deleteAll(): Unit = mutex.withLock {
        mutableState.value = mutableState.value.copy(loading = true, error = null, message = null)
        try {
            source.deleteAll()
            mutableState.value = ShortformScreenState(data = source.today(),
                message = "숏폼 기록을 삭제했습니다. 이후 사용은 새로 기록됩니다.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            mutableState.value = mutableState.value.copy(error = e.message ?: "숏폼 기록 삭제 실패")
        } finally { mutableState.value = mutableState.value.copy(loading = false) }
    }
    suspend fun refresh() = mutex.withLock {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        try {
            mutableState.value = ShortformScreenState(data = source.today(), message = mutableState.value.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.value = mutableState.value.copy(error = e.message ?: "숏폼 기록 조회 실패")
        } finally {
            mutableState.value = mutableState.value.copy(loading = false)
        }
    }
}
