package com.example.itv.presentation.coaching

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Restores the floor-plane translation removed by the drawing camera. The
 * destination is the actor window's top-left at rest. Legs and window are
 * sampled at the same baked instant, including when the frame rate drops.
 */
internal class GoldenTravel(
    private val endX: Int, private val endY: Int, private val size: Int,
    val left: Boolean, val cycles: Int
) {
    val walk = if (left) GoldenMotion.ENTRY_LEFT else GoldenMotion.ENTRY_RIGHT
    val turn = if (left) GoldenMotion.FACE_LEFT else GoldenMotion.FACE_RIGHT
    private val sign = if (left) -1f else 1f
    private val dx = sign * ENTRY_HORIZONTAL * size / VIEWBOX
    private val dy = ENTRY_DEPTH * size / VIEWBOX

    private fun steps(u: Float) = floor(u.coerceIn(0f, 1f) * cycles * WALK_FRAMES + .0001f).toInt()
    fun walkPhase(u: Float) = ((WALK_FRAMES / 2 + steps(u)) % WALK_FRAMES).toFloat() / WALK_FRAMES
    private fun remainingWalk(u: Float) =
        ENTRY_STRIDE * (cycles - steps(u).toFloat() / WALK_FRAMES) + brake(1f)

    fun walkX(u: Float) = endX - (dx * remainingWalk(u)).roundToInt()
    fun walkY(u: Float) = endY - (dy * remainingWalk(u)).roundToInt()

    private fun remainingTurn(u: Float) = brake(1f) - brake(turnPhase(u))
    fun turnX(u: Float) = endX - (dx * remainingTurn(u)).roundToInt()
    fun turnY(u: Float) = endY - (dy * remainingTurn(u)).roundToInt()

    // Reverse the approach's last pivot at the entry edge before the first stride.
    fun launchX(u: Float) = endX - (dx * (cycles * ENTRY_STRIDE + remainingTurn(u))).roundToInt()
    fun launchY(u: Float) = endY - (dy * (cycles * ENTRY_STRIDE + remainingTurn(u))).roundToInt()

    companion object {
        const val VIEWBOX = 340f
        const val WALK_FRAMES = 20
        const val ENTRY_STRIDE = 57.285f
        private const val ENTRY_HORIZONTAL = .743144825f  // sin(48 degrees)
        private const val ENTRY_DEPTH = .14051743f        // .21 * cos(48 degrees)
        const val SIDE_STRIDE = 88f / VIEWBOX
        const val SIDE_PHASE = .5f

        fun entryRun(cycles: Int, size: Int) = (cycles * size * ENTRY_STRIDE * ENTRY_HORIZONTAL / VIEWBOX).roundToInt()
        fun turnPhase(u: Float) = floor(u.coerceIn(0f, 1f) * 21 + .0001f) / 21f
        // Root track in rig units for the 22 side turn frames. The new longer
        // stride blends into the pivot over six frames. Recovering translation
        // from the median of adjacent planted toes captures that hand-off.
        private val sideTurnRoot = floatArrayOf(
            0f, 6.119f, 11.198f, 17.002f, 21.389f, 24.039f, 25.283f, 26.261f,
            26.529f, 26.861f, 27.240f, 27.649f, 28.072f, 28.491f, 28.890f,
            28.890f, 28.890f, 28.890f, 29.031f, 29.058f, 29.058f, 29.058f
        )

        fun sideTurnX(anchor: Int, size: Int, left: Boolean, phase: Float): Int {
            val index = (phase.coerceIn(0f, 1f) * sideTurnRoot.lastIndex + .0001f).toInt()
            val sampled = if (left && index >= 19) {
                if (index == 19) 28.988f else 28.929f
            } else sideTurnRoot[index]
            val total = if (left) 28.929f else sideTurnRoot.last()
            val offset = size * (total - sampled) / VIEWBOX
            return anchor + ((if (left) 1f else -1f) * offset).roundToInt()
        }

        /** Authored braking beginning at 4.54s, then a four-unit shift in the turn. */
        private fun brake(u: Float): Float {
            val t = .98f * u
            val remaining = (.32f - t).coerceAtLeast(0f)
            return 85.5f * (.32f * .32f - remaining * remaining) / .76f + 4f * smooth((t - .22f) / .66f)
        }

        fun smooth(u: Float): Float {
            val t = u.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
