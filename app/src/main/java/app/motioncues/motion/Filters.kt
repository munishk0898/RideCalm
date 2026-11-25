package app.motioncues.motion

/**
 * First-order exponential smoothing (low-pass filter) defined by a time constant rather than a
 * fixed alpha, so it behaves the same whatever rate the sensor delivers samples at.
 */
class LowPassFilter(private val timeConstantSec: Float) {

    init {
        require(timeConstantSec >= 0f) { "timeConstantSec must be >= 0" }
    }

    var value: Float = 0f
        private set

    private var initialized = false

    fun filter(input: Float, dtSec: Float): Float {
        if (!initialized) {
            value = input
            initialized = true
            return value
        }
        value += alpha(dtSec, timeConstantSec) * (input - value)
        return value
    }

    fun reset() {
        initialized = false
        value = 0f
    }

    companion object {
        /** Smoothing factor for a sample that arrived [dtSec] after the previous one. */
        fun alpha(dtSec: Float, timeConstantSec: Float): Float = when {
            dtSec <= 0f -> 0f
            timeConstantSec <= 0f -> 1f
            else -> dtSec / (timeConstantSec + dtSec)
        }
    }
}

/** [LowPassFilter] applied to each component of a [Vec3]. */
class Vec3LowPassFilter(timeConstantSec: Float) {
    private val fx = LowPassFilter(timeConstantSec)
    private val fy = LowPassFilter(timeConstantSec)
    private val fz = LowPassFilter(timeConstantSec)

    fun filter(input: Vec3, dtSec: Float) = Vec3(
        fx.filter(input.x, dtSec),
        fy.filter(input.y, dtSec),
        fz.filter(input.z, dtSec),
    )

    fun reset() {
        fx.reset(); fy.reset(); fz.reset()
    }
}

/**
 * Soft dead zone: values within ±[threshold] become 0 and larger values are shifted towards zero
 * by [threshold], so the output stays continuous (no jump when crossing the threshold).
 */
fun deadZone(value: Float, threshold: Float): Float = when {
    value > threshold -> value - threshold
    value < -threshold -> value + threshold
    else -> 0f
}
