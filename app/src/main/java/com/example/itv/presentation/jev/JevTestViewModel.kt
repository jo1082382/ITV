package com.example.itv.presentation.jev

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.itv.domain.jev.*
import com.example.itv.infrastructure.jev.*
import com.example.itv.presentation.coaching.CoachingAutoStart
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

enum class SimAppCategory(
    val label: String,
    val shortLabel: String,
    val packageName: String,
    val appCategory: String,
    val activityPhase: String,
    val youtubeWatchMode: String?,
    val isShortform: Boolean,
    val defaultDivisor: Double,
    val defaultWeightLabel: String,
    val curveMinutes: List<Int>,
) {
    SHORTFORM(
        label = "숏폼 (쇼츠/릴스)",
        shortLabel = "숏폼",
        packageName = JevSettings.YOUTUBE,
        appCategory = "SHORTFORM",
        activityPhase = "BROWSING",
        youtubeWatchMode = "SHORTS",
        isShortform = true,
        defaultDivisor = 1.0,
        defaultWeightLabel = "1:1",
        curveMinutes = listOf(5, 10, 15, 20)
    ),
    FEED_SNS(
        label = "피드·SNS (인스타/커뮤니티)",
        shortLabel = "피드/SNS",
        packageName = JevSettings.INSTAGRAM,
        appCategory = "COMMUNITY_SNS",
        activityPhase = "BROWSING",
        youtubeWatchMode = null,
        isShortform = false,
        defaultDivisor = 2.0,
        defaultWeightLabel = "1:2",
        curveMinutes = listOf(10, 20, 30, 45)
    ),
    LONGFORM(
        label = "유튜브 롱폼",
        shortLabel = "유튜브 롱폼",
        packageName = JevSettings.YOUTUBE,
        appCategory = "LONGFORM_VIDEO",
        activityPhase = "VIDEO_PLAYING",
        youtubeWatchMode = "PORTRAIT_WATCH",
        isShortform = false,
        defaultDivisor = 3.0,
        defaultWeightLabel = "1:3",
        curveMinutes = listOf(15, 30, 45, 60)
    ),
    GAME(
        label = "모바일 게임",
        shortLabel = "게임",
        packageName = "com.example.game",
        appCategory = "GAME",
        activityPhase = "ACTIVE_GAMEPLAY",
        youtubeWatchMode = null,
        isShortform = false,
        defaultDivisor = 2.0,
        defaultWeightLabel = "1:2",
        curveMinutes = listOf(10, 20, 30, 45)
    ),
    BROWSER_GENERAL(
        label = "웹·일반 앱",
        shortLabel = "웹/일반",
        packageName = "com.android.chrome",
        appCategory = "BROWSER_SEARCH",
        activityPhase = "ACTIVE",
        youtubeWatchMode = null,
        isShortform = false,
        defaultDivisor = 3.0,
        defaultWeightLabel = "1:3",
        curveMinutes = listOf(15, 30, 45, 60)
    ),
    DOCUMENT_MESSENGER(
        label = "문서·메신저",
        shortLabel = "문서/메신저",
        packageName = "com.kakao.talk",
        appCategory = "DOCUMENT",
        activityPhase = "ACTIVE",
        youtubeWatchMode = null,
        isShortform = false,
        defaultDivisor = 3.0,
        defaultWeightLabel = "1:3",
        curveMinutes = listOf(15, 30, 45, 60)
    )
}

enum class SimWeightMode(val label: String, val shortLabel: String, val divisor: Double?) {
    APP_3_TIER("앱 기본 (3단계)", "앱 기본", null),
    CURRENT_1_TO_3("1:3 고정", "1:3", 3.0),
    TEST_1_TO_2("1:2 고정", "1:2", 2.0),
    RAW_1_TO_1("1:1 원본", "1:1", 1.0)
}

