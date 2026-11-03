package com.example.itv.composition

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import com.example.itv.application.permissions.PermissionSettingsService
import com.example.itv.infrastructure.permissions.AndroidPermissionSettings

fun permissionSettingsService(context: Context, scope: CoroutineScope): PermissionSettingsService =
    PermissionSettingsService(AndroidPermissionSettings(context), scope)
