package com.example.itv.infrastructure.permissions

import com.example.itv.infrastructure.coaching.InterventionJournal
import com.example.itv.infrastructure.coaching.MissionStats

import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe
import com.example.itv.infrastructure.observation.accessibility.WritingGuard


import com.example.itv.presentation.coaching.*
import android.os.SystemClock
import android.Manifest
import android.content.pm.PackageManager
import com.example.itv.presentation.coaching.CoachingAutoStart
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.AtomicFile
import com.example.itv.ui.theme.ITVTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.example.itv.domain.permissions.*

internal class AndroidPermissionSettings(context: Context) : android.content.ContextWrapper(context.applicationContext), PermissionSettingsPort {
    override suspend fun diagnostics(includeJournal: Boolean): PermissionDiagnostics = withContext(Dispatchers.IO) {
        val sample = com.example.itv.domain.coaching.DeveloperStatus.value
        val counts = com.example.itv.infrastructure.coaching.MissionStats.counts(this@AndroidPermissionSettings)
        PermissionDiagnostics(
            sample = PermissionCoachSample(sample.at, sample.running, sample.usedMs,
                sample.restMs, sample.interventionMs, sample.requiredRestMs,
                sample.retryRemainingMs, sample.decision),
            connected = WritingGuard.connected, checkedAt = WritingGuard.checkedAt,
            guardReason = WritingGuard.blockReason(),
            youtubeState = com.example.itv.infrastructure.observation.youtube.YoutubeProbe.screenState(),
            reelsState = com.example.itv.infrastructure.observation.instagram.ReelsProbe.screenState(),
            missionCounts = PermissionMissionCounts(counts.assigned, counts.success, counts.failed, counts.interrupted),
            journal = if (includeJournal) com.example.itv.infrastructure.coaching.InterventionJournal.rows(this@AndroidPermissionSettings) else emptyList(),
            now = android.os.SystemClock.elapsedRealtime())
    }
    override suspend fun clearJournal(): Unit = withContext(Dispatchers.IO) { com.example.itv.infrastructure.coaching.InterventionJournal.clear(this@AndroidPermissionSettings) 
        Unit
    }
    override suspend fun setAutoEnabled(value: Boolean): Unit = withContext(Dispatchers.IO) { CoachingAutoStart.setEnabled(this@AndroidPermissionSettings, value); autoEnabled = CoachingAutoStart.testModeEnabled(this@AndroidPermissionSettings) 
        Unit
    }
    private var guardConnected = false
    private var notifications: Boolean? = null
    private var monitorVisible: Boolean? = null
    private var autoEnabled = true
    private var consent = false
    private var usage: Boolean? = null
    private var overlay: Boolean? = null
    private var history: List<String> = emptyList()
    private var notice: String? = null
    private val store = AtomicFile(File(noBackupFilesDir, "consent_guest_v1.json"))
    private var doc = JSONObject()
    override suspend fun initialize() = withContext(Dispatchers.IO) {
        com.example.itv.composition.CoachingAccess.channels(this@AndroidPermissionSettings)
        try {
            if (store.baseFile.exists()) doc = JSONObject(store.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (_: Exception) {
            doc = JSONObject(); notice = "저장 내용을 읽지 못해 동의를 미동의로 처리했습니다. 다시 확인해 주세요." 
        }
        consent = doc.optBoolean("consent", false); updateHistory()
    
        Unit
    }
    override suspend fun snapshot(): PermissionSnapshot = withContext(Dispatchers.IO) { PermissionSnapshot(guardConnected, notifications, monitorVisible,
        autoEnabled, consent, usage, overlay, history, notice) }
    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (!enabled) return false
            val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (services != null) {
                services.contains(packageName)
            } else {
                WritingGuard.connected
            }
        } catch (_: Exception) {
            WritingGuard.connected
        }
    }

    override suspend fun poll() = withContext(Dispatchers.IO) {
        guardConnected = isAccessibilityEnabled()
        usage = try { (getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED } catch (_: Exception) { null }
        overlay = try { Settings.canDrawOverlays(this@AndroidPermissionSettings) } catch (_: Exception) { null }
        notifications = try { com.example.itv.composition.CoachingAccess.notifications(this@AndroidPermissionSettings) } catch (_: Exception) { null }
        autoEnabled = CoachingAutoStart.testModeEnabled(this@AndroidPermissionSettings)
    
        Unit
    }
    override suspend fun updateCollectionConsent(value: Boolean) = withContext(Dispatchers.IO) {
        notice = null
        val next = JSONObject(doc.toString()).put("consent", value).put("noticeVersion", 1)
        addEvent(next, if (value) "수집 동의함 · 안내 v1" else "수집 동의 철회 · 안내 v1")
        if (persist(next)) {
            consent = value
            CoachingAutoStart.sync(this@AndroidPermissionSettings)
        }
    
        Unit
    }
    override suspend fun refresh() = withContext(Dispatchers.IO) {
        notice = null
        guardConnected = isAccessibilityEnabled()
        usage = try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { null }
        overlay = try { Settings.canDrawOverlays(this@AndroidPermissionSettings) } catch (_: Exception) { null }
        notifications = try { com.example.itv.composition.CoachingAccess.notifications(this@AndroidPermissionSettings) } catch (_: Exception) { null }
        monitorVisible = try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.areNotificationsEnabled() &&
                (nm.getNotificationChannel(com.example.itv.composition.CoachingAccess.MONITOR)?.importance ?: android.app.NotificationManager.IMPORTANCE_NONE) != android.app.NotificationManager.IMPORTANCE_NONE
        } catch (_: Exception) { null }
        autoEnabled = CoachingAutoStart.testModeEnabled(this@AndroidPermissionSettings)
        val next = JSONObject(doc.toString())
        var changed = false
        for ((key, name, value) in listOf(
            Triple("usage", "사용 정보 접근", usage),
            Triple("overlay", "다른 앱 위에 표시", overlay),
            Triple("accessibility", "접근성 서비스", guardConnected as Boolean?),
            Triple("notifications", "휴식 제안 알림", notifications)
        )) {
            if (value != null && (!next.has(key) || next.optBoolean(key) != value)) {
                addEvent(next, "$name: ${if (value) "허용 확인" else "미허용 확인"}")
                next.put(key, value)
                changed = true
            }
        }
        if (changed) persist(next)
        CoachingAutoStart.sync(this@AndroidPermissionSettings)
    
        Unit
    }
    private fun addEvent(next: JSONObject, message: String) {
        val old = next.optJSONArray("events") ?: JSONArray()
        val events = JSONArray().put(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date()) + " · " + message)
        for (i in 0 until minOf(old.length(), 99)) events.put(old.getString(i))
        next.put("events", events)
    }
    private fun updateHistory() {
        val a = doc.optJSONArray("events") ?: JSONArray()
        history = (0 until a.length()).map { a.optString(it) }
    }
    private fun persist(next: JSONObject): Boolean {
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = store.startWrite()
            stream.write(next.toString().toByteArray(Charsets.UTF_8))
            store.finishWrite(stream)
            doc = next
            updateHistory()
            notice = null
            true
        } catch (_: Exception) {
            stream?.let { runCatching { store.failWrite(it) } }
            notice = "설정 내역 저장에 실패했습니다. 동의 변경은 반영하지 않았습니다. 다시 시도해 주세요."
            false
        }
    }
}
