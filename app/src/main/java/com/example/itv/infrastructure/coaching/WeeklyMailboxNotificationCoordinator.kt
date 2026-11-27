package com.example.itv.infrastructure.coaching

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.itv.MainActivity
import com.example.itv.R
import com.example.itv.domain.coaching.WeeklyCoachLetter
import com.example.itv.domain.coaching.WeeklyMailboxNotificationContext
import com.example.itv.domain.coaching.WeeklyMailboxNotificationPolicy
import com.example.itv.domain.jev.JevSettings
import com.example.itv.infrastructure.observation.GeneralAppTracker
import com.example.itv.infrastructure.observation.InferredAppCategory
import com.example.itv.infrastructure.observation.InterventionEligibility
import com.example.itv.infrastructure.observation.accessibility.WritingGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight in-process navigation signal used when the user taps a weekly-letter notification
 * so [com.example.itv.presentation.navigation.ItvMainScreen] immediately switches to Home and opens
 * the Mailbox modal (works cleanly for both cold start and warm `onNewIntent`).
 */
internal object MailboxNavigationBus {
    private val _openMailboxNonce = MutableStateFlow(0)
    val openMailboxNonce: StateFlow<Int> = _openMailboxNonce.asStateFlow()

    fun requestOpenMailbox() {
        _openMailboxNonce.value = _openMailboxNonce.value + 1
    }
}

/**
 * Context-aware on-device coordinator for Monday weekly coach letter notifications.
 *
 * Resource & data footprint:
 * - Zero network requests & zero extra background polling loops (piggies on the already-running
 *   [com.example.itv.presentation.coaching.CoachingMonitorService] tick with a 20-second throttle
 *   and an O(1) in-memory fast-path when no unread letter awaits notification).
 * - Never interrupts the user during messenger, finance/banking, calls, typing, active gameplay,
 *   active video playback, lock screen, or quiet morning hours (`00:00..07:59`).
 */
internal object WeeklyMailboxNotificationCoordinator {

    private const val PREF_NAME = "weekly_mailbox_notif_v1"
    private const val KEY_NOTIFIED_IDS = "notified_letter_ids"
    private const val NOTIFICATION_ID = 42019
    private const val CHECK_THROTTLE_ELAPSED_MS = 20_000L

    @Volatile
    private var cachedNotifiedIds: Set<String>? = null

    @Volatile
    private var lastTickCheckElapsedMs: Long = 0L

    @Volatile
    private var lastDeliveryEnsureDate: LocalDate? = null

    private val ensureInFlight = AtomicBoolean(false)

