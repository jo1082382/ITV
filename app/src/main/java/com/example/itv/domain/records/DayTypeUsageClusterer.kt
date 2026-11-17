package com.example.itv.domain.records

import java.time.LocalDate

enum class EstimatedDayType(val label: String) {
    WORKDAY("일과 날"),     // 업무, 학업, 루틴이 있는 날 (사용량 적음, 세션 짧음)
    REST_DAY("쉬는 날")     // 휴일, 주말, 여가 시간 (사용량 많음, 세션 긺)
}

data class DayUsageProfile(
    val date: LocalDate,
    val totalDurationMs: Long,
    val sessions: List<Long> = emptyList(),
    val categoryDurationsMs: Map<String, Long> = emptyMap()
) {
    val maxSessionMs: Long get() = sessions.maxOrNull() ?: totalDurationMs
    val avgSessionMs: Long get() = if (sessions.isNotEmpty()) (sessions.sum() / sessions.size) else totalDurationMs
}

data class DayTypeClusterResult(
    val workdayDays: List<DayUsageProfile>,
    val restdayDays: List<DayUsageProfile>,
    val splitThresholdMs: Long,
    val workdayAvgDailyMs: Long,
    val workdayAvgSessionMs: Long,
    val restdayAvgDailyMs: Long,
    val restdayAvgSessionMs: Long,
    val clusterRatio: Double,
    val isConfident: Boolean
) {
    fun classify(dailyDurationMs: Long): EstimatedDayType {
        return if (dailyDurationMs >= splitThresholdMs) EstimatedDayType.REST_DAY else EstimatedDayType.WORKDAY
    }
}

/**
 * Clusters daily shortform usage profiles into WORKDAY (low-usage) and REST_DAY (high-usage)
 * using an exact 1D 2-means variance minimization (Otsu / Fisher optimal 1D partition).
 */
object DayTypeUsageClusterer {

    fun cluster(profiles: List<DayUsageProfile>): DayTypeClusterResult {
        val active = profiles.filter { it.totalDurationMs > 0L }
        if (active.size < 2) {
            val single = active.firstOrNull()
            val total = single?.totalDurationMs ?: 0L
            val session = single?.avgSessionMs ?: 0L
            return DayTypeClusterResult(
                workdayDays = active,
                restdayDays = emptyList(),
                splitThresholdMs = total,
                workdayAvgDailyMs = total,
                workdayAvgSessionMs = session,
                restdayAvgDailyMs = total,
                restdayAvgSessionMs = session,
                clusterRatio = 1.0,
                isConfident = false
            )
        }

        val sorted = active.sortedBy { it.totalDurationMs }
        val n = sorted.size

        val x = sorted.map { it.totalDurationMs.toDouble() }
        val sumPrefix = DoubleArray(n + 1)
        val sqPrefix = DoubleArray(n + 1)
        for (i in 0 until n) {
            sumPrefix[i + 1] = sumPrefix[i] + x[i]
            sqPrefix[i + 1] = sqPrefix[i] + (x[i] * x[i])
        }

        var minVariance = Double.MAX_VALUE
        var bestSplitIndex = 1

        for (k in 1 until n) {
            val count1 = k.toDouble()
            val sum1 = sumPrefix[k]
            val sq1 = sqPrefix[k]
            val mean1 = sum1 / count1
            val var1 = sq1 - count1 * mean1 * mean1

            val count2 = (n - k).toDouble()
            val sum2 = sumPrefix[n] - sumPrefix[k]
            val sq2 = sqPrefix[n] - sqPrefix[k]
            val mean2 = sum2 / count2
            val var2 = sq2 - count2 * mean2 * mean2

            val totalVar = var1 + var2
            if (totalVar < minVariance) {
                minVariance = totalVar
                bestSplitIndex = k
            }
        }

        val lowDays = sorted.subList(0, bestSplitIndex)
        val highDays = sorted.subList(bestSplitIndex, n)

        val splitThresholdMs = if (bestSplitIndex in 1 until n) {
            (sorted[bestSplitIndex - 1].totalDurationMs + sorted[bestSplitIndex].totalDurationMs) / 2L
        } else {
            sorted[0].totalDurationMs
        }

        val lowDailyAvg = if (lowDays.isNotEmpty()) lowDays.map { it.totalDurationMs }.average().toLong() else 0L
        val highDailyAvg = if (highDays.isNotEmpty()) highDays.map { it.totalDurationMs }.average().toLong() else lowDailyAvg

        val lowSessions = lowDays.flatMap { it.sessions }.filter { it >= 30_000L }.ifEmpty { lowDays.flatMap { it.sessions } }
        val highSessions = highDays.flatMap { it.sessions }.filter { it >= 30_000L }.ifEmpty { highDays.flatMap { it.sessions } }

        val lowSessionAvg = if (lowSessions.isNotEmpty()) lowSessions.average().toLong() else lowDailyAvg
        val highSessionAvg = if (highSessions.isNotEmpty()) highSessions.average().toLong() else highDailyAvg

        val ratio = if (lowDailyAvg > 0L) {
            kotlin.math.round((highDailyAvg.toDouble() / lowDailyAvg.toDouble()) * 100.0) / 100.0
        } else 1.0

        val isConfident = n >= 4 && ratio >= 1.35 && lowDays.isNotEmpty() && highDays.isNotEmpty()

        return DayTypeClusterResult(
            workdayDays = lowDays,
            restdayDays = highDays,
            splitThresholdMs = splitThresholdMs,
            workdayAvgDailyMs = lowDailyAvg,
            workdayAvgSessionMs = lowSessionAvg,
            restdayAvgDailyMs = highDailyAvg,
            restdayAvgSessionMs = highSessionAvg,
            clusterRatio = ratio,
            isConfident = isConfident
        )
    }

