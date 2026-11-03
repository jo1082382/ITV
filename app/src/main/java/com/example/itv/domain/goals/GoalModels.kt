package com.example.itv.domain.goals

// Values crossing the application boundary; no Room entities or DAO escape.
data class GoalValue(val enabled: Boolean)
data class DailyGoalValue(val dailyLimitSeconds: Long)
data class BedtimeGoalValue(val localMinuteOfDay: Int)
data class BreakGoalValue(val usageThresholdSeconds: Long, val breakDurationSeconds: Long,
    val usageMetric: String, val scopePackageName: String?, val accumulationPolicy: String)
data class GoalSnapshot(val daily: Pair<GoalValue, DailyGoalValue>?,
    val bedtime: Pair<GoalValue, BedtimeGoalValue>?, val usageBreak: Pair<GoalValue, BreakGoalValue>?)
