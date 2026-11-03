package com.example.itv.application.records

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class ShortformQueueAdmissionTest {
    @Test fun boundedQueuePreservesAcceptedOrderAndMarksGap() {
        val queue = Channel<Pair<Int,Long>>(2)
        val policy = ShortformQueueAdmission()
        fun send(value:Int):Boolean {
            val accepted=queue.trySend(value to policy.generation).isSuccess
            if(accepted) policy.accepted(false) else policy.rejected(false)
            return accepted
        }
        assertTrue(send(1));assertTrue(send(2));assertFalse(send(3))
        assertEquals(1L,policy.lost)
        assertEquals(1 to 0L,queue.tryReceive().getOrThrow())
        assertTrue(send(4))
        assertEquals(2 to 0L,queue.tryReceive().getOrThrow())
        assertEquals(4 to 1L,queue.tryReceive().getOrThrow())
        queue.close()
    }
    @Test fun maintenanceIsCoalescedWithoutFalseLoss() {
        val p=ShortformQueueAdmission()
        assertTrue(p.canSubmit(true));p.accepted(true)
        assertFalse(p.canSubmit(true));assertTrue(p.canSubmit(false))
        p.rejected(true);assertEquals(0L,p.lost)
        p.started(true);assertTrue(p.canSubmit(true))
    }
}