    /**
     * Clusters usage by a specific category (e.g. "shortform", "game", "longform", "messenger")
     * extracted from the daily category breakdown.
     */
    fun clusterByCategory(
        profiles: List<DayUsageProfile>,
        category: String
    ): DayTypeClusterResult {
        val categoryProfiles = profiles.map { p ->
            DayUsageProfile(
                date = p.date,
                totalDurationMs = p.categoryDurationsMs[category] ?: 0L,
                sessions = emptyList()
            )
        }
        return cluster(categoryProfiles)
    }
}

data class HybridDayTypeDecision(
    val estimatedType: EstimatedDayType,
    val confidence: Double,
    val effectiveAvgSessionMs: Long,
    val effectiveAvgDailyMs: Long,
    val calendarPriorType: EstimatedDayType,
    val reason: String
)

/**
 * Combines Calendar Day-of-Week prior (weekday vs weekend) with observed
 * phone total usage and shortform usage likelihoods using a Bayesian evidence update.
 */
object HybridDayTypeClassifier {

    fun classify(
        date: LocalDate,
        phoneTotalTodayMs: Long,
        shortformTodayMs: Long,
        phoneCluster: DayTypeClusterResult?,
        shortformCluster: DayTypeClusterResult?,
        globalAvgSessionMs: Long,
        globalAvgDailyMs: Long
    ): HybridDayTypeDecision {
        val dayOfWeek = date.dayOfWeek
        val isWeekend = dayOfWeek == java.time.DayOfWeek.SATURDAY || dayOfWeek == java.time.DayOfWeek.SUNDAY
        val calendarPriorType = if (isWeekend) EstimatedDayType.REST_DAY else EstimatedDayType.WORKDAY

        // 1. Calendar Prior: P(REST_DAY)
        // Weekend has 85% prior for rest day, weekday has 15% prior for rest day (85% workday)
        val priorRestProb = if (isWeekend) 0.85 else 0.15
        var logOdds = kotlin.math.ln(priorRestProb / (1.0 - priorRestProb))

        var phoneEvidenceReason = ""
        // 2. Phone Total Usage Evidence
        if (phoneCluster != null && phoneCluster.splitThresholdMs > 0L && phoneTotalTodayMs > 0L) {
            val phoneThreshold = phoneCluster.splitThresholdMs
            val ratioToThreshold = phoneTotalTodayMs.toDouble() / phoneThreshold.toDouble()
            // Likelihood ratio (REST / WORK) for phone usage:
            val phoneWeight = if (phoneCluster.isConfident) 1.5 else 0.8
            val phoneEvidence = ((ratioToThreshold - 1.0) / 0.5).coerceIn(-1.0, 1.0) * phoneWeight
            logOdds += phoneEvidence
            phoneEvidenceReason = if (ratioToThreshold >= 1.0) "총 사용량(${phoneTotalTodayMs / 60_000L}분) 기준 초과"
            else "총 사용량(${phoneTotalTodayMs / 60_000L}분) 일과 수준"
        }

        var sfEvidenceReason = ""
        // 3. Shortform Usage Evidence
        if (shortformCluster != null && shortformCluster.splitThresholdMs > 0L && shortformTodayMs > 0L) {
            val sfThreshold = shortformCluster.splitThresholdMs
            val ratioToThreshold = shortformTodayMs.toDouble() / sfThreshold.toDouble()
            val sfWeight = if (shortformCluster.isConfident) 1.2 else 0.6
            val sfEvidence = ((ratioToThreshold - 1.0) / 0.5).coerceIn(-1.0, 1.0) * sfWeight
            logOdds += sfEvidence
            sfEvidenceReason = if (ratioToThreshold >= 1.0) "숏폼(${shortformTodayMs / 60_000L}분) 휴일 패턴"
            else "숏폼(${shortformTodayMs / 60_000L}분) 평일 패턴"
        }

        // 4. Posterior Probability of REST_DAY
        val posteriorRestProb = 1.0 / (1.0 + kotlin.math.exp(-logOdds))
        val estimatedType = if (posteriorRestProb >= 0.50) EstimatedDayType.REST_DAY else EstimatedDayType.WORKDAY
        val confidence = if (estimatedType == EstimatedDayType.REST_DAY) posteriorRestProb else (1.0 - posteriorRestProb)

        // 5. Determine Effective Session and Daily Baseline
        val (effectiveSessionMs, effectiveDailyMs) = when (estimatedType) {
            EstimatedDayType.REST_DAY -> {
                val s = shortformCluster?.restdayAvgSessionMs?.takeIf { it > 0L }
                    ?: phoneCluster?.restdayAvgSessionMs?.takeIf { it > 0L }
                    ?: globalAvgSessionMs
                val d = shortformCluster?.restdayAvgDailyMs?.takeIf { it > 0L }
                    ?: globalAvgDailyMs
                s to d
            }
            EstimatedDayType.WORKDAY -> {
                val s = shortformCluster?.workdayAvgSessionMs?.takeIf { it > 0L }
                    ?: phoneCluster?.workdayAvgSessionMs?.takeIf { it > 0L }
                    ?: globalAvgSessionMs
                val d = shortformCluster?.workdayAvgDailyMs?.takeIf { it > 0L }
                    ?: globalAvgDailyMs
                s to d
            }
        }

        val reason = buildString {
            append(if (isWeekend) "주말(캘린더)" else "평일(캘린더)")
            if (phoneEvidenceReason.isNotEmpty()) {
                append(" + ").append(phoneEvidenceReason)
            }
            if (sfEvidenceReason.isNotEmpty()) {
                append(" + ").append(sfEvidenceReason)
            }
            append(" → ").append(estimatedType.label).append(" (신뢰도 ").append((confidence * 100).toInt()).append("%)")
        }

        return HybridDayTypeDecision(
            estimatedType = estimatedType,
            confidence = kotlin.math.round(confidence * 100.0) / 100.0,
            effectiveAvgSessionMs = effectiveSessionMs,
            effectiveAvgDailyMs = effectiveDailyMs,
            calendarPriorType = calendarPriorType,
            reason = reason
        )
    }
}
