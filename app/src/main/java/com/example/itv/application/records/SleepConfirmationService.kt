package com.example.itv.application.records
import com.example.itv.domain.records.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SleepConfirmationState(val value:SleepConfirmation?=null,val loading:Boolean=false,val error:String?=null)
class SleepConfirmationService(private val store:SleepConfirmationStore) {
    private val mutable=MutableStateFlow(SleepConfirmationState())
    val state=mutable.asStateFlow()
    private val mutex=Mutex()
    private suspend fun execute(action:suspend ()->SleepConfirmation?) { mutex.withLock {
        mutable.value=mutable.value.copy(loading=true,error=null)
        try { mutable.value=SleepConfirmationState(action()) }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { mutable.value=mutable.value.copy(error=e.message ?: "수면 기록 처리 실패") }
        finally { mutable.value=mutable.value.copy(loading=false) }
    } }
    suspend fun readDates(dates:List<String>):Map<String,SleepConfirmation> = mutex.withLock {
        dates.mapNotNull { date -> store.read(date)?.let { date to it } }.toMap()
    }
    suspend fun load(date:String) = execute { store.read(date) }
    suspend fun save(value:SleepConfirmation) = execute {
        SleepConfirmationPolicy.validate(value,System.currentTimeMillis());store.save(value);store.read(value.date)
    }
    suspend fun reset(date:String) = execute { store.reset(date);null }
}
