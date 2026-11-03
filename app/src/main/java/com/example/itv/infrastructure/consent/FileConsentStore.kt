package com.example.itv.infrastructure.consent

import android.content.Context
import android.util.AtomicFile
import com.example.itv.domain.consent.ConsentState
import com.example.itv.domain.consent.ConsentStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** All instances share a process lock. Always reload before mutation; no stale JSON cache. */
internal class FileConsentStore(context: Context) : ConsentStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "consent_guest_v1.json"))
    companion object { private val lock = Any() }
    private fun load(): JSONObject {
        // AtomicFile.openRead also recovers a legacy backup after an interrupted write.
        return try {
            JSONObject(file.openRead().use { String(it.readBytes(), Charsets.UTF_8) })
        } catch (e: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
            JSONObject()
        }
    }
    private fun state(doc: JSONObject): ConsentState {
        val events = doc.optJSONArray("events") ?: JSONArray()
        return ConsentState(doc.optBoolean("consent", false),
            (0 until events.length()).map { events.optString(it) })
    }
    override fun read(): ConsentState = synchronized(lock) { state(load()) }
    override fun setCollectionConsent(granted: Boolean): ConsentState = synchronized(lock) {
        val doc = load()
        doc.put("consent", granted).put("noticeVersion", 1)
        event(doc, if (granted) "수집 동의함 · 안내 v1" else "수집 동의 철회 · 안내 v1")
        persist(doc)
        state(doc)
    }
    override fun recordPermissionChecks(checks: Map<String, Boolean>): ConsentState = synchronized(lock) {
        val doc = load()
        var changed = false
        for ((key, name) in listOf("usage" to "사용 정보 접근", "overlay" to "다른 앱 위에 표시",
                "notifications" to "휴식 제안 알림")) {
            val value = checks[key] ?: continue
            if (!doc.has(key) || doc.optBoolean(key) != value) {
                event(doc, "$name: ${if (value) "허용 확인" else "미허용 확인"}")
                doc.put(key, value)
                changed = true
            }
        }
        if (changed) persist(doc)
        state(doc)
    }
    private fun event(doc: JSONObject, text: String) {
        val previous = doc.optJSONArray("events") ?: JSONArray()
        val events = JSONArray().put(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date()) + " · " + text)
        for (i in 0 until minOf(previous.length(), 99)) events.put(previous.optString(i))
        doc.put("events", events)
    }
    private fun persist(doc: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(doc.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            runCatching { file.failWrite(stream) }
            throw e
        }
    }
}
