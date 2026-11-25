package app.motioncues.detection

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.motioncues.util.Permissions
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide "are we in a vehicle" state, fed by Play Services activity transitions. */
object VehicleState {
    private val _inVehicle = MutableStateFlow(false)
    val inVehicle: StateFlow<Boolean> = _inVehicle.asStateFlow()

    fun update(inVehicle: Boolean) {
        _inVehicle.value = inVehicle
    }
}

/**
 * Subscribes to IN_VEHICLE enter/exit transitions with Google Play Services'
 * ActivityRecognitionClient. Transitions are low power: the system batches detection and only
 * wakes the app when the state changes.
 */
class VehicleDetector(context: Context) {

    private val appContext = context.applicationContext
    private val client = ActivityRecognition.getClient(appContext)

    var isRegistered = false
        private set

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(appContext, VehicleTransitionReceiver::class.java)
            .setAction(VehicleTransitionReceiver.ACTION_TRANSITION)
        // Must be mutable: Play Services fills in the transition result extras.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent, flags)
    }

    /** @return false if detection cannot run (permission missing or Play Services unavailable). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRegistered) return true
        if (!Permissions.hasActivityRecognition(appContext) || !Permissions.isPlayServicesAvailable(appContext)) {
            return false
        }
        val transitions = listOf(
            ActivityTransition.ACTIVITY_TRANSITION_ENTER,
            ActivityTransition.ACTIVITY_TRANSITION_EXIT,
        ).map { type ->
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(type)
                .build()
        }
        return try {
            client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent)
                .addOnFailureListener { error ->
                    Log.w(TAG, "Activity transition registration failed", error)
                    isRegistered = false
                }
            isRegistered = true
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Activity recognition permission missing", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isRegistered) return
        isRegistered = false
        try {
            client.removeActivityTransitionUpdates(pendingIntent)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not remove activity transition updates", e)
        }
        VehicleState.update(false)
    }

    private companion object {
        const val TAG = "VehicleDetector"
        const val REQUEST_CODE = 42
    }
}

/** Receives IN_VEHICLE enter/exit events from Play Services. */
class VehicleTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val latest = result.transitionEvents
            .filter { it.activityType == DetectedActivity.IN_VEHICLE }
            .maxByOrNull { it.elapsedRealTimeNanos }
            ?: return
        VehicleState.update(latest.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
    }

    companion object {
        const val ACTION_TRANSITION = "app.motioncues.action.VEHICLE_TRANSITION"
    }
}
