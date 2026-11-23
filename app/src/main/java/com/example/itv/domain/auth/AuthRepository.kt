package com.example.itv.domain.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * 도메인 계층의 인증 포트(인터페이스).
 * 구현체는 infrastructure/auth 에서 Firebase나 기타 인증 SDK를 사용해 작성됩니다.
 */
interface AuthRepository {
    /** 현재 로그인된 사용자 정보 (미로그인 시 null) */
    val currentUser: StateFlow<UserProfile?>

    /**
     * 기기에서 가장 최근에 로그인했던 소셜 제공사 (Google, Kakao, Naver 등).
     * 로그아웃 후에도 유지되어 스마트 단일화 로그인 UI를 제공하는 데 사용됩니다.
     */
    fun getLastLoginProvider(): AuthProvider? = null

    /**
     * 게스트(익명) 원클릭 로그인
     * 이메일 입력이나 복잡한 절차 없이 즉시 앱의 고유 UID를 발급받습니다.
     */
    suspend fun signInAsGuest(): Result<UserProfile>

    /**
     * 구글 ID 토큰을 이용한 로그인
     */
    suspend fun signInWithGoogle(idToken: String): Result<UserProfile>

    /**
     * 카카오 소셜 계정으로 로그인 (accessToken이 있으면 Firebase Custom Token으로 통합 인증 및 클라우드 동기화)
     */
    suspend fun signInWithKakao(
        profile: UserProfile,
        accessToken: String? = null,
        mergeLocalGuestData: Boolean = false
    ): Result<UserProfile>

    /**
     * 네이버 소셜 계정으로 로그인 (accessToken이 있으면 Firebase Custom Token으로 통합 인증 및 클라우드 동기화)
     */
    suspend fun signInWithNaver(
        profile: UserProfile,
        accessToken: String? = null,
        mergeLocalGuestData: Boolean = false
    ): Result<UserProfile>

    /**
     * 기존 게스트 계정을 구글 계정으로 통합(Account Linking)
     * 게스트 시절에 쌓인 시청 통계나 설정 데이터를 잃지 않고 소셜 계정으로 연결합니다.
     */
    suspend fun linkGuestWithGoogle(idToken: String): Result<UserProfile>

    /**
     * 로그아웃
     */
    suspend fun signOut(): Result<Unit>

    /**
     * 계정 삭제 (회원 탈퇴)
     * Google Play 스토어 계정 삭제 정책에 따라 Firebase 계정과 모든 연동 정보를 영구 파기합니다.
     * Google 계정인 경우 민감 작업 재인증을 위해 [googleIdToken]을 전달받아 재인증 후 삭제합니다.
     */
    suspend fun deleteAccount(googleIdToken: String? = null): Result<Unit>
}
