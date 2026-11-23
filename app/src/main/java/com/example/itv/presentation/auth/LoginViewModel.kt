package com.example.itv.presentation.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.itv.application.auth.AuthService
import com.example.itv.composition.AuthComposition
import com.example.itv.domain.auth.UserProfile
import com.example.itv.infrastructure.auth.KakaoAuthClient
import com.example.itv.infrastructure.auth.NaverAuthClient
import com.example.itv.infrastructure.cloud.CloudSyncRepository
import com.example.itv.infrastructure.cloud.CloudSyncStatus
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    object Idle : AuthUiState
    data class Loading(
        val title: String = "계정 데이터 연동 중...",
        val message: String = "잠시만 기다려 주세요."
    ) : AuthUiState
    data class Success(val message: String) : AuthUiState
    data class Error(val message: String) : AuthUiState
    object RequiresRecentLogin : AuthUiState
}

class LoginViewModel(
    private val authService: AuthService = AuthComposition.authService
) : ViewModel() {

    val currentUser: StateFlow<UserProfile?> = authService.currentUser
    val cloudSyncStatus: StateFlow<CloudSyncStatus> = CloudSyncRepository.status

    fun getLastLoginProvider(): com.example.itv.domain.auth.AuthProvider? = authService.getLastLoginProvider()

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun refreshCloudSyncStatus(context: Context) {
        CloudSyncRepository.refreshStatus(context)
    }

    fun backupNowToCloud(context: Context) {
        viewModelScope.launch {
            val result = CloudSyncRepository.backupAllToCloud(context, currentUser.value)
            result.fold(
                onSuccess = {
                    _uiState.value = AuthUiState.Success("6개월 기록과 설정이 클라우드에 안전하게 백업되었습니다.")
                },
                onFailure = { err ->
                    _uiState.value = AuthUiState.Error(err.localizedMessage ?: "클라우드 백업에 실패했습니다.")
                }
            )
        }
    }

    fun signInAsGuest() {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading(
                title = "게스트 시작 준비 중...",
                message = "앱을 사용할 준비를 하고 있어요."
            )
            val result = authService.signInAsGuest()
            result.fold(
                onSuccess = { _uiState.value = AuthUiState.Success("게스트로 시작했습니다.") },
                onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "게스트 로그인 실패") }
            )
        }
    }

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading(
                title = "계정 데이터 연동 중...",
                message = "클라우드에서 6개월 기록과 캘린더를 불러오고 있어요."
            )
            val result = authService.signInWithGoogle(idToken)
            result.fold(
                onSuccess = { _uiState.value = AuthUiState.Success("구글 계정으로 로그인되었습니다.") },
                onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "구글 로그인 실패") }
            )
        }
    }

    fun signInWithKakao(context: Context) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading(
                title = "카카오 계정 확인 중...",
                message = "카카오 인증을 진행하고 있어요."
            )
            val kakaoResult = KakaoAuthClient.signInWithToken(context)
            kakaoResult.fold(
                onSuccess = { payload ->
                    _uiState.value = AuthUiState.Loading(
                        title = "계정 데이터 연동 중...",
                        message = "클라우드에서 6개월 기록과 캘린더를 불러오고 있어요."
                    )
                    val result = authService.signInWithKakao(
                        profile = payload.profile,
                        accessToken = payload.accessToken,
                        mergeLocalGuestData = false
                    )
                    result.fold(
                        onSuccess = { _uiState.value = AuthUiState.Success("카카오 계정으로 로그인되었습니다.") },
                        onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "카카오 로그인 실패") }
                    )
                },
                onFailure = { error ->
                    if (error is ClientError && error.reason == ClientErrorCause.Cancelled) {
                        _uiState.value = AuthUiState.Idle
                    } else {
                        _uiState.value = AuthUiState.Error(error.localizedMessage ?: "카카오 로그인 실패")
                    }
                }
            )
        }
    }

    fun signInWithNaver(context: Context) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading(
                title = "네이버 계정 확인 중...",
                message = "네이버 인증을 진행하고 있어요."
            )
            val naverResult = NaverAuthClient.signInWithToken(context)
            naverResult.fold(
                onSuccess = { payload ->
                    _uiState.value = AuthUiState.Loading(
                        title = "계정 데이터 연동 중...",
                        message = "클라우드에서 6개월 기록과 캘린더를 불러오고 있어요."
                    )
                    val result = authService.signInWithNaver(
                        profile = payload.profile,
                        accessToken = payload.accessToken,
                        mergeLocalGuestData = false
                    )
                    result.fold(
                        onSuccess = { _uiState.value = AuthUiState.Success("네이버 계정으로 로그인되었습니다.") },
                        onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "네이버 로그인 실패") }
                    )
                },
                onFailure = { error ->
                    val msg = error.localizedMessage ?: "네이버 로그인 실패"
                    if (msg.contains("cancel", ignoreCase = true) || msg.contains("취소", ignoreCase = true)) {
                        _uiState.value = AuthUiState.Idle
                    } else {
                        _uiState.value = AuthUiState.Error(msg)
                    }
                }
            )
        }
    }

    fun linkGuestWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading(
                title = "계정 연동 및 백업 중...",
                message = "현재 기기 기록과 클라우드 데이터를 안전하게 통합하고 있어요."
            )
            val result = authService.linkGuestWithGoogle(idToken)
            result.fold(
                onSuccess = { profile ->
                    val accountDesc = profile.email?.let { " ($it)" } ?: ""
                    _uiState.value = AuthUiState.Success("구글 본계정$accountDesc 으로 연동 및 클라우드 백업이 완료되었습니다!")
                },
                onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "계정 연동 실패") }
            )
        }
    }

    fun linkGuestWithKakao(context: Context) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading(
                title = "카카오 계정 확인 중...",
                message = "카카오 인증을 진행하고 있어요."
            )
            val kakaoResult = KakaoAuthClient.signInWithToken(context)
            kakaoResult.fold(
                onSuccess = { payload ->
                    _uiState.value = AuthUiState.Loading(
                        title = "계정 연동 및 백업 중...",
                        message = "현재 기기 기록과 클라우드 데이터를 안전하게 통합하고 있어요."
                    )
                    val result = authService.signInWithKakao(
                        profile = payload.profile,
                        accessToken = payload.accessToken,
                        mergeLocalGuestData = true
                    )
                    result.fold(
                        onSuccess = { linked ->
                            val desc = linked.email?.let { " ($it)" } ?: ""
                            _uiState.value = AuthUiState.Success("카카오 계정$desc 으로 연동 및 클라우드 백업이 완료되었습니다!")
                        },
                        onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "카카오 연동 실패") }
                    )
                },
                onFailure = { error ->
                    if (error is ClientError && error.reason == ClientErrorCause.Cancelled) {
                        _uiState.value = AuthUiState.Idle
                    } else {
                        _uiState.value = AuthUiState.Error(error.localizedMessage ?: "카카오 연동 실패")
                    }
                }
            )
        }
    }

    fun linkGuestWithNaver(context: Context) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading(
                title = "네이버 계정 확인 중...",
                message = "네이버 인증을 진행하고 있어요."
            )
            val naverResult = NaverAuthClient.signInWithToken(context)
            naverResult.fold(
                onSuccess = { payload ->
                    _uiState.value = AuthUiState.Loading(
                        title = "계정 연동 및 백업 중...",
                        message = "현재 기기 기록과 클라우드 데이터를 안전하게 통합하고 있어요."
                    )
                    val result = authService.signInWithNaver(
                        profile = payload.profile,
                        accessToken = payload.accessToken,
                        mergeLocalGuestData = true
                    )
                    result.fold(
                        onSuccess = { linked ->
                            val desc = linked.email?.let { " ($it)" } ?: ""
                            _uiState.value = AuthUiState.Success("네이버 계정$desc 으로 연동 및 클라우드 백업이 완료되었습니다!")
                        },
                        onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "네이버 연동 실패") }
                    )
                },
                onFailure = { error ->
                    val msg = error.localizedMessage ?: "네이버 연동 실패"
                    if (msg.contains("cancel", ignoreCase = true) || msg.contains("취소", ignoreCase = true)) {
                        _uiState.value = AuthUiState.Idle
                    } else {
                        _uiState.value = AuthUiState.Error(msg)
                    }
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading(
                title = "로그아웃 및 백업 중...",
                message = "최신 기록을 클라우드에 백업한 뒤 기기를 정리하고 있어요."
            )
            val result = authService.signOut()
            result.fold(
                onSuccess = { _uiState.value = AuthUiState.Success("로그아웃되었습니다.") },
                onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "로그아웃 실패") }
            )
        }
    }

    fun deleteAccount(googleIdToken: String? = null) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading(
                title = "계정 영구 삭제 중...",
                message = "클라우드와 기기의 모든 데이터를 안전하게 삭제하고 있어요."
            )
            val result = authService.deleteAccount(googleIdToken)
            result.fold(
                onSuccess = { _uiState.value = AuthUiState.Success("계정 및 클라우드/로컬 데이터가 영구 삭제되었습니다.") },
                onFailure = { error ->
                    android.util.Log.e("ITV_AUTH", "LoginViewModel deleteAccount error", error)
                    if (error is com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException ||
                        error.message?.contains("recent", ignoreCase = true) == true
                    ) {
                        _uiState.value = AuthUiState.RequiresRecentLogin
                    } else {
                        _uiState.value = AuthUiState.Error(error.localizedMessage ?: "계정 삭제 실패")
                    }
                }
            )
        }
    }

    fun resetState() {
        _uiState.value = AuthUiState.Idle
    }
}
