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
        var lastOutcomeAt: Long=0L
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
    private var screenOffSinceWallMs: Long?=null
    private var screenOffRestResetDone: Boolean=false
    private var networkRetryAfterElapsed: Long=0L
    private var pendingTransferApproval: Approval?=null
    private var savedAt=0L
    private var lastPersistedJson: String? = null
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
                    var restoredAllSession = false
                    for(pkg in restorePkgs) {
                        if (pkg == "boot" || pkg == "sessionCategories") continue
                        val a=json.optJSONObject(pkg) ?: continue
                        val savedDate=a.optString("lastActiveDate","")
                        val savedWall=a.optLong("lastActiveWallMs",0L)
                        val activeAcrossMidnight=savedWall>0L && (nowWall-savedWall) in 0L until JevSessionTracker.INTERRUPTION_TOLERANCE_MS
                        val sessionStillRecent=savedWall>0L && (nowWall-savedWall) in 0L until JevSessionTracker.SESSION_TIMEOUT_MS &&
                            (savedDate==todayStr || activeAcrossMidnight)
                        if(sessionStillRecent) {
                            lastActiveDate[pkg]=todayStr
                            lastActiveWallMs[pkg]=savedWall
                            val savedTotal = a.optLong("total")
                            val savedSf = if (a.has("totalShortform")) a.optLong("totalShortform") else savedTotal
                            val savedMid = a.optLong("totalMid", 0L)
                            val savedLow = a.optLong("totalLow", 0L)
                            tracker.restore(pkg, a.optString("session",UUID.randomUUID().toString()), savedTotal, a.optBoolean("entered"), savedSf, savedMid, savedLow)
                            if (pkg == "ALL") restoredAllSession = true
                            val sameBoot=bootId>=0 && json.optInt("boot",-2)==bootId
                            val savedAttemptTotal = if (a.has("attemptRawTotal")) a.optLong("attemptRawTotal") else a.optLong("attemptTotal")
                            controls[pkg]=Attempt(if(sameBoot)a.optLong("at") else SystemClock.elapsedRealtime(), savedAttemptTotal,
                                a.optBoolean("asked"),a.optString("suppressed").takeIf { it.isNotEmpty() },
                                if(sameBoot)a.optLong("deferUntil") else if(a.optLong("deferUntil")>0)Long.MAX_VALUE else 0,
                                a.optString("attemptSession",a.optString("session")),
                                a.optInt("dynInterval", 300),
                                a.optDouble("lastGap", -1.0).takeIf { it.isFinite() && it >= 0.0 },
                                a.optString("lastOutcome").takeIf { it.isNotEmpty() },
                                0L)
                        } else {
                            tracker.resetSession(pkg)
                        }
                    }
                    if (restoredAllSession) {
                        com.example.itv.infrastructure.observation.SessionCategoryTracker.restoreFromJson(
                            json.optJSONObject("sessionCategories")
                        )
                    } else {
                        com.example.itv.infrastructure.observation.SessionCategoryTracker.clear()
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
        val nowWall = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val lastSleep = com.example.itv.infrastructure.sleep.SleepWakeCycleStore.getLastCompletedSleep(appContext)
        val minute=bedtimeMinute
        if (minute != null) {
            if (com.example.itv.domain.sleep.SleepWakeCyclePolicy.hasCompletedSleepForBedtimeCycle(
                    nowWallMs = nowWall,
                    targetMinuteOfDay = minute,
                    zone = zone,
                    lastSleepEndWallMs = lastSleep?.sleepEndWallMs,
                    lastSleepDurationMs = lastSleep?.durationMs
                )
            ) {
                return null
            }
            val now=ZonedDateTime.now(zone)
            var start=now.toLocalDate().atStartOfDay(now.zone).plusMinutes(minute.toLong())
            if(now.isBefore(start))start=start.minusDays(1)
            val elapsed=Duration.between(start,now).toMinutes()
            return elapsed.takeIf { it in 0 until settings.bedtimeWindowMinutes.toLong() }?.toInt()
        }
        // 수동 취침 목표가 없는 경우에도 자동 추정/권장 취침 시각 경과분을 폴백으로 연동합니다.
        // 단, 이미 3시간 이상 수면을 완료하고 기상했다면 전날 취침 경과로 간주하지 않습니다.
        val ambientSnap = com.example.itv.infrastructure.observation.LightAndBrightnessTracker
            .getInstance(appContext).snapshot.value
        if (ambientSnap.hasWokenUpFromSleep) return null
        val autoDist = ambientSnap.recommendedBedtimeDistanceMinutes ?: ambientSnap.bedtimeDistanceMinutes
        val maxWindow = minOf(
            settings.bedtimeWindowMinutes,
            com.example.itv.domain.sleep.SleepWakeCyclePolicy.MAX_POST_BEDTIME_MINUTES + 1
        )
        return autoDist?.takeIf { it in 0 until maxWindow }
    }
    fun enrichContext(base: JevContext, nowElapsed: Long = SystemClock.elapsedRealtime()): JevContext {
        val personal = ShortformPersonalStatsStore.quickContextMetrics(
            context = appContext,
            pkg = base.packageName,
            currentContinuousMs = base.continuousMs,
            rawShortformSessionMs = base.rawShortformMs
        )
        val isAll = base.packageName == "ALL"
        val includeLongform = base.packageName == JevSettings.YOUTUBE || isAll
        val lfMetricsAll = com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.getJevLongformMetrics(base.observedAt)
        val lfMetrics = if (includeLongform) lfMetricsAll else null

        val genSnap = com.example.itv.infrastructure.observation.GeneralAppTracker.snapshot.value
        val igMode = com.example.itv.infrastructure.observation.instagram.InstagramTracker.snapshot.value.mode
        val isReelsNow = ReelsProbe.screenState() == 1 ||
            igMode == com.example.itv.infrastructure.observation.instagram.InstagramScreenMode.REELS
        val isYoutubeShortsNow = base.packageName == JevSettings.YOUTUBE &&
            (lfMetrics?.youtubeWatchMode == "SHORTS" || YoutubeProbe.screenState() == 1)
        val isInstagramReelsNow = base.packageName == JevSettings.INSTAGRAM && isReelsNow
        val isActiveShortformScreen = isYoutubeShortsNow || isInstagramReelsNow

        val shortformApp = if (base.packageName == JevSettings.INSTAGRAM) ShortformApp.INSTAGRAM else ShortformApp.YOUTUBE
        val swipeSnap = if (isActiveShortformScreen) SwipeDynamicsTracker.snapshot(shortformApp, nowElapsed, base.observedAt) else null
        val effSpm1m = swipeSnap?.effectiveSpm1m?.takeIf { swipeSnap.totalSwipesInRun > 0 || it > 0.0 }
        val effSessionSpm = swipeSnap?.effectiveSessionSpm?.takeIf { swipeSnap.totalSwipesInRun > 0 || it > 0.0 }
        val personalSpm = if (isActiveShortformScreen) personal.personalAvgSpm?.takeIf { it > 0.0 } else null
        val spmRatio = if (effSpm1m != null && personalSpm != null && personalSpm >= 1.0) {
            kotlin.math.round((effSpm1m / personalSpm) * 100.0) / 100.0
        } else null
        val accel1m = swipeSnap?.effectiveAccel1m?.takeIf { swipeSnap.totalSwipesInRun > 0 || it != 0.0 }
        val streak = swipeSnap?.currentFastStreak ?: 0
        val overheated = isActiveShortformScreen && ((streak >= 3 && (effSpm1m ?: 0.0) >= 10.0) || ((effSpm1m ?: 0.0) >= 12.0 && (accel1m ?: 0.0) >= 3.0))

        val ambient = com.example.itv.infrastructure.observation.LightAndBrightnessTracker.getInstance(appContext).snapshot.value
        val isOccluded = ambient.isOccluded || (ambient.isNear && (ambient.proximityDistanceCm ?: 5f) <= 1.0f)
        val bedtimeDist = ambient.bedtimeDistanceMinutes ?: base.bedtimePassedMinutes

        val ytSwipes = maxOf(
            SwipeDynamicsTracker.snapshot(ShortformApp.YOUTUBE, nowElapsed, base.observedAt).totalSwipesInRun,
            YoutubeProbe.candidates,
            YoutubeProbe.lastRunCandidates
        )
        val igSwipes = maxOf(
            SwipeDynamicsTracker.snapshot(ShortformApp.INSTAGRAM, nowElapsed, base.observedAt).totalSwipesInRun,
            ReelsProbe.candidates,
            ReelsProbe.lastRunCandidates
        )
        // 숏폼 누적 스와이프 수와 롱폼 연속 전환 수는 3분 자연 휴식까지 유지되는 세션 통합 지표이므로
        // 앱 전환이나 HUD/테스트 창 간 조회 시 동일한 값을 사용합니다.
        val shortformSwipeCount = ytSwipes + igSwipes

        val resolvedAppCategory = when {
            base.packageName == JevSettings.INSTAGRAM && isReelsNow -> "SHORTFORM"
            base.packageName == JevSettings.INSTAGRAM && igMode == com.example.itv.infrastructure.observation.instagram.InstagramScreenMode.DM -> "MESSENGER"
            base.packageName == JevSettings.INSTAGRAM -> "COMMUNITY_SNS"
            isYoutubeShortsNow -> "SHORTFORM"
            base.packageName == JevSettings.YOUTUBE && lfMetrics?.youtubeWatchMode == "BROWSE_FEED" -> "COMMUNITY_SNS"
            base.packageName == JevSettings.YOUTUBE -> "LONGFORM_VIDEO"
            genSnap.category == com.example.itv.infrastructure.observation.InferredAppCategory.GAME -> "GAME"
            genSnap.category == com.example.itv.infrastructure.observation.InferredAppCategory.DOCUMENT -> "DOCUMENT"
            genSnap.category == com.example.itv.infrastructure.observation.InferredAppCategory.VIDEO_MEDIA -> "LONGFORM_VIDEO"
            genSnap.category == com.example.itv.infrastructure.observation.InferredAppCategory.VOICE_CALL -> "VOICE_CALL"
            genSnap.category == com.example.itv.infrastructure.observation.InferredAppCategory.WRITING -> "WRITING"
            else -> genSnap.category.name
        }
        val resolvedActivityPhase = when {
            isActiveShortformScreen -> if (overheated) "FAST_SWIPING" else "BROWSING"
            base.packageName == JevSettings.YOUTUBE && (lfMetrics?.currentVideoWatchMs ?: 0L) > 0L -> "VIDEO_PLAYING"
            base.packageName == JevSettings.YOUTUBE && lfMetrics?.youtubeWatchMode == "BROWSE_FEED" -> "BROWSING"
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
            recommendedBedtimeDistanceMinutes = ambient.recommendedBedtimeDistanceMinutes,
            userEnvironmentState = ambient.userEnvironmentState.name,
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
            consecutiveLongformChainCount = lfMetricsAll.consecutiveChainCount,
            shortformSwipeCount = shortformSwipeCount
        )
    }

    /**
     * HUD와 앱 내 Jev 테스트 창이 완전히 동일한 기준 확률·보정 내역을 계산할 수 있도록
     * 현재 평가용 [JevContext]의 단일 기준(Single Source of Truth)을 반환합니다.
     */
    fun currentEvaluationContext(
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis()
    ): JevContext {
        state.value.context?.let { live ->
            return live
        }
        val settings = store.settings.value
        val allSession = tracker.states["ALL"]
        val ytSession = tracker.states[JevSettings.YOUTUBE]
        val igSession = tracker.states[JevSettings.INSTAGRAM]
        val devStatus = com.example.itv.domain.coaching.DeveloperStatus.value
        val lastCtx = lastObservedContext

        val allC = allSession?.continuousMs ?: 0L
        val sumC = (ytSession?.continuousMs ?: 0L) + (igSession?.continuousMs ?: 0L)
        val continuousNow = maxOf(allC, maxOf(devStatus.usedMs, sumC))
        val allLast = allSession?.lastRunContinuousMs ?: 0L
        val sumLast = (ytSession?.lastRunContinuousMs ?: 0L) + (igSession?.lastRunContinuousMs ?: 0L)
        val continuousLast = maxOf(allLast, maxOf(devStatus.usedMs, sumLast))
        val continuousMs = if (continuousNow > 0L) continuousNow else maxOf(continuousLast, lastCtx?.continuousMs ?: 0L)

        val allTotal = allSession?.totalMs ?: 0L
        val sumTotal = (ytSession?.totalMs ?: 0L) + (igSession?.totalMs ?: 0L)
        val totalMs = maxOf(allTotal, maxOf(devStatus.usedMs, maxOf(sumTotal, lastCtx?.sessionMs ?: 0L)))

        val rawShortformMs = maxOf(
            allSession?.rawShortformMs ?: 0L,
            maxOf((ytSession?.rawShortformMs ?: 0L) + (igSession?.rawShortformMs ?: 0L), lastCtx?.rawShortformMs ?: 0L)
        )
        val rawMidWeightMs = maxOf(
            allSession?.rawMidMs ?: 0L,
            maxOf((ytSession?.rawMidMs ?: 0L) + (igSession?.rawMidMs ?: 0L), lastCtx?.rawMidWeightMs ?: 0L)
        )
        val rawGeneralMs = maxOf(
            allSession?.rawGeneralMs ?: 0L,
            maxOf((ytSession?.rawGeneralMs ?: 0L) + (igSession?.rawGeneralMs ?: 0L), lastCtx?.rawGeneralMs ?: 0L)
        )
        val returns = maxOf(
            allSession?.returns?.size ?: ((ytSession?.returns?.size ?: 0) + (igSession?.returns?.size ?: 0)),
            lastCtx?.returns10m ?: 0
        )
        val sessionId = lastCtx?.sessionId ?: allSession?.id ?: ytSession?.id ?: igSession?.id ?: java.util.UUID.randomUUID().toString()
        val liveBedtime = bedtime(settings) ?: lastCtx?.bedtimePassedMinutes

        val base = JevContext(
            sessionId = sessionId,
            packageName = lastCtx?.packageName ?: "ALL",
            sessionMs = totalMs,
            continuousMs = continuousMs,
            returns10m = returns,
            returnsObservedSeconds = lastCtx?.returnsObservedSeconds ?: 600,
            bedtimePassedMinutes = liveBedtime,
            observedAt = nowWall,
            rawShortformMs = rawShortformMs,
            rawGeneralMs = rawGeneralMs,
            rawMidWeightMs = rawMidWeightMs
        )
        val enriched = enrichContext(base, nowElapsed)

        // 직전 관측 화면이 숏폼(쇼츠/릴스)이었던 경우, ITV 앱(테스트 창)으로 전환한 동안에도
        // HUD와 테스트 창이 동일한 스와이프 과열 보정(swipeDelta)을 표시하도록 직전 숏폼 동역학을 유지합니다.
        val lastWasShortform = lastCtx != null && JevCandidatePolicy.isShortformContext(lastCtx)
        return if (lastWasShortform && !JevCandidatePolicy.isShortformContext(enriched)) {
            val shortApp = if (lastCtx.packageName == JevSettings.INSTAGRAM) ShortformApp.INSTAGRAM else ShortformApp.YOUTUBE
            val swipeSnap = SwipeDynamicsTracker.snapshot(shortApp, nowElapsed, nowWall)
            val hasSwipes = swipeSnap.totalSwipesInRun > 0 || (lastCtx.effectiveSpm1m ?: 0.0) > 0.0
            if (hasSwipes) {
                val effSpm1m = swipeSnap.effectiveSpm1m.takeIf { swipeSnap.totalSwipesInRun > 0 || it > 0.0 } ?: lastCtx.effectiveSpm1m
                val effSessionSpm = swipeSnap.effectiveSessionSpm.takeIf { swipeSnap.totalSwipesInRun > 0 || it > 0.0 } ?: lastCtx.effectiveSessionSpm
                val accel1m = swipeSnap.effectiveAccel1m.takeIf { swipeSnap.totalSwipesInRun > 0 || it != 0.0 } ?: lastCtx.swipeAccel1m
                val streak = if (swipeSnap.totalSwipesInRun > 0) swipeSnap.currentFastStreak else lastCtx.fastSwipeStreak
                val personalSpm = enriched.personalAvgSpm ?: lastCtx.personalAvgSpm
                val spmRatio = if (effSpm1m != null && personalSpm != null && personalSpm >= 1.0) {
                    kotlin.math.round((effSpm1m / personalSpm) * 100.0) / 100.0
                } else lastCtx.currentVsPersonalSpmRatio
                val overheated = (streak >= 3 && (effSpm1m ?: 0.0) >= 10.0) || ((effSpm1m ?: 0.0) >= 12.0 && (accel1m ?: 0.0) >= 3.0)
                enriched.copy(
                    appCategory = "SHORTFORM",
                    youtubeWatchMode = lastCtx.youtubeWatchMode ?: enriched.youtubeWatchMode,
                    effectiveSpm1m = effSpm1m,
                    effectiveSessionSpm = effSessionSpm,
                    personalAvgSpm = personalSpm,
                    currentVsPersonalSpmRatio = spmRatio,
                    swipeAccel1m = accel1m,
                    fastSwipeStreak = streak,
                    swipeOverheated = overheated,
                    activityPhase = if (overheated) "FAST_SWIPING" else "BROWSING"
                )
            } else {
                enriched
            }
        } else {
            enriched
        }
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
        // 3분 이상 화면 꺼짐(자연 휴식) 시에만 롱폼 영상 전환 횟수·숏폼 누적 스와이프 수 및 Jev 직전 확률 초기화 (15분 세션 만료와는 분리)
        var confirmedScreenOffRestOnWake = false
        if (!unlocked) {
            val offSince = screenOffSinceWallMs ?: nowWall.also { screenOffSinceWallMs = it }
            if (!screenOffRestResetDone && (nowWall - offSince) >= JevSessionTracker.RECOGNIZED_REST_MS) {
                resetContentSwitchCounters()
                resetProbability("SCREEN_OFF_NATURAL_REST")
                screenOffRestResetDone = true
            }
        } else {
            val offSince = screenOffSinceWallMs
            if (offSince != null && (nowWall - offSince) >= JevSessionTracker.RECOGNIZED_REST_MS) {
                confirmedScreenOffRestOnWake = true
                if (!screenOffRestResetDone) {
                    resetContentSwitchCounters()
                    resetProbability("SCREEN_OFF_NATURAL_REST")
                }
                if ((nowWall - offSince) >= com.example.itv.domain.sleep.SleepWakeCyclePolicy.MIN_SLEEP_CYCLE_MS) {
                    com.example.itv.infrastructure.observation.LightAndBrightnessTracker
                        .getInstance(appContext)
                        .onWakeUpFromSleep(offSince, nowWall, source = "JEV_SCREEN_OFF")
                }
            }
            screenOffSinceWallMs = null
            screenOffRestResetDone = false
        }
        val ownAppFg = WritingGuard.inAppForeground
        val pkg = when {
            !unlocked -> "LOCKED"
            ownAppFg -> appContext.packageName
            else -> JevObservationHub.foregroundPackage.takeIf { now - JevObservationHub.checkedAt in 0..FOREGROUND_FRESHNESS_MS }
        }
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
            val screenOffRestExpired=confirmedScreenOffRestOnWake || (!unlocked && prevWall>0L && (nowWall-prevWall>=JevSessionTracker.RECOGNIZED_REST_MS))
            if(dateChangedWhileAway || inactiveExpired || screenOffRestExpired) {
                tracker.resetSession(targetPkg)
                controls.remove(targetPkg)
                lastActiveDate.remove(targetPkg)
                lastActiveWallMs.remove(targetPkg)
                if(lastObservedContext?.packageName==targetPkg)lastObservedContext=null
                suddenDarkTriggeredSession = null
                // 전체 세션("ALL")이 실제로 만료됐을 때만 카테고리 누적과 직전 확률을 초기화한다.
                // (다른 앱을 시청 중인 상태에서 과거 백그라운드 앱 1개만 15분 만료되는 경우 진행 중인 세션을 날리지 않음)
                if (targetPkg == "ALL") {
                    resetProbability("SESSION_EXPIRED")
                }
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
            JevSettings.INSTAGRAM -> {
                val raw = ReelsProbe.screenState()
                if (raw == 1 || com.example.itv.infrastructure.observation.instagram.InstagramTracker.snapshot.value.mode == com.example.itv.infrastructure.observation.instagram.InstagramScreenMode.REELS) 1 else raw
            }
            JevSettings.YOUTUBE -> {
                val raw = YoutubeProbe.screenState()
                if (raw == 1 || com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.isActivelyInShortsOrComments()) 1 else raw
            }
            else -> 0
        }
        val guard=WritingGuard.blockReason()
        val confirmed=pkg?.takeIf { screen==1 && guard==null && s.targets(it) }
        val isOwnApp = pkg != null && (pkg == appContext.packageName || pkg.startsWith("com.team.itv") || pkg.startsWith("com.example.itv"))
        val effectivePkg=if(guard!=null && !isOwnApp)"GUARD_BLOCKED" else pkg
        val weightTier = com.example.itv.infrastructure.observation.UsageWeightResolver.resolveTier(effectivePkg)
        tracker.observe(now, effectivePkg, confirmed, nonShortformTier = weightTier)
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
        val isLandscapeNow = appContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if(currentApproval!=null) {
            if (sameAppSafe && approvalContext?.quality == "VALID" && (isShortsNow || isLongformNow || isLandscapeNow)) {
                // 숏폼·롱폼 영상 시청 중 또는 가로 모드 사용 중에는 종료/세로 전환 시점까지 승인을 만료시키지 않고 유지함
                approval = currentApproval.copy(requestedAt = now)
            } else if (!JevCandidatePolicy.resultIsCurrent(currentApproval.settings.revision,s,currentApproval.captured,approvalContext,now-currentApproval.requestedAt)) {
                suppress(currentApproval.trial,"STALE_CONTEXT_OR_RESULT");approval=null
            }
        }
        val control=c?.let { controls.getOrPut(it.packageName){Attempt()} }
        val allSessionId = tracker.states["ALL"]?.id
        val sessionControl = controls.getOrPut("ALL") { Attempt() }
        if(c!=null && control!=null && control.lastSession!=c.sessionId) {
            control.lastSession=c.sessionId;control.asked=false;control.at=0;control.total=0
            control.dynamicIntervalSeconds=300;control.lastGap=null
        }
        if(allSessionId!=null && sessionControl.lastSession!=allSessionId) {
            sessionControl.lastSession=allSessionId;sessionControl.asked=false;sessionControl.at=0;sessionControl.total=0
            sessionControl.dynamicIntervalSeconds=300;sessionControl.lastGap=null
        }
        if(control!=null && control.deferUntil in 1..now)control.deferUntil=0
        if(sessionControl.deferUntil in 1..now)sessionControl.deferUntil=0
        val suppressionMap = (listOf(JevSettings.YOUTUBE, JevSettings.INSTAGRAM, "ALL") + listOfNotNull(pkg).filter(s::targets)).distinct().associateWith { targetPkg ->
            getAppSuppressionInfo(targetPkg, now, nowWall)
        }
        val reasons=if(c!=null)JevCandidatePolicy.reasons(c,s) else emptyList()
        val isDarkAmbient = c != null && (c.ambientLux ?: 999f) <= com.example.itv.domain.coaching.ContextualPreInterventionPolicy.DARK_LUX_THRESHOLD && !c.isNearOrOccluded
        if (isDarkAmbient) {
            if (darkSince == null) darkSince = now
        } else {
            darkSince = null
            if (c != null && (c.ambientLux ?: 0f) >= 30f) {
                suddenDarkTriggeredSession = null
            }
        }
        val isSustainedDarkness = darkSince != null && (now - darkSince!! >= 5000L)
        val suddenDarknessEvent = isSustainedDarkness && c != null && (suddenDarkTriggeredSession != (allSessionId ?: c.sessionId))
        val isGeneralOrLongform = c != null && (c.packageName == "ALL" || (c.rawGeneralMs > c.rawShortformMs))
        val minInitialMs = if (isGeneralOrLongform) 120_000L else 90_000L
        val sessionAsked = sessionControl.asked
        val initialProbeEligible = c != null && c.continuousMs >= minInitialMs && !sessionAsked

        val effectiveReasons = when {
            reasons.isNotEmpty() -> reasons
            suddenDarknessEvent -> listOf("SUDDEN_DARK_ENVIRONMENT")
            initialProbeEligible -> listOf(if (isGeneralOrLongform) "INITIAL_LONGFORM_PROBE" else "INITIAL_90S_PROBE")
            else -> emptyList()
        }

        val isSuppressedNow = c != null && (
            control?.suppressedSession == c.sessionId ||
                (allSessionId != null && sessionControl.suppressedSession == allSessionId)
            )
        val effectiveDeferUntil = maxOf(control?.deferUntil ?: 0L, sessionControl.deferUntil)

        val block = when {
            !s.collectionConsent -> "외부 전송 동의 필요"
            !store.tokenConfigured.value -> "테스트 토큰 필요"
            !CoachingConsentStore.granted(appContext) -> "기존 사용량 수집 동의 필요"
            !unlocked -> "화면 꺼짐·잠금"
            guard!=null -> guard
            c==null || c.quality!="VALID" -> "사용 화면 관측 대기"
            missionActive -> "미션 진행·완료 안내 대기"
            offerVisible -> "개입 표시 중"
            isSuppressedNow -> {
                val info = suppressionMap["ALL"] ?: suppressionMap[c.packageName]
                val remSec = info?.sessionResetRemainingSeconds ?: (JevSessionTracker.SESSION_TIMEOUT_MS / 1000L)
                val remM = remSec / 60
                val remS = remSec % 60
                "현재 사용 구간 재제안 중단 (앱 15분 미사용 시 만료 · 남은 시간: ${remM}분 ${remS}초)"
            }
            effectiveDeferUntil > now -> {
                if(effectiveDeferUntil==Long.MAX_VALUE) "재부팅으로 보류 시각 확인 불가 · 상태 초기화 필요"
                else {
                    val remSec = maxOf(0L, (effectiveDeferUntil - now) / 1000L)
                    val remM = remSec / 60
                    val remS = remSec % 60
                    "‘나중에’ 일시 보류 중 (남은 시간: ${remM}분 ${remS}초)"
                }
            }
            effectiveReasons.isEmpty() -> "호출 조건 미충족"
            else -> null
        }
        val result=approval
        // 동적 재평가 간격(intervalSec)이 이미 일반/롱폼 가중치를 반영하므로, 추가 시청 요구 시간은 물리 사용 시간(raw) 기준으로 맞춰
        // 1:3 앱에서 "6분 후 재평가"가 실제 18분으로 3배 늘어나는 왜곡을 방지합니다.
        val allRawTotalMs = tracker.states["ALL"]?.totalRawMs ?: ((c?.rawShortformMs ?: 0L) + (c?.rawGeneralMs ?: 0L))
        val pkgRawTotalMs = c?.let { tracker.states[it.packageName]?.totalRawMs ?: (it.rawShortformMs + it.rawGeneralMs) } ?: 0L
        val effectiveControl = if (sessionControl.asked) sessionControl else (control ?: lastObservedContext?.let { controls[it.packageName] })
        val intervalSec = effectiveControl?.dynamicIntervalSeconds ?: mutableState.value.dynamicIntervalSeconds ?: 300
        val nextEvalIn = if (sessionControl.asked) maxOf(0L, (intervalSec * 1000L - (now - sessionControl.at)) / 1000L) else 0L
        val addViewingNeeded = if (sessionControl.asked && c != null) maxOf(0L, (intervalSec * 1000L - (allRawTotalMs - sessionControl.total)) / 1000L) else 0L
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
            now >= networkRetryAfterElapsed &&
            (safeSince?.let { now-it>=5000 }==true)) {
            val regularTimeElapsed = !sessionControl.asked || (now-sessionControl.at>=intervalSec*1000L &&
                allRawTotalMs-sessionControl.total>=intervalSec*1000L)
            val eligible = regularTimeElapsed || suddenDarknessEvent
            if(eligible) {
                val prevSessionAsked = sessionControl.asked
                val prevSessionAt = sessionControl.at
                val prevSessionTotal = sessionControl.total
                val prevControlAsked = control.asked
                val prevControlAt = control.at
                val prevControlTotal = control.total
                val prevSuddenDarkSession = suddenDarkTriggeredSession
                if (suddenDarknessEvent) {
                    suddenDarkTriggeredSession = allSessionId ?: c.sessionId
                }
                sessionControl.asked=true;sessionControl.at=now;sessionControl.total=allRawTotalMs
                control.asked=true;control.at=now;control.total=pkgRawTotalMs
                persist()
                inFlight=scope.launch {
                    try {
                        val answer=repository.evaluate(c,s,"LIVE",effectiveReasons.joinToString("|"))
                        networkRetryAfterElapsed = 0L
                        val effThreshold = JevCandidatePolicy.effectiveThreshold(s, c)
                        val isGen = c.packageName == "ALL" || (c.rawGeneralMs > c.rawShortformMs)
                        val dynamicSec = JevCandidatePolicy.calculateDynamicInterval(effThreshold, answer.probability, isGen)
                        val gap = (effThreshold - answer.probability).coerceAtLeast(0.0)
                        sessionControl.dynamicIntervalSeconds = dynamicSec
                        sessionControl.lastGap = gap
                        control.dynamicIntervalSeconds = dynamicSec
                        control.lastGap = gap
                        mutableState.value=mutableState.value.copy(
                            probability=answer.probability,
                            dynamicIntervalSeconds=dynamicSec,
                            lastProbabilityGap=gap
                        )
                        val doneAt=SystemClock.elapsedRealtime()
                        val fgNow=JevObservationHub.foregroundPackage
                        val sameForeground=(fgNow==c.packageName || (c.packageName=="ALL" && fgNow!=null && s.targets(fgNow))) &&
                            doneAt-JevObservationHub.checkedAt in 0..FOREGROUND_FRESHNESS_MS
                        val fresh=(state.value.context ?: c.takeIf { sameForeground })?.let {
                            if(sameForeground && (it.packageName==c.packageName || c.packageName=="ALL") && it.sessionId==c.sessionId) it.copy(quality="VALID") else it
                        }
                        when {
                            !attached || !enabled || !JevCandidatePolicy.resultIsCurrent(s.revision,store.settings.value,c,fresh,doneAt-now) ->
                                suppress(answer.trial,"STALE_CONTEXT_OR_SETTINGS")
                            !JevCandidatePolicy.passes(answer.probability,s,c) -> suppress(answer.trial,"BELOW_PROBABILITY_THRESHOLD")
                            !s.actualIntervention -> suppress(answer.trial,"OBSERVE_ONLY")
                            else -> approval=Approval(answer.trial,s,c,doneAt)
                        }
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) {
                        val isDailyLimit = (e as? RelayFailure)?.code == "DAILY_LIMIT_REACHED"
                        if (isDailyLimit) {
                            networkRetryAfterElapsed = SystemClock.elapsedRealtime() + 300_000L
                            mutableState.value=mutableState.value.copy(status="일일 Jev 평가 한도 도달 (${s.dailyLimit}회)")
                            store.notice.value="오늘 Jev 평가 한도(${s.dailyLimit}회)에 도달했습니다."
                        } else {
                            // 일시적 네트워크 오류로 5~18분간 재요청이 봉쇄되지 않도록 호출 상태를 복구하고 30초 백오프 후 재시도합니다.
                            sessionControl.asked = prevSessionAsked
                            sessionControl.at = prevSessionAt
                            sessionControl.total = prevSessionTotal
                            control.asked = prevControlAsked
                            control.at = prevControlAt
                            control.total = prevControlTotal
                            if (suddenDarknessEvent) {
                                suddenDarkTriggeredSession = prevSuddenDarkSession
                            }
                            networkRetryAfterElapsed = SystemClock.elapsedRealtime() + NETWORK_ERROR_BACKOFF_MS
                            persist()
                            mutableState.value=mutableState.value.copy(status="Jev 요청 보류 (30초 후 재시도) · "+(e.message ?: "오류"))
                            store.notice.value=e.message
                        }
                    }
                }
            }
        }
        val persistIntervalMs = if (com.example.itv.infrastructure.observation.accessibility.WritingGuard.inAppForeground) {
            30_000L
        } else {
            5_000L
        }
        if (now - savedAt >= persistIntervalMs) persist()
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
        val allControl = controls["ALL"]
        val allSessionId = tracker.states["ALL"]?.id
        val isSuppressed = control?.suppressedSession == a.captured.sessionId ||
            (allSessionId != null && allControl?.suppressedSession == allSessionId)
        val effectiveDeferUntil = maxOf(control?.deferUntil ?: 0L, allControl?.deferUntil ?: 0L)
        val probe=when {
            pkg == JevSettings.INSTAGRAM || fgPkg == JevSettings.INSTAGRAM -> ReelsProbe.screenState()
            pkg == JevSettings.YOUTUBE || fgPkg == JevSettings.YOUTUBE -> YoutubeProbe.screenState()
            else -> 0
        }
        val sameForeground=(fgPkg==pkg || (pkg=="ALL" && fgPkg!=null && s.targets(fgPkg))) && now-JevObservationHub.checkedAt in 0..FOREGROUND_FRESHNESS_MS
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
            !isSuppressed && effectiveDeferUntil<=now &&
            WritingGuard.blockReason()==null && (transition || safeSince?.let { now-it>=5000 }==true) &&
            JevCandidatePolicy.resultIsCurrent(a.settings.revision,s,a.captured,currentContext,effectiveAgeMs)
    }
    fun approvedId(): String? = approval?.trial?.id
    fun approvedPackage(): String? = approval?.captured?.packageName
    fun hasActiveOffer() = active != null
    fun canKeepDisplayed(): Boolean {
        if (WritingGuard.inAppForeground) return false
        val displayed = displayedApproval ?: return false
        val now = SystemClock.elapsedRealtime()
        val currentFg = JevObservationHub.foregroundPackage
        val sameForeground = (currentFg == displayed.captured.packageName ||
            (displayed.captured.packageName == "ALL" && currentFg != null && store.settings.value.targets(currentFg)) ||
            JevObservationHub.isTransientSystemPackage(currentFg)) &&
            now - JevObservationHub.checkedAt in 0..FOREGROUND_FRESHNESS_MS
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
        val allControl = controls["ALL"]
        val allSessionId = tracker.states["ALL"]?.id
        val isSuppressed = control?.suppressedSession == a.captured.sessionId ||
            (allSessionId != null && allControl?.suppressedSession == allSessionId)
        val effectiveDeferUntil = maxOf(control?.deferUntil ?: 0L, allControl?.deferUntil ?: 0L)
        val probe = when {
            pkg == JevSettings.INSTAGRAM || fgPkg == JevSettings.INSTAGRAM -> ReelsProbe.screenState()
            pkg == JevSettings.YOUTUBE || fgPkg == JevSettings.YOUTUBE -> YoutubeProbe.screenState()
            else -> 0
        }
        val sameForeground = (fgPkg == pkg || (pkg == "ALL" && fgPkg != null && s.targets(fgPkg))) && now - JevObservationHub.checkedAt in 0..FOREGROUND_FRESHNESS_MS
        if (!sameForeground) {
            val appLabel = when (pkg) {
                JevSettings.YOUTUBE -> "YouTube"
                JevSettings.INSTAGRAM -> "Instagram"
                else -> pkg
            }
            return "현재 화면이 대상 앱(${appLabel})이 아님"
        }
        if (isSuppressed) {
            val info = getAppSuppressionInfo("ALL", now, System.currentTimeMillis())
            val remSec = info.sessionResetRemainingSeconds ?: (JevSessionTracker.SESSION_TIMEOUT_MS / 1000L)
            val remM = remSec / 60
            val remS = remSec % 60
            return "현재 사용 구간에서 이미 사용자가 응답하여 재개입 억제 중 (앱 15분 미사용 시 만료 · 남은 시간: ${remM}분 ${remS}초)"
        }
        if (effectiveDeferUntil > now) {
            val remainSec = maxOf(0L, (effectiveDeferUntil - now) / 1000L)
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
        displayedApproval=null
        val trial=active ?: return
        active=null
        val now = SystemClock.elapsedRealtime()
        val payloadJson = JSONObject(trial.payload)
        val pkg=payloadJson.optString("package_name")
        val control=controls.getOrPut(pkg){Attempt()}
        val allControl=controls.getOrPut("ALL"){Attempt()}
        val session=payloadJson.optString("session_id")
        val allSessionId = tracker.states["ALL"]?.id ?: session
        if(action in setOf("LATER","NO_RESPONSE")) {
            val deferUntil = now + com.example.itv.domain.coaching.TestCoachingPolicy.JEV_REMINDER_MS
            control.deferUntil = deferUntil
            control.lastOutcome = action
            control.lastOutcomeAt = now
            allControl.deferUntil = deferUntil
            allControl.lastOutcome = action
            allControl.lastOutcomeAt = now
        } else if(action in setOf("REST","DISMISS")) {
            control.suppressedSession = session
            control.lastOutcome = action
            control.lastOutcomeAt = now
            allControl.suppressedSession = allSessionId
            allControl.lastOutcome = action
            allControl.lastOutcomeAt = now
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
        resetContentSwitchCounters()
        resetProbability("MISSION_REST_SUCCESS")
        approval = null
        displayedApproval = null
    }

    /** 휴식 미션 성공 또는 3분 이상 자연 휴식 시 롱폼 전환 횟수와 숏폼 스와이프 수, 직전 Jev 개입 확률을 초기화합니다. */
    private fun resetContentSwitchCounters() {
        com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker.resetSessionChain()
        YoutubeProbe.resetSwipeCounts()
        ReelsProbe.resetSwipeCounts()
        resetProbability("CONTENT_SWITCH_RESET")
    }

    /** 세션 종료, 자연 휴식(화면 꺼짐 3분), 휴식 미션 완료 시 HUD 및 Jev 상태의 직전 확률과 카테고리 누적을 초기화합니다. */
    fun resetProbability(reason: String = "RESET") {
        com.example.itv.infrastructure.observation.SessionCategoryTracker.clear()
        val cur = mutableState.value
        mutableState.value = cur.copy(
            probability = null,
            lastProbabilityGap = null,
            status = if (store.tokenConfigured.value && store.settings.value.collectionConsent) "첫 Jev 확률 측정 전" else cur.status
        )
        for (attempt in controls.values) {
            attempt.lastGap = null
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
        val json=JSONObject()
            .put("boot",bootId)
            .put("sessionCategories", com.example.itv.infrastructure.observation.SessionCategoryTracker.toJson())
        for((pkg,frame) in tracker.states) {
            val c=controls[pkg] ?: Attempt()
            json.put(pkg,JSONObject().put("session",frame.id).put("total",frame.totalMs)
                .put("totalShortform",frame.totalShortformMs).put("totalMid",frame.totalMidMs).put("totalLow",frame.totalLowMs)
                .put("entered",frame.entered)
                .put("lastActiveWallMs",lastActiveWallMs[pkg] ?: 0L).put("lastActiveDate",lastActiveDate[pkg] ?: "")
                .put("at",c.at).put("attemptTotal",c.total).put("attemptRawTotal",c.total).put("asked",c.asked)
                .put("suppressed",c.suppressedSession ?: "").put("deferUntil",c.deferUntil).put("attemptSession",c.lastSession ?: frame.id)
                .put("dynInterval", c.dynamicIntervalSeconds).put("lastGap", c.lastGap ?: -1.0)
                .put("lastOutcome", c.lastOutcome ?: ""))
        }
        val serialized = json.toString()
        if (serialized == lastPersistedJson) return
        lastPersistedJson = serialized
        scope.launch {
            persistMutex.lock()
            try { runCatching { store.writeControl("runtime", serialized) }.onFailure { store.notice.value="관측 상태 저장 실패" } }
            finally { persistMutex.unlock() }
        }
    }
    fun sessionState(pkg: String) = tracker.states[pkg]

    fun getAppSuppressionInfo(
        pkg: String,
        now: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis()
    ): JevAppSuppressionInfo {
        val pkgControl = controls[pkg]
        val allControl = controls["ALL"]
        val frame = tracker.states[pkg]
        val allFrame = tracker.states["ALL"]
        val currentSessionId = frame?.id
        val allSessionId = allFrame?.id
        val isPkgSuppressed = pkgControl?.suppressedSession != null && pkgControl.suppressedSession == currentSessionId
        val isAllSuppressed = allControl?.suppressedSession != null && allControl.suppressedSession == allSessionId
        val isSuppressed = isPkgSuppressed || isAllSuppressed
        val effectiveDeferUntil = maxOf(pkgControl?.deferUntil ?: 0L, allControl?.deferUntil ?: 0L)
        val isDeferred = effectiveDeferUntil > now
        val cadenceControl = when {
            allControl?.asked == true -> allControl
            pkgControl?.asked == true -> pkgControl
            else -> allControl ?: pkgControl
        }
        val isAsked = cadenceControl?.asked == true

        val prevWall = if (isAllSuppressed && !isPkgSuppressed) (lastActiveWallMs["ALL"] ?: 0L) else (lastActiveWallMs[pkg] ?: 0L)
        val awayWallMs = if (prevWall > 0L) maxOf(0L, nowWall - prevWall) else 0L
        val frameAwayMs = if (isAllSuppressed && !isPkgSuppressed) (allFrame?.awayMs ?: 0L) else (frame?.awayMs ?: 0L)
        val awayMs = maxOf(frameAwayMs, awayWallMs)

        val sessionResetRemainingSeconds = if (isSuppressed) {
            maxOf(0L, (JevSessionTracker.SESSION_TIMEOUT_MS - awayMs + 999L) / 1000L)
        } else null

        val deferRemainingSeconds = if (isDeferred) {
            maxOf(0L, (effectiveDeferUntil - now + 999L) / 1000L)
        } else null

        val intervalSec = cadenceControl?.dynamicIntervalSeconds ?: 300
        val nextEvaluationInSeconds = if (isAsked && !isSuppressed && !isDeferred) {
            maxOf(0L, (intervalSec * 1000L - (now - (cadenceControl?.at ?: now)) + 999L) / 1000L)
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
            sessionId = currentSessionId ?: allSessionId,
            isSuppressed = isSuppressed,
            isDeferred = isDeferred,
            isAsked = isAsked,
            awayMs = awayMs,
            sessionResetRemainingSeconds = sessionResetRemainingSeconds,
            deferRemainingSeconds = deferRemainingSeconds,
            nextEvaluationInSeconds = nextEvaluationInSeconds,
            dynamicIntervalSeconds = intervalSec,
            statusText = statusText,
            lastProbabilityGap = cadenceControl?.lastGap
        )
    }

    /** Returns the most relevant active suppression/dynamic-interval info across all observed apps. */
    fun latestSuppressionInfo(
        now: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis()
    ): JevAppSuppressionInfo {
        if (controls["ALL"]?.asked == true ||
            controls["ALL"]?.suppressedSession != null ||
            (controls["ALL"]?.deferUntil ?: 0L) > now
        ) {
            return getAppSuppressionInfo("ALL", now, nowWall)
        }
        val lastPkg = lastObservedContext?.packageName
        if (lastPkg != null && controls[lastPkg]?.asked == true) {
            return getAppSuppressionInfo(lastPkg, now, nowWall)
        }
        val latestAskedPkg = controls.entries
            .filter { it.value.asked }
            .maxByOrNull { it.value.at }
            ?.key
        if (latestAskedPkg != null) {
            return getAppSuppressionInfo(latestAskedPkg, now, nowWall)
        }
        if (lastPkg != null) {
            return getAppSuppressionInfo(lastPkg, now, nowWall)
        }
        return getAppSuppressionInfo("ALL", now, nowWall)
    }

    /** Explicit operator action for a test run, never automatic on restart or settings edits. */
    fun resetTestObservation() {
        approval?.let { suppress(it.trial,"OPERATOR_RESET") };approval=null
        resetContentSwitchCounters()
        tracker.states.clear();controls.clear();lastActiveWallMs.clear();lastActiveDate.clear();safeSince=null;lastObservedContext=null;persist()
        mutableState.value=JevLiveState("테스트 관측 상태 초기화 · 재관측 필요")
    }
    companion object {
        const val FOREGROUND_FRESHNESS_MS = 4000L
        const val NETWORK_ERROR_BACKOFF_MS = 30_000L
        const val SHORTFORM_TRANSITION_TIMEOUT_MS = 90_000L
        @Volatile private var instance: JevRuntime?=null
        fun get(context: Context)=instance ?: synchronized(this) { instance ?: JevRuntime(context.applicationContext).also { instance=it } }
    }
}
