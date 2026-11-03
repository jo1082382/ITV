package com.example.itv.infrastructure.records
import com.example.itv.data.local.*
import com.example.itv.domain.records.*
internal class RoomSleepConfirmationStore(db:ItvDatabase, private val owner:String):SleepConfirmationStore {
    private val dao=db.sleepConfirmationDao()
    override suspend fun read(date:String):SleepConfirmation? = dao.read(owner,date)?.let {
        SleepConfirmation(it.recordDate,it.zoneId,it.choice,it.startMs,it.endMs,it.origin,it.updatedAt)
    }
    override suspend fun save(value:SleepConfirmation) {
        dao.save(SleepConfirmationEntity(owner,value.date,value.zoneId,value.choice,value.startMs,value.endMs,value.origin,value.updatedAt))
    }
    override suspend fun reset(date:String) { dao.reset(owner,date) }
}
