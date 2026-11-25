package app.motioncues.motion

/**
 * Vehicle motion expressed in the viewer's frame, in m/s².
 *
 * @property longitudinal positive while speeding up, negative while braking.
 * @property lateral positive when the dots should drift right (a left turn), negative for a right turn.
 */
data class MotionSignal(val longitudinal: Float, val lateral: Float) {
    operator fun plus(o: MotionSignal) = MotionSignal(longitudinal + o.longitudinal, lateral + o.lateral)

    companion object {
        val ZERO = MotionSignal(0f, 0f)
    }
}

/**
 * Sensor fusion for motion cues.
 *
 * Inputs (device coordinates, as delivered by Android's SensorManager):
 * - gravity (`TYPE_GRAVITY`), or raw accelerometer as a fallback,
 * - linear acceleration (`TYPE_LINEAR_ACCELERATION`, gravity already removed),
 * - angular rate (`TYPE_GYROSCOPE`).
 *
 * Linear acceleration is projected onto the horizontal vehicle frame ([VehicleFrame]) to get
 * forward/braking and sideways components. The yaw rate (rotation about the gravity axis) adds a
 * turn cue that does not depend on speed. Each channel is low-pass filtered with a time constant
 * (sample-rate independent) and passed through a soft dead zone so road vibration and hand
 * tremor do not move the dots.
 *
 * Not thread safe for input: call the `on*` methods from one thread (the sensor thread).
 * [signal] and [rotation] may be read or written from any thread.
 */
class MotionProcessor(private val config: Config = Config()) {

    data class Config(
        /** Smoothing for horizontal acceleration. Larger is smoother but laggier. */
        val accelTimeConstantSec: Float = 0.15f,
        /** Smoothing for the yaw rate. */
        val yawTimeConstantSec: Float = 0.2f,
        /** Smoothing of the gravity direction (defines the horizontal plane). */
        val gravityTimeConstantSec: Float = 0.3f,
        /** Gravity estimate from a raw accelerometer when no gravity sensor exists. */
        val rawGravityTimeConstantSec: Float = 0.8f,
        /** Acceleration below this (m/s²) is treated as noise. */
        val accelDeadZone: Float = 0.15f,
        /** Yaw rate below this (rad/s) is treated as noise. */
        val yawDeadZone: Float = 0.04f,
        /** Converts yaw rate (rad/s) into an equivalent lateral cue (m/s²). */
        val yawToLateralGain: Float = 4f,
        /** Averaging window for the vertical vibration RMS used by vehicle detection. */
        val vibrationTimeConstantSec: Float = 1.0f,
        /** Gaps longer than this between samples are clamped (e.g. after the CPU slept). */
        val maxDtSec: Float = 0.1f,
    )

    /** Current display rotation, 0..3 (Surface.ROTATION_*). */
    @Volatile
    var rotation: Int = ScreenAxes.ROTATION_0

    private val gravityFilter = Vec3LowPassFilter(config.gravityTimeConstantSec)
    private val rawGravityFilter = Vec3LowPassFilter(config.rawGravityTimeConstantSec)
    private val longitudinalFilter = LowPassFilter(config.accelTimeConstantSec)
    private val lateralFilter = LowPassFilter(config.accelTimeConstantSec)
    private val yawFilter = LowPassFilter(config.yawTimeConstantSec)
    private val verticalPowerFilter = LowPassFilter(config.vibrationTimeConstantSec)

    private var gravity: Vec3? = null
    private var hasGravitySensor = false
    private var lastGravityNs = 0L
    private var lastRawNs = 0L
    private var lastLinearNs = 0L
    private var lastGyroNs = 0L

    private var longitudinal = 0f
    private var lateral = 0f
    private var yaw = 0f

    @Volatile
    private var output: MotionSignal = MotionSignal.ZERO

    /** Latest filtered motion signal. */
    val signal: MotionSignal get() = output

    /** Smoothed horizontal acceleration magnitude (m/s²), excluding the yaw cue. */
    @Volatile
    var horizontalAcceleration: Float = 0f
        private set

    /** RMS of acceleration along gravity over about a second (road vibration, footsteps). */
    @Volatile
    var verticalVibrationRms: Float = 0f
        private set

    fun onGravity(value: Vec3, timestampNs: Long) {
        hasGravitySensor = true
        gravity = gravityFilter.filter(value, dt(timestampNs, lastGravityNs))
        lastGravityNs = timestampNs
    }

    /**
     * Raw accelerometer fallback. Always used to estimate gravity when there is no gravity sensor,
     * and also to derive linear acceleration when [deriveLinear] is true (no linear sensor).
     */
    fun onRawAccelerometer(value: Vec3, timestampNs: Long, deriveLinear: Boolean) {
        val estimatedGravity = rawGravityFilter.filter(value, dt(timestampNs, lastRawNs))
        lastRawNs = timestampNs
        if (!hasGravitySensor) gravity = estimatedGravity
        if (deriveLinear) onLinearAcceleration(value - estimatedGravity, timestampNs)
    }

    fun onLinearAcceleration(value: Vec3, timestampNs: Long) {
        val dt = dt(timestampNs, lastLinearNs)
        lastLinearNs = timestampNs
        val basis = currentBasis() ?: return
        longitudinal = longitudinalFilter.filter(value dot basis.forward, dt)
        lateral = lateralFilter.filter(value dot basis.right, dt)
        val vertical = value dot basis.up
        verticalVibrationRms = kotlin.math.sqrt(verticalPowerFilter.filter(vertical * vertical, dt))
        publish()
    }

    fun onGyroscope(value: Vec3, timestampNs: Long) {
        val dt = dt(timestampNs, lastGyroNs)
        lastGyroNs = timestampNs
        val basis = currentBasis() ?: return
        // Positive yaw = counter-clockwise seen from above = turning left.
        yaw = yawFilter.filter(value dot basis.up, dt)
        publish()
    }

    fun reset() {
        gravityFilter.reset(); rawGravityFilter.reset()
        longitudinalFilter.reset(); lateralFilter.reset(); yawFilter.reset(); verticalPowerFilter.reset()
        horizontalAcceleration = 0f
        verticalVibrationRms = 0f
        gravity = null
        hasGravitySensor = false
        lastGravityNs = 0L; lastRawNs = 0L; lastLinearNs = 0L; lastGyroNs = 0L
        longitudinal = 0f; lateral = 0f; yaw = 0f
        output = MotionSignal.ZERO
    }

    private fun currentBasis(): VehicleBasis? = gravity?.let { VehicleFrame.basis(it, rotation) }

    private fun publish() {
        // In a left turn the centripetal acceleration points left (negative "right" component)
        // and the yaw rate is positive. Both should push the dots to the right.
        val lateralCue = deadZone(-lateral, config.accelDeadZone) +
            config.yawToLateralGain * deadZone(yaw, config.yawDeadZone)
        val lon = deadZone(longitudinal, config.accelDeadZone)
        val lat = deadZone(lateral, config.accelDeadZone)
        horizontalAcceleration = kotlin.math.sqrt(lon * lon + lat * lat)
        output = MotionSignal(
            longitudinal = lon,
            lateral = lateralCue,
        )
    }

    private fun dt(nowNs: Long, lastNs: Long): Float {
        if (lastNs == 0L || nowNs <= lastNs) return 0f
        return ((nowNs - lastNs) / 1_000_000_000f).coerceAtMost(config.maxDtSec)
    }
}
