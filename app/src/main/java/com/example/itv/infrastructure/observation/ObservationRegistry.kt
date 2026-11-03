package com.example.itv.infrastructure.observation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe

/** Main-thread hooks. Do not retain AccessibilityEvent or UI nodes in implementations. */
internal interface ObservationModule {
    fun onOwnApp() {}
    fun onEvent(event: AccessibilityEvent?) {}
    fun beforeWritingCheck(service: AccessibilityService) {}
    fun afterWritingCheck(service: AccessibilityService) {}
}

/** Explicit registration point for future observers; order is intentional. */
internal class ObservationRegistry {
    private val modules: List<ObservationModule> = listOf(
        object : ObservationModule {
            override fun onOwnApp() = ReelsProbe.onOwnApp()
            override fun onEvent(event: AccessibilityEvent?) = ReelsProbe.event(event)
            override fun beforeWritingCheck(service: AccessibilityService) = ReelsProbe.tick(service)
        },
        object : ObservationModule {
            override fun onOwnApp() = YoutubeProbe.onOwnApp()
            override fun afterWritingCheck(service: AccessibilityService) = YoutubeProbe.tick(service)
        }
    )
    fun onOwnApp(): Unit { modules.forEach { it.onOwnApp() } }
    fun onEvent(event: AccessibilityEvent?): Unit { modules.forEach { it.onEvent(event) } }
    fun beforeWritingCheck(service: AccessibilityService): Unit { modules.forEach { it.beforeWritingCheck(service) } }
    fun afterWritingCheck(service: AccessibilityService): Unit { modules.forEach { it.afterWritingCheck(service) } }
}
