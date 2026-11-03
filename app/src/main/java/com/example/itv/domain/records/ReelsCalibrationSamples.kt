package com.example.itv.domain.records

/** UI structure identifiers only; null means not registered, not an empty sample. */
internal data class ReelsCalibrationSamples(val reels: Set<String>? = null,
    val feed: Set<String>? = null, val dm: Set<String>? = null,
    val comments: Set<String>? = null)
