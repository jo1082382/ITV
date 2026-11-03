package com.example.itv.application.records

import com.example.itv.domain.records.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ShortformPersistenceFailureTest {
    private class FakeStore : ShortformPersistence {
        var fail=false
        var rows=emptyList<StoredShortformDay>()
        override suspend fun prepare()=rows.toList()
        override suspend fun save(records:List<StoredShortformDay>) {
            if(fail) error("injected failure")
            rows=records.toList()
        }
        override suspend fun clear() { rows=emptyList() }
    }
    private fun row(ms:Long)=StoredShortformDay("com.instagram.android","2026-11-02",
        "Asia/Seoul","LIVE_OBSERVATION",ms,1,true,"shortform-v1")
    @Test fun failedSaveKeepsPreviousThenRetryReplacesWithoutAddition() = runBlocking {
        val fake=FakeStore();val service=ShortformPersistenceService(fake)
        service.save(listOf(row(100)))
        fake.fail=true
        var failed=false
        try { service.save(listOf(row(200))) } catch(e:IllegalStateException) { failed=true }
        assertTrue(failed);assertEquals(100L,fake.rows.single().ms)
        fake.fail=false;service.save(listOf(row(200)));service.save(listOf(row(200)))
        assertEquals(200L,fake.rows.single().ms)
    }
    @Test fun negativeValueNeverReachesStorage() = runBlocking {
        val fake=FakeStore();val service=ShortformPersistenceService(fake)
        var failed=false
        try { service.save(listOf(row(-1))) } catch(e:IllegalArgumentException) { failed=true }
        assertTrue(failed);assertTrue(fake.rows.isEmpty())
    }
}
