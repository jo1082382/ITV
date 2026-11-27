package com.example.itv.infrastructure.storage

import com.example.itv.domain.records.CalendarDayCyclePolicy
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class SixMonthArchiveAndWiperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    @Test
    fun retentionConstantsAreSixMonthsAcrossStores() {
        assertEquals(6L, MonthlyUsageArchiveStore.MAX_RETENTION_MONTHS)
        assertEquals(6L, ShortformPersonalStatsStore.MAX_RETENTION_MONTHS)
    }

    @Test
    fun pruneToSixMonthsKeepsExactBoundaryAndDropsOlderRecords() {
        val today = LocalDate.of(2026, 11, 23)
        val exactCutoff = today.minusMonths(6) // 2026-05-23
        val tooOld = exactCutoff.minusDays(1) // 2026-05-22

        val dailies = mapOf(
            tooOld.toString() to DailyArchiveRecord(date = tooOld.toString(), totalPhoneMs = 60_000L, shortformMs = 10_000L),
            exactCutoff.toString() to DailyArchiveRecord(
                date = exactCutoff.toString(),
                totalPhoneMs = 7_200_000L,
                shortformMs = 1_800_000L,
                phoneSessionCount = 8,
                phoneTotalSessionMs = 7_200_000L,
                phoneAvgSessionMs = 900_000L,
                phoneMaxSessionMs = 2_400_000L,
                shortformSessionCount = 3,
                shortformAvgSessionMs = 600_000L,
                shortformMaxSessionMs = 900_000L,
                shortformSpm = 3.2,
                preSleepUsageMinutes = 14.0,
                preSleepShortformMinutes = 5.0
            ),
            today.toString() to DailyArchiveRecord(date = today.toString(), totalPhoneMs = 3_600_000L, shortformMs = 600_000L)
        )

        val pruned = MonthlyUsageArchiveStore.pruneToSixMonths(dailies, today)

        assertNull(pruned[tooOld.toString()])
        assertNotNull(pruned[exactCutoff.toString()])
        assertNotNull(pruned[today.toString()])
        assertEquals(900_000L, pruned[exactCutoff.toString()]?.phoneAvgSessionMs)
        assertEquals(600_000L, pruned[exactCutoff.toString()]?.shortformAvgSessionMs)
        assertEquals(14.0, pruned[exactCutoff.toString()]?.preSleepUsageMinutes ?: 0.0, 0.001)
    }

    @Test
    fun buildMonthlySummariesUsesTrueShortformSessionAverageWhenArchived() {
        val dailies = mapOf(
            "2026-07-01" to DailyArchiveRecord(
                date = "2026-07-01",
                totalPhoneMs = 10_000_000L,
                shortformMs = 3_000_000L,
                shortformAvgSessionMs = 300_000L,
                bedtimeMinute = 23 * 60 + 30,
                wakeupMinute = 7 * 60
            ),
            "2026-07-02" to DailyArchiveRecord(
                date = "2026-07-02",
                totalPhoneMs = 8_000_000L,
                shortformMs = 1_800_000L,
                shortformAvgSessionMs = 500_000L,
                bedtimeMinute = 23 * 60 + 50,
                wakeupMinute = 7 * 60 + 20
            )
        )

        val summaries = MonthlyUsageArchiveStore.buildMonthlySummaries(dailies, nowWallMs = 1234L)
        val july = summaries["2026-07"]

        assertNotNull(july)
        assertEquals(2, july!!.activeDays)
        assertEquals(400_000L, july.shortformSessionAvgMs)
        assertEquals(2_400_000L, july.shortformDailyAvgMs)
    }

    @Test
    fun resolveNightSleepForDateUsesArchivedWallMsAndDurationWhenLiveSegmentsPurged() {
        val date = LocalDate.of(2026, 7, 10)
        val nextDate = date.plusDays(1)
        val startMs = date.atTime(23, 40).atZone(zone).toInstant().toEpochMilli()
        val endMs = nextDate.atTime(7, 10).atZone(zone).toInstant().toEpochMilli()

        val resolved = CalendarDayCyclePolicy.resolveNightSleepForDate(
            date = date,
            zone = zone,
            wakeConf = null,
            segments = emptyList(),
            candidates = emptyList(),
            nextArchiveBedtimeMin = null,
            nextArchiveWakeupMin = null,
            nextArchiveSleepStartWallMs = startMs,
            nextArchiveSleepEndWallMs = endMs,
            nextArchiveSleepDurationMinutes = 450.0
        )

        assertTrue(resolved.hasWakeupResolved)
        assertEquals(23 * 60 + 40, resolved.bedtimeMinute)
        assertEquals(7 * 60 + 10, resolved.wakeupMinute)
        assertEquals(startMs, resolved.bedtimeStartWallMs)
        assertEquals(endMs, resolved.sleepEndWallMs)
        assertEquals(450.0, resolved.sleepDurationMinutes ?: 0.0, 0.001)
    }

    @Test
    fun localDataWiperDeletesAllConfiguredFilesAndBakSiblings() {
        val dir = tempFolder.newFolder("noBackup")
        LocalDataWiper.NO_BACKUP_FILES_TO_DELETE.forEach { name ->
            File(dir, name).writeText("secret-user-data")
            File(dir, "$name.bak").writeText("backup-user-data")
        }
        val unrelated = File(dir, "keep_unrelated_system_marker")
        unrelated.writeText("system")

        LocalDataWiper.deleteFilesInDirectory(dir, LocalDataWiper.NO_BACKUP_FILES_TO_DELETE)

        LocalDataWiper.NO_BACKUP_FILES_TO_DELETE.forEach { name ->
            assertFalse("$name should be deleted", File(dir, name).exists())
            assertFalse("$name.bak should be deleted", File(dir, "$name.bak").exists())
        }
        assertTrue(unrelated.exists())
    }

    @Test
    fun signOutWipeListsPreserveDeviceIntroConsentAndLastProviderHint() {
        assertFalse("consent_guest_v1.json" in LocalDataWiper.SIGN_OUT_FILES_TO_DELETE)
        assertFalse("permission_intro_v2_seen" in LocalDataWiper.SIGN_OUT_FILES_TO_DELETE)
        assertFalse("jev_test_token_v1" in LocalDataWiper.SIGN_OUT_FILES_TO_DELETE)
        assertFalse("itv_auth_prefs" in LocalDataWiper.SIGN_OUT_PREF_NAMES_TO_CLEAR)
        assertFalse("jev_test_settings_v1" in LocalDataWiper.SIGN_OUT_PREF_NAMES_TO_CLEAR)
        assertFalse("coaching_test" in LocalDataWiper.SIGN_OUT_PREF_NAMES_TO_CLEAR)
        assertTrue("login_completed_v1" in LocalDataWiper.SIGN_OUT_FILES_TO_DELETE)
        assertTrue(MonthlyUsageArchiveStore.FILE_NAME in LocalDataWiper.SIGN_OUT_FILES_TO_DELETE)
        assertTrue(
            com.example.itv.infrastructure.reward.CalendarRewardStore.PREF_NAME in LocalDataWiper.SIGN_OUT_PREF_NAMES_TO_CLEAR
        )

        // Full account deletion still removes consent, intro, dev test settings, and auth prefs
        assertTrue("consent_guest_v1.json" in LocalDataWiper.NO_BACKUP_FILES_TO_DELETE)
        assertTrue("permission_intro_v2_seen" in LocalDataWiper.NO_BACKUP_FILES_TO_DELETE)
        assertTrue("jev_test_token_v1" in LocalDataWiper.NO_BACKUP_FILES_TO_DELETE)
        assertTrue("itv_auth_prefs" in LocalDataWiper.SHARED_PREF_NAMES_TO_CLEAR)
        assertTrue("jev_test_settings_v1" in LocalDataWiper.SHARED_PREF_NAMES_TO_CLEAR)
        assertTrue("coaching_test" in LocalDataWiper.SHARED_PREF_NAMES_TO_CLEAR)
        assertTrue(
            com.example.itv.infrastructure.reward.CalendarRewardStore.PREF_NAME in LocalDataWiper.SHARED_PREF_NAMES_TO_CLEAR
        )
        assertTrue(
            com.example.itv.infrastructure.coaching.WeeklyMailboxStore.FILE_NAME in LocalDataWiper.SIGN_OUT_FILES_TO_DELETE
        )
        assertTrue(
            com.example.itv.infrastructure.coaching.WeeklyMailboxStore.FILE_NAME in LocalDataWiper.NO_BACKUP_FILES_TO_DELETE
        )
    }

    @Test
    fun accountDataBoundaryFiltersPreDeletionOsHistoryAndNightSegments() {
        val boundaryDate = LocalDate.of(2026, 11, 20)
        val boundaryMs = boundaryDate.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()

        assertTrue(AccountDataBoundaryStore.isDateBeforeBoundary(LocalDate.of(2026, 11, 19), boundaryDate))
        assertFalse(AccountDataBoundaryStore.isDateBeforeBoundary(LocalDate.of(2026, 11, 20), boundaryDate))
        assertFalse(AccountDataBoundaryStore.isDateBeforeBoundary(LocalDate.of(2026, 11, 21), boundaryDate))
        assertFalse(AccountDataBoundaryStore.isDateBeforeBoundary(LocalDate.of(2026, 11, 1), null))

        val earlierMs = boundaryDate.minusDays(10).atStartOfDay(zone).toInstant().toEpochMilli()
        val laterMs = boundaryDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(boundaryMs, AccountDataBoundaryStore.clampStartWallMs(earlierMs, boundaryMs))
        assertEquals(laterMs, AccountDataBoundaryStore.clampStartWallMs(laterMs, boundaryMs))

        val oldSeg = com.example.itv.domain.records.NightSegment(
            wakeDate = LocalDate.of(2026, 11, 20),
            start = LocalDate.of(2026, 11, 19).atTime(23, 30).atZone(zone).toInstant().toEpochMilli(),
            end = LocalDate.of(2026, 11, 20).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(),
            minutes = 450.0
        )
        val newSeg = com.example.itv.domain.records.NightSegment(
            wakeDate = LocalDate.of(2026, 11, 21),
            start = LocalDate.of(2026, 11, 20).atTime(23, 45).atZone(zone).toInstant().toEpochMilli(),
            end = LocalDate.of(2026, 11, 21).atTime(7, 15).atZone(zone).toInstant().toEpochMilli(),
            minutes = 450.0
        )
        val report = com.example.itv.domain.records.NightReport(
            days = 7,
            segments = listOf(oldSeg, newSeg),
            zone = zone,
            now = newSeg.end!!,
            candidates = listOf(oldSeg, newSeg)
        )

        val filtered = AccountDataBoundaryStore.filterNightReport(report, boundaryMs)!!
        assertEquals(listOf(newSeg), filtered.segments)
        assertEquals(listOf(newSeg), filtered.candidates)
    }

    @Test
    fun shortformCoordinatorCloseAndTimeoutPreventSignOutHang() = kotlinx.coroutines.runBlocking {
        val failingPersistence = object : com.example.itv.domain.records.ShortformPersistence {
            override suspend fun prepare(): List<com.example.itv.domain.records.StoredShortformDay> {
                throw IllegalStateException("기존 Room 기록과 이전 상태를 확인해 주세요.")
            }
            override suspend fun save(records: List<com.example.itv.domain.records.StoredShortformDay>) = Unit
            override suspend fun clear() = Unit
        }
        val coordinator = com.example.itv.application.records.ShortformRecordingCoordinator(
            persistenceFactory = { com.example.itv.application.records.ShortformPersistenceService(failingPersistence) },
            elapsedNow = { 1_000L },
            consentGranted = { true }
        )

        // Enqueue an un-awaited command that enters the retry-prepare loop (just like live UI/service calls)
        coordinator.today("instagram")

        val startMs = System.currentTimeMillis()
        try {
            kotlinx.coroutines.withTimeoutOrNull(250L) {
                runCatching { coordinator.deleteAllAndAwait() }
            }
        } finally {
            coordinator.close()
        }
        val elapsedMs = System.currentTimeMillis() - startMs
        assertTrue("Sign-out reset must not hang on a stuck coordinator (elapsed=${elapsedMs}ms)", elapsedMs < 2_000L)
    }
}
