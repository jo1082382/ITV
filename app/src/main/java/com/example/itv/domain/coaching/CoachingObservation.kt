package com.example.itv.domain.coaching

internal data class CoachingObservation(
    val using: Boolean,
    val guardReason: String?,
    val youtubeState: Int,
    val reelsState: Int,
    val youtubeLongformWatching: Boolean = false,
    val generalAppDeferredReason: String? = null
) {
    /**
     * 사용자가 실제로 키보드를 띄우거나 입력란에 포커스를 두고 글을 작성 중인지 여부.
     * 스크린샷 오버레이, 알림, 볼륨바 등의 일시적 시스템 방해 요소로 인한 감지 제한("전면 앱 확인 불가", "화면 구조 확인 불가" 등)은
     * 메시지 작성 중이 아니므로 false를 반환합니다.
     */
    fun isActivelyWriting(): Boolean {
        val reason = guardReason ?: return false
        return reason.contains("키보드") ||
               reason.contains("입력란") ||
               reason.contains("포커스") ||
               reason.contains("메신저") ||
               reason.contains("Instagram DM") ||
               reason.contains("작성 중")
    }
}
