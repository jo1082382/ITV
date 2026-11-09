package com.example.itv.infrastructure.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.itv.data.local.BackupStateEntity
import com.example.itv.data.local.ItvDatabase
import com.example.itv.domain.auth.*
import com.example.itv.infrastructure.goals.RoomGoalStore
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * CredentialManager + FirebaseAuth + Room(owners)을 묶어 AuthRepository 계약을 채운다.
 * context는 구글 로그인 창을 띄우기 위한 Activity context다. 이 객체는 Activity보다 오래 보관하지 않는다.
 *
 * 현재 활성 사용자는 Firebase 세션이 아니라 이 클래스가 저장한 값(active_owner_id/type)이 기준이다.
 * Firebase는 신원 확인에만 쓴다. (인증 후 전환 확정 전에 취소하면 기존 사용자를 유지해야 하므로 — SRS FR-02)
 *
 * 소유자 생성은 조원의 RoomGoalStore.createLocalOwner를 통해서만 한다.
 *  - 게스트: ownerType GUEST, ownerId = "guest-<UUID>"
 *  - 구글  : ownerType ACCOUNT, ownerId = authUid = Firebase uid
 */
internal class AndroidAuthRepository(
    private val context: Context,
    private val firebaseAuth: FirebaseAuth,
    private val webClientId: String, // Firebase 콘솔의 Web Client ID
    private val db: ItvDatabase
) : AuthRepository {

    private val credentialManager = CredentialManager.create(context)
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dao = db.dao()
    private val goalStore = RoomGoalStore(db)

    /** 저장된 활성 사용자를 복원한다. 최초 실행(저장값 없음)이면 null. */
    override suspend fun currentUser(): AuthUser? {
        val activeId = prefs.getString(KEY_ACTIVE_ID, null) ?: return null
        return when (prefs.getString(KEY_ACTIVE_TYPE, null)) {
            TYPE_GOOGLE -> {
                val firebaseUser = firebaseAuth.currentUser
                if (firebaseUser != null && firebaseUser.uid == activeId) {
                    accountUser(firebaseUser)
                } else {
                    // Firebase 세션이 사라졌다. 계정 자료는 보관하고 로그아웃과 같게 새 게스트로 전환한다.
                    startNewGuest()
                }
            }
            TYPE_GUEST -> activeGuestOrNull()
            else -> null
        }
    }

    override suspend fun signInWithGoogle(): AuthUser {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = try {
            credentialManager.getCredential(context, request)
        } catch (e: GetCredentialCancellationException) {
            throw LoginCancelledException()
        }

        val credential = result.credential
        require(
            credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) { "지원하지 않는 로그인 정보입니다." }

        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        val authResult = firebaseAuth.signInWithCredential(firebaseCredential).await()
        val firebaseUser = requireNotNull(authResult.user) { "로그인은 성공했지만 사용자 정보를 가져오지 못했습니다." }

        // TODO(다음 PR, SRS 3.1.3): 여기서 바로 전환하지 말고
        //  인증 → (병합 / 게스트 전체 삭제 선택 + 확인) → 전환 확정 순서로 분리한다.
        //  선택 취소 시 firebaseAuth.signOut()으로 되돌리고 기존 게스트를 유지한다.
        //  게스트 데이터를 계정으로 병합하는 처리도 이 지점이다.
        val user = accountUser(firebaseUser)
        setActive(firebaseUser.uid, TYPE_GOOGLE)
        return user
    }

    override suspend fun signInAsGuest(): AuthUser = activeGuestOrNull() ?: startNewGuest()

    override suspend fun signOut(): AuthUser {
        // 게스트에게는 로그아웃할 계정이 없다. 현재 게스트를 그대로 둔다.
        activeGuestOrNull()?.let { return it }

        firebaseAuth.signOut()
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 자격 증명 초기화 실패는 로그아웃 자체에 영향이 없다.
        }
        // 계정 자료는 그대로 보관하고, 이전 계정 기록을 복사하지 않은 빈 새 게스트로 전환한다.
        return startNewGuest()
    }

    // ---- 내부 ----

    private suspend fun activeGuestOrNull(): AuthUser? {
        if (prefs.getString(KEY_ACTIVE_TYPE, null) != TYPE_GUEST) return null
        val guestId = prefs.getString(KEY_ACTIVE_ID, null) ?: return null
        // 앱 백업 복원 등으로 저장값만 남고 DB가 비어 있을 수 있어 DB에서 한 번 더 확인한다.
        if (dao.owner(guestId)?.ownerType != "GUEST") return null
        return guestUser(guestId)
    }

    private suspend fun startNewGuest(): AuthUser {
        val guestId = "guest-${UUID.randomUUID()}"
        goalStore.createLocalOwner(guestId, null)
        setActive(guestId, TYPE_GUEST)
        return guestUser(guestId)
    }

    private fun setActive(ownerId: String, type: String) {
        prefs.edit().putString(KEY_ACTIVE_ID, ownerId).putString(KEY_ACTIVE_TYPE, type).apply()
    }

    private suspend fun accountUser(firebaseUser: FirebaseUser): AuthUser {
        ensureAccountOwner(firebaseUser.uid)
        return AuthUser(
            ownerId = firebaseUser.uid,
            loginType = LoginType.GOOGLE,
            role = AuthPolicy.resolveRole(firebaseUser.email),
            displayName = firebaseUser.displayName,
            email = firebaseUser.email,
            firebaseUid = firebaseUser.uid
        )
    }

    private fun guestUser(guestId: String) = AuthUser(
        ownerId = guestId,
        loginType = LoginType.GUEST,
        role = UserRole.USER,
        displayName = "게스트",
        email = null,
        firebaseUid = null
    )

    private suspend fun ensureAccountOwner(uid: String) {
        goalStore.createLocalOwner(uid, uid) // 이미 있으면 계정 일치만 확인하고 넘어간다.
        if (!SERVER_RESTORE_AVAILABLE && dao.backupState(uid)?.restoreState != "READY") {
            // 임시: SRS상 서버 복원·기기 간 동기화는 필수 범위가 아니다. 서버 백업이 붙으면 이 블록은 삭제한다.
            dao.saveBackupState(BackupStateEntity(uid, restoreState = "READY"))
        }
    }

    private companion object {
        const val PREFS = "itv_auth"
        const val KEY_ACTIVE_ID = "active_owner_id"
        const val KEY_ACTIVE_TYPE = "active_owner_type"
        const val TYPE_GUEST = "GUEST"
        const val TYPE_GOOGLE = "GOOGLE"
        const val SERVER_RESTORE_AVAILABLE = false
    }
}
