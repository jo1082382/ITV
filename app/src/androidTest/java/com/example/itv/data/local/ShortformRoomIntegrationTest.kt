package com.example.itv.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses an isolated named DB, never ItvDatabase.get or itv.db. */
@RunWith(AndroidJUnit4::class)
class ShortformRoomIntegrationTest {
    private fun row() = ShortformDailyRecordEntity("test-owner", "com.instagram.android",
        "2026-11-02", "LEGACY_UNKNOWN", "LEGACY_FILE", 12345, 3, true, "legacy-v1", 1)
    private fun receipt() = LocalImportReceiptEntity("test-import", "test-owner", "synthetic.json", 1, 1)
    private fun open(context: Context, name: String) = Room.databaseBuilder(context,
        ItvDatabase::class.java, name).addMigrations(ItvDatabase.MIGRATION_1_2,
        ItvDatabase.MIGRATION_2_3).build()

    @Test fun realRoomRollbackReceiptAndReopen() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="itv-integration-${UUID.randomUUID()}.db"
        var db=open(context,name)
        try {
            var failed=false
            try {
                db.withTransaction {
                    db.shortformDao().insertRecords(listOf(row()))
                    error("injected failure before receipt")
                }
            } catch(e:IllegalStateException) { failed=true }
            assertTrue(failed)
            assertTrue(db.shortformDao().all("test-owner").isEmpty())
            assertNull(db.shortformDao().receipt("test-import"))
            db.withTransaction {
                db.shortformDao().insertRecords(listOf(row()))
                db.shortformDao().insertReceipt(receipt())
            }
            db.close(); db=open(context,name)
            assertEquals(12345L,db.shortformDao().all("test-owner").single().durationMs)
            assertNotNull(db.shortformDao().receipt("test-import"))
            db.withTransaction { db.shortformDao().deleteOwnerRecords("test-owner") }
            db.close(); db=open(context,name)
            assertTrue(db.shortformDao().all("test-owner").isEmpty())
            assertNotNull(db.shortformDao().receipt("test-import"))
        } finally { db.close();context.deleteDatabase(name) }
    }

    @Test fun migration2to3PreservesExistingRowsAndCreatesExpectedColumns() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="itv-migration-${UUID.randomUUID()}.db"
        try {
            val raw=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
            try {
                raw.execSQL("CREATE TABLE synthetic_existing (id TEXT PRIMARY KEY, value INTEGER NOT NULL)")
                raw.execSQL("INSERT INTO synthetic_existing VALUES ('keep',42)")
                // Wrap Android SQLite using a test-only adapter is unnecessary: apply the production
                // migration through Room's SupportSQLiteOpenHelper on this isolated minimal fixture.
            } finally { raw.close() }
            val config=androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object:androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db:androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(db:androidx.sqlite.db.SupportSQLiteDatabase,oldVersion:Int,newVersion:Int) {}
                }).build()
            val helper=androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(config)
            try {
                val db=helper.writableDatabase
                ItvDatabase.MIGRATION_2_3.migrate(db)
                db.query("SELECT value FROM synthetic_existing WHERE id='keep'").use {
                    assertTrue(it.moveToFirst());assertEquals(42,it.getInt(0))
                }
                val columns=mutableSetOf<String>()
                db.query("PRAGMA table_info(shortform_daily_records)").use {
                    while(it.moveToNext()) columns.add(it.getString(it.getColumnIndexOrThrow("name")))
                }
                assertEquals(setOf("ownerId","packageName","localDate","zoneKey","source",
                    "durationMs","transitionCount","observed","calculationVersion","updatedAt"),columns)
            } finally { helper.close() }
        } finally { context.deleteDatabase(name) }
    }
}
