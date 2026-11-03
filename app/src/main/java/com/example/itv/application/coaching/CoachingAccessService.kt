package com.example.itv.application.coaching

import com.example.itv.domain.coaching.*

internal class CoachingAccessService(private val source: CoachingAccessSource) {
    fun ready(): Boolean = source.consent() && source.overlay() && source.notifications()
    fun snapshot(): CoachingAvailability = CoachingAvailability(source.consent(), source.overlay(), source.notifications())
}
