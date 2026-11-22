package com.example.itv.domain.records

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 5-tier daily puppy-face ink stamp + in-progress / no-data states for ITV Calendar.
 * 1: GOLD (해맑음 - 혀 내밀고 웃는 얼굴)
 * 2: SILVER (웃음 - 기분 좋게 웃는 얼굴)
 * 3: BRONZE (슬픔 - 시무룩한 얼굴)
 * 4: WARNING (화남 - 찌푸리고 화난 얼굴)
 * 5: CRITICAL (아픔 - 체온계 물고 아픈 얼굴)
 */
internal enum class DailyHealthTier(
    val label: String,
    val shortLabel: String,
    val isPositive: Boolean,
    val isWarning: Boolean
) {
    GOLD("해맑음", "최고", isPositive = true, isWarning = false),
    SILVER("웃음", "좋음", isPositive = true, isWarning = false),
    BRONZE("슬픔", "아쉬움", isPositive = false, isWarning = false),
    WARNING("화남", "화남", isPositive = false, isWarning = true),
    CRITICAL("아픔", "아픔", isPositive = false, isWarning = true),
    IN_PROGRESS("집계 중", "진행", isPositive = false, isWarning = false),
    NO_DATA("기록 없음", "-", isPositive = false, isWarning = false)
}

internal data class HealthAxisScore(
    val id: String,
    val title: String,
    val score: Int,          // 0..100
    val weightPercent: Int,  // Applied normalized weight (0..100)
    val available: Boolean,
    val summary: String
)

internal data class DailyHealthContext(
    val date: LocalDate,
    val isToday: Boolean,
    val isFinalized: Boolean,

    // 1. Sleep & Pre-sleep restraint context
    val bedtimeMinute: Int? = null,             // 0..1439 (e.g., 23:30 = 1410, 00:30 = 30)
    val wakeupMinute: Int? = null,
    val sleepDurationMinutes: Double? = null,
    val baselineBedtimeMinute: Int? = null,     // Personal average bedtime (circular)
    val preSleepUsageMinutes: Double? = null,   // Screen usage in pre-sleep / late-night window
    val preSleepShortformMinutes: Double? = null,

    // 2. Shortform escape & swipe speed (SPM) context
    val hasShortformTracking: Boolean = false,
    val shortformTotalMs: Long = 0L,
    val shortformSwipes: Long = 0L,
    val shortformAvgSessionMs: Long? = null,
    val shortformMaxSessionMs: Long? = null,
    val shortformSpm: Double? = null,
    val baselineShortformDailyMs: Long? = null,
    val baselineShortformSessionMs: Long? = null,
    val baselineSpm: Double? = null,

    // 3. Phone total session health context
    val totalPhoneMs: Long = 0L,
    val phoneAvgSessionMs: Long? = null,
    val phoneMaxSessionMs: Long? = null,
    val phoneShortformRatio: Double? = null,
    val baselinePhoneDailyMs: Long? = null,
    val baselinePhoneSessionMs: Long? = null,

    // 4. Day type rhythm context
    val isRestDay: Boolean = false
)

internal data class DailyHealthAssessment(
    val date: LocalDate,
    val tier: DailyHealthTier,
    val projectedTier: DailyHealthTier,
    val score: Int,
    val headline: String,
    val praises: List<String>,
    val cautions: List<String>,
    val axes: List<HealthAxisScore>,
    val isPartialData: Boolean,
    val evaluatedAxesCount: Int,
    val totalAxesCount: Int
)

/**
 * Pure domain evaluator that synthesizes ITV's sleep rhythm, pre-sleep restraint,
 * shortform escape power & swipe speed (SPM), phone session health, and workday/restday
 * rhythm into a calibrated 5-tier puppy face ink stamp.
 *
 * Calibrated strictly from a neutral (~76) baseline with tight 10-point intermediate bands
 * (84+ / 74~83 / 64~73 / 52~63 / <=51) so all 5 puppy expressions meaningfully reflect daily habits.
 */
internal object DailyHealthEvaluator {

    private const val DEFAULT_BASELINE_BEDTIME_MIN = 30 // 00:30 default fallback

