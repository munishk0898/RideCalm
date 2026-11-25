package app.motioncues.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class SmoothingAndPhysicsTest {

    // ---- LowPassFilter ---------------------------------------------------------------------

    @Test
    fun lowPass_firstSampleInitialisesOutput() {
        val filter = LowPassFilter(0.2f)
        assertEquals(5f, filter.filter(5f, 0.02f), 0f)
    }

    @Test
    fun lowPass_stepResponseMatchesTimeConstant() {
        val tau = 0.2f
        val filter = LowPassFilter(tau)
        filter.filter(0f, 0f)
        var t = 0f
        val dt = 0.001f
        while (t < tau - 1e-6f) {
            filter.filter(1f, dt)
            t += dt
        }
        // After one time constant a first-order system reaches 1 - 1/e.
        assertEquals(1f - exp(-1f), filter.value, 0.01f)
    }

    @Test
    fun lowPass_isSampleRateIndependent() {
        fun responseAfter(seconds: Float, rateHz: Int): Float {
            val filter = LowPassFilter(0.15f)
            filter.filter(0f, 0f)
            val dt = 1f / rateHz
            repeat((seconds * rateHz).toInt()) { filter.filter(2f, dt) }
            return filter.value
        }
        val slow = responseAfter(0.4f, 25)
        val fast = responseAfter(0.4f, 200)
        assertEquals(fast, slow, 0.08f)
    }

    @Test
    fun lowPass_alphaEdgeCases() {
        assertEquals(0f, LowPassFilter.alpha(0f, 0.1f), 0f)
        assertEquals(1f, LowPassFilter.alpha(0.02f, 0f), 0f)
        assertEquals(0.5f, LowPassFilter.alpha(0.1f, 0.1f), 1e-6f)
    }

    @Test
    fun deadZone_isContinuousAndSymmetric() {
        assertEquals(0f, deadZone(0.1f, 0.15f), 0f)
        assertEquals(0f, deadZone(-0.15f, 0.15f), 0f)
        assertEquals(0.35f, deadZone(0.5f, 0.15f), 1e-6f)
        assertEquals(-0.35f, deadZone(-0.5f, 0.15f), 1e-6f)
        assertEquals(0f, deadZone(0.150001f, 0.15f), 1e-5f)
    }

    // ---- MotionProcessor ---------------------------------------------------------------------

    private class Feeder(val processor: MotionProcessor, rateHz: Int = 50) {
        val stepNs = 1_000_000_000L / rateHz
        var t = 1_000L

        fun run(seconds: Float, gravity: Vec3, linear: Vec3, gyro: Vec3 = Vec3.ZERO) {
            repeat((seconds * 1_000_000_000L / stepNs).toInt()) {
                t += stepNs
                processor.onGravity(gravity, t)
                processor.onLinearAcceleration(linear, t)
                processor.onGyroscope(gyro, t)
            }
        }

        fun runNoise(seconds: Float, gravity: Vec3, amplitude: Float) {
            var sign = 1f
            repeat((seconds * 1_000_000_000L / stepNs).toInt()) {
                t += stepNs
                sign = -sign
                processor.onGravity(gravity, t)
                processor.onLinearAcceleration(Vec3(sign * amplitude, -sign * amplitude, sign * amplitude), t)
                processor.onGyroscope(Vec3(sign * 0.05f, sign * 0.05f, sign * 0.05f), t)
            }
        }
    }

    private val uprightPortrait = Vec3(0f, 9.81f, 0f)
    private val flatPortrait = Vec3(0f, 0f, 9.81f)

    @Test
    fun forwardAcceleration_givesPositiveLongitudinal() {
        val processor = MotionProcessor()
        // Upright phone: the direction of travel is into the screen (-Z).
        Feeder(processor).run(1f, uprightPortrait, linear = Vec3(0f, 0f, -2f))
        val signal = processor.signal
        assertEquals(2f - 0.15f, signal.longitudinal, 0.05f)
        assertEquals(0f, signal.lateral, 0.05f)
        // Forward acceleration moves the dots down (+y on screen).
        assertTrue(CueMapping.targetOffset(signal, 1f).y > 0.5f)
    }

    @Test
    fun braking_givesNegativeLongitudinal_dotsMoveUp() {
        val processor = MotionProcessor()
        Feeder(processor).run(1f, flatPortrait, linear = Vec3(0f, -3f, 0f))
        val signal = processor.signal
        assertTrue(signal.longitudinal < -2.5f)
        assertTrue(CueMapping.targetOffset(signal, 1f).y < -0.5f)
    }

    @Test
    fun leftTurn_movesDotsRight() {
        val processor = MotionProcessor()
        // Left turn: centripetal acceleration to the left (-X) and positive yaw about "up" (+Y).
        Feeder(processor).run(1f, uprightPortrait, linear = Vec3(-2f, 0f, 0f), gyro = Vec3(0f, 0.3f, 0f))
        val signal = processor.signal
        assertTrue("lateral=${signal.lateral}", signal.lateral > 2f)
        assertEquals(0f, signal.longitudinal, 0.05f)
        assertTrue(CueMapping.targetOffset(signal, 1f).x > 0.5f)
    }

    @Test
    fun rightTurn_movesDotsLeft() {
        val processor = MotionProcessor()
        Feeder(processor).run(1f, flatPortrait, linear = Vec3(2f, 0f, 0f), gyro = Vec3(0f, 0f, -0.3f))
        assertTrue(CueMapping.targetOffset(processor.signal, 1f).x < -0.5f)
    }

    @Test
    fun yawAlone_producesTurnCue() {
        val processor = MotionProcessor()
        Feeder(processor).run(1f, flatPortrait, linear = Vec3.ZERO, gyro = Vec3(0f, 0f, 0.25f))
        assertEquals(4f * (0.25f - 0.04f), processor.signal.lateral, 0.05f)
    }

    @Test
    fun landscapeRotation_keepsVehicleDirections() {
        val processor = MotionProcessor().apply { rotation = ScreenAxes.ROTATION_90 }
        val feeder = Feeder(processor)
        // Upright landscape (device +X up). Acceleration to the viewer's right is device -Y.
        feeder.run(1f, Vec3(9.81f, 0f, 0f), linear = Vec3(0f, -2f, 0f))
        // Accelerating to the right = turning right: dots move left.
        assertTrue(processor.signal.lateral < -1.5f)
        assertEquals(0f, processor.signal.longitudinal, 0.05f)

        feeder.run(1f, Vec3(9.81f, 0f, 0f), linear = Vec3(0f, 0f, -2f))
        assertTrue(processor.signal.longitudinal > 1.5f)
    }

    @Test
    fun sameMotion_givesSameCue_inEveryRotation() {
        // Phone flat; the vehicle accelerates towards the top of the *screen* in each rotation.
        for (rotation in 0..3) {
            val processor = MotionProcessor().apply { this.rotation = rotation }
            val screenUp = ScreenAxes.forRotation(rotation).up
            Feeder(processor).run(1f, flatPortrait, linear = screenUp * 2f)
            assertEquals("rotation $rotation", 1.85f, processor.signal.longitudinal, 0.05f)
            assertEquals("rotation $rotation", 0f, processor.signal.lateral, 0.05f)
        }
    }

    @Test
    fun roadVibration_isSuppressed() {
        val processor = MotionProcessor()
        Feeder(processor).runNoise(2f, flatPortrait, amplitude = 1.5f)
        val signal = processor.signal
        assertTrue("longitudinal=${signal.longitudinal}", abs(signal.longitudinal) < 0.2f)
        assertTrue("lateral=${signal.lateral}", abs(signal.lateral) < 0.3f)
    }

    @Test
    fun stoppedVehicle_returnsToZero() {
        val processor = MotionProcessor()
        val feeder = Feeder(processor)
        feeder.run(1f, flatPortrait, linear = Vec3(0f, 3f, 0f))
        feeder.run(1f, flatPortrait, linear = Vec3.ZERO)
        assertEquals(0f, processor.signal.longitudinal, 1e-3f)
        assertEquals(0f, processor.signal.lateral, 1e-3f)
    }

    @Test
    fun rawAccelerometerFallback_estimatesGravityAndLinear() {
        val processor = MotionProcessor()
        var t = 1_000L
        repeat(250) { // 5 s at 50 Hz
            t += 20_000_000L
            processor.onRawAccelerometer(Vec3(0f, 2f, 9.81f), t, deriveLinear = true)
        }
        // A sustained push slowly blends into the gravity estimate, so only require the sign.
        repeat(10) {
            t += 20_000_000L
            processor.onRawAccelerometer(Vec3(0f, 0f, 9.81f), t, deriveLinear = true)
        }
        assertTrue(processor.signal.longitudinal < 0f)
    }

    @Test
    fun noGravity_noOutput() {
        val processor = MotionProcessor()
        processor.onLinearAcceleration(Vec3(0f, 5f, 0f), 1_000L)
        processor.onLinearAcceleration(Vec3(0f, 5f, 0f), 21_000_000L)
        assertEquals(MotionSignal.ZERO, processor.signal)
    }

    @Test
    fun longGaps_areClamped() {
        val processor = MotionProcessor()
        processor.onGravity(flatPortrait, 1_000L)
        processor.onLinearAcceleration(Vec3.ZERO, 1_000L)
        // 10 seconds later (device slept): a single sample must not jump straight to full value.
        processor.onGravity(flatPortrait, 10_000_001_000L)
        processor.onLinearAcceleration(Vec3(0f, 3f, 0f), 10_000_001_000L)
        assertTrue(processor.signal.longitudinal < 1.5f)
    }

    // ---- CueMapping ------------------------------------------------------------------------

    @Test
    fun cueMapping_isBoundedMonotonicAndScaledBySensitivity() {
        var previous = -2f
        var s = -20f
        while (s <= 20f) {
            val v = CueMapping.normalize(s, 1f)
            assertTrue(v >= previous)
            assertTrue(abs(v) <= 1f)
            previous = v
            s += 0.5f
        }
        assertTrue(CueMapping.normalize(1f, 2f) > CueMapping.normalize(1f, 1f))
        assertEquals(0f, CueMapping.normalize(0f, 2f), 0f)
        assertEquals(-CueMapping.normalize(1.3f, 1f), CueMapping.normalize(-1.3f, 1f), 1e-6f)
    }

    // ---- Spring ----------------------------------------------------------------------------

    @Test
    fun spring_reachesTargetAndSnapsBackWithoutOvershoot() {
        val spring = Spring(stiffness = 90f, dampingRatio = 1f)
        repeat(60) { spring.step(1f, 1f / 60f) }
        assertEquals(1f, spring.position, 0.01f)
        var minPosition = Float.MAX_VALUE
        repeat(90) {
            spring.step(0f, 1f / 60f)
            minPosition = minOf(minPosition, spring.position)
        }
        assertTrue(spring.isAtRest(0f, 0.005f))
        assertTrue("overshoot $minPosition", minPosition > -0.005f)
    }

    @Test
    fun spring_underdampedOvershoots() {
        val spring = Spring(stiffness = 40f, dampingRatio = 0.5f)
        var max = 0f
        repeat(120) {
            spring.step(1f, 1f / 60f)
            max = maxOf(max, spring.position)
        }
        assertTrue(max > 1.05f)
    }

    @Test
    fun spring_ignoresInvalidTimeSteps() {
        val spring = Spring(90f)
        assertEquals(0f, spring.step(1f, 0f), 0f)
        assertEquals(0f, spring.step(1f, -1f), 0f)
        // Very large dt is clamped so the spring stays stable.
        spring.step(1f, 5f)
        assertTrue(spring.position in 0f..1.2f)
    }

    // ---- SimulationScript ------------------------------------------------------------------

    @Test
    fun simulation_coversAllManoeuvresInOrderAndEnds() {
        val phases = mutableListOf<SimulationScript.Phase>()
        var t = 0f
        while (true) {
            val sample = SimulationScript.sample(t) ?: break
            if (phases.lastOrNull() != sample.phase) phases += sample.phase
            when (sample.phase) {
                SimulationScript.Phase.ACCELERATING -> assertTrue(sample.signal.longitudinal >= 0f)
                SimulationScript.Phase.BRAKING -> assertTrue(sample.signal.longitudinal <= 0f)
                SimulationScript.Phase.TURNING_LEFT -> assertTrue(sample.signal.lateral >= 0f)
                SimulationScript.Phase.TURNING_RIGHT -> assertTrue(sample.signal.lateral <= 0f)
                else -> Unit
            }
            t += 0.05f
        }
        assertTrue(phases.containsAll(SimulationScript.Phase.entries))
        assertEquals(SimulationScript.Phase.ACCELERATING, phases.first())
        assertNull(SimulationScript.sample(SimulationScript.totalDurationSec + 0.01f))
        assertNotNull(SimulationScript.sample(0f))
    }
}
