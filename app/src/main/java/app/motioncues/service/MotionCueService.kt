package app.motioncues.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.motioncues.detection.VehicleState
import app.motioncues.motion.VehicleClassifier
import app.motioncues.overlay.OverlayController
import app.motioncues.sensors.SensorController
import app.motioncues.settings.ActivationMode
import app.motioncues.settings.CueSettings
import app.motioncues.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the motion cue overlay. Everything runs on the phone: no network,
 * no Google Play services.
 *
 * - On: overlay and full-rate sensors while the screen is on.
 * - Automatic: while the screen is on, sensors run at a low rate and the on-device
 *   [VehicleClassifier] decides when you are riding; the overlay appears only then.
 *   With the screen off, all sensors are off.
 * - Off: the service stops itself.
 */
class MotionCueService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var repository: SettingsRepository
    private lateinit var sensors: SensorController
    private lateinit var overlay: OverlayController

    private val classifier = VehicleClassifier()
    private var stateJob: Job? = null
    private var status: ServiceStatus = ServiceStatus.WAITING_FOR_VEHICLE
    private var mode: ActivationMode = ActivationMode.OFF
    private var screenOn = true
    private var screenOffAtMs = 0L

    private val detectionListener = object : SensorController.Listener {
        override fun onMotionSample(timestampNs: Long, horizontalAccel: Float, verticalVibrationRms: Float) {
            val changed = synchronized(classifier) {
                classifier.onSample(timestampNs / 1e9, horizontalAccel, verticalVibrationRms)
            }
            if (changed) publishVehicleState()
        }

        override fun onStep(timestampNs: Long) {
            val changed = synchronized(classifier) { classifier.onStep(timestampNs / 1e9) }
            if (changed) publishVehicleState()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    screenOffAtMs = SystemClock.elapsedRealtime()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // After a long screen-off the trip may be over: detect again from scratch.
                    if (SystemClock.elapsedRealtime() - screenOffAtMs > STALE_AFTER_MS) {
                        synchronized(classifier) { classifier.reset() }
                        VehicleState.update(false)
                    }
                }
            }
            updateSensors()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository.get(this)
        sensors = SensorController(this).also { it.listener = detectionListener }
        overlay = OverlayController(this, sensors::signal)
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        CueNotifications.ensureChannel(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called promptly after startForegroundService(), on every start.
        if (!enterForeground()) return START_NOT_STICKY

        if (intent?.action == ACTION_TURN_OFF) {
            // The settings collector sees OFF and stops the service.
            scope.launch { repository.setMode(ActivationMode.OFF) }
            return START_NOT_STICKY
        }

        if (stateJob == null) {
            stateJob = scope.launch {
                combine(repository.settings, VehicleState.inVehicle) { settings, inVehicle ->
                    settings to inVehicle
                }.collect { (settings, inVehicle) -> render(settings, inVehicle) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        sensors.listener = null
        overlay.hide()
        sensors.stop()
        VehicleState.update(false)
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    private fun render(settings: CueSettings, inVehicle: Boolean) {
        if (settings.mode != mode && settings.mode != ActivationMode.AUTOMATIC) {
            synchronized(classifier) { classifier.reset() }
        }
        mode = settings.mode
        val newStatus = when (settings.mode) {
            ActivationMode.OFF -> {
                shutdown()
                return
            }
            ActivationMode.ON -> showCues(settings)
            ActivationMode.AUTOMATIC -> if (inVehicle) {
                showCues(settings)
            } else {
                overlay.hide()
                ServiceStatus.WAITING_FOR_VEHICLE
            }
        }
        updateSensors()
        if (newStatus != status) {
            status = newStatus
            CueNotifications.update(this, newStatus)
        }
    }

    private fun showCues(settings: CueSettings): ServiceStatus =
        if (overlay.show(settings)) ServiceStatus.ACTIVE else ServiceStatus.NEEDS_OVERLAY_PERMISSION

    /** Runs sensors only as much as the current mode, screen and overlay need. */
    private fun updateSensors() {
        when {
            !screenOn || mode == ActivationMode.OFF -> sensors.stop()
            mode == ActivationMode.ON ->
                if (overlay.isShowing) sensors.start(SensorController.Rate.FULL) else sensors.stop()
            else -> sensors.start(
                rate = if (overlay.isShowing) SensorController.Rate.FULL else SensorController.Rate.PROBE,
                withSteps = true,
            )
        }
    }

    private fun publishVehicleState() {
        mainHandler.post {
            val inVehicle = synchronized(classifier) { classifier.inVehicle }
            VehicleState.update(inVehicle)
        }
    }

    private fun shutdown() {
        overlay.hide()
        sensors.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun enterForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(
                this,
                CueNotifications.NOTIFICATION_ID,
                CueNotifications.build(this, status),
                type,
            )
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not enter the foreground", e)
            stopSelf()
            false
        }
    }

    companion object {
        private const val TAG = "MotionCueService"
        private const val STALE_AFTER_MS = 3 * 60_000L
        const val ACTION_TURN_OFF = "app.motioncues.action.TURN_OFF"
    }
}
