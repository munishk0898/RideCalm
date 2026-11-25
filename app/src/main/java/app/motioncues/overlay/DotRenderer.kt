package app.motioncues.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.motioncues.motion.CueOffset
import app.motioncues.motion.Spring
import app.motioncues.settings.CueSettings
import app.motioncues.settings.PatternStyle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Draws the motion cue dots onto any [Canvas]. Shared by the system overlay and the in-app
 * preview so both look and move the same. Allocation free per frame.
 *
 * Call [setTarget] with the desired displacement, [step] once per frame, and [draw] when
 * [step] reports movement.
 */
class DotRenderer(private val density: Float) {

    var settings: CueSettings = CueSettings()
        set(value) {
            if (field == value) return
            val layoutChanged = field.pattern != value.pattern || field.moreDots != value.moreDots ||
                field.edgeRows != value.edgeRows || field.largerDots != value.largerDots
            field = value
            applyPaints()
            if (layoutChanged) dynamicLayoutDirty = true
        }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var haloBaseAlpha = 0

    private val regularX = Spring(REGULAR_STIFFNESS, REGULAR_DAMPING)
    private val regularY = Spring(REGULAR_STIFFNESS, REGULAR_DAMPING)
    private val layerX = Array(LAYERS.size) { Spring(LAYERS[it].stiffness, LAYERS[it].damping) }
    private val layerY = Array(LAYERS.size) { Spring(LAYERS[it].stiffness, LAYERS[it].damping) }

    private var targetX = 0f
    private var targetY = 0f

    private var dynamicLayoutDirty = true
    private var layoutWidth = -1
    private var layoutHeight = -1
    private var dynamicLayers: Array<LayerDots> = emptyArray()

    init {
        applyPaints()
    }

    fun setTarget(offset: CueOffset) {
        targetX = offset.x.coerceIn(-1f, 1f)
        targetY = offset.y.coerceIn(-1f, 1f)
    }

    /**
     * Advances the dot physics.
     * @return true when the dots moved enough to need a redraw.
     */
    fun step(dtSec: Float): Boolean {
        if (dtSec <= 0f) return false
        var moved = 0f
        moved = max(moved, regularX.step(targetX, dtSec))
        moved = max(moved, regularY.step(targetY, dtSec))
        val regularMoved = moved
        var dynamicMoved = 0f
        for (i in LAYERS.indices) {
            dynamicMoved = max(dynamicMoved, layerX[i].step(targetX, dtSec))
            dynamicMoved = max(dynamicMoved, layerY[i].step(targetY, dtSec))
        }
        val relevant = if (settings.pattern == PatternStyle.REGULAR) regularMoved else dynamicMoved
        return relevant > MOVE_EPSILON
    }

    fun reset() {
        regularX.reset(); regularY.reset()
        layerX.forEach { it.reset() }
        layerY.forEach { it.reset() }
        targetX = 0f; targetY = 0f
    }

