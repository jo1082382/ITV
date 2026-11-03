package com.example.itv.composition

import android.content.Context
import com.example.itv.application.records.ReelsDiagnosticService
import com.example.itv.infrastructure.records.AndroidReelsDiagnosticPort

internal fun reelsDiagnosticService(context: Context): ReelsDiagnosticService =
    ReelsDiagnosticService(AndroidReelsDiagnosticPort(context))
