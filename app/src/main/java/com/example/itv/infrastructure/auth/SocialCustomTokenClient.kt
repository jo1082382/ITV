package com.example.itv.infrastructure.auth

import com.example.itv.BuildConfig
import com.example.itv.domain.auth.AuthProvider
import com.example.itv.domain.auth.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class SocialAuthPayload(
    val profile: UserProfile,
    val accessToken: String
)

data class SocialCustomTokenResponse(
    val customToken: String,
    val uid: String,
    val provider: AuthProvider,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?
)

/**
 * Exchanges a verified Kakao or Naver OAuth access token for a Firebase Custom Token
 * via the project's `exchangeSocialToken` Cloud Function (asia-northeast3).
 * This allows Google, Kakao, and Naver users to share Firebase Auth (`request.auth.uid`)
 * and identical per-user Firestore ownership security rules.
 */
object SocialCustomTokenClient {

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 15_000

    suspend fun exchange(
        provider: AuthProvider,
        accessToken: String,
        endpointUrl: String = BuildConfig.SOCIAL_TOKEN_EXCHANGE_URL
    ): SocialCustomTokenResponse = withContext(Dispatchers.IO) {
        require(provider == AuthProvider.KAKAO || provider == AuthProvider.NAVER) {
            "Custom token exchange is only supported for KAKAO and NAVER."
        }
        require(accessToken.isNotBlank()) { "소셜 액세스 토큰이 비어 있습니다." }

        val url = URL(endpointUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val payload = JSONObject()
                .put("provider", provider.name)
                .put("accessToken", accessToken)
                .toString()

            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val errMsg = runCatching {
                    JSONObject(body).optString("message").ifBlank { JSONObject(body).optString("error") }
                }.getOrNull().orEmpty()
                throw IOException("Firebase 인증 토큰 발급 실패 (HTTP $code): ${errMsg.ifBlank { "서버 응답 오류" }}")
            }

            parseResponse(body, provider)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseResponse(body: String, fallbackProvider: AuthProvider): SocialCustomTokenResponse {
        val json = JSONObject(body)
        val customToken = json.optString("customToken").trim()
        val uid = json.optString("uid").trim()
        if (customToken.isEmpty() || uid.isEmpty()) {
            throw IOException("Firebase 인증 토큰 응답 형식이 올바르지 않습니다.")
        }
        val provider = runCatching {
            AuthProvider.valueOf(json.optString("provider", fallbackProvider.name))
        }.getOrDefault(fallbackProvider)
        return SocialCustomTokenResponse(
            customToken = customToken,
            uid = uid,
            provider = provider,
            displayName = json.optString("displayName").takeIf { it.isNotBlank() && it != "null" },
            email = json.optString("email").takeIf { it.isNotBlank() && it != "null" },
            photoUrl = json.optString("photoUrl").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
