package com.example.itv.domain.records
internal data class ShortformSessionSnapshot(val app:String="",val durationMs:Long=0,val observedAt:Long=0)
/** Session means uninterrupted confirmed observations, not total screen time. */
internal class ShortformSessionTracker {
 var recent=ShortformSessionSnapshot();private set
 private fun finish(){if(app.isNotEmpty()) recent=snapshot()}
 private var app=""; private var previous:ShortformClock=ShortformClock();private var duration=0L
 fun reset(){finish();app="";previous=ShortformClock();duration=0}
 fun observe(name:String,valid:Boolean,t:ShortformObservationTime):ShortformSessionSnapshot {
  val delta=t.elapsedMs-previous.elapsedMs;val wall=t.wallMs-previous.wallMs
  if(!valid){if(name==app)reset();return snapshot()}
  if(app==name && previous.valid && delta in 1L..1500L && wall in 1L..2000L) duration+=delta else {finish();duration=0}
  app=name;previous=ShortformClock(true,t.elapsedMs,t.wallMs)
  return snapshot()
 }
 fun snapshot()=ShortformSessionSnapshot(app,duration,previous.elapsedMs)
}
