package com.example.itv.infrastructure.observation.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingGuardServiceInfoPolicyTest {

    @Test
    fun `own app dormant config disables events feedback flags and isolates package filter`() {
        val cfg = WritingGuardServiceInfoPolicy.resolve(inOwnApp = true)

        assertEquals(0, cfg.eventTypes)
        assertEquals(0, cfg.feedbackType)
        assertEquals(0, cfg.flags)
        assertEquals(
            "Zero feedbackType must not match FEEDBACK_ALL_MASK so Compose disables its 100ms accessibility loop inside ITV",
            0,
            cfg.feedbackType and AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        assertEquals(listOf(WritingGuardServiceInfoPolicy.DORMANT_NOOP_PACKAGE), cfg.packageNames)
        assertTrue(cfg.notificationTimeoutMs >= 500L)
    }

    @Test
    fun `external app config restores minimal 5 event types generic feedback window flags and all packages`() {
        val cfg = WritingGuardServiceInfoPolicy.resolve(inOwnApp = false)

        val expectedEvents = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        assertEquals(expectedEvents, cfg.eventTypes)
        assertEquals(AccessibilityServiceInfo.FEEDBACK_GENERIC, cfg.feedbackType)
        assertTrue((cfg.feedbackType and AccessibilityServiceInfo.FEEDBACK_ALL_MASK) != 0)
        assertEquals(
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS,
            cfg.flags
        )
        assertNull("External mode must observe all packages", cfg.packageNames)
        assertEquals(250L, cfg.notificationTimeoutMs)
    }
}
