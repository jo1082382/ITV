package com.example.itv.application.coaching

internal interface CoachingJournal { fun add(id: String, event: String) }
internal interface MissionHistory {
    fun begin(id: String)
    fun finish(id: String, result: String)
}
internal interface MissionScheduler {
    fun cancelStartTimeout()
    fun scheduleStartTimeout(delayMs: Long)
}
