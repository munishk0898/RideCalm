package app.motioncues.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.motioncues.R
import app.motioncues.motion.CueMapping
import app.motioncues.motion.CueOffset
import app.motioncues.motion.SimulationScript
import app.motioncues.overlay.DotRenderer
import app.motioncues.sensors.SensorController
import app.motioncues.settings.CueSettings
import kotlin.math.roundToInt

/**
 * Live preview of the dots using the same renderer as the system overlay. It reacts to the
 * phone's real sensors, to dragging inside the box, and to a scripted simulated drive.
 */
@Composable
fun MotionPreview(settings: CueSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val renderer = remember(density) { DotRenderer(density) }
    val sensors = remember { SensorController(context) }
    val currentSettings by rememberUpdatedState(settings)

    var simulating by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<SimulationScript.Phase?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var frameTick by remember { mutableLongStateOf(0L) }
    var darkBackground by rememberSaveable { mutableStateOf(true) }

    // Sensors only while the screen is in the foreground.
    LifecycleResumeEffect(sensors) {
        sensors.start()
        onPauseOrDispose { sensors.stop() }
    }

    LaunchedEffect(renderer) {
        var lastNanos = 0L
        var simulationStart = -1L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0f else (now - lastNanos) / 1_000_000_000f
                lastNanos = now

                var signal = sensors.signal()
                if (simulating) {
                    if (simulationStart < 0L) simulationStart = now
                    val sample = SimulationScript.sample((now - simulationStart) / 1_000_000_000f)
                    if (sample == null) {
                        simulating = false
                        simulationStart = -1L
                        phase = null
                    } else {
                        signal += sample.signal
                        phase = sample.phase
                    }
                } else if (simulationStart >= 0L) {
                    simulationStart = -1L
                    phase = null
                }

                val drag = dragOffset
                val target = if (drag != Offset.Zero && boxSize.width > 0) {
                    val scale = boxSize.width * 0.3f
                    CueOffset((drag.x / scale).coerceIn(-1f, 1f), (drag.y / scale).coerceIn(-1f, 1f))
                } else {
                    CueMapping.targetOffset(signal, currentSettings.sensitivity)
                }
                renderer.setTarget(target)
                if (renderer.step(dt)) frameTick = now
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(24.dp))
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { dragOffset = Offset.Zero },
                        onDragCancel = { dragOffset = Offset.Zero },
                    ) { change, amount ->
                        change.consume()
                        dragOffset += amount
                    }
                },
        ) {
            val background = if (darkBackground) Color(0xFF121418) else Color(0xFFF4F5F7)
            val placeholder = if (darkBackground) Color(0xFF2A2F37) else Color(0xFFD9DCE1)
            Canvas(modifier = Modifier.matchParentSize()) {
                @Suppress("UNUSED_VARIABLE")
                val tick = frameTick // Redraw whenever the dots move.
                drawRect(background)
                drawFakeContent(placeholder)
                renderer.settings = settings
                drawIntoCanvas { canvas ->
                    renderer.draw(canvas.nativeCanvas, size.width.roundToInt(), size.height.roundToInt())
                }
            }
            phase?.let { current ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                ) {
                    Text(
                        text = stringResource(current.labelRes()),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { simulating = !simulating },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(
                    imageVector = if (simulating) Icons.Filled.Close else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(if (simulating) R.string.stop_simulation else R.string.simulate_motion))
            }
            FilterChip(
                selected = darkBackground,
                onClick = { darkBackground = !darkBackground },
                label = { Text(stringResource(R.string.preview_dark_background)) },
            )
        }
        Text(
            text = stringResource(R.string.preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Placeholder "app content" so the dots can be judged against something realistic. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFakeContent(color: Color) {
    val margin = size.width * 0.16f
    val contentWidth = size.width - margin * 2
    val radius = CornerRadius(12f, 12f)
    drawRoundRect(
        color = color,
        topLeft = Offset(margin, size.height * 0.1f),
        size = Size(contentWidth, size.height * 0.36f),
        cornerRadius = radius,
    )
    val lineHeight = size.height * 0.05f
    val widths = floatArrayOf(1f, 0.85f, 0.92f, 0.6f)
    for (i in widths.indices) {
        drawRoundRect(
            color = color,
            topLeft = Offset(margin, size.height * (0.53f + i * 0.1f)),
            size = Size(contentWidth * widths[i], lineHeight),
            cornerRadius = radius,
        )
    }
}

private fun SimulationScript.Phase.labelRes(): Int = when (this) {
    SimulationScript.Phase.ACCELERATING -> R.string.phase_accelerating
    SimulationScript.Phase.CRUISING -> R.string.phase_cruising
    SimulationScript.Phase.TURNING_LEFT -> R.string.phase_turning_left
    SimulationScript.Phase.TURNING_RIGHT -> R.string.phase_turning_right
    SimulationScript.Phase.BRAKING -> R.string.phase_braking
    SimulationScript.Phase.STOPPED -> R.string.phase_stopped
}
