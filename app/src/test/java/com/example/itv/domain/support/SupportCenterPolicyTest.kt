package com.example.itv.domain.support

import com.example.itv.infrastructure.support.SupportFirestoreRepository
import com.example.itv.infrastructure.support.SupportInquiryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportCenterPolicyTest {

    @Test
    fun `faq filter returns all items for ALL category and blank query`() {
        val result = SupportFaqCatalog.filter(FaqCategory.ALL, "   ")
        assertEquals(SupportFaqCatalog.items.size, result.size)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `faq filter narrows by category`() {
        val permItems = SupportFaqCatalog.filter(FaqCategory.PERMISSIONS, "")
        assertTrue(permItems.isNotEmpty())
        assertTrue(permItems.all { it.category == FaqCategory.PERMISSIONS })
    }

    @Test
    fun `faq filter matches keywords and ignores surrounding whitespace`() {
        val shadowItems = SupportFaqCatalog.filter(FaqCategory.ALL, "  그림자  ")
        assertTrue(shadowItems.any { it.id == "faq_coaching_dark_room" })

        val lagItems = SupportFaqCatalog.filter(FaqCategory.PERMISSIONS, "렉")
        assertTrue(lagItems.any { it.id == "faq_perm_lag" })
    }

    @Test
    fun `inquiry validator rejects blank or too-short inputs and trims valid inputs`() {
        val shortTitle = SupportInquiryValidator.validate("a", "충분히 긴 문의 내용입니다.")
        assertTrue(shortTitle is SupportInquiryValidationResult.Invalid)

        val shortBody = SupportInquiryValidator.validate("정상 제목", "짧음")
        assertTrue(shortBody is SupportInquiryValidationResult.Invalid)

        val valid = SupportInquiryValidator.validate("  오류 제보  ", "  상세 설명을 작성했습니다.  ")
        assertTrue(valid is SupportInquiryValidationResult.Valid)
        valid as SupportInquiryValidationResult.Valid
        assertEquals("오류 제보", valid.normalizedTitle)
        assertEquals("상세 설명을 작성했습니다.", valid.normalizedContent)
    }

    @Test
    fun `reply validator enforces non-blank and max length limits`() {
        assertTrue(SupportReplyValidator.validate("   ") is SupportReplyValidationResult.Invalid)
        assertTrue(SupportReplyValidator.validate("a".repeat(2001)) is SupportReplyValidationResult.Invalid)

        val valid = SupportReplyValidator.validate("  확인 후 수정 반영했습니다.  ")
        assertTrue(valid is SupportReplyValidationResult.Valid)
        valid as SupportReplyValidationResult.Valid
        assertEquals("확인 후 수정 반영했습니다.", valid.normalizedReply)
    }

    @Test
    fun `inquiry unread reply flag activates only when answered with non-blank reply and unread`() {
        val received = SupportInquiry(
            id = "1",
            category = InquiryCategory.BUG,
            title = "테스트",
            content = "테스트 내용입니다",
            createdAtEpochMs = 1000L,
            status = InquiryStatus.RECEIVED,
            adminReply = null,
            isReplyRead = false,
        )
        assertFalse(received.hasUnreadReply)

        val answeredUnread = received.copy(
            status = InquiryStatus.ANSWERED,
            adminReply = "확인 후 수정했습니다.",
            isReplyRead = false,
        )
        assertTrue(answeredUnread.hasUnreadReply)

        val answeredRead = answeredUnread.copy(isReplyRead = true)
        assertFalse(answeredRead.hasUnreadReply)
    }

    @Test
    fun `diagnostic snapshot formats human-readable Korean summary without PII`() {
        val snap = SupportDiagnosticSnapshot(
            appVersion = "1.2.3",
            androidVersion = "Android 15 (API 35)",
            deviceModel = "samsung SM-S928N",
            usageGranted = true,
            overlayGranted = false,
            accessibilityConnected = true,
            notificationsEnabled = true,
        )
        val text = snap.toSummaryText()
        assertTrue(text.contains("앱 버전: 1.2.3"))
        assertTrue(text.contains("사용정보(허용)"))
        assertTrue(text.contains("오버레이(미허용)"))
        assertTrue(text.contains("접근성(허용)"))
        assertTrue(text.contains("알림(허용)"))
    }

    @Test
    fun `mergeRemoteAndLocal keeps authoritative remote answers and unsynced offline drafts`() {
        val remoteAnswered = SupportInquiry(
            id = "inq_1",
            category = InquiryCategory.BUG,
            title = "서버 동기화 문의",
            content = "내용입니다",
            createdAtEpochMs = 1_700_000_000_000L,
            status = InquiryStatus.ANSWERED,
            adminReply = "답변 완료",
            repliedAtEpochMs = 1_700_000_010_000L,
            isReplyRead = false,
            syncedToCloud = true,
        )
        val staleLocalVersion = remoteAnswered.copy(
            status = InquiryStatus.RECEIVED,
            adminReply = null,
            isReplyRead = true,
            syncedToCloud = true,
        )
        val offlineDraft = SupportInquiry(
            id = "inq_2",
            category = InquiryCategory.SUGGESTION,
            title = "오프라인 임시 저장",
            content = "네트워크 없을 때 작성",
            createdAtEpochMs = 1_700_000_020_000L,
            syncedToCloud = false,
        )

        val merged = SupportInquiryStore.mergeRemoteAndLocal(
            remote = listOf(remoteAnswered),
            local = listOf(staleLocalVersion, offlineDraft),
        )
        assertEquals(2, merged.size)
        assertEquals("inq_2", merged[0].id)
        assertFalse(merged[0].syncedToCloud)
        assertEquals("inq_1", merged[1].id)
        assertEquals(InquiryStatus.ANSWERED, merged[1].status)
        assertEquals("답변 완료", merged[1].adminReply)
        assertTrue(merged[1].hasUnreadReply)
    }

    @Test
    fun `firestore create payload matches strict schema and initial invariants`() {
        val inquiry = SupportInquiry(
            id = "inq_1700000000000_abc123",
            category = InquiryCategory.USAGE_PERMISSION,
            title = "권한 설정 문의",
            content = "접근성 설정이 유지되지 않아요.",
            createdAtEpochMs = 1_700_000_000_000L,
            status = InquiryStatus.ANSWERED, // intentionally spoofed to verify create map forces RECEIVED
            adminReply = "spoofed",
            adminUid = "spoofed_admin",
            repliedAtEpochMs = 1_700_000_005_000L,
            isReplyRead = false,
            uid = "user_uid_123",
            authorProvider = "KAKAO",
            authorLabel = "강아지주인",
            diagnostics = SupportDiagnosticSnapshot(
                appVersion = "1.0",
                androidVersion = "Android 15 (API 35)",
                deviceModel = "Pixel",
                usageGranted = true,
                overlayGranted = true,
                accessibilityConnected = false,
                notificationsEnabled = true,
            ),
        )

        val map = SupportFirestoreRepository.toCreateFirestoreMap(inquiry)
        val expectedKeys = setOf(
            "uid",
            "authorProvider",
            "authorLabel",
            "category",
            "title",
            "content",
            "diagnostics",
            "status",
            "adminReply",
            "adminUid",
            "createdAt",
            "repliedAt",
            "updatedAt",
            "isReplyRead",
        )
        assertEquals(expectedKeys, map.keys)
        assertEquals("RECEIVED", map["status"])
        assertNull(map["adminReply"])
        assertNull(map["adminUid"])
        assertNull(map["repliedAt"])
        assertEquals(true, map["isReplyRead"])
        assertEquals("user_uid_123", map["uid"])
        assertEquals("KAKAO", map["authorProvider"])
    }
}
