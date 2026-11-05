package com.example.itv.domain.character
import org.junit.Assert.*
import org.junit.Test
class CompanionMotionTest {
    @Test fun invitationBoundaries() {
        assertEquals(CompanionPhase.APPEAR,CompanionMotion.frame(-1).phase)
        assertEquals(CompanionPhase.WALK_HOME,CompanionMotion.frame(700).phase)
        assertEquals(CompanionPhase.TURN_BACK,CompanionMotion.frame(2700).phase)
        assertEquals(CompanionPhase.INVITE,CompanionMotion.frame(3300).phase)
    }
    @Test fun reactionsFinish() {
        assertEquals(CompanionPhase.REST,CompanionMotion.frame(950,CompanionAction.REST).phase)
        assertEquals(CompanionPhase.GONE,CompanionMotion.frame(1200,CompanionAction.LATER).phase)
    }
    @Test fun normalized() {
        for(action in CompanionAction.values()) for(t in -10L..5000L)
            assertTrue(CompanionMotion.frame(t,action).progress in 0f..1f)
    }
}
