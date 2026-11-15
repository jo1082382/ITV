package com.example.itv.domain.coaching

import com.example.itv.domain.jev.JevContext
import com.example.itv.infrastructure.observation.AmbientSnapshot
import java.time.LocalTime

object InterventionReasonResolver {

    /**
     * 강아지 개입 시 사용자에게 친근한 말투로 안내할 판단 사유를 최대 3가지 추출합니다.
     * 가장 영향력이 큰 사유(취침 관련 -> 연속 시청 시간 -> 스와이프 과열 -> 평소 대비 증가) 순으로 정렬됩니다.
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

        // 1. 취침 목표 초과 / 야간 시간대 (최우선 순위: 수면 및 눈 건강)
        val bedtimePassed = jevContext?.bedtimePassedMinutes
        if (bedtimePassed != null) {
            if (bedtimePassed > 0) {
                candidates.add(PrioritizedReason(100, "취침 목표보다 ${bedtimePassed}분이나 지났어!"))
            } else if (bedtimePassed == 0) {
                candidates.add(PrioritizedReason(98, "지금이 딱 약속한 취침 시간이야!"))
            }
        } else {
            // 심야 시간대 (23시 ~ 06시)
            val hour = localTime.hour
            if (hour >= 23 || hour in 0..5) {
                candidates.add(PrioritizedReason(95, "벌써 늦은 밤이야, 내일을 위해 눈 붙이자!"))
            }
        }

        // 2. 환경 조도 및 취침 연계 상태
        if (ambient != null) {
            when (ambient.userEnvironmentState) {
                com.example.itv.infrastructure.observation.UserEnvironmentState.BEDTIME_DELAY_WATCH -> {
                    candidates.add(PrioritizedReason(92, "취침 시간인데 어두운 방에서 밝은 화면을 보면 눈이 많이 지쳐!"))
                }
                com.example.itv.infrastructure.observation.UserEnvironmentState.PRE_SLEEP_REST -> {
                    candidates.add(PrioritizedReason(90, "불을 끄고 누워서 보고 있네! 이제 눈을 감고 같이 잘 시간이야"))
                }
                com.example.itv.infrastructure.observation.UserEnvironmentState.NIGHT_ACTIVE -> {
                    candidates.add(PrioritizedReason(88, "평소 자던 시간보다 늦었어! 내일을 위해 슬슬 마무리하자!"))
                }
                com.example.itv.infrastructure.observation.UserEnvironmentState.INDOOR_DARK_ACTIVE -> {
                    candidates.add(PrioritizedReason(82, "어두운 실내에서 밝은 화면을 보고 있어 눈이 피로해 보여!"))
                }
                else -> {
                    val isDarkAmbient = ambient.lux != null && ambient.lux <= 15f
                    if (ambient.stateLabel.contains("취침") || (isDarkAmbient && !ambient.isOccluded)) {
                        candidates.add(PrioritizedReason(85, "주변이 어두운데 화면을 보고 있어 눈이 피로해 보여!"))
                    } else if (ambient.stateLabel.contains("야간 시청") || (isDarkAmbient && (ambient.screenBrightness ?: 0) > 100)) {
                        candidates.add(PrioritizedReason(85, "어두운 방에서 밝은 화면을 보면 눈이 많이 지쳐!"))
                    }
                }
            }
            when (ambient.posture) {
                com.example.itv.domain.coaching.UserPosture.LYING_DOWN -> {
                    candidates.add(PrioritizedReason(87, "스마트폰을 눕혀 보느라 목에 부담이 실리고 있어!"))
                }
                com.example.itv.domain.coaching.UserPosture.SITTING_OR_STANDING -> {
                    candidates.add(PrioritizedReason(83, "스마트폰을 오래 쥐고 있어 어깨와 등이 굳고 있어!"))
                }
                com.example.itv.domain.coaching.UserPosture.TABLE_OR_DESK -> {
                    candidates.add(PrioritizedReason(84, "테이블을 내려다보느라 고개가 푹 숙여지고 있어!"))
                }
                else -> Unit
            }
        }

        // 3. 연속 시청 시간 (과몰입의 가장 직접적인 지표)
        val continuousMs = jevContext?.continuousMs?.takeIf { it > 0 } ?: continuousUsedMs
        val continuousSec = (continuousMs / 1000L).toInt()
        if (continuousSec >= 60) {
            val minutes = continuousSec / 60
            candidates.add(PrioritizedReason(80, "쉬지 않고 ${minutes}분 넘게 계속 보고 있어!"))
        } else if (continuousSec >= 20) {
            candidates.add(PrioritizedReason(80, "쉬지 않고 ${continuousSec}초 동안 집중해서 보고 있어!"))
        } else if (shortformAppName != null) {
            candidates.add(PrioritizedReason(75, "${shortformAppName}에 너무 푹 빠져 있어!"))
        }

        // 4. 빠른 연속 스와이프 / 과열 스크롤 (도파민 루프)
        if (jevContext != null) {
            if (jevContext.swipeOverheated || jevContext.fastSwipeStreak >= 3) {
                candidates.add(PrioritizedReason(70, "영상을 너무 빠르게 휙휙 넘기고 있어!"))
            } else if ((jevContext.effectiveSpm1m ?: 0.0) >= 12.0) {
                candidates.add(PrioritizedReason(68, "영상을 쉴 새 없이 넘겨보며 몰입 중이야!"))
            }
        }

        // 5. 평소보다 긴 시청 / 잦은 재접속 / 폰 사용 중 숏폼 과다
        if (jevContext != null) {
            val sessionRatio = jevContext.sessionVsAvgRatio
            val phoneShare = jevContext.shortformShareOfPhoneRatio
            if (sessionRatio != null && sessionRatio >= 1.4) {
                candidates.add(PrioritizedReason(65, "평소 보던 시간보다 훨씬 오래 보고 있어!"))
            } else if (jevContext.returns10m >= 3) {
                candidates.add(PrioritizedReason(62, "조금 전에도 껐다가 바로 다시 켰잖아!"))
            } else if (phoneShare != null && phoneShare >= 0.5) {
                candidates.add(PrioritizedReason(60, "오늘 폰 사용의 대부분이 숏폼에 집중되어 있어!"))
            }
        }

        // 6. 후보가 1개뿐일 때 친근한 보조 사유 추가
        if (candidates.size < 2) {
            candidates.add(PrioritizedReason(50, "집중해서 보느라 눈 깜빡임이 많이 줄었어!"))
        }

        // 우선순위 내림차순 정렬 후 최대 3개 선택
        return candidates
            .sortedByDescending { it.priority }
            .map { it.text }
            .distinct()
            .take(3)
    }

    private data class PrioritizedReason(val priority: Int, val text: String)
}
