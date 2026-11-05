package com.example.itv.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic, isolated databases only. Never opens itv.db or JevStore's database. */
@RunWith(AndroidJUnit4::class)
class JevUpstreamMigrationTest {
    private fun open(context:Context,name:String)=Room.databaseBuilder(context,ItvDatabase::class.java,name)
        .addMigrations(ItvDatabase.MIGRATION_4_5,ItvDatabase.MIGRATION_5_6,ItvDatabase.MIGRATION_6_7).build()

    @Test fun preJevVersion4RecordsSurviveUpgradeToTeamVersion7() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="itv-jev-upgrade4-${UUID.randomUUID()}.db"
        var db=open(context,name)
        try {
            val old=ShortformDailyRecordEntity("synthetic-owner","com.instagram.android","2026-11-05",
                "Asia/Seoul","LEGACY_FILE",12345,3,true,"legacy-v1",1)
            db.shortformDao().insertRecords(listOf(old))
            db.close()
            // The upstream change only adds these two tables to the existing v4 schema.
            context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { raw ->
                raw.execSQL("DROP TABLE shortform_hourly_records")
                raw.execSQL("DROP TABLE intervention_events")
                raw.version=4
            }
            db=open(context,name)
            // Room validates the complete migrated schema when the first DAO is opened.
            assertEquals(listOf(old),db.shortformDao().all("synthetic-owner"))
            assertTrue(db.shortformHourDao().all("synthetic-owner").isEmpty())
            assertTrue(db.interventionEventDao().recent().isEmpty())
            db.shortformHourDao().save(listOf(ShortformHourEntity("synthetic-owner",
                "com.instagram.android","2026-11-05",12,"Asia/Seoul",12345)))
            db.interventionEventDao().insert(InterventionEventEntity("synthetic-event","jev-decision",
                2,"OFFER_SHOWN","jev-candidate-v1","JEV","instagram",12345))
            assertEquals(12345L,db.shortformHourDao().all("synthetic-owner").single().durationMs)
            assertEquals("JEV",db.interventionEventDao().recent().single().engine)
        } finally { db.close();context.deleteDatabase(name) }
    }

    @Test fun version6RuleEventsRetainHistoryAndReceiveNullableDefaults() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="itv-jev-upgrade6-${UUID.randomUUID()}.db"
        var db=open(context,name)
        try {
            db.openHelper.writableDatabase
            db.close()
            context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { raw ->
                raw.execSQL("DROP TABLE intervention_events")
                raw.execSQL("CREATE TABLE intervention_events (eventId TEXT NOT NULL PRIMARY KEY, decisionId TEXT NOT NULL, wallMs INTEGER NOT NULL, kind TEXT NOT NULL, policyVersion TEXT NOT NULL, engine TEXT NOT NULL, sessionApp TEXT NOT NULL, sessionMs INTEGER NOT NULL)")
                raw.execSQL("CREATE INDEX index_intervention_events_decisionId ON intervention_events(decisionId)")
                raw.execSQL("INSERT INTO intervention_events VALUES ('old-event','old-decision',1,'OFFER_SHOWN','test-coaching-v1','RULE','instagram',60000)")
                raw.version=6
            }
            db=open(context,name)
            val event=db.interventionEventDao().recent().single()
            assertEquals("old-decision",event.decisionId)
            assertEquals("RULE",event.engine)
            assertEquals(60000L,event.sessionMs)
            assertEquals(0L,event.instagramTodayMs)
            assertEquals(0L,event.youtubeTodayMs)
            assertNull(event.previousOfferGapMs)
        } finally { db.close();context.deleteDatabase(name) }
    }
}
