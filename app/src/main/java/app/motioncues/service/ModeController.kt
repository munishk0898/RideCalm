package app.motioncues.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.motioncues.settings.ActivationMode
import app.motioncues.settings.SettingsRepository
import app.motioncues.util.Permissions

/** Keeps the foreground service in step with the selected [ActivationMode]. */
object ModeController {

    private const val TAG = "ModeController"

    /**
     * Starts or stops [MotionCueService] to match [mode].
     *
     * @return false when the service should run but could not be started, either because the
     * overlay permission is missing or because Android refused a foreground service start from
     * the background. Callers can then bring the app to the front, which always succeeds.
     */
    fun apply(context: Context, mode: ActivationMode): Boolean {
        val app = context.applicationContext
        val intent = Intent(app, MotionCueService::class.java)
        if (mode == ActivationMode.OFF) {
            app.stopService(intent)
            return true
        }
        if (!Permissions.canDrawOverlays(app)) return false
        return try {
            ContextCompat.startForegroundService(app, intent)
            true
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException (Android 12+).
            Log.w(TAG, "Foreground service start not allowed right now", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Foreground service start rejected", e)
            false
        }
    }

    /** Persists [mode] and applies it. */
    suspend fun setMode(context: Context, mode: ActivationMode): Boolean {
        SettingsRepository.get(context).setMode(mode)
        return apply(context, mode)
    }
}
