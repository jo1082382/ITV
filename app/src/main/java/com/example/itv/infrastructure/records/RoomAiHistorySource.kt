package com.example.itv.infrastructure.records
import android.content.Context
import com.example.itv.application.records.*
import com.example.itv.data.local.ItvDatabase
internal class RoomAiHistorySource(context:Context):AiHistorySource {
 private val db=ItvDatabase.get(context.applicationContext)
 override suspend fun recent()=db.interventionEventDao().recent().map {
  AiHistoryItem(it.decisionId,it.wallMs,it.kind,it.sessionApp,it.sessionMs,it.instagramTodayMs,it.youtubeTodayMs,it.previousOfferGapMs,it.policyVersion,it.engine)
 }
}
