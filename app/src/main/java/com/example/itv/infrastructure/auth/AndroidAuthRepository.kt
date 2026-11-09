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
 * context는 구글 로그인 창을 띄우기 위한 Activity context다. 이 객체(와 AuthService)는
 * Activity보다 오래 보관하지 않는다.
 *
 * 소유자(owner) 생성은 조원의 RoomGoalStore.createLocalOwner를 통해서만 한다.
 *  - 게스트: ownerType GUEST, backup_state LOCAL_ONLY
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

    override suspend fun currentUser(): AuthUser? {
        val firebaseUser = firebaseAuth.currentUser
        if (firebaseUser != null) return accountUser(firebaseUser)
        return loadActiveGuest()
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

        prefs.edit().putBoolean(KEY_GUEST_ACTIVE, false).apply()
        // TODO(캐릭터 구현 시): prefs의 KEY_GUEST_ID 게스트 데이터를 firebaseUser.uid 계정으로 병합

        return accountUser(firebaseUser)
    }

    override suspend fun signInAsGuest(): AuthUser {
        // 이전에 쓰던 게스트가 DB에 남아 있으면 재사용한다. (목표 데이터 유지)
        val saved = prefs.getString(KEY_GUEST_ID, null)
            ?.takeIf { dao.owner(it)?.ownerType == "GUEST" }
        val guestId = saved ?: "guest-${UUID.randomUUID()}"

        goalStore.createLocalOwner(guestId, null)
        prefs.edit()
            .putString(KEY_GUEST_ID, guestId)
            .putBoolean(KEY_GUEST_ACTIVE, true)
            .apply()
        return guestUser(guestId)
    }

    override suspend fun signOut() {
        // 게스트 ID는 지우지 않는다. 다시 "게스트로 시작"하면 같은 데이터로 돌아온다.
        prefs.edit().putBoolean(KEY_GUEST_ACTIVE, false).apply()
        firebaseAuth.signOut()
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 자격 증명 초기화 실패는 로그아웃 자체에 영향이 없다.
        }
    }

    // ---- 내부 ----

    private suspend fun loadActiveGuest(): AuthUser? {
        if (!prefs.getBoolean(KEY_GUEST_ACTIVE, false)) return null
        val guestId = prefs.getString(KEY_GUEST_ID, null) ?: return null
        // 앱 백업 복원 등으로 prefs만 남고 DB가 비어 있을 수 있으니 DB에서 한 번 더 확인한다.
        if (dao.owner(guestId)?.ownerType != "GUEST") return null
        return guestUser(guestId)
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
            // 임시: 서버 복원 기능이 없어서 NOT_CHECKED로 두면 목표를 저장할 수 없다.
            // 서버 백업/복원이 붙으면 이 블록은 삭제하고 복원 흐름이 READY로 바꾸게 한다.
            dao.saveBackupState(BackupStateEntity(uid, restoreState = "READY"))
        }
    }

    private companion object {
        const val PREFS = "itv_auth"
        const val KEY_GUEST_ID = "guest_owner_id"
        const val KEY_GUEST_ACTIVE = "guest_active"
        const val SERVER_RESTORE_AVAILABLE = false
    }
}


