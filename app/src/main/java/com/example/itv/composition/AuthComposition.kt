package com.example.itv.composition

import android.app.Activity
import com.example.itv.application.auth.AuthService
import com.example.itv.data.local.ItvDatabase
import com.example.itv.infrastructure.auth.AndroidAuthRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope

// Firebase 콘솔에서 확인한 Web Client ID. 비밀 값이 아니라 앱에 포함되어도 괜찮다.
private const val GOOGLE_WEB_CLIENT_ID =
    "384269930250-dmqhcnrueflltq18f63eb8ckj5q4rdbb.apps.googleusercontent.com"

/**
 * 로그인 서비스를 조립한다.
 *
 * 구글 로그인 창을 띄우려면 Activity context가 필요하므로 Activity를 받는다.
 * 그래서 이 서비스는 **그 Activity 안에서만** 쓰고, ViewModel·싱글톤·companion 등
 * Activity보다 오래 사는 곳에 보관하면 안 된다. (Activity 메모리 누수)
 * scope에는 반드시 그 Activity의 lifecycleScope를 넘긴다.
 *
 * 사용 예 (MainActivity):
 *   private val auth by lazy { authService(this, lifecycleScope) }
 */
internal fun authService(activity: Activity, scope: CoroutineScope): AuthService {
    val repository = AndroidAuthRepository(
        context = activity,
        firebaseAuth = FirebaseAuth.getInstance(),
        webClientId = GOOGLE_WEB_CLIENT_ID,
        db = ItvDatabase.get(activity.applicationContext)
    )
    return AuthService(repository, scope)
}