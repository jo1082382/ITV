package com.example.itv.domain.coaching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanTextWrapUtilsTest {

    private val nbsp = KoreanTextWrapUtils.NBSP

    @Test
    fun bindsNumbersUnitsAndFollowingTimeWords() {
        assertEquals("15분${nbsp}째", KoreanTextWrapUtils.preventAwkwardWrap("15분 째"))
        assertEquals("3편${nbsp}째", KoreanTextWrapUtils.preventAwkwardWrap("3편 째"))
        assertEquals("연속${nbsp}30분", KoreanTextWrapUtils.preventAwkwardWrap("연속 30분"))
        assertEquals("30초${nbsp}뒤에 다시 알릴게요${nbsp}🌿", KoreanTextWrapUtils.preventAwkwardWrap("30초 뒤에 다시 알릴게요 🌿"))
        assertEquals("10초${nbsp}내 화면을 끄세요", KoreanTextWrapUtils.preventAwkwardWrap("10초 내 화면을 끄세요"))
    }

    @Test
    fun bindsBadgeIconsAndBrandPhrases() {
        val bedtimeBadge = KoreanTextWrapUtils.preventAwkwardWrap("🌙 취침 +35분·암실")
        assertEquals("🌙${nbsp}취침${nbsp}+35분·암실", bedtimeBadge)

        val preBedBadge = KoreanTextWrapUtils.preventAwkwardWrap("🛌 취침 40분 전")
        assertEquals("🛌${nbsp}취침${nbsp}40분${nbsp}전", preBedBadge)

        val shortsLine = KoreanTextWrapUtils.preventAwkwardWrap("YouTube 쇼츠를 연속 15분 째 보고 있어!")
        assertTrue(shortsLine.contains("YouTube${nbsp}쇼츠"))
        assertTrue(shortsLine.contains("연속${nbsp}15분${nbsp}째"))
    }

    @Test
    fun preventsTrailingOrphanEmojiAndPreservesNewlines() {
        val headline = KoreanTextWrapUtils.preventAwkwardWrap("잠깐 쉬어가자 🌿")
        assertEquals("잠깐 쉬어가자${nbsp}🌿", headline)

        val subline = KoreanTextWrapUtils.preventAwkwardWrap("눈 피로가 커져요 · 터치해 쉬기")
        assertTrue(subline.contains("눈${nbsp}피로가"))
        assertTrue(subline.contains("·${nbsp}터치해${nbsp}쉬기"))

        val multiLine = KoreanTextWrapUtils.preventAwkwardWrap("집: 쉬기\n좌우·아래 가장자리: 나중에\n철창: 닫기")
        assertEquals(3, multiLine.split('\n').size)
        assertFalse(multiLine.contains("\n$nbsp"))
    }

    @Test
    fun isIdempotentOnRepeatedCalls() {
        val samples = listOf(
            "⏰ 취침 +25분",
            "어두운데 화면이 밝아요 👀",
            "눈 피로가 커져요 · 터치해 쉬기",
            "Instagram 릴스 22분",
            "영상을 3편 째 연달아 보고 있어!"
        )
        for (sample in samples) {
            val once = KoreanTextWrapUtils.preventAwkwardWrap(sample)
            val twice = KoreanTextWrapUtils.preventAwkwardWrap(once)
            assertEquals(once, twice)
        }
    }
}
