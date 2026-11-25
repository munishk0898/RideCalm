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

/**
 * Owns the sensor registrations and feeds a [MotionProcessor] on a background thread.
 * Tracks the display rotation (including 0°/180° flips, which do not cause a configuration
 * change) so the dots always follow the vehicle whatever way the phone is held.
 */
class SensorController(context: Context) {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val processor = MotionProcessor()

    private val linearSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var thread: HandlerThread? = null

    @Volatile
    var isRunning = false
        private set

    /** The device can detect acceleration at all (either a linear or a raw accelerometer). */
    val hasMotionSensors: Boolean get() = linearSensor != null || accelSensor != null

    /** Latest filtered motion, or zero when stopped. Safe to call from any thread. */
    fun signal(): MotionSignal = if (isRunning) processor.signal else MotionSignal.ZERO

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = Vec3(event.values[0], event.values[1], event.values[2])
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> processor.onLinearAcceleration(v, event.timestamp)
                Sensor.TYPE_GRAVITY -> processor.onGravity(v, event.timestamp)
                Sensor.TYPE_GYROSCOPE -> processor.onGyroscope(v, event.timestamp)
                Sensor.TYPE_ACCELEROMETER ->
                    processor.onRawAccelerometer(v, event.timestamp, deriveLinear = linearSensor == null)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateRotation()
        }
    }

    /** Starts listening. Must be called from the main thread. */
    fun start() {
        val manager = sensorManager ?: return
        if (isRunning || !hasMotionSensors) return

        processor.reset()
        updateRotation()

        val sensorThread = HandlerThread("MotionCueSensors", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        thread = sensorThread
        val handler = Handler(sensorThread.looper)
        val rate = SensorManager.SENSOR_DELAY_GAME

        linearSensor?.let { manager.registerListener(listener, it, rate, handler) }
        gravitySensor?.let { manager.registerListener(listener, it, rate, handler) }
        gyroSensor?.let { manager.registerListener(listener, it, rate, handler) }
        if (linearSensor == null || gravitySensor == null) {
            accelSensor?.let { manager.registerListener(listener, it, rate, handler) }
        }
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        isRunning = true
    }

    /** Stops listening and releases the sensor thread. Must be called from the main thread. */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        sensorManager?.unregisterListener(listener)
        displayManager?.unregisterDisplayListener(displayListener)
        thread?.quitSafely()
        thread = null
    }

    private fun updateRotation() {
        processor.rotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }
}
