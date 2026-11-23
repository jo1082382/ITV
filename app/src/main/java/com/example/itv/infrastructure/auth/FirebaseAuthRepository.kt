package com.example.itv.infrastructure.auth

import android.content.Context
import android.util.Log
import com.example.itv.domain.auth.AuthProvider
import com.example.itv.domain.auth.AuthRepository
import com.example.itv.domain.auth.UserProfile
import com.example.itv.infrastructure.cloud.CloudSyncRepository
import com.example.itv.infrastructure.cloud.DailyCloudBackupWorker
import com.example.itv.infrastructure.equip.UserEquipStore
import com.example.itv.infrastructure.reward.UserRewardStore
import com.example.itv.infrastructure.storage.CalendarHealthLoader
import com.example.itv.infrastructure.storage.LocalDataWiper
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FirebaseAuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val sessionStore: LocalSessionStore? = null,
    private val appContext: Context? = null
) : AuthRepository {

    companion object {
        private const val TAG = "ITV_AUTH"
        private const val RECENT_LOGIN_WINDOW_MS = 4L * 60_000L
    }

    private val _currentUser = MutableStateFlow<UserProfile?>(
        resolveCurrentProfile(auth.currentUser, sessionStore?.loadSession())
    )
    override val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    override fun getLastLoginProvider(): AuthProvider? = sessionStore?.getLastProvider()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = resolveCurrentProfile(
                firebaseUser = firebaseAuth.currentUser,
                savedLocal = sessionStore?.loadSession()
            )
        }
    }

    private fun resolveCurrentProfile(
        firebaseUser: FirebaseUser?,
        savedLocal: UserProfile?
    ): UserProfile? {
        if (firebaseUser == null) return savedLocal
        val domain = firebaseUser.toDomain()
        // For Kakao/Naver Custom Token users, preserve email/nickname/photo from LocalSessionStore when Firebase Auth omits email
        return if (savedLocal != null && savedLocal.uid == domain.uid) {
            domain.copy(
                provider = if (domain.provider == AuthProvider.GUEST && !savedLocal.isAnonymous) {
                    savedLocal.provider
                } else {
                    domain.provider
                },
                displayName = domain.displayName ?: savedLocal.displayName,
                email = domain.email ?: savedLocal.email,
                photoUrl = domain.photoUrl ?: savedLocal.photoUrl
            )
        } else {
            domain
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
                dao.insertOwner(
                    com.example.itv.data.local.OwnerEntity(
                        ownerId = "demo-guest",
                        ownerType = "ACCOUNT",
                        authUid = authUid,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            dao.saveBackupState(
                com.example.itv.data.local.BackupStateEntity(
                    ownerId = "demo-guest",
                    restoreState = "READY"
                )
            )
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
                dao.insertOwner(
                    com.example.itv.data.local.OwnerEntity(
                        ownerId = "demo-guest",
                        ownerType = "GUEST",
                        authUid = null,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            dao.saveBackupState(
                com.example.itv.data.local.BackupStateEntity(
                    ownerId = "demo-guest",
                    restoreState = "READY"
                )
            )
        }
    }

    private suspend fun performPostSocialLoginCloudSync(
        profile: UserProfile,
        mergeWithLocal: Boolean
    ) {
        val ctx = appContext ?: return
        val fbUser = auth.currentUser
        if (fbUser == null || fbUser.isAnonymous) return

        val restoreResult = CloudSyncRepository.restoreFromCloud(ctx, mergeWithLocal = mergeWithLocal)
        if (restoreResult.isFailure) {
            // Never overwrite existing cloud backup with empty local state when restore fails transiently
            Log.w(TAG, "Cloud restore failed during login; skipping overwrite backup", restoreResult.exceptionOrNull())
        } else {
            val hadCloudData = restoreResult.getOrNull()?.hadCloudData == true
            if (mergeWithLocal || !hadCloudData) {
                // Upload merged guest data or seed initial cloud state for a brand-new social account
                CloudSyncRepository.backupAllToCloud(ctx, profile)
            }
        }

        // Pre-warm Home calendar & gamification caches BEFORE exiting the login waiting screen
        // so the user lands on a fully populated Home screen with zero blank-calendar flash.
        CloudSyncRepository.setProgressMessage("홈 화면과 캘린더 기록을 준비하는 중...")
        try {
            withTimeoutOrNull(5_000L) {
                runCatching { UserRewardStore.getReward(ctx) }
                runCatching { UserEquipStore.getEquipped(ctx) }
                runCatching { CalendarHealthLoader.preloadCurrentMonth(ctx) }
            }
        } finally {
            CloudSyncRepository.setProgressMessage(null)
        }
        DailyCloudBackupWorker.schedule(ctx)
    }

    override suspend fun signInAsGuest(): Result<UserProfile> = runCatching {
        sessionStore?.clearSession()
        val authResult = auth.signInAnonymously().awaitTask()
        val user = authResult.user ?: throw IllegalStateException("익명 로그인 사용자 정보를 가져올 수 없습니다.")
        syncLocalOwnerGuest()
        appContext?.let { DailyCloudBackupWorker.cancel(it) }
        user.toDomain()
    }

    override suspend fun signInWithGoogle(idToken: String): Result<UserProfile> = runCatching {
        val guestUser = auth.currentUser?.takeIf { it.isAnonymous }
        val wasGuest = guestUser != null
        sessionStore?.clearSession()
        val credential = GoogleAuthProvider.getCredential(idToken, null)

        val targetUser: FirebaseUser = if (guestUser != null) {
            try {
                // 1. 게스트 계정에 구글 계정 연동 시도 (새로운 구글 계정인 경우)
                val authResult = guestUser.linkWithCredential(credential).awaitTask()
                authResult.user ?: guestUser
            } catch (collision: FirebaseAuthUserCollisionException) {
                // 2. 이미 존재하는 구글 계정인 경우: 기존 익명 계정을 삭제하고 본계정으로 전환
                Log.i(TAG, "Google account already exists. Deleting anonymous guest account from Firebase...")
                runCatching { guestUser.delete().awaitTask() }
                val authResult = auth.signInWithCredential(credential).awaitTask()
                authResult.user ?: throw collision
            }
        } else {
            val authResult = auth.signInWithCredential(credential).awaitTask()
            authResult.user ?: throw IllegalStateException("구글 로그인 사용자 정보를 가져올 수 없습니다.")
        }

        val domain = targetUser.toDomain()
        sessionStore?.setLastProvider(AuthProvider.GOOGLE)
        syncLocalOwnerAccount(targetUser.uid)
        performPostSocialLoginCloudSync(profile = domain, mergeWithLocal = wasGuest)
        domain
    }

    override suspend fun signInWithKakao(
        profile: UserProfile,
        accessToken: String?,
        mergeLocalGuestData: Boolean
    ): Result<UserProfile> = signInWithSocialCustomToken(
        provider = AuthProvider.KAKAO,
        profile = profile,
        accessToken = accessToken,
        mergeLocalGuestData = mergeLocalGuestData
    )

    override suspend fun signInWithNaver(
        profile: UserProfile,
        accessToken: String?,
        mergeLocalGuestData: Boolean
    ): Result<UserProfile> = signInWithSocialCustomToken(
        provider = AuthProvider.NAVER,
        profile = profile,
        accessToken = accessToken,
        mergeLocalGuestData = mergeLocalGuestData
    )

    private suspend fun signInWithSocialCustomToken(
        provider: AuthProvider,
        profile: UserProfile,
        accessToken: String?,
        mergeLocalGuestData: Boolean
    ): Result<UserProfile> = runCatching {
        val guestUser = auth.currentUser?.takeIf { it.isAnonymous }
        val shouldMergeLocal = mergeLocalGuestData || guestUser != null

        var finalProfile = profile
        if (!accessToken.isNullOrBlank()) {
            // Exchange provider OAuth token for a verified Firebase Custom Token via Cloud Function
            val exchanged = SocialCustomTokenClient.exchange(provider = provider, accessToken = accessToken)
            if (guestUser != null) {
                Log.i(TAG, "Deleting anonymous guest account before $provider Custom Token sign-in...")
                runCatching { guestUser.delete().awaitTask() }
            }
            val authResult = auth.signInWithCustomToken(exchanged.customToken).awaitTask()
            val fbUser = authResult.user
                ?: throw IllegalStateException("${provider.name} Firebase 인증 세션을 생성할 수 없습니다.")
            finalProfile = UserProfile(
                uid = fbUser.uid,
                provider = provider,
                displayName = exchanged.displayName ?: profile.displayName,
                email = exchanged.email ?: profile.email,
                photoUrl = exchanged.photoUrl ?: profile.photoUrl,
                isAnonymous = false
            )
        } else if (guestUser != null) {
            runCatching { guestUser.delete().awaitTask() }
        }

        sessionStore?.saveSession(finalProfile)
        _currentUser.value = finalProfile
        syncLocalOwnerAccount(finalProfile.uid)
        performPostSocialLoginCloudSync(profile = finalProfile, mergeWithLocal = shouldMergeLocal)
        finalProfile
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
        } catch (collision: FirebaseAuthUserCollisionException) {
            Log.i(TAG, "Google account already exists. Deleting anonymous guest account from Firebase...")
            runCatching { user.delete().awaitTask() }
            val authResult = auth.signInWithCredential(credential).awaitTask()
            val existingUser = authResult.user ?: throw collision
            sessionStore?.clearSession()
            sessionStore?.setLastProvider(AuthProvider.GOOGLE)
            existingUser
        }
        val domain = targetUser.toDomain()
        syncLocalOwnerAccount(targetUser.uid)
        performPostSocialLoginCloudSync(profile = domain, mergeWithLocal = true)
        domain
    }

    override suspend fun signOut(): Result<Unit> = runCatching {
        val current = _currentUser.value
        val ctx = appContext

        try {
            // 1. Best-effort backup of latest local data to Firestore BEFORE signing out (hard bounded timeout)
            if (ctx != null && current != null && !current.isAnonymous) {
                CloudSyncRepository.setProgressMessage("최신 6개월 기록과 설정을 클라우드에 백업하는 중...")
                val backupOutcome = withTimeoutOrNull(10_000L) {
                    runCatching { CloudSyncRepository.backupAllToCloud(ctx, current) }
                }
                if (backupOutcome == null) {
                    Log.w(TAG, "Pre-signout cloud backup timed out; continuing sign-out")
                } else {
                    backupOutcome.onFailure { Log.w(TAG, "Pre-signout cloud backup warning: ${it.message}") }
                }
            }

            // 2. Stop periodic cloud backup & sign out social/Firebase sessions (hard bounded timeout)
            CloudSyncRepository.setProgressMessage("소셜 로그인 세션을 정리하는 중...")
            if (ctx != null) {
                runCatching { DailyCloudBackupWorker.cancel(ctx) }
            }
            withTimeoutOrNull(4_000L) {
                when (current?.provider) {
                    AuthProvider.KAKAO -> runCatching { KakaoAuthClient.signOut() }
                    AuthProvider.NAVER -> runCatching { NaverAuthClient.signOut() }
                    else -> {}
                }
            }
            if (auth.currentUser != null) {
                runCatching { auth.signOut() }
            }

            // 3. Wipe local user data so the next account never mixes records (hard bounded timeout)
            CloudSyncRepository.setProgressMessage("이 기기의 로컬 데이터를 정리하는 중...")
            ctx?.let {
                withTimeoutOrNull(6_000L) {
                    LocalDataWiper.wipeForSignOut(it)
                }
            }
            sessionStore?.clearSession()
            _currentUser.value = null
        } finally {
            CloudSyncRepository.setProgressMessage(null)
        }
    }

    override suspend fun deleteAccount(googleIdToken: String?): Result<Unit> = runCatching {
        val current = _currentUser.value
        val ctx = appContext
        val user = auth.currentUser

        // 1. For Google accounts without a fresh token, proactively check recent-login window
        //    BEFORE deleting Firestore documents so we never leave a half-deleted account.
        if (user != null && !user.isAnonymous && current?.provider == AuthProvider.GOOGLE) {
            if (googleIdToken != null) {
                val credential = GoogleAuthProvider.getCredential(googleIdToken, null)
                user.reauthenticate(credential).awaitTask()
                Log.i(TAG, "Google re-authentication succeeded before delete")
            } else {
                val lastSignIn = user.metadata?.lastSignInTimestamp ?: 0L
                val ageMs = System.currentTimeMillis() - lastSignIn
                if (lastSignIn <= 0L || ageMs > RECENT_LOGIN_WINDOW_MS) {
                    throw FirebaseAuthRecentLoginRequiredException(
                        "ERROR_REQUIRES_RECENT_LOGIN",
                        "Google 보안 정책상 삭제 전 최근 1회 본인 확인이 필요합니다."
                    )
                }
            }
        }

        // 2. Delete ALL Cloud Firestore documents while `request.auth.uid` is still authenticated!
        if (user != null && !user.isAnonymous) {
            CloudSyncRepository.deleteAllCloudUserData(user.uid).getOrThrow()
        }

        // 3. Stop periodic cloud backup worker
        if (ctx != null) {
            runCatching { DailyCloudBackupWorker.cancel(ctx) }
        }

        // 4. Delete Firebase Auth account + unlink external social provider
        if (user != null) {
            try {
                user.delete().awaitTask()
            } catch (e: Exception) {
                Log.e(TAG, "user.delete failed: ${e.javaClass.simpleName} - ${e.message}", e)
                throw e
            }
        }
        withTimeoutOrNull(4_000L) {
            when (current?.provider) {
                AuthProvider.KAKAO -> runCatching { KakaoAuthClient.unlink() }
                AuthProvider.NAVER -> runCatching { NaverAuthClient.unlink() }
                else -> {}
            }
        }

        // 5. Permanently wipe ALL on-device data AFTER remote/auth deletion succeeds
        ctx?.let {
            withTimeoutOrNull(6_000L) {
                LocalDataWiper.wipeAllLocalUserData(it)
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
            uid.startsWith("kakao_") -> AuthProvider.KAKAO
            uid.startsWith("naver_") -> AuthProvider.NAVER
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
