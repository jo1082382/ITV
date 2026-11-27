package com.example.itv.infrastructure.cloud

import android.content.Context
import android.util.Log
import com.example.itv.data.local.ItvDatabase
import com.example.itv.data.local.LocalImportReceiptEntity
import com.example.itv.data.local.ShortformDailyRecordEntity
import com.example.itv.data.local.SleepConfirmationEntity
import com.example.itv.domain.auth.AuthProvider
import com.example.itv.domain.auth.UserProfile
import com.example.itv.domain.coaching.CharacterIntensity
import com.example.itv.domain.coaching.CoachingIntensity
import com.example.itv.domain.coaching.CoachingTone
import com.example.itv.domain.coaching.InterventionType
import com.example.itv.domain.coaching.UserCoachingSettings
import com.example.itv.infrastructure.auth.KakaoAuthClient
import com.example.itv.infrastructure.auth.LocalSessionStore
import com.example.itv.infrastructure.auth.NaverAuthClient
import com.example.itv.infrastructure.auth.SocialCustomTokenClient
import com.example.itv.infrastructure.coaching.UserCoachingSettingsStore
import com.example.itv.infrastructure.coaching.WeeklyMailboxStore
import com.example.itv.infrastructure.equip.UserEquipStore
import com.example.itv.infrastructure.goals.RoomGoalStore
import com.example.itv.infrastructure.reward.CalendarRewardStore
import com.example.itv.infrastructure.reward.UserRewardStore
import com.example.itv.composition.queryNightReport
import com.example.itv.composition.queryUsageReport
import com.example.itv.infrastructure.sleep.BedtimeMemoStore
import com.example.itv.infrastructure.storage.CalendarHealthLoader
import com.example.itv.infrastructure.storage.DailyArchiveRecord
import com.example.itv.infrastructure.storage.MonthlyUsageArchiveStore
import com.example.itv.infrastructure.storage.shortform.ShortformDailyStore
import com.example.itv.infrastructure.storage.shortform.ShortformPersonalStatsStore
import com.example.itv.infrastructure.theme.UserThemeStore
import com.example.itv.presentation.usage.DailyStampPrefs
import com.google.android.gms.tasks.Task
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CloudSyncStatus(
    val isSyncing: Boolean = false,
    val lastBackupAt: Long? = null,
    val lastRestoreAt: Long? = null,
    val lastError: String? = null,
    val progressMessage: String? = null
)

data class CloudRestoreSummary(
    val hadCloudData: Boolean,
    val restoredDays: Int,
    val restoredSessions: Int,
    val restoredSleepConfirmations: Int
)

/**
 * Manages 6-month cloud backup, cross-device restore, guest-to-social merge, and
 * full remote data deletion on Cloud Firestore (Standard edition, `(default)` database).
 *
 * Firestore layout (strictly validated by `firestore.rules`):
 * - `users/{uid}`: account root metadata (immutable `createdAt` & `provider`)
 * - `users/{uid}/profile/state`: rewards, equip, stamps, character, theme, coaching, goals, sleep confirmations, memo
 * - `users/{uid}/monthly_archives/{YYYY-MM}`: 6-month daily usage/sleep/session/app archive
 * - `users/{uid}/shortform_sessions/{YYYY-MM}`: 6-month shortform personal session records
 * - `users/{uid}/shortform_daily/{YYYY-MM}`: 6-month Room shortform daily aggregates
 */
object CloudSyncRepository {

    private const val TAG = "ITV_CLOUD_SYNC"
    internal const val SCHEMA_VERSION = 1
    internal const val MAX_RETENTION_MONTHS = 6L
    internal const val PREF_NAME = "itv_cloud_sync_meta_v1"
    private const val KEY_LAST_BACKUP_AT = "last_backup_at"
    private const val KEY_LAST_RESTORE_AT = "last_restore_at"
    private const val MAX_JSON_ARCHIVE_BYTES = 390_000
    private const val MAX_JSON_SESSIONS_BYTES = 390_000
    private const val MAX_JSON_SF_DAILY_BYTES = 190_000
    private const val MAX_JSON_SLEEP_CONF_BYTES = 62_000
    private const val MAX_JSON_GOALS_BYTES = 15_000
    private const val MAX_BATCH_DELETE_OPS = 400
    private const val MAX_MONTH_MS = 2_678_400_000L

    private val syncMutex = Mutex()
    private val _status = MutableStateFlow(CloudSyncStatus())
    val status: StateFlow<CloudSyncStatus> = _status.asStateFlow()

    @Volatile
    private var metaLoaded = false

    private fun firestore(): FirebaseFirestore = Firebase.firestore

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun refreshStatus(context: Context) {
        val p = prefs(context)
        val backupAt = p.getLong(KEY_LAST_BACKUP_AT, 0L).takeIf { it > 0L }
        val restoreAt = p.getLong(KEY_LAST_RESTORE_AT, 0L).takeIf { it > 0L }
        metaLoaded = true
        _status.value = _status.value.copy(
            lastBackupAt = backupAt,
            lastRestoreAt = restoreAt
        )
    }

    internal fun setProgressMessage(message: String?) {
        _status.value = _status.value.copy(progressMessage = message)
    }

    fun clearLocalStatus(context: Context) {
        prefs(context).edit().clear().commit()
        CalendarHealthLoader.clearCache()
        metaLoaded = false
        _status.value = CloudSyncStatus()
    }

