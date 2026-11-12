package com.example.itv.presentation.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.itv.application.auth.AuthService
import com.example.itv.composition.AuthComposition
import com.example.itv.domain.auth.UserProfile
import com.example.itv.infrastructure.auth.KakaoAuthClient
import com.example.itv.infrastructure.auth.NaverAuthClient
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    object Idle : AuthUiState
    object Loading : AuthUiState
    data class Success(val message: String) : AuthUiState
    data class Error(val message: String) : AuthUiState
    object RequiresRecentLogin : AuthUiState
}

class LoginViewModel(
    private val authService: AuthService = AuthComposition.authService
) : ViewModel() {

    val currentUser: StateFlow<UserProfile?> = authService.currentUser

    fun getLastLoginProvider(): com.example.itv.domain.auth.AuthProvider? = authService.getLastLoginProvider()

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun signInAsGuest() {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = authService.signInAsGuest()
            result.fold(
                onSuccess = { _uiState.value = AuthUiState.Success("게스트로 시작했습니다.") },
                onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "게스트 로그인 실패") }
            )
        }
    }

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = authService.signInWithGoogle(idToken)
            result.fold(
                onSuccess = { _uiState.value = AuthUiState.Success("구글 계정으로 로그인되었습니다.") },
                onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "구글 로그인 실패") }
            )
        }
    }

    fun signInWithKakao(context: Context) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val kakaoResult = KakaoAuthClient.signIn(context)
            kakaoResult.fold(
                onSuccess = { profile ->
                    val result = authService.signInWithKakao(profile)
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
            _uiState.value = AuthUiState.Loading
            val naverResult = NaverAuthClient.signIn(context)
            naverResult.fold(
                onSuccess = { profile ->
                    val result = authService.signInWithNaver(profile)
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
            _uiState.value = AuthUiState.Loading
            val result = authService.linkGuestWithGoogle(idToken)
            result.fold(
                onSuccess = { profile ->
                    val accountDesc = profile.email?.let { " ($it)" } ?: ""
                    _uiState.value = AuthUiState.Success("구글 본계정$accountDesc 으로 정상 연결되었습니다!")
                },
                onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "계정 연동 실패") }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = authService.signOut()
            result.fold(
                onSuccess = { _uiState.value = AuthUiState.Success("로그아웃되었습니다.") },
                onFailure = { _uiState.value = AuthUiState.Error(it.localizedMessage ?: "로그아웃 실패") }
            )
        }
    }

    fun deleteAccount(googleIdToken: String? = null) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = authService.deleteAccount(googleIdToken)
            result.fold(
                onSuccess = { _uiState.value = AuthUiState.Success("계정 및 데이터가 성공적으로 영구 삭제되었습니다.") },
                onFailure = { error ->
                    android.util.Log.e("ITV_AUTH", "LoginViewModel deleteAccount error", error)
                    if (error is com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException ||
                        error.message?.contains("recent", ignoreCase = true) == true) {
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
