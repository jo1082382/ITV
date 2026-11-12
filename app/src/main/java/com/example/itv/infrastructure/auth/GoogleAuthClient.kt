package com.example.itv.infrastructure.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Android 최신 표준인 Credential Manager를 사용하여 구글 ID 토큰을 안전하게 획득하는 인프라 헬퍼.
 */
class GoogleAuthClient(
    private val context: Context,
    private val webClientId: String
) {
    private val credentialManager = CredentialManager.create(context)

    suspend fun getGoogleIdToken(): Result<String> = runCatching {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val response: GetCredentialResponse = credentialManager.getCredential(
            request = request,
            context = context
        )

        val credential = response.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            googleIdTokenCredential.idToken
        } else {
            throw IllegalStateException("지원하지 않는 인증 응답 형식입니다: ${credential.type}")
        }
    }
}
