package com.example.itv.composition

import android.content.Context
import com.example.itv.application.records.ShortformObservationService
import com.example.itv.infrastructure.records.AndroidShortformObservationSink

internal fun shortformObservationService(context: Context): ShortformObservationService =
    ShortformObservationService(AndroidShortformObservationSink(context))
