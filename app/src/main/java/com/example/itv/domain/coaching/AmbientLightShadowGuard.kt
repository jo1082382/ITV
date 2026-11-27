package com.example.itv.domain.coaching

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 롤링 조도 히스토리 기반 그림자/머리 가림 오판정 필터.
 *
 * 밝은 실내에서 사용자가 머리로 천장 조명을 가리거나 책상 그늘에서 휴대폰을 내려다볼 때
 * 전면 지향성 조도 센서(`TYPE_LIGHT`) 값이 일시적으로 5~19 lux까지 떨어져 암실로 오판정되는
 * 문제를 막기 위해 설계되었습니다.
 *
 * - Android `TYPE_LIGHT`가 `REPORTING_MODE_ON_CHANGE` 특성상 일정 밝기에서 이벤트를 반복 발행하지
 *   않는 하드웨어 특성을 반영하여, 각 샘플의 **유지 시간(piecewise-constant hold duration)**을
 *   계산합니다.
 * - 64칸 고정 원시 배열(약 768 bytes)만 사용하므로 센서 콜백/틱마다 힙 할당이 0건입니다.
 */
class AmbientLightShadowGuard {

    data class Assessment(
        val isLikelyShadowInLitRoom: Boolean,
        val recentPeakLux: Float?,
        val recentBrightHoldMs: Long,
        val continuousDeepDarkMs: Long
    )

    private val lock = Any()
    private val timestamps = LongArray(CAPACITY)
    private val luxValues = FloatArray(CAPACITY)
    private var head = 0
    private var count = 0

    private var lastRecordedLux: Float = Float.NaN
    private var lastRecordedAtMs: Long = Long.MIN_VALUE
    private var deepDarkSinceMs: Long = Long.MIN_VALUE

