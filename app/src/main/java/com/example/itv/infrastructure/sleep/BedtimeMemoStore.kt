package com.example.itv.infrastructure.sleep

import android.content.Context
import android.content.SharedPreferences

data class BedtimeMemo(
    val content: String,
    val savedAt: Long,
    val isPendingBriefing: Boolean
)

class BedtimeMemoStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("itv_bedtime_memo", Context.MODE_PRIVATE)

    fun saveMemo(text: String) {
        prefs.edit()
            .putString(KEY_MEMO_CONTENT, text)
            .putLong(KEY_MEMO_TIME, System.currentTimeMillis())
            .putBoolean(KEY_PENDING_BRIEFING, true)
            .apply()
    }

    fun getPendingMemo(): BedtimeMemo? {
        val pending = prefs.getBoolean(KEY_PENDING_BRIEFING, false)
        if (!pending) return null
        val content = prefs.getString(KEY_MEMO_CONTENT, null) ?: return null
        val time = prefs.getLong(KEY_MEMO_TIME, 0L)
        return BedtimeMemo(content, time, true)
    }

    fun dismissBriefing() {
        prefs.edit()
            .putBoolean(KEY_PENDING_BRIEFING, false)
            .apply()
    }

    fun restoreMemo(content: String?, savedAt: Long?, isPending: Boolean) {
        val editor = prefs.edit().clear()
        if (!content.isNullOrBlank() && savedAt != null && savedAt > 0L) {
            editor
                .putString(KEY_MEMO_CONTENT, content)
                .putLong(KEY_MEMO_TIME, savedAt)
                .putBoolean(KEY_PENDING_BRIEFING, isPending)
        }
        editor.commit()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val KEY_MEMO_CONTENT = "memo_content"
        private const val KEY_MEMO_TIME = "memo_time"
        private const val KEY_PENDING_BRIEFING = "pending_briefing"

        @Volatile
        private var instance: BedtimeMemoStore? = null

        fun getInstance(context: Context): BedtimeMemoStore {
            return instance ?: synchronized(this) {
                instance ?: BedtimeMemoStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
