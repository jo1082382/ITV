package com.example.itv.infrastructure.coaching

import android.content.Context
import android.provider.Settings
import com.example.itv.domain.coaching.CoachingAccessSource

internal class AndroidCoachingAccess(context: Context): CoachingAccessSource {
    private val context: Context = context.applicationContext
    override fun consent(): Boolean = CoachingConsentStore.granted(context)
    override fun overlay(): Boolean = Settings.canDrawOverlays(context)
    override fun notifications(): Boolean = CoachingNotifications.allowed(context)
}
