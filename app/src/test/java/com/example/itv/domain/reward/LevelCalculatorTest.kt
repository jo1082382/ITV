package com.example.itv.domain.reward

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelCalculatorTest {

    @Test
    fun initialLevelStartsAtOne() {
        val reward = LevelCalculator.calculate(totalExp = 0L, points = 0L, totalMissions = 0)
        assertEquals(1, reward.currentLevel)
        assertEquals(0L, reward.currentLevelExp)
        assertEquals(100L, reward.nextLevelExpRequired)
        assertEquals(0L, reward.currentPoints)
        assertEquals(0, reward.totalCompletedMissions)
        assertEquals(0f, reward.progressPercent, 0.001f)
    }

    @Test
    fun expAccumulatesWithinLevel() {
        val reward = LevelCalculator.calculate(totalExp = 60L, points = 100L, totalMissions = 2)
        assertEquals(1, reward.currentLevel)
        assertEquals(60L, reward.currentLevelExp)
        assertEquals(100L, reward.nextLevelExpRequired)
        assertEquals(0.6f, reward.progressPercent, 0.001f)
    }

    @Test
    fun levelUpOccursWhenExpThresholdReached() {
        // Level 1 requires 100 EXP. 100 EXP -> Level 2 with 0 remaining
        val at100 = LevelCalculator.calculate(totalExp = 100L, points = 150L, totalMissions = 3)
        assertEquals(2, at100.currentLevel)
        assertEquals(0L, at100.currentLevelExp)
        assertEquals(150L, at100.nextLevelExpRequired) // Level 2 requires 150 EXP

        // 130 EXP -> Level 2 with 30 remaining
        val at130 = LevelCalculator.calculate(totalExp = 130L, points = 200L, totalMissions = 4)
        assertEquals(2, at130.currentLevel)
        assertEquals(30L, at130.currentLevelExp)
        assertEquals(150L, at130.nextLevelExpRequired)
        assertEquals(0.2f, at130.progressPercent, 0.001f)
    }

    @Test
    fun multipleLevelUpsCalculateCorrectly() {
        // Level 1: 100 EXP
        // Level 2: 150 EXP (total 250)
        // Level 3: 200 EXP (total 450)
        val at300 = LevelCalculator.calculate(totalExp = 300L, points = 500L, totalMissions = 10)
        assertEquals(3, at300.currentLevel)
        assertEquals(50L, at300.currentLevelExp)
        assertEquals(200L, at300.nextLevelExpRequired)
    }
}
