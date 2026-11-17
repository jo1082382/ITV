package com.example.itv.infrastructure.records

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import com.example.itv.data.local.*
import com.example.itv.domain.records.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Import and receipt commit together. The old writer is replaced in this same release. */
internal class RoomShortformPersistence(context: Context, private val ownerId: String) : ShortformPersistence {
    private val context = context.applicationContext
    private val db = ItvDatabase.get(this.context)
    private val dao = db.shortformDao()
    private val importKey = "shortform_daily_v1_to_room_v1"
    private val sourceName = "shortform_daily_v1.json"
    private fun entity(x: StoredShortformDay) = ShortformDailyRecordEntity(ownerId,
        x.packageName, x.date, x.zoneKey, x.source, x.ms, x.transitions, x.observed,
        x.calculationVersion, System.currentTimeMillis())
    private fun value(x: ShortformDailyRecordEntity) = StoredShortformDay(x.packageName,
        x.localDate, x.zoneKey, x.source, x.durationMs, x.transitionCount, x.observed, x.calculationVersion)
    override suspend fun prepare(): List<StoredShortformDay> = withContext(Dispatchers.IO) {
        require(ownerId.isNotBlank())
        db.withTransaction {
            val owner = db.dao().owner(ownerId)
            if (owner == null) db.dao().insertOwner(OwnerEntity(ownerId, "GUEST", createdAt = System.currentTimeMillis()))
            val receipt = dao.receipt(importKey)
            if (receipt != null) {
                check(receipt.ownerId == ownerId) { "이전된 기록의 소유자가 다릅니다." }
            } else {
                // Do not overwrite or guess how an existing unreceipted target arose.
                check(dao.all(ownerId).isEmpty()) { "기존 Room 기록과 이전 상태를 확인해 주세요." }
                val rows = readLegacy()
                dao.insertRecords(rows.map { entity(it) })
                dao.insertReceipt(LocalImportReceiptEntity(importKey, ownerId, sourceName,
                    rows.size.toLong(), System.currentTimeMillis()))
            }
        }
        dao.all(ownerId).map { value(it) }
    }
    private fun readLegacy(): List<StoredShortformDay> {
        val f = AtomicFile(File(context.noBackupFilesDir, sourceName))
        val text = try { f.openRead().bufferedReader().use { it.readText() } }
        catch (e: java.io.FileNotFoundException) {
            if (f.baseFile.exists() || File(f.baseFile.path + ".bak").exists()) throw e
            return emptyList()
        }
        val doc = JSONObject(text)
        val keys = doc.keys()
        val result = mutableListOf<StoredShortformDay>()
        while (keys.hasNext()) {
            val app = keys.next()
            val pkg = when (app) {
                "instagram" -> "com.instagram.android"
                "youtube" -> "com.google.android.youtube"
                else -> error("알 수 없는 숏폼 앱 키: $app")
            }
            val dates = doc.getJSONObject(app)
            val it = dates.keys()
            while (it.hasNext()) {
                val date = it.next(); LocalDate.parse(date)
                val d = dates.getJSONObject(date)
                fun integer(key: String): Long {
                    if (!d.has(key)) return 0L
                    val n = d.get(key)
                    check(n is Int || n is Long) { "잘못된 $key 값" }
                    return (n as Number).toLong().also { check(it >= 0) { "음수 $key 값" } }
                }
                val observed = if (d.has("seen")) d.get("seen").also {
                    check(it is Boolean) { "잘못된 관측 여부" }
                } as Boolean else false
                result += StoredShortformDay(pkg, date, "LEGACY_UNKNOWN", "LEGACY_FILE",
                    integer("ms"), integer("n"), observed, "legacy-v1")
            }
        }
        return result
    }
    override suspend fun save(records: List<StoredShortformDay>): Unit = withContext(Dispatchers.IO) {
        db.withTransaction {
            check(dao.receipt(importKey)?.ownerId == ownerId) { "기록 이전이 완료되지 않았습니다." }
            dao.saveRecords(records.map { entity(it) })
        }
    }
    override suspend fun hours():List<StoredShortformHour> = db.shortformHourDao().all(ownerId).map {
        StoredShortformHour(it.packageName,it.localDate,it.hour,it.zoneId,it.durationMs)
    }
    override suspend fun saveWithHours(records:List<StoredShortformDay>,hours:List<StoredShortformHour>) {
        db.withTransaction {
            check(dao.receipt(importKey)?.ownerId==ownerId)
            dao.saveRecords(records.map { entity(it) })
            db.shortformHourDao().save(hours.map { ShortformHourEntity(ownerId,it.packageName,it.date,it.hour,it.zone,it.ms) })
        }
    }
    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        db.withTransaction {
            check(dao.receipt(importKey)?.ownerId == ownerId)
            dao.deleteOwnerRecords(ownerId)
            db.shortformHourDao().clear(ownerId)
            // Keep receipt: preserved JSON must never resurrect deleted records.
        }
    }
}
