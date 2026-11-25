package app.motioncues.motion

import kotlin.math.PI
import kotlin.math.sin

/** Scripted drive used by the "Simulate Motion" preview so the dots can be tested at a desk. */
object SimulationScript {

    enum class Phase { ACCELERATING, CRUISING, TURNING_LEFT, TURNING_RIGHT, BRAKING, STOPPED }

    data class Segment(
        val phase: Phase,
        val durationSec: Float,
        val longitudinal: Float,
        val lateral: Float,
    )

    data class Sample(val phase: Phase, val signal: MotionSignal)

    val segments = listOf(
        Segment(Phase.ACCELERATING, 1.8f, longitudinal = 2.8f, lateral = 0f),
        Segment(Phase.CRUISING, 0.8f, 0f, 0f),
        Segment(Phase.TURNING_LEFT, 2.0f, longitudinal = 0f, lateral = 2.8f),
        Segment(Phase.CRUISING, 0.6f, 0f, 0f),
        Segment(Phase.TURNING_RIGHT, 2.0f, longitudinal = 0f, lateral = -2.8f),
        Segment(Phase.CRUISING, 0.6f, 0f, 0f),
        Segment(Phase.BRAKING, 1.8f, longitudinal = -3.2f, lateral = 0f),
        Segment(Phase.STOPPED, 1.0f, 0f, 0f),
    )

    val totalDurationSec: Float = segments.sumOf { it.durationSec.toDouble() }.toFloat()

    /**
     * Signal at [timeSec] from the start, with a smooth half-sine envelope per segment.
     * Returns null once the script has finished.
     */
    fun sample(timeSec: Float): Sample? {
        if (timeSec < 0f) return Sample(segments.first().phase, MotionSignal.ZERO)
        var start = 0f
        for (segment in segments) {
            val end = start + segment.durationSec
            if (timeSec < end) {
                val progress = (timeSec - start) / segment.durationSec
                val envelope = sin(PI * progress).toFloat()
                return Sample(
                    segment.phase,
                    MotionSignal(segment.longitudinal * envelope, segment.lateral * envelope),
                )
            }
            start = end
        }
        return null
    }
}
