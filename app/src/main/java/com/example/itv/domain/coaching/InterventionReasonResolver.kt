package com.example.itv.domain.coaching

import com.example.itv.domain.jev.JevCandidatePolicy
import com.example.itv.domain.jev.JevContext
import com.example.itv.domain.jev.JevSettings
import com.example.itv.infrastructure.observation.AmbientSnapshot
import com.example.itv.infrastructure.observation.BedtimePhase
import com.example.itv.infrastructure.observation.SessionAppCategory
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
     * - 여러 앱을 섞어 쓴 혼합 세션에서는 [categoryDurationsMs]의 가중치 환산 기여도(1:1 / 1:2 / 1:3)를 기준으로
     *   실제 피로·확률 상승에 가장 크게 기여한 주범 카테고리를 선별합니다.
     * - 고정 우선순위가 아니라 각 요인의 실제 개입 기여도(임계치 -Δ%p 차감량 + 환산 피로 누적)를 점수화하여
     *   최상위 기여 맥락을 정렬하되, 취침 전·취침 지연 및 암실 사용에는 보호 가중치를 높게 부여합니다.
     */
    fun resolveReasons(
        isJev: Boolean,
        jevContext: JevContext?,
        continuousUsedMs: Long,
        ambient: AmbientSnapshot?,
        shortformAppName: String?,
        localTime: LocalTime = LocalTime.now(),
        categoryDurationsMs: Map<SessionAppCategory, Long> = emptyMap()
    ): List<String> {
        val candidates = mutableListOf<ScoredReason>()
        val adj = jevContext?.let {
            JevCandidatePolicy.computeThresholdAdjustment(JevSettings(personalizedEnabled = true), it)
        }

        val bedtimeDeltaPct = (-(adj?.bedtimeDelta ?: 0.0)).coerceAtLeast(0.0) * 100.0
        val sessionDeltaPct = (-(adj?.sessionDelta ?: 0.0)).coerceAtLeast(0.0) * 100.0
        val swipeDeltaPct = (-(adj?.swipeDelta ?: 0.0)).coerceAtLeast(0.0) * 100.0
        val longformSwitchDeltaPct = (-(adj?.longformSwitchDelta ?: 0.0)).coerceAtLeast(0.0) * 100.0
        val shortformSwipeCountDeltaPct = (-(adj?.shortformSwipeCountDelta ?: 0.0)).coerceAtLeast(0.0) * 100.0
        val fatigueDeltaPct = (-(adj?.fatigueDelta ?: 0.0)).coerceAtLeast(0.0) * 100.0

        val darkCap = ContextualPreInterventionPolicy.DARK_LUX_THRESHOLD
        val fullDark = ContextualPreInterventionPolicy.FULL_DARK_LUX_THRESHOLD
        val isDarkAmbient = ambient != null && !ambient.isOccluded && ambient.lux != null && ambient.lux <= darkCap
        val darknessLevel = if (ambient != null && !ambient.isOccluded && ambient.lux != null) {
            when {
                ambient.lux <= fullDark -> 1.0
                ambient.lux <= darkCap -> ((darkCap - ambient.lux) / (darkCap - fullDark)).toDouble().coerceIn(0.0, 1.0)
                else -> 0.0
            }
        } else 0.0
        val isBrightScreen = (ambient?.screenBrightness ?: jevContext?.screenBrightness ?: 0) > 100

        val effectiveContinuousMs = jevContext?.continuousMs?.takeIf { it > 0L } ?: continuousUsedMs
        val isBedtimeWindow = if (ambient != null) {
            when (ambient.bedtimePhase) {
                BedtimePhase.PAST_BEDTIME -> true
                BedtimePhase.APPROACHING -> {
                    // 취침 전 접근 구간: 반드시 암실(lux <= 20)이고 30초 이상 연속 사용한 경우에만 취침 개입 맥락 인정
                    isDarkAmbient && effectiveContinuousMs >= 30_000L
                }
                BedtimePhase.ACTIVE_HOURS -> false
                BedtimePhase.UNKNOWN -> {
                    // 미측정/초기 상태 fallback: 심야(23시~05시) 암실 환경일 때만 인정
                    (localTime.hour >= 23 || localTime.hour in 0..4) && isDarkAmbient
                }
            }
        } else {
            localTime.hour >= 23 || localTime.hour in 0..4
        }

        val bedtimeDistance = jevContext?.bedtimeDistanceMinutes ?: ambient?.bedtimeDistanceMinutes
        val bedtimePassed = jevContext?.bedtimePassedMinutes
            ?: ambient?.bedtimeDistanceMinutes?.takeIf { it >= 0 }
        val preBedtimeMin = bedtimeDistance?.takeIf { it in -180..-1 }?.let { -it }
        var includedDarkInBedtime = false

        // ---------------------------------------------------------------------
        // 1. 취침 시각 초과 + 암실 (최고 보호 가중치: bedtimeDeltaPct * 2.0 + 지연 가중)
        // ---------------------------------------------------------------------
        if (isBedtimeWindow && bedtimePassed != null) {
            if (bedtimePassed > 0) {
                if (isDarkAmbient) {
                    includedDarkInBedtime = true
                    val score = 115.0 + bedtimeDeltaPct * 2.0 + darknessLevel * 10.0 +
                        (bedtimePassed.coerceAtMost(60) * 0.25)
                    candidates.add(
                        ScoredReason(
                            score,
                            "평소 취침보다 ${bedtimePassed}분 늦었고, 어두운 곳에서 보고 있어!"
                        )
                    )
                } else {
                    val score = 105.0 + bedtimeDeltaPct * 1.8 +
                        (bedtimePassed.coerceAtMost(60) * 0.35)
                    candidates.add(
                        ScoredReason(
                            score,
                            "평소 취침 시간보다 ${bedtimePassed}분이나 지났어!"
                        )
                    )
                }
            } else if (bedtimePassed == 0) {
                if (isDarkAmbient) {
                    includedDarkInBedtime = true
                    val score = 110.0 + bedtimeDeltaPct * 2.0 + darknessLevel * 10.0
                    candidates.add(
                        ScoredReason(
                            score,
                            "약속한 취침 시간인데 어두운 방에서 보고 있어!"
                        )
                    )
                } else {
                    val score = 100.0 + bedtimeDeltaPct * 1.6
                    candidates.add(ScoredReason(score, "지금이 딱 약속한 취침 시간이야!"))
                }
            }
        } else if (isBedtimeWindow && (ambient?.bedtimePhase == BedtimePhase.PAST_BEDTIME || ambient == null)) {
            if (isDarkAmbient) {
                includedDarkInBedtime = true
                val score = 105.0 + bedtimeDeltaPct * 2.0 + darknessLevel * 10.0
                candidates.add(ScoredReason(score, "늦은 밤 어두운 곳에서 화면을 보고 있어!"))
            } else {
                val score = 96.0 + bedtimeDeltaPct * 1.6
                candidates.add(ScoredReason(score, "벌써 늦은 밤이야, 내일을 위해 눈 붙이자!"))
            }
        }

        // ---------------------------------------------------------------------
        // 2. 취침 전 암실 / 일반 암실 사용 (취침 전·암실 보호 가중치 강화)
        // ---------------------------------------------------------------------
        if (ambient != null && !includedDarkInBedtime) {
            val isDaytime = !isBedtimeWindow && localTime.hour in 6..20
            val hasBedtimeDarkPenalty = bedtimeDeltaPct >= 5.0
            val preBedtimeDarkScore = 98.0 + bedtimeDeltaPct * 2.2 + darknessLevel * 12.0 +
                (if (isBrightScreen) 4.0 else 0.0)
            val darkRoomScore = 80.0 + darknessLevel * 22.0 + bedtimeDeltaPct * 2.0 +
                (if (isBrightScreen) 6.0 else 0.0)

            if (isDaytime && isDarkAmbient) {
                if (isBrightScreen) {
                    candidates.add(
                        ScoredReason(
                            darkRoomScore,
                            "낮 시간인데 어두운 곳에서 밝은 화면을 보고 있어 눈이 지쳐!"
                        )
                    )
                } else {
                    candidates.add(
                        ScoredReason(
                            darkRoomScore,
                            "주변이 어두운 곳에서 화면을 보고 있어 눈이 피로해!"
                        )
                    )
                }
            } else if (isBedtimeWindow) {
                when (ambient.userEnvironmentState) {
                    UserEnvironmentState.BEDTIME_DELAY_WATCH -> {
                        val text = if (preBedtimeMin != null) {
                            "취침 ${preBedtimeMin}분 전인데 어두운 방에서 밝은 화면을 보고 있어 눈이 지쳐!"
                        } else {
                            "취침 시간인데 어두운 방에서 밝은 화면을 보고 있어 눈이 지쳐!"
                        }
                        candidates.add(ScoredReason(preBedtimeDarkScore + 2.0, text))
                    }
                    UserEnvironmentState.PRE_SLEEP_REST -> {
                        val text = if (preBedtimeMin != null) {
                            "취침 ${preBedtimeMin}분 전, 불을 끈 어두운 방에서 보고 있어! 이제 눈을 감고 잘 시간이야"
                        } else {
                            "불을 끈 어두운 방에서 보고 있어! 이제 눈을 감고 잘 시간이야"
                        }
                        candidates.add(ScoredReason(preBedtimeDarkScore, text))
                    }
                    UserEnvironmentState.NIGHT_ACTIVE -> {
                        candidates.add(
                            ScoredReason(
                                92.0 + bedtimeDeltaPct * 1.6,
                                "평소 자던 시간보다 늦었어! 내일을 위해 슬슬 마무리하자!"
                            )
                        )
                    }
                    UserEnvironmentState.INDOOR_DARK_ACTIVE -> {
                        candidates.add(
                            ScoredReason(
                                darkRoomScore,
                                "어두운 실내에서 밝은 화면을 보고 있어 눈이 피로해!"
                            )
                        )
                    }
                    else -> {
                        if (isDarkAmbient || hasBedtimeDarkPenalty) {
                            val text = if (preBedtimeMin != null) {
                                "취침 ${preBedtimeMin}분 전인데 어두운 곳에서 화면을 보고 있어 눈이 피로해!"
                            } else {
                                "취침 전 어두운 곳에서 화면을 보고 있어 눈이 피로해!"
                            }
                            candidates.add(ScoredReason(preBedtimeDarkScore, text))
                        }
                    }
                }
            } else if (isDarkAmbient) {
                if (hasBedtimeDarkPenalty && preBedtimeMin != null) {
                    candidates.add(
                        ScoredReason(
                            preBedtimeDarkScore,
                            "취침 ${preBedtimeMin}분 전인데 어두운 곳에서 화면을 보고 있어 눈이 피로해!"
                        )
                    )
                } else if (isBrightScreen) {
                    candidates.add(
                        ScoredReason(
                            darkRoomScore,
                            "어두운 실내에서 밝은 화면을 보고 있어 눈이 피로해!"
                        )
                    )
                } else {
                    candidates.add(
                        ScoredReason(
                            darkRoomScore,
                            "주변이 어두운 곳에서 화면을 보고 있어 눈이 피로해!"
                        )
                    )
                }
            }
        }

        // ---------------------------------------------------------------------
        // 3. 혼합 앱 카테고리별 가중치 기여도 분석 -> 실제 시청/사용 주범 1가지 추출
        // ---------------------------------------------------------------------
        val usageSummary = resolveDominantUsageSummary(
            categoryDurationsMs = categoryDurationsMs,
            jevContext = jevContext,
            continuousUsedMs = continuousUsedMs,
            shortformAppName = shortformAppName
        )

        val eqMinutes = usageSummary.totalWeightedMs / 60_000.0
        val displayMinutes = usageSummary.displayWatchMs / 60_000.0
        if (usageSummary.reasonText != null) {
            val watchScore = when {
                usageSummary.displayWatchMs >= 60_000L -> {
                    62.0 + (eqMinutes.coerceAtMost(45.0) * 1.4) +
                        (displayMinutes.coerceAtMost(60.0) * 0.25) +
                        (fatigueDeltaPct * 3.0)
                }
                usageSummary.displayWatchMs >= 20_000L -> 60.0
                else -> 52.0
            }
            candidates.add(ScoredReason(watchScore, usageSummary.reasonText))
        }

        // ---------------------------------------------------------------------
        // 4. 개인 평균 세션 초과 (sessionDelta 실제 차감량 기반 동적 점수: 최대 -10%p)
        // ---------------------------------------------------------------------
        if (jevContext != null) {
            val sessionRatio = jevContext.sessionVsAvgRatio
            val avgMin = jevContext.personalAvgSessionMinutes
            val hasSessionPenalty = (adj?.sessionDelta ?: 0.0) <= -0.02 ||
                (sessionRatio != null && sessionRatio >= 1.25)
            if (hasSessionPenalty && sessionRatio != null && sessionRatio > 1.0) {
                val avgMinRounded = avgMin?.roundToInt()?.takeIf { it >= 1 }
                val sessionCompareMin = (usageSummary.totalRawMs / 60_000L).toInt()
                    .coerceAtLeast((usageSummary.displayWatchMs / 60_000L).toInt())
                val text = if (avgMinRounded != null && sessionCompareMin > avgMinRounded) {
                    val overMin = sessionCompareMin - avgMinRounded
                    "평소 평균(${avgMinRounded}분)보다 ${overMin}분 더 오래 보고 있어!"
                } else if (avgMinRounded != null) {
                    "평소 평균 세션(${avgMinRounded}분)을 넘겨 오래 보고 있어!"
                } else {
                    val ratioText = String.format(Locale.KOREA, "%.1f", sessionRatio)
                    "평소 보던 시간보다 ${ratioText}배 더 오래 보고 있어!"
                }
                val ratioBonus = (sessionRatio - 1.0).coerceIn(0.0, 1.5) * 4.0
                val sessionScore = 64.0 + (sessionDeltaPct * 2.5) + ratioBonus
                candidates.add(ScoredReason(sessionScore, text))
            }
        }

        // ---------------------------------------------------------------------
        // 5. 과몰입 스와이프 속도 / 누적 스와이프 / 롱폼 연속 전환 (실제 -Δ%p 기여도 기반)
        // ---------------------------------------------------------------------
        if (jevContext != null) {
            val hasLongformSwitchPenalty = (adj?.longformSwitchDelta ?: 0.0) <= -0.025 ||
                jevContext.consecutiveLongformChainCount >= 3
            val hasSwipeHeatPenalty = (adj?.swipeDelta ?: 0.0) <= -0.02 ||
                jevContext.swipeOverheated ||
                jevContext.fastSwipeStreak >= 3
            val hasSwipeCountPenalty = (adj?.shortformSwipeCountDelta ?: 0.0) <= -0.025 ||
                jevContext.shortformSwipeCount >= 25

            if (hasLongformSwitchPenalty && jevContext.consecutiveLongformChainCount >= 2) {
                val chainScore = 62.0 + (longformSwitchDeltaPct * 2.4) +
                    (jevContext.consecutiveLongformChainCount.coerceAtMost(8) * 0.5)
                candidates.add(
                    ScoredReason(
                        chainScore,
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
                val spmBonus = (spm - 5.0).coerceIn(0.0, 15.0) * 0.4
                val swipeScore = 62.0 + (swipeDeltaPct * 2.5) + spmBonus
                candidates.add(ScoredReason(swipeScore, text))
            } else if (hasSwipeCountPenalty) {
                val countScore = 61.0 + (shortformSwipeCountDeltaPct * 2.4)
                candidates.add(
                    ScoredReason(
                        countScore,
                        "숏폼을 벌써 ${jevContext.shortformSwipeCount}개째 계속 넘겨 보고 있어!"
                    )
                )
            }
        }

        // ---------------------------------------------------------------------
        // 6. 잦은 재접속 (10분 내 3회 이상)
        // ---------------------------------------------------------------------
        if (jevContext != null && jevContext.returns10m >= 3) {
            val returnsScore = 64.0 + ((jevContext.returns10m - 2).coerceIn(1, 8) * 3.5)
            candidates.add(
                ScoredReason(
                    returnsScore,
                    "최근 10분 사이 ${jevContext.returns10m}번이나 다시 켰어!"
                )
            )
        }

        // ---------------------------------------------------------------------
        // 7. 후보가 전혀 없을 때만 기본 안내 추가
        // ---------------------------------------------------------------------
        if (candidates.isEmpty()) {
            candidates.add(ScoredReason(50.0, "집중해서 보느라 눈 깜빡임이 많이 줄었어!"))
        }

        // 실제 기여도 점수 내림차순 정렬 후 상위 최대 3개 선택
        return candidates
            .sortedByDescending { it.score }
            .map { it.text }
            .distinct()
            .take(3)
    }

    private fun resolveDominantUsageSummary(
        categoryDurationsMs: Map<SessionAppCategory, Long>,
        jevContext: JevContext?,
        continuousUsedMs: Long,
        shortformAppName: String?
    ): DominantUsageSummary {
        val activeEntries = categoryDurationsMs.entries.filter { it.value > 0L }
        if (activeEntries.isNotEmpty()) {
            val totalRawMs = activeEntries.sumOf { it.value }
            val weightedList = activeEntries.map { (cat, rawMs) ->
                CategoryContribution(
                    category = cat,
                    rawMs = rawMs,
                    weightedMs = rawMs / cat.weightTier.divisor
                )
            }
            val totalWeightedMs = weightedList.sumOf { it.weightedMs }
                .coerceAtLeast(jevContext?.continuousMs?.takeIf { it > 0L } ?: 0L)
            val totalRawSec = (totalRawMs / 1000L).toInt()
            val totalRawMin = totalRawSec / 60

            if (totalRawMin < 1) {
                val text = when {
                    totalRawSec >= 20 -> "쉬지 않고 ${totalRawSec}초 동안 계속 보고 있어!"
                    shortformAppName != null -> "${shortformAppName}에 너무 푹 빠져 있어!"
                    else -> null
                }
                return DominantUsageSummary(
                    displayWatchMs = totalRawMs,
                    totalRawMs = totalRawMs,
                    totalWeightedMs = totalWeightedMs.coerceAtLeast(totalRawMs),
                    reasonText = text
                )
            }

            val top = weightedList.maxWith(
                compareBy<CategoryContribution> { it.weightedMs }.thenBy { it.rawMs }
            )
            val topShare = if (totalWeightedMs > 0L) {
                top.weightedMs.toDouble() / totalWeightedMs.toDouble()
            } else 1.0

            val shortsMs = categoryDurationsMs[SessionAppCategory.YOUTUBE_SHORTS] ?: 0L
            val reelsMs = categoryDurationsMs[SessionAppCategory.INSTA_REELS] ?: 0L
            val combinedShortformMs = shortsMs + reelsMs
            val combinedShortformShare = if (totalWeightedMs > 0L) {
                combinedShortformMs.toDouble() / totalWeightedMs.toDouble()
            } else 0.0

            // 쇼츠와 릴스를 함께 많이 봤고(각 2분 이상, 한쪽 비중 < 70%) 숏폼 합산이 세션의 과반을 차지하면 통합 "숏폼"으로 안내
            val isMixedShortformDominant =
                combinedShortformShare >= 0.50 &&
                    shortsMs >= 120_000L &&
                    reelsMs >= 120_000L &&
                    (maxOf(shortsMs, reelsMs).toDouble() / combinedShortformMs.toDouble()) < 0.70

            if (isMixedShortformDominant) {
                val sfMin = (combinedShortformMs / 60_000L).toInt()
                return DominantUsageSummary(
                    displayWatchMs = combinedShortformMs,
                    totalRawMs = totalRawMs,
                    totalWeightedMs = totalWeightedMs,
                    reasonText = "숏폼을 ${sfMin}분째 계속 보고 있어!"
                )
            }

            // 단일 카테고리가 환산 피로 기여도의 45% 이상을 차지하고 1분 이상이면 해당 주범 카테고리를 정확히 지목
            val topMin = (top.rawMs / 60_000L).toInt()
            if (topShare >= 0.45 && topMin >= 1) {
                val text = formatCategoryReason(top.category, topMin)
                return DominantUsageSummary(
                    displayWatchMs = top.rawMs,
                    totalRawMs = totalRawMs,
                    totalWeightedMs = totalWeightedMs,
                    reasonText = text
                )
            }

            // 특정 한 앱이 압도적이지 않고 여러 앱을 고르게 섞어 쓴 경우
            return DominantUsageSummary(
                displayWatchMs = totalRawMs,
                totalRawMs = totalRawMs,
                totalWeightedMs = totalWeightedMs,
                reasonText = "여러 앱을 쉬지 않고 연속 ${totalRawMin}분째 보고 있어!"
            )
        }

        // Fallback: 카테고리 맵이 전달되지 않은 경우 기존 JevContext / continuousUsedMs 기반 계산
        val rawShortformMs = jevContext?.rawShortformMs ?: 0L
        val rawGeneralMs = jevContext?.rawGeneralMs ?: 0L
        val longformWatchMs = jevContext?.longformWatchMs ?: 0L
        val weightedContinuousMs = jevContext?.continuousMs?.takeIf { it > 0L } ?: continuousUsedMs
        val rawTotalMs = rawShortformMs + rawGeneralMs

        // 가중치 기여도 기준 비교 (쇼츠 1:1 vs 롱폼 1:3)
        val weightedLongformMs = longformWatchMs / 3L
        val isLongform = jevContext != null && (
            (jevContext.youtubeWatchMode == "LONGFORM" && rawShortformMs <= weightedLongformMs) ||
                (longformWatchMs >= 60_000L && weightedLongformMs > rawShortformMs)
            )
        val isShortform = !isLongform && (
            shortformAppName != null || rawShortformMs >= 60_000L
            )

        val actualWatchMs = when {
            isLongform -> longformWatchMs.takeIf { it > 0L } ?: maxOf(rawGeneralMs, weightedContinuousMs)
            isShortform -> if (rawShortformMs > 0L) rawShortformMs else weightedContinuousMs
            rawTotalMs > 0L -> rawTotalMs
            else -> weightedContinuousMs
        }
        val actualWatchSec = (actualWatchMs / 1000L).toInt()
        val actualWatchMin = actualWatchSec / 60

        val reasonText = when {
            actualWatchMin >= 1 && isLongform -> "유튜브 영상을 연속 ${actualWatchMin}분째 시청 중이야!"
            actualWatchMin >= 1 && isShortform -> {
                val label = shortformAppName ?: "숏폼"
                "${withObjectParticle(label)} ${actualWatchMin}분째 계속 보고 있어!"
            }
            actualWatchMin >= 1 -> "쉬지 않고 연속 ${actualWatchMin}분째 화면을 보고 있어!"
            actualWatchSec >= 20 -> "쉬지 않고 ${actualWatchSec}초 동안 계속 보고 있어!"
            shortformAppName != null -> "${shortformAppName}에 너무 푹 빠져 있어!"
            else -> null
        }

        return DominantUsageSummary(
            displayWatchMs = actualWatchMs,
            totalRawMs = rawTotalMs.takeIf { it > 0L } ?: actualWatchMs,
            totalWeightedMs = weightedContinuousMs,
            reasonText = reasonText
        )
    }

    private fun formatCategoryReason(category: SessionAppCategory, minutes: Int): String = when (category) {
        SessionAppCategory.YOUTUBE_SHORTS -> "YouTube 쇼츠를 ${minutes}분째 계속 보고 있어!"
        SessionAppCategory.INSTA_REELS -> "Instagram 릴스를 ${minutes}분째 계속 보고 있어!"
        SessionAppCategory.YOUTUBE_LONGFORM -> "유튜브 영상을 연속 ${minutes}분째 시청 중이야!"
        SessionAppCategory.FEED_SNS -> "피드·SNS를 연속 ${minutes}분째 둘러보고 있어!"
        SessionAppCategory.GAME -> "게임을 연속 ${minutes}분째 플레이 중이야!"
        SessionAppCategory.DOC_MESSENGER -> "메신저·문서를 연속 ${minutes}분째 보고 있어!"
        SessionAppCategory.WEB_GENERAL -> "웹·화면을 연속 ${minutes}분째 보고 있어!"
    }

    private fun withObjectParticle(word: String): String {
        val last = word.trim().lastOrNull() ?: return word
        if (last in '\uAC00'..'\uD7A3') {
            val hasBatchim = (last.code - 0xAC00) % 28 != 0
            return if (hasBatchim) "${word}을" else "${word}를"
        }
        return "${word}를"
    }

    private data class CategoryContribution(
        val category: SessionAppCategory,
        val rawMs: Long,
        val weightedMs: Long
    )

    private data class DominantUsageSummary(
        val displayWatchMs: Long,
        val totalRawMs: Long,
        val totalWeightedMs: Long,
        val reasonText: String?
    )

    private data class ScoredReason(val score: Double, val text: String)
}