/** 로컬 개입 기준 확률 변동 비교 전용 (Jev 서버 요청에는 전송하지 않음). */
enum class SimEnvironment(
    val label: String,
    val shortLabel: String,
    val ambientLux: Float,
    val screenBrightness: Int,
    val bedtimeDistanceMinutes: Int,
    val bedtimePassedMinutes: Int?,
    val userEnvironmentState: String,
) {
    DAYTIME_BRIGHT(
        label = "주간 · 밝은 환경",
        shortLabel = "주간(밝음)",
        ambientLux = 250f,
        screenBrightness = 200,
        bedtimeDistanceMinutes = -480,
        bedtimePassedMinutes = null,
        userEnvironmentState = "DAYTIME_LIT"
    ),
    INDOOR_NEUTRAL(
        label = "평상시 실내 (기준)",
        shortLabel = "실내 기준",
        ambientLux = 35f,
        screenBrightness = 110,
        bedtimeDistanceMinutes = -240,
        bedtimePassedMinutes = null,
        userEnvironmentState = "NORMAL_INDOOR"
    ),
    DARK_NON_BEDTIME(
        label = "어두운 환경 (취침 외)",
        shortLabel = "어두움(취침외)",
        ambientLux = 3f,
        screenBrightness = 50,
        bedtimeDistanceMinutes = -240,
        bedtimePassedMinutes = null,
        userEnvironmentState = "DARK_INDOOR"
    ),
    DARK_PRE_BEDTIME(
        label = "어두운 환경 + 취침 전(-45분)",
        shortLabel = "취침전 암실",
        ambientLux = 3f,
        screenBrightness = 40,
        bedtimeDistanceMinutes = -45,
        bedtimePassedMinutes = null,
        userEnvironmentState = "PRE_BEDTIME_DARK"
    ),
    DARK_BEDTIME(
        label = "어두운 환경 + 취침 시간(+30분)",
        shortLabel = "취침+암실",
        ambientLux = 2f,
        screenBrightness = 30,
        bedtimeDistanceMinutes = 30,
        bedtimePassedMinutes = 30,
        userEnvironmentState = "BEDTIME_DARK"
    )
}

/** 로컬 개입 기준 확률 변동 비교 전용 (Jev 서버 요청에는 전송하지 않음). */
enum class SimSessionRatio(val label: String, val shortLabel: String, val ratio: Double) {
    SHORT("짧음 (평균 0.5배)", "0.5배", 0.5),
    NORMAL("보통 (평균 1.0배)", "1.0배", 1.0),
    HIGH("과몰입 (평균 1.5배)", "1.5배", 1.5)
}

/** 로컬 개입 기준 확률 변동 비교 전용 (Jev 서버 요청에는 전송하지 않음). */
enum class SimImmersionLevel(
    val label: String,
    val shortLabel: String,
    val spm1m: Double,
    val swipeAccel: Double,
    val fastSwipeStreak: Int,
    val swipeOverheated: Boolean,
    val shortformSwipeCount: Int,
    val longformChainCount: Int,
) {
    LOW(
        label = "차분함 (낮음)",
        shortLabel = "몰입 낮음",
        spm1m = 3.0,
        swipeAccel = 0.0,
        fastSwipeStreak = 0,
        swipeOverheated = false,
        shortformSwipeCount = 5,
        longformChainCount = 0
    ),
    MEDIUM(
        label = "보통 몰입",
        shortLabel = "몰입 보통",
        spm1m = 9.0,
        swipeAccel = 1.0,
        fastSwipeStreak = 2,
        swipeOverheated = false,
        shortformSwipeCount = 26,
        longformChainCount = 3
    ),
    HIGH(
        label = "강한 몰입 (과열)",
        shortLabel = "몰입 과열",
        spm1m = 15.0,
        swipeAccel = 3.5,
        fastSwipeStreak = 4,
        swipeOverheated = true,
        shortformSwipeCount = 50,
        longformChainCount = 5
    )
}

data class SimContextPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val environment: SimEnvironment,
    val sessionRatio: SimSessionRatio,
    val immersion: SimImmersionLevel,
)

