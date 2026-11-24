package com.example.itv.domain.coaching

import com.example.itv.domain.jev.JevCandidatePolicy
import com.example.itv.domain.jev.JevContext
import com.example.itv.domain.jev.JevSettings
import com.example.itv.infrastructure.observation.AmbientSnapshot
import com.example.itv.infrastructure.observation.BedtimePhase
import com.example.itv.infrastructure.observation.UserEnvironmentState
import java.time.LocalTime
import java.util.Locale
import kotlin.math.roundToInt

object InterventionReasonResolver {

    /**
     * 개입 발동 시 사용자에게 안내할 판단 사유를 최대 3가지 추출합니다.
     *
     * 원칙:
     * - 확률 임계치 보정과 무관한 사용 자세(posture)는 제외합니다.
     * - 우리 앱의 기본 판단 확률 임계치를 낮춘(마이너스 보정) 요인(취침 지연·어두운 조도·세션 초과·스와이프/영상전환 과몰입·연속 피로)과
     *   실제 콘텐츠별 연속 시청 시간(분 단위)을 우선순위로 상세히 안내합니다.
     */
    fun resolveReasons(
        isJev: Boolean,
        jevContext: JevContext?,
        continuousUsedMs: Long,
        ambient: AmbientSnapshot?,
        shortformAppName: String?,
        localTime: LocalTime = LocalTime.now()
    ): List<String> {
        val candidates = mutableListOf<PrioritizedReason>()
        val adj = jevContext?.let {
            JevCandidatePolicy.computeThresholdAdjustment(JevSettings(personalizedEnabled = true), it)
        }

        val isBedtimeWindow = if (ambient != null) {
            when (ambient.bedtimePhase) {
                BedtimePhase.PAST_BEDTIME -> true
                BedtimePhase.APPROACHING -> {
                    // 취침 1시간 이내: 반드시 암실(lux <= 25)이고 30초 이상 연속 사용한 경우에만 취침 개입 맥락 인정
                    val isDark = !ambient.isOccluded && ambient.lux != null && ambient.lux <= 25f
                    val continuousMs = jevContext?.continuousMs?.takeIf { it > 0 } ?: continuousUsedMs
                    isDark && continuousMs >= 30_000L
                }
                BedtimePhase.ACTIVE_HOURS -> false
                BedtimePhase.UNKNOWN -> {
                    // 미측정/초기 상태 fallback: 심야(23시~05시) 암실 환경일 때만 인정
                    (localTime.hour >= 23 || localTime.hour in 0..4) &&
                        !ambient.isOccluded &&
                        ambient.lux != null && ambient.lux <= 25f
                }
            }
        } else {
            localTime.hour >= 23 || localTime.hour in 0..4
        }

        val isDarkAmbient = ambient != null && !ambient.isOccluded && ambient.lux != null && ambient.lux <= 25f
        val isBrightScreen = (ambient?.screenBrightness ?: jevContext?.screenBrightness ?: 0) > 100
        val bedtimePassed = jevContext?.bedtimePassedMinutes ?: ambient?.bedtimeDistanceMinutes
        var includedDarkInBedtime = false

        // 1. 취침 시각 초과 + 어두운 환경 (임계치 최대 -30%p 마이너스 보정, 최우선)
        if (isBedtimeWindow && bedtimePassed != null) {
            if (bedtimePassed > 0) {
                if (isDarkAmbient) {
                    includedDarkInBedtime = true
                    candidates.add(
                        PrioritizedReason(
                            100,
                            "평소 취침보다 ${bedtimePassed}분 늦었고, 어두운 곳에서 보고 있어!"
                        )
                    )
                } else {
                    candidates.add(
                        PrioritizedReason(
                            100,
                            "평소 취침 시간보다 ${bedtimePassed}분이나 지났어!"
                        )
                    )
                }
            } else if (bedtimePassed == 0) {
                if (isDarkAmbient) {
                    includedDarkInBedtime = true
                    candidates.add(
                        PrioritizedReason(
                            98,
                            "약속한 취침 시간인데 어두운 방에서 보고 있어!"
                        )
                    )
                } else {
                    candidates.add(PrioritizedReason(98, "지금이 딱 약속한 취침 시간이야!"))
                }
            }
        } else if (isBedtimeWindow && (ambient?.bedtimePhase == BedtimePhase.PAST_BEDTIME || ambient == null)) {
            if (isDarkAmbient) {
                includedDarkInBedtime = true
                candidates.add(PrioritizedReason(95, "늦은 밤 어두운 곳에서 화면을 보고 있어!"))
            } else {
                candidates.add(PrioritizedReason(95, "벌써 늦은 밤이야, 내일을 위해 눈 붙이자!"))
            }
        }

        // 2. 어두운 조도 / 취침 연계 환경 (bedtimeDelta 마이너스 보정 요인 — 1번과 중복되지 않을 때만)
        if (ambient != null && !includedDarkInBedtime) {
            val isDaytime = !isBedtimeWindow && localTime.hour in 6..20
            val hasBedtimeDarkPenalty = (adj?.bedtimeDelta ?: 0.0) <= -0.05

            if (isDaytime && isDarkAmbient) {
                if (isBrightScreen) {
                    candidates.add(PrioritizedReason(92, "낮 시간인데 어두운 곳에서 밝은 화면을 보고 있어 눈이 지쳐!"))
                } else {
                    candidates.add(PrioritizedReason(91, "주변이 어두운 곳에서 화면을 보고 있어 눈이 피로해!"))
                }
            } else if (isBedtimeWindow) {
                when (ambient.userEnvironmentState) {
                    UserEnvironmentState.BEDTIME_DELAY_WATCH -> {
                        candidates.add(PrioritizedReason(93, "취침 시간인데 어두운 방에서 밝은 화면을 보고 있어 눈이 지쳐!"))
                    }
                    UserEnvironmentState.PRE_SLEEP_REST -> {
                        candidates.add(PrioritizedReason(91, "불을 끈 어두운 방에서 보고 있어! 이제 눈을 감고 잘 시간이야"))
                    }
                    UserEnvironmentState.NIGHT_ACTIVE -> {
                        candidates.add(PrioritizedReason(89, "평소 자던 시간보다 늦었어! 내일을 위해 슬슬 마무리하자!"))
                    }
                    UserEnvironmentState.INDOOR_DARK_ACTIVE -> {
                        candidates.add(PrioritizedReason(86, "어두운 실내에서 밝은 화면을 보고 있어 눈이 피로해!"))
                    }
                    else -> {
                        if (isDarkAmbient || hasBedtimeDarkPenalty) {
                            candidates.add(PrioritizedReason(90, "취침 전 어두운 곳에서 화면을 보고 있어 눈이 피로해!"))
                        }
                    }
                }
            } else if (isDarkAmbient) {
                // 일상 활동 시간대 어두운 환경: 취침 멘트는 배제하고 조도/눈 피로만 설명
                candidates.add(PrioritizedReason(86, "어두운 실내에서 밝은 화면을 보고 있어 눈이 피로해!"))
            }
        }

        // 3. 실제 시청 시간 (분 단위 · 콘텐츠 유형별 구체 설명 + 연속 피로 마이너스 보정 반영)
        val rawShortformMs = jevContext?.rawShortformMs ?: 0L
        val rawGeneralMs = jevContext?.rawGeneralMs ?: 0L
        val longformWatchMs = jevContext?.longformWatchMs ?: 0L
        val weightedContinuousMs = jevContext?.continuousMs?.takeIf { it > 0L } ?: continuousUsedMs
        val rawTotalMs = rawShortformMs + rawGeneralMs

        val isLongform = jevContext != null && (
            jevContext.youtubeWatchMode == "LONGFORM" ||
                (longformWatchMs >= 60_000L && rawShortformMs < longformWatchMs)
            )
        val isShortform = !isLongform && (
            shortformAppName != null || rawShortformMs >= 60_000L
            )

        val actualWatchMs = when {
            isLongform -> maxOf(longformWatchMs, rawGeneralMs, weightedContinuousMs)
            isShortform -> if (rawShortformMs > 0L) rawShortformMs else weightedContinuousMs
            rawTotalMs > 0L -> rawTotalMs
            else -> weightedContinuousMs
        }
        val actualWatchSec = (actualWatchMs / 1000L).toInt()
        val actualWatchMin = actualWatchSec / 60

        val watchPriority = if ((adj?.fatigueDelta ?: 0.0) <= -0.01) 89 else 87
        when {
            actualWatchMin >= 1 && isLongform -> {
                candidates.add(
                    PrioritizedReason(
                        watchPriority,
                        "유튜브 영상을 연속 ${actualWatchMin}분째 시청 중이야!"
                    )
                )
            }
            actualWatchMin >= 1 && isShortform -> {
                val label = shortformAppName ?: "숏폼"
                candidates.add(
                    PrioritizedReason(
                        watchPriority,
                        "${withObjectParticle(label)} ${actualWatchMin}분째 계속 보고 있어!"
                    )
                )
            }
            actualWatchMin >= 1 -> {
                candidates.add(
                    PrioritizedReason(
                        watchPriority,
                        "쉬지 않고 연속 ${actualWatchMin}분째 화면을 보고 있어!"
                    )
                )
            }
            actualWatchSec >= 20 -> {
                candidates.add(
                    PrioritizedReason(
                        80,
                        "쉬지 않고 ${actualWatchSec}초 동안 계속 보고 있어!"
                    )
                )
            }
            shortformAppName != null -> {
                candidates.add(
                    PrioritizedReason(
                        75,
                        "${shortformAppName}에 너무 푹 빠져 있어!"
                    )
                )
            }
        }

        // 4. 개인 평균 세션 초과 (sessionDelta 마이너스 보정 요인: 최대 -10%p)
        if (jevContext != null) {
            val sessionRatio = jevContext.sessionVsAvgRatio
            val avgMin = jevContext.personalAvgSessionMinutes
            val hasSessionPenalty = (adj?.sessionDelta ?: 0.0) <= -0.02 || (sessionRatio != null && sessionRatio >= 1.25)
            if (hasSessionPenalty && sessionRatio != null && sessionRatio > 1.0) {
                val avgMinRounded = avgMin?.roundToInt()?.takeIf { it >= 1 }
                val text = if (avgMinRounded != null && actualWatchMin > avgMinRounded) {
                    val overMin = actualWatchMin - avgMinRounded
                    "평소 평균(${avgMinRounded}분)보다 ${overMin}분 더 오래 보고 있어!"
                } else if (avgMinRounded != null) {
                    "평소 평균 세션(${avgMinRounded}분)을 넘겨 오래 보고 있어!"
                } else {
                    val ratioText = String.format(Locale.KOREA, "%.1f", sessionRatio)
                    "평소 보던 시간보다 ${ratioText}배 더 오래 보고 있어!"
                }
                candidates.add(PrioritizedReason(84, text))
            }
        }

        // 5. 과몰입 스와이프 속도 / 누적 스와이프 / 롱폼 연속 전환 (swipeDelta · contentSwitchDelta 마이너스 보정 요인)
        if (jevContext != null) {
            val hasLongformSwitchPenalty = (adj?.longformSwitchDelta ?: 0.0) <= -0.025 ||
                jevContext.consecutiveLongformChainCount >= 3
            val hasSwipeHeatPenalty = (adj?.swipeDelta ?: 0.0) <= -0.02 ||
                jevContext.swipeOverheated ||
                jevContext.fastSwipeStreak >= 3
            val hasSwipeCountPenalty = (adj?.shortformSwipeCountDelta ?: 0.0) <= -0.025 ||
                jevContext.shortformSwipeCount >= 25

            if (hasLongformSwitchPenalty && jevContext.consecutiveLongformChainCount >= 2) {
                candidates.add(
                    PrioritizedReason(
                        83,
                        "영상을 ${jevContext.consecutiveLongformChainCount}편째 연달아 넘겨 보고 있어!"
                    )
                )
            }
            if (hasSwipeHeatPenalty) {
                val spm = jevContext.effectiveSpm1m ?: 0.0
                val text = if (spm >= 5.0) {
                    "분당 ${spm.roundToInt()}회 속도로 영상을 빠르게 넘기고 있어!"
                } else {
                    "영상을 너무 빠르게 휙휙 넘기고 있어!"
                }
                candidates.add(PrioritizedReason(82, text))
            } else if (hasSwipeCountPenalty) {
                candidates.add(
                    PrioritizedReason(
                        81,
                        "숏폼을 벌써 ${jevContext.shortformSwipeCount}개째 계속 넘겨 보고 있어!"
                    )
                )
            }
        }

        // 6. 잦은 재접속 (10분 내 3회 이상)
        if (jevContext != null && jevContext.returns10m >= 3) {
            candidates.add(
                PrioritizedReason(
                    78,
                    "최근 10분 사이 ${jevContext.returns10m}번이나 다시 켰어!"
                )
            )
        }

        // 7. 후보가 전혀 없을 때만 기본 안내 추가
        if (candidates.isEmpty()) {
            candidates.add(PrioritizedReason(50, "집중해서 보느라 눈 깜빡임이 많이 줄었어!"))
        }

        // 우선순위 내림차순 정렬 후 최대 3개 선택
        return candidates
            .sortedByDescending { it.priority }
            .map { it.text }
            .distinct()
            .take(3)
    }

    private fun withObjectParticle(word: String): String {
        val last = word.trim().lastOrNull() ?: return word
        if (last in '\uAC00'..'\uD7A3') {
            val hasBatchim = (last.code - 0xAC00) % 28 != 0
            return if (hasBatchim) "${word}을" else "${word}를"
        }
        return "${word}를"
    }

    private data class PrioritizedReason(val priority: Int, val text: String)
}
