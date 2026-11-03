package com.example.itv.composition
import android.content.Context
import com.example.itv.application.records.SleepConfirmationService
import com.example.itv.infrastructure.records.RoomSleepConfirmationStore
import com.example.itv.data.local.ItvDatabase
fun sleepConfirmationService(c:Context)=SleepConfirmationService(RoomSleepConfirmationStore(ItvDatabase.get(c),"demo-guest"))