    fun draw(canvas: Canvas, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        when (settings.pattern) {
            PatternStyle.REGULAR -> drawRegular(canvas, width.toFloat(), height.toFloat())
            PatternStyle.DYNAMIC -> drawDynamic(canvas, width, height)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Regular: symmetrical columns (and optional rows) that move together and snap back.
    // ---------------------------------------------------------------------------------------

    private fun drawRegular(canvas: Canvas, w: Float, h: Float) {
        val r = dotRadius()
        val inset = EDGE_INSET_DP * density + r
        val range = REGULAR_MAX_OFFSET_DP * density
        val ox = regularX.position * range
        val oy = regularY.position * range

        val perSide = if (settings.moreDots) REGULAR_MORE_PER_SIDE else REGULAR_PER_SIDE
        val spacing = h / (perSide + 1)
        for (i in 1..perSide) {
            val y = i * spacing + oy
            drawDot(canvas, inset + ox, y, r, 1f)
            drawDot(canvas, w - inset + ox, y, r, 1f)
        }

        if (settings.edgeRows) {
            val perRow = max(3, (perSide * w / h).roundToInt())
            val rowSpacing = (w - 2 * inset) / (perRow + 1)
            for (j in 1..perRow) {
                val x = inset + j * rowSpacing + ox
                drawDot(canvas, x, inset + oy, r, 1f)
                drawDot(canvas, x, h - inset + oy, r, 1f)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Dynamic: three depth layers with parallax, softer springs and organic jitter.
    // Dots wrap along their edge and fade near the ends so motion reads as a continuous flow.
    // ---------------------------------------------------------------------------------------

    private class LayerDots(
        val x: FloatArray,
        val y: FloatArray,
        /** true: wraps vertically (side columns); false: wraps horizontally (top/bottom rows). */
        val vertical: BooleanArray,
    )

    private fun drawDynamic(canvas: Canvas, width: Int, height: Int) {
        ensureDynamicLayout(width, height)
        val w = width.toFloat()
        val h = height.toFloat()
        val range = DYNAMIC_MAX_OFFSET_DP * density
        val fade = FADE_DP * density
        val baseRadius = dotRadius()

        for (li in LAYERS.indices) {
            val layer = LAYERS[li]
            val dots = dynamicLayers[li]
            val ox = layerX[li].position * range * layer.depth
            val oy = layerY[li].position * range * layer.depth
            val r = baseRadius * layer.radiusScale
            for (k in dots.x.indices) {
                var x = dots.x[k] + ox
                var y = dots.y[k] + oy
                val edgeAlpha: Float
                if (dots.vertical[k]) {
                    y = wrap(y, h)
                    edgeAlpha = min(1f, min(y, h - y) / fade)
                } else {
                    x = wrap(x, w)
                    edgeAlpha = min(1f, min(x, w - x) / fade)
                }
                drawDot(canvas, x, y, r, layer.alpha * edgeAlpha.coerceIn(0f, 1f))
            }
        }
    }

    private fun ensureDynamicLayout(width: Int, height: Int) {
        if (!dynamicLayoutDirty && width == layoutWidth && height == layoutHeight) return
        layoutWidth = width
        layoutHeight = height
        dynamicLayoutDirty = false

        val w = width.toFloat()
        val h = height.toFloat()
        val random = Random(LAYOUT_SEED)
        val inset = EDGE_INSET_DP * density + dotRadius()
        val columnGap = COLUMN_GAP_DP * density
        val perSide = if (settings.moreDots) DYNAMIC_MORE_PER_SIDE else DYNAMIC_PER_SIDE
        val perRow = if (settings.edgeRows) max(3, (perSide * w / h).roundToInt()) else 0

        dynamicLayers = Array(LAYERS.size) { li ->
            val layer = LAYERS[li]
            val count = perSide * 2 + perRow * 2
            val xs = FloatArray(count)
            val ys = FloatArray(count)
            val vertical = BooleanArray(count)
            // Nearest layer sits at the screen edge, farther layers step inwards.
            val depthInset = inset + (LAYERS.size - 1 - li) * columnGap
            val spacing = h / perSide
            var n = 0
            for (i in 0 until perSide) {
                val y = (i + layer.phase + random.nextFloat() * 0.5f - 0.25f) * spacing
                val jitterLeft = (random.nextFloat() - 0.5f) * columnGap * 0.6f
                val jitterRight = (random.nextFloat() - 0.5f) * columnGap * 0.6f
                xs[n] = depthInset + jitterLeft; ys[n] = y; vertical[n] = true; n++
                xs[n] = w - depthInset + jitterRight; ys[n] = y + spacing * 0.5f; vertical[n] = true; n++
            }
            if (perRow > 0) {
                val rowSpacing = w / perRow
                for (j in 0 until perRow) {
                    val x = (j + layer.phase + random.nextFloat() * 0.5f - 0.25f) * rowSpacing
                    val jitterTop = (random.nextFloat() - 0.5f) * columnGap * 0.6f
                    val jitterBottom = (random.nextFloat() - 0.5f) * columnGap * 0.6f
                    xs[n] = x; ys[n] = depthInset + jitterTop; vertical[n] = false; n++
                    xs[n] = x + rowSpacing * 0.5f; ys[n] = h - depthInset + jitterBottom; vertical[n] = false; n++
                }
            }
            LayerDots(xs, ys, vertical)
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun drawDot(canvas: Canvas, x: Float, y: Float, radius: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        if (settings.adaptiveContrast) {
            haloPaint.alpha = (haloBaseAlpha * alpha).roundToInt()
            canvas.drawCircle(x, y, radius + haloWidth(), haloPaint)
        }
        dotPaint.alpha = (255 * alpha).roundToInt()
        canvas.drawCircle(x, y, radius, dotPaint)
    }

    private fun applyPaints() {
        val color = settings.color.argb
        dotPaint.color = color
        // Light dots get a dark halo and vice versa, so they stay visible over any content.
        val halo = if (Color.luminance(color) > 0.4f) HALO_DARK else HALO_LIGHT
        haloPaint.color = halo
        haloBaseAlpha = Color.alpha(halo)
    }

    private fun dotRadius(): Float = (if (settings.largerDots) LARGE_RADIUS_DP else RADIUS_DP) * density

    private fun haloWidth(): Float = (if (settings.largerDots) 1.75f else 1.25f) * density

    private fun wrap(value: Float, size: Float): Float {
        val m = value % size
        return if (m < 0f) m + size else m
    }

    private data class Layer(
        val depth: Float,
        val stiffness: Float,
        val damping: Float,
        val radiusScale: Float,
        val alpha: Float,
        val phase: Float,
    )

    private companion object {
        const val RADIUS_DP = 3.5f
        const val LARGE_RADIUS_DP = 5.5f
        const val EDGE_INSET_DP = 14f
        const val COLUMN_GAP_DP = 14f
        const val FADE_DP = 28f
        const val REGULAR_MAX_OFFSET_DP = 32f
        const val DYNAMIC_MAX_OFFSET_DP = 44f
        const val REGULAR_PER_SIDE = 9
        const val REGULAR_MORE_PER_SIDE = 14
        const val DYNAMIC_PER_SIDE = 8
        const val DYNAMIC_MORE_PER_SIDE = 13
        const val REGULAR_STIFFNESS = 90f
        const val REGULAR_DAMPING = 1f
        const val MOVE_EPSILON = 0.0004f
        const val LAYOUT_SEED = 0x5EED

        val HALO_DARK = Color.argb(150, 0, 0, 0)
        val HALO_LIGHT = Color.argb(180, 255, 255, 255)

        val LAYERS = arrayOf(
            Layer(depth = 0.45f, stiffness = 14f, damping = 0.7f, radiusScale = 0.6f, alpha = 0.45f, phase = 0.0f),
            Layer(depth = 0.75f, stiffness = 26f, damping = 0.75f, radiusScale = 0.8f, alpha = 0.7f, phase = 0.33f),
            Layer(depth = 1.0f, stiffness = 42f, damping = 0.8f, radiusScale = 1.0f, alpha = 1.0f, phase = 0.66f),
        )
    }
}
