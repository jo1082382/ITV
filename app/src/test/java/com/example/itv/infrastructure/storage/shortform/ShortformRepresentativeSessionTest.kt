package com.example.itv.infrastructure.storage.shortform

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortformRepresentativeSessionTest {

    private fun session(durationMs: Long) = ShortformPersonalStatsStore.SessionRecord(
        id = "s_${durationMs}",
        packageName = ShortformPersonalStatsStore.PKG_YOUTUBE,
        date = "2026-11-10",
        startWallMs = 1000L,
        updatedWallMs = 1000L + durationMs,
        durationMs = durationMs,
        swipes = (durationMs / 10_000L).toInt()
    )

    @Test
    fun excludesMicroSessionsUnderOneMinute() {
        val sessions = listOf(
            session(10_000L), // 10s (exclude)
            session(30_000L), // 30s (exclude)
            session(120_000L), // 2m
            session(300_000L), // 5m
            session(900_000L)  // 15m
        )
        // Eligible: 2m, 5m, 15m
        // Median: 5m (300,000)
        // Max: 15m (900,000)
        // Result: (5m + 15m) / 2 = 10m (600,000)
        val repMs = ShortformPersonalStatsStore.calculateRepresentativeSessionMs(sessions)
        assertEquals(600_000L, repMs)
    }

    @Test
    fun evenNumberOfSessionsAveragesMedians() {
        val sessions = listOf(
            session(120_000L), // 2m
            session(240_000L), // 4m
            session(480_000L), // 8m
            session(960_000L)  // 16m
        )
        // Median: (4m + 8m) / 2 = 6m (360,000)
        // Max: 16m (960,000)
        // Result: (6m + 16m) / 2 = 11m (660,000)
        val repMs = ShortformPersonalStatsStore.calculateRepresentativeSessionMs(sessions)
        assertEquals(660_000L, repMs)
    }

    @Test
    fun singleSessionReturnsExactDuration() {
        val sessions = listOf(session(420_000L)) // 7m
        // Median: 7m, Max: 7m -> 7m
        val repMs = ShortformPersonalStatsStore.calculateRepresentativeSessionMs(sessions)
        assertEquals(420_000L, repMs)
    }

    @Test
    fun fallbackWhenAllSessionsAreUnderOneMinute() {
        val sessions = listOf(
            session(15_000L),
            session(45_000L)
        )
        // No session >= 1m, fall back to available sessions:
        // Median: (15s + 45s) / 2 = 30s
        // Max: 45s
        // Result: (30s + 45s) / 2 = 37.5s -> 37,500ms
        val repMs = ShortformPersonalStatsStore.calculateRepresentativeSessionMs(sessions)
        assertEquals(37_500L, repMs)
    }

    @Test
    fun emptyListReturnsFallbackMs() {
        val repMs = ShortformPersonalStatsStore.calculateRepresentativeSessionMs(emptyList(), fallbackMs = 180_000L)
        assertEquals(180_000L, repMs)
    }
}
