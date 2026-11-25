package app.motioncues.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Display
import android.view.Surface
import app.motioncues.motion.MotionProcessor
import app.motioncues.motion.MotionSignal
import app.motioncues.motion.Vec3
import app.motioncues.util.Permissions

/**
 * Owns the sensor registrations and feeds a [MotionProcessor] on a background thread.
 * Tracks the display rotation (including 0°/180° flips, which do not cause a configuration
 * change) so the dots always follow the vehicle whatever way the phone is held.
 *
 * Two rates keep power and CPU low:
 * - [Rate.FULL] (50 Hz, with gyroscope) while dots are on screen,
 * - [Rate.PROBE] (10 Hz, no gyroscope) while Automatic mode only checks for a vehicle.
 */
class SensorController(context: Context) {

    enum class Rate(val periodUs: Int) {
        FULL(20_000),
        PROBE(100_000),
    }

    /** Callbacks run on the sensor thread. */
    interface Listener {
        fun onMotionSample(timestampNs: Long, horizontalAccel: Float, verticalVibrationRms: Float)
        fun onStep(timestampNs: Long)
    }

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val processor = MotionProcessor()

    private val linearSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val stepSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var thread: HandlerThread? = null
    private var currentRate: Rate? = null
    private var currentSteps = false

    @Volatile
    var listener: Listener? = null

    @Volatile
    var isRunning = false
        private set

    /** The device can detect acceleration at all (either a linear or a raw accelerometer). */
    val hasMotionSensors: Boolean get() = linearSensor != null || accelSensor != null

    /** Latest filtered motion, or zero when stopped. Safe to call from any thread. */
    fun signal(): MotionSignal = if (isRunning) processor.signal else MotionSignal.ZERO

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> {
                    listener?.onStep(event.timestamp)
                    return
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    processor.onLinearAcceleration(vec(event), event.timestamp)
                    notifySample(event.timestamp)
                }
                Sensor.TYPE_GRAVITY -> processor.onGravity(vec(event), event.timestamp)
                Sensor.TYPE_GYROSCOPE -> processor.onGyroscope(vec(event), event.timestamp)
                Sensor.TYPE_ACCELEROMETER -> {
                    val derive = linearSensor == null
                    processor.onRawAccelerometer(vec(event), event.timestamp, deriveLinear = derive)
                    if (derive) notifySample(event.timestamp)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private fun vec(event: SensorEvent) = Vec3(event.values[0], event.values[1], event.values[2])

    private fun notifySample(timestampNs: Long) {
        listener?.onMotionSample(timestampNs, processor.horizontalAcceleration, processor.verticalVibrationRms)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateRotation()
        }
    }

    /**
     * Starts listening at [rate], or switches rate if already running. [withSteps] adds the step
     * detector (only registered if the activity recognition permission is granted).
     * Must be called from the main thread.
     */
    fun start(rate: Rate = Rate.FULL, withSteps: Boolean = false) {
        val manager = sensorManager ?: return
        if (!hasMotionSensors) return
        val steps = withSteps && stepSensor != null && Permissions.hasActivityRecognition(appContext)
        if (isRunning && rate == currentRate && steps == currentSteps) return
        if (isRunning) stop()

        processor.reset()
        updateRotation()

        val sensorThread = HandlerThread("MotionCueSensors", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        thread = sensorThread
        val handler = Handler(sensorThread.looper)
        val period = rate.periodUs
        // Let the sensor hub batch samples while probing so the CPU can stay idle longer.
        val latency = if (rate == Rate.PROBE) 1_000_000 else 0

        linearSensor?.let { manager.registerListener(sensorListener, it, period, latency, handler) }
        gravitySensor?.let { manager.registerListener(sensorListener, it, period, latency, handler) }
        if (rate == Rate.FULL) {
            gyroSensor?.let { manager.registerListener(sensorListener, it, period, latency, handler) }
        }
        if (linearSensor == null || gravitySensor == null) {
            accelSensor?.let { manager.registerListener(sensorListener, it, period, latency, handler) }
        }
        if (steps) {
            stepSensor?.let { manager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        }
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        currentRate = rate
        currentSteps = steps
        isRunning = true
    }

    /** Stops listening and releases the sensor thread. Must be called from the main thread. */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        currentRate = null
        currentSteps = false
        sensorManager?.unregisterListener(sensorListener)
        displayManager?.unregisterDisplayListener(displayListener)
        thread?.quitSafely()
        thread = null
    }

    private fun updateRotation() {
        processor.rotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }
}
