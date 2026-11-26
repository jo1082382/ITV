package com.example.itv.domain.reward

import com.example.itv.domain.records.DailyHealthTier
import java.time.LocalDate
import java.time.YearMonth

/**
 * 월간 해맑음(GOLD) + 웃음(SILVER) 스탬프 누적 보상 상자 등급 (5개 / 12개 / 20개)
 */
internal enum class MonthlyChestTier(
    val requiredHappyStamps: Int,
    val title: String,
    val shortTitle: String,
    val keyLabel: String,
    val rewardExp: Long,
    val rewardPoints: Long,
    val bonusShields: Int
) {
    BRONZE(
        requiredHappyStamps = 5,
        title = "동 보물상자",
        shortTitle = "동 상자",
        keyLabel = "동 뼈다귀 열쇠",
        rewardExp = 60L,
        rewardPoints = 120L,
        bonusShields = 0
    ),
    SILVER(
        requiredHappyStamps = 12,
        title = "은 보물상자",
        shortTitle = "은 상자",
        keyLabel = "은 별빛 열쇠",
        rewardExp = 120L,
        rewardPoints = 250L,
        bonusShields = 1
    ),
    GOLD(
        requiredHappyStamps = 20,
        title = "금 보물상자",
        shortTitle = "금 상자",
        keyLabel = "금 왕관 열쇠",
        rewardExp = 250L,
        rewardPoints = 500L,
        bonusShields = 1
    )
}

internal enum class ChestClaimStatus {
    LOCKED,
    UNLOCKED_CLAIMABLE,
    CLAIMED
}

internal data class MonthlyChestItemState(
    val tier: MonthlyChestTier,
    val status: ChestClaimStatus,
    val remainingToUnlock: Int,
    val progressFraction: Float
)

internal data class MonthlyCounterState(
    val yearMonth: YearMonth,
    val happyCount: Int,
    val goldCount: Int,
    val silverCount: Int,
    val chests: List<MonthlyChestItemState>
) {
    val maxTarget: Int get() = MonthlyChestTier.GOLD.requiredHappyStamps
    val overallProgress: Float get() = (happyCount.toFloat() / maxTarget.toFloat()).coerceIn(0f, 1f)
    val claimableCount: Int get() = chests.count { it.status == ChestClaimStatus.UNLOCKED_CLAIMABLE }
    val nextLockedChest: MonthlyChestItemState? get() = chests.firstOrNull { it.status == ChestClaimStatus.LOCKED }
}

internal enum class StreakProtectionSource {
    SHIELD,
    POINTS
}

internal data class CalendarStreakSummary(
    val currentStreak: Int,
    val bestStreak: Int,
    val nextMilestone: Int,
    val daysToNextMilestone: Int,
    val activeShieldProtectedDates: Set<LocalDate>,
    val autoShieldCandidateDate: LocalDate?,
    val autoProtectionSource: StreakProtectionSource?,
    val isTodayProjectedPositive: Boolean
)

/**
 * 월간 건강 스탬프 카운터(해맑음 + 웃음) 및 연속 건강 스트릭·보호권 계산 정책 (순수 도메인 로직)
 */
internal object CalendarStreakPolicy {
    const val MAX_STREAK_SHIELDS = 2
    const val SHIELD_COST_POINTS = 150L
    const val OVERFLOW_SHIELD_BONUS_POINTS = 100L
    val STREAK_MILESTONES = listOf(3, 7, 14, 30, 60, 100)

    fun isPositiveStamp(tier: DailyHealthTier?): Boolean =
        tier == DailyHealthTier.GOLD || tier == DailyHealthTier.SILVER

    fun evaluateMonthlyCounter(
        yearMonth: YearMonth,
        goldCount: Int,
        silverCount: Int,
        claimedTiers: Set<MonthlyChestTier>
    ): MonthlyCounterState {
        val safeGold = goldCount.coerceAtLeast(0)
        val safeSilver = silverCount.coerceAtLeast(0)
        val happy = safeGold + safeSilver
        val chests = MonthlyChestTier.entries.map { tier ->
            val unlocked = happy >= tier.requiredHappyStamps
            val status = when {
                tier in claimedTiers -> ChestClaimStatus.CLAIMED
                unlocked -> ChestClaimStatus.UNLOCKED_CLAIMABLE
                else -> ChestClaimStatus.LOCKED
            }
            MonthlyChestItemState(
                tier = tier,
                status = status,
                remainingToUnlock = (tier.requiredHappyStamps - happy).coerceAtLeast(0),
                progressFraction = (happy.toFloat() / tier.requiredHappyStamps.toFloat()).coerceIn(0f, 1f)
            )
        }
        return MonthlyCounterState(
            yearMonth = yearMonth,
            happyCount = happy,
            goldCount = safeGold,
            silverCount = safeSilver,
            chests = chests
        )
    }

