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
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

object JevObservationHub {
    val transientSystemPackages = setOf(
        "com.samsung.android.app.smartcapture",
        "com.samsung.android.capture",
        "com.sec.android.app.quicktool",
        "com.android.systemui",
        "com.google.android.markup"
    )

    fun isTransientSystemPackage(pkg: String?): Boolean {
        return pkg != null && pkg in transientSystemPackages
    }

    @Volatile var foregroundPackage: String?=null
        private set
    @Volatile var checkedAt: Long=0
        private set
    fun foreground(pkg: String?) {
        if (isTransientSystemPackage(pkg)) {
            checkedAt = SystemClock.elapsedRealtime()
            return
        }
        val changed = foregroundPackage != pkg
        foregroundPackage = pkg
        checkedAt = SystemClock.elapsedRealtime()
        if (changed) {
            com.example.itv.infrastructure.observation.LightAndBrightnessTracker.notifyAppSwitched()
        }
    }
}
data class JevLiveState(
    val status: String="Jev 꺼짐",
    val context: JevContext?=null,
    val probability: Double?=null,
    val pending: Boolean=false,
    val dynamicIntervalSeconds: Int?=null,
    val lastProbabilityGap: Double?=null,
    val nextEvaluationInSeconds: Long?=null,
    val additionalViewingNeededSeconds: Long?=null
)

