package com.example.itv.domain.records
import java.time.*
internal data class StoredShortformHour(val packageName:String,val date:String,val hour:Int,val zone:String,val ms:Long)
internal object ShortformHourPolicy {
 fun split(pkg:String, previous:ShortformClock, valid:Boolean,t:ShortformObservationTime):List<StoredShortformHour> {
  val dt=t.elapsedMs-previous.elapsedMs
  val wall=t.wallMs-previous.wallMs
  if(!previous.valid || !valid || dt !in 1L..1500L || wall !in 1L..2000L) return emptyList()
  val zone=ZoneId.of(t.zoneId); val out=mutableListOf<StoredShortformHour>()
  var cursor=previous.wallMs; var allocated=0L
  while(cursor<t.wallMs) {
   val z=Instant.ofEpochMilli(cursor).atZone(zone)
   val end=minOf(t.wallMs,z.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli())
   val cumulative=if(end==t.wallMs) dt else dt*(end-previous.wallMs)/wall
   out+=StoredShortformHour(pkg,z.toLocalDate().toString(),z.hour,t.zoneId,cumulative-allocated)
   allocated=cumulative;cursor=end
  }
  return out
 }
}
