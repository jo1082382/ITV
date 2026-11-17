package com.example.itv.infrastructure.auth

import com.example.itv.domain.auth.AuthProvider
import com.example.itv.domain.auth.AuthRepository
import com.example.itv.domain.auth.UserProfile
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FirebaseAuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val sessionStore: LocalSessionStore? = null,
    private val appContext: android.content.Context? = null
) : AuthRepository {

    private val _currentUser = MutableStateFlow<UserProfile?>(
        auth.currentUser?.toDomain() ?: sessionStore?.loadSession()
    )
    override val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    override fun getLastLoginProvider(): AuthProvider? = sessionStore?.getLastProvider()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val firebaseUser = firebaseAuth.currentUser
            if (firebaseUser != null) {
                _currentUser.value = firebaseUser.toDomain()
            } else {
                val localUser = sessionStore?.loadSession()
                _currentUser.value = localUser
            }
        }
    }

    private suspend fun syncLocalOwnerAccount(authUid: String) {
        val ctx = appContext ?: return
        runCatching {
            val db = com.example.itv.data.local.ItvDatabase.get(ctx)
            val dao = db.dao()
            val existing = dao.owner("demo-guest")
            if (existing != null) {
                dao.upgradeOwner("demo-guest", authUid)
            } else {
                dao.insertOwner(com.example.itv.data.local.OwnerEntity(
                    ownerId = "demo-guest",
                    ownerType = "ACCOUNT",
                    authUid = authUid,
                    createdAt = System.currentTimeMillis()
                ))
            }
            dao.saveBackupState(com.example.itv.data.local.BackupStateEntity(
                ownerId = "demo-guest",
                restoreState = "READY"
            ))
        }
    }

    private suspend fun syncLocalOwnerGuest() {
        val ctx = appContext ?: return
        runCatching {
            val db = com.example.itv.data.local.ItvDatabase.get(ctx)
            val dao = db.dao()
            val existing = dao.owner("demo-guest")
            if (existing != null) {
                dao.resetOwnerToGuest("demo-guest")
            } else {
                dao.insertOwner(com.example.itv.data.local.OwnerEntity(
                    ownerId = "demo-guest",
                    ownerType = "GUEST",
                    authUid = null,
                    createdAt = System.currentTimeMillis()
                ))
            }
            dao.saveBackupState(com.example.itv.data.local.BackupStateEntity(
                ownerId = "demo-guest",
                restoreState = "READY"
            ))
        }
    }

    override suspend fun signInAsGuest(): Result<UserProfile> = runCatching {
        sessionStore?.clearSession()
        val authResult = auth.signInAnonymously().awaitTask()
        val user = authResult.user ?: throw IllegalStateException("익명 로그인 사용자 정보를 가져올 수 없습니다.")
        syncLocalOwnerGuest()
        user.toDomain()
    }

    override suspend fun signInWithGoogle(idToken: String): Result<UserProfile> = runCatching {
        val guestUser = auth.currentUser?.takeIf { it.isAnonymous }
        sessionStore?.clearSession()
        val credential = GoogleAuthProvider.getCredential(idToken, null)

        val targetUser: FirebaseUser = if (guestUser != null) {
            try {
                // 1. 게스트 계정에 구글 계정 연동 시도 (새로운 구글 계정인 경우)
                val authResult = guestUser.linkWithCredential(credential).awaitTask()
                authResult.user ?: guestUser
            } catch (collision: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                // 2. 이미 존재하는 구글 계정인 경우:
                // 기존 게스트(익명) 계정을 파이어베이스에서 완전히 삭제합니다!
                android.util.Log.i("ITV_AUTH", "Google account already exists. Deleting anonymous guest account from Firebase...")
                try {
                    guestUser.delete().awaitTask()
                    android.util.Log.i("ITV_AUTH", "Anonymous guest account deleted successfully.")
                } catch (e: Exception) {
                    android.util.Log.w("ITV_AUTH", "Failed to delete anonymous user: ${e.message}")
                }
                // 기존 구글 계정으로 로그인하여 전환
                val authResult = auth.signInWithCredential(credential).awaitTask()
                authResult.user ?: throw collision
            }
        } else {
            val authResult = auth.signInWithCredential(credential).awaitTask()
            authResult.user ?: throw IllegalStateException("구글 로그인 사용자 정보를 가져올 수 없습니다.")
        }

        sessionStore?.setLastProvider(AuthProvider.GOOGLE)
        syncLocalOwnerAccount(targetUser.uid)
        targetUser.toDomain()
    }

    override suspend fun signInWithKakao(profile: UserProfile): Result<UserProfile> = runCatching {
        val guestUser = auth.currentUser?.takeIf { it.isAnonymous }
        if (guestUser != null) {
            android.util.Log.i("ITV_AUTH", "Deleting anonymous guest account upon Kakao sign in...")
            runCatching { guestUser.delete().awaitTask() }
        }
        sessionStore?.saveSession(profile)
        _currentUser.value = profile
        syncLocalOwnerAccount(profile.uid)
        profile
    }

    override suspend fun signInWithNaver(profile: UserProfile): Result<UserProfile> = runCatching {
        val guestUser = auth.currentUser?.takeIf { it.isAnonymous }
        if (guestUser != null) {
            android.util.Log.i("ITV_AUTH", "Deleting anonymous guest account upon Naver sign in...")
            runCatching { guestUser.delete().awaitTask() }
        }
        sessionStore?.saveSession(profile)
        _currentUser.value = profile
        syncLocalOwnerAccount(profile.uid)
        profile
    }

    override suspend fun linkGuestWithGoogle(idToken: String): Result<UserProfile> = runCatching {
        val user = auth.currentUser ?: throw IllegalStateException("연동할 현재 게스트 로그인 세션이 없습니다.")
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val targetUser: FirebaseUser = try {
            val authResult = user.linkWithCredential(credential).awaitTask()
            val linkedUser = authResult.user ?: user
            sessionStore?.clearSession()
            sessionStore?.setLastProvider(AuthProvider.GOOGLE)
            linkedUser
        } catch (collision: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
            // 이미 다른 기기 등에서 가입된 기존 구글 계정이 존재하는 경우:
            // 임시 게스트 계정을 파이어베이스에서 완전히 삭제하고, 원래 구글 본계정으로 전환합니다!
            android.util.Log.i("ITV_AUTH", "Google account already exists. Deleting anonymous guest account from Firebase...")
            try {
                user.delete().awaitTask()
                android.util.Log.i("ITV_AUTH", "Anonymous guest account deleted successfully.")
            } catch (e: Exception) {
                android.util.Log.w("ITV_AUTH", "Failed to delete anonymous user: ${e.message}")
            }
            val authResult = auth.signInWithCredential(credential).awaitTask()
            val existingUser = authResult.user ?: throw collision
            sessionStore?.clearSession()
            sessionStore?.setLastProvider(AuthProvider.GOOGLE)
            existingUser
        }
        syncLocalOwnerAccount(targetUser.uid)
        targetUser.toDomain()
    }

    override suspend fun signOut(): Result<Unit> = runCatching {
        val current = _currentUser.value
        when (current?.provider) {
            AuthProvider.KAKAO -> KakaoAuthClient.signOut()
            AuthProvider.NAVER -> NaverAuthClient.signOut()
            else -> {}
        }
        if (auth.currentUser != null) {
            auth.signOut()
        }
        sessionStore?.clearSession()
        _currentUser.value = null
    }

    override suspend fun deleteAccount(googleIdToken: String?): Result<Unit> = runCatching {
        val current = _currentUser.value
        when (current?.provider) {
            AuthProvider.KAKAO -> {
                KakaoAuthClient.unlink()
                sessionStore?.clearSession()
                sessionStore?.clearLastProvider()
                _currentUser.value = null
                return@runCatching
            }
            AuthProvider.NAVER -> {
                NaverAuthClient.unlink()
                sessionStore?.clearSession()
                sessionStore?.clearLastProvider()
                _currentUser.value = null
                return@runCatching
            }
            else -> {}
        }
        val user = auth.currentUser
        if (user != null) {
            if (googleIdToken != null) {
                try {
                    val credential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    user.reauthenticate(credential).awaitTask()
                    android.util.Log.i("ITV_AUTH", "Google re-authentication succeeded before delete")
                } catch (e: Exception) {
                    android.util.Log.w("ITV_AUTH", "Re-authentication before delete failed: ${e.message}")
                }
            }
            try {
                user.delete().awaitTask()
            } catch (e: Exception) {
                android.util.Log.e("ITV_AUTH", "user.delete failed: ${e.javaClass.simpleName} - ${e.message}", e)
                throw e
            }
        }
        sessionStore?.clearSession()
        sessionStore?.clearLastProvider()
        _currentUser.value = null
    }

    private fun FirebaseUser.toDomain(): UserProfile {
        val detectedProvider = when {
            isAnonymous -> AuthProvider.GUEST
            providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID } -> AuthProvider.GOOGLE
            else -> AuthProvider.GUEST
        }
        return UserProfile(
            uid = uid,
            provider = detectedProvider,
            displayName = displayName,
            email = email,
            photoUrl = photoUrl?.toString(),
            isAnonymous = isAnonymous
        )
    }

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