    fun evaluate(ctx: DailyHealthContext): DailyHealthAssessment {
        val hasAnyData = ctx.totalPhoneMs > 0L ||
            ctx.shortformTotalMs > 0L ||
            ctx.bedtimeMinute != null ||
            (ctx.preSleepUsageMinutes ?: 0.0) > 0.0

        if (!hasAnyData) {
            return DailyHealthAssessment(
                date = ctx.date,
                tier = DailyHealthTier.NO_DATA,
                projectedTier = DailyHealthTier.NO_DATA,
                score = 0,
                headline = "저장된 사용 기록이 없는 날입니다.",
                praises = emptyList(),
                cautions = emptyList(),
                axes = emptyList(),
                isPartialData = false,
                evaluatedAxesCount = 0,
                totalAxesCount = 4
            )
        }

        val praises = mutableListOf<String>()
        val cautions = mutableListOf<String>()
        var severeTriggers = 0

        val restAllowance = if (ctx.isRestDay) 1.20 else 1.0

        // =========================================================================
        // Axis 1: 수면 직전 절제 & 수면 리듬 (Base Weight: 40)
        // Neutral baseline 82 -> earns 90+ only with on-time sleep & clean pre-sleep
        // =========================================================================
        val hasSleepAxis = ctx.bedtimeMinute != null ||
            ctx.preSleepUsageMinutes != null ||
            ctx.preSleepShortformMinutes != null ||
            ctx.sleepDurationMinutes != null

        var sleepScore = 82
        var sleepSummary = "수면 데이터 없음"
        if (hasSleepAxis) {
            val summaryParts = mutableListOf<String>()

            // 1-A. Bedtime rhythm consistency (circular diff vs baseline)
            val bedMin = ctx.bedtimeMinute
            if (bedMin != null) {
                val baseMin = ctx.baselineBedtimeMinute ?: DEFAULT_BASELINE_BEDTIME_MIN
                val delayMin = circularBedtimeDiffMinutes(bedMin, baseMin)
                val tightAllowed = if (ctx.isRestDay) 25 else 15

                when {
                    delayMin <= tightAllowed -> {
                        sleepScore += 10
                        praises += "평소 취침 리듬(${formatMinute(baseMin)})을 잘 지켰어요"
                        summaryParts += "취침 ${formatMinute(bedMin)}"
                    }
                    delayMin <= tightAllowed + 20 -> {
                        sleepScore -= 4
                        summaryParts += "취침 +${delayMin}분"
                    }
                    delayMin <= 60 -> {
                        sleepScore -= 10
                        cautions += "평소보다 취침이 ${delayMin}분 늦어졌어요"
                        summaryParts += "취침 +${delayMin}분 지연"
                    }
                    delayMin <= 95 -> {
                        sleepScore -= 22
                        severeTriggers++
                        cautions += "평소보다 취침이 ${formatDurationMinutes(delayMin)} 늦어졌어요"
                        summaryParts += "취침 +${delayMin}분 지연"
                    }
                    else -> {
                        sleepScore -= 34
                        severeTriggers++
                        cautions += "평소보다 취침이 ${formatDurationMinutes(delayMin)} 크게 밀렸어요"
                        summaryParts += "취침 심하게 밀림"
                    }
                }
            }

            // 1-B. Pre-sleep / Late-night shortform usage (strongest sleep disruptor)
            val preSleepSf = ctx.preSleepShortformMinutes
            if (preSleepSf != null) {
                when {
                    preSleepSf < 1.0 -> {
                        sleepScore += 4
                    }
                    preSleepSf < 10.0 -> {
                        sleepScore -= 4
                    }
                    preSleepSf < 22.0 -> {
                        sleepScore -= 14
                        cautions += "취침 전·심야 숏폼 ${preSleepSf.roundToInt()}분 시청"
                    }
                    preSleepSf < 35.0 -> {
                        sleepScore -= 24
                        severeTriggers++
                        cautions += "취침 전·심야 숏폼 ${preSleepSf.roundToInt()}분 몰입"
                    }
                    else -> {
                        sleepScore -= 36
                        severeTriggers++
                        cautions += "취침 전·심야 숏폼 ${preSleepSf.roundToInt()}분 과몰입"
                    }
                }
            }

            // 1-C. Pre-sleep / Late-night overall screen usage
            val preSleepPhone = ctx.preSleepUsageMinutes
            if (preSleepPhone != null) {
                when {
                    preSleepPhone < 5.0 -> {
                        sleepScore += 6
                        praises += "취침 전 골든타임 스마트폰 절제 성공"
                        summaryParts += "취침 전 클린"
                    }
                    preSleepPhone < 15.0 -> {
                        summaryParts += "야간 ${preSleepPhone.roundToInt()}분"
                    }
                    preSleepPhone < 30.0 -> {
                        sleepScore -= 7
                        cautions += "취침 전·심야 화면 사용 ${preSleepPhone.roundToInt()}분"
                        summaryParts += "야간 ${preSleepPhone.roundToInt()}분"
                    }
                    preSleepPhone < 50.0 -> {
                        sleepScore -= 16
                        cautions += "취침 전·심야 화면 사용 ${preSleepPhone.roundToInt()}분으로 피로 누적"
                        summaryParts += "야간 ${preSleepPhone.roundToInt()}분"
                    }
                    else -> {
                        sleepScore -= 28
                        if ((preSleepSf ?: 0.0) < 22.0) {
                            severeTriggers++
                        }
                        cautions += "야간 화면 과사용(${preSleepPhone.roundToInt()}분)으로 수면 방해"
                        summaryParts += "야간 과사용"
                    }
                }
            }

            // 1-D. Sleep duration sanity
            val sleepDur = ctx.sleepDurationMinutes
            if (sleepDur != null && sleepDur > 0.0) {
                when {
                    sleepDur < 300.0 -> { // < 5h
                        sleepScore -= 14
                        cautions += "수면 시간이 ${(sleepDur / 60.0).let { String.format(Locale.KOREA, "%.1f", it) }}시간으로 크게 부족했어요"
                    }
                    sleepDur < 360.0 -> { // 5~6h
                        sleepScore -= 6
                    }
                    sleepDur in 400.0..540.0 -> {
                        sleepScore += 2
                    }
                }
            }

            sleepScore = sleepScore.coerceIn(0, 100)
            sleepSummary = summaryParts.joinToString(" · ").ifEmpty { "야간 리듬 점검 완료" }
        }

        // =========================================================================
        // Axis 2: 숏폼 탈출력 & 스와이프 속도 (Base Weight: 30)
        // Neutral baseline 82
        // =========================================================================
        val hasShortformAxis = ctx.hasShortformTracking
        var shortformScore = 82
        var shortformSummary = "추적 데이터 없음 (가중치 자동 보정)"

        if (hasShortformAxis) {
            val sfMinutes = ctx.shortformTotalMs / 60_000.0
            if (sfMinutes < 1.0) {
                shortformScore = 96
                praises += "숏폼 없이 보낸 도파민 클린 데이!"
                shortformSummary = "시청 없음 (클린)"
            } else {
                val summaryParts = mutableListOf<String>()

                // 2-A. Session escape power (avg & max shortform session)
                val avgSesMin = ctx.shortformAvgSessionMs?.let { it / 60_000.0 }
                val maxSesMin = ctx.shortformMaxSessionMs?.let { it / 60_000.0 }

                if (maxSesMin != null || avgSesMin != null) {
                    val effMax = maxSesMin ?: avgSesMin ?: 0.0
                    val effAvg = avgSesMin ?: maxSesMin ?: 0.0
                    when {
                        effAvg <= 8.0 * restAllowance && effMax <= 14.0 * restAllowance -> {
                            shortformScore += 10
                            praises += "숏폼을 평균 ${effAvg.roundToInt().coerceAtLeast(1)}분 만에 깔끔하게 끊어냈어요"
                            summaryParts += "평균 ${effAvg.roundToInt().coerceAtLeast(1)}분 탈출"
                        }
                        effAvg <= 13.0 * restAllowance && effMax <= 22.0 * restAllowance -> {
                            summaryParts += "평균 ${effAvg.roundToInt().coerceAtLeast(1)}분"
                        }
                        effMax >= 38.0 * restAllowance || effAvg >= 22.0 * restAllowance -> {
                            shortformScore -= 26
                            severeTriggers++
                            cautions += "숏폼 최장 ${effMax.roundToInt()}분 연속 몰입 발생"
                            summaryParts += "최장 ${effMax.roundToInt()}분 몰입"
                        }
                        else -> {
                            shortformScore -= 10
                            cautions += "숏폼 세션이 길게 이어졌어요(최장 ${effMax.roundToInt()}분)"
                            summaryParts += "최장 ${effMax.roundToInt()}분"
                        }
                    }
                }

                // 2-B. Swipe speed (SPM - unconscious rapid scrolling)
                val spm = ctx.shortformSpm
                val baseSpm = ctx.baselineSpm?.takeIf { it > 0.5 }
                if (spm != null && spm > 0.0 && ctx.shortformSwipes >= 20L) {
                    val spmRatio = if (baseSpm != null) spm / baseSpm else null
                    when {
                        (spmRatio != null && spmRatio >= 1.45) || spm >= 5.0 -> {
                            shortformScore -= 18
                            val ratioText = spmRatio?.let { String.format(Locale.KOREA, "평소 %.1f배", it) }
                                ?: String.format(Locale.KOREA, "분당 %.1f회", spm)
                            cautions += "빠른 무의식 스와이프 과속 ($ratioText)"
                            summaryParts += "스와이프 과속"
                        }
                        (spmRatio != null && spmRatio >= 1.25) || spm >= 3.8 -> {
                            shortformScore -= 8
                        }
                    }
                }

                // 2-C. Daily volume vs personal baseline (rest-day adjusted)
                val baseDailyMin = ctx.baselineShortformDailyMs?.takeIf { it >= 5 * 60_000L }?.let { it / 60_000.0 }
                val ratioToBase = if (baseDailyMin != null) sfMinutes / (baseDailyMin * restAllowance) else null
                when {
                    sfMinutes <= 12.0 * restAllowance -> {
                        shortformScore += 6
                        if (praises.none { it.contains("숏폼") }) {
                            praises += "숏폼 총 ${sfMinutes.roundToInt()}분으로 가볍게 절제했어요"
                        }
                    }
                    sfMinutes <= 25.0 * restAllowance && (ratioToBase == null || ratioToBase <= 1.1) -> {
                        shortformScore -= 2
                    }
                    sfMinutes >= 75.0 * restAllowance || (ratioToBase != null && ratioToBase >= 1.75 && sfMinutes >= 40.0) -> {
                        shortformScore -= 26
                        if ((maxSesMin ?: 0.0) < 38.0 * restAllowance) {
                            severeTriggers++
                        }
                        cautions += "숏폼 총 ${formatDurationMinutes(sfMinutes.roundToInt())} 과다 시청"
                    }
                    sfMinutes >= 40.0 * restAllowance || (ratioToBase != null && ratioToBase >= 1.3 && sfMinutes >= 22.0) -> {
                        shortformScore -= 14
                    }
                    else -> {
                        shortformScore -= 6
                    }
                }

                shortformScore = shortformScore.coerceIn(0, 100)
                shortformSummary = summaryParts.joinToString(" · ").ifEmpty {
                    "총 ${formatDurationMinutes(sfMinutes.roundToInt())}"
                }
            }
        }

        // =========================================================================
        // Axis 3: 휴대폰 세션 건강도 (Base Weight: 20)
        // Neutral baseline 82
        // =========================================================================
        val hasSessionAxis = ctx.totalPhoneMs > 0L
        var sessionScore = 82
        var sessionSummary = "사용 기록 없음"

        if (hasSessionAxis) {
            val summaryParts = mutableListOf<String>()
            val phoneMin = ctx.totalPhoneMs / 60_000.0

            // 3-A. Continuous phone session health (excluding <2m quick checks)
            val phoneAvgMin = ctx.phoneAvgSessionMs?.let { it / 60_000.0 }
            val phoneMaxMin = ctx.phoneMaxSessionMs?.let { it / 60_000.0 }

            if (phoneAvgMin != null && phoneAvgMin > 0.0) {
                when {
                    phoneAvgMin <= 13.0 * restAllowance && (phoneMaxMin ?: 0.0) <= 35.0 * restAllowance -> {
                        sessionScore += 10
                        praises += "필요할 때만 짧게 쓰고 내려놓는 건강한 세션(평균 ${phoneAvgMin.roundToInt()}분)"
                        summaryParts += "평균 ${phoneAvgMin.roundToInt()}분"
                    }
                    phoneAvgMin <= 20.0 * restAllowance && (phoneMaxMin ?: 0.0) <= 50.0 * restAllowance -> {
                        sessionScore += 2
                        summaryParts += "평균 ${phoneAvgMin.roundToInt()}분"
                    }
                    (phoneMaxMin ?: 0.0) >= 85.0 * restAllowance || phoneAvgMin >= 36.0 * restAllowance -> {
                        sessionScore -= 24
                        cautions += "장시간 연속 사용 세션(최장 ${(phoneMaxMin ?: phoneAvgMin).roundToInt()}분)이 있었어요"
                        summaryParts += "연속 사용 과다"
                    }
                    else -> {
                        sessionScore -= 10
                        summaryParts += "평균 ${phoneAvgMin.roundToInt()}분"
                    }
                }
            }

            // 3-B. Shortform share of total phone usage
            val sfRatio = ctx.phoneShortformRatio ?: if (ctx.hasShortformTracking) {
                (ctx.shortformTotalMs.toDouble() / ctx.totalPhoneMs.toDouble()).coerceIn(0.0, 1.0)
            } else null

            if (sfRatio != null) {
                val pct = (sfRatio * 100.0).roundToInt()
                when {
                    pct >= 38 -> {
                        sessionScore -= 22
                        cautions += "전체 사용 중 숏폼 비중이 ${pct}%로 매우 높아요"
                        summaryParts += "숏폼 비중 ${pct}%"
                    }
                    pct >= 22 -> {
                        sessionScore -= 12
                        summaryParts += "숏폼 비중 ${pct}%"
                    }
                    pct >= 12 -> {
                        sessionScore -= 4
                        summaryParts += "숏폼 비중 ${pct}%"
                    }
                    pct <= 8 && phoneMin >= 30.0 -> {
                        sessionScore += 4
                        if (praises.size < 3 && phoneMin >= 60.0) {
                            praises += "숏폼 비중이 낮아 목적성 있게 스마트폰을 활용했어요"
                        }
                    }
                }
            }

            // 3-C. Overall screen load guard (gentle if purposeful, stricter if heavy)
            val basePhoneMin = ctx.baselinePhoneDailyMs?.takeIf { it >= 30 * 60_000L }?.let { it / 60_000.0 }
            val phoneVsBase = if (basePhoneMin != null) phoneMin / (basePhoneMin * restAllowance) else null
            val isLowShortform = (sfRatio ?: 0.0) < 0.10 && ctx.hasShortformTracking
            when {
                phoneMin >= 480.0 * restAllowance -> { // >= 8h
                    sessionScore -= if (isLowShortform) 12 else 22
                    if (!isLowShortform) {
                        cautions += "하루 총 화면 사용량이 ${formatDurationMinutes(phoneMin.roundToInt())}으로 매우 많았어요"
                    }
                }
                phoneMin >= 330.0 * restAllowance || (phoneVsBase != null && phoneVsBase >= 1.45 && phoneMin >= 200.0) -> {
                    sessionScore -= if (isLowShortform) 8 else 14
                }
                phoneMin >= 210.0 * restAllowance -> {
                    sessionScore -= if (isLowShortform) 3 else 6
                }
                phoneMin <= 130.0 * restAllowance -> {
                    sessionScore += 6
                }
            }

            sessionScore = sessionScore.coerceIn(0, 100)
            sessionSummary = summaryParts.joinToString(" · ").ifEmpty {
                "총 ${formatDurationMinutes(phoneMin.roundToInt())}"
            }
        }

        // =========================================================================
        // Axis 4: 요일/생활 리듬 보정 (Base Weight: 10)
        // =========================================================================
        val hasRhythmAxis = ctx.totalPhoneMs > 0L
        var rhythmScore = 80
        var rhythmSummary = if (ctx.isRestDay) "쉬는 날 패턴 반영" else "일과 날 패턴 반영"

        if (hasRhythmAxis) {
            val phoneMin = ctx.totalPhoneMs / 60_000.0
            val basePhoneMin = ctx.baselinePhoneDailyMs?.takeIf { it >= 30 * 60_000L }?.let { it / 60_000.0 }

            if (basePhoneMin != null) {
                val ratio = phoneMin / (basePhoneMin * restAllowance)
                rhythmScore = when {
                    ratio <= 0.90 -> 92
                    ratio <= 1.08 -> 80
                    ratio <= 1.25 -> 70
                    ratio <= 1.45 -> 56
                    else -> 40
                }
                rhythmSummary = if (ctx.isRestDay) {
                    if (ratio <= 1.15) "휴일 여유 패턴 내 안정적" else "휴일 평소 패턴 초과"
                } else {
                    if (ratio <= 1.08) "평일 생활 리듬 유지" else "평일 평소 사용량 초과"
                }
            } else {
                rhythmScore = when {
                    phoneMin <= 150.0 * restAllowance -> 90
                    phoneMin <= 240.0 * restAllowance -> 78
                    phoneMin <= 340.0 * restAllowance -> 64
                    else -> 48
                }
            }
        }

        // =========================================================================
        // Dynamic Weight Redistribution across available axes
        // =========================================================================
        data class RawAxis(
            val id: String,
            val title: String,
            val score: Int,
            val baseWeight: Int,
            val available: Boolean,
            val summary: String
        )

        val rawAxes = listOf(
            RawAxis("sleep", "수면·취침 절제", sleepScore, 40, hasSleepAxis, sleepSummary),
            RawAxis("shortform", "숏폼 탈출·속도", shortformScore, 30, hasShortformAxis, shortformSummary),
            RawAxis("session", "세션 건강도", sessionScore, 20, hasSessionAxis, sessionSummary),
            RawAxis("rhythm", "생활 리듬 균형", rhythmScore, 10, hasRhythmAxis, rhythmSummary)
        )

        val availableWeightSum = rawAxes.filter { it.available }.sumOf { it.baseWeight }.coerceAtLeast(1)
        var weightedScoreSum = 0.0

        val normalizedAxes = rawAxes.map { axis ->
            val appliedWeight = if (axis.available) {
                ((axis.baseWeight.toDouble() / availableWeightSum.toDouble()) * 100.0).roundToInt()
            } else {
                0
            }
            if (axis.available) {
                weightedScoreSum += axis.score * (axis.baseWeight.toDouble() / availableWeightSum.toDouble())
            }
            HealthAxisScore(
                id = axis.id,
                title = axis.title,
                score = if (axis.available) axis.score else 0,
                weightPercent = appliedWeight,
                available = axis.available,
                summary = axis.summary
            )
        }

        val finalScore = weightedScoreSum.roundToInt().coerceIn(0, 100)
        val evaluatedCount = rawAxes.count { it.available }
        val isPartialData = evaluatedCount < rawAxes.size

        // Tighter, well-calibrated 5-tier cutoffs (84+ / 74~83 / 64~73 / 52~63 / <=51)
        val projectedTier = when {
            finalScore <= 51 || severeTriggers >= 2 -> DailyHealthTier.CRITICAL  // 5단계: 아픔 (체온계)
            finalScore <= 63 || severeTriggers == 1 -> DailyHealthTier.WARNING   // 4단계: 화남
            finalScore >= 84 -> DailyHealthTier.GOLD                             // 1단계: 해맑음 (혀 내밈)
            finalScore >= 74 -> DailyHealthTier.SILVER                           // 2단계: 웃음
            else -> DailyHealthTier.BRONZE                                       // 3단계: 슬픔
        }

        // Defer finalization for today / not-yet-woken nights
        val finalTier = if (ctx.isToday || !ctx.isFinalized) {
            DailyHealthTier.IN_PROGRESS
        } else {
            projectedTier
        }

        val distinctPraises = praises.distinct().take(3)
        val distinctCautions = cautions.distinct().take(3)

        val headline = buildHeadline(
            tier = finalTier,
            projectedTier = projectedTier,
            isToday = ctx.isToday,
            cautions = distinctCautions
        )

        return DailyHealthAssessment(
            date = ctx.date,
            tier = finalTier,
            projectedTier = projectedTier,
            score = finalScore,
            headline = headline,
            praises = distinctPraises,
            cautions = distinctCautions,
            axes = normalizedAxes,
            isPartialData = isPartialData,
            evaluatedAxesCount = evaluatedCount,
            totalAxesCount = rawAxes.size
        )
    }

