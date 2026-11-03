package com.example.itv.domain.coaching

/** In-memory, read-only snapshot. No screen content or persistent logs. */
internal data class DeveloperSnapshot(
    val at: Long = 0L, val running: Boolean = false,
    val usedMs: Long = 0L, val restMs: Long = 0L,
    val interventionMs: Long = 60_000L, val requiredRestMs: Long = 30_000L,
    val retryRemainingMs: Long = 0L,
    val screen: String = "관측 없음", val decision: String = "코칭 서비스 실행 관측 없음"
)
internal object DeveloperStatus {
    @Volatile var value = DeveloperSnapshot()
}
