package com.example.itv.presentation.coaching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenTravelTest {
    @Test fun walkAndTurnShareBothContactPhaseAndGroundPosition() {
        for (direction in listOf(-1, 1)) {
            val from = 350
            val to = from + direction * 570
            val r = GoldenTravel(from, to, 384)
            assertEquals(from, r.walkX(0f))
            assertEquals(r.walkX(1f), r.turnX(0f))
            assertEquals(to, r.turnX(1f))
            assertEquals(GoldenTravel.TURN_PHASE, r.walkPhase(1f, true), .0001f)
            assertEquals(GoldenTravel.TURN_PHASE, r.walkPhase(0f, true), .0001f)
            val positions = (0..48).map { r.turnX(it / 48f) }
            assertTrue(positions.zipWithNext().all { (a, b) -> direction * (b - a) >= 0 })
            // Reversing the baked turn must retrace the footprint in either direction.
            for (i in 0..48) {
                val u = i / 48f
                assertEquals(r.turnX(1f - u), GoldenTravel.turningOutX(to, 384, direction < 0, u))
            }
        }
    }

    @Test fun aGentleEntranceUsesTheBakedStrideEvenForAFractionalLastCycle() {
        val r = GoldenTravel(930, 680, 320)
        val u = .25f
        val actualCycles = (r.walkX(1f) - r.walkX(u)).toFloat() / (-320f * GoldenTravel.STRIDE)
        val phase = r.walkPhase(u, false) + actualCycles - GoldenTravel.TURN_PHASE
        assertEquals(kotlin.math.round(phase), phase, .025f)
    }
}