/** Main-thread coordinator. Network/Room/Keystore work is delegated to IO implementations. */
class JevRuntime private constructor(context: Context) {
    private val appContext=context.applicationContext
    val repository=JevRepository.get(appContext)
    val store=repository.store
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val tracker=JevSessionTracker { UUID.randomUUID().toString() }
    private val mutableState=MutableStateFlow(JevLiveState())
    val state: StateFlow<JevLiveState> = mutableState.asStateFlow()
    private data class Attempt(var at: Long=0,var total: Long=0,var asked: Boolean=false,var suppressedSession: String?=null,
                               var deferUntil: Long=0,var lastSession: String?=null,var dynamicIntervalSeconds: Int=300,var lastGap: Double?=null)
    private val controls=mutableMapOf<String,Attempt>()
    private val lastActiveWallMs=mutableMapOf<String,Long>()
    private val lastActiveDate=mutableMapOf<String,String>()
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
    private val bootId=runCatching { Settings.Global.getInt(appContext.contentResolver,Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
    val enabled: Boolean get()=JevFeature.available() && store.settings.value.enabled
    init {
        scope.launch {
            withContext(Dispatchers.IO) {
                val json=runCatching { JSONObject(store.readControl("runtime") ?: "{}") }.getOrDefault(JSONObject())
                withContext(Dispatchers.Main.immediate) {
                    val nowWall=System.currentTimeMillis()
                    val todayStr=LocalDate.now().toString()
                    for(pkg in listOf(JevSettings.INSTAGRAM,JevSettings.YOUTUBE)) {
                        val a=json.optJSONObject(pkg) ?: continue
                        val savedDate=a.optString("lastActiveDate","")
                        val savedWall=a.optLong("lastActiveWallMs",0L)
                        val activeAcrossMidnight=savedWall>0L && (nowWall-savedWall) in 0L until 15_000L
                        val sessionStillRecent=savedWall>0L && (nowWall-savedWall) in 0L until 600_000L &&
                            (savedDate==todayStr || activeAcrossMidnight)
                        if(sessionStillRecent) {
                            lastActiveDate[pkg]=todayStr
                            lastActiveWallMs[pkg]=savedWall
                            tracker.restore(pkg,a.optString("session",UUID.randomUUID().toString()),a.optLong("total"),a.optBoolean("entered"))
                            val sameBoot=bootId>=0 && json.optInt("boot",-2)==bootId
                            controls[pkg]=Attempt(if(sameBoot)a.optLong("at") else SystemClock.elapsedRealtime(),a.optLong("attemptTotal"),
                                a.optBoolean("asked"),a.optString("suppressed").takeIf { it.isNotEmpty() },
                                if(sameBoot)a.optLong("deferUntil") else if(a.optLong("deferUntil")>0)Long.MAX_VALUE else 0,
                                a.optString("attemptSession",a.optString("session")),
                                a.optInt("dynInterval", 300),
                                if(a.has("lastGap")) a.optDouble("lastGap") else null)
                        } else {
                            tracker.resetSession(pkg)
                        }
                    }
                    loaded=true
                    persist()
                }
            }
        }
        scope.launch {
            ItvDatabase.get(appContext).dao().observeHomeGoals("demo-guest").collect { goals ->
                bedtimeMinute=goals.firstOrNull { it.enabled && it.goalType=="BEDTIME" }?.localMinuteOfDay
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { ShortformPersonalStatsStore.refreshBaselineCache(appContext) }
                delay(60.seconds)
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
            context = appContext,
            pkg = base.packageName,
            currentContinuousMs = base.continuousMs
        )
        val shortformApp = if (base.packageName == JevSettings.INSTAGRAM) ShortformApp.INSTAGRAM else ShortformApp.YOUTUBE
        val swipeSnap = SwipeDynamicsTracker.snapshot(shortformApp, nowElapsed, base.observedAt)
        val effSpm1m = swipeSnap.effectiveSpm1m.takeIf { swipeSnap.totalSwipesInRun > 0 || it > 0.0 }
        val effSessionSpm = swipeSnap.effectiveSessionSpm.takeIf { swipeSnap.totalSwipesInRun > 0 || it > 0.0 }
        val personalSpm = personal.personalAvgSpm?.takeIf { it > 0.0 }
        val spmRatio = if (effSpm1m != null && personalSpm != null && personalSpm >= 1.0) {
            kotlin.math.round((effSpm1m / personalSpm) * 100.0) / 100.0
        } else null
        val accel1m = swipeSnap.effectiveAccel1m.takeIf { swipeSnap.totalSwipesInRun > 0 || it != 0.0 }
        val streak = swipeSnap.currentFastStreak
        val overheated = (streak >= 3 && (effSpm1m ?: 0.0) >= 10.0) || ((effSpm1m ?: 0.0) >= 12.0 && (accel1m ?: 0.0) >= 3.0)
        return base.copy(
            personalAvgSessionMinutes = personal.avgSessionMinutes,
            sessionVsAvgRatio = personal.sessionVsAvgRatio,
            todayShortformMinutes = personal.todayShortformMinutes,
            avg7dDailyMinutes = personal.avg7dDailyMinutes,
            todayVs7dRatio = personal.todayVs7dRatio,
            effectiveSpm1m = effSpm1m,
            effectiveSessionSpm = effSessionSpm,
            personalAvgSpm = personalSpm,
            currentVsPersonalSpmRatio = spmRatio,
            swipeAccel1m = accel1m,
            fastSwipeStreak = streak,
            swipeOverheated = overheated
        )
    }
    fun tick(unlocked: Boolean, missionActive: Boolean, offerVisible: Boolean) {
        missionActiveState.value=missionActive
        if(!enabled || !store.ready || !loaded)return
        val s=store.settings.value
        val now=SystemClock.elapsedRealtime()
        val nowWall=System.currentTimeMillis()
        val todayStr=LocalDate.now().toString()
        if(revision!=s.revision) {
            approval?.let { suppress(it.trial,"SETTINGS_CHANGED") };approval=null
            if(revision!=-1L) {
                for(c in controls.values) { c.asked=false;c.at=0;c.total=0 }
            }
            revision=s.revision;safeSince=null
        }
        val pkg=if(!unlocked)"LOCKED" else JevObservationHub.foregroundPackage.takeIf { now-JevObservationHub.checkedAt in 0..1500 }
        for(targetPkg in listOf(JevSettings.INSTAGRAM,JevSettings.YOUTUBE)) {
            val prevDate=lastActiveDate[targetPkg]
            val prevWall=lastActiveWallMs[targetPkg] ?: 0L
            val currentlyActive=(unlocked && pkg==targetPkg) ||
                ((tracker.states[targetPkg]?.continuousMs ?: 0L)>0L) ||
                (prevWall>0L && (nowWall-prevWall) in 0L until 15_000L)
            val dateChangedWhileAway=prevDate!=null && prevDate!=todayStr && !currentlyActive
            val inactiveExpired=prevWall>0L && (nowWall-prevWall>=600_000L || nowWall<prevWall)
            if(dateChangedWhileAway || inactiveExpired) {
                tracker.resetSession(targetPkg)
                controls.remove(targetPkg)
                lastActiveDate.remove(targetPkg)
                lastActiveWallMs.remove(targetPkg)
                if(lastObservedContext?.packageName==targetPkg)lastObservedContext=null
            } else if(prevDate!=null && prevDate!=todayStr) {
                // Actively watching across 00:00 midnight: preserve the ongoing session without resetting
                lastActiveDate[targetPkg]=todayStr
            }
        }
        if(unlocked && pkg!=null && s.targets(pkg)) {
            lastActiveWallMs[pkg]=nowWall
            lastActiveDate[pkg]=todayStr
        }
        val screen=when(pkg) {
            JevSettings.INSTAGRAM -> ReelsProbe.screenState()
            JevSettings.YOUTUBE -> YoutubeProbe.screenState()
            else -> 0
        }
        val guard=WritingGuard.blockReason()
        val confirmed=pkg?.takeIf { screen==1 && guard==null && s.targets(it) }
        val effectivePkg=if(guard!=null && pkg!="com.example.itv")"GUARD_BLOCKED" else pkg
        tracker.observe(now,effectivePkg,confirmed)
        val sameAppSafe=pkg!=null && s.targets(pkg) && guard==null && unlocked && screen>=0
        val c=pkg?.takeIf(s::targets)?.let { tracker.context(it,now,nowWall,bedtime(s)) }
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
            control.dynamicIntervalSeconds=300;control.lastGap=null
        }
        if(control!=null && control.deferUntil in 1..now)control.deferUntil=0
        val reasons=if(c!=null)JevCandidatePolicy.reasons(c,s) else emptyList()
        val block=when {
            !s.collectionConsent -> "외부 전송 동의 필요"
            !store.tokenConfigured.value -> "테스트 토큰 필요"
            !CoachingConsentStore.granted(appContext) -> "기존 사용량 수집 동의 필요"
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
        val intervalSec = control?.dynamicIntervalSeconds ?: 300
        val nextEvalIn = if (control?.asked == true) maxOf(0L, (intervalSec * 1000L - (now - control.at)) / 1000L) else 0L
        val addViewingNeeded = if (control?.asked == true && c != null) maxOf(0L, (intervalSec * 1000L - (c.sessionMs - control.total)) / 1000L) else 0L
        mutableState.value=JevLiveState(
            status = block ?: if(inFlight?.isActive==true)"Jev 응답 대기" else if(result!=null)"확률 통과 · 다음 숏폼 전환 대기" else "호출 조건 확인",
            context = c,
            probability = mutableState.value.probability,
            pending = inFlight?.isActive==true,
            dynamicIntervalSeconds = control?.dynamicIntervalSeconds ?: 300,
            lastProbabilityGap = control?.lastGap,
            nextEvaluationInSeconds = nextEvalIn,
            additionalViewingNeededSeconds = addViewingNeeded
        )
        if(c!=null && s.collectionConsent && store.tokenConfigured.value && block!=null &&
            (block!=loggedReason || now-loggedAt>60_000)) {
            loggedReason=block;loggedAt=now
            scope.launch { runCatching { repository.observation(c,s,block) }.onFailure { store.notice.value="상황 기록 저장 실패" } }
        }
        if(block==null && c!=null && control!=null && approval==null && inFlight?.isActive!=true &&
            (safeSince?.let { now-it>=5000 }==true)) {
            val eligible=!control.asked || (now-control.at>=intervalSec*1000L &&
                c.sessionMs-control.total>=intervalSec*1000L)
            if(eligible) {
                control.asked=true;control.at=now;control.total=c.sessionMs
                persist()
                inFlight=scope.launch {
                    try {
                        val answer=repository.evaluate(c,s,"LIVE",reasons.joinToString("|"))
                        val effThreshold = JevCandidatePolicy.effectiveThreshold(s, c)
                        val dynamicSec = JevCandidatePolicy.calculateDynamicInterval(effThreshold, answer.probability)
                        val gap = (effThreshold - answer.probability).coerceAtLeast(0.0)
                        control.dynamicIntervalSeconds = dynamicSec
                        control.lastGap = gap
                        mutableState.value=mutableState.value.copy(
                            probability=answer.probability,
                            dynamicIntervalSeconds=dynamicSec,
                            lastProbabilityGap=gap
                        )
                        val doneAt=SystemClock.elapsedRealtime()
                        val sameForeground=JevObservationHub.foregroundPackage==c.packageName && doneAt-JevObservationHub.checkedAt in 0..1500
                        val fresh=(state.value.context ?: c.takeIf { sameForeground })?.let {
                            if(sameForeground && it.packageName==c.packageName && it.sessionId==c.sessionId) it.copy(quality="VALID") else it
                        }
                        when {
                            !attached || !enabled || !JevCandidatePolicy.resultIsCurrent(s.revision,store.settings.value,c,fresh,doneAt-now) ->
                                suppress(answer.trial,"STALE_CONTEXT_OR_SETTINGS")
                            !JevCandidatePolicy.passes(answer.probability,s,c) -> suppress(answer.trial,"BELOW_PROBABILITY_THRESHOLD")
                            !s.actualIntervention -> suppress(answer.trial,"OBSERVE_ONLY")
                            else -> approval=Approval(answer.trial,s,c,now)
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
            CoachingConsentStore.granted(appContext) && !missionActiveState.value && active==null &&
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
        val currentFg = JevObservationHub.foregroundPackage
        val sameForeground = (currentFg == displayed.captured.packageName || JevObservationHub.isTransientSystemPackage(currentFg)) &&
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

    fun displayBlockingReason(): String? {
        val s = store.settings.value
        val now = SystemClock.elapsedRealtime()

        if (com.example.itv.presentation.coaching.CoachingAutoStart.testModeEnabled(appContext)) {
            return "테스트 모드가 켜져 있어 Jev 대신 고정값 규칙으로 개입함 (설정에서 테스트 모드 끄기 필요)"
        }
        if (!com.example.itv.presentation.coaching.CoachingAutoStart.enabled(appContext)) {
            return "코칭 서비스가 꺼져 있음 (서비스 켜기 필요)"
        }
        if (!s.actualIntervention) {
            return "‘확률 통과 시 실제 개입’ 설정이 꺼져 있어 판단만 기록됨 (설정에서 켜기 필요)"
        }
        if (!enabled) {
            return "Jev 자동 관측·판단 설정이 꺼져 있음"
        }
        if (!android.provider.Settings.canDrawOverlays(appContext)) {
            return "‘다른 앱 위에 표시’ 권한이 허용되지 않음"
        }
        val a = approval
        if (a == null) {
            val lastReason = lastSuppressionReason
            return when (lastReason) {
                "OBSERVE_ONLY" -> "‘확률 통과 시 실제 개입’ 설정이 꺼져 있어 개입이 보류됨 (설정에서 켜기 필요)"
                "STALE_CONTEXT_OR_SETTINGS" -> "설정 변경 또는 앱 전환으로 이전 확률이 만료됨"
                "BELOW_PROBABILITY_THRESHOLD" -> "개입 기준 확률 미달"
                "MONITOR_STOPPED" -> "모니터링 서비스 중지로 승인 취소됨"
                else -> null
            }
        }
        val pkg = a.captured.packageName
        val control = controls[pkg]
        val probe = when (pkg) {
            JevSettings.INSTAGRAM -> ReelsProbe.screenState()
            JevSettings.YOUTUBE -> YoutubeProbe.screenState()
            else -> 0
        }
        val sameForeground = JevObservationHub.foregroundPackage == pkg && now - JevObservationHub.checkedAt in 0..1500
        if (!sameForeground) {
            return "현재 화면이 대상 앱(${if (pkg == JevSettings.YOUTUBE) "YouTube" else "Instagram"})이 아님"
        }
        if (control?.suppressedSession == a.captured.sessionId) {
            return "현재 사용 구간에서 이미 사용자가 응답하여 재개입 보류 중"
        }
        if ((control?.deferUntil ?: 0) > now) {
            val remainSec = ((control?.deferUntil ?: 0) - now) / 1000
            return "‘나중에’를 선택하여 일시 보류 중 (남은 시간: ${remainSec}초)"
        }
        val block = WritingGuard.blockReason()
        if (block != null) {
            return "글 작성/키보드 활성화로 개입 보류 ($block)"
        }
        val safeAt = safeSince
        if (safeAt == null || now - safeAt < 5000) {
            return "화면 안전 상태 확인 중 (최대 5초 대기)"
        }
        if (probe == 1 && (now - a.requestedAt < 15_000L)) {
            val remainSec = (15_000L - (now - a.requestedAt)) / 1000L
            return "다음 숏폼 전환 대기 중 (시청 방해를 줄이기 위해 다음 영상으로 넘기거나 ${remainSec}초 대기 필요)"
        }
        if (active != null) {
            return "이미 화면에 개입 캐릭터가 표시 중"
        }
        return null
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
        if(action in setOf("LATER","NO_RESPONSE")) control.deferUntil=SystemClock.elapsedRealtime()+com.example.itv.domain.coaching.TestCoachingPolicy.JEV_REMINDER_MS
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
    @Volatile var lastSuppressionReason: String? = null
        private set
    @Volatile var lastSuppressionAt: Long = 0L
        private set

    private fun suppress(trial: JevTrial,reason: String) {
        lastSuppressionReason = reason
        lastSuppressionAt = System.currentTimeMillis()
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    store.dao.saveTrial(trial.copy(state = "SUPPRESSED:$reason"))
                    repository.event(trial, "suppressed", JSONObject().put("reason", reason))
                }.onFailure { store.notice.value = "보류 기록 저장 실패" }
            }
        }
    }
    private fun persist() {
        savedAt=SystemClock.elapsedRealtime()
        val json=JSONObject().put("boot",bootId)
        for((pkg,frame) in tracker.states) {
            val c=controls[pkg] ?: Attempt()
            json.put(pkg,JSONObject().put("session",frame.id).put("total",frame.totalMs).put("entered",frame.entered)
                .put("lastActiveWallMs",lastActiveWallMs[pkg] ?: 0L).put("lastActiveDate",lastActiveDate[pkg] ?: "")
                .put("at",c.at).put("attemptTotal",c.total).put("asked",c.asked)
                .put("suppressed",c.suppressedSession ?: "").put("deferUntil",c.deferUntil).put("attemptSession",c.lastSession ?: frame.id)
                .put("dynInterval", c.dynamicIntervalSeconds).put("lastGap", c.lastGap ?: -1.0))
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
        tracker.states.clear();controls.clear();lastActiveWallMs.clear();lastActiveDate.clear();safeSince=null;lastObservedContext=null;persist()
        mutableState.value=JevLiveState("테스트 관측 상태 초기화 · 재관측 필요")
    }
    companion object {
        @Volatile private var instance: JevRuntime?=null
        fun get(context: Context)=instance ?: synchronized(this) { instance ?: JevRuntime(context.applicationContext).also { instance=it } }
    }
}
