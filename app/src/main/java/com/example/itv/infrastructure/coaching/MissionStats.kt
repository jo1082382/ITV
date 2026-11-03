package com.example.itv.infrastructure.coaching

import android.content.Context

internal data class MissionCounts(val assigned: Long, val success: Long, val failed: Long, val interrupted: Long)
internal object MissionStats {
    private fun prefs(c: Context) = c.getSharedPreferences("itv_mission_counts_v1", Context.MODE_PRIVATE)
    fun counts(c: Context): MissionCounts {
        val p = prefs(c)
        return MissionCounts(p.getLong("assigned", 0), p.getLong("success", 0),
            p.getLong("failed", 0), p.getLong("interrupted", 0))
    }
    fun begin(c: Context, id: String) {
        val p = prefs(c)
        if (p.getString("active", null) == id) return
        val old = p.getString("active", null)
        val e = p.edit().putLong("assigned", p.getLong("assigned", 0) + 1).putString("active", id)
        if (old != null) e.putLong("interrupted", p.getLong("interrupted", 0) + 1)
        e.apply()
    }
    fun finish(c: Context, id: String, outcome: String) {
        val p = prefs(c)
        if (p.getString("active", null) != id) return
        require(outcome in setOf("success", "failed", "interrupted"))
        p.edit().putLong(outcome, p.getLong(outcome, 0) + 1).remove("active").apply()
    }
    fun recoverInterrupted(c: Context) {
        prefs(c).getString("active", null)?.let { id ->
            finish(c, id, "interrupted")
            InterventionJournal.add(c, id, "이전 미션 관측 중단 · 서비스 재시작")
        }
    }
}