    /**
     * 해맑음(GOLD)·웃음(SILVER) 스탬프의 연속 스트릭과 자동 보호(보호권 우선 -> 없으면 포인트 자동 차감) 상태를 계산합니다.
     *
     * - 오늘(또는 아직 기상 미확정인 어제)이 `IN_PROGRESS`인 동안은 스트릭을 끊지 않고 직전 확정일부터 계산합니다.
     * - 이미 보호된 날짜(`protectedDates`)는 연속 기록을 끊지 않고 연결합니다(양성 일수 +1은 부여하지 않음).
     * - 직전 확정일이 양성 스탬프가 아니지만 그 전날까지 이어지던 스트릭이 1일 이상 존재하고
     *   (1) 보유 보호권이 있거나 (2) 보호권이 없더라도 보유 포인트가 [SHIELD_COST_POINTS] 이상이면
     *   해당 날짜를 `autoShieldCandidateDate`와 `autoProtectionSource`로 반환해 자동 방어할 수 있도록 합니다.
     */
    fun evaluateStreak(
        dayTiers: Map<LocalDate, DailyHealthTier>,
        today: LocalDate,
        protectedDates: Set<LocalDate>,
        availableShields: Int,
        availablePoints: Long = 0L,
        persistedBestStreak: Int = 0,
        todayProjectedTier: DailyHealthTier? = null
    ): CalendarStreakSummary {
        val todayTier = dayTiers[today]
        val yesterday = today.minusDays(1)
        val yesterdayTier = dayTiers[yesterday]

        val anchorDate = when {
            isPositiveStamp(todayTier) -> today
            yesterdayTier == DailyHealthTier.IN_PROGRESS -> today.minusDays(2)
            else -> yesterday
        }

        val canAutoProtect = availableShields > 0 || availablePoints >= SHIELD_COST_POINTS
        var autoShieldCandidate: LocalDate? = null
        var autoProtectionSource: StreakProtectionSource? = null
        val anchorTier = dayTiers[anchorDate]
        if (!isPositiveStamp(anchorTier) &&
            anchorDate !in protectedDates &&
            canAutoProtect &&
            anchorTier != DailyHealthTier.IN_PROGRESS
        ) {
            // 직전 날까지 실제 이어지던 건강 스트릭이 있을 때만 자동 방어 발동
            val priorStreak = walkBackwardStreak(
                startDate = anchorDate.minusDays(1),
                dayTiers = dayTiers,
                protectedDates = protectedDates
            ).first
            if (priorStreak >= 1) {
                autoShieldCandidate = anchorDate
                autoProtectionSource = if (availableShields > 0) {
                    StreakProtectionSource.SHIELD
                } else {
                    StreakProtectionSource.POINTS
                }
            }
        }

        val (currentStreak, usedProtectedInCurrent) = walkBackwardStreak(
            startDate = anchorDate,
            dayTiers = dayTiers,
            protectedDates = protectedDates
        )

        val historicalBest = computeHistoricalBestStreak(dayTiers, protectedDates)
        val bestStreak = maxOf(persistedBestStreak, historicalBest, currentStreak)
        val nextMilestone = STREAK_MILESTONES.firstOrNull { it > currentStreak }
            ?: (((currentStreak / 30) + 1) * 30)
        val daysToNext = (nextMilestone - currentStreak).coerceAtLeast(0)

        return CalendarStreakSummary(
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            nextMilestone = nextMilestone,
            daysToNextMilestone = daysToNext,
            activeShieldProtectedDates = usedProtectedInCurrent,
            autoShieldCandidateDate = autoShieldCandidate,
            autoProtectionSource = autoProtectionSource,
            isTodayProjectedPositive = isPositiveStamp(todayTier) || isPositiveStamp(todayProjectedTier)
        )
    }

    private fun walkBackwardStreak(
        startDate: LocalDate,
        dayTiers: Map<LocalDate, DailyHealthTier>,
        protectedDates: Set<LocalDate>
    ): Pair<Int, Set<LocalDate>> {
        var streak = 0
        val usedProtected = mutableSetOf<LocalDate>()
        var cursor = startDate
        var steps = 0
        // 안전 상한 365일
        while (steps < 365) {
            val tier = dayTiers[cursor]
            when {
                isPositiveStamp(tier) -> {
                    streak++
                    cursor = cursor.minusDays(1)
                }
                cursor in protectedDates -> {
                    usedProtected += cursor
                    cursor = cursor.minusDays(1)
                }
                else -> break
            }
            steps++
        }
        // 보호권만 있고 앞에 양성 기록이 하나도 없는 경우는 유효 스트릭 0으로 정리
        return if (streak > 0) streak to usedProtected else 0 to emptySet()
    }

    private fun computeHistoricalBestStreak(
        dayTiers: Map<LocalDate, DailyHealthTier>,
        protectedDates: Set<LocalDate>
    ): Int {
        val allDates = (dayTiers.keys + protectedDates)
        if (allDates.isEmpty()) return 0
        val minDate = allDates.minOrNull() ?: return 0
        val maxDate = allDates.maxOrNull() ?: return 0
        var best = 0
        var running = 0
        var cursor = minDate
        while (!cursor.isAfter(maxDate)) {
            val tier = dayTiers[cursor]
            when {
                isPositiveStamp(tier) -> {
                    running++
                    if (running > best) best = running
                }
                cursor in protectedDates -> {
                    // 보호권으로 이어진 날은 기존 running을 유지 (양성 1일 이상 진행 중일 때만 의미 있음)
                }
                tier == DailyHealthTier.IN_PROGRESS -> {
                    // 집계 진행 중인 날은 과거 최고 기록을 끊지 않고 건너뜀
                }
                else -> {
                    running = 0
                }
            }
            cursor = cursor.plusDays(1)
        }
        return best
    }
}