    /**
     * Computes signed circular difference (actual - baseline) in minutes within [-720, +720].
     * Positive means `actual` is LATER than `baseline` (e.g., 00:30 vs 23:30 = +60).
     * Negative means `actual` is EARLIER than `baseline` (e.g., 23:40 vs 00:20 = -40).
     */
    internal fun circularBedtimeDiffMinutes(actualMin: Int, baselineMin: Int): Int {
        val a = ((actualMin % 1440) + 1440) % 1440
        val b = ((baselineMin % 1440) + 1440) % 1440
        var diff = a - b
        if (diff > 720) diff -= 1440
        if (diff < -720) diff += 1440
        return diff
    }

    private fun buildHeadline(
        tier: DailyHealthTier,
        projectedTier: DailyHealthTier,
        isToday: Boolean,
        cautions: List<String>
    ): String {
        if (tier == DailyHealthTier.IN_PROGRESS) {
            return if (isToday) {
                when (projectedTier) {
                    DailyHealthTier.GOLD -> "지금까지 최고예요! 오늘 밤 취침 전 절제만 지키면 내일 아침 해맑은 스탬프가 찍혀요."
                    DailyHealthTier.SILVER -> "흐름이 좋아요! 잠들기 전 폰만 일찍 내려놓으면 내일 아침 활짝 웃는 스탬프를 받을 수 있어요."
                    DailyHealthTier.BRONZE -> "살짝 아쉬운 흐름이에요. 지금부터 숏폼을 줄이고 일찍 쉬면 웃는 얼굴로 바꿀 수 있어요."
                    else -> "과사용 신호가 감지됐어요. 오늘 밤엔 일찍 폰을 내려놓고 강아지가 아프지 않게 지켜주세요."
                }
            } else {
                "수면 및 기상이 확인되면 아침에 최종 강아지 스탬프가 확정됩니다."
            }
        }

        return when (tier) {
            DailyHealthTier.GOLD ->
                "수면 리듬과 스마트폰 절제력을 완벽하게 지켜내 강아지가 해맑게 반겨줘요!"
            DailyHealthTier.SILVER ->
                "전반적으로 건강하게 잘 관리해서 강아지가 기분 좋게 웃고 있어요."
            DailyHealthTier.BRONZE ->
                if (cautions.isNotEmpty()) {
                    "조금 아쉬운 하루였어요: ${cautions.first()}"
                } else {
                    "살짝 아쉬운 리듬이었어요. 내일은 취침 전 스마트폰을 조금만 더 일찍 내려놓아 보세요."
                }
            DailyHealthTier.WARNING ->
                if (cautions.isNotEmpty()) {
                    "리듬이 흔들려 강아지가 화났어요: ${cautions.first()}"
                } else {
                    "야간 사용이나 연속 몰입이 쌓여 주의가 필요한 하루였어요."
                }
            DailyHealthTier.CRITICAL ->
                "수면 지연과 과몰입이 겹쳐 강아지가 열이 나고 아파요. 오늘은 충분히 쉬어주세요."
            else -> "기록이 집계되었습니다."
        }
    }

    private fun formatMinute(min: Int): String {
        val norm = ((min % 1440) + 1440) % 1440
        val h = norm / 60
        val m = norm % 60
        return String.format(Locale.KOREA, "%02d:%02d", h, m)
    }

    private fun formatDurationMinutes(totalMin: Int): String {
        val m = abs(totalMin)
        val hours = m / 60
        val rem = m % 60
        return when {
            hours > 0 && rem > 0 -> "${hours}시간 ${rem}분"
            hours > 0 -> "${hours}시간"
            else -> "${rem}분"
        }
    }
}
