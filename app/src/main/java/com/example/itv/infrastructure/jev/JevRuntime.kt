package com.example.itv.infrastructure.jev

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.example.itv.data.local.ItvDatabase
import com.example.itv.domain.jev.*
import com.example.itv.domain.records.ShortformApp
import com.example.itv.infrastructure.coaching.CoachingConsentStore
import com.example.itv.infrastructure.observation.SwipeDynamicsTracker
import com.example.itv.infrastructure.observation.accessibility.WritingGuard
import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID

object JevObservationHub {
    @Volatile var foregroundPackage: String?=null
        private set
    @Volatile var checkedAt: Long=0
        private set
    fun foreground(pkg: String?) { foregroundPackage=pkg;checkedAt=SystemClock.elapsedRealtime() }
}
data class JevLiveState(val status: String="Jev 꺼짐",val context: JevContext?=null,val probability: Double?=null,val pending: Boolean=false)

/** Main-thread coordinator. Network/Room/Keystore work is delegated to IO implementations. */
class JevRuntime private constructor(context: Context) {
    private val app=context.applicationContext
    val repository=JevRepository.get(app)
    val store=repository.store
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val tracker=JevSessionTracker { UUID.randomUUID().toString() }
    private val mutableState=MutableStateFlow(JevLiveState())
    val state: StateFlow<JevLiveState> = mutableState.asStateFlow()
    private data class Attempt(var at: Long=0,var total: Long=0,var asked: Boolean=false,var suppressedSession: String?=null,
                               var deferUntil: Long=0,var lastSession: String?=null)
    private val controls=mutableMapOf<String,Attempt>()
    private data class Approval(val trial: JevTrial,val settings: JevSettings,val captured: JevContext,val requestedAt: Long)
    private var approval: Approval?=null
    private var displayedApproval: Approval?=null
    private var active: JevTrial?=null
    private var displayReceipt: Job?=null
    private var inFlight: Job?=null
    private var safeSince: Long?=null
    private var savedAt=0L
    private var loggedAt=0L
    private var loggedReason=""
    val missionActiveState=MutableStateFlow(false)
    var lastObservedContext: JevContext?=null
        private set
    private val persistMutex=kotlinx.coroutines.sync.Mutex()
    private var revision=-1L
    private var loaded=false
    private var attached=false
    private var bedtimeMinute: Int?=null
    private val bootId=runCatching { Settings.Global.getInt(app.contentResolver,Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
    val enabled: Boolean get()=JevFeature.available() && store.settings.value.enabled
    init {
        scope.launch {
            withContext(Dispatchers.IO) {
                val json=runCatching { JSONObject(store.readControl("runtime") ?: "{}") }.getOrDefault(JSONObject())
                withContext(Dispatchers.Main.immediate) {
                    for(pkg in listOf(JevSettings.INSTAGRAM,JevSettings.YOUTUBE)) {
                        val a=json.optJSONObject(pkg) ?: continue
                        tracker.restore(pkg,a.optString("session",UUID.randomUUID().toString()),a.optLong("total"),a.optBoolean("entered"))
                        val sameBoot=bootId>=0 && json.optInt("boot",-2)==bootId
                        controls[pkg]=Attempt(if(sameBoot)a.optLong("at") else SystemClock.elapsedRealtime(),a.optLong("attemptTotal"),
                            a.optBoolean("asked"),a.optString("suppressed").takeIf { it.isNotEmpty() },
                            if(sameBoot)a.optLong("deferUntil") else if(a.optLong("deferUntil")>0)Long.MAX_VALUE else 0,
                            a.optString("attemptSession",a.optString("session")))
                    }
                    loaded=true
                }
            }
        }
        scope.launch {
            ItvDatabase.get(app).dao().observeHomeGoals("demo-guest").collect { goals ->
                bedtimeMinute=goals.firstOrNull { it.enabled && it.goalType=="BEDTIME" }?.localMinuteOfDay
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { ShortformPersonalStatsStore.refreshBaselineCache(app) }
                delay(60_000L)
            }
        }
    }
    fun start() { attached=true }
    fun stop() {
        attached=false
        missionActiveState.value=false
        approval?.let { suppress(it.trial,"MONITOR_STOPPED") };approval=null
        cancelled("MONITOR_STOPPED")
        persist()
    }
    private fun bedtime(settings: JevSettings): Int? {
        if(!settings.bedtimeEnabled)return null
        val minute=bedtimeMinute ?: return null
        val now=ZonedDateTime.now()
        var start=now.toLocalDate().atStartOfDay(now.zone).plusMinutes(minute.toLong())
        if(now.isBefore(start))start=start.minusDays(1)
        val elapsed=Duration.between(start,now).toMinutes()
        return elapsed.takeIf { it in 0 until settings.bedtimeWindowMinutes.toLong() }?.toInt()
    }
    fun enrichContext(base: JevContext, nowElapsed: Long = SystemClock.elapsedRealtime()): JevContext {
        val personal = ShortformPersonalStatsStore.quickContextMetrics(
            context = app,
            pkg = base.packageName,
            currentSessionMs = base.sessionMs,
            currentContinuousMs = base.continuousMs
        )
        val shortformApp = if (base.packageName == JevSettings.INSTAGRAM) ShortformApp.INSTAGRAM else ShortformApp.YOUTUBE
        val swipeSnap = SwipeDynamicsTracker.snapshot(shortformApp, nowElapsed, base.observedAt)
        return base.copy(
            sessionVsAvgRatio = personal.sessionVsAvgRatio,
            todayVs7dRatio = personal.todayVs7dRatio,
            effectiveSpm1m = swipeSnap.effectiveSpm1m.takeIf { swipeSnap.totalSwipesInRun > 0 || it > 0.0 },
            swipeAccel1m = swipeSnap.effectiveAccel1m.takeIf { swipeSnap.totalSwipesInRun > 0 || it != 0.0 },
            fastSwipeStreak = swipeSnap.currentFastStreak
        )
    }
    fun tick(unlocked: Boolean, missionActive: Boolean, offerVisible: Boolean) {
        missionActiveState.value=missionActive
        if(!enabled || !store.ready || !loaded)return
        val s=store.settings.value
        val now=SystemClock.elapsedRealtime()
        if(revision!=s.revision) {
            approval?.let { suppress(it.trial,"SETTINGS_CHANGED") };approval=null
            if(revision!=-1L) {
                for(c in controls.values) { c.asked=false;c.at=0;c.total=0 }
            }
            revision=s.revision;safeSince=null
        }
        val pkg=if(!unlocked)"LOCKED" else JevObservationHub.foregroundPackage.takeIf { now-JevObservationHub.checkedAt in 0..1500 }
        val screen=when(pkg) {
            JevSettings.INSTAGRAM -> ReelsProbe.screenState()
            JevSettings.YOUTUBE -> YoutubeProbe.screenState()
            else -> 0
        }
        val guard=WritingGuard.blockReason()
        val confirmed=pkg?.takeIf { screen==1 && guard==null && unlocked && s.targets(it) }
        val effectivePkg=if(guard!=null && pkg!="com.example.itv")"GUARD_BLOCKED" else pkg
        tracker.observe(now,effectivePkg,confirmed)
        val sameAppSafe=pkg!=null && s.targets(pkg) && guard==null && unlocked && screen>=0
        val c=pkg?.takeIf(s::targets)?.let { tracker.context(it,now,System.currentTimeMillis(),bedtime(s)) }
            ?.let { enrichContext(it, now) }
            ?.let { if(confirmed==pkg)it else it.copy(quality="INSUFFICIENT") }
        if(c?.quality=="VALID")lastObservedContext=c
        if(!unlocked || guard!=null || pkg==null || !s.targets(pkg))safeSince=null else if((confirmed!=null || sameAppSafe) && safeSince==null)safeSince=now
        val currentApproval=approval
        val approvalContext=c?.let {
            if(sameAppSafe && currentApproval!=null && it.packageName==currentApproval.captured.packageName && it.sessionId==currentApproval.captured.sessionId)
                it.copy(quality="VALID")
            else it
        }
        if(currentApproval!=null && !JevCandidatePolicy.resultIsCurrent(currentApproval.settings.revision,s,currentApproval.captured,approvalContext,now-currentApproval.requestedAt)) {
            suppress(currentApproval.trial,"STALE_CONTEXT_OR_RESULT");approval=null
        }
        val control=c?.let { controls.getOrPut(it.packageName){Attempt()} }
        if(c!=null && control!=null && control.lastSession!=c.sessionId) {
            control.lastSession=c.sessionId;control.asked=false;control.at=0;control.total=0
        }
        if(control!=null && control.deferUntil in 1..now)control.deferUntil=0
        val reasons=if(c!=null)JevCandidatePolicy.reasons(c,s) else emptyList()
        val block=when {
            !s.collectionConsent -> "외부 전송 동의 필요"
            !store.tokenConfigured.value -> "테스트 토큰 필요"
            !CoachingConsentStore.granted(app) -> "기존 사용량 수집 동의 필요"
            !unlocked -> "화면 꺼짐·잠금"
            guard!=null -> guard
            c==null || c.quality!="VALID" -> "확인된 숏폼 관측 대기"
            missionActive -> "미션 진행·완료 안내 대기"
            offerVisible -> "개입 표시 중"
            control?.suppressedSession==c.sessionId -> "현재 사용 구간 재제안 중단"
            (control?.deferUntil ?: 0)>now -> if(control?.deferUntil==Long.MAX_VALUE)"재부팅으로 보류 시각 확인 불가 · 상태 초기화 필요" else "나중에 보류 중"
            reasons.isEmpty() -> "호출 조건 미충족"
            else -> null
        }
        val result=approval
        mutableState.value=JevLiveState(block ?: if(inFlight?.isActive==true)"Jev 응답 대기" else if(result!=null)"확률 통과 · 다음 숏폼 전환 대기" else "호출 조건 확인",
            c,mutableState.value.probability,inFlight?.isActive==true)
        if(c!=null && s.collectionConsent && store.tokenConfigured.value && block!=null &&
            (block!=loggedReason || now-loggedAt>60_000)) {
            loggedReason=block;loggedAt=now
            scope.launch { runCatching { repository.observation(c,s,block) }.onFailure { store.notice.value="상황 기록 저장 실패" } }
        }
        if(block==null && c!=null && control!=null && approval==null && inFlight?.isActive!=true &&
            (safeSince?.let { now-it>=5000 }==true)) {
            val eligible=!control.asked || (now-control.at>=s.reevaluateSeconds*1000L &&
                c.sessionMs-control.total>=s.additionalViewingSeconds*1000L)
            if(eligible) {
                control.asked=true;control.at=now;control.total=c.sessionMs
                persist()
                val requestedAt=now
                inFlight=scope.launch {
                    try {
                        val answer=repository.evaluate(c,s,"LIVE",reasons.joinToString("|"))
                        mutableState.value=mutableState.value.copy(probability=answer.probability)
                        val doneAt=SystemClock.elapsedRealtime()
                        val sameForeground=JevObservationHub.foregroundPackage==c.packageName && doneAt-JevObservationHub.checkedAt in 0..1500
                        val fresh=(state.value.context ?: c.takeIf { sameForeground })?.let {
                            if(sameForeground && it.packageName==c.packageName && it.sessionId==c.sessionId) it.copy(quality="VALID") else it
                        }
                        when {
                            !attached || !enabled || !JevCandidatePolicy.resultIsCurrent(s.revision,store.settings.value,c,fresh,doneAt-requestedAt) ->
                                suppress(answer.trial,"STALE_CONTEXT_OR_SETTINGS")
                            !JevCandidatePolicy.passes(answer.probability,s,c) -> suppress(answer.trial,"BELOW_PROBABILITY_THRESHOLD")
                            !s.actualIntervention -> suppress(answer.trial,"OBSERVE_ONLY")
                            else -> approval=Approval(answer.trial,s,c,requestedAt)
                        }
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) { mutableState.value=mutableState.value.copy(status="Jev 요청 보류 · "+(e.message ?: "오류"));store.notice.value=e.message }
                }
            }
        }
        if(now-savedAt>=5000)persist()
    }
    /** Called when approved and ready to display (on transition or when navigating within the same app). */
    fun canShow(transition: Boolean = true): Boolean {
        val a=approval ?: return false
        val s=store.settings.value
        val now=SystemClock.elapsedRealtime()
        val pkg=a.captured.packageName
        val control=controls[pkg]
        val probe=when(pkg) {
            JevSettings.INSTAGRAM -> ReelsProbe.screenState()
            JevSettings.YOUTUBE -> YoutubeProbe.screenState()
            else -> 0
        }
        val sameForeground=JevObservationHub.foregroundPackage==pkg && now-JevObservationHub.checkedAt in 0..1500
        val currentContext=(state.value.context ?: a.captured.takeIf { sameForeground })?.let {
            if(sameForeground && it.packageName==pkg && it.sessionId==a.captured.sessionId) it.copy(quality="VALID") else it
        }
        val readyForScreen = if (probe == 1) (transition || now - a.requestedAt >= 15_000L) else (probe == 0)
        return attached && enabled && s.collectionConsent && s.actualIntervention && store.tokenConfigured.value &&
            CoachingConsentStore.granted(app) && !missionActiveState.value && active==null &&
            sameForeground && readyForScreen &&
            control?.suppressedSession!=a.captured.sessionId && (control?.deferUntil ?: 0)<=now &&
            WritingGuard.blockReason()==null && safeSince?.let { now-it>=5000 }==true &&
            JevCandidatePolicy.resultIsCurrent(a.settings.revision,s,a.captured,currentContext,now-a.requestedAt)
    }
    fun approvedId(): String? = approval?.trial?.id
    fun approvedPackage(): String? = approval?.captured?.packageName
    fun hasActiveOffer() = active != null
    fun canKeepDisplayed(): Boolean {
        val displayed = displayedApproval ?: return false
        val now = SystemClock.elapsedRealtime()
        val sameForeground = JevObservationHub.foregroundPackage == displayed.captured.packageName &&
            now - JevObservationHub.checkedAt in 0..1500
        val currentContext = (state.value.context ?: displayed.captured.takeIf { sameForeground })?.let {
            if (sameForeground && it.packageName == displayed.captured.packageName && it.sessionId == displayed.captured.sessionId)
                it.copy(quality = "VALID")
            else it
        }
        return attached && enabled && store.tokenConfigured.value &&
            JevCandidatePolicy.canContinueDisplayed(displayed.settings.revision,store.settings.value,
                displayed.captured,currentContext)
    }
    fun shown() {
        val a=approval ?: return
        active=a.trial;displayedApproval=a;approval=null
        displayReceipt=scope.launch {
            runCatching { repository.displayed(a.trial.id) }.onFailure { store.notice.value="표시 기록 저장 실패 · "+it.message }
        }
    }
    fun displayFailed() { approval?.let { suppress(it.trial,"DISPLAY_FAILED") };approval=null }
    fun respond(action: String) {
        val trial=active ?: return
        active=null
        val pkg=JSONObject(trial.payload).optString("package_name")
        val control=controls.getOrPut(pkg){Attempt()}
        val session=JSONObject(trial.payload).optString("session_id")
        if(action in setOf("LATER","NO_RESPONSE")) control.deferUntil=SystemClock.elapsedRealtime()+store.settings.value.laterSeconds*1000L
        else if(action in setOf("REST","DISMISS"))control.suppressedSession=session
        persist()
        val receipt=displayReceipt
        scope.launch {
            receipt?.join()
            runCatching { repository.event(trial,"response",JSONObject().put("action",action)) }
                .onFailure { store.notice.value="응답 기록 저장 실패" }
        }
    }
    fun cancelled(reason: String) {
        displayedApproval=null
        val trial=active ?: return
        active=null
        val receipt=displayReceipt
        scope.launch {
            receipt?.join()
            runCatching { repository.event(trial,"response",JSONObject().put("action","CANCELLED").put("reason",reason)) }
                .onFailure { store.notice.value="중단 기록 저장 실패" }
        }
    }
    fun recordMission(decisionId: String,kind: String) {
        if(!JevFeature.available())return
        val receipt=displayReceipt
        scope.launch {
            receipt?.join()
            runCatching {
                val trial=withContext(Dispatchers.IO) { store.dao.trial(decisionId) } ?: return@runCatching
                repository.event(trial,"mission",JSONObject().put("reason",kind))
            }.onFailure { store.notice.value="미션 기록 저장 실패" }
        }
    }
    private fun suppress(trial: JevTrial,reason: String) {
        scope.launch { runCatching { repository.event(trial,"suppressed",JSONObject().put("reason",reason)) }
            .onFailure { store.notice.value="보류 기록 저장 실패" } }
    }
    private fun persist() {
        savedAt=SystemClock.elapsedRealtime()
        val json=JSONObject().put("boot",bootId)
        for((pkg,frame) in tracker.states) {
            val c=controls[pkg] ?: Attempt()
            json.put(pkg,JSONObject().put("session",frame.id).put("total",frame.totalMs).put("entered",frame.entered)
                .put("at",c.at).put("attemptTotal",c.total).put("asked",c.asked)
                .put("suppressed",c.suppressedSession ?: "").put("deferUntil",c.deferUntil).put("attemptSession",c.lastSession ?: frame.id))
        }
        scope.launch {
            persistMutex.lock()
            try { runCatching { store.writeControl("runtime",json.toString()) }.onFailure { store.notice.value="관측 상태 저장 실패" } }
            finally { persistMutex.unlock() }
        }
    }
    fun sessionState(pkg: String) = tracker.states[pkg]
    /** Explicit operator action for a test run, never automatic on restart or settings edits. */
    fun resetTestObservation() {
        approval?.let { suppress(it.trial,"OPERATOR_RESET") };approval=null
        tracker.states.clear();controls.clear();safeSince=null;lastObservedContext=null;persist()
        mutableState.value=JevLiveState("테스트 관측 상태 초기화 · 재관측 필요")
    }
    companion object {
        @Volatile private var instance: JevRuntime?=null
        fun get(context: Context)=instance ?: synchronized(this) { instance ?: JevRuntime(context.applicationContext).also { instance=it } }
    }
}
