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
        "com.android.systemui",
        "android",
        // Samsung Screenshot & Smart Capture
        "com.samsung.android.app.smartcapture",
        "com.sec.android.app.smartcapture",
        "com.samsung.android.smartcapture",
        "com.samsung.android.capture",
        "com.sec.android.app.quicktool",
        // Google Screenshot / Markup / Intelligence
        "com.google.android.markup",
        "com.google.android.as",
        // Sound & Volume overlays
        "com.samsung.android.soundalive",
        "com.sec.android.soundalive",
        "com.samsung.android.app.soundalive",
        "com.sec.android.app.soundalive",
        // Samsung Edge / Quick Tools / Gestures
        "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.sidegesturepad",
        "com.samsung.android.game.gametools",
        "com.sec.android.app.screenrecorder",
        "com.samsung.android.app.screenrecorder",
        // HoneyBoard / Clipboard overlay
        "com.samsung.android.honeyboard"
    )

    fun isTransientSystemPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        if (pkg in transientSystemPackages) return true
        return pkg.startsWith("com.samsung.android.smartcapture") ||
               pkg.startsWith("com.sec.android.app.smartcapture") ||
               (pkg.contains("capture") && (pkg.contains("samsung") || pkg.contains("android.systemui"))) ||
               pkg.contains("soundalive") ||
               pkg.contains("screenrecorder")
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
data class JevAppSuppressionInfo(
    val packageName: String,
    val sessionId: String?,
    val isSuppressed: Boolean,
    val isDeferred: Boolean,
    val isAsked: Boolean,
    val awayMs: Long,
    val sessionResetRemainingSeconds: Long?,
    val deferRemainingSeconds: Long?,
    val nextEvaluationInSeconds: Long?,
    val dynamicIntervalSeconds: Int,
    val statusText: String,
    val lastProbabilityGap: Double? = null
)

