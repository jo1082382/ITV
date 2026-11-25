package com.example.itv.domain.coaching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class InterventionBubbleFormatterTest {

    @Test
    fun toBadge_convertsBedtimeAndDarknessToCompactAlertChip() {
        val badge = InterventionBubbleFormatter.toBadge(
            "평소 취침보다 35분 늦었고, 어두운 곳에서 보고 있어!"
        )
        assertEquals("🌙 취침 +35분·암실", badge.text)
        assertTrue(badge.isAlert)
    }

    @Test
    fun toBadge_convertsContentWatchMinutesToCompactInfoChip() {
        val reels = InterventionBubbleFormatter.toBadge("Instagram 릴스를 22분째 계속 보고 있어!")
        assertEquals("🎬 릴스 22분", reels.text)
        assertFalse(reels.isAlert)

        val shorts = InterventionBubbleFormatter.toBadge("YouTube 쇼츠를 14분째 계속 보고 있어!")
        assertEquals("⚡ 쇼츠 14분", shorts.text)
        assertFalse(shorts.isAlert)

        val longform = InterventionBubbleFormatter.toBadge("유튜브 영상을 연속 36분째 시청 중이야!")
        assertEquals("📺 영상 36분", longform.text)
        assertFalse(longform.isAlert)
    }

    @Test
    fun toBadge_convertsOverheatAndSessionExcessToAlertChip() {
        val avg = InterventionBubbleFormatter.toBadge("평소 평균(15분)보다 7분 더 오래 보고 있어!")
        assertEquals("📈 평균 +7분", avg.text)
        assertTrue(avg.isAlert)

        val chain = InterventionBubbleFormatter.toBadge("영상을 3편째 연달아 넘겨 보고 있어!")
        assertEquals("🔁 영상 3편 연속", chain.text)
        assertTrue(chain.isAlert)

        val spm = InterventionBubbleFormatter.toBadge("분당 13회 속도로 영상을 빠르게 넘기고 있어!")
        assertEquals("🔥 분당 13회 넘김", spm.text)
        assertTrue(spm.isAlert)
    }

    @Test
    fun buildOfferBubble_producesMaxTwoBadgesAndShortHeadlineAcrossAllIntensitiesAndTones() {
        val reasons = listOf(
            "평소 취침보다 35분 늦었고, 어두운 곳에서 보고 있어!",
            "Instagram 릴스를 22분째 계속 보고 있어!",
            "평소 평균(15분)보다 7분 더 오래 보고 있어!"
        )
        for (intensity in CharacterIntensity.entries) {
            for (tone in CoachingTone.entries) {
                val bubble = InterventionBubbleFormatter.buildOfferBubble(
                    intensity = intensity,
                    reasons = reasons,
                    tone = tone,
                    random = Random(7)
                )
                assertEquals(2, bubble.badges.size)
                assertTrue("Headline should be concise (<=18 chars): ${bubble.headline}", bubble.headline.length <= 18)
                assertNotNull(bubble.subline)
                assertTrue("Subline should be concise (<=20 chars): ${bubble.subline}", bubble.subline!!.length <= 20)
            }
        }
    }

    @Test
    fun toBadge_convertsMultiAppAndExtraCategoriesAndPreBedtimeDark() {
        val multi = InterventionBubbleFormatter.toBadge("여러 앱을 쉬지 않고 연속 39분째 보고 있어!")
        assertEquals("📱 여러 앱 39분", multi.text)
        assertFalse(multi.isAlert)

        val feed = InterventionBubbleFormatter.toBadge("피드·SNS를 연속 20분째 둘러보고 있어!")
        assertEquals("📱 피드 20분", feed.text)
        assertFalse(feed.isAlert)

        val msg = InterventionBubbleFormatter.toBadge("메신저·문서를 연속 18분째 보고 있어!")
        assertEquals("💬 메신저 18분", msg.text)
        assertFalse(msg.isAlert)

        val web = InterventionBubbleFormatter.toBadge("웹·화면을 연속 24분째 보고 있어!")
        assertEquals("🌐 웹 24분", web.text)
        assertFalse(web.isAlert)

        val preBedDark = InterventionBubbleFormatter.toBadge("취침 35분 전인데 어두운 방에서 밝은 화면을 보고 있어 눈이 지쳐!")
        assertEquals("🌙 취침 35분전·암실", preBedDark.text)
        assertTrue(preBedDark.isAlert)

        val preBedDarkNoMin = InterventionBubbleFormatter.toBadge("취침 전 어두운 곳에서 화면을 보고 있어 눈이 피로해!")
        assertEquals("🌙 취침 전·암실", preBedDarkNoMin.text)
        assertTrue(preBedDarkNoMin.isAlert)
    }
}
