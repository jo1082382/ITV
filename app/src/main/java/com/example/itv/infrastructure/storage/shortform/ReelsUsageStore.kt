package com.example.itv.infrastructure.storage.shortform
import com.example.itv.domain.records.ReelsCalibrationSamples


import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Local, backup-excluded aggregate store. No message content or video identities. */
internal object ReelsUsageStore {
    data class Day(val millis: Long = 0, val count: Int = 0, val observed: Boolean = false)
    private var file: AtomicFile? = null
    private var data = JSONObject()
    private var previous = false
    private var lastTick = 0L
    private var lastWall = 0L
    private var lastSeen = 0L
    private var lastSave = 0L
    private var sampleKey = ""
    private var loadError: Exception? = null
    @Synchronized fun initialize(context: Context) {
        if (file != null) return
        file = AtomicFile(File(context.noBackupFilesDir, "reels_usage_v1.json"))
        data = try { JSONObject(file!!.openRead().bufferedReader().use { it.readText() }) }
        catch (e: java.io.FileNotFoundException) {
            if (file!!.baseFile.exists() || File(file!!.baseFile.path + ".bak").exists()) loadError = e
            JSONObject()
        } catch (e: Exception) { loadError = e; JSONObject() }
        sampleKey = (data.optJSONObject("samples") ?: JSONObject()).toString()
    }
    @Synchronized fun readSamples(): ReelsCalibrationSamples {
        check(loadError == null) { "릴스 표본 파일을 읽지 못했습니다. 원본 파일은 유지합니다." }
        val samples = data.optJSONObject("samples") ?: JSONObject()
        fun read(key: String): Set<String>? {
            val a = samples.optJSONArray(key) ?: return null
            return (0 until a.length()).asSequence().map { a.getString(it) }.toSet()
        }
        return ReelsCalibrationSamples(read("reels"), read("feed"), read("dm"), read("comments"))
    }
    @Synchronized fun saveSamples(samples: ReelsCalibrationSamples) {
        saveSamplesConfirmed(samples)
    }
    @Synchronized fun saveSamplesConfirmed(samples: ReelsCalibrationSamples) {
        val value = JSONObject()
        fun put(key: String, ids: Set<String>?) { ids?.let { value.put(key, JSONArray(it.sorted())) } }
        put("reels", samples.reels); put("feed", samples.feed)
        put("dm", samples.dm); put("comments", samples.comments)
        val next = JSONObject(data.toString()).put("samples", value)
        // Always confirm the write, including retries after an earlier failure.
        writeConfirmed(next)
        data = next; sampleKey = value.toString()
    }
    @Synchronized fun clearRecordsConfirmed(context: Context) {
        initialize(context)
        val next = JSONObject(data.toString()); next.remove("days")
        writeConfirmed(next)
        data = next
        previous = false; lastTick = 0L; lastWall = 0L; lastSeen = 0L
    }
    private fun writeConfirmed(next: JSONObject) {
        check(loadError == null) { "기존 릴스 파일을 읽지 못했습니다. 원본 파일은 유지합니다." }
        val target = checkNotNull(file) { "릴스 저장소가 초기화되지 않았습니다." }
        val stream = target.startWrite()
        try {
            stream.write(next.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
            lastSave = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            runCatching { target.failWrite(stream) }
            throw e
        }
    }
    private fun bucket(key: String = LocalDate.now().toString()): JSONObject {
        val days = data.optJSONObject("days") ?: JSONObject().also { data.put("days", it) }
        return days.optJSONObject(key) ?: JSONObject().also { days.put(key, it) }
    }
    @Synchronized fun observe(valid: Boolean, @Suppress("UNUSED_PARAMETER") context: Context) {
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val dt = now - lastTick
        if (valid) {
            val b = bucket(); b.put("observed", true)
            // Very brief structure loss does not imply a new viewing entry.
            if (!previous && (lastSeen == 0L || now - lastSeen >= 3000L)) b.put("count", b.optInt("count") + 1)
            if (previous && dt in 1L..1500L && wall - lastWall in 1L..2000L) {
                val zone = ZoneId.systemDefault()
                val oldDate = java.time.Instant.ofEpochMilli(lastWall).atZone(zone).toLocalDate()
                val newDate = java.time.Instant.ofEpochMilli(wall).atZone(zone).toLocalDate()
                if (oldDate == newDate) b.put("ms", b.optLong("ms") + dt)
                else {
                    val midnight = newDate.atStartOfDay(zone).toInstant().toEpochMilli()
                    val before = (midnight - lastWall).coerceIn(0L, dt)
                    val old = bucket(oldDate.toString()); old.put("ms", old.optLong("ms") + before)
                    b.put("ms", b.optLong("ms") + dt - before)
                }
            }
            lastSeen = now
        }
        val changed = valid != previous
        previous = valid; lastTick = now; lastWall = wall
        if (changed || now - lastSave >= 5000L) persist()
    }
    @Synchronized fun transition() {
        if (!previous) return
        val b = bucket(); b.put("count", b.optInt("count") + 1); persist()
    }
    @Synchronized fun pause() { previous = false; lastTick = 0; persist() }
    /** Clears only reel aggregates; keeps calibration samples. */
    @Synchronized fun clearRecords(context: Context) {
        initialize(context)
        data.remove("days")
        previous = false; lastTick = 0L; lastWall = 0L; lastSeen = 0L
        persist()
    }
    /** Available for a future account-wide deletion flow, which must also stop collection. */
    @Synchronized fun clearAll(context: Context) {
        initialize(context)
        data = JSONObject(); sampleKey = ""
        previous = false; lastTick = 0L; lastWall = 0L; lastSeen = 0L
        file?.delete()
    }
    @Synchronized fun today(context: Context): Day {
        initialize(context)
        if (!com.example.itv.infrastructure.coaching.CoachingConsentStore.granted(context)) return Day()
        val b = bucket()
        return Day(b.optLong("ms"), b.optInt("count"), b.optBoolean("observed"))
    }
    private fun persist() {
        if (loadError != null) return // Never overwrite unreadable existing data.
        val target = file ?: return
        var stream: java.io.FileOutputStream? = null
        try {
            stream = target.startWrite()
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
            lastSave = SystemClock.elapsedRealtime()
        } catch (_: Exception) { stream?.let { target.failWrite(it) } }
    }
}
