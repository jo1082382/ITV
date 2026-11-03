package com.example.itv.composition

import android.content.Context
import com.example.itv.application.records.ShortformRecordService
import com.example.itv.infrastructure.records.AndroidShortformRecordSource

fun shortformRecordService(context: Context): ShortformRecordService =
    ShortformRecordService(AndroidShortformRecordSource(context, consentStore(context)))
