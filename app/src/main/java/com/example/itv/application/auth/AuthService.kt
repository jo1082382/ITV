package com.example.itv.application.auth

import com.example.itv.domain.auth.AuthRepository
import com.example.itv.domain.auth.AuthState
import com.example.itv.domain.auth.AuthUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class AuthService(
    private val repository: AuthRepository,
    scope: CoroutineScope
) {
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    /** 앱 시작 시 저장된 로그인(구글/게스트)을 복원한다. 실패하면 로그아웃 상태로 둔다. */
    suspend fun refresh() {
        val user = try {
            repository.currentUser()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        mutableState.value = if (user != null) AuthState.SignedIn(user) else AuthState.SignedOut
    }

    suspend fun signInWithGoogle(): Result<AuthUser> = signIn { repository.signInWithGoogle() }

    suspend fun signInAsGuest(): Result<AuthUser> = signIn { repository.signInAsGuest() }

    suspend fun signOut() {
        try {
            repository.signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 로그아웃 실패해도 화면은 로그아웃 상태로 전환한다.
        }
        mutableState.value = AuthState.SignedOut
    }

    // 취소(CancellationException)는 실패로 바꾸지 않고 그대로 다시 던진다.
    private suspend fun signIn(block: suspend () -> AuthUser): Result<AuthUser> = try {
        val user = block()
        mutableState.value = AuthState.SignedIn(user)
        Result.success(user)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}