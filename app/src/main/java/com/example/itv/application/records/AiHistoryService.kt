package com.example.itv.application.records
internal data class AiHistoryItem(val id:String,val time:Long,val kind:String,val sessionApp:String,
 val sessionMs:Long,val instagramMs:Long,val youtubeMs:Long,val gapMs:Long?,val version:String,val engine:String="RULE")
internal interface AiHistorySource {suspend fun recent():List<AiHistoryItem>}
internal class AiHistoryService(private val source:AiHistorySource){suspend fun recent()=source.recent()}