    private fun notifiedIds(context: Context): Set<String> {
        cachedNotifiedIds?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_NOTIFIED_IDS, emptySet())?.toSet() ?: emptySet()
        cachedNotifiedIds = stored
        return stored
    }

    private fun recordNotifiedId(context: Context, letterId: String) {
        val appCtx = context.applicationContext
        val updated = (notifiedIds(appCtx) + letterId).toList().takeLast(32).toSet()
        cachedNotifiedIds = updated
        appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_NOTIFIED_IDS, updated)
            .apply()
    }

    fun markNotifiedAndCancelTray(context: Context, letterId: String) {
        recordNotifiedId(context, letterId)
        runCatching {
            context.applicationContext
                .getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ID)
        }
    }

    fun markRestoredLettersAsNotified(context: Context, letterIds: Collection<String>) {
        if (letterIds.isEmpty()) return
        val appCtx = context.applicationContext
        val updated = (notifiedIds(appCtx) + letterIds).toList().takeLast(32).toSet()
        cachedNotifiedIds = updated
        appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_NOTIFIED_IDS, updated)
            .apply()
    }

    fun reset(context: Context) {
        val appCtx = context.applicationContext
        cachedNotifiedIds = emptySet()
        lastTickCheckElapsedMs = 0L
        lastDeliveryEnsureDate = null
        appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        runCatching {
            appCtx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    /**
     * Called opportunistically from [com.example.itv.presentation.coaching.CoachingMonitorService]'s
     * existing tick loop. Throttled to at most once every 20 seconds and O(1) when nothing is pending.
     */
    fun onMonitorTick(
        context: Context,
        scope: CoroutineScope,
        isCoachingOverlayOrMissionActive: Boolean,
        isWritingActive: Boolean,
        currentForegroundPkg: String?,
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastTickCheckElapsedMs < CHECK_THROTTLE_ELAPSED_MS) return
        lastTickCheckElapsedMs = nowElapsed

        val appCtx = context.applicationContext
        val today = LocalDate.now()
        val nowHour = LocalTime.now().hour

        // Ensure Monday's letter is generated promptly once morning arrives (>= 08:00), at most once/day
        if (nowHour >= WeeklyMailboxNotificationPolicy.EARLIEST_NOTIFICATION_HOUR &&
            lastDeliveryEnsureDate != today &&
            ensureInFlight.compareAndSet(false, true)
        ) {
            scope.launch(Dispatchers.IO) {
                try {
                    notifiedIds(appCtx)
                    WeeklyMailboxStore.loadIfNeeded(appCtx)
                    WeeklyMailboxStore.ensureMondayLettersDelivered(appCtx, today)
                    lastDeliveryEnsureDate = today
                } catch (_: Throwable) {
                    // Best-effort; will retry on next day/resume
                } finally {
                    ensureInFlight.set(false)
                }
            }
        }

        // Fast-path: check in-memory letters StateFlow & cached IDs (zero main-thread disk I/O)
        val letters = WeeklyMailboxStore.letters.value
        if (letters.isEmpty()) return
        val knownNotified = cachedNotifiedIds
        if (knownNotified == null) {
            if (ensureInFlight.compareAndSet(false, true)) {
                scope.launch(Dispatchers.IO) {
                    try {
                        notifiedIds(appCtx)
                    } finally {
                        ensureInFlight.set(false)
                    }
                }
            }
            return
        }
        val pendingLetter = letters.firstOrNull { letter ->
            !letter.isRead && !letter.isPreview && letter.id !in knownNotified
        } ?: return

        val pm = appCtx.getSystemService(PowerManager::class.java)
        val km = appCtx.getSystemService(KeyguardManager::class.java)
        val genSnap = GeneralAppTracker.snapshot.value
        val fgPkg = currentForegroundPkg ?: genSnap.packageName
        val cat = genSnap.category

        val isProtected = cat == InferredAppCategory.FINANCE ||
            cat == InferredAppCategory.MESSENGER ||
            cat == InferredAppCategory.VOICE_CALL ||
            cat == InferredAppCategory.DOCUMENT ||
            cat == InferredAppCategory.SYSTEM ||
            GeneralAppTracker.isFinancialPackage(fgPkg, genSnap.appName) ||
            JevSettings.isDocumentOrMessagingPackage(fgPkg) ||
            JevSettings.isExcludedSystemPackage(fgPkg)

        // Allow gentle notification ONLY when GeneralAppTracker confirms an eligible casual moment
        // OR when the user has just returned to the phone's Home launcher screen.
        val isInterruptible = genSnap.eligibility == InterventionEligibility.ELIGIBLE ||
            cat == InferredAppCategory.HOME_LAUNCHER

        val evalCtx = WeeklyMailboxNotificationContext(
            isScreenInteractive = pm?.isInteractive == true,
            isDeviceLocked = km?.isKeyguardLocked == true,
            localHour = nowHour,
            isInOwnApp = WritingGuard.inAppForeground || cat == InferredAppCategory.ITV_OWN || fgPkg == appCtx.packageName,
            isCoachingOverlayOrMissionActive = isCoachingOverlayOrMissionActive,
            isWritingActive = isWritingActive || WritingGuard.blocked,
            isProtectedContext = isProtected,
            isInterruptibleNow = isInterruptible,
        )

        val decision = WeeklyMailboxNotificationPolicy.evaluate(evalCtx)
        if (!decision.canNotify) return

        if (postLetterNotification(appCtx, pendingLetter)) {
            recordNotifiedId(appCtx, pendingLetter.id)
        }
    }

    /**
     * Immediately sends a test weekly-letter notification from the Test tab so the team can verify
     * notification copy, channel behavior, and tap-to-open-mailbox routing without waiting for Monday.
     */
    fun sendTestNotification(context: Context, letter: WeeklyCoachLetter): Boolean {
        return postLetterNotification(context.applicationContext, letter)
    }

    private fun postLetterNotification(context: Context, letter: WeeklyCoachLetter): Boolean {
        CoachingNotifications.channels(context)
        if (!CoachingNotifications.mailboxAllowed(context)) return false

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(MainActivity.EXTRA_OPEN_MAILBOX, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CoachingNotifications.MAILBOX)
            .setSmallIcon(R.drawable.ic_coaching_notification)
            .setContentTitle("📬 ${letter.title} 도착!")
            .setContentText("지난 한 주의 따뜻한 코치 인사와 생활 패턴 편지가 우편함에 도착했어요.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("지난 한 주의 따뜻한 코치 인사와 생활 패턴 편지가 우편함에 도착했어요.\n눌러서 강아지 발자국 실링 편지를 열어보세요.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }
}
