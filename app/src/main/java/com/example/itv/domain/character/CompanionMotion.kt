package com.example.itv.domain.character

enum class CompanionPhase { APPEAR, WALK_HOME, TURN_BACK, INVITE, ENTER_HOME, REST, WAVE, LEAVE, GONE }
enum class CompanionAction { INVITE, REST, LATER }
data class CompanionFrame(val phase: CompanionPhase, val progress: Float)
object CompanionMotion {
    const val DURATION_MS = 3300L
    fun duration(action: CompanionAction) = when(action) {
        CompanionAction.INVITE -> DURATION_MS
        CompanionAction.REST -> 950L
        CompanionAction.LATER -> 1200L
    }
    fun frame(elapsedMs: Long, action: CompanionAction = CompanionAction.INVITE): CompanionFrame {
        val t=elapsedMs.coerceAtLeast(0L)
        return when(action) {
            CompanionAction.INVITE -> when {
                t<700 -> CompanionFrame(CompanionPhase.APPEAR,t/700f)
                t<2700 -> CompanionFrame(CompanionPhase.WALK_HOME,(t-700)/2000f)
                t<DURATION_MS -> CompanionFrame(CompanionPhase.TURN_BACK,(t-2700)/600f)
                else -> CompanionFrame(CompanionPhase.INVITE,1f)
            }
            CompanionAction.REST -> if(t<950) CompanionFrame(CompanionPhase.ENTER_HOME,t/950f) else CompanionFrame(CompanionPhase.REST,1f)
            CompanionAction.LATER -> when {
                t<450 -> CompanionFrame(CompanionPhase.WAVE,t/450f)
                t<1200 -> CompanionFrame(CompanionPhase.LEAVE,(t-450)/750f)
                else -> CompanionFrame(CompanionPhase.GONE,1f)
            }
        }
    }
}
