package com.example.itv.presentation.coaching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterStageGeometryTest {
    @Test fun tallBubbleCanSitAtTopOfAShortViewport() {
        for (above in listOf(false, true)) {
            assertEquals(0, bubbleTop(20, 65, 146, 110, 8, above))
            assertEquals(3, bubbleTop(20, 65, 99, 110, 8, above))
        }
    }

    @Test fun bubbleKeepsGapAndFitsOnItsSelectedSide() {
        assertEquals(198, bubbleTop(320, 86, 114, 640, 8, true))
        assertEquals(104, bubbleTop(10, 86, 114, 640, 8, false))
        assertEquals(518, bubbleTop(600, 86, 114, 640, 8, false))
    }

    @Test fun strongActorFitsBesideLandscapeGuideAndInsidePortraitBounds() {
        for ((w, h) in listOf(640 to 172, 720 to 320, 360 to 600, 280 to 775)) {
            val columns = w > h
            val size = strongDogExtent(w, h, 8, columns)
            val cx = if (columns) w / 4 else w / 2
            assertTrue(cx - size / 2 >= 0)
            assertTrue(cx + size / 2 <= if (columns) w / 2 else w)
            assertTrue(size + 8 <= h)
        }
    }
}
