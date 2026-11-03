package com.example.itv.application.records

import com.example.itv.domain.records.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.time.*

/** Process-local single consumer. Never waits for Room on the calling thread. */
internal class ShortformRecordingCoordinator(
    private val persistenceFactory: () -> ShortformPersistenceService,
    private val elapsedNow: () -> Long,
    private val consentGranted: () -> Boolean
) {
    data class Day(val ms: Long = 0, val transitions: Int = 0, val observed: Boolean = false)
    private data class Command(val type: Int, val initialize: Boolean = false,
        val app: String = "", val valid: Boolean = false,
        val time: ShortformObservationTime? = null, val generation: Long = 0, val completion: CompletableDeferred<Unit>? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Command>(512)
    private val admissionLock = Any()
    private val admission = com.example.itv.application.records.ShortformQueueAdmission()
    private var lastGeneration = 0L
    @Volatile private var ready = false
    @Volatile private var pendingSave = false
    @Volatile private var failure: String? = null
    @Volatile private var issue = ShortformStorageIssue.NONE
    @Volatile private var published: List<StoredShortformDay> = emptyList()
    private var service: com.example.itv.application.records.ShortformPersistenceService? = null
    private var prepared = false
    private var dirty = false
    private var saved = 0L
    private val records = linkedMapOf<String, StoredShortformDay>()
    private val hourly = linkedMapOf<String,StoredShortformHour>()
    private fun hourKey(x:StoredShortformHour)="${x.packageName}|${x.date}|${x.hour}|${x.zone}"
    private val clocks = mutableMapOf<String, ShortformClock>()
    private fun key(x: StoredShortformDay) = "${x.packageName}|${x.date}|${x.zoneKey}|${x.source}"
    private fun pkg(app: String) = when(app) {
        "instagram" -> "com.instagram.android"
        "youtube" -> "com.google.android.youtube"
        else -> error("지원하지 않는 숏폼 앱: $app")
    }
    init {
        scope.launch {
            for (command in queue) {
                try {
                    if (command.type == 3 || command.type == 5) synchronized(admissionLock) { admission.started(true) }
                    if (command.generation != lastGeneration) {
                        clocks.clear()
                        lastGeneration = command.generation
                    }
                    val initialize = command.initialize
                    if (!prepared && initialize) {
                        val current = service ?: persistenceFactory().also { service = it }
                        // Keep this command in hand until initialization succeeds. No silent event loss.
                        while (!prepared) {
                            try {
                                val initial = current.prepare()
                                val initialHours=current.hours()
                                hourly.clear();initialHours.forEach { hourly[hourKey(it)]=it }
                                records.clear(); initial.forEach { records[key(it)] = it }
                                prepared = true; ready = true; issue=ShortformStorageIssue.NONE;failure = null; publish()
                            } catch(e: CancellationException) { throw e }
                            catch(e: Exception) {
                                if (command.completion != null) throw e
                                issue = ShortformStorageIssue.PREPARATION; failure = e.message
                                delay(5000)
                            }
                        }
                    }
                    when(command.type) {
                        0 -> {
                            val t = checkNotNull(command.time)
                            ShortformHourPolicy.split(pkg(command.app),clocks[command.app] ?: ShortformClock(),command.valid,t).forEach {
                                val id=hourKey(it); hourly[id]=it.copy(ms=Math.addExact(hourly[id]?.ms ?: 0L,it.ms))
                            }
                            val result = ShortformIntervalCalculator.evaluate(clocks[command.app] ?: ShortformClock(), command.valid, t)
                            result.contributions.forEach { add(command.app, it.date, it.zoneId, it.ms, 0) }
                            clocks[command.app] = result.clock
                            publish()
                            if(result.changed || elapsedNow()-saved >= 5000) save()
                        }
                        1 -> {
                            val t=checkNotNull(command.time)
                            if(command.valid) {
                                val date=Instant.ofEpochMilli(t.wallMs).atZone(ZoneId.of(t.zoneId)).toLocalDate().toString()
                                add(command.app,date,t.zoneId,0,1);publish();save()
                            }
                        }
                        2 -> { clocks.clear();save() }
                        3 -> { publish();save() }
                        4 -> {
                            checkNotNull(service).clear()
                            records.clear();hourly.clear();clocks.clear();dirty=false;pendingSave=false;issue=ShortformStorageIssue.NONE;failure=null;publish()
                        }
                        5 -> save()
                    }
                    command.completion?.complete(Unit)
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    issue = ShortformStorageIssue.PROCESSING; failure=e.message
                    command.completion?.completeExceptionally(e)
                }
            }
        }
        // Maintenance requests are coalesced and never suspend producers.
        scope.launch { while(isActive) { delay(5000);submit(Command(5)) } }
    }
    private fun publish() { published=records.values.toList() }
    private fun add(app:String,date:String,zone:String,ms:Long,n:Long) {
        val zero=StoredShortformDay(pkg(app),date,zone,"LIVE_OBSERVATION",0,0,false,"shortform-v1")
        val id=key(zero);val old=records[id] ?: zero
        records[id]=old.copy(ms=Math.addExact(old.ms,ms),transitions=Math.addExact(old.transitions,n),observed=true)
        dirty=true;pendingSave=true
    }
    private suspend fun save() {
        if(!prepared || !dirty)return
        try {
            checkNotNull(service).saveWithHours(records.values.toList(),hourly.values.toList())
            dirty=false;pendingSave=false;saved=elapsedNow();issue=ShortformStorageIssue.NONE;failure=null
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { issue = ShortformStorageIssue.SAVING; failure=e.message }
    }
    private fun captureTime()=ShortformObservationTime(elapsedNow(),System.currentTimeMillis(),ZoneId.systemDefault().id)
    private fun submit(command:Command) {
        synchronized(admissionLock) {
            val maintenance = command.type == 3 || command.type == 5
            if (!admission.canSubmit(maintenance)) return
            if(queue.trySend(command.copy(generation = admission.generation)).isSuccess) {
                admission.accepted(maintenance)
            } else if (!maintenance) {
                // Never connect the next accepted observation across rejected events.
                admission.rejected(false)
                issue = ShortformStorageIssue.OVERFLOW; failure=null
            }
        }
    }
    fun isReady(): Boolean = ready
    fun storageStatus(): ShortformStorageStatus = ShortformStorageStatus(ready, pendingSave,
        synchronized(admissionLock) { admission.lost }, issue, failure)
    fun observe(app:String,valid:Boolean,time:ShortformObservationTime=captureTime()) {
        val granted=consentGranted()
        submit(Command(0,true,app,valid && granted,time))
    }
    fun transition(app:String,time:ShortformObservationTime=captureTime()) {
        val granted=consentGranted()
        submit(Command(1,true,app,granted,time))
    }
    fun today(app:String):Day {
        submit(Command(3,true))
        if(!consentGranted()) return Day()
        val date=LocalDate.now().toString()
        val rows=published.filter { it.packageName==pkg(app) && it.date==date }
        return Day(rows.sumOf { it.ms },rows.sumOf { it.transitions }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),rows.any { it.observed })
    }
    fun pause() { submit(Command(2)) }
    /** FIFO boundary: earlier events are deleted; later events form fresh records. */
    suspend fun deleteAllAndAwait() {
        val completion = CompletableDeferred<Unit>()
        synchronized(admissionLock) {
            check(queue.trySend(Command(4, true,
                generation = admission.generation, completion = completion)).isSuccess) {
                "숏폼 대기열이 가득 찼습니다. 삭제하지 않았습니다. 잠시 후 다시 시도해 주세요."
            }
        }
        // Accepted deletion belongs to the process worker, not to the caller's screen.
        completion.await()
    }
    fun clear() { submit(Command(4,true)) }
}
