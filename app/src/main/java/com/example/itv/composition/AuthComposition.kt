package com.example.itv.composition

import android.content.Context
import com.example.itv.application.auth.AuthService
import com.example.itv.domain.auth.AuthRepository
import com.example.itv.infrastructure.auth.FirebaseAuthRepository
import com.example.itv.infrastructure.auth.GoogleAuthClient

object AuthComposition {
    const val GOOGLE_WEB_CLIENT_ID = "607435924516-995hqj3d71bnb2n1i3evove7hi39lv00.apps.googleusercontent.com"

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    val authRepository: AuthRepository by lazy {
        val sessionStore = appContext?.let { com.example.itv.infrastructure.auth.LocalSessionStore(it) }
        FirebaseAuthRepository(sessionStore = sessionStore)
    }

    val authService: AuthService by lazy {
        AuthService(authRepository)
    }

    fun googleAuthClient(context: Context, webClientId: String = GOOGLE_WEB_CLIENT_ID): GoogleAuthClient {
        return GoogleAuthClient(context, webClientId)
    }
}
