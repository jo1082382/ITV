package com.example.itv.composition

import android.content.Context
import com.example.itv.application.records.ShortformPersistenceService
import com.example.itv.infrastructure.records.RoomShortformPersistence

// Current app owner, supplied at one assembly point. Account switching is not enabled here.
internal fun shortformPersistenceService(context: Context): ShortformPersistenceService =
    ShortformPersistenceService(RoomShortformPersistence(context, "demo-guest"))
