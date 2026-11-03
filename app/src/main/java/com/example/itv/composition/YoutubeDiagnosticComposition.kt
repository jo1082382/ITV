package com.example.itv.composition

import android.content.Context
import com.example.itv.application.records.YoutubeDiagnosticService
import com.example.itv.infrastructure.records.AndroidYoutubeDiagnosticPort

internal fun youtubeDiagnosticService(context: Context): YoutubeDiagnosticService =
    YoutubeDiagnosticService(AndroidYoutubeDiagnosticPort(consentStore(context)))
