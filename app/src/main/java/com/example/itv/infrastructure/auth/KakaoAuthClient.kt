package com.example.itv.infrastructure.auth

import android.content.Context
import com.example.itv.domain.auth.AuthProvider
import com.example.itv.domain.auth.UserProfile
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import com.kakao.sdk.user.UserApiClient
import com.kakao.sdk.user.model.User
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 카카오 공식 SDK(v2-user)를 래핑하여 카카오톡 간편로그인 및 카카오계정 로그인을 처리하는 클라이언트.
 */
object KakaoAuthClient {

    suspend fun signIn(context: Context): Result<UserProfile> = runCatching {
        // 1. 카카오 로그인 토큰 획득
        acquireToken(context)

        // 2. 카카오 사용자 프로필 획득
        val user = fetchKakaoUser()

        val uid = "kakao_${user.id}"
        val nickname = user.kakaoAccount?.profile?.nickname
        val email = user.kakaoAccount?.email
        val photoUrl = user.kakaoAccount?.profile?.profileImageUrl
            ?: user.kakaoAccount?.profile?.thumbnailImageUrl

        UserProfile(
            uid = uid,
            provider = AuthProvider.KAKAO,
            displayName = nickname,
            email = email,
            photoUrl = photoUrl,
            isAnonymous = false
        )
    }

    private suspend fun acquireToken(context: Context): OAuthToken = suspendCancellableCoroutine { cont ->
        val callback: (OAuthToken?, Throwable?) -> Unit = { token, error ->
            if (error != null) {
                if (cont.isActive) cont.resumeWithException(error)
            } else if (token != null) {
                if (cont.isActive) cont.resume(token)
            } else {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("카카오 토큰을 가져올 수 없습니다."))
            }
        }

        if (UserApiClient.instance.isKakaoTalkLoginAvailable(context)) {
            UserApiClient.instance.loginWithKakaoTalk(context) { token, error ->
                if (error != null) {
                    if (error is ClientError && error.reason == ClientErrorCause.Cancelled) {
                        if (cont.isActive) cont.resumeWithException(error)
                        return@loginWithKakaoTalk
                    }
                    UserApiClient.instance.loginWithKakaoAccount(context, callback = callback)
                } else if (token != null) {
                    if (cont.isActive) cont.resume(token)
                }
            }
        } else {
            UserApiClient.instance.loginWithKakaoAccount(context, callback = callback)
        }
    }

    private suspend fun fetchKakaoUser(): User = suspendCancellableCoroutine { cont ->
        UserApiClient.instance.me { user, error ->
            if (error != null) {
                if (cont.isActive) cont.resumeWithException(error)
            } else if (user != null) {
                if (cont.isActive) cont.resume(user)
            } else {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("카카오 사용자 정보를 가져올 수 없습니다."))
            }
        }
    }

    suspend fun signOut(): Result<Unit> = suspendCancellableCoroutine { cont ->
        UserApiClient.instance.logout { error ->
            if (error != null) {
                if (cont.isActive) cont.resume(Result.failure(error))
            } else {
                if (cont.isActive) cont.resume(Result.success(Unit))
            }
        }
    }

    suspend fun unlink(): Result<Unit> = suspendCancellableCoroutine { cont ->
        UserApiClient.instance.unlink { error ->
            if (error != null) {
                if (cont.isActive) cont.resume(Result.failure(error))
            } else {
                if (cont.isActive) cont.resume(Result.success(Unit))
            }
        }
    }
}
