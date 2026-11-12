package com.example.itv.application.auth

import com.example.itv.domain.auth.AuthProvider
import com.example.itv.domain.auth.AuthRepository
import com.example.itv.domain.auth.UserProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * 4계층 아키텍처의 응용 계층(Application Layer) 서비스.
 * 사용자 로그인, 게스트 모드, 계정 통합 등의 유스케이스 흐름을 관장합니다.
 */
class AuthService(
    private val authRepository: AuthRepository
) {
    val currentUser: StateFlow<UserProfile?> = authRepository.currentUser

    /**
     * 가장 최근에 로그인했던 제공사 (Google, Kakao, Naver)
     */
    fun getLastLoginProvider(): AuthProvider? {
        return authRepository.getLastLoginProvider()
    }

    /**
     * 이메일 입력 없는 즉시 게스트(익명) 시작
     */
    suspend fun signInAsGuest(): Result<UserProfile> {
        return authRepository.signInAsGuest()
    }

    /**
     * 구글 소셜 계정으로 로그인
     */
    suspend fun signInWithGoogle(idToken: String): Result<UserProfile> {
        return authRepository.signInWithGoogle(idToken)
    }

    /**
     * 카카오 소셜 계정으로 로그인
     */
    suspend fun signInWithKakao(profile: UserProfile): Result<UserProfile> {
        return authRepository.signInWithKakao(profile)
    }

    /**
     * 네이버 소셜 계정으로 로그인
     */
    suspend fun signInWithNaver(profile: UserProfile): Result<UserProfile> {
        return authRepository.signInWithNaver(profile)
    }

    /**
     * 게스트로 사용하던 중 기존 데이터(시청 기록 등)를 유지한 채 구글 계정으로 연동(통합)
     */
    suspend fun linkGuestWithGoogle(idToken: String): Result<UserProfile> {
        return authRepository.linkGuestWithGoogle(idToken)
    }

    /**
     * 로그아웃
     */
    suspend fun signOut(): Result<Unit> {
        return authRepository.signOut()
    }

    /**
     * 계정 삭제 (회원 탈퇴)
     * Google Play 스토어 계정 삭제 정책에 따라 모든 계정 정보와 연동 데이터를 영구 삭제합니다.
     */
    suspend fun deleteAccount(googleIdToken: String? = null): Result<Unit> {
        return authRepository.deleteAccount(googleIdToken)
    }
}
