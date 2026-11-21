package com.example.itv.domain.coaching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UserCoachingSettingsTest {

    @Test
    fun defaultSettings_hasSensibleDefaults() {
        val settings = UserCoachingSettings()
        assertEquals(InterventionType.CHARACTER_OVERLAY, settings.interventionType)
        assertEquals(CoachingIntensity.NORMAL, settings.coachingIntensity)
        assertEquals(CharacterIntensity.NORMAL, settings.characterIntensity)
        assertEquals(CoachingTone.SOFT, settings.tone)
        assertEquals(true, settings.bedtimeCareEnabled)
    }

    @Test
    fun interventionTypes_haveTitlesAndDescriptions() {
        InterventionType.entries.forEach { type ->
            assertNotNull(type.title)
            assertNotNull(type.description)
        }
    }

    @Test
    fun coachingIntensities_coverThreeLevels() {
        assertEquals(3, CoachingIntensity.entries.size)
        assertEquals(listOf("약하게", "보통", "강하게"), CoachingIntensity.entries.map { it.title })
    }

    @Test
    fun characterIntensities_coverThreeLevels() {
        assertEquals(3, CharacterIntensity.entries.size)
        assertEquals(listOf("약하게", "보통", "강하게"), CharacterIntensity.entries.map { it.title })
        CharacterIntensity.entries.forEach { intensity ->
            assertNotNull(intensity.subtitle)
            assertNotNull(intensity.description)
        }
    }

    @Test
    fun tones_coverThreeTonesWithDialogs() {
        assertEquals(3, CoachingTone.entries.size)
        assertEquals(listOf("부드럽게", "장난스럽게", "강하게"), CoachingTone.entries.map { it.title })
        CoachingTone.entries.forEach { tone ->
            assert(tone.sampleDialog.isNotBlank())
        }
    }
}
