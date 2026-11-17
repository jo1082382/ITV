package com.example.itv.domain.coaching

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoachingSpeechCatalogTest {

    @Test
    fun eachSituationAndToneHasMultipleDistinctCandidates() {
        for (situation in SpeechSituation.entries) {
            for (tone in CoachingTone.entries) {
                val candidates = CoachingSpeechCatalog.getCandidates(
                    situation = situation,
                    tone = tone,
                    reason = "테스트 사유",
                    timeText = "10분",
                    level = 3,
                    exp = 100L,
                    points = 20L
                )
                // 각 상황/말투마다 최소 2개 이상의 대사 후보군이 있어야 함
                assertTrue(
                    "Situation $situation with tone $tone should have >= 2 candidates, but had ${candidates.size}",
                    candidates.size >= 2
                )
                // 빈 문자열이 없어야 함
                for (c in candidates) {
                    assertFalse("Candidate should not be blank in $situation / $tone", c.isBlank())
                }
            }
        }
    }

    @Test
    fun pickSpeech_returnsNonBlankString() {
        for (situation in SpeechSituation.entries) {
            for (tone in CoachingTone.entries) {
                val speech = CoachingSpeechCatalog.pickSpeech(
                    situation = situation,
                    tone = tone,
                    reason = "30분 이상 시청",
                    timeText = "5분",
                    level = 2,
                    exp = 50L,
                    points = 10L,
                    random = Random(42)
                )
                assertNotNull(speech)
                assertTrue(speech.isNotBlank())
            }
        }
    }

    @Test
    fun pickSpeech_randomVariationWorks() {
        val samples = (1..50).map { seed ->
            CoachingSpeechCatalog.pickSpeech(
                situation = SpeechSituation.GENTLE_OFFER,
                tone = CoachingTone.PLAYFUL,
                random = Random(seed)
            )
        }.toSet()

        // 50번 뽑았을 때 적어도 3개 이상의 서로 다른 대사가 나와야 함
        assertTrue("Expected variation across random seeds, but got ${samples.size}", samples.size >= 3)
    }
}