object SimContextPresets {
    val all: List<SimContextPreset> = listOf(
        SimContextPreset(
            id = "daytime_short",
            title = "주간 · 밝은 환경 + 짧은 세션",
            subtitle = "주간 250lx · 평균 0.5배 · 몰입 낮음",
            environment = SimEnvironment.DAYTIME_BRIGHT,
            sessionRatio = SimSessionRatio.SHORT,
            immersion = SimImmersionLevel.LOW
        ),
        SimContextPreset(
            id = "daytime_normal",
            title = "주간 · 밝은 환경 (보통)",
            subtitle = "주간 250lx · 평균 1.0배 · 몰입 낮음",
            environment = SimEnvironment.DAYTIME_BRIGHT,
            sessionRatio = SimSessionRatio.NORMAL,
            immersion = SimImmersionLevel.LOW
        ),
        SimContextPreset(
            id = "indoor_baseline",
            title = "평상시 실내 (기본 기준선)",
            subtitle = "실내 35lx · 평균 1.0배 · 몰입 낮음",
            environment = SimEnvironment.INDOOR_NEUTRAL,
            sessionRatio = SimSessionRatio.NORMAL,
            immersion = SimImmersionLevel.LOW
        ),
        SimContextPreset(
            id = "dark_non_bedtime",
            title = "어두운 환경 (취침 시간 외)",
            subtitle = "암실 3lx · 취침 외 · 평균 1.0배",
            environment = SimEnvironment.DARK_NON_BEDTIME,
            sessionRatio = SimSessionRatio.NORMAL,
            immersion = SimImmersionLevel.LOW
        ),
        SimContextPreset(
            id = "dark_pre_bedtime",
            title = "어두운 환경 + 취침 전(-45분)",
            subtitle = "암실 3lx · 취침 45분 전 · 평균 1.0배",
            environment = SimEnvironment.DARK_PRE_BEDTIME,
            sessionRatio = SimSessionRatio.NORMAL,
            immersion = SimImmersionLevel.LOW
        ),
        SimContextPreset(
            id = "dark_bedtime",
            title = "어두운 환경 + 취침 시간(+30분)",
            subtitle = "암실 2lx · 취침 30분 경과 · 평균 1.0배",
            environment = SimEnvironment.DARK_BEDTIME,
            sessionRatio = SimSessionRatio.NORMAL,
            immersion = SimImmersionLevel.LOW
        ),
        SimContextPreset(
            id = "session_over",
            title = "세션 비율 과몰입 (평균 1.5배)",
            subtitle = "실내 기준 · 평소 세션의 1.5배 사용",
            environment = SimEnvironment.INDOOR_NEUTRAL,
            sessionRatio = SimSessionRatio.HIGH,
            immersion = SimImmersionLevel.LOW
        ),
        SimContextPreset(
            id = "immersion_high",
            title = "높은 몰입도 (전환·스와이프 과열)",
            subtitle = "실내 기준 · 숏폼 15SPM/50회 · 롱폼 5회 전환",
            environment = SimEnvironment.INDOOR_NEUTRAL,
            sessionRatio = SimSessionRatio.NORMAL,
            immersion = SimImmersionLevel.HIGH
        ),
        SimContextPreset(
            id = "max_risk",
            title = "최고 위험 복합 (취침 암실+1.5배+고몰입)",
            subtitle = "취침+암실 · 평균 1.5배 · 몰입 과열",
            environment = SimEnvironment.DARK_BEDTIME,
            sessionRatio = SimSessionRatio.HIGH,
            immersion = SimImmersionLevel.HIGH
        )
    )
}

/**
 * 순수 카테고리 + 사용 시간 + 환산 비율 기반 Jev 시뮬레이션 요청.
 * 환경·세션 비율·몰입도는 Jev로 전송하지 않으며, 로컬 기준 비교에만 별도로 적용됩니다.
 */
