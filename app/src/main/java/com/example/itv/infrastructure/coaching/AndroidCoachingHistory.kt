package com.example.itv.infrastructure.coaching

import android.content.Context
import com.example.itv.application.coaching.*

internal class AndroidCoachingJournal(context: Context): CoachingJournal {
    private val context = context.applicationContext
    var engine: String = "RULE"
    var policyVersion: String = "test-coaching-v2"
    override fun add(id: String, event: String) = InterventionJournal.add(context, id, event)
    var onStructured: ((String,String)->Unit)? = null
    override fun structured(id:String,kind:String) {
        InterventionEventRecorder.record(context,id,kind,engine,policyVersion)
        onStructured?.invoke(id,kind)
    }
}
internal class AndroidMissionHistory(context: Context): MissionHistory {
    private val context = context.applicationContext
    override fun begin(id: String) = MissionStats.begin(context, id)
    override fun finish(id: String, result: String) = MissionStats.finish(context, id, result)
}
