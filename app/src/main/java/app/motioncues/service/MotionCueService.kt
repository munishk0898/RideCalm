package app.motioncues.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.motioncues.detection.VehicleDetector
import app.motioncues.detection.VehicleState
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
 * Foreground service hosting the motion cue overlay.
 *
 * - On: overlay and sensors always running (paused while the screen is off).
 * - Automatic: stays in the foreground with sensors off, listening for IN_VEHICLE transitions;
 *   shows the overlay only while in a vehicle. Keeping the service alive avoids Android 12+
 *   restrictions on starting foreground services from the background.
 * - Off: the service stops itself.
 */
class MotionCueService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var repository: SettingsRepository
    private lateinit var sensors: SensorController
    private lateinit var overlay: OverlayController
    private lateinit var detector: VehicleDetector

    private var stateJob: Job? = null
    private var status: ServiceStatus = ServiceStatus.WAITING_FOR_VEHICLE
    private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    sensors.stop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    if (overlay.isShowing) sensors.start()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository.get(this)
        sensors = SensorController(this)
        overlay = OverlayController(this, sensors::signal)
        detector = VehicleDetector(this)
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
        detector.stop()
        overlay.hide()
        sensors.stop()
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    private fun render(settings: CueSettings, inVehicle: Boolean) {
        val newStatus = when (settings.mode) {
            ActivationMode.OFF -> {
                shutdown()
                return
            }
            ActivationMode.ON -> {
                detector.stop()
                showCues(settings)
            }
            ActivationMode.AUTOMATIC -> {
                if (!detector.start()) {
                    hideCues()
                    ServiceStatus.NEEDS_ACTIVITY_PERMISSION
                } else if (inVehicle) {
                    showCues(settings)
                } else {
                    hideCues()
                    ServiceStatus.WAITING_FOR_VEHICLE
                }
            }
        }
        if (newStatus != status) {
            status = newStatus
            CueNotifications.update(this, newStatus)
        }
    }

    private fun showCues(settings: CueSettings): ServiceStatus {
        if (!overlay.show(settings)) {
            sensors.stop()
            return ServiceStatus.NEEDS_OVERLAY_PERMISSION
        }
        overlay.update(settings)
        if (screenOn) sensors.start()
        return ServiceStatus.ACTIVE
    }

    private fun hideCues() {
        overlay.hide()
        sensors.stop()
    }

    private fun shutdown() {
        hideCues()
        detector.stop()
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
        const val ACTION_TURN_OFF = "app.motioncues.action.TURN_OFF"
    }
}