data class CategorySimRequest(
    val category: SimAppCategory,
    val rawMinutes: Int,
    val weightMode: SimWeightMode,
    val labelOverride: String? = null,
) {
    val effectiveDivisor: Double
        get() = if (category.isShortform) 1.0 else (weightMode.divisor ?: category.defaultDivisor)

    val effectiveWeightLabel: String
        get() = if (category.isShortform) "1:1" else if (weightMode.divisor == null) category.defaultWeightLabel else weightMode.shortLabel

    val equivalentMinutes: Double
        get() = (rawMinutes.toDouble() / effectiveDivisor).coerceAtLeast(1.0 / 60.0)

    /**
     * Jev 서버로 전송하는 순수 컨텍스트:
     * 카테고리·실사용 시간·등가 환산 시간만 포함하고, 환경/조도/취침/세션 비율/몰입도는 일체 포함하지 않습니다.
     */
    fun toPureJevContext(nowMs: Long = System.currentTimeMillis()): JevContext {
        val rawMs = rawMinutes.coerceIn(1, 1440) * 60_000L
        val eqMs = (rawMs / effectiveDivisor).toLong().coerceIn(1_000L, rawMs)
        val rawShortMs = if (category.isShortform) rawMs else 0L
        val rawGenMs = if (category.isShortform) 0L else rawMs
        val isLong = category == SimAppCategory.LONGFORM

        return JevContext(
            sessionId = "sim-${UUID.randomUUID()}",
            packageName = category.packageName,
            sessionMs = eqMs,
            continuousMs = eqMs,
            returns10m = 0,
            returnsObservedSeconds = 600,
            bedtimePassedMinutes = null,
            observedAt = nowMs,
            rawShortformMs = rawShortMs,
            rawGeneralMs = rawGenMs,
            quality = "VALID",
            longformWatchMs = if (isLong) rawMs else 0L,
            currentVideoWatchMs = if (isLong) rawMs else 0L,
            completedLongformCount = 0,
            youtubeWatchMode = category.youtubeWatchMode,
            appCategory = category.appCategory,
            activityPhase = category.activityPhase
        )
    }

    /**
     * 우리 앱 내부의 동적 개입 기준 확률 변동(computeThresholdAdjustment)을 계산하기 위한 로컬 비교 전용 컨텍스트.
     * 네트워크로 전송되지 않습니다.
     */
    fun toLocalThresholdContext(
        environment: SimEnvironment,
        sessionRatio: SimSessionRatio,
        immersion: SimImmersionLevel,
        nowMs: Long = System.currentTimeMillis(),
    ): JevContext {
        val base = toPureJevContext(nowMs)
        val eqMinutes = base.continuousMs / 60_000.0
        val personalAvgMin = (eqMinutes / sessionRatio.ratio).coerceIn(0.1, 1440.0)
        val isShort = category == SimAppCategory.SHORTFORM
        val isLong = category == SimAppCategory.LONGFORM

        return base.copy(
            bedtimePassedMinutes = environment.bedtimePassedMinutes,
            personalAvgSessionMinutes = personalAvgMin,
            sessionVsAvgRatio = sessionRatio.ratio,
            effectiveSpm1m = if (isShort) immersion.spm1m else null,
            effectiveSessionSpm = if (isShort) immersion.spm1m else null,
            swipeAccel1m = if (isShort) immersion.swipeAccel else null,
            fastSwipeStreak = if (isShort) immersion.fastSwipeStreak else 0,
            swipeOverheated = isShort && immersion.swipeOverheated,
            ambientLux = environment.ambientLux,
            screenBrightness = environment.screenBrightness,
            isNearOrOccluded = false,
            bedtimeDistanceMinutes = environment.bedtimeDistanceMinutes,
            userEnvironmentState = environment.userEnvironmentState,
            consecutiveLongformChainCount = if (isLong) immersion.longformChainCount else 0,
            shortformSwipeCount = if (isShort) immersion.shortformSwipeCount else 0
        )
    }
}

data class CategorySimResult(
    val id: String,
    val request: CategorySimRequest,
    val probability: Double,
    val baseThreshold: Double,
    val latencyMs: Long?,
    val model: String?,
    val recordedAt: Long,
) {
    val passedBase: Boolean get() = probability >= baseThreshold
}

data class ContextBubbleEvalResult(
    val id: String,
    val summaryTag: String,
    val probability: Double,
    val baseThreshold: Double,
    val effectiveThreshold: Double,
    val latencyMs: Long?,
    val model: String?,
    val recordedAt: Long,
) {
    val passedEffective: Boolean get() = probability >= effectiveThreshold
}

class JevTestViewModel(app: Application): AndroidViewModel(app) {
    val repository=JevRepository.get(app)
    val store=repository.store
    val runtime=JevRuntime.get(app)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val busy=MutableStateFlow(false)
    val message=MutableStateFlow<String?>(null)
    val manualAnswer=MutableStateFlow<JevAnswer?>(null)
    val simResults=MutableStateFlow<List<CategorySimResult>>(emptyList())
    val simProgress=MutableStateFlow<String?>(null)
    val contextBubbleResult=MutableStateFlow<ContextBubbleEvalResult?>(null)
    private var simBatchJob: Job? = null

