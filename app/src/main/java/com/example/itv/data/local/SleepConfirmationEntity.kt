package com.example.itv.data.local

import androidx.room.*
@Entity(tableName="sleep_confirmations", primaryKeys=["ownerId","recordDate"])
data class SleepConfirmationEntity(val ownerId:String,val recordDate:String,val zoneId:String,
    val choice:String,val startMs:Long?,val endMs:Long?,val origin:String,val updatedAt:Long)
@Dao
interface SleepConfirmationDao {
    @Query("SELECT * FROM sleep_confirmations WHERE ownerId=:owner AND recordDate=:date")
    suspend fun read(owner:String,date:String):SleepConfirmationEntity?
    @Upsert suspend fun save(value:SleepConfirmationEntity)
    @Query("DELETE FROM sleep_confirmations WHERE ownerId=:owner AND recordDate=:date")
    suspend fun reset(owner:String,date:String)
}
