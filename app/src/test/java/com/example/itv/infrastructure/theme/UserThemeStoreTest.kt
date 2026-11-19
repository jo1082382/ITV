package com.example.itv.infrastructure.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserThemeStoreTest {

    @Test
    fun darkModeStateFlowUpdatesCorrectly() {
        UserThemeStore.setDarkModeForTesting(false)
        assertFalse(UserThemeStore.darkModeState.value)

        UserThemeStore.setDarkModeForTesting(true)
        assertTrue(UserThemeStore.darkModeState.value)

        UserThemeStore.setDarkModeForTesting(false)
        assertFalse(UserThemeStore.darkModeState.value)
    }
}