    /**
     * Ensures an active non-anonymous Firebase Auth session exists.
     * For users who logged in with Kakao or Naver prior to Cloud Sync rollout (so only a local session
     * existed), transparently exchanges their stored Kakao/Naver OAuth token for a Firebase Custom Token.
     */
    private suspend fun ensureFirebaseSocialSession(
        appCtx: Context,
        profile: UserProfile?
    ): Result<FirebaseUser> {
        val auth = FirebaseAuth.getInstance()
        val existing = auth.currentUser
        if (existing != null && !existing.isAnonymous) {
            return Result.success(existing)
        }

        val effectiveProfile = profile ?: runCatching { LocalSessionStore(appCtx).loadSession() }.getOrNull()
        val provider = effectiveProfile?.provider

        if (provider == AuthProvider.KAKAO || provider == AuthProvider.NAVER) {
            val token = when (provider) {
                AuthProvider.KAKAO -> KakaoAuthClient.getValidStoredAccessToken()
                AuthProvider.NAVER -> NaverAuthClient.getStoredAccessToken()
                else -> null
            }
            if (!token.isNullOrBlank()) {
                val upgraded = runCatching {
                    val exchanged = SocialCustomTokenClient.exchange(provider = provider, accessToken = token)
                    if (existing != null && existing.isAnonymous) {
                        runCatching { existing.delete().awaitTask() }
                    }
                    val result = auth.signInWithCustomToken(exchanged.customToken).awaitTask()
                    result.user ?: throw IllegalStateException("Firebase 인증 세션을 생성하지 못했습니다.")
                }
                if (upgraded.isSuccess) {
                    runCatching { DailyCloudBackupWorker.schedule(appCtx) }
                    return upgraded
                }
                val causeMsg = upgraded.exceptionOrNull()?.localizedMessage
                Log.w(TAG, "Auto-upgrade of $provider session to Firebase Custom Token failed: $causeMsg")
                return Result.failure(
                    IllegalStateException(
                        causeMsg ?: "클라우드 인증 연동에 실패했습니다. 로그아웃 후 다시 로그인해 주세요."
                    )
                )
            }
            val label = if (provider == AuthProvider.KAKAO) "카카오" else "네이버"
            return Result.failure(
                IllegalStateException("클라우드 백업 연동을 위해 $label 계정으로 한 번만 다시 로그인해 주세요.")
            )
        }

        return Result.failure(
            IllegalStateException("소셜 계정으로 로그인한 상태에서만 클라우드 백업이 가능합니다.")
        )
    }

    /**
     * Uploads current 6-month local state to Cloud Firestore for the active non-anonymous user,
     * and deletes any cloud monthly documents older than the 6-month retention window.
     */
    suspend fun backupAllToCloud(
        context: Context,
        profile: UserProfile? = null,
        today: LocalDate = LocalDate.now()
    ): Result<Long> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val appCtx = context.applicationContext
            if (!metaLoaded) refreshStatus(appCtx)

            _status.value = _status.value.copy(
                isSyncing = true,
                lastError = null,
                progressMessage = "최신 사용·수면 기록을 정리하는 중..."
            )
            val fbUserResult = ensureFirebaseSocialSession(appCtx, profile)
            val fbUser = fbUserResult.getOrElse { err ->
                _status.value = _status.value.copy(
                    isSyncing = false,
                    lastError = err.localizedMessage ?: "클라우드 백업 실패",
                    progressMessage = null
                )
                return@withLock Result.failure(err)
            }
            val uid = fbUser.uid
            val resolvedProvider = resolveCloudProvider(profile?.provider, uid)

