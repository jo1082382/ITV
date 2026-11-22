package com.example.itv.presentation.coaching

import org.junit.Assert.assertEquals
import org.junit.Test

class GoldenPlaybackClockTest {
    @Test fun coldDecodeDoesNotConsumeShortPickup() {
        val clock = GoldenPlaybackClock()
        clock.seek(0L)
        assertEquals(0L, clock.elapsed(980L))
        clock.resume(980L)
        assertEquals(0L, clock.elapsed(980L))
        assertEquals(340L, clock.elapsed(1320L))
    }

    @Test fun repeatedVisibilityCallbacksResumeAnInterruptedPoseAtItsVisibleAge() {
        val clock = GoldenPlaybackClock()
        clock.seek(110L)
        clock.resume(1_000L)
        clock.resume(1_010L)
        clock.pause(1_040L)
        clock.pause(1_500L)
        assertEquals(150L, clock.elapsed(10_000L))
        clock.resume(10_000L)
        assertEquals(200L, clock.elapsed(10_050L))
        clock.seek(0L)
        assertEquals(0L, clock.elapsed(10_550L))
    }
}