data class JevLiveState(
    val status: String = "Jev 꺼짐",
    val context: JevContext? = null,
    val probability: Double? = null,
    val pending: Boolean = false,
    val dynamicIntervalSeconds: Int? = null,
    val lastProbabilityGap: Double? = null,
    val nextEvaluationInSeconds: Long? = null,
    val additionalViewingNeededSeconds: Long? = null,
    val targetAppSuppression: Map<String, JevAppSuppressionInfo> = emptyMap()
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
    private data class Attempt(
        var at: Long=0,
        var total: Long=0,
        var asked: Boolean=false,
        var suppressedSession: String?=null,
        var deferUntil: Long=0,
        var lastSession: String?=null,
        var dynamicIntervalSeconds: Int=300,
        var lastGap: Double?=null,
        var lastOutcome: String?=null,
        var lastOutcomeAt: Long=0L,
        var lastOutcomeSwipes: Int=0,
        var resistanceStreak: Int=0
    )
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
    private var darkSince: Long?=null
    private var suddenDarkTriggeredSession: String?=null
    private var pendingTransferApproval: Approval?=null
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
                    val restorePkgs = (listOf(JevSettings.INSTAGRAM, JevSettings.YOUTUBE, "ALL") + json.keys().asSequence().toList()).distinct()
                    for(pkg in restorePkgs) {
                        val a=json.optJSONObject(pkg) ?: continue
                        val savedDate=a.optString("lastActiveDate","")
                        val savedWall=a.optLong("lastActiveWallMs",0L)
                        val activeAcrossMidnight=savedWall>0L && (nowWall-savedWall) in 0L until JevSessionTracker.INTERRUPTION_TOLERANCE_MS
                        val sessionStillRecent=savedWall>0L && (nowWall-savedWall) in 0L until JevSessionTracker.SESSION_TIMEOUT_MS &&
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
                                if(a.has("lastGap")) a.optDouble("lastGap") else null,
                                a.optString("lastOutcome").takeIf { it.isNotEmpty() },
                                0L,
                                a.optInt("lastOutcomeSwipes", 0),
                                a.optInt("resistanceStreak", 0))
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
    fun start() {
        attached = true
        com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(appContext).start()
    }
    fun stop() {
        attached = false
        missionActiveState.value = false
        approval?.let { suppress(it.trial, "MONITOR_STOPPED") }; approval = null
        cancelled("MONITOR_STOPPED")
        persist()
        com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(appContext).stop()
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
        val isAll = base.packageName == "ALL"
        val isShortform = base.packageName == JevSettings.INSTAGRAM || base.packageName == JevSettings.YOUTUBE
        val shortformApp = if (base.packageName == JevSettings.INSTAGRAM) ShortformApp.INSTAGRAM else ShortformApp.YOUTUBE
        val swipeSnap = if (isShortform) SwipeDynamicsTracker.snapshot(shortformApp, nowElapsed, base.observedAt) else null
        val effSpm1m = swipeSnap?.effectiveSpm1m?.takeIf { swipeSnap.totalSwipesInRun > 0 || it > 0.0 }
        val effSessionSpm = swipeSnap?.effectiveSessionSpm?.takeIf { swipeSnap.totalSwipesInRun > 0 || it > 0.0 }
        val personalSpm = if (isShortform) personal.personalAvgSpm?.takeIf { it > 0.0 } else null
        val spmRatio = if (effSpm1m != null && personalSpm != null && personalSpm >= 1.0) {
            kotlin.math.round((effSpm1m / personalSpm) * 100.0) / 100.0
        } else null
        val accel1m = swipeSnap?.effectiveAccel1m?.takeIf { swipeSnap.totalSwipesInRun > 0 || it != 0.0 }
        val streak = swipeSnap?.currentFastStreak ?: 0
        val overheated = isShortform && ((streak >= 3 && (effSpm1m ?: 0.0) >= 10.0) || ((effSpm1m ?: 0.0) >= 12.0 && (accel1m ?: 0.0) >= 3.0))

        val ambient = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(appContext).snapshot.value
        val isOccluded = ambient.isOccluded || (ambient.isNear && (ambient.proximityDistanceCm ?: 5f) <= 1.0f)
        val bedtimeDist = ambient.bedtimeDistanceMinutes ?: base.bedtimePassedMinutes

        val ctrl = controls[base.packageName] ?: if (isAll) controls.values.filter { it.lastOutcome != null }.maxByOrNull { it.lastOutcomeAt } else null
        val outcome = ctrl?.lastOutcome
        val outcomeSwipes = ctrl?.lastOutcomeSwipes ?: 0
        val currSwipes = swipeSnap?.totalSwipesInRun ?: 0
        val isPostActive = (outcome != null) && (currSwipes > outcomeSwipes || base.continuousMs >= 10_000L)
        val resistanceStreak = ctrl?.resistanceStreak ?: 0

        val includeLongform = base.packageName == JevSettings.YOUTUBE || isAll
        val lfMetrics = if (includeLongform) {
            com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.getJevLongformMetrics(base.observedAt)
        } else null

        val genSnap = com.example.itv.infrastructure.observation.GeneralAppTracker.snapshot.value
        val resolvedAppCategory = when {
            base.packageName == JevSettings.INSTAGRAM -> "SHORTFORM"
            base.packageName == JevSettings.YOUTUBE && lfMetrics?.youtubeWatchMode == "SHORTS" -> "SHORTFORM"
            base.packageName == JevSettings.YOUTUBE -> "LONGFORM_VIDEO"
            genSnap.category == com.example.itv.infrastructure.observation.InferredAppCategory.GAME -> "GAME"
            genSnap.category == com.example.itv.infrastructure.observation.InferredAppCategory.DOCUMENT -> "DOCUMENT"
            genSnap.category == com.example.itv.infrastructure.observation.InferredAppCategory.VIDEO_MEDIA -> "LONGFORM_VIDEO"
            genSnap.category == com.example.itv.infrastructure.observation.InferredAppCategory.VOICE_CALL -> "VOICE_CALL"
            genSnap.category == com.example.itv.infrastructure.observation.InferredAppCategory.WRITING -> "WRITING"
            else -> genSnap.category.name
        }
        val resolvedActivityPhase = when {
            isShortform -> if (overheated) "FAST_SWIPING" else "BROWSING"
            base.packageName == JevSettings.YOUTUBE && (lfMetrics?.currentVideoWatchMs ?: 0L) > 0L -> "VIDEO_PLAYING"
            genSnap.gamePhase != com.example.itv.infrastructure.observation.GamePhase.NONE -> genSnap.gamePhase.name
            genSnap.isKeyboardOrInputActive -> "TEXT_TYPING"
            genSnap.idleSeconds >= 15L -> "IDLE"
            else -> genSnap.inferredActivity.takeIf { it.isNotBlank() } ?: "ACTIVE"
        }
        val resolvedAudioPipeline = genSnap.audioPipeline.name
        val idleSec = genSnap.idleSeconds

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
            swipeOverheated = overheated,
            shortformShareOfPhoneRatio = personal.shortformShareOfPhoneRatio,
            ambientLux = ambient.lux,
            screenBrightness = ambient.screenBrightness,
            isNearOrOccluded = isOccluded,
            bedtimeDistanceMinutes = bedtimeDist,
            userEnvironmentState = ambient.userEnvironmentState.name,
            lastInterventionOutcome = outcome,
            postInterventionActivity = isPostActive,
            resistanceStreak = resistanceStreak,
            estimatedDayType = personal.estimatedDayType?.name,
            dayTypeReason = personal.dayTypeReason,
            longformWatchMs = lfMetrics?.totalLongformWatchMs ?: 0L,
            currentVideoWatchMs = lfMetrics?.currentVideoWatchMs ?: 0L,
            completedLongformCount = lfMetrics?.completedLongformCount ?: 0,
            youtubeWatchMode = lfMetrics?.youtubeWatchMode,
            appCategory = resolvedAppCategory,
            activityPhase = resolvedActivityPhase,
            audioPipelineKind = resolvedAudioPipeline,
            generalAppIdleSeconds = idleSec,
            consecutiveLongformChainCount = lfMetrics?.consecutiveChainCount ?: 0
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
        val trackedPkgs = (listOf(JevSettings.INSTAGRAM, JevSettings.YOUTUBE, "ALL") + tracker.states.keys + controls.keys).distinct()
        for(targetPkg in trackedPkgs) {
            val prevDate=lastActiveDate[targetPkg]
            val prevWall=lastActiveWallMs[targetPkg] ?: 0L
            val isTargetAppActive = unlocked && (pkg == targetPkg || (targetPkg == "ALL" && pkg != null && s.targets(pkg)))
            val currentlyActive = isTargetAppActive ||
                ((tracker.states[targetPkg]?.continuousMs ?: 0L)>0L) ||
                (prevWall>0L && (nowWall-prevWall) in 0L until JevSessionTracker.INTERRUPTION_TOLERANCE_MS)
            val dateChangedWhileAway=prevDate!=null && prevDate!=todayStr && !currentlyActive
            val inactiveExpired=prevWall>0L && (nowWall-prevWall>=JevSessionTracker.SESSION_TIMEOUT_MS || nowWall<prevWall)
            val screenOffRestExpired=!unlocked && prevWall>0L && (nowWall-prevWall>=JevSessionTracker.RECOGNIZED_REST_MS)
            if(dateChangedWhileAway || inactiveExpired || screenOffRestExpired) {
                tracker.resetSession(targetPkg)
                controls.remove(targetPkg)
                lastActiveDate.remove(targetPkg)
                lastActiveWallMs.remove(targetPkg)
                if(lastObservedContext?.packageName==targetPkg)lastObservedContext=null
                suddenDarkTriggeredSession = null
            } else if(prevDate!=null && prevDate!=todayStr) {
                // Actively watching across 00:00 midnight: preserve the ongoing session without resetting
                lastActiveDate[targetPkg]=todayStr
            }
        }
        if(unlocked && pkg!=null && s.targets(pkg)) {
            lastActiveWallMs[pkg]=nowWall
            lastActiveDate[pkg]=todayStr
            lastActiveWallMs["ALL"]=nowWall
            lastActiveDate["ALL"]=todayStr
        }
        val screen=when(pkg) {
            JevSettings.INSTAGRAM -> ReelsProbe.screenState()
            JevSettings.YOUTUBE -> YoutubeProbe.screenState()
            else -> 0
        }
        val guard=WritingGuard.blockReason()
        val confirmed=pkg?.takeIf { screen==1 && guard==null && s.targets(it) }
        val effectivePkg=if(guard!=null && pkg!=appContext.packageName && pkg!="com.example.itv")"GUARD_BLOCKED" else pkg
        tracker.observe(now,effectivePkg,confirmed)
        val sameAppSafe=pkg!=null && s.targets(pkg) && guard==null && unlocked && screen>=0
        val c=pkg?.takeIf(s::targets)?.let { tracker.context(it,now,nowWall,bedtime(s)) }
            ?.let { enrichContext(it, now) }
            ?.let { if(confirmed==pkg || sameAppSafe)it else it.copy(quality="INSUFFICIENT") }
        if(c?.quality=="VALID")lastObservedContext=c
        if(!unlocked || guard!=null || pkg==null || !s.targets(pkg))safeSince=null else if((confirmed!=null || sameAppSafe) && safeSince==null)safeSince=now
        val isDocOrMsg = JevSettings.isDocumentOrMessagingPackage(pkg) || (guard != null)
        val pendingTransfer = pendingTransferApproval
        if (c != null && s.targets(c.packageName)) {
            if (pendingTransfer != null && pendingTransfer.settings.revision == s.revision) {
                if (isDocOrMsg) {
                    approval = null
                } else if (c.quality == "VALID") {
                    approval = pendingTransfer.copy(captured = c, requestedAt = now)
                    pendingTransferApproval = null
                }
            } else if (approval != null && approval?.captured?.packageName != c.packageName && approval?.settings?.revision == s.revision) {
                if (isDocOrMsg) {
                    pendingTransferApproval = approval
                    approval = null
                } else if (c.quality == "VALID") {
                    approval = approval?.copy(captured = c, requestedAt = now)
                }
            }
        }
        val isYoutubeFg = pkg == JevSettings.YOUTUBE
        val isShortsNow = screen == 1 || (isYoutubeFg && com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments())
        val isLongformNow = isYoutubeFg && com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyWatchingLongform()

        val currentApproval=approval
        val approvalContext=c?.let {
            if(sameAppSafe && currentApproval!=null && (it.packageName==currentApproval.captured.packageName || currentApproval.captured.packageName=="ALL") && it.sessionId==currentApproval.captured.sessionId)
                it.copy(quality="VALID")
            else it
        }
        if(currentApproval!=null) {
            if (sameAppSafe && approvalContext?.quality == "VALID" && (isShortsNow || isLongformNow)) {
                // 숏폼 또는 롱폼 영상 시청 중에는 '시청 중 개입 금지' 원칙에 따라 영상 종료/전환 시점까지 승인을 만료시키지 않고 유지함
                approval = currentApproval.copy(requestedAt = now)
            } else if (!JevCandidatePolicy.resultIsCurrent(currentApproval.settings.revision,s,currentApproval.captured,approvalContext,now-currentApproval.requestedAt)) {
                suppress(currentApproval.trial,"STALE_CONTEXT_OR_RESULT");approval=null
            }
        }
        val control=c?.let { controls.getOrPut(it.packageName){Attempt()} }
        if(c!=null && control!=null && control.lastSession!=c.sessionId) {
            control.lastSession=c.sessionId;control.asked=false;control.at=0;control.total=0
            control.dynamicIntervalSeconds=300;control.lastGap=null
        }
        if(control!=null && control.deferUntil in 1..now)control.deferUntil=0
        val suppressionMap = (listOf(JevSettings.YOUTUBE, JevSettings.INSTAGRAM, "ALL") + listOfNotNull(pkg).filter(s::targets)).distinct().associateWith { targetPkg ->
            getAppSuppressionInfo(targetPkg, now, nowWall)
        }
        val reasons=if(c!=null)JevCandidatePolicy.reasons(c,s) else emptyList()
        val isDarkAmbient = c != null && (c.ambientLux ?: 999f) <= 15f && !c.isNearOrOccluded
        if (isDarkAmbient) {
            if (darkSince == null) darkSince = now
        } else {
            darkSince = null
            if (c != null && (c.ambientLux ?: 0f) >= 30f) {
                suddenDarkTriggeredSession = null
            }
        }
        val isSustainedDarkness = darkSince != null && (now - darkSince!! >= 5000L)
        val suddenDarknessEvent = isSustainedDarkness && c != null && (suddenDarkTriggeredSession != c.sessionId)
        val isGeneralOrLongform = c != null && (c.packageName == "ALL" || (c.rawGeneralMs > c.rawShortformMs))
        val minInitialMs = if (isGeneralOrLongform) 120_000L else 90_000L
        val initialProbeEligible = c != null && c.continuousMs >= minInitialMs && control?.asked != true

        val effectiveReasons = when {
            reasons.isNotEmpty() -> reasons
            suddenDarknessEvent -> listOf("SUDDEN_DARK_ENVIRONMENT")
            initialProbeEligible -> listOf(if (isGeneralOrLongform) "INITIAL_LONGFORM_PROBE" else "INITIAL_90S_PROBE")
            else -> emptyList()
        }

        val block = when {
            !s.collectionConsent -> "외부 전송 동의 필요"
            !store.tokenConfigured.value -> "테스트 토큰 필요"
            !CoachingConsentStore.granted(appContext) -> "기존 사용량 수집 동의 필요"
            !unlocked -> "화면 꺼짐·잠금"
            guard!=null -> guard
            c==null || c.quality!="VALID" -> "사용 화면 관측 대기"
            missionActive -> "미션 진행·완료 안내 대기"
            offerVisible -> "개입 표시 중"
            control?.suppressedSession==c.sessionId -> {
                val info = suppressionMap[c.packageName]
                val remSec = info?.sessionResetRemainingSeconds ?: (JevSessionTracker.SESSION_TIMEOUT_MS / 1000L)
                val remM = remSec / 60
                val remS = remSec % 60
                "현재 사용 구간 재제안 중단 (앱 15분 미사용 시 만료 · 남은 시간: ${remM}분 ${remS}초)"
            }
            (control?.deferUntil ?: 0)>now -> {
                if(control?.deferUntil==Long.MAX_VALUE) "재부팅으로 보류 시각 확인 불가 · 상태 초기화 필요"
                else {
                    val remSec = maxOf(0L, ((control?.deferUntil ?: 0L) - now) / 1000L)
                    val remM = remSec / 60
                    val remS = remSec % 60
                    "‘나중에’ 일시 보류 중 (남은 시간: ${remM}분 ${remS}초)"
                }
            }
            effectiveReasons.isEmpty() -> "호출 조건 미충족"
            else -> null
        }
        val result=approval
        val effectiveControl = control ?: lastObservedContext?.let { controls[it.packageName] }
        val intervalSec = effectiveControl?.dynamicIntervalSeconds ?: mutableState.value.dynamicIntervalSeconds ?: 300
        val nextEvalIn = if (effectiveControl?.asked == true) maxOf(0L, (intervalSec * 1000L - (now - effectiveControl.at)) / 1000L) else 0L
        val addViewingNeeded = if (effectiveControl?.asked == true && c != null) maxOf(0L, (intervalSec * 1000L - (c.sessionMs - effectiveControl.total)) / 1000L) else 0L
        val waitingStatusText = when {
            isShortsNow -> "확률 통과 · 다음 숏폼 전환 대기"
            isLongformNow -> "확률 통과 · 롱폼 영상 종료/전환 대기"
            else -> "확률 통과 · 개입 대기"
        }
        mutableState.value=JevLiveState(
            status = block ?: if(inFlight?.isActive==true)"Jev 응답 대기" else if(result!=null)waitingStatusText else if(pendingTransferApproval!=null)"개입 대기 중 (문서·메시지 보호 또는 다음 영상 전환 대기)" else "호출 조건 확인",
            context = c,
            probability = mutableState.value.probability,
            pending = inFlight?.isActive==true,
            dynamicIntervalSeconds = intervalSec,
            lastProbabilityGap = effectiveControl?.lastGap ?: mutableState.value.lastProbabilityGap,
            nextEvaluationInSeconds = nextEvalIn,
            additionalViewingNeededSeconds = addViewingNeeded,
            targetAppSuppression = suppressionMap
        )
        if(c!=null && s.collectionConsent && store.tokenConfigured.value && block!=null &&
            (block!=loggedReason || now-loggedAt>60_000)) {
            loggedReason=block;loggedAt=now
            scope.launch { runCatching { repository.observation(c,s,block) }.onFailure { store.notice.value="상황 기록 저장 실패" } }
        }

        if(block==null && c!=null && control!=null && approval==null && inFlight?.isActive!=true &&
            (safeSince?.let { now-it>=5000 }==true)) {
            val regularTimeElapsed = !control.asked || (now-control.at>=intervalSec*1000L &&
                c.sessionMs-control.total>=intervalSec*1000L)
            val eligible = regularTimeElapsed || suddenDarknessEvent
            if(eligible) {
                if (suddenDarknessEvent) {
                    suddenDarkTriggeredSession = c.sessionId
                }
                control.asked=true;control.at=now;control.total=c.sessionMs
                persist()
                inFlight=scope.launch {
                    try {
                        val answer=repository.evaluate(c,s,"LIVE",effectiveReasons.joinToString("|"))
                        val effThreshold = JevCandidatePolicy.effectiveThreshold(s, c)
                        val isGen = c.packageName == "ALL" || (c.rawGeneralMs > c.rawShortformMs)
                        val dynamicSec = JevCandidatePolicy.calculateDynamicInterval(effThreshold, answer.probability, isGen)
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
                            else -> approval=Approval(answer.trial,s,c,doneAt)
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
        val fgPkg=JevObservationHub.foregroundPackage
        if (JevSettings.isDocumentOrMessagingPackage(fgPkg) ||
            com.example.itv.infrastructure.observation.GeneralAppTracker.isFinancialPackage(fgPkg) ||
            WritingGuard.blockReason() != null
        ) return false
        val control=controls[pkg] ?: fgPkg?.let { controls[it] }
        val probe=when {
            pkg == JevSettings.INSTAGRAM || fgPkg == JevSettings.INSTAGRAM -> ReelsProbe.screenState()
            pkg == JevSettings.YOUTUBE || fgPkg == JevSettings.YOUTUBE -> YoutubeProbe.screenState()
            else -> 0
        }
        val sameForeground=(fgPkg==pkg || (pkg=="ALL" && fgPkg!=null && s.targets(fgPkg))) && now-JevObservationHub.checkedAt in 0..1500
        val currentContext=(state.value.context ?: a.captured.takeIf { sameForeground })?.let {
            if(sameForeground && (it.packageName==pkg || pkg=="ALL") && it.sessionId==a.captured.sessionId)
                it.copy(packageName = pkg, quality = "VALID")
            else it
        }
        val isYoutubeActive = pkg == JevSettings.YOUTUBE || fgPkg == JevSettings.YOUTUBE
        val isInstagramActive = pkg == JevSettings.INSTAGRAM || fgPkg == JevSettings.INSTAGRAM
        val isShortsWatch = probe == 1 ||
            (isYoutubeActive && com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments()) ||
            (isInstagramActive && com.example.itv.infrastructure.observation.instagram.InstagramTracker.isActivelyInReelsCommentsOrStory())
        val isLongformWatch = isYoutubeActive && com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyWatchingLongform()
        val isGeneralAppDeferred = !isYoutubeActive && !isInstagramActive &&
            com.example.itv.infrastructure.observation.GeneralAppTracker.isInterventionDeferredOrBlocked(fgPkg)

        // 핵심 원칙 준수:
        // - 숏폼(쇼츠·숏폼 댓글·릴스·인스타 댓글·스토리) 시청 중에는 절대 중간에 개입하지 않고 반드시 다음 전환/피드 복귀(transition == true) 시점에만 개입
        // - 롱폼(세로/가로 영상 시청 중)에도 절대 영상 중간에 개입하지 않고 반드시 영상 종료(엔드스크린)/전환/유튜브 홈 이동(transition == true) 시점에만 개입
        // - 그 외 앱(게임 인게임 플레이 중, 외부 OTT 영상 시청 중, 음성통화 등)에서도 개입 불가/대기 상태(🔴/🟡)에서는 보류하고 개입 가능(🟢) 상태에서 개입
        val readyForScreen = when {
            isShortsWatch -> transition
            isLongformWatch -> transition
            isGeneralAppDeferred -> false
            else -> (probe == 0)
        }
        val effectiveAgeMs = if (isShortsWatch || isLongformWatch || isGeneralAppDeferred) 0L else (now - a.requestedAt)
        return attached && enabled && s.collectionConsent && s.actualIntervention && store.tokenConfigured.value &&
            CoachingConsentStore.granted(appContext) && !missionActiveState.value && active==null &&
            sameForeground && readyForScreen &&
            control?.suppressedSession!=a.captured.sessionId && (control?.deferUntil ?: 0)<=now &&
            WritingGuard.blockReason()==null && (transition || safeSince?.let { now-it>=5000 }==true) &&
            JevCandidatePolicy.resultIsCurrent(a.settings.revision,s,a.captured,currentContext,effectiveAgeMs)
    }
    fun approvedId(): String? = approval?.trial?.id
    fun approvedPackage(): String? = approval?.captured?.packageName
    fun hasActiveOffer() = active != null
    fun canKeepDisplayed(): Boolean {
        val displayed = displayedApproval ?: return false
        val now = SystemClock.elapsedRealtime()
        val currentFg = JevObservationHub.foregroundPackage
        val sameForeground = (currentFg == displayed.captured.packageName ||
            (displayed.captured.packageName == "ALL" && currentFg != null && store.settings.value.targets(currentFg)) ||
            JevObservationHub.isTransientSystemPackage(currentFg)) &&
            now - JevObservationHub.checkedAt in 0..1500
        val currentContext = (state.value.context ?: displayed.captured.takeIf { sameForeground })?.let {
            if (sameForeground && (it.packageName == displayed.captured.packageName || displayed.captured.packageName == "ALL") && it.sessionId == displayed.captured.sessionId)
                it.copy(packageName = displayed.captured.packageName, quality = "VALID")
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
        val fgPkg = JevObservationHub.foregroundPackage
        val control = controls[pkg] ?: fgPkg?.let { controls[it] }
        val probe = when {
            pkg == JevSettings.INSTAGRAM || fgPkg == JevSettings.INSTAGRAM -> ReelsProbe.screenState()
            pkg == JevSettings.YOUTUBE || fgPkg == JevSettings.YOUTUBE -> YoutubeProbe.screenState()
            else -> 0
        }
        val sameForeground = (fgPkg == pkg || (pkg == "ALL" && fgPkg != null && s.targets(fgPkg))) && now - JevObservationHub.checkedAt in 0..1500
        if (!sameForeground) {
            val appLabel = when (pkg) {
                JevSettings.YOUTUBE -> "YouTube"
                JevSettings.INSTAGRAM -> "Instagram"
                else -> pkg
            }
            return "현재 화면이 대상 앱(${appLabel})이 아님"
        }
        if (control?.suppressedSession == a.captured.sessionId) {
            val info = getAppSuppressionInfo(pkg, now, System.currentTimeMillis())
            val remSec = info.sessionResetRemainingSeconds ?: (JevSessionTracker.SESSION_TIMEOUT_MS / 1000L)
            val remM = remSec / 60
            val remS = remSec % 60
            return "현재 사용 구간에서 이미 사용자가 응답하여 재개입 억제 중 (앱 15분 미사용 시 만료 · 남은 시간: ${remM}분 ${remS}초)"
        }
        if ((control?.deferUntil ?: 0) > now) {
            val remainSec = maxOf(0L, ((control?.deferUntil ?: 0L) - now) / 1000L)
            val remM = remainSec / 60
            val remS = remainSec % 60
            return "‘나중에’를 선택하여 일시 보류 중 (남은 시간: ${remM}분 ${remS}초)"
        }
        val block = WritingGuard.blockReason()
        if (block != null) {
            return "글 작성/키보드 활성화로 개입 보류 ($block)"
        }
        val safeAt = safeSince
        if (safeAt == null || now - safeAt < 5000) {
            return "화면 안전 상태 확인 중 (최대 5초 대기)"
        }
        val isYoutubeActive = pkg == JevSettings.YOUTUBE || fgPkg == JevSettings.YOUTUBE
        val isInstagramActive = pkg == JevSettings.INSTAGRAM || fgPkg == JevSettings.INSTAGRAM
        val isShortsWatch = probe == 1 ||
            (isYoutubeActive && com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments()) ||
            (isInstagramActive && com.example.itv.infrastructure.observation.instagram.InstagramTracker.isActivelyInReelsCommentsOrStory())
        if (isShortsWatch) {
            return "숏폼/스토리 시청 중 (시청 중 개입 금지 원칙에 따라 다음 전환 시 개입 대기 중)"
        }
        val isLongformWatch = isYoutubeActive && com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyWatchingLongform()
        if (isLongformWatch) {
            return "유튜브 롱폼 시청 중 (시청 중 개입 금지 원칙에 따라 영상 종료·전환·유튜브 홈 이동 시 개입 대기 중)"
        }
        val genDeferredReason = if (!isYoutubeActive && !isInstagramActive) {
            com.example.itv.infrastructure.observation.GeneralAppTracker.blockingOrWaitingReason(fgPkg)
        } else null
        if (genDeferredReason != null) {
            return "현재 앱 개입 대기 중 ($genDeferredReason)"
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
    fun transferOnAppSwitch() {
        val candidate = displayedApproval ?: approval ?: return
        pendingTransferApproval = candidate
        displayedApproval = null
        active = null
        approval = null
    }
    fun hasTransferrableApproval(): Boolean = approval != null || pendingTransferApproval != null
    fun promotePendingTransfer(pkg: String? = null): Boolean {
        val pending = pendingTransferApproval ?: return false
        val s = store.settings.value
        val now = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val targetPkg = pkg ?: JevObservationHub.foregroundPackage ?: return false
        if (JevSettings.isDocumentOrMessagingPackage(targetPkg) ||
            com.example.itv.infrastructure.observation.GeneralAppTracker.isFinancialPackage(targetPkg) ||
            WritingGuard.blockReason() != null
        ) return false
        if (!s.targets(targetPkg)) return false
        val c = tracker.context(targetPkg, now, nowWall, bedtime(s))?.let { enrichContext(it, now) } ?: return false
        approval = pending.copy(captured = c.copy(quality = "VALID"), requestedAt = now)
        pendingTransferApproval = null
        return true
    }
    fun respond(action: String) {
        pendingTransferApproval=null
        val trial=active ?: return
        active=null
        val pkg=JSONObject(trial.payload).optString("package_name")
        val control=controls.getOrPut(pkg){Attempt()}
        val session=JSONObject(trial.payload).optString("session_id")
        val shortformApp = if (pkg == JevSettings.INSTAGRAM) ShortformApp.INSTAGRAM else ShortformApp.YOUTUBE
        val curSwipes = SwipeDynamicsTracker.snapshot(shortformApp).totalSwipesInRun
        if(action in setOf("LATER","NO_RESPONSE")) {
            control.deferUntil=SystemClock.elapsedRealtime()+com.example.itv.domain.coaching.TestCoachingPolicy.JEV_REMINDER_MS
            control.lastOutcome = action
            control.lastOutcomeAt = SystemClock.elapsedRealtime()
            control.lastOutcomeSwipes = curSwipes
            control.resistanceStreak = (control.resistanceStreak + 1).coerceAtMost(5)
        } else if(action in setOf("REST","DISMISS")) {
            control.suppressedSession=session
            control.lastOutcome = action
            control.lastOutcomeAt = SystemClock.elapsedRealtime()
            control.resistanceStreak = 0
        }
        persist()
        val receipt=displayReceipt
        scope.launch {
            receipt?.join()
            runCatching { repository.event(trial,"response",JSONObject().put("action",action)) }
                .onFailure { store.notice.value="응답 기록 저장 실패" }
        }
    }
    fun cancelled(reason: String) {
        pendingTransferApproval=null
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
    fun suppressCurrentSession(reason: String = "DISMISS") {
        pendingTransferApproval = null
        val fg = JevObservationHub.foregroundPackage
        val targetPkg = fg?.takeIf { store.settings.value.targets(it) }
            ?: displayedApproval?.captured?.packageName
            ?: approval?.captured?.packageName
            ?: lastObservedContext?.packageName
        val now = SystemClock.elapsedRealtime()
        val reminderMs = com.example.itv.domain.coaching.TestCoachingPolicy.JEV_REMINDER_MS
        if (targetPkg != null) {
            controls.getOrPut(targetPkg) { Attempt() }.let {
                it.suppressedSession = tracker.states[targetPkg]?.id
                it.deferUntil = now + reminderMs
                it.lastOutcome = reason
                it.lastOutcomeAt = now
                it.asked = false
            }
        }
        for (pkg in listOf(JevSettings.INSTAGRAM, JevSettings.YOUTUBE)) {
            controls.getOrPut(pkg) { Attempt() }.let {
                it.suppressedSession = tracker.states[pkg]?.id
                it.deferUntil = now + reminderMs
                it.lastOutcome = reason
                it.lastOutcomeAt = now
                it.asked = false
            }
        }
        controls.getOrPut("ALL") { Attempt() }.let {
            it.suppressedSession = tracker.states["ALL"]?.id
            it.deferUntil = now + reminderMs
            it.lastOutcome = reason
            it.lastOutcomeAt = now
            it.asked = false
        }
        lastSuppressionReason = reason
        lastSuppressionAt = now
        persist()
        approval = null
        displayedApproval = null
        active = null
    }
    fun onMissionRestSuccess() {
        pendingTransferApproval = null
        val fg = JevObservationHub.foregroundPackage
        val targetPkg = fg?.takeIf { store.settings.value.targets(it) }
            ?: displayedApproval?.captured?.packageName
            ?: approval?.captured?.packageName
            ?: lastObservedContext?.packageName
        if (targetPkg != null) {
            tracker.resetContinuous(targetPkg)
            controls[targetPkg]?.let {
                it.suppressedSession = tracker.states[targetPkg]?.id
                it.deferUntil = SystemClock.elapsedRealtime() + 300_000L // 5분 재개입 억제 쿨타임
                it.asked = false
            }
        } else {
            for (pkg in listOf(JevSettings.INSTAGRAM, JevSettings.YOUTUBE)) {
                tracker.resetContinuous(pkg)
                controls[pkg]?.let {
                    it.suppressedSession = tracker.states[pkg]?.id
                    it.deferUntil = SystemClock.elapsedRealtime() + 300_000L
                    it.asked = false
                }
            }
        }
        tracker.resetContinuous("ALL")
        controls["ALL"]?.let {
            it.suppressedSession = tracker.states["ALL"]?.id
            it.deferUntil = SystemClock.elapsedRealtime() + 300_000L
            it.asked = false
        }
        com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.resetSessionChain()
        approval = null
        displayedApproval = null
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
                .put("dynInterval", c.dynamicIntervalSeconds).put("lastGap", c.lastGap ?: -1.0)
                .put("lastOutcome", c.lastOutcome ?: "")
                .put("lastOutcomeSwipes", c.lastOutcomeSwipes)
                .put("resistanceStreak", c.resistanceStreak))
        }
        scope.launch {
            persistMutex.lock()
            try { runCatching { store.writeControl("runtime",json.toString()) }.onFailure { store.notice.value="관측 상태 저장 실패" } }
            finally { persistMutex.unlock() }
        }
    }
    fun sessionState(pkg: String) = tracker.states[pkg]

    fun getAppSuppressionInfo(
        pkg: String,
        now: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis()
    ): JevAppSuppressionInfo {
        val control = controls[pkg]
        val frame = tracker.states[pkg]
        val currentSessionId = frame?.id
        val isSuppressed = control?.suppressedSession != null && control.suppressedSession == currentSessionId
        val isDeferred = (control?.deferUntil ?: 0L) > now
        val isAsked = control?.asked == true

        val prevWall = lastActiveWallMs[pkg] ?: 0L
        val awayWallMs = if (prevWall > 0L) maxOf(0L, nowWall - prevWall) else 0L
        val awayMs = maxOf(frame?.awayMs ?: 0L, awayWallMs)

        val sessionResetRemainingSeconds = if (isSuppressed) {
            maxOf(0L, (JevSessionTracker.SESSION_TIMEOUT_MS - awayMs + 999L) / 1000L)
        } else null

        val deferRemainingSeconds = if (isDeferred) {
            maxOf(0L, ((control?.deferUntil ?: 0L) - now + 999L) / 1000L)
        } else null

        val intervalSec = control?.dynamicIntervalSeconds ?: 300
        val nextEvaluationInSeconds = if (isAsked && !isSuppressed && !isDeferred) {
            maxOf(0L, (intervalSec * 1000L - (now - (control?.at ?: now)) + 999L) / 1000L)
        } else null

        val statusText = when {
            isSuppressed -> {
                if (sessionResetRemainingSeconds != null && sessionResetRemainingSeconds > 0L) {
                    val remM = sessionResetRemainingSeconds / 60
                    val remS = sessionResetRemainingSeconds % 60
                    if (awayMs < 5_000L) {
                        "쉬기/닫기로 개입 억제 중 · 앱을 닫고 15분간 미사용 시 세션 만료 (남은 시간: ${remM}분 ${remS}초)"
                    } else {
                        val awayM = (awayMs / 1000L) / 60
                        val awayS = (awayMs / 1000L) % 60
                        "쉬기/닫기로 개입 억제 중 · 세션 만료까지 ${remM}분 ${remS}초 남음 (부재 경과: ${awayM}분 ${awayS}초 / 15분)"
                    }
                } else {
                    "15분 미사용으로 세션 만료 완료 · 앱 재진입 시 새 세션으로 개입 활성화"
                }
            }
            isDeferred -> {
                val remSec = deferRemainingSeconds ?: 0L
                val remM = remSec / 60
                val remS = remSec % 60
                "‘나중에’ 일시 보류 중 · 다음 개입까지 ${remM}분 ${remS}초 남음"
            }
            isAsked && (nextEvaluationInSeconds ?: 0L) > 0L -> {
                val remSec = nextEvaluationInSeconds ?: 0L
                val remM = remSec / 60
                val remS = remSec % 60
                "재평가 대기 중 · 다음 평가까지 ${remM}분 ${remS}초 남음 (동적 간격: ${intervalSec}초)"
            }
            else -> "정상 관측 중 (호출 조건 충족 시 개입 가능)"
        }

        return JevAppSuppressionInfo(
            packageName = pkg,
            sessionId = currentSessionId,
            isSuppressed = isSuppressed,
            isDeferred = isDeferred,
            isAsked = isAsked,
            awayMs = awayMs,
            sessionResetRemainingSeconds = sessionResetRemainingSeconds,
            deferRemainingSeconds = deferRemainingSeconds,
            nextEvaluationInSeconds = nextEvaluationInSeconds,
            dynamicIntervalSeconds = intervalSec,
            statusText = statusText,
            lastProbabilityGap = control?.lastGap
        )
    }

    /** Explicit operator action for a test run, never automatic on restart or settings edits. */
    fun resetTestObservation() {
        approval?.let { suppress(it.trial,"OPERATOR_RESET") };approval=null
        com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.resetSessionChain()
        tracker.states.clear();controls.clear();lastActiveWallMs.clear();lastActiveDate.clear();safeSince=null;lastObservedContext=null;persist()
        mutableState.value=JevLiveState("테스트 관측 상태 초기화 · 재관측 필요")
    }
    companion object {
        const val SHORTFORM_TRANSITION_TIMEOUT_MS = 90_000L
        @Volatile private var instance: JevRuntime?=null
        fun get(context: Context)=instance ?: synchronized(this) { instance ?: JevRuntime(context.applicationContext).also { instance=it } }
    }
}
