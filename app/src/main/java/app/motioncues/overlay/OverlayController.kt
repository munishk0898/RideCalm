package app.motioncues.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import app.motioncues.motion.MotionSignal
import app.motioncues.settings.CueSettings

/**
 * Adds and removes the full-screen overlay window. The window never takes focus or touches,
 * so the apps, videos and games underneath keep working normally.
 */
class OverlayController(
    context: Context,
    private val signalProvider: () -> MotionSignal,
) {

    private val appContext = context.applicationContext

    /** On Android 11+ overlay windows should be created from a window context. */
    private val windowContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val display = appContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        appContext.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else {
        appContext
    }

    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private var view: CueOverlayView? = null

    val isShowing: Boolean get() = view != null

    /** Shows the overlay (or updates it). Returns false if the window could not be added. */
    fun show(settings: CueSettings): Boolean {
        view?.let {
            it.settings = settings
            return true
        }
        if (!Settings.canDrawOverlays(appContext)) return false
        val newView = CueOverlayView(windowContext, signalProvider).also { it.settings = settings }
        return try {
            windowManager.addView(newView, createLayoutParams())
            view = newView
            true
        } catch (e: RuntimeException) {
            // WindowManager.BadTokenException / SecurityException if permission was just revoked.
            Log.w(TAG, "Could not add overlay window", e)
            false
        }
    }

    fun update(settings: CueSettings) {
        view?.settings = settings
    }

    fun hide() {
        val current = view ?: return
        view = null
        try {
            windowManager.removeViewImmediate(current)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay was already removed", e)
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "MotionCuesOverlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Cover the status and navigation bar areas too.
                fitInsetsTypes = 0
            }
            // Android 12+ blocks touches that pass through an overlay more opaque than the
            // system maximum (0.8). Staying at or below it keeps the apps underneath usable.
            alpha = maxPassThroughAlpha()
        }
    }

    private fun maxPassThroughAlpha(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 1f
        val inputManager = appContext.getSystemService(InputManager::class.java)
        val max = inputManager?.maximumObscuringOpacityForTouch ?: DEFAULT_MAX_OBSCURING_OPACITY
        return max.coerceIn(0.5f, DEFAULT_MAX_OBSCURING_OPACITY)
    }

    private companion object {
        const val TAG = "OverlayController"
        const val DEFAULT_MAX_OBSCURING_OPACITY = 0.8f
    }
}
