package com.example.itv.domain.coaching

import java.time.LocalDate

/**
 * Pure domain snapshot of a single day's archived metrics used to generate weekly coach letters.
 * Decoupled from Android / storage classes so weekly analysis is 100% unit-testable.
 */
internal data class WeeklyDaySnapshot(
    val date: LocalDate,
    val totalPhoneMs: Long,
    val shortformMs: Long,
    val shortformSwipes: Long = 0L,
    val bedtimeMinute: Int? = null,
    val wakeupMinute: Int? = null,
    val sleepDurationMinutes: Double? = null,
    val preSleepUsageMinutes: Double? = null,
    val preSleepShortformMinutes: Double? = null,
    val phoneSessionCount: Int = 0,
    val phoneAvgSessionMs: Long? = null,
    val shortformSessionCount: Int = 0,
    val shortformAvgSessionMs: Long? = null,
    val shortformMaxSessionMs: Long? = null,
    val shortformSpm: Double? = null,
    val hourlyPhoneMinutes: List<Double> = emptyList(),
    val topApps: List<WeeklyAppStat> = emptyList(),
    val isRestDay: Boolean = false,
)

internal data class WeeklyAppStat(
    val packageName: String,
    val label: String,
    val minutes: Double,
)

internal enum class WeeklyPatternCategory(val label: String, val badgeEmoji: String) {
    TIME_RHYTHM("시간대 리듬", "🕰️"),
    SHORTFORM_HABIT("숏폼 패턴", "🎬"),
    SLEEP_CONNECTION("밤과 수면", "🌙"),
    DAY_TYPE_BALANCE("일과 · 쉬는 날", "🌿"),
    TOP_APPS("자주 머문 앱", "📱"),
}

internal data class WeeklyImprovementHighlight(
    val id: String,
    val title: String,
    val badgeText: String,
    val detail: String,
)

internal data class WeeklyPatternDiscovery(
    val id: String,
    val category: WeeklyPatternCategory,
    val title: String,
    val detail: String,
    val highlightTag: String? = null,
)

internal data class WeeklyComparisonStats(
    val activeDays: Int,
    val avgDailyPhoneMinutes: Int,
    val prevAvgDailyPhoneMinutes: Int?,
    val avgDailyShortformMinutes: Int,
    val prevAvgDailyShortformMinutes: Int?,
    val avgBedtimeMinute: Int?,
    val avgSleepMinutes: Int?,
    val savedWeeklyMinutes: Int?,
    val reclaimedTimeExample: String?,
)

/**
 * A weekly life-coach letter delivered every Monday for the just-completed Mon~Sun week.
 * Intentionally avoids judgmental scores or letter grades, focusing on concrete wins vs. last week,
 * lifestyle pattern discoveries, and one light experiment for the upcoming week.
 */
internal data class WeeklyCoachLetter(
    val id: String, // "week-yyyy-MM-dd" (Monday of the reviewed week)
    val weekStartDate: LocalDate,
    val weekEndDate: LocalDate,
    val deliveredMondayDate: LocalDate,
    val title: String,
    val previewTeaser: String,
    val greeting: String,
    val improvements: List<WeeklyImprovementHighlight>,
    val discoveries: List<WeeklyPatternDiscovery>,
    val coachTipTitle: String,
    val coachTipBody: String,
    val stats: WeeklyComparisonStats,
    val deliveredAtMs: Long,
    val isRead: Boolean = false,
    val isPreview: Boolean = false,
)

internal enum class MailboxTab(val label: String) {
    PERSONAL("개인"),
    NOTICE("공지"),
}

internal enum class MailboxNoticeCategory(val label: String, val emoji: String) {
    UPDATE("업데이트", "🛠️"),
    EVENT("이벤트", "🎉"),
    SURVEY("설문·보상", "📝"),
    SYSTEM("안내", "📢"),
}

/**
 * Notice / announcement item displayed inside the Mailbox's "공지" tab
 * (system updates, events, surveys, general announcements).
 */
internal data class MailboxNotice(
    val id: String,
    val category: MailboxNoticeCategory,
    val title: String,
    val body: String,
    val publishedDate: LocalDate,
    val isPinned: Boolean = false,
    val isRead: Boolean = false,
)
