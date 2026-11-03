package com.example.itv.domain.records

import java.time.LocalDate

internal object SleepSummaryPolicy {
    fun minutes(date: LocalDate, automatic: Double?, confirmations: Map<String, SleepConfirmation>): Double? {
        val value = confirmations[date.toString()] ?: return automatic
        return if (value.choice == "NOT_SLEEP") 0.0
        else if (value.startMs != null && value.endMs != null)
            (value.endMs - value.startMs) / 60000.0 else automatic
    }
    fun average(values: List<Double?>): Double? = values.filterNotNull().let {
        if (it.isEmpty()) null else it.average()
    }
}
