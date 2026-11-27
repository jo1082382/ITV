package com.example.itv.domain.support

/**
 * Customer Center top-level tabs inside [com.example.itv.presentation.support.SupportCenterScreen].
 */
enum class SupportTab(val label: String) {
    FAQ("자주 묻는 질문"),
    INQUIRY("1:1 문의"),
    HISTORY("내 문의 내역"),
}

/**
 * Filter categories for the FAQ tab.
 */
enum class FaqCategory(val label: String, val emoji: String) {
    ALL("전체", "✨"),
    PERMISSIONS("권한·접근성", "🔐"),
    COACHING("코칭·개입", "🐶"),
    SLEEP_CALENDAR("수면·캘린더", "🌙"),
    REWARDS("도장·우편함", "✉️"),
    ACCOUNT("계정·백업", "👤"),
}

data class FaqItem(
    val id: String,
    val category: FaqCategory,
    val question: String,
    val answer: String,
    val keywords: List<String> = emptyList(),
)

/**
 * Inquiry categories selectable by users in the 1:1 Inquiry form.
 */
enum class InquiryCategory(val label: String, val emoji: String) {
    BUG("오류 제보", "🐛"),
    SUGGESTION("기능 제안", "💡"),
    USAGE_PERMISSION("권한·사용법", "🛠️"),
    ACCOUNT_DATA("계정·데이터", "🔐"),
    OTHER("기타 문의", "💬"),
}

/**
 * Lifecycle status of a user-submitted inquiry.
 * Ready to map cleanly to remote Firestore statuses in a follow-up step.
 */
enum class InquiryStatus(val label: String) {
    RECEIVED("접수됨"),
    ANSWERED("답변 완료"),
}

/**
 * Optional, privacy-safe device/permission snapshot attached to an inquiry when the user opts in.
 * Contains zero PII, zero screen content, and zero app usage logs.
 */
data class SupportDiagnosticSnapshot(
    val appVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    val usageGranted: Boolean,
    val overlayGranted: Boolean,
    val accessibilityConnected: Boolean,
    val notificationsEnabled: Boolean,
) {
    fun toSummaryText(): String {
        fun flag(value: Boolean) = if (value) "허용" else "미허용"
        return buildString {
            appendLine("앱 버전: $appVersion")
            appendLine("Android: $androidVersion · 기기: $deviceModel")
            append("권한 상태: 사용정보(${flag(usageGranted)}) · 오버레이(${flag(overlayGranted)}) · ")
            append("접근성(${flag(accessibilityConnected)}) · 알림(${flag(notificationsEnabled)})")
        }
    }
}

/**
 * Represents a single 1:1 customer inquiry and its optional admin reply.
 */
data class SupportInquiry(
    val id: String,
    val category: InquiryCategory,
    val title: String,
    val content: String,
    val createdAtEpochMs: Long,
    val status: InquiryStatus = InquiryStatus.RECEIVED,
    val diagnostics: SupportDiagnosticSnapshot? = null,
    val adminReply: String? = null,
    val repliedAtEpochMs: Long? = null,
    val isReplyRead: Boolean = true,
    val uid: String = "",
    val authorProvider: String = "GUEST",
    val authorLabel: String = "사용자",
    val adminUid: String? = null,
    val updatedAtEpochMs: Long = createdAtEpochMs,
    val syncedToCloud: Boolean = false,
) {
    val hasUnreadReply: Boolean
        get() = status == InquiryStatus.ANSWERED && !adminReply.isNullOrBlank() && !isReplyRead
}

/**
 * Filter tabs inside the in-app Admin Support Center screen.
 */
enum class AdminInquiryFilter(val label: String) {
    PENDING("답변 대기"),
    ALL("전체"),
    ANSWERED("답변 완료"),
}

sealed interface SupportInquiryValidationResult {
    data class Valid(val normalizedTitle: String, val normalizedContent: String) : SupportInquiryValidationResult
    data class Invalid(val message: String) : SupportInquiryValidationResult
}

object SupportInquiryValidator {
    const val MIN_TITLE_LENGTH = 2
    const val MAX_TITLE_LENGTH = 80
    const val MIN_CONTENT_LENGTH = 5
    const val MAX_CONTENT_LENGTH = 1000

    fun validate(rawTitle: String, rawContent: String): SupportInquiryValidationResult {
        val title = rawTitle.trim()
        val content = rawContent.trim()
        return when {
            title.length < MIN_TITLE_LENGTH ->
                SupportInquiryValidationResult.Invalid("제목을 ${MIN_TITLE_LENGTH}자 이상 입력해 주세요.")
            title.length > MAX_TITLE_LENGTH ->
                SupportInquiryValidationResult.Invalid("제목은 ${MAX_TITLE_LENGTH}자 이하로 입력해 주세요.")
            content.length < MIN_CONTENT_LENGTH ->
                SupportInquiryValidationResult.Invalid("문의 내용을 ${MIN_CONTENT_LENGTH}자 이상 입력해 주세요.")
            content.length > MAX_CONTENT_LENGTH ->
                SupportInquiryValidationResult.Invalid("문의 내용은 ${MAX_CONTENT_LENGTH}자 이하로 입력해 주세요.")
            else -> SupportInquiryValidationResult.Valid(title, content)
        }
    }
}

sealed interface SupportReplyValidationResult {
    data class Valid(val normalizedReply: String) : SupportReplyValidationResult
    data class Invalid(val message: String) : SupportReplyValidationResult
}

object SupportReplyValidator {
    const val MIN_REPLY_LENGTH = 1
    const val MAX_REPLY_LENGTH = 2000

    fun validate(rawReply: String): SupportReplyValidationResult {
        val reply = rawReply.trim()
        return when {
            reply.length < MIN_REPLY_LENGTH ->
                SupportReplyValidationResult.Invalid("답변 내용을 입력해 주세요.")
            reply.length > MAX_REPLY_LENGTH ->
                SupportReplyValidationResult.Invalid("답변은 ${MAX_REPLY_LENGTH}자 이하로 입력해 주세요.")
            else -> SupportReplyValidationResult.Valid(reply)
        }
    }
}
