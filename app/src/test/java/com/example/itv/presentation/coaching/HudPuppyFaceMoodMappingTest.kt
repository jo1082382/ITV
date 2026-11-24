package com.example.itv.presentation.coaching

import com.example.itv.presentation.components.PuppyMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudPuppyFaceMoodMappingTest {

    @Test
    fun `maps Jev vs ITV threshold gap to all 5 puppy mood stages`() {
        val threshold = 0.65

        // Stage 1: gap >= 30%p (very safe, including IEEE-754 boundary 0.70 - 0.40)
        assertEquals(PuppyMood.BRIGHT, resolveHudMoodForProbabilityGap(0.20, threshold))
        assertEquals(PuppyMood.BRIGHT, resolveHudMoodForProbabilityGap(0.35, threshold))
        assertEquals(PuppyMood.BRIGHT, resolveHudMoodForProbabilityGap(0.40, 0.70))

        // Stage 2: 15%p <= gap < 30%p (comfortable)
        assertEquals(PuppyMood.SMILE, resolveHudMoodForProbabilityGap(0.36, threshold))
        assertEquals(PuppyMood.SMILE, resolveHudMoodForProbabilityGap(0.50, threshold))

        // Stage 3: 5%p <= gap < 15%p (approaching)
        assertEquals(PuppyMood.SAD, resolveHudMoodForProbabilityGap(0.51, threshold))
        assertEquals(PuppyMood.SAD, resolveHudMoodForProbabilityGap(0.60, threshold))

        // Stage 4: 0%p < gap < 5%p (imminent)
        assertEquals(PuppyMood.ANGRY, resolveHudMoodForProbabilityGap(0.61, threshold))
        assertEquals(PuppyMood.ANGRY, resolveHudMoodForProbabilityGap(0.649, threshold))

        // Stage 5: gap <= 0%p (Jev >= ITV threshold, rest needed)
        assertEquals(PuppyMood.SICK, resolveHudMoodForProbabilityGap(0.65, threshold))
        assertEquals(PuppyMood.SICK, resolveHudMoodForProbabilityGap(0.85, threshold))
    }

    @Test
    fun `returns null when probability or threshold is missing or non-finite`() {
        assertNull(resolveHudMoodForProbabilityGap(null, 0.65))
        assertNull(resolveHudMoodForProbabilityGap(Double.NaN, 0.65))
        assertNull(resolveHudMoodForProbabilityGap(0.40, Double.NaN))
        assertNull(resolveHudMoodForProbabilityGap(Double.POSITIVE_INFINITY, 0.65))
    }
}
