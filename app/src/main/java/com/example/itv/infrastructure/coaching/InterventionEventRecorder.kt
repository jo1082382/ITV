package com.example.itv.infrastructure.coaching
import android.content.Context
import com.example.itv.data.local.*
import com.example.itv.infrastructure.storage.shortform.ShortformDailyStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
/** Ordered process-local writes; failures are visible. No network / AI request. */
internal object InterventionEventRecorder {
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private data class Pending(val context:Context,val row:InterventionEventEntity)
 private val queue=Channel<Pending>(128)
 @Volatile var failed=false;private set
 init {scope.launch {for(p in queue){
   var done=false
   while(!done){try {val db=ItvDatabase.get(p.context);val dao=db.interventionEventDao()
      val previous=if(p.row.kind=="OFFER_SHOWN")dao.previousOffer() else null
      dao.insert(p.row.copy(previousOfferGapMs=previous?.let {(p.row.wallMs-it.wallMs).takeIf {gap->gap>=0}}));failed=false;done=true}
    catch(e:CancellationException){throw e}catch(e:Exception){failed=true;delay(5000)}}
 }}}
 fun record(context:Context,id:String,kind:String){
  val session=ShortformDailyStore.sessionSnapshot()
  val row=InterventionEventEntity(java.util.UUID.randomUUID().toString(),id,System.currentTimeMillis(),kind,
    "test-coaching-v2","RULE",session.app,session.durationMs,
    ShortformDailyStore.today(context,"instagram").ms,ShortformDailyStore.today(context,"youtube").ms)
  if(!queue.trySend(Pending(context.applicationContext,row)).isSuccess)failed=true
 }
}