    private fun action(block: suspend ()->Unit) {
        if(busy.value)return
        busy.value=true;message.value=null
        scope.launch {
            try { block() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { message.value=e.message ?: "처리 실패" }
            finally { busy.value=false }
        }
    }
    fun save(settings: JevSettings) = action {
        store.save(settings)
        CoachingAutoStart.sync(getApplication())
        message.value="설정 저장 완료 · 새 판단부터 적용됩니다."
    }
    fun saveToken(value: String,onSaved: ()->Unit) = action { store.saveToken(value);onSaved();message.value="토큰 저장 완료" }
    fun deleteToken() = action { store.deleteToken();message.value="토큰 삭제 완료" }
    fun checkConnection() = action { message.value=repository.connectionStatus() }
    fun manual(context: JevContext) = action {
        manualAnswer.value=repository.evaluate(context,store.settings.value,"MANUAL","MANUAL_INPUT")
        message.value="수동 판단 완료 · 실제 개입은 실행하지 않았습니다."
    }
    fun retry(id: String) = action { manualAnswer.value=repository.retryTrial(id);message.value="동일 요청 결과 확인 완료" }
    fun feedback(id: String,score: Int?) = action { repository.feedback(id,score);message.value="평가 저장 완료 · 서버 전송 대기열에 등록했습니다." }
    fun retryUploads() = action { store.dao.retry();repository.flush();message.value="전송 재시도 완료 · 남은 대기 건수를 확인해 주세요." }

    fun evaluateContextBubble(context: JevContext, summaryTag: String) = action {
        val settings = store.settings.value
        val effThreshold = JevCandidatePolicy.effectiveThreshold(settings, context)
        val (trial, probability) = repository.evaluate(context, settings, "MANUAL", "CONTEXT_BUBBLE_TEST")
        contextBubbleResult.value = ContextBubbleEvalResult(
            id = trial.id,
            summaryTag = summaryTag,
            probability = probability,
            baseThreshold = settings.probabilityThreshold,
            effectiveThreshold = effThreshold,
            latencyMs = trial.latencyMs,
            model = trial.model,
            recordedAt = System.currentTimeMillis()
        )
        message.value = if (probability >= effThreshold) {
            "Jev 확률 ${"%.1f".format(probability * 100.0)}% ≥ 최종 기준 ${"%.1f".format(effThreshold * 100.0)}% → 개입 조건 충족!"
        } else {
            "Jev 확률 ${"%.1f".format(probability * 100.0)}% < 최종 기준 ${"%.1f".format(effThreshold * 100.0)}% → 개입 보류"
        }
    }

    fun clearContextBubbleResult() {
        contextBubbleResult.value = null
    }

    fun runCategorySimulation(req: CategorySimRequest) {
        runCategorySimulations(listOf(req), reasonPrefix = "CATEGORY_SIM_SINGLE")
    }

    fun runCategorySimulations(requests: List<CategorySimRequest>, reasonPrefix: String = "CATEGORY_SIM_BATCH") {
        if (busy.value || requests.isEmpty()) return
        busy.value = true
        message.value = null
        simBatchJob = scope.launch {
            var completed = 0
            try {
                val settings = store.settings.value
                for ((index, req) in requests.withIndex()) {
                    val tag = req.labelOverride ?: "${req.category.shortLabel} ${req.rawMinutes}분"
                    simProgress.value = "${index + 1}/${requests.size} 측정 중 · $tag"
                    val pureCtx = req.toPureJevContext()
                    val reason = "${reasonPrefix}_${req.category.appCategory}_${req.rawMinutes}M"
                    val (trial, probability) = repository.evaluate(pureCtx, settings, "MANUAL", reason)
                    val result = CategorySimResult(
                        id = trial.id,
                        request = req,
                        probability = probability,
                        baseThreshold = settings.probabilityThreshold,
                        latencyMs = trial.latencyMs,
                        model = trial.model,
                        recordedAt = System.currentTimeMillis()
                    )
                    simResults.update { listOf(result) + it }
                    completed++
                }
                message.value = if (requests.size == 1) {
                    "순수 카테고리·시간 기준 Jev 확률 측정 완료 (환경·세션·몰입도 미전송)"
                } else {
                    "총 ${completed}건 측정 완료 · 아래에서 앱 내 9개 상황 기준과 비교해 보세요."
                }
            } catch (_: CancellationException) {
                message.value = "측정 취소됨 ($completed/${requests.size}건 완료)"
            } catch (e: Exception) {
                message.value = if (completed > 0) {
                    "$completed/${requests.size}건 완료 후 중단: ${e.message ?: "처리 실패"}"
                } else {
                    e.message ?: "처리 실패"
                }
            } finally {
                simProgress.value = null
                simBatchJob = null
                busy.value = false
            }
        }
    }

    fun cancelCategorySimulation() {
        simBatchJob?.cancel()
    }

    fun clearSimResults() {
        simResults.value = emptyList()
    }

    override fun onCleared() { scope.cancel();super.onCleared() }
}
