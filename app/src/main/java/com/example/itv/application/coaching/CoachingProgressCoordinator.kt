package com.example.itv.application.coaching
import com.example.itv.domain.coaching.RestUsageClock


import com.example.itv.domain.coaching.*

internal interface CoachingProgressEffects {
    fun status(value: String)
    fun hideOffer()
    fun showOffer()
}

/** Main-thread orchestration of the existing TEST policy; owns no Android objects. */
internal class CoachingProgressCoordinator(
    private val responseController: CoachingResponseController,
    private val effects: CoachingProgressEffects
) {
    val usageClock: RestUsageClock = RestUsageClock(TestCoachingPolicy.REST_MS)
    var waitingYoutube: Boolean = false
    var waitingTransition: Boolean = false
    var safeSince: Long? = null
    private val interventionMs: Long = TestCoachingPolicy.INTERVENTION_MS
    fun reset(): Unit {
        waitingYoutube = false; waitingTransition = false; safeSince = null
        responseController.offered = false; responseController.retryAt = null
        responseController.suppressed = false
        effects.hideOffer()
    }
    fun observe(now: Long, using: Boolean, offerVisible: Boolean): Boolean {
        if (usageClock.observe(now, using)) reset()
        if (!using) {
            safeSince = null
            if (offerVisible) { effects.hideOffer(); responseController.offered = false }
        }
        return using
    }
    fun tick(now: Long, using: Boolean, observation: CoachingObservation, offerVisible: Boolean): Unit {
            val guardReason = observation.guardReason
            if (guardReason != null) {
                safeSince = null
                if (offerVisible) { effects.hideOffer(); responseController.offered = false }
            } else if (using && safeSince == null) safeSince = now
            if (!using) {
                effects.status( "일시정지 · 누적 ${usageClock.usedMs / 1000}초 · 확인된 휴식 ${usageClock.restMs / 1000}초")
            } else if (!responseController.offered && !responseController.suppressed) {
                val elapsed = usageClock.usedMs
                effects.status( "휴식 이후 누적 · ${elapsed / 1000}초 / ${interventionMs / 1000}초")
                val safeAt = safeSince
                when {
                    guardReason != null -> effects.status( "$guardReason · 누적 ${elapsed / 1000}초")
                    safeAt == null || now - safeAt < 5000L -> effects.status( "작성 종료 확인 중 · 안전 상태 5초 대기")
                    responseController.retryAt != null && now < (responseController.retryAt ?: now) -> effects.status( "나중에 선택 · 재알림 대기")
                    elapsed >= interventionMs -> {
                        val youtube = observation.youtubeState
                        if (youtube == 1) {
                            waitingYoutube = true; waitingTransition = false
                            effects.status( "개입 대기 · 다음 쇼츠 전환을 기다리는 중")
                        } else if (youtube == -1) {
                            waitingYoutube = false; waitingTransition = false
                            effects.status( "YouTube/현재 화면 확인 중 · 개입 보류")
                        } else when (observation.reelsState) {
                            1 -> {
                                waitingYoutube = false; waitingTransition = true
                                effects.status( "개입 대기 · 다음 릴스 전환을 기다리는 중")
                            }
                            0 -> { waitingYoutube = false; waitingTransition = false; effects.showOffer() }
                            else -> effects.status( "현재 화면 판별 불가 · 개입 보류")
                        }
                    }
                }
            }
    }
}
