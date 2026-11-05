package com.example.itv.infrastructure.jev

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.example.itv.BuildConfig
import com.example.itv.domain.jev.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class RelayFailure(val code: String, val httpStatus: Int=0): Exception(code)
data class JevAnswer(val trial: JevTrial, val probability: Double)
internal fun JevContext.json() = JSONObject().put("session_id",sessionId).put("package_name",packageName)
    .put("session_minutes",sessionMs/60000.0).put("continuous_minutes",continuousMs/60000.0)
    .put("returns_10m",returns10m).put("returns_observed_seconds",returnsObservedSeconds)
    .put("bedtime_goal_enabled",bedtimePassedMinutes!=null)
    .put("bedtime_passed_minutes",bedtimePassedMinutes ?: JSONObject.NULL)
    .put("observed_at",Instant.ofEpochMilli(observedAt).toString()).put("quality",quality)

class JevRepository private constructor(context: Context) {
    val store=JevStore.get(context)
    private val requestMutex=Mutex()
    private val eventMutex=Mutex()
    private val flushMutex=Mutex()
    init {
        store.scope.launch {
            while(isActive) {
                if(store.ready && store.settings.value.collectionConsent && store.tokenConfigured.value) flush()
                delay(5000)
            }
        }
    }
    private fun requireConsent() {
        check(JevFeature.available()) { "공동 테스트 빌드에서만 사용할 수 있습니다." }
        check(store.settings.value.collectionConsent) { "테스트 데이터 외부 전송 안내에 동의해 주세요." }
    }
    private fun call(method: String, path: String, body: String?=null): JSONObject {
        requireConsent()
        val connection=URL(BASE+path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod=method;connection.connectTimeout=10_000;connection.readTimeout=12_000
            connection.instanceFollowRedirects=false
            connection.setRequestProperty("Authorization","Bearer "+store.token())
            connection.setRequestProperty("Accept","application/json")
            if(body!=null) {
                connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status=connection.responseCode
            val stream=if(status in 200..299)connection.inputStream else connection.errorStream
            val bytes=stream?.use { input ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(1024)
                while(output.size()<=16_384) {
                    val count=input.read(buffer,0,minOf(buffer.size,16_385-output.size()))
                    if(count<0)break
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            if(bytes.size>16_384)throw RelayFailure("RESPONSE_TOO_LARGE",status)
            val json=try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch(_:Exception) { throw RelayFailure("INVALID_SERVER_RESPONSE",status) }
            if(status !in 200..299) throw RelayFailure(json.optString("error","HTTP_$status"),status)
            return json
        } finally { connection.disconnect() }
    }
    suspend fun connectionStatus(): String=withContext(Dispatchers.IO) {
        val result=call("GET","/api/status")
        "서버 연결됨 · Jev "+if(result.optBoolean("jev_enabled"))"허용 · 모델 "+result.optString("model") else "서버에서 중단됨"
    }
    suspend fun evaluate(c: JevContext,settings: JevSettings,source: String,reason: String,
                         requestId: String=UUID.randomUUID().toString()): JevAnswer = requestMutex.withLock {
        withContext(Dispatchers.IO) {
            requireConsent();settings.validated()
            check(source in setOf("LIVE","MANUAL"))
            check(settings.revision==store.settings.value.revision) { "설정이 변경되어 요청을 취소했습니다." }
            val day=LocalDate.now(ZoneOffset.UTC).toString()
            val key="daily_${settings.testerId}_$day"
            val count=store.readControl(key)?.toIntOrNull() ?: 0
            check(count<settings.dailyLimit) { "이 기기의 일일 호출 한도에 도달했습니다." }
            val payload=c.json().put("protocol_version",2).put("id",requestId).put("tester_id",settings.testerId)
                .put("source",source).put("trigger_reason",reason).put("settings",settings.json())
                .put("app_version",BuildConfig.VERSION_NAME)
            val trial=JevTrial(requestId,settings.testerId,source,payload.toString(),System.currentTimeMillis())
            store.dao.saveTrial(trial)
            store.writeControl(key,(count+1).toString())
            execute(trial)
        }
    }
    /** Same ID and same frozen payload. Safe status lookup; the relay will not repeat an upstream call. */
    suspend fun retryTrial(id: String): JevAnswer=requestMutex.withLock {
        withContext(Dispatchers.IO) {
            requireConsent()
            execute(requireNotNull(store.dao.trial(id)) { "요청 기록이 없습니다." })
        }
    }
    private suspend fun execute(trial: JevTrial): JevAnswer {
        val started=SystemClock.elapsedRealtime()
        try {
            val result=call("POST","/api/jev/evaluate",trial.payload)
            val state=result.optString("status")
            val p=result.optDouble("probability",Double.NaN)
            if(state!="SUCCEEDED" || !p.isFinite() || p !in 0.0..1.0) {
                val error=result.optString("error",if(state=="PENDING")"REQUEST_PENDING" else "INVALID_JEV_RESPONSE")
                store.dao.saveTrial(trial.copy(state=state.ifEmpty { "FAILED" },error=error,latencyMs=SystemClock.elapsedRealtime()-started))
                throw RelayFailure(error)
            }
            val saved=trial.copy(state="SUCCEEDED",probability=p,model=result.optString("model"),
                inputTokens=if(result.isNull("input_tokens"))null else result.optLong("input_tokens"),
                latencyMs=SystemClock.elapsedRealtime()-started,error=null)
            store.dao.saveTrial(saved)
            return JevAnswer(saved,p)
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            if(store.dao.trial(trial.id)?.state=="PENDING")
                store.dao.saveTrial(trial.copy(state="UNKNOWN",error=(e as? RelayFailure)?.code ?: "NETWORK_OR_TOKEN_ERROR",latencyMs=SystemClock.elapsedRealtime()-started))
            throw e
        }
    }
    private suspend fun enqueue(parent: String,tester: String,source: String,type: String,data: JSONObject,operationId: String=UUID.randomUUID().toString()) {
        check(store.dao.count()<2000) { "전송 대기열이 가득 찼습니다. 기록 화면에서 확인해 주세요." }
        val payload=JSONObject().put("protocol_version",2).put("event_id",operationId).put("intervention_id",parent)
            .put("tester_id",tester).put("source",source).put("event_type",type)
            .put("occurred_at",Instant.now().toString()).put("data",data)
        store.dao.enqueue(JevOutbox(eventId=operationId,payload=payload.toString()))
    }
    suspend fun event(trial: JevTrial,type: String,data: JSONObject) = eventMutex.withLock {
        withContext(Dispatchers.IO) {
            requireConsent()
            store.db.withTransaction { enqueue(trial.id,trial.testerId,trial.source,type,data) }
        }
    }
    suspend fun observation(c: JevContext,s: JevSettings,reason: String) = eventMutex.withLock {
        withContext(Dispatchers.IO) {
            requireConsent()
            store.db.withTransaction { enqueue(UUID.randomUUID().toString(),s.testerId,"LIVE","candidate",
                JSONObject().put("reason",reason).put("context",c.json()).put("settings",s.json())) }
        }
    }
    suspend fun displayed(id: String) = eventMutex.withLock {
        withContext(Dispatchers.IO) {
            requireConsent()
            store.db.withTransaction {
                val trial=requireNotNull(store.dao.trial(id))
                if(trial.displayedAt!=null)return@withTransaction
                store.dao.saveTrial(trial.copy(displayedAt=System.currentTimeMillis(),feedbackStatus="PENDING"))
                enqueue(id,trial.testerId,trial.source,"displayed",JSONObject(),"$id:displayed")
            }
        }
    }
    suspend fun feedback(id: String,score: Int?) = eventMutex.withLock {
        withContext(Dispatchers.IO) {
            requireConsent()
            require(score==null || score in 0..10)
            store.db.withTransaction {
                val trial=requireNotNull(store.dao.trial(id))
                check(trial.displayedAt!=null) { "실제 표시된 개입만 평가합니다." }
                if(trial.feedbackStatus!="PENDING")return@withTransaction
                val status=if(score==null)"SKIPPED" else "SUBMITTED"
                enqueue(id,trial.testerId,trial.source,"feedback",
                    JSONObject().put("score",score ?: JSONObject.NULL).put("feedback_status",status),"$id:feedback")
                store.dao.saveTrial(trial.copy(feedbackStatus=status,feedbackScore=score))
            }
        }
    }
    suspend fun flush() = flushMutex.withLock { withContext(Dispatchers.IO) {
        // One process worker only. Preserve display -> response -> feedback ordering across restarts.
        repeat(20) {
            val head=store.dao.head() ?: return@withContext
            if(head.retryAt>System.currentTimeMillis())return@withContext
            try { call("POST","/api/events",head.payload);store.dao.remove(head) }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                val http=(e as? RelayFailure)?.httpStatus ?: 0
                val permanent=http in listOf(400,403,404,415) || (http==409 && (e as? RelayFailure)?.code=="EVENT_ID_CONFLICT")
                val delayMs=(5000L shl head.attempts.coerceAtMost(6)).coerceAtMost(300_000)
                store.dao.update(head.copy(attempts=head.attempts+1,retryAt=System.currentTimeMillis()+delayMs,
                    error=(e as? RelayFailure)?.code ?: "NETWORK_OR_TOKEN_ERROR",permanent=permanent))
                return@withContext
            }
        }
    }
    }
    companion object {
        const val BASE="https://itv-jev-relay.ehdrbsdl10042.workers.dev"
        @Volatile private var instance: JevRepository?=null
        fun get(context: Context): JevRepository=instance ?: synchronized(this) {
            instance ?: JevRepository(context.applicationContext).also { instance=it }
        }
    }
}
