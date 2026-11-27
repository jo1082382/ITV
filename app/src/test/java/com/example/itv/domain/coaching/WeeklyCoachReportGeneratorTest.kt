package com.example.itv.domain.coaching

import com.example.itv.infrastructure.coaching.WeeklyMailboxStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class WeeklyCoachReportGeneratorTest {

    @Test
    fun latestCompletedWeekStart_advancesExactlyWhenMondayStarts() {
        // Sunday 2026-11-22: current week (Nov 16..22) is still ongoing, so latest completed is Nov 9..15
        assertEquals(
            LocalDate.of(2026, 11, 9),
            WeeklyCoachReportGenerator.latestCompletedWeekStart(LocalDate.of(2026, 11, 22))
        )
        // Monday 2026-11-23: Nov 16..22 just finished at Monday 00:00!
        assertEquals(
            LocalDate.of(2026, 11, 16),
            WeeklyCoachReportGenerator.latestCompletedWeekStart(LocalDate.of(2026, 11, 23))
        )
        // Thursday 2026-11-26: still points to Nov 16..22 until next Monday (Nov 30)
        assertEquals(
            LocalDate.of(2026, 11, 16),
            WeeklyCoachReportGenerator.latestCompletedWeekStart(LocalDate.of(2026, 11, 26))
        )
        // Next Monday 2026-11-30: advances to Nov 23..29
        assertEquals(
            LocalDate.of(2026, 11, 23),
            WeeklyCoachReportGenerator.latestCompletedWeekStart(LocalDate.of(2026, 11, 30))
        )
    }

    @Test
    fun generateLetter_detectsImprovementsVsPriorWeekAndLifestylePatterns() {
        val sample = WeeklyCoachReportGenerator.generateSamplePreviewLetter(LocalDate.of(2026, 11, 26))

        assertEquals(LocalDate.of(2026, 11, 16), sample.weekStartDate)
        assertEquals(LocalDate.of(2026, 11, 22), sample.weekEndDate)
        assertEquals(LocalDate.of(2026, 11, 23), sample.deliveredMondayDate)
        assertTrue(sample.improvements.isNotEmpty())
        assertTrue(sample.discoveries.any { it.category == WeeklyPatternCategory.TIME_RHYTHM })
        assertTrue(sample.discoveries.any { it.category == WeeklyPatternCategory.SHORTFORM_HABIT })
        assertTrue(sample.discoveries.any { it.category == WeeklyPatternCategory.SLEEP_CONNECTION })
        assertNotNull(sample.stats.savedWeeklyMinutes)
        assertTrue(sample.stats.savedWeeklyMinutes!! > 0)
        assertNotNull(sample.stats.reclaimedTimeExample)
    }

    @Test
    fun generateLetter_handlesFirstWeekWithNoPriorDataAndReturnsNullWhenEmpty() {
        val monday = LocalDate.of(2026, 11, 16)
        assertNull(WeeklyCoachReportGenerator.generateLetter(monday, emptyMap()))

        val onlyCurrentWeek = mapOf(
            monday to WeeklyDaySnapshot(
                date = monday,
                totalPhoneMs = 150 * 60_000L,
                shortformMs = 30 * 60_000L,
                hourlyPhoneMinutes = List(24) { h -> if (h == 23) 40.0 else 5.0 },
                topApps = listOf(WeeklyAppStat("com.instagram.android", "Instagram", 50.0)),
                isRestDay = false,
            ),
            monday.plusDays(5) to WeeklyDaySnapshot(
                date = monday.plusDays(5),
                totalPhoneMs = 240 * 60_000L,
                shortformMs = 70 * 60_000L,
                hourlyPhoneMinutes = List(24) { h -> if (h == 23) 55.0 else 8.0 },
                topApps = listOf(WeeklyAppStat("com.instagram.android", "Instagram", 80.0)),
                isRestDay = true,
            ),
        )

        val letter = WeeklyCoachReportGenerator.generateLetter(monday, onlyCurrentWeek)
        assertNotNull(letter)
        assertEquals(2, letter!!.stats.activeDays)
        assertEquals("win_first_week", letter.improvements.first().id)
        assertTrue(letter.discoveries.any { it.category == WeeklyPatternCategory.DAY_TYPE_BALANCE })
        assertEquals(DayOfWeek.MONDAY, letter.deliveredMondayDate.dayOfWeek)
    }

    @Test
    fun buildWeeksToDeliver_deliversSingleLatestForFirstUserAndBackfillsMissedMondays() {
        val latest = LocalDate.of(2026, 11, 16)
        val cutoff = LocalDate.of(2026, 5, 26)

        // Brand-new mailbox: only 1 letter for latest completed Monday
        assertEquals(
            listOf(latest),
            WeeklyMailboxStore.buildWeeksToDeliver(emptySet(), latest, cutoff)
        )

        // Already delivered: nothing to add
        assertEquals(
            emptyList<LocalDate>(),
            WeeklyMailboxStore.buildWeeksToDeliver(setOf(latest), latest, cutoff)
        )

        // User missed 2 Mondays (last had Oct 26, now latest is Nov 16 -> delivers Nov 2, Nov 9, Nov 16)
        assertEquals(
            listOf(
                LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 11, 16),
            ),
            WeeklyMailboxStore.buildWeeksToDeliver(
                setOf(LocalDate.of(2026, 10, 26)),
                latest,
                cutoff
            )
        )
    }

    @Test
    fun generateSampleMailboxLetters_returnsUnreadNewestAndOpenedOlderLetters() {
        val letters = WeeklyCoachReportGenerator.generateSampleMailboxLetters(LocalDate.of(2026, 11, 26))

        assertEquals(3, letters.size)
        assertEquals(LocalDate.of(2026, 11, 16), letters[0].weekStartDate)
        assertEquals(LocalDate.of(2026, 11, 9), letters[1].weekStartDate)
        assertEquals(LocalDate.of(2026, 11, 2), letters[2].weekStartDate)
        assertTrue(!letters[0].isRead)
        assertTrue(letters[1].isRead)
        assertTrue(letters[2].isRead)
        assertTrue(letters.all { !it.isPreview })
        assertTrue(letters.all { letter -> letter.greeting.none { it.isDigit() } })
    }
}
