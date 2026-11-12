package com.example.itv.domain.coaching

import org.junit.Assert.*
import org.junit.Test

class CharacterEscalationTrackerTest {

    @Test
    fun initialState_hasZeroResistanceAndKeepsBaseIntensity() {
        val tracker = CharacterEscalationTracker()
        assertEquals(0, tracker.consecutiveResistanceCount)
        assertFalse(tracker.isEscalated)

        assertEquals(CharacterIntensity.GENTLE, tracker.resolveEffectiveIntensity(CharacterIntensity.GENTLE))
        assertEquals(CharacterIntensity.NORMAL, tracker.resolveEffectiveIntensity(CharacterIntensity.NORMAL))
        assertEquals(CharacterIntensity.STRONG, tracker.resolveEffectiveIntensity(CharacterIntensity.STRONG))
    }

    @Test
    fun singleResistance_doesNotEscalate() {
        val tracker = CharacterEscalationTracker()
        tracker.recordResistance()

        assertEquals(1, tracker.consecutiveResistanceCount)
        assertFalse(tracker.isEscalated)
        assertEquals(CharacterIntensity.GENTLE, tracker.resolveEffectiveIntensity(CharacterIntensity.GENTLE))
        assertEquals(CharacterIntensity.NORMAL, tracker.resolveEffectiveIntensity(CharacterIntensity.NORMAL))
    }

    @Test
    fun twoConsecutiveResistances_escalatesToStrongEvenIfBaseIsGentleOrNormal() {
        val tracker = CharacterEscalationTracker()
        tracker.recordResistance()
        tracker.recordResistance()

        assertEquals(2, tracker.consecutiveResistanceCount)
        assertTrue(tracker.isEscalated)

        // 약하게여도 강하게로 스케일업
        assertEquals(CharacterIntensity.STRONG, tracker.resolveEffectiveIntensity(CharacterIntensity.GENTLE))
        // 보통이어도 강하게로 스케일업
        assertEquals(CharacterIntensity.STRONG, tracker.resolveEffectiveIntensity(CharacterIntensity.NORMAL))
        // 강하게는 강하게 유지
        assertEquals(CharacterIntensity.STRONG, tracker.resolveEffectiveIntensity(CharacterIntensity.STRONG))
    }

    @Test
    fun furtherResistances_remainEscalated() {
        val tracker = CharacterEscalationTracker()
        tracker.recordResistance()
        tracker.recordResistance()
        tracker.recordResistance()

        assertEquals(3, tracker.consecutiveResistanceCount)
        assertTrue(tracker.isEscalated)
        assertEquals(CharacterIntensity.STRONG, tracker.resolveEffectiveIntensity(CharacterIntensity.GENTLE))
    }

    @Test
    fun restReset_restoresBaseIntensity() {
        val tracker = CharacterEscalationTracker()
        tracker.recordResistance()
        tracker.recordResistance()
        assertTrue(tracker.isEscalated)

        // 화면을 끄고 휴식 시 리셋
        tracker.reset()

        assertEquals(0, tracker.consecutiveResistanceCount)
        assertFalse(tracker.isEscalated)
        // 원래 선택한 기본 강도로 복귀
        assertEquals(CharacterIntensity.GENTLE, tracker.resolveEffectiveIntensity(CharacterIntensity.GENTLE))
        assertEquals(CharacterIntensity.NORMAL, tracker.resolveEffectiveIntensity(CharacterIntensity.NORMAL))
    }
}
