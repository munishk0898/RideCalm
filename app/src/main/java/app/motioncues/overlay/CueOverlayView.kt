package app.motioncues.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import app.motioncues.motion.CueMapping
import app.motioncues.motion.MotionSignal
import app.motioncues.settings.CueSettings

/**
 * Full-screen, non-interactive view that renders the dots. Animation is driven by
 * [Choreographer] in step with the display refresh; the view only invalidates when the dots
 * actually move, so a parked car costs almost nothing.
 */
@SuppressLint("ViewConstructor")
class CueOverlayView(
    context: Context,
    private val signalProvider: () -> MotionSignal,
) : View(context) {

    private val renderer = DotRenderer(resources.displayMetrics.density)
    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastFrameNanos = 0L

    var settings: CueSettings
        get() = renderer.settings
        set(value) {
            if (renderer.settings == value) return
            renderer.settings = value
            invalidate()
        }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) 0f else (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = frameTimeNanos
            renderer.setTarget(CueMapping.targetOffset(signalProvider(), renderer.settings.sensitivity))
            if (renderer.step(dt)) invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimating()
    }

    override fun onDetachedFromWindow() {
        stopAnimating()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startAnimating() else stopAnimating()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(canvas, width, height)
    }

    private fun startAnimating() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopAnimating() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }
}
