package com.example.itv.domain.coaching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyMailboxNotificationPolicyTest {

    private fun eligibleBase() = WeeklyMailboxNotificationContext(
        isScreenInteractive = true,
        isDeviceLocked = false,
        localHour = 10,
        isInOwnApp = false,
        isCoachingOverlayOrMissionActive = false,
        isWritingActive = false,
        isProtectedContext = false,
        isInterruptibleNow = true,
    )

    @Test
    fun evaluate_allowsWhenAwakeUnlockedAndInterruptible() {
        val result = WeeklyMailboxNotificationPolicy.evaluate(eligibleBase())
        assertTrue(result.canNotify)
        assertEquals("ELIGIBLE_MOMENT", result.reason)
    }

    @Test
    fun evaluate_blocksDuringMessengerFinanceCallsAndProtectedApps() {
        val result = WeeklyMailboxNotificationPolicy.evaluate(
            eligibleBase().copy(isProtectedContext = true)
        )
        assertFalse(result.canNotify)
        assertEquals("PROTECTED_APP_OR_ACTIVITY", result.reason)
    }

    @Test
    fun evaluate_blocksWhenTypingOrKeyboardActive() {
        val result = WeeklyMailboxNotificationPolicy.evaluate(
            eligibleBase().copy(isWritingActive = true)
        )
        assertFalse(result.canNotify)
        assertEquals("WRITING_ACTIVE", result.reason)
    }

    @Test
    fun evaluate_blocksDuringActiveGameplayOrVideoPlayback() {
        val result = WeeklyMailboxNotificationPolicy.evaluate(
            eligibleBase().copy(isInterruptibleNow = false)
        )
        assertFalse(result.canNotify)
        assertEquals("NOT_INTERRUPTIBLE_MOMENT", result.reason)
    }

    @Test
    fun evaluate_blocksDuringQuietMorningSleepHoursAndScreenOffOrLocked() {
        assertFalse(
            WeeklyMailboxNotificationPolicy.evaluate(eligibleBase().copy(localHour = 0)).canNotify
        )
        assertFalse(
            WeeklyMailboxNotificationPolicy.evaluate(eligibleBase().copy(localHour = 7)).canNotify
        )
        assertTrue(
            WeeklyMailboxNotificationPolicy.evaluate(eligibleBase().copy(localHour = 8)).canNotify
        )
        assertFalse(
            WeeklyMailboxNotificationPolicy.evaluate(eligibleBase().copy(isScreenInteractive = false)).canNotify
        )
        assertFalse(
            WeeklyMailboxNotificationPolicy.evaluate(eligibleBase().copy(isDeviceLocked = true)).canNotify
        )
    }

    @Test
    fun evaluate_blocksWhenInOwnAppOrCoachingActive() {
        assertFalse(
            WeeklyMailboxNotificationPolicy.evaluate(eligibleBase().copy(isInOwnApp = true)).canNotify
        )
        assertFalse(
            WeeklyMailboxNotificationPolicy.evaluate(eligibleBase().copy(isCoachingOverlayOrMissionActive = true)).canNotify
        )
    }
}
