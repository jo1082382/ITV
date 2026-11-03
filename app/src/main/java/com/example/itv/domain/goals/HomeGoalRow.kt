package com.example.itv.domain.goals

// Query projection only: not an Entity and not a new table.
data class HomeGoalRow(
    val goalType: String,
    val enabled: Boolean,
    val dailyLimitSeconds: Long?,
    val localMinuteOfDay: Int?,
    val usageThresholdSeconds: Long?,
    val breakDurationSeconds: Long?,
    val usageMetric: String?,
    val scopePackageName: String?,
    val accumulationPolicy: String?
)
