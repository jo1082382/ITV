package com.example.itv.infrastructure.support

import android.content.Context
import android.util.Log
import com.example.itv.domain.auth.AuthProvider
import com.example.itv.domain.support.InquiryCategory
import com.example.itv.domain.support.InquiryStatus
import com.example.itv.domain.support.SupportDiagnosticSnapshot
import com.example.itv.domain.support.SupportInquiry
import com.example.itv.infrastructure.auth.LocalSessionStore
import com.google.android.gms.tasks.Task
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cloud Firestore gateway for Customer Center 1:1 inquiries and admin ACL checks.
 *
 * Collections (strictly validated by `firestore.rules`):
 * - `support_admins/{uid}`: server/console-managed admin allowlist (read own doc only, zero client writes)
 * - `support_inquiries/{inquiryId}`: 1:1 inquiry documents (owner-isolated + admin support center)
 */
object SupportFirestoreRepository {
    private const val TAG = "ITV_SUPPORT_CLOUD"
    private const val COLLECTION_INQUIRIES = "support_inquiries"
    private const val COLLECTION_ADMINS = "support_admins"
    private const val REQUEST_TIMEOUT_MS = 10_000L
    private const val AUTH_TIMEOUT_MS = 6_000L

    data class SupportAuthorIdentity(
        val uid: String,
        val provider: String,
        val label: String,
        val isAnonymous: Boolean,
    )

    private fun firestore(): FirebaseFirestore = Firebase.firestore

    /**
     * Resolves an authenticated Firebase Auth identity for submitting or syncing inquiries.
     * - Signed-in social users reuse their Firebase Auth UID and non-PII display label.
     * - Guest users without an active Firebase session sign in anonymously so they can still
     *   submit inquiries and receive replies on this device.
     */
    suspend fun resolveOrCreateAuthorIdentity(
        context: Context,
        allowAnonymousSignIn: Boolean,
    ): SupportAuthorIdentity? = withContext(Dispatchers.IO) {
        val auth = FirebaseAuth.getInstance()
        val savedLocal = runCatching { LocalSessionStore(context.applicationContext).loadSession() }.getOrNull()

        var fbUser: FirebaseUser? = auth.currentUser
        if (fbUser == null && allowAnonymousSignIn) {
            fbUser = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
                runCatching { auth.signInAnonymously().awaitTask().user }.getOrNull()
            }
        }
        val activeUser = fbUser ?: return@withContext null

        val provider = when {
            activeUser.isAnonymous -> "GUEST"
            savedLocal != null && savedLocal.uid == activeUser.uid && savedLocal.provider != AuthProvider.GUEST ->
                savedLocal.provider.name
            activeUser.uid.startsWith("kakao_") -> "KAKAO"
            activeUser.uid.startsWith("naver_") -> "NAVER"
            else -> "GOOGLE"
        }

        // Strictly non-PII display label (never store raw email/phone in inquiry documents)
        val rawName = activeUser.displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: savedLocal?.displayName?.trim()?.takeIf { it.isNotBlank() }
        val safeLabel = (rawName ?: if (activeUser.isAnonymous) "게스트 사용자" else "ITV 사용자")
            .take(60)

