package com.example.itv.infrastructure.coaching

import android.content.Context
import org.json.JSONObject
import java.io.File

internal object CoachingConsentStore {
    fun granted(c: Context): Boolean = try {
        JSONObject(File(c.noBackupFilesDir, "consent_guest_v1.json").readText()).optBoolean("consent", false)
    } catch (_: Exception) { false }
}
