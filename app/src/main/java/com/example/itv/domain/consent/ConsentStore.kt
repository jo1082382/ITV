package com.example.itv.domain.consent

/** Legacy guest storage contract. Calls may block; infrastructure callers use IO. */
data class ConsentState(val granted: Boolean = false, val history: List<String> = emptyList())
interface ConsentStore {
    fun read(): ConsentState
    fun setCollectionConsent(granted: Boolean): ConsentState
    fun recordPermissionChecks(checks: Map<String, Boolean>): ConsentState
}
