package com.example.itv.presentation.coaching

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class GoldenTravelTest {
    @Test fun entryAndFaceShareMiddleFootfallAndWindowCoordinatesInBothDirections() {
        for (left in listOf(false, true)) {
            val route = GoldenTravel(450, 660, 384, left, 7)
            assertEquals(if (left) GoldenMotion.ENTRY_LEFT else GoldenMotion.ENTRY_RIGHT, route.walk)
            assertEquals(if (left) GoldenMotion.FACE_LEFT else GoldenMotion.FACE_RIGHT, route.turn)
            assertEquals(.5f, route.walkPhase(0f), .00001f)
            assertEquals(.5f, route.walkPhase(1f), .00001f)
            assertEquals(route.launchX(0f), route.walkX(0f))
            assertEquals(route.launchY(0f), route.walkY(0f))
            assertEquals(route.walkX(1f), route.turnX(0f))
            assertEquals(route.walkY(1f), route.turnY(0f))
            assertEquals(450, route.turnX(1f))
            assertEquals(660, route.turnY(1f))
        }
    }

    @Test fun eachBakedStrideAdvancesAnatomicallyProjectedDistance() {
        // Individual timestamps landing in the same baked cel must keep the
        // same root so the floor cannot creep under a held foot at 60/120 Hz.
        val right = GoldenTravel(500, 200, 340, false, 4)
        val left = GoldenTravel(500, 200, 340, true, 4)
        for (i in 0 until 60) {
            val u = (i + .2f) / 80f
            assertEquals(right.walkPhase(u), right.walkPhase(u + .006f), .0001f)
            assertEquals(right.walkX(u), right.walkX(u + .006f))
            assertEquals(right.walkY(u), right.walkY(u + .006f))
            assertEquals(42.5713f, (right.walkX(u + .25f) - right.walkX(u)).toFloat(), 1f)
            assertEquals(8.0496f, (right.walkY(u + .25f) - right.walkY(u)).toFloat(), 1f)
            assertEquals(1000, right.walkX(u) + left.walkX(u))
            assertEquals(right.walkY(u), left.walkY(u))
        }
    }

    @Test fun sideDepartureUsesLongerStrideAndItsOwnBakedGroundTrack() {
        assertEquals(88f, GoldenTravel.SIDE_STRIDE * 340f, .00001f)
        for (left in listOf(false, true)) {
            val x = GoldenTravel.sideTurnX(450, 340, left, 0f)
            assertEquals(29, abs(x - 450))
            assertEquals(left, x > 450)
            assertEquals(450, GoldenTravel.sideTurnX(450, 340, left, 1f))
        }
    }
}
