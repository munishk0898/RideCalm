package app.motioncues.motion

/**
 * On-device "am I riding in a vehicle?" detector. No network, no Google Play services.
 *
 * Idea: a vehicle produces *sustained* horizontal acceleration (speeding up, braking, turning)
 * lasting a second or more, while the person is not taking steps. A hand moving a phone only
 * accelerates it for a fraction of a second, and walking produces steps (or, without a step
 * sensor, strong rhythmic vertical vibration).
 *
 * - Enter: at least [Config.eventsToEnter] sustained acceleration events within
 *   [Config.enterWindowSec], and no steps in the last [Config.quietBeforeEnterSec].
 * - Exit: walking ([Config.stepsToExit] steps within [Config.stepWindowSec]) or no sustained
 *   motion for [Config.idleExitSec] (parked).
 *
 * Times are in seconds on any monotonic clock. Not thread safe; call from one thread.
 */
class VehicleClassifier(private val config: Config = Config()) {

    data class Config(
        /** Smoothed horizontal acceleration (m/s², after the processor's dead zone). */
        val sustainedAccelThreshold: Float = 0.3f,
        val sustainedDurationSec: Double = 1.5,
        val eventsToEnter: Int = 2,
        val enterWindowSec: Double = 45.0,
        val stepsToExit: Int = 4,
        val stepWindowSec: Double = 10.0,
        val quietBeforeEnterSec: Double = 20.0,
        /** Vertical vibration RMS (m/s²) treated as walking when there is no step sensor. */
        val walkingVibrationRms: Float = 1.2f,
        val walkingProxyStepIntervalSec: Double = 0.5,
        val idleExitSec: Double = 180.0,
    )

    var inVehicle = false
        private set

    private var aboveSince = NONE
    private var eventCounted = false
    private var lastActivity = NONE
    private var lastProxyStep = NONE
    private val events = ArrayDeque<Double>()
    private val steps = ArrayDeque<Double>()

    /** A step from the step detector sensor. Returns true if [inVehicle] changed. */
    fun onStep(timeSec: Double): Boolean {
        steps.addLast(timeSec)
        return evaluate(timeSec)
    }

    /**
     * A motion sample. Returns true if [inVehicle] changed.
     * @param horizontalAccel smoothed horizontal acceleration magnitude, m/s².
     * @param verticalVibrationRms RMS of acceleration along gravity, m/s².
     */
    fun onSample(timeSec: Double, horizontalAccel: Float, verticalVibrationRms: Float): Boolean {
        if (verticalVibrationRms >= config.walkingVibrationRms &&
            (lastProxyStep == NONE || timeSec - lastProxyStep >= config.walkingProxyStepIntervalSec)
        ) {
            steps.addLast(timeSec)
            lastProxyStep = timeSec
        }

        if (horizontalAccel >= config.sustainedAccelThreshold) {
            if (aboveSince == NONE) aboveSince = timeSec
            if (!eventCounted && timeSec - aboveSince >= config.sustainedDurationSec) {
                events.addLast(timeSec)
                eventCounted = true
                lastActivity = timeSec
            }
        } else {
            aboveSince = NONE
            eventCounted = false
        }
        return evaluate(timeSec)
    }

    fun reset() {
        inVehicle = false
        aboveSince = NONE
        eventCounted = false
        lastActivity = NONE
        lastProxyStep = NONE
        events.clear()
        steps.clear()
    }

    private fun evaluate(now: Double): Boolean {
        while (events.isNotEmpty() && now - events.first() > config.enterWindowSec) events.removeFirst()
        val stepHorizon = maxOf(config.stepWindowSec, config.quietBeforeEnterSec)
        while (steps.isNotEmpty() && now - steps.first() > stepHorizon) steps.removeFirst()

        val before = inVehicle
        if (inVehicle) {
            val recentSteps = steps.count { now - it <= config.stepWindowSec }
            val idle = lastActivity != NONE && now - lastActivity > config.idleExitSec
            if (recentSteps >= config.stepsToExit || idle) {
                inVehicle = false
                events.clear()
            }
        } else {
            val quiet = steps.none { now - it <= config.quietBeforeEnterSec }
            if (events.size >= config.eventsToEnter && quiet) {
                inVehicle = true
                lastActivity = now
            }
        }
        return inVehicle != before
    }

    private companion object {
        const val NONE = -1.0
    }
}
