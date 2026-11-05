package com.example.itv.data.local
import androidx.room.*
@Entity(tableName="intervention_events",indices=[Index(value=["decisionId"])])
data class InterventionEventEntity(@PrimaryKey val eventId:String,val decisionId:String,val wallMs:Long,
 val kind:String,val policyVersion:String,val engine:String,val sessionApp:String,val sessionMs:Long,
 @ColumnInfo(defaultValue="0") val instagramTodayMs:Long=0,
 @ColumnInfo(defaultValue="0") val youtubeTodayMs:Long=0,
 val previousOfferGapMs:Long?=null)
@Dao interface InterventionEventDao {
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(row:InterventionEventEntity)
 @Query("SELECT * FROM intervention_events ORDER BY wallMs DESC, eventId DESC LIMIT 60") suspend fun recent():List<InterventionEventEntity>
 @Query("SELECT * FROM intervention_events WHERE kind='OFFER_SHOWN' ORDER BY wallMs DESC LIMIT 1") suspend fun previousOffer():InterventionEventEntity?
 @Query("SELECT * FROM intervention_events ORDER BY wallMs DESC LIMIT 1") suspend fun latest():InterventionEventEntity?
}
