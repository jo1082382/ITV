package com.example.itv.infrastructure.support

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.example.itv.domain.support.InquiryCategory
import com.example.itv.domain.support.InquiryStatus
import com.example.itv.domain.support.SupportDiagnosticSnapshot
import com.example.itv.domain.support.SupportInquiry
import com.example.itv.infrastructure.observation.accessibility.WritingGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Local-first + Cloud Firestore synchronized store for Customer Center 1:1 inquiries.
 *
 * - All disk and Firestore network I/O runs strictly on [Dispatchers.IO].
 * - Writes optimistically to local cache first so UI reacts instantaneously, then uploads
 *   to Cloud Firestore (`support_inquiries`) and retries any pending offline tickets on next sync.
 * - Exposes [isAdmin] backed by server-verified `support_admins/{uid}` / Auth custom claim.
 */
object SupportInquiryStore {
    internal const val PREF_NAME = "itv_support_center_v1"
    private const val KEY_INQUIRIES_JSON = "inquiries_json"
    private const val MAX_STORED_INQUIRIES = 50

    private val syncMutex = Mutex()

    private val _inquiries = MutableStateFlow<List<SupportInquiry>>(emptyList())
    val inquiries: StateFlow<List<SupportInquiry>> = _inquiries.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    @Volatile
    private var loaded = false

    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        val appContext = context.applicationContext
        val parsed = withContext(Dispatchers.IO) {
            val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_INQUIRIES_JSON, null)
            decodeInquiries(raw)
        }
        _inquiries.value = parsed
        loaded = true
    }

    /**
     * Checks server-verified Support Admin privilege for the current signed-in social user.
     */
    suspend fun refreshAdminFlag(): Boolean {
        val verified = SupportFirestoreRepository.checkIsSupportAdmin()
        _isAdmin.value = verified
        return verified
    }

    /**
     * Synchronizes the current user's inquiries with Cloud Firestore:
     * 1. Retries uploading any locally cached inquiries created while offline.
     * 2. Fetches latest remote tickets (including admin replies) and merges cleanly.
     * 3. Refreshes [isAdmin] state.
     */
    suspend fun refreshFromCloud(context: Context) {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        syncMutex.withLock {
            _isSyncing.value = true
            try {
                refreshAdminFlag()
                val identity = SupportFirestoreRepository.resolveOrCreateAuthorIdentity(
                    context = appContext,
                    allowAnonymousSignIn = false,
                )
                if (identity == null) {
                    _lastSyncError.value = null
                    return
                }

                // 1. Retry uploading any unsynced local tickets owned by this user (or created offline)
                var currentLocal = _inquiries.value
                val pendingUploads = currentLocal.filter { !it.syncedToCloud }
                if (pendingUploads.isNotEmpty()) {
                    val uploadedIds = mutableSetOf<String>()
                    val patchedById = mutableMapOf<String, SupportInquiry>()
                    for (item in pendingUploads) {
                        val prepared = item.copy(
                            uid = identity.uid,
                            authorProvider = identity.provider,
                            authorLabel = identity.label,
                            updatedAtEpochMs = maxOf(item.updatedAtEpochMs, item.createdAtEpochMs),
                        )
                        val uploadRes = SupportFirestoreRepository.uploadInquiry(prepared)
                        if (uploadRes.isSuccess) {
                            uploadedIds.add(item.id)
                            patchedById[item.id] = prepared.copy(syncedToCloud = true)
                        }
                    }
                    if (uploadedIds.isNotEmpty()) {
                        currentLocal = currentLocal.map { patchedById[it.id] ?: it }
                        _inquiries.value = currentLocal
                        persist(appContext, currentLocal)
                    }
                }

                // 2. Pull authoritative list from Firestore and merge with any still-unsynced local drafts
                val remoteResult = SupportFirestoreRepository.fetchMyInquiries(identity.uid)
                remoteResult.onSuccess { remoteList ->
                    val merged = mergeRemoteAndLocal(remoteList, _inquiries.value)
                    _inquiries.value = merged
                    _lastSyncError.value = null
                    persist(appContext, merged)
                }.onFailure { err ->
                    _lastSyncError.value = err.message ?: "서버 동기화에 실패했습니다."
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    suspend fun submitInquiry(
        context: Context,
        category: InquiryCategory,
        title: String,
        content: String,
        diagnostics: SupportDiagnosticSnapshot?,
    ): SupportInquiry {
        val appContext = context.applicationContext
        ensureLoaded(appContext)

        val identity = SupportFirestoreRepository.resolveOrCreateAuthorIdentity(
            context = appContext,
            allowAnonymousSignIn = true,
        )
        val nowMs = System.currentTimeMillis()
        val draft = SupportInquiry(
            id = "inq_${nowMs}_${UUID.randomUUID().toString().replace("-", "").take(6)}",
            category = category,
            title = title,
            content = content,
            createdAtEpochMs = nowMs,
            status = InquiryStatus.RECEIVED,
            diagnostics = diagnostics,
            adminReply = null,
            repliedAtEpochMs = null,
            isReplyRead = true,
            uid = identity?.uid.orEmpty(),
            authorProvider = identity?.provider ?: "GUEST",
            authorLabel = identity?.label ?: "게스트 사용자",
            adminUid = null,
            updatedAtEpochMs = nowMs,
            syncedToCloud = false,
        )

        val uploadSucceeded = if (identity != null) {
            SupportFirestoreRepository.uploadInquiry(draft).isSuccess
        } else {
            false
        }
        val finalItem = draft.copy(syncedToCloud = uploadSucceeded)
        if (!uploadSucceeded) {
            _lastSyncError.value = "네트워크 또는 서버 연결 지연으로 기기에 임시 저장되었습니다. 새로고침 시 자동 전송됩니다."
        } else {
            _lastSyncError.value = null
        }

        val updated = (listOf(finalItem) + _inquiries.value).take(MAX_STORED_INQUIRIES)
        _inquiries.value = updated
        persist(appContext, updated)
        return finalItem
    }

    suspend fun markReplyRead(context: Context, inquiryId: String) {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        val current = _inquiries.value
        val target = current.firstOrNull { it.id == inquiryId } ?: return
        if (target.isReplyRead) return
        val nowMs = maxOf(System.currentTimeMillis(), target.updatedAtEpochMs)
        val updated = current.map { item ->
            if (item.id == inquiryId) item.copy(isReplyRead = true, updatedAtEpochMs = nowMs) else item
        }
        _inquiries.value = updated
        persist(appContext, updated)

        if (target.syncedToCloud) {
            SupportFirestoreRepository.markReplyReadRemote(inquiryId, nowMs)
        }
    }

    suspend fun deleteInquiry(context: Context, inquiryId: String) {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        val target = _inquiries.value.firstOrNull { it.id == inquiryId } ?: return
        val updated = _inquiries.value.filterNot { it.id == inquiryId }
        _inquiries.value = updated
        persist(appContext, updated)

        if (target.syncedToCloud) {
            SupportFirestoreRepository.deleteInquiryRemote(inquiryId)
        }
    }

    fun reset(context: Context) {
        _inquiries.value = emptyList()
        _isAdmin.value = false
        _lastSyncError.value = null
        _isSyncing.value = false
        loaded = false
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit(commit = true) { clear() }
        }
    }

    /**
     * Merges remote authoritative inquiries with any unsynced local-only drafts,
     * preserving newest-first ordering and capping at [MAX_STORED_INQUIRIES].
     */
    internal fun mergeRemoteAndLocal(
        remote: List<SupportInquiry>,
        local: List<SupportInquiry>,
    ): List<SupportInquiry> {
        val remoteById = remote.associateBy { it.id }
        val unsyncedLocal = local.filter { !it.syncedToCloud && it.id !in remoteById }
        return (remote + unsyncedLocal)
            .sortedByDescending { it.createdAtEpochMs }
            .take(MAX_STORED_INQUIRIES)
    }

    /**
     * Collects a privacy-safe diagnostic snapshot (app version, OS/device model, and boolean
     * permission flags only). Zero PII and zero screen content.
     */
    fun collectDiagnosticSnapshot(context: Context): SupportDiagnosticSnapshot {
        val appContext = context.applicationContext
        return SupportDiagnosticSnapshot(
            appVersion = resolveAppVersionName(appContext),
            androidVersion = "Android ${Build.VERSION.RELEASE ?: "?"} (API ${Build.VERSION.SDK_INT})",
            deviceModel = listOfNotNull(
                Build.MANUFACTURER?.takeIf { it.isNotBlank() },
                Build.MODEL?.takeIf { it.isNotBlank() },
            ).joinToString(" ").ifBlank { "알 수 없음" },
            usageGranted = hasUsageStatsPermission(appContext),
            overlayGranted = Settings.canDrawOverlays(appContext),
            accessibilityConnected = WritingGuard.connected,
            notificationsEnabled = runCatching {
                NotificationManagerCompat.from(appContext).areNotificationsEnabled()
            }.getOrDefault(false),
        )
    }

    fun resolveAppVersionName(context: Context): String {
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName?.takeIf { it.isNotBlank() } ?: "1.0"
        }.getOrDefault("1.0")
    }

    @Suppress("DEPRECATION")
    private fun hasUsageStatsPermission(context: Context): Boolean {
        return runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    private suspend fun persist(appContext: Context, items: List<SupportInquiry>) {
        val json = encodeInquiries(items)
        withContext(Dispatchers.IO) {
            appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit {
                    putString(KEY_INQUIRIES_JSON, json)
                }
        }
    }

    internal fun encodeInquiries(items: List<SupportInquiry>): String {
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("category", item.category.name)
                put("title", item.title)
                put("content", item.content)
                put("createdAtEpochMs", item.createdAtEpochMs)
                put("status", item.status.name)
                put("isReplyRead", item.isReplyRead)
                put("uid", item.uid)
                put("authorProvider", item.authorProvider)
                put("authorLabel", item.authorLabel)
                put("updatedAtEpochMs", item.updatedAtEpochMs)
                put("syncedToCloud", item.syncedToCloud)
                item.adminReply?.let { put("adminReply", it) }
                item.adminUid?.let { put("adminUid", it) }
                item.repliedAtEpochMs?.let { put("repliedAtEpochMs", it) }
                item.diagnostics?.let { d ->
                    put(
                        "diagnostics",
                        JSONObject().apply {
                            put("appVersion", d.appVersion)
                            put("androidVersion", d.androidVersion)
                            put("deviceModel", d.deviceModel)
                            put("usageGranted", d.usageGranted)
                            put("overlayGranted", d.overlayGranted)
                            put("accessibilityConnected", d.accessibilityConnected)
                            put("notificationsEnabled", d.notificationsEnabled)
                        },
                    )
                }
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    internal fun decodeInquiries(raw: String?): List<SupportInquiry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val category = runCatching {
                        InquiryCategory.valueOf(obj.optString("category"))
                    }.getOrDefault(InquiryCategory.OTHER)
                    val status = runCatching {
                        InquiryStatus.valueOf(obj.optString("status"))
                    }.getOrDefault(InquiryStatus.RECEIVED)
                    val createdAt = obj.optLong("createdAtEpochMs", 0L)
                    val diagObj = obj.optJSONObject("diagnostics")
                    val diagnostics = diagObj?.let { d ->
                        SupportDiagnosticSnapshot(
                            appVersion = d.optString("appVersion", "?"),
                            androidVersion = d.optString("androidVersion", "?"),
                            deviceModel = d.optString("deviceModel", "?"),
                            usageGranted = d.optBoolean("usageGranted", false),
                            overlayGranted = d.optBoolean("overlayGranted", false),
                            accessibilityConnected = d.optBoolean("accessibilityConnected", false),
                            notificationsEnabled = d.optBoolean("notificationsEnabled", false),
                        )
                    }
                    add(
                        SupportInquiry(
                            id = id,
                            category = category,
                            title = obj.optString("title", ""),
                            content = obj.optString("content", ""),
                            createdAtEpochMs = createdAt,
                            status = status,
                            diagnostics = diagnostics,
                            adminReply = obj.optString("adminReply", "").takeIf { it.isNotBlank() },
                            repliedAtEpochMs = if (obj.has("repliedAtEpochMs")) obj.optLong("repliedAtEpochMs") else null,
                            isReplyRead = obj.optBoolean("isReplyRead", true),
                            uid = obj.optString("uid", ""),
                            authorProvider = obj.optString("authorProvider", "GUEST"),
                            authorLabel = obj.optString("authorLabel", "사용자"),
                            adminUid = obj.optString("adminUid", "").takeIf { it.isNotBlank() },
                            updatedAtEpochMs = obj.optLong("updatedAtEpochMs", createdAt),
                            syncedToCloud = obj.optBoolean("syncedToCloud", false),
                        ),
                    )
                }
            }.sortedByDescending { it.createdAtEpochMs }
        }.getOrDefault(emptyList())
    }
}
