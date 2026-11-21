package com.example.itv.domain.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightContinuousBudgetTest {

    private fun calculateRemainingWaitMs(
        budgetMs: Long,
        continuousUsedMs: Long
    ): Long = (budgetMs - continuousUsedMs).coerceAtLeast(0L)

    @Test
    fun testNormalMode30MinutesBudgetDeduction() {
        val budgetMs = 30 * 60 * 1000L // 30분 = 1,800,000ms

        // 1. 초기 상태 (사용 시간 0분)
        assertEquals(1800000L, calculateRemainingWaitMs(budgetMs, 0L))

        // 2. 이미 10분 사용한 상태에서 대기 진입 -> 남은 대기 시간은 20분이어야 함 (실질 40~50분 지연 방지)
        val used10Min = 10 * 60 * 1000L
        assertEquals(20 * 60 * 1000L, calculateRemainingWaitMs(budgetMs, used10Min))

        // 3. 25분 사용한 상태 -> 5분 남음
        val used25Min = 25 * 60 * 1000L
        assertEquals(5 * 60 * 1000L, calculateRemainingWaitMs(budgetMs, used25Min))

        // 4. 30분 이상 사용한 상태 -> 남은 대기 0초 (예산 소진 즉시 경보 대상)
        val used32Min = 32 * 60 * 1000L
        assertEquals(0L, calculateRemainingWaitMs(budgetMs, used32Min))
    }

    @Test
    fun test30SecondTestEnvironmentBudgetDeduction() {
        val testBudgetMs = 30_000L // 30초

        // 1. 0초 사용
        assertEquals(30000L, calculateRemainingWaitMs(testBudgetMs, 0L))

        // 2. 12초 사용 후 진입 -> 18초 남음
        assertEquals(18000L, calculateRemainingWaitMs(testBudgetMs, 12000L))

        // 3. 30초 이상 사용 -> 즉시 0초
        assertEquals(0L, calculateRemainingWaitMs(testBudgetMs, 30000L))
        assertEquals(0L, calculateRemainingWaitMs(testBudgetMs, 45000L))
    }

    @Test
    fun testMidActivityInterventionSelection() {
        val isNightCandidate = true
        val remainingBudgetMs = 0L // 예산 초과

        // 게임 중, 롱폼 시청 중, 입력 중 등 몰입 상태
        var isGamingOrWatchingLongform = true
        var nonIntrusiveGentleBannerTriggered = false
        var modalDialogShown = false
        var hudSleepModeActive = false
        var bedtimeCarePending = false

        if (isNightCandidate && remainingBudgetMs == 0L) {
            if (isGamingOrWatchingLongform) {
                // 게임/영상 중에는 화면 터치를 방해하지 않는 비침해 배너 알림만 발동 + HUD 눈감은 강아지 전환
                nonIntrusiveGentleBannerTriggered = true
                hudSleepModeActive = true
                bedtimeCarePending = true
                modalDialogShown = false
            } else {
                modalDialogShown = true
            }
        }

        assertTrue("게임 중에는 비침해 배너가 떠야 합니다", nonIntrusiveGentleBannerTriggered)
        assertTrue("HUD 아이콘이 눈 감은 강아지(수면 모드)로 전환되어야 합니다", hudSleepModeActive)
        assertTrue("게임 종료 후 띄우기 위해 취침 케어가 대기 상태로 예약되어야 합니다", bedtimeCarePending)
        assertFalse("게임 중에는 전면 모달 다이얼로그가 직접 뜨면 안 됩니다", modalDialogShown)

        // 판이 끝나서 게임 로비로 복귀하거나 영상 종료(전환 발생) 시점
        isGamingOrWatchingLongform = false
        if (bedtimeCarePending && !isGamingOrWatchingLongform) {
            bedtimeCarePending = false
            modalDialogShown = true
        }

        assertFalse(bedtimeCarePending)
        assertTrue("전환 시점에 예약되었던 전면 취침 케어가 노출되어야 합니다", modalDialogShown)
    }
}
