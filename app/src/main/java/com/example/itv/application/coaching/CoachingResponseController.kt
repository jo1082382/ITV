package com.example.itv.application.coaching

import com.example.itv.domain.coaching.TestCoachingPolicy

internal enum class ResponseOutcome { IGNORED, EXPIRED, REST, LATER, DISMISS }
internal class CoachingResponseController(private val journal: CoachingJournal) {
    var offerId: String? = null
    var deadline = 0L
    var offered = false
    var retryAt: Long? = null
    var suppressed = false
    fun finish(outcome: String) {
        offerId?.let { journal.add(it, outcome) }; offerId = null; deadline = 0L
    }
    fun choose(title: String, now: Long): ResponseOutcome {
        if (offerId == null) return ResponseOutcome.IGNORED
        if (now >= deadline) return ResponseOutcome.EXPIRED
        offerId?.let { journal.structured(it, when(title) { "쉬기" -> "ACCEPTED"; "나중에" -> "LATER"; else -> "DISMISSED" }) }
        finish("선택 · $title")
        return if (title == "나중에") {
            offered = false; retryAt = now + TestCoachingPolicy.REMINDER_MS
            ResponseOutcome.LATER
        } else {
            suppressed = true; retryAt = null
            if (title == "쉬기") ResponseOutcome.REST else ResponseOutcome.DISMISS
        }
    }
    fun timeout(now: Long) {
        offerId?.let { journal.structured(it,"NO_RESPONSE") }
        finish("무응답 · 10초 동안 선택 없음")
        offered = false; retryAt = now + TestCoachingPolicy.REMINDER_MS
    }
}
