package com.example.itv.infrastructure.jev

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated DB only: verifies real Room persistence, receipt uniqueness and feedback nullability. */
@RunWith(AndroidJUnit4::class)
class JevPersistenceTest {
    @Test fun zeroSkipAndOutboxSurviveReopen() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="jev-integration-${UUID.randomUUID()}.db"
        fun open()=Room.databaseBuilder(context,JevDatabase::class.java,name).build()
        var db=open()
        try {
            val zero=JevTrial("zero","T01","LIVE","{}",1,state="SUCCEEDED",displayedAt=2,
                feedbackStatus="SUBMITTED",feedbackScore=0)
            val skip=zero.copy(id="skip",feedbackStatus="SKIPPED",feedbackScore=null)
            db.withTransaction {
                db.dao().saveTrial(zero);db.dao().saveTrial(skip)
                db.dao().enqueue(JevOutbox(eventId="same-receipt",payload="immutable"))
                db.dao().enqueue(JevOutbox(eventId="same-receipt",payload="duplicate"))
            }
            db.close();db=open()
            assertEquals(0,db.dao().trial("zero")!!.feedbackScore)
            assertNull(db.dao().trial("skip")!!.feedbackScore)
            assertEquals("SKIPPED",db.dao().trial("skip")!!.feedbackStatus)
            assertEquals(1,db.dao().count())
            assertEquals("immutable",db.dao().head()!!.payload)
            assertTrue(db.dao().pendingFeedback().first().isEmpty())
            try {
                db.withTransaction {
                    db.dao().enqueue(JevOutbox(eventId="rolled-back",payload="never committed"))
                    error("injected failure")
                }
            } catch(_:IllegalStateException) { }
            assertEquals(1,db.dao().count())
        } finally {
            db.close();context.deleteDatabase(name)
        }
    }
}