        SupportAuthorIdentity(
            uid = activeUser.uid,
            provider = provider,
            label = safeLabel,
            isAnonymous = activeUser.isAnonymous,
        )
    }

    /**
     * Checks whether the currently signed-in social user has verified Support Admin privileges
     * via either a Firebase Auth custom claim (`admin == true`) or a server-managed document at
     * `support_admins/{uid}`. Never throws and returns false for guests/unauthenticated users.
     */
    suspend fun checkIsSupportAdmin(): Boolean = withContext(Dispatchers.IO) {
        val fbUser = FirebaseAuth.getInstance().currentUser
        if (fbUser == null || fbUser.isAnonymous) return@withContext false

        // 1. Check Firebase Auth custom claim first (zero Firestore read if already set)
        val hasClaim = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
            runCatching {
                val tokenResult = fbUser.getIdToken(false).awaitTask()
                tokenResult.claims["admin"] == true
            }.getOrDefault(false)
        } == true
        if (hasClaim) return@withContext true

        // 2. Fallback: check server/console-managed `support_admins/{uid}` allowlist doc
        val existsInAcl = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                firestore()
                    .collection(COLLECTION_ADMINS)
                    .document(fbUser.uid)
                    .get()
                    .awaitTask()
                    .exists()
            }.getOrDefault(false)
        } == true
        existsInAcl
    }

    fun currentFirebaseUid(): String? = runCatching {
        FirebaseAuth.getInstance().currentUser?.uid
    }.getOrNull()

    suspend fun uploadInquiry(inquiry: SupportInquiry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(inquiry.uid.isNotBlank()) { "인증 UID가 없어 클라우드에 업로드할 수 없습니다." }
            val payload = toCreateFirestoreMap(inquiry)
            val completed = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                firestore()
                    .collection(COLLECTION_INQUIRIES)
                    .document(inquiry.id)
                    .set(payload)
                    .awaitTask()
                true
            }
            if (completed != true) {
                throw IllegalStateException("서버 응답 시간이 초과되었습니다.")
            }
        }.onFailure { err ->
            Log.w(TAG, "uploadInquiry failed for id=${inquiry.id}: ${err.message}")
        }
    }

    /**
     * Fetches the current user's inquiries using a single-field index (`uid ==`) and sorts
     * client-side so it works immediately with zero composite-index setup delay.
     */
    suspend fun fetchMyInquiries(uid: String): Result<List<SupportInquiry>> = withContext(Dispatchers.IO) {
        runCatching {
            val snap = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                firestore()
                    .collection(COLLECTION_INQUIRIES)
                    .whereEqualTo("uid", uid)
                    .limit(50)
                    .get()
                    .awaitTask()
            } ?: throw IllegalStateException("문의 내역 동기화 시간이 초과되었습니다.")

            snap.documents
                .mapNotNull(::fromDocumentSnapshot)
                .sortedByDescending { it.createdAtEpochMs }
        }.onFailure { err ->
            Log.w(TAG, "fetchMyInquiries failed: ${err.message}")
        }
    }

    suspend fun markReplyReadRemote(inquiryId: String, nowMs: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val completed = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                firestore()
                    .collection(COLLECTION_INQUIRIES)
                    .document(inquiryId)
                    .update(
                        mapOf(
                            "isReplyRead" to true,
                            "updatedAt" to nowMs,
                        ),
                    )
                    .awaitTask()
                true
            }
            if (completed != true) {
                throw IllegalStateException("읽음 처리 응답 시간이 초과되었습니다.")
            }
        }.onFailure { err ->
            Log.w(TAG, "markReplyReadRemote failed for id=$inquiryId: ${err.message}")
        }
    }

    suspend fun deleteInquiryRemote(inquiryId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val completed = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                firestore()
                    .collection(COLLECTION_INQUIRIES)
                    .document(inquiryId)
                    .delete()
                    .awaitTask()
                true
            }
            if (completed != true) {
                throw IllegalStateException("삭제 요청 응답 시간이 초과되었습니다.")
            }
        }.onFailure { err ->
            Log.w(TAG, "deleteInquiryRemote failed for id=$inquiryId: ${err.message}")
        }
    }

    /**
     * Best-effort cleanup of all support inquiries belonging to [uid] during account deletion.
     */
    suspend fun deleteAllUserInquiries(uid: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val db = firestore()
            val snap = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                db.collection(COLLECTION_INQUIRIES)
                    .whereEqualTo("uid", uid)
                    .limit(100)
                    .get()
                    .awaitTask()
            } ?: return@runCatching 0
            if (snap.isEmpty) return@runCatching 0

            val batch = db.batch()
            snap.documents.forEach { doc -> batch.delete(doc.reference) }
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                batch.commit().awaitTask()
            }
            snap.size()
        }.onFailure { err ->
            Log.w(TAG, "deleteAllUserInquiries failed: ${err.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Admin Support Center Queries & Mutations
    // -------------------------------------------------------------------------

    /**
     * Fetches the latest inquiries across all users for verified support admins.
     * Uses only the automatic single-field index on `createdAt`.
     */
    suspend fun fetchAllInquiriesForAdmin(limit: Long = 100L): Result<List<SupportInquiry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snap = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    firestore()
                        .collection(COLLECTION_INQUIRIES)
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .limit(limit)
                        .get()
                        .awaitTask()
                } ?: throw IllegalStateException("관리자 문의 목록을 불러오는 데 시간이 초과되었습니다.")

                snap.documents.mapNotNull(::fromDocumentSnapshot)
            }.onFailure { err ->
                Log.w(TAG, "fetchAllInquiriesForAdmin failed: ${err.message}")
            }
        }

    suspend fun submitAdminReply(
        inquiryId: String,
        replyText: String,
        previousUpdatedAtMs: Long,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val adminUser = FirebaseAuth.getInstance().currentUser
            if (adminUser == null || adminUser.isAnonymous) {
                throw IllegalStateException("관리자 로그인 세션이 필요합니다.")
            }
            val nowMs = maxOf(System.currentTimeMillis(), previousUpdatedAtMs)
            val completed = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                firestore()
                    .collection(COLLECTION_INQUIRIES)
                    .document(inquiryId)
                    .update(
                        mapOf(
                            "status" to InquiryStatus.ANSWERED.name,
                            "adminReply" to replyText,
                            "adminUid" to adminUser.uid,
                            "repliedAt" to nowMs,
                            "updatedAt" to nowMs,
                            "isReplyRead" to false,
                        ),
                    )
                    .awaitTask()
                true
            }
            if (completed != true) {
                throw IllegalStateException("답변 저장 시간이 초과되었습니다.")
            }
            nowMs
        }.onFailure { err ->
            Log.w(TAG, "submitAdminReply failed for id=$inquiryId: ${err.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Strict Schema Serialization / Deserialization
    // -------------------------------------------------------------------------

    internal fun toCreateFirestoreMap(inquiry: SupportInquiry): Map<String, Any?> {
        val diagMap: Map<String, Any>? = inquiry.diagnostics?.let { d ->
            mapOf(
                "appVersion" to d.appVersion.take(32).ifBlank { "?" },
                "androidVersion" to d.androidVersion.take(64).ifBlank { "?" },
                "deviceModel" to d.deviceModel.take(80).ifBlank { "?" },
                "usageGranted" to d.usageGranted,
                "overlayGranted" to d.overlayGranted,
                "accessibilityConnected" to d.accessibilityConnected,
                "notificationsEnabled" to d.notificationsEnabled,
            )
        }
        val createdAt = inquiry.createdAtEpochMs
        val updatedAt = maxOf(inquiry.updatedAtEpochMs, createdAt)
        return mapOf(
            "uid" to inquiry.uid.take(128),
            "authorProvider" to inquiry.authorProvider.takeIf {
                it in setOf("GOOGLE", "KAKAO", "NAVER", "GUEST")
            }.orEmpty().ifBlank { "GUEST" },
            "authorLabel" to inquiry.authorLabel.trim().take(60).ifBlank { "ITV 사용자" },
            "category" to inquiry.category.name,
            "title" to inquiry.title.trim().take(80),
            "content" to inquiry.content.trim().take(1000),
            "diagnostics" to diagMap,
            "status" to InquiryStatus.RECEIVED.name,
            "adminReply" to null,
            "adminUid" to null,
            "createdAt" to createdAt,
            "repliedAt" to null,
            "updatedAt" to updatedAt,
            "isReplyRead" to true,
        )
    }

    @Suppress("UNCHECKED_CAST")
    internal fun fromDocumentSnapshot(doc: DocumentSnapshot): SupportInquiry? {
        val id = doc.id.takeIf { it.isNotBlank() } ?: return null
        val uid = doc.getString("uid") ?: return null
        val category = runCatching {
            InquiryCategory.valueOf(doc.getString("category") ?: "")
        }.getOrDefault(InquiryCategory.OTHER)
        val status = runCatching {
            InquiryStatus.valueOf(doc.getString("status") ?: "")
        }.getOrDefault(InquiryStatus.RECEIVED)
        val createdAt = doc.getLong("createdAt") ?: 0L
        val updatedAt = doc.getLong("updatedAt") ?: createdAt
        val diagRaw = doc.get("diagnostics") as? Map<String, Any?>
        val diagnostics = diagRaw?.let { m ->
            SupportDiagnosticSnapshot(
                appVersion = (m["appVersion"] as? String).orEmpty().ifBlank { "?" },
                androidVersion = (m["androidVersion"] as? String).orEmpty().ifBlank { "?" },
                deviceModel = (m["deviceModel"] as? String).orEmpty().ifBlank { "?" },
                usageGranted = m["usageGranted"] as? Boolean ?: false,
                overlayGranted = m["overlayGranted"] as? Boolean ?: false,
                accessibilityConnected = m["accessibilityConnected"] as? Boolean ?: false,
                notificationsEnabled = m["notificationsEnabled"] as? Boolean ?: false,
            )
        }
        return SupportInquiry(
            id = id,
            category = category,
            title = doc.getString("title").orEmpty(),
            content = doc.getString("content").orEmpty(),
            createdAtEpochMs = createdAt,
            status = status,
            diagnostics = diagnostics,
            adminReply = doc.getString("adminReply")?.takeIf { it.isNotBlank() },
            repliedAtEpochMs = doc.getLong("repliedAt"),
            isReplyRead = doc.getBoolean("isReplyRead") ?: true,
            uid = uid,
            authorProvider = doc.getString("authorProvider") ?: "GUEST",
            authorLabel = doc.getString("authorLabel") ?: "ITV 사용자",
            adminUid = doc.getString("adminUid")?.takeIf { it.isNotBlank() },
            updatedAtEpochMs = updatedAt,
            syncedToCloud = true,
        )
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { e ->
            if (cont.isActive) cont.resumeWithException(e)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }
}
