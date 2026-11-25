package app.motioncues.motion

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Damped spring used to give the dots inertia. With [dampingRatio] = 1 it is critically damped:
 * it follows the target and snaps back to rest without overshoot. Lower values add a little
 * fluid overshoot.
 *
 * @param stiffness spring constant per unit mass (1/s²); natural frequency is sqrt(stiffness).
 */
class Spring(var stiffness: Float, var dampingRatio: Float = 1f) {

    var position = 0f
        private set
    var velocity = 0f
        private set

    /**
     * Advances the simulation by [dtSec] towards [target] using fixed sub-steps for stability.
     * @return absolute change in position during this step.
     */
    fun step(target: Float, dtSec: Float): Float {
        val start = position
        var remaining = dtSec.coerceIn(0f, MAX_DT_SEC)
        val omega = sqrt(stiffness)
        while (remaining > 0f) {
            val h = min(remaining, SUB_STEP_SEC)
            val acceleration = stiffness * (target - position) - 2f * dampingRatio * omega * velocity
            velocity += acceleration * h
            position += velocity * h
            remaining -= h
        }
        return abs(position - start)
    }

    fun isAtRest(target: Float, epsilon: Float = 1e-3f): Boolean =
        abs(position - target) < epsilon && abs(velocity) < epsilon

    fun reset() {
        position = 0f
        velocity = 0f
    }

    companion object {
        private const val SUB_STEP_SEC = 1f / 240f
        private const val MAX_DT_SEC = 0.1f
    }
}
