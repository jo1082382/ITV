package com.example.itv.infrastructure.auth

import android.content.Context
import com.example.itv.domain.auth.AuthProvider
import com.example.itv.domain.auth.UserProfile
import com.navercorp.nid.NidOAuth
import com.navercorp.nid.oauth.util.NidOAuthCallback
import com.navercorp.nid.profile.domain.vo.NidProfile
import com.navercorp.nid.profile.util.NidProfileCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 네이버 공식 SDK(oauth:5.12.0) 최신 NidOAuth API를 래핑하여
 * 네이버 앱/웹 로그인 및 프로필 조회를 처리하는 클라이언트.
 */
object NaverAuthClient {

    suspend fun signIn(context: Context): Result<UserProfile> =
        signInWithToken(context).map { it.profile }

    suspend fun signInWithToken(context: Context): Result<SocialAuthPayload> = runCatching {
        // 1. 네이버 로그인 토큰 획득
        authenticate(context)
        val accessToken = NidOAuth.getAccessToken()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("네이버 액세스 토큰을 가져올 수 없습니다.")

        // 2. 네이버 사용자 프로필 획득
        val nidProfile = fetchProfile()
        val detail = nidProfile.profile

        val uid = "naver_${detail.id}"
        val displayName = detail.nickname.ifEmpty { detail.name.ifEmpty { "네이버 사용자" } }
        val email = detail.email.ifEmpty { null }
        val photoUrl = detail.profileImage.ifEmpty { null }

        SocialAuthPayload(
            profile = UserProfile(
                uid = uid,
                provider = AuthProvider.NAVER,
                displayName = displayName,
                email = email,
                photoUrl = photoUrl,
                isAnonymous = false
            ),
            accessToken = accessToken
        )
    }

    private suspend fun authenticate(context: Context): Unit = suspendCancellableCoroutine { cont ->
        NidOAuth.requestLogin(context, object : NidOAuthCallback {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onFailure(errorCode: String, errorDesc: String) {
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("네이버 로그인 실패 ($errorCode): $errorDesc"))
                }
            }
        })
    }

    private suspend fun fetchProfile(): NidProfile = suspendCancellableCoroutine { cont ->
        NidOAuth.getUserProfile(object : NidProfileCallback<NidProfile> {
            override fun onSuccess(result: NidProfile) {
                if (cont.isActive) cont.resume(result)
            }

            override fun onFailure(errorCode: String, errorDesc: String) {
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("네이버 프로필 조회 실패 ($errorCode): $errorDesc"))
                }
            }
        })
    }

    fun getStoredAccessToken(): String? = runCatching {
        NidOAuth.getAccessToken()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    suspend fun signOut(): Result<Unit> = suspendCancellableCoroutine { cont ->
        NidOAuth.logout(object : NidOAuthCallback {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(Result.success(Unit))
            }

            override fun onFailure(errorCode: String, errorDesc: String) {
                if (cont.isActive) cont.resume(Result.success(Unit))
            }
        })
    }

    suspend fun unlink(): Result<Unit> = suspendCancellableCoroutine { cont ->
        NidOAuth.disconnect(object : NidOAuthCallback {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(Result.success(Unit))
            }

            override fun onFailure(errorCode: String, errorDesc: String) {
                if (cont.isActive) cont.resume(Result.success(Unit))
            }
        })
    }
}
