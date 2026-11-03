package com.example.itv.application.records

/** Caller serializes all calls with the queue admission lock. */
internal class ShortformQueueAdmission {
    var generation: Long = 0; private set
    var lost: Long = 0; private set
    private var maintenancePending = false
    fun canSubmit(maintenance: Boolean): Boolean = !maintenance || !maintenancePending
    fun accepted(maintenance: Boolean) { if (maintenance) maintenancePending = true }
    fun rejected(maintenance: Boolean) {
        if (!maintenance) { generation++; lost++ }
    }
    fun started(maintenance: Boolean) { if (maintenance) maintenancePending = false }
}
