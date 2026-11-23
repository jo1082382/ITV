package com.example.itv.data.local
import androidx.room.*
@Entity(tableName="shortform_hourly_records",primaryKeys=["ownerId","packageName","localDate","hour","zoneId"])
data class ShortformHourEntity(val ownerId:String,val packageName:String,val localDate:String,val hour:Int,val zoneId:String,val durationMs:Long)
@Dao
interface ShortformHourDao {
 @Query("SELECT * FROM shortform_hourly_records WHERE ownerId=:owner")
 suspend fun all(owner:String):List<ShortformHourEntity>
 @Query("SELECT * FROM shortform_hourly_records WHERE ownerId=:owner AND localDate=:date AND hour=:hour")
 suspend fun hour(owner:String,date:String,hour:Int):List<ShortformHourEntity>
 @Upsert suspend fun save(rows:List<ShortformHourEntity>)
 @Query("DELETE FROM shortform_hourly_records WHERE ownerId=:owner")
 suspend fun clear(owner:String)
 @Query("DELETE FROM shortform_hourly_records WHERE ownerId=:owner AND localDate < :cutoffDate")
 suspend fun deleteOlderThan(owner:String,cutoffDate:String)
}
