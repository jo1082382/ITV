package com.example.itv.domain.auth

/**
 * 4계층 아키텍처의 도메인 계층에 속하는 순수 사용자 프로필 모델.
 * 플랫폼/프레임워크(Firebase 등)에 종속되지 않습니다.
 */
data class UserProfile(
    val uid: String,
    val provider: AuthProvider,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?,
    val isAnonymous: Boolean
)