    /**
     * 새 조도 샘플을 기록합니다. (O(1), 힙 할당 없음)
     */
    fun record(nowMs: Long, lux: Float?, isOccluded: Boolean) {
        synchronized(lock) {
            if (isOccluded || lux == null || !lux.isFinite() || lux < 0f) {
                // 센서 가림(손/주머니/이불)은 실제 암실 소등으로 인정하지 않음
                deepDarkSinceMs = Long.MIN_VALUE
                return
            }

            // 완전 암실(<= 5 lx) 연속 유지 시각 추적
            if (lux <= DEEP_DARK_LUX) {
                if (deepDarkSinceMs == Long.MIN_VALUE) {
                    deepDarkSinceMs = nowMs
                }
            } else if (lux >= DEEP_DARK_BREAK_LUX) {
                // 20 lx 이상 빛이 한 번이라도 들어오면 소등 연속 유지 초기화
                deepDarkSinceMs = Long.MIN_VALUE
            } else if (deepDarkSinceMs != Long.MIN_VALUE && lux > SHADOW_SAFE_DEEP_TOLERANCE_LUX) {
                // 5~20 lx 구간(머리 그림자 대역)으로 올라오면 완전 소등 확신 타이머 초기화
                deepDarkSinceMs = Long.MIN_VALUE
            }

            // 링 버퍼 과밀 방지: 3초 이내 소폭 변동은 덮어쓰지 않고, 큰 변화(>= 15 lx) 또는 경계 교차는 최소 400ms 간격으로 기록
            val dt = if (lastRecordedAtMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastRecordedAtMs
            val crossedBoundary = !lastRecordedLux.isNaN() && (
                (lastRecordedLux < BRIGHT_ROOM_LUX) != (lux < BRIGHT_ROOM_LUX) ||
                    (lastRecordedLux <= DEEP_DARK_LUX) != (lux <= DEEP_DARK_LUX)
                )
            val significantJump = lastRecordedLux.isNaN() || abs(lux - lastRecordedLux) >= 15f
            val shouldAppend = count == 0 ||
                lastRecordedLux.isNaN() ||
                dt >= MIN_SAMPLE_INTERVAL_MS ||
                ((crossedBoundary || significantJump) && dt >= FAST_CHANGE_MIN_INTERVAL_MS)

            if (shouldAppend) {
                appendSample(nowMs, lux)
            }
        }
    }

    /**
     * 화면 꺼짐 등으로 센서 관측이 중단된 시점을 표시합니다.
     * 중단 기간 동안 이전 밝기/암실 상태가 그대로 유지된 것처럼 과대 계산되는 현상을 방지합니다.
     */
    fun recordGap(nowMs: Long) {
        synchronized(lock) {
            deepDarkSinceMs = Long.MIN_VALUE
            if (count > 0 && !lastRecordedLux.isNaN()) {
                appendSample(nowMs, Float.NaN)
            }
        }
    }

    /**
     * 현재 시점(`nowMs`) 기준으로 "밝은 공간 안의 머리/물체 그림자" 가능성을 평가합니다.
     * `TYPE_LIGHT`가 정적인 어둠 속에서 새 이벤트를 보내지 않더라도 `nowMs` 경과를 반영해
     * 실제 방 소등 여부를 자동으로 판정합니다.
     */
    fun evaluate(
        nowMs: Long,
        currentLux: Float?,
        isOccluded: Boolean,
        isBedtimeWindow: Boolean,
        isLyingDown: Boolean,
        isTestMode: Boolean = false
    ): Assessment {
        synchronized(lock) {
            if (isOccluded || currentLux == null || !currentLux.isFinite() || currentLux < 0f) {
                return Assessment(
                    isLikelyShadowInLitRoom = false,
                    recentPeakLux = null,
                    recentBrightHoldMs = 0L,
                    continuousDeepDarkMs = 0L
                )
            }

            val windowStart = nowMs - ROLLING_WINDOW_MS
            var brightHoldMs = 0L
            var peakLux = Float.NaN

            if (count > 0) {
                val oldestIdx = (head - count + CAPACITY) and MASK
                for (i in 0 until count) {
                    val idx = (oldestIdx + i) and MASK
                    val segStart = timestamps[idx]
                    val segEnd = if (i + 1 < count) {
                        timestamps[(oldestIdx + i + 1) and MASK]
                    } else {
                        nowMs
                    }
                    if (segEnd <= windowStart || segStart > nowMs) continue

                    val clampedStart = max(segStart, windowStart)
                    val clampedEnd = min(segEnd, nowMs)
                    val holdMs = (clampedEnd - clampedStart).coerceAtLeast(0L)
                    val sampleLux = luxValues[idx]
                    if (sampleLux.isNaN()) continue

                    // 0.5초 이상 유지됐거나 최신 샘플이면 피크 조도에 반영 (순간 노이즈 배제)
                    if (holdMs >= PEAK_MIN_HOLD_MS || i == count - 1) {
                        if (peakLux.isNaN() || sampleLux > peakLux) {
                            peakLux = sampleLux
                        }
                    }
                    if (sampleLux >= BRIGHT_ROOM_LUX) {
                        brightHoldMs += holdMs
                    }
                }
            }
            if (peakLux.isNaN()) {
                peakLux = currentLux
            }

            val continuousDeepDarkMs = if (currentLux <= DEEP_DARK_LUX && deepDarkSinceMs != Long.MIN_VALUE) {
                (nowMs - deepDarkSinceMs).coerceAtLeast(0L)
            } else {
                0L
            }

            // 현재 자체가 밝은 환경이면 그림자 가드 불필요
            if (currentLux > ContextualPreInterventionPolicy.DARK_LUX_THRESHOLD) {
                return Assessment(
                    isLikelyShadowInLitRoom = false,
                    recentPeakLux = peakLux.takeIf { !it.isNaN() },
                    recentBrightHoldMs = brightHoldMs,
                    continuousDeepDarkMs = 0L
                )
            }

            // 최근 3분 내 45 lx 이상 밝은 환경이 누적 3초 이상 관찰되었다면 "조명 켜진 방"으로 판단
            val recentlyLitRoom = brightHoldMs >= BRIGHT_CONFIRM_HOLD_MS
            val lightsOffOverrideMs = when {
                isTestMode -> TEST_LIGHTS_OFF_OVERRIDE_MS
                isBedtimeWindow || isLyingDown -> BEDTIME_LIGHTS_OFF_OVERRIDE_MS
                else -> DAYTIME_LIGHTS_OFF_OVERRIDE_MS
            }
            val confirmedRealLightsOff = continuousDeepDarkMs >= lightsOffOverrideMs

            val isLikelyShadow = recentlyLitRoom && !confirmedRealLightsOff
            return Assessment(
                isLikelyShadowInLitRoom = isLikelyShadow,
                recentPeakLux = peakLux.takeIf { !it.isNaN() },
                recentBrightHoldMs = brightHoldMs,
                continuousDeepDarkMs = continuousDeepDarkMs
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            head = 0
            count = 0
            lastRecordedLux = Float.NaN
            lastRecordedAtMs = Long.MIN_VALUE
            deepDarkSinceMs = Long.MIN_VALUE
        }
    }

    private fun appendSample(nowMs: Long, lux: Float) {
        timestamps[head] = nowMs
        luxValues[head] = lux
        head = (head + 1) and MASK
        if (count < CAPACITY) count++
        lastRecordedLux = lux
        lastRecordedAtMs = nowMs
    }

    companion object {
        private const val CAPACITY = 64 // 반드시 2의 거듭제곱
        private const val MASK = CAPACITY - 1

        /** 최근 조도 이력을 보는 롤링 윈도우 (3분) */
        const val ROLLING_WINDOW_MS = 180_000L
        /** 실내 조명/주간 환경으로 인정하는 밝기 기준 (45 lx 이상) */
        const val BRIGHT_ROOM_LUX = 45f
        /** 0.5초 스쳐 지나간 플래시가 아닌 실제 밝은 공간으로 인정하는 최소 유지 시간 (3초) */
        const val BRIGHT_CONFIRM_HOLD_MS = 3_000L
        /** 피크 조도에 반영할 최소 유지 시간 (0.5초) */
        private const val PEAK_MIN_HOLD_MS = 500L

        /** 진짜 방 불을 끈 완전 암실 기준 (5 lx 이하) */
        const val DEEP_DARK_LUX = 5f
        /** 완전 암실 유지 허용 상한 (미세 센서 노이즈 7 lx 초과 시 소등 연속 타이머 리셋) */
        private const val SHADOW_SAFE_DEEP_TOLERANCE_LUX = 7f
        /** 명백한 빛 유입으로 완전 암실 타이머를 즉시 리셋하는 기준 */
        private const val DEEP_DARK_BREAK_LUX = 20f

        /** 최근 밝았더라도 이 시간 이상 연속 완전 암실(<= 5 lx)이면 진짜 소등으로 인정 */
        const val BEDTIME_LIGHTS_OFF_OVERRIDE_MS = 45_000L
        const val DAYTIME_LIGHTS_OFF_OVERRIDE_MS = 90_000L
        const val TEST_LIGHTS_OFF_OVERRIDE_MS = 5_000L

        private const val MIN_SAMPLE_INTERVAL_MS = 3_000L
        private const val FAST_CHANGE_MIN_INTERVAL_MS = 400L
    }
}