            try {
                // Flush latest on-device usage & sleep snapshots into the 6-month archive (fast & bounded)
                withTimeoutOrNull(3_500L) {
                    runCatching {
                        val todayRep = runCatching { appCtx.queryUsageReport(1, today) }.getOrNull()
                        val nightRep = runCatching { queryNightReport(appCtx, 7) }.getOrNull()
                        MonthlyUsageArchiveStore.syncDailySnapshot(
                            context = appCtx,
                            todayUsageReport = todayRep,
                            periodUsageReport = null,
                            nightReport = nightRep
                        )
                    }
                }

                _status.value = _status.value.copy(progressMessage = "클라우드에 6개월 기록을 백업하는 중...")

                val db = firestore()
                val userDoc = db.collection("users").document(uid)
                val nowMs = System.currentTimeMillis()
                val cutoffMonth = YearMonth.from(today).minusMonths(MAX_RETENTION_MONTHS)
                val cutoffDateStr = today.minusMonths(MAX_RETENTION_MONTHS).toString()

                // 1. User root metadata (preserving immutable createdAt & provider if doc exists)
                val existingRoot = userDoc.get().awaitTask()
                val createdAt = existingRoot.getLong("createdAt") ?: nowMs
                val existingProvider = existingRoot.getString("provider")
                    ?.takeIf { it in setOf("GOOGLE", "KAKAO", "NAVER") }
                    ?: resolvedProvider
                val rootPayload = mapOf(
                    "provider" to existingProvider,
                    "displayName" to (profile?.displayName ?: fbUser.displayName)?.take(100),
                    "email" to (profile?.email ?: fbUser.email)?.take(254),
                    "schemaVersion" to SCHEMA_VERSION,
                    "createdAt" to createdAt,
                    "updatedAt" to nowMs,
                    "lastBackupAt" to nowMs
                )
                userDoc.set(rootPayload).awaitTask()

                // 2. Profile / settings / gamification / goals / sleep confirmations state
                val profilePayload = buildProfileStatePayload(appCtx, cutoffDateStr, nowMs)
                userDoc.collection("profile").document("state").set(profilePayload).awaitTask()

                // 3. Monthly usage/sleep/session/topApps archives (last 6 months + current month)
                val dailiesByMonth = MonthlyUsageArchiveStore.getAllDailyRecords(appCtx)
                    .values
                    .filter { it.date >= cutoffDateStr }
                    .groupBy { it.date.take(7) }
                    .filterKeys { isValidYearMonth(it) && !YearMonth.parse(it).isBefore(cutoffMonth) }

                for ((ym, monthDailies) in dailiesByMonth) {
                    val sorted = monthDailies.sortedBy { it.date }
                    val activeDays = sorted.count { it.totalPhoneMs > 0L }.coerceIn(0, 31)
                    val totalPhoneMs = sorted.sumOf { it.totalPhoneMs.coerceAtLeast(0L) }.coerceAtMost(MAX_MONTH_MS)
                    val shortformTotalMs = sorted.sumOf { it.shortformMs.coerceAtLeast(0L) }.coerceAtMost(MAX_MONTH_MS)
                    val json = serializeMonthlyDailiesBounded(sorted)
                    val docPayload = mapOf(
                        "yearMonth" to ym,
                        "schemaVersion" to SCHEMA_VERSION,
                        "updatedAt" to nowMs,
                        "activeDays" to activeDays,
                        "totalPhoneMs" to totalPhoneMs,
                        "shortformTotalMs" to shortformTotalMs,
                        "dailyRecordsJson" to json
                    )
                    userDoc.collection("monthly_archives").document(ym).set(docPayload).awaitTask()
                }
                pruneExpiredMonthDocs(userDoc, "monthly_archives", cutoffMonth)

                // 4. Shortform personal sessions grouped by YYYY-MM
                val sessionsByMonth = ShortformPersonalStatsStore.getStoredSessionsSnapshot(appCtx)
                    .filter { it.date >= cutoffDateStr }
                    .groupBy { it.date.take(7) }
                    .filterKeys { isValidYearMonth(it) && !YearMonth.parse(it).isBefore(cutoffMonth) }

                for ((ym, monthSessions) in sessionsByMonth) {
                    val trimmed = monthSessions.sortedBy { it.startWallMs }.takeLast(1500)
                    val json = serializeSessionsBounded(trimmed)
                    val docPayload = mapOf(
                        "yearMonth" to ym,
                        "schemaVersion" to SCHEMA_VERSION,
                        "updatedAt" to nowMs,
                        "sessionCount" to trimmed.size,
                        "sessionsJson" to json
                    )
                    userDoc.collection("shortform_sessions").document(ym).set(docPayload).awaitTask()
                }
                pruneExpiredMonthDocs(userDoc, "shortform_sessions", cutoffMonth)

                // 5. Room shortform_daily_records grouped by YYYY-MM
                val roomDb = ItvDatabase.get(appCtx)
                val sfDailyByMonth = runCatching { roomDb.shortformDao().all("demo-guest") }
                    .getOrDefault(emptyList())
                    .filter { it.localDate >= cutoffDateStr }
                    .groupBy { it.localDate.take(7) }
                    .filterKeys { isValidYearMonth(it) && !YearMonth.parse(it).isBefore(cutoffMonth) }

                for ((ym, monthRows) in sfDailyByMonth) {
                    val trimmed = monthRows.sortedBy { it.localDate }.takeLast(500)
                    val json = serializeShortformDailyBounded(trimmed)
                    val docPayload = mapOf(
                        "yearMonth" to ym,
                        "schemaVersion" to SCHEMA_VERSION,
                        "updatedAt" to nowMs,
                        "recordCount" to trimmed.size,
                        "recordsJson" to json
                    )
                    userDoc.collection("shortform_daily").document(ym).set(docPayload).awaitTask()
                }
                pruneExpiredMonthDocs(userDoc, "shortform_daily", cutoffMonth)

                // Persist backup timestamp locally & in Room backup_state
                prefs(appCtx).edit().putLong(KEY_LAST_BACKUP_AT, nowMs).apply()
                runCatching {
                    val existingBackupState = roomDb.dao().backupState("demo-guest")
                    if (roomDb.dao().owner("demo-guest") != null) {
                        roomDb.dao().saveBackupState(
                            com.example.itv.data.local.BackupStateEntity(
                                ownerId = "demo-guest",
                                restoreState = "READY",
                                schemaVersion = SCHEMA_VERSION,
                                lastBackupAt = nowMs,
                                lastRestoreAt = existingBackupState?.lastRestoreAt
                            )
                        )
                    }
                }
                _status.value = _status.value.copy(
                    isSyncing = false,
                    lastBackupAt = nowMs,
                    lastError = null,
                    progressMessage = null
                )
                Result.success(nowMs)
            } catch (ce: CancellationException) {
                _status.value = _status.value.copy(
                    isSyncing = false,
                    progressMessage = null
                )
                throw ce
            } catch (err: Throwable) {
                Log.e(TAG, "Cloud backup failed: ${err.message}", err)
                _status.value = _status.value.copy(
                    isSyncing = false,
                    lastError = err.localizedMessage ?: "클라우드 백업 실패",
                    progressMessage = null
                )
                Result.failure(err)
            }
        }
    }

    /**
     * Restores 6-month cloud data into local stores.
     * - [mergeWithLocal] = true (Guest -> Social linking): merges cloud and local data without losing local progress.
     * - [mergeWithLocal] = false (Fresh social login / new device): restores cloud data cleanly onto device.
     */
    suspend fun restoreFromCloud(
        context: Context,
        mergeWithLocal: Boolean,
        today: LocalDate = LocalDate.now()
    ): Result<CloudRestoreSummary> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val appCtx = context.applicationContext
            if (!metaLoaded) refreshStatus(appCtx)

            val fbUser = FirebaseAuth.getInstance().currentUser
            if (fbUser == null || fbUser.isAnonymous) {
                return@withLock Result.success(CloudRestoreSummary(false, 0, 0, 0))
            }
            val uid = fbUser.uid

            _status.value = _status.value.copy(
                isSyncing = true,
                lastError = null,
                progressMessage = "클라우드에서 저장된 기록을 확인하는 중..."
            )
            try {
                val db = firestore()
                val userDoc = db.collection("users").document(uid)
                val cutoffMonth = YearMonth.from(today).minusMonths(MAX_RETENTION_MONTHS)
                val cutoffDateStr = today.minusMonths(MAX_RETENTION_MONTHS).toString()

                val profileSnap = userDoc.collection("profile").document("state").get().awaitTask()
                val archivesSnap = userDoc.collection("monthly_archives").get().awaitTask()
                val sessionsSnap = userDoc.collection("shortform_sessions").get().awaitTask()
                val sfDailySnap = userDoc.collection("shortform_daily").get().awaitTask()

                val hadCloudData = profileSnap.exists() ||
                    !archivesSnap.isEmpty ||
                    !sessionsSnap.isEmpty ||
                    !sfDailySnap.isEmpty

                if (!hadCloudData) {
                    _status.value = _status.value.copy(
                        isSyncing = false,
                        lastError = null,
                        progressMessage = null
                    )
                    return@withLock Result.success(CloudRestoreSummary(false, 0, 0, 0))
                }

                _status.value = _status.value.copy(progressMessage = "6개월 기록과 캐릭터 설정을 복원하는 중...")

                // 1. Restore profile, rewards, equip, stamps, theme, coaching, goals, sleep confirmations
                var restoredSleepCount = 0
                if (profileSnap.exists()) {
                    restoredSleepCount = applyProfileState(appCtx, profileSnap.data.orEmpty(), mergeWithLocal, cutoffDateStr)
                }

                // 2. Restore 6-month daily archives
                val cloudDailies = mutableListOf<DailyArchiveRecord>()
                for (doc in archivesSnap.documents) {
                    val ym = doc.id
                    if (!isValidYearMonth(ym) || YearMonth.parse(ym).isBefore(cutoffMonth)) continue
                    val json = doc.getString("dailyRecordsJson").orEmpty()
                    runCatching { MonthlyUsageArchiveStore.parseDailyRecordsArrayJson(json) }
                        .getOrDefault(emptyList())
                        .filter { it.date >= cutoffDateStr }
                        .let { cloudDailies.addAll(it) }
                }
                if (cloudDailies.isNotEmpty() || !mergeWithLocal) {
                    MonthlyUsageArchiveStore.restoreOrMergeFromCloud(
                        context = appCtx,
                        cloudDailies = cloudDailies,
                        mergeWithLocal = mergeWithLocal,
                        today = today
                    )
                }

                // 2.5 Rebuild weekly coach mailbox letters from restored 6-month archive + cloud read states
                val restoredGoalsJson = profileSnap.getString("goalsJson")
                if (!restoredGoalsJson.isNullOrBlank()) {
                    val mailboxMeta = runCatching {
                        JSONObject(restoredGoalsJson).optJSONObject("weeklyMailbox")
                    }.getOrNull()
                    if (mailboxMeta != null) {
                        runCatching {
                            WeeklyMailboxStore.restoreFromCloudMetadata(
                                context = appCtx,
                                metadata = mailboxMeta,
                                mergeWithLocal = mergeWithLocal,
                                today = today
                            )
                        }
                    }
                }

                // 3. Restore 6-month shortform personal sessions
                val cloudSessions = mutableListOf<ShortformPersonalStatsStore.SessionRecord>()
                for (doc in sessionsSnap.documents) {
                    val ym = doc.id
                    if (!isValidYearMonth(ym) || YearMonth.parse(ym).isBefore(cutoffMonth)) continue
                    val json = doc.getString("sessionsJson").orEmpty()
                    parseSessionsJson(json)
                        .filter { it.date >= cutoffDateStr }
                        .let { cloudSessions.addAll(it) }
                }
                if (cloudSessions.isNotEmpty() || !mergeWithLocal) {
                    ShortformPersonalStatsStore.restoreOrMergeFromCloud(
                        context = appCtx,
                        cloudSessions = cloudSessions,
                        mergeWithLocal = mergeWithLocal,
                        today = today
                    )
                }

                // 4. Restore 6-month Room shortform_daily_records
                val roomDb = ItvDatabase.get(appCtx)
                val cloudSfDaily = mutableListOf<ShortformDailyRecordEntity>()
                for (doc in sfDailySnap.documents) {
                    val ym = doc.id
                    if (!isValidYearMonth(ym) || YearMonth.parse(ym).isBefore(cutoffMonth)) continue
                    val json = doc.getString("recordsJson").orEmpty()
                    parseShortformDailyJson(json)
                        .filter { it.localDate >= cutoffDateStr }
                        .let { cloudSfDaily.addAll(it) }
                }
                if (cloudSfDaily.isNotEmpty()) {
                    val dao = roomDb.shortformDao()
                    if (mergeWithLocal) {
                        val existingByKey = dao.all("demo-guest").associateBy {
                            "${it.packageName}|${it.localDate}|${it.zoneKey}|${it.source}"
                        }
                        val mergedRows = cloudSfDaily.map { remote ->
                            val key = "${remote.packageName}|${remote.localDate}|${remote.zoneKey}|${remote.source}"
                            val local = existingByKey[key]
                            if (local != null) {
                                remote.copy(
                                    durationMs = maxOf(local.durationMs, remote.durationMs),
                                    transitionCount = maxOf(local.transitionCount, remote.transitionCount),
                                    observed = local.observed || remote.observed,
                                    updatedAt = maxOf(local.updatedAt, remote.updatedAt)
                                )
                            } else {
                                remote
                            }
                        }
                        dao.saveRecords(mergedRows)
                    } else {
                        dao.saveRecords(cloudSfDaily)
                    }
                    // Ensure RoomShortformPersistence sees a valid import receipt for restored rows
                    // so ShortformRecordingCoordinator.prepare() never loops on unreceipted rows.
                    if (dao.receipt("shortform_daily_v1_to_room_v1") == null) {
                        runCatching {
                            dao.insertReceipt(
                                LocalImportReceiptEntity(
                                    importKey = "shortform_daily_v1_to_room_v1",
                                    ownerId = "demo-guest",
                                    sourceName = "cloud_restore",
                                    importedRows = cloudSfDaily.size.toLong(),
                                    completedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    ShortformDailyStore.reloadAfterCloudRestore()
                }

                val nowMs = System.currentTimeMillis()
                prefs(appCtx).edit().putLong(KEY_LAST_RESTORE_AT, nowMs).apply()
                val rootBackupAt = runCatching { userDoc.get().awaitTask().getLong("lastBackupAt") }.getOrNull()
                if (rootBackupAt != null && rootBackupAt > 0L) {
                    prefs(appCtx).edit().putLong(KEY_LAST_BACKUP_AT, rootBackupAt).apply()
                }
                runCatching {
                    if (roomDb.dao().owner("demo-guest") != null) {
                        roomDb.dao().saveBackupState(
                            com.example.itv.data.local.BackupStateEntity(
                                ownerId = "demo-guest",
                                restoreState = "READY",
                                schemaVersion = SCHEMA_VERSION,
                                lastBackupAt = rootBackupAt,
                                lastRestoreAt = nowMs
                            )
                        )
                    }
                }

                CalendarHealthLoader.clearCache()

                _status.value = _status.value.copy(
                    isSyncing = false,
                    lastBackupAt = rootBackupAt ?: _status.value.lastBackupAt,
                    lastRestoreAt = nowMs,
                    lastError = null,
                    progressMessage = null
                )

                Result.success(
                    CloudRestoreSummary(
                        hadCloudData = true,
                        restoredDays = cloudDailies.size,
                        restoredSessions = cloudSessions.size,
                        restoredSleepConfirmations = restoredSleepCount
                    )
                )
            } catch (ce: CancellationException) {
                _status.value = _status.value.copy(
                    isSyncing = false,
                    progressMessage = null
                )
                throw ce
            } catch (err: Throwable) {
                Log.e(TAG, "Cloud restore failed: ${err.message}", err)
                _status.value = _status.value.copy(
                    isSyncing = false,
                    lastError = err.localizedMessage ?: "클라우드 복원 실패",
                    progressMessage = null
                )
                Result.failure(err)
            }
        }
    }

    /**
     * Permanently deletes ALL Cloud Firestore documents owned by [uid].
     * MUST be called BEFORE deleting the Firebase Auth account while `request.auth.uid == uid` is still valid.
     */
    suspend fun deleteAllCloudUserData(uid: String): Result<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            runCatching {
                val fbUser = FirebaseAuth.getInstance().currentUser
                if (fbUser == null || fbUser.isAnonymous || fbUser.uid != uid) {
                    return@runCatching
                }
                val db = firestore()
                val userDoc = db.collection("users").document(uid)
                val refsToDelete = mutableListOf<DocumentReference>()

                for (sub in listOf("monthly_archives", "shortform_sessions", "shortform_daily")) {
                    val snap = userDoc.collection(sub).get().awaitTask()
                    snap.documents.forEach { refsToDelete.add(it.reference) }
                }
                refsToDelete.add(userDoc.collection("profile").document("state"))
                refsToDelete.add(userDoc)

                refsToDelete.chunked(MAX_BATCH_DELETE_OPS).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { ref -> batch.delete(ref) }
                    batch.commit().awaitTask()
                }
                // Also delete any Customer Center inquiries owned by this user while Auth token is valid
                com.example.itv.infrastructure.support.SupportFirestoreRepository.deleteAllUserInquiries(uid)
                Log.i(TAG, "Deleted ${refsToDelete.size} Firestore documents for uid=$uid")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Profile & Subcollection Serialization / Deserialization Helpers
    // -------------------------------------------------------------------------

    private suspend fun buildProfileStatePayload(
        appCtx: Context,
        cutoffDateStr: String,
        nowMs: Long
    ): Map<String, Any?> {
        val reward = UserRewardStore.getReward(appCtx)
        val equipped = UserEquipStore.getEquipped(appCtx)
        val equippedJson = JSONObject().apply {
            equipped.forEach { (slot, item) ->
                if (item != null) put(slot.name, item.id)
            }
        }.toString().take(2048)

        val stampedDates = DailyStampPrefs.getStampedDates(appCtx)
            .filter { it >= cutoffDateStr }
            .sorted()
            .takeLast(220)
        val stampedDatesJson = JSONArray(stampedDates).toString().take(4096)

        val selectedCharacterId = DailyStampPrefs.getSelectedCharacter(appCtx).id.take(64)
        val darkMode = UserThemeStore.isDarkMode(appCtx)
        val coaching = UserCoachingSettingsStore.getInstance(appCtx).loadSettings()
        val bedtimeMemo = BedtimeMemoStore.getInstance(appCtx).getPendingMemo()

        val roomDb = ItvDatabase.get(appCtx)
        val goalsJson = serializeGoalsAndSettings(roomDb, appCtx, cutoffDateStr)
        val sleepConfs = runCatching { roomDb.sleepConfirmationDao().all("demo-guest") }
            .getOrDefault(emptyList())
            .filter { it.recordDate >= cutoffDateStr }
            .sortedBy { it.recordDate }
        val sleepConfirmationsJson = serializeSleepConfirmationsBounded(sleepConfs)

        return mapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "updatedAt" to nowMs,
            "rewardTotalExp" to reward.totalExp.coerceIn(0L, 100_000_000L),
            "rewardPoints" to reward.currentPoints.coerceIn(0L, 100_000_000L),
            "rewardTotalMissions" to reward.totalCompletedMissions.coerceIn(0, 1_000_000),
            "equippedSlotsJson" to equippedJson,
            "stampedDatesJson" to stampedDatesJson,
            "selectedCharacterId" to selectedCharacterId,
            "darkMode" to darkMode,
            "coachingInterventionType" to coaching.interventionType.name.take(40),
            "coachingIntensity" to coaching.coachingIntensity.name.take(40),
            "characterIntensity" to coaching.characterIntensity.name.take(40),
            "coachingTone" to coaching.tone.name.take(40),
            "bedtimeCareEnabled" to coaching.bedtimeCareEnabled,
            "customBedtimeHour" to coaching.customBedtimeHour?.coerceIn(0, 23),
            "customBedtimeMinute" to coaching.customBedtimeMinute?.coerceIn(0, 59),
            "goalsJson" to goalsJson,
            "sleepConfirmationsJson" to sleepConfirmationsJson,
            "bedtimeMemoContent" to bedtimeMemo?.content?.take(2000),
            "bedtimeMemoSavedAt" to bedtimeMemo?.savedAt,
            "bedtimeMemoPending" to (bedtimeMemo?.isPendingBriefing == true)
        )
    }

    private suspend fun applyProfileState(
        appCtx: Context,
        data: Map<String, Any>,
        mergeWithLocal: Boolean,
        cutoffDateStr: String
    ): Int {
        // 1. Rewards
        val totalExp = (data["rewardTotalExp"] as? Number)?.toLong() ?: 0L
        val points = (data["rewardPoints"] as? Number)?.toLong() ?: 0L
        val totalMissions = (data["rewardTotalMissions"] as? Number)?.toInt() ?: 0
        UserRewardStore.restoreState(appCtx, totalExp, points, totalMissions, mergeWithLocal)

        // 2. Equipped cosmetic items
        val equippedJson = data["equippedSlotsJson"] as? String
        if (!equippedJson.isNullOrBlank()) {
            val slotMap = mutableMapOf<String, String>()
            runCatching {
                val obj = JSONObject(equippedJson)
                obj.keys().forEach { key ->
                    val v = obj.optString(key)
                    if (v.isNotBlank()) slotMap[key] = v
                }
            }
            UserEquipStore.restoreEquipped(appCtx, slotMap, mergeWithLocal)
        }

        // 3. Stamped dates & selected character
        val stampedDates = mutableSetOf<String>()
        val stampedJson = data["stampedDatesJson"] as? String
        if (!stampedJson.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(stampedJson)
                for (i in 0 until arr.length()) {
                    val d = arr.optString(i)
                    if (d.isNotBlank() && d >= cutoffDateStr) stampedDates.add(d)
                }
            }
        }
        val charId = (data["selectedCharacterId"] as? String)?.takeIf { it.isNotBlank() }
        DailyStampPrefs.restoreFromCloud(appCtx, stampedDates, charId, mergeWithLocal)

        // 4. Theme (existing cloud account preferences always restore so dark mode survives Guest -> Social link)
        val darkMode = data["darkMode"] as? Boolean
        if (darkMode != null) {
            UserThemeStore.setDarkMode(appCtx, darkMode)
        }

        // 5. Coaching settings (restore existing cloud account's coaching preferences)
        val interventionType = runCatching {
            InterventionType.valueOf(data["coachingInterventionType"] as? String ?: "")
        }.getOrDefault(InterventionType.CHARACTER_OVERLAY)
        val coachingIntensity = runCatching {
            CoachingIntensity.valueOf(data["coachingIntensity"] as? String ?: "")
        }.getOrDefault(CoachingIntensity.NORMAL)
        val characterIntensity = runCatching {
            CharacterIntensity.valueOf(data["characterIntensity"] as? String ?: "")
        }.getOrDefault(CharacterIntensity.NORMAL)
        val tone = runCatching {
            CoachingTone.valueOf(data["coachingTone"] as? String ?: "")
        }.getOrDefault(CoachingTone.SOFT)
        val bedtimeCareEnabled = (data["bedtimeCareEnabled"] as? Boolean) ?: true
        val customHour = (data["customBedtimeHour"] as? Number)?.toInt()?.takeIf { it in 0..23 }
        val customMin = (data["customBedtimeMinute"] as? Number)?.toInt()?.takeIf { it in 0..59 }
        UserCoachingSettingsStore.getInstance(appCtx).restoreSettings(
            UserCoachingSettings(
                interventionType = interventionType,
                coachingIntensity = coachingIntensity,
                characterIntensity = characterIntensity,
                tone = tone,
                bedtimeCareEnabled = bedtimeCareEnabled,
                customBedtimeHour = customHour,
                customBedtimeMinute = customMin
            )
        )

        // 6. Bedtime memo (preserve local guest pending memo if present; otherwise restore cloud memo)
        val memoContent = data["bedtimeMemoContent"] as? String
        val memoSavedAt = (data["bedtimeMemoSavedAt"] as? Number)?.toLong()
        val memoPending = (data["bedtimeMemoPending"] as? Boolean) ?: false
        val memoStore = BedtimeMemoStore.getInstance(appCtx)
        if (!mergeWithLocal || (memoStore.getPendingMemo() == null && !memoContent.isNullOrBlank())) {
            memoStore.restoreMemo(memoContent, memoSavedAt, memoPending)
        }

        // 7. Room goals, settings & embedded calendarReward (streak shields, best streak, claimed chests)
        val goalsJson = data["goalsJson"] as? String
        if (!goalsJson.isNullOrBlank()) {
            restoreGoalsAndSettings(ItvDatabase.get(appCtx), appCtx, goalsJson, mergeWithLocal, cutoffDateStr)
        }

        // 8. Room sleep confirmations
        val sleepJson = data["sleepConfirmationsJson"] as? String
        val sleepConfs = if (!sleepJson.isNullOrBlank()) {
            parseSleepConfirmationsJson(sleepJson).filter { it.recordDate >= cutoffDateStr }
        } else {
            emptyList()
        }
        if (sleepConfs.isNotEmpty()) {
            val dao = ItvDatabase.get(appCtx).sleepConfirmationDao()
            sleepConfs.forEach { remote ->
                val local = dao.read("demo-guest", remote.recordDate)
                if (!mergeWithLocal || local == null || remote.updatedAt >= local.updatedAt) {
                    dao.save(remote)
                }
            }
        }
        return sleepConfs.size
    }

    private suspend fun serializeGoalsAndSettings(
        roomDb: ItvDatabase,
        appCtx: Context,
        cutoffDateStr: String
    ): String {
        val dao = roomDb.dao()
        val obj = JSONObject()
        val settings = runCatching { dao.settings("demo-guest") }.getOrNull()
        if (settings != null) {
            obj.put(
                "settings",
                JSONObject()
                    .put("coachingEnabled", settings.coachingEnabled)
                    .put("coachingIntensity", settings.coachingIntensity)
                    .put("displayMode", settings.displayMode)
                    .put("snoozeSeconds", settings.snoozeSeconds)
                    .put("goalSetupCompleted", settings.goalSetupCompleted)
            )
        }
        val goalStore = RoomGoalStore(roomDb)
        val snap = runCatching { goalStore.snapshot("demo-guest") }.getOrNull()
        if (snap != null) {
            snap.daily?.let { (g, v) ->
                obj.put("daily", JSONObject().put("enabled", g.enabled).put("seconds", v.dailyLimitSeconds))
            }
            snap.bedtime?.let { (g, v) ->
                obj.put("bedtime", JSONObject().put("enabled", g.enabled).put("minuteOfDay", v.localMinuteOfDay))
            }
            snap.usageBreak?.let { (g, v) ->
                obj.put(
                    "usageBreak",
                    JSONObject()
                        .put("enabled", g.enabled)
                        .put("usageSeconds", v.usageThresholdSeconds)
                        .put("breakSeconds", v.breakDurationSeconds)
                        .put("metric", v.usageMetric)
                        .put("packageName", v.scopePackageName ?: JSONObject.NULL)
                )
            }
        }

        // Embed compact weekly coach mailbox metadata (delivered & read week keys)
        val mailboxMeta = runCatching { WeeklyMailboxStore.exportCloudMetadata(appCtx) }.getOrNull()
        if (mailboxMeta != null) {
            obj.put("weeklyMailbox", mailboxMeta)
        }

        // Embed streak shields, best streak, protected dates & monthly claimed chests inside goalsJson
        // (stays cleanly inside the existing 16KB goalsJson budget validated by firestore.rules).
        val cutoffMonthStr = cutoffDateStr.take(7)
        val calendarRewardSnap = runCatching { CalendarRewardStore.getSnapshot(appCtx) }.getOrNull()
        if (calendarRewardSnap != null) {
            obj.put(
                "calendarReward",
                CalendarRewardStore.serializeToJsonObject(
                    snapshot = calendarRewardSnap,
                    cutoffDateStr = cutoffDateStr,
                    cutoffMonthStr = cutoffMonthStr,
                    includeRecentTiers = true
                )
            )
            val full = obj.toString()
            if (full.length <= MAX_JSON_GOALS_BYTES) return full
            // Size fallback: drop recentDayTiers first while keeping valid JSON & core reward state
            obj.put(
                "calendarReward",
                CalendarRewardStore.serializeToJsonObject(
                    snapshot = calendarRewardSnap,
                    cutoffDateStr = cutoffDateStr,
                    cutoffMonthStr = cutoffMonthStr,
                    includeRecentTiers = false
                )
            )
        }
        val serialized = obj.toString()
        return if (serialized.length <= MAX_JSON_GOALS_BYTES) serialized else "{}"
    }

    private suspend fun restoreGoalsAndSettings(
        roomDb: ItvDatabase,
        appCtx: Context,
        goalsJson: String,
        mergeWithLocal: Boolean,
        cutoffDateStr: String
    ) {
        runCatching {
            val obj = JSONObject(goalsJson)
            val goalStore = RoomGoalStore(roomDb)
            val dao = roomDb.dao()
            if (dao.owner("demo-guest") == null) {
                goalStore.createLocalOwner("demo-guest", FirebaseAuth.getInstance().currentUser?.uid)
            }
            val localSnap = if (mergeWithLocal) runCatching { goalStore.snapshot("demo-guest") }.getOrNull() else null

            obj.optJSONObject("settings")?.let { s ->
                goalStore.saveSettings(
                    ownerId = "demo-guest",
                    coachingEnabled = s.optBoolean("coachingEnabled", false),
                    snoozeSeconds = s.optLong("snoozeSeconds", 300L).coerceAtLeast(1L),
                    intensity = s.optString("coachingIntensity", "DEFAULT"),
                    displayMode = s.optString("displayMode", "DEFAULT")
                )
                if (s.optBoolean("goalSetupCompleted", false)) {
                    goalStore.completeSetup("demo-guest")
                }
            }
            obj.optJSONObject("daily")?.let { d ->
                val localEnabled = localSnap?.daily?.first?.enabled == true
                if (!mergeWithLocal || !localEnabled) {
                    goalStore.saveDailyUsageGoal(
                        ownerId = "demo-guest",
                        seconds = d.optLong("seconds", 0L).coerceAtLeast(0L),
                        enabled = d.optBoolean("enabled", false)
                    )
                }
            }
            obj.optJSONObject("bedtime")?.let { b ->
                val localEnabled = localSnap?.bedtime?.first?.enabled == true
                if (!mergeWithLocal || !localEnabled) {
                    val minOfDay = b.optInt("minuteOfDay", 23 * 60 + 30).coerceIn(0, 23 * 60 + 59)
                    goalStore.saveBedtimeGoal(
                        ownerId = "demo-guest",
                        hour = minOfDay / 60,
                        minute = minOfDay % 60,
                        enabled = b.optBoolean("enabled", false)
                    )
                }
            }
            obj.optJSONObject("usageBreak")?.let { ub ->
                val localEnabled = localSnap?.usageBreak?.first?.enabled == true
                if (!mergeWithLocal || !localEnabled) {
                    val usageSec = ub.optLong("usageSeconds", 0L)
                    val breakSec = ub.optLong("breakSeconds", 0L)
                    val metric = ub.optString("metric", "PHONE_USAGE")
                    val pkg = if (ub.isNull("packageName")) null else ub.optString("packageName").takeIf { it.isNotBlank() }
                    if (usageSec > 0L && breakSec > 0L) {
                        goalStore.saveUsageBreakGoal(
                            ownerId = "demo-guest",
                            usageSeconds = usageSec,
                            breakSeconds = breakSec,
                            metric = metric,
                            packageName = pkg,
                            enabled = ub.optBoolean("enabled", false)
                        )
                    }
                }
            }
            obj.optJSONObject("calendarReward")?.let { crObj ->
                val cutoffMonthStr = cutoffDateStr.take(7)
                val parsed = CalendarRewardStore.parseFromJsonObject(
                    obj = crObj,
                    cutoffDateStr = cutoffDateStr,
                    cutoffMonthStr = cutoffMonthStr
                )
                CalendarRewardStore.restoreFromCloud(
                    context = appCtx,
                    remote = parsed,
                    mergeWithLocal = mergeWithLocal
                )
            }
        }
    }

    private suspend fun pruneExpiredMonthDocs(
        userDoc: DocumentReference,
        subcollection: String,
        cutoffMonth: YearMonth
    ) {
        val snap = userDoc.collection(subcollection).get().awaitTask()
        val expired = snap.documents.filter { doc ->
            !isValidYearMonth(doc.id) || YearMonth.parse(doc.id).isBefore(cutoffMonth)
        }
        if (expired.isEmpty()) return
        expired.chunked(MAX_BATCH_DELETE_OPS).forEach { chunk ->
            val batch = firestore().batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().awaitTask()
        }
    }

    internal fun serializeMonthlyDailiesBounded(records: List<DailyArchiveRecord>): String {
        val full = MonthlyUsageArchiveStore.serializeDailyRecordsArrayJson(records)
        if (full.length <= MAX_JSON_ARCHIVE_BYTES) return full
        // If unusually large, trim topApps first while keeping all daily metrics & hourly distribution
        val trimmed = records.map { it.copy(topApps = it.topApps.take(5)) }
        val second = MonthlyUsageArchiveStore.serializeDailyRecordsArrayJson(trimmed)
        if (second.length <= MAX_JSON_ARCHIVE_BYTES) return second
        return MonthlyUsageArchiveStore.serializeDailyRecordsArrayJson(
            trimmed.map { it.copy(topApps = emptyList(), hourlyPhoneMinutes = emptyList()) }
        ).take(MAX_JSON_ARCHIVE_BYTES)
    }

    internal fun serializeSessionsBounded(sessions: List<ShortformPersonalStatsStore.SessionRecord>): String {
        var list = sessions
        while (true) {
            val arr = JSONArray()
            list.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("pkg", s.packageName)
                        .put("date", s.date)
                        .put("startWallMs", s.startWallMs)
                        .put("updatedWallMs", s.updatedWallMs)
                        .put("durationMs", s.durationMs)
                        .put("swipes", s.swipes)
                )
            }
            val json = arr.toString()
            if (json.length <= MAX_JSON_SESSIONS_BYTES || list.size <= 1) {
                return if (json.length <= MAX_JSON_SESSIONS_BYTES) json else "[]"
            }
            list = list.takeLast((list.size * 3) / 4)
        }
    }

    internal fun parseSessionsJson(json: String): List<ShortformPersonalStatsStore.SessionRecord> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val pkg = o.optString("pkg")
                val date = o.optString("date")
                if (id.isBlank() || pkg.isBlank() || date.isBlank()) return@mapNotNull null
                ShortformPersonalStatsStore.SessionRecord(
                    id = id,
                    packageName = pkg,
                    date = date,
                    startWallMs = o.optLong("startWallMs"),
                    updatedWallMs = o.optLong("updatedWallMs"),
                    durationMs = o.optLong("durationMs"),
                    swipes = o.optInt("swipes")
                )
            }
        }.getOrDefault(emptyList())
    }

    internal fun serializeShortformDailyBounded(rows: List<ShortformDailyRecordEntity>): String {
        var list = rows
        while (true) {
            val arr = JSONArray()
            list.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("pkg", r.packageName)
                        .put("date", r.localDate)
                        .put("zone", r.zoneKey)
                        .put("src", r.source)
                        .put("dur", r.durationMs)
                        .put("swipes", r.transitionCount)
                        .put("obs", r.observed)
                        .put("ver", r.calculationVersion)
                        .put("upd", r.updatedAt)
                )
            }
            val json = arr.toString()
            if (json.length <= MAX_JSON_SF_DAILY_BYTES || list.size <= 1) {
                return if (json.length <= MAX_JSON_SF_DAILY_BYTES) json else "[]"
            }
            list = list.takeLast((list.size * 3) / 4)
        }
    }

    internal fun parseShortformDailyJson(json: String): List<ShortformDailyRecordEntity> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val pkg = o.optString("pkg")
                val date = o.optString("date")
                if (pkg.isBlank() || date.isBlank()) return@mapNotNull null
                ShortformDailyRecordEntity(
                    ownerId = "demo-guest",
                    packageName = pkg,
                    localDate = date,
                    zoneKey = o.optString("zone", "LEGACY_UNKNOWN"),
                    source = o.optString("src", "LIVE"),
                    durationMs = o.optLong("dur"),
                    transitionCount = o.optLong("swipes"),
                    observed = o.optBoolean("obs", true),
                    calculationVersion = o.optString("ver", "1"),
                    updatedAt = o.optLong("upd", System.currentTimeMillis())
                )
            }
        }.getOrDefault(emptyList())
    }

    internal fun serializeSleepConfirmationsBounded(rows: List<SleepConfirmationEntity>): String {
        var list = rows
        while (true) {
            val arr = JSONArray()
            list.forEach { r ->
                val o = JSONObject()
                    .put("date", r.recordDate)
                    .put("zone", r.zoneId)
                    .put("choice", r.choice)
                    .put("origin", r.origin)
                    .put("upd", r.updatedAt)
                r.startMs?.let { o.put("startMs", it) }
                r.endMs?.let { o.put("endMs", it) }
                arr.put(o)
            }
            val json = arr.toString()
            if (json.length <= MAX_JSON_SLEEP_CONF_BYTES || list.size <= 1) {
                return if (json.length <= MAX_JSON_SLEEP_CONF_BYTES) json else "[]"
            }
            list = list.takeLast((list.size * 3) / 4)
        }
    }

    internal fun parseSleepConfirmationsJson(json: String): List<SleepConfirmationEntity> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val date = o.optString("date")
                val choice = o.optString("choice")
                if (date.isBlank() || choice.isBlank()) return@mapNotNull null
                SleepConfirmationEntity(
                    ownerId = "demo-guest",
                    recordDate = date,
                    zoneId = o.optString("zone", "Asia/Seoul"),
                    choice = choice,
                    startMs = if (o.has("startMs")) o.optLong("startMs") else null,
                    endMs = if (o.has("endMs")) o.optLong("endMs") else null,
                    origin = o.optString("origin", "CLOUD_RESTORE"),
                    updatedAt = o.optLong("upd", System.currentTimeMillis())
                )
            }
        }.getOrDefault(emptyList())
    }

    internal fun resolveCloudProvider(provider: AuthProvider?, uid: String): String = when {
        provider == AuthProvider.KAKAO || uid.startsWith("kakao_") -> "KAKAO"
        provider == AuthProvider.NAVER || uid.startsWith("naver_") -> "NAVER"
        else -> "GOOGLE"
    }

    internal fun isValidYearMonth(ym: String): Boolean =
        ym.matches(Regex("^[0-9]{4}-(0[1-9]|1[0-2])$"))

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { exception ->
            if (cont.isActive) cont.resumeWithException(exception)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }
}
