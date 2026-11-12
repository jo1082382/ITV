package com.example.itv.infrastructure.auth

import android.content.Context
import android.util.AtomicFile
import com.example.itv.domain.auth.AuthProvider
import com.example.itv.domain.auth.UserProfile
import org.json.JSONObject
import java.io.File

/**
 * 소셜 로그인(카카오, 네이버 등) 및 게스트 세션 정보를 로컬에 안전하게 영속화하는 스토어.
 * 앱 재실행 시 세션을 복원하고 로그아웃/탈퇴 시 안전하게 초기화합니다.
 */
class LocalSessionStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "itv_user_session.json"))
    private val prefs = context.applicationContext.getSharedPreferences("itv_auth_prefs", Context.MODE_PRIVATE)

    fun getLastProvider(): AuthProvider? {
        val name = prefs.getString("last_provider", null) ?: return null
        return runCatching { AuthProvider.valueOf(name) }.getOrNull()
    }

    fun setLastProvider(provider: AuthProvider) {
        if (provider != AuthProvider.GUEST && provider != AuthProvider.TEST) {
            prefs.edit().putString("last_provider", provider.name).apply()
        }
    }

    fun clearLastProvider() {
        prefs.edit().remove("last_provider").apply()
    }

    fun loadSession(): UserProfile? = synchronized(this) {
        try {
            if (!file.baseFile.exists()) return null
            val jsonStr = file.openRead().use { String(it.readBytes(), Charsets.UTF_8) }
            val obj = JSONObject(jsonStr)
            val uid = obj.optString("uid")
            if (uid.isEmpty()) return null
            val providerStr = obj.optString("provider", AuthProvider.GUEST.name)
            val provider = runCatching { AuthProvider.valueOf(providerStr) }.getOrDefault(AuthProvider.GUEST)
            UserProfile(
                uid = uid,
                provider = provider,
                displayName = obj.optString("displayName").takeIf { it.isNotEmpty() },
                email = obj.optString("email").takeIf { it.isNotEmpty() },
                photoUrl = obj.optString("photoUrl").takeIf { it.isNotEmpty() },
                isAnonymous = obj.optBoolean("isAnonymous", false)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun saveSession(profile: UserProfile): Unit = synchronized(this) {
        setLastProvider(profile.provider)
        val obj = JSONObject().apply {
            put("uid", profile.uid)
            put("provider", profile.provider.name)
            put("displayName", profile.displayName ?: "")
            put("email", profile.email ?: "")
            put("photoUrl", profile.photoUrl ?: "")
            put("isAnonymous", profile.isAnonymous)
        }
        val stream = file.startWrite()
        try {
            stream.write(obj.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            runCatching { file.failWrite(stream) }
        }
    }

    fun clearSession(): Unit = synchronized(this) {
        try {
            file.delete()
        } catch (_: Exception) {}
    }
}
