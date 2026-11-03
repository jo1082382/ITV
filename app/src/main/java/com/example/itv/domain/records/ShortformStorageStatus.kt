package com.example.itv.domain.records

/** Process-local diagnostics, not persisted daily quality or durability proof. */
enum class ShortformStorageIssue { NONE, PREPARATION, SAVING, PROCESSING, OVERFLOW }
data class ShortformStorageStatus(val ready: Boolean = false, val pendingSave: Boolean = false,
    val rejectedEvents: Long = 0, val issue: ShortformStorageIssue = ShortformStorageIssue.NONE,
    val detail: String? = null)
