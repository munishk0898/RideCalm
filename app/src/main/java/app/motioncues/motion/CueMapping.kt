package app.motioncues.motion

import kotlin.math.tanh

/** Normalised dot displacement in -1..1. Screen convention: +x right, +y down. */
data class CueOffset(val x: Float, val y: Float) {
    companion object {
        val ZERO = CueOffset(0f, 0f)
    }
}

/** Translates a [MotionSignal] into a dot displacement. */
object CueMapping {

    /** Acceleration (m/s²) that moves the dots about 76% of their range at sensitivity 1. */
    const val FULL_SCALE_MS2 = 2.5f

    /** Soft-saturating mapping: linear for gentle motion, never exceeds ±1. */
    fun normalize(signalMs2: Float, sensitivity: Float): Float =
        tanh(signalMs2 * sensitivity / FULL_SCALE_MS2)

    /**
     * Forward acceleration moves the dots down, braking moves them up.
     * Turning moves them away from the turn (left turn, dots drift right).
     */
    fun targetOffset(signal: MotionSignal, sensitivity: Float) = CueOffset(
        x = normalize(signal.lateral, sensitivity),
        y = normalize(signal.longitudinal, sensitivity),
    )
}
