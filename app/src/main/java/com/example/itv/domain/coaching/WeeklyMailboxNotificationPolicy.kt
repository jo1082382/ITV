package com.example.itv.domain.coaching

/**
 * Pure domain policy deciding whether a pending Monday weekly-coach-letter notification may be
 * displayed right now.
 *
 * Mirrors ITV's strict non-interruptibility principles:
 * - Never notifies during sleep/night hours (`00:00..07:59`), screen-off, or lock screen.
 * - Never notifies while the user is inside ITV, actively typing, on a call, using a messenger,
 *   using a finance/banking app, editing documents, playing an active game, watching active video,
 *   or while another coaching overlay/mission is active.
 * - Only delivers when the user is in an explicitly interruptible moment (eligible browsing or
 *   returning to the Home launcher).
 */
internal data class WeeklyMailboxNotificationContext(
    val isScreenInteractive: Boolean,
    val isDeviceLocked: Boolean,
    val localHour: Int,
    val isInOwnApp: Boolean,
    val isCoachingOverlayOrMissionActive: Boolean,
    val isWritingActive: Boolean,
    val isProtectedContext: Boolean,
    val isInterruptibleNow: Boolean,
)

internal data class WeeklyMailboxNotificationDecision(
    val canNotify: Boolean,
    val reason: String,
)

internal object WeeklyMailboxNotificationPolicy {

    const val EARLIEST_NOTIFICATION_HOUR = 8

    fun evaluate(ctx: WeeklyMailboxNotificationContext): WeeklyMailboxNotificationDecision {
        return when {
            !ctx.isScreenInteractive ->
                WeeklyMailboxNotificationDecision(false, "SCREEN_OFF")
            ctx.isDeviceLocked ->
                WeeklyMailboxNotificationDecision(false, "DEVICE_LOCKED")
            ctx.localHour < EARLIEST_NOTIFICATION_HOUR ->
                WeeklyMailboxNotificationDecision(false, "QUIET_MORNING_HOURS")
            ctx.isInOwnApp ->
                WeeklyMailboxNotificationDecision(false, "IN_OWN_APP")
            ctx.isCoachingOverlayOrMissionActive ->
                WeeklyMailboxNotificationDecision(false, "COACHING_ACTIVE")
            ctx.isWritingActive ->
                WeeklyMailboxNotificationDecision(false, "WRITING_ACTIVE")
            ctx.isProtectedContext ->
                WeeklyMailboxNotificationDecision(false, "PROTECTED_APP_OR_ACTIVITY")
            !ctx.isInterruptibleNow ->
                WeeklyMailboxNotificationDecision(false, "NOT_INTERRUPTIBLE_MOMENT")
            else ->
                WeeklyMailboxNotificationDecision(true, "ELIGIBLE_MOMENT")
        }
    }
}
