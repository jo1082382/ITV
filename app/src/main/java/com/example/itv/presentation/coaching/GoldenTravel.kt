package com.example.itv.presentation.coaching

import kotlin.math.abs
import kotlin.math.roundToInt

/** Camera travel paired with the baked 3.51s walk and 4.54s settling turn.
 * Coordinates are the actor window's top-left on one horizontal ground plane. */
internal class GoldenTravel(private val start: Int, private val end: Int, private val size: Int) {
    val left = end < start
    val walk = if (left) GoldenMotion.SIDE_LEFT else GoldenMotion.SIDE_RIGHT
    val turn = if (left) GoldenMotion.TURN_LEFT else GoldenMotion.TURN_RIGHT
    private val direction = if (left) -1f else 1f
    private val distance = abs(end - start).toFloat()
    private val turnDistance = minOf(distance, size * turnUnits(1f) / 340f)
    private val walkDistance = distance - turnDistance

    fun walkX(u: Float) = start + (direction * walkDistance * u).roundToInt()

    fun walkPhase(u: Float, matchBothEnds: Boolean): Float {
        val cycles = walkDistance / (size * STRIDE)
        val steps = if (matchBothEnds) cycles.roundToInt().toFloat() else cycles
        val phase = TURN_PHASE - steps * (1f - u)
        return phase - kotlin.math.floor(phase)
    }

    fun turnX(u: Float) = end - (direction * turnDistance * (1f - turnUnits(u) / turnUnits(1f))).roundToInt()

    companion object {
        const val STRIDE = 57.285f / 340f
        const val TURN_PHASE = .36f / .67f

        /** Offset from the resting anchor when the same contact frames run backwards. */
        fun turningOutX(anchor: Int, size: Int, left: Boolean, u: Float): Int {
            val distance = size * (turnUnits(1f) - turnUnits(1f - u)) / 340f
            return anchor + ((if (left) 1f else -1f) * distance).roundToInt()
        }

        private fun turnUnits(u: Float): Float {
            // Integrated braking to 4.86s, followed by the four-unit pivot shift.
            val t = .98f * u.coerceIn(0f, 1f)
            val remaining = (.32f - t).coerceAtLeast(0f)
            val pivot = ((t - .22f) / .66f).coerceIn(0f, 1f)
            return 85.5f * (.32f * .32f - remaining * remaining) / .76f + 4f * smooth(pivot)
        }

        fun smooth(u: Float): Float {
            val t = u.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
