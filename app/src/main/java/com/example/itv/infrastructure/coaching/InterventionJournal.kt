package com.example.itv.infrastructure.coaching

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small bounded local journal, SharedPreferences.apply persists asynchronously. No content stored. */
internal object InterventionJournal {
    private fun prefs(c: Context) = c.getSharedPreferences("itv_intervention_journal_v1", Context.MODE_PRIVATE)
    fun add(c: Context, id: String, event: String) {
        val p = prefs(c)
        val old = try { JSONArray(p.getString("rows", "[]")) } catch (_: Exception) { JSONArray() }
        val rows = JSONArray().put(JSONObject().put("at", System.currentTimeMillis()).put("id", id).put("event", event))
        for (i in 0 until minOf(old.length(), 199)) rows.put(old.getJSONObject(i))
        p.edit().putString("rows", rows.toString()).apply()
    }
    fun rows(c: Context): List<String> {
        val a = try { JSONArray(prefs(c).getString("rows", "[]")) } catch (_: Exception) { JSONArray() }
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)
        return (0 until a.length()).map { i ->
            val row = a.getJSONObject(i)
            "${format.format(Date(row.optLong("at")))} · ${row.optString("event")}\n제안 ${row.optString("id")}"
        }
    }
    fun clear(c: Context) { prefs(c).edit().remove("rows").apply() }
}
