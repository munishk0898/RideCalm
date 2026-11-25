package app.motioncues.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleClassifierTest {

    private val rate = 10.0 // Hz, the PROBE rate

    private class Clock(var t: Double = 100.0)

    private fun VehicleClassifier.feed(
        clock: Clock,
        seconds: Double,
        horizontal: (Double) -> Float,
        vibration: Float = 0.2f,
    ) {
        val n = (seconds * rate).toInt()
        repeat(n) {
            clock.t += 1.0 / rate
            onSample(clock.t, horizontal(clock.t), vibration)
        }
    }

    /** Stop-and-go driving: 2 s of acceleration every 8 s. */
    private val driving: (Double) -> Float = { t -> if (t % 8.0 < 2.0) 0.8f else 0.05f }

    @Test
    fun drivingIsDetected() {
        val c = VehicleClassifier()
        val clock = Clock()
        c.feed(clock, 30.0, driving)
        assertTrue(c.inVehicle)
    }

    @Test
    fun handMovementsAreNotDriving() {
        // Short bursts (0.5 s) of acceleration, like picking up or waving the phone.
        val c = VehicleClassifier()
        val clock = Clock()
        c.feed(clock, 120.0, { t -> if (t % 5.0 < 0.5) 2.0f else 0f })
        assertFalse(c.inVehicle)
    }

    @Test
    fun phoneOnDeskIsNotDriving() {
        val c = VehicleClassifier()
        val clock = Clock()
        c.feed(clock, 120.0, { 0f }, vibration = 0.01f)
        assertFalse(c.inVehicle)
    }

    @Test
    fun walkingPreventsAndEndsVehicleState() {
        val c = VehicleClassifier()
        val clock = Clock()
        // Steps while accelerating: e.g. jogging. Not a vehicle.
        repeat(300) {
            clock.t += 0.1
            c.onSample(clock.t, if (clock.t % 8.0 < 2.0) 0.8f else 0.05f, 0.2f)
            if (it % 5 == 0) c.onStep(clock.t)
        }
        assertFalse(c.inVehicle)

        // Sit down in a car and drive.
        val driver = VehicleClassifier()
        driver.feed(clock, 40.0, driving)
        assertTrue(driver.inVehicle)
        // Get out and walk: 2 steps per second.
        repeat(30) {
            clock.t += 0.5
            driver.onStep(clock.t)
            driver.onSample(clock.t, 0.1f, 0.3f)
        }
        assertFalse(driver.inVehicle)
    }

    @Test
    fun walkingDetectedFromVibrationWithoutStepSensor() {
        val c = VehicleClassifier()
        val clock = Clock()
        c.feed(clock, 40.0, driving)
        assertTrue(c.inVehicle)
        c.feed(clock, 10.0, { 0.4f }, vibration = 2.5f)
        assertFalse(c.inVehicle)
    }

    @Test
    fun trafficLightKeepsVehicleState_longParkEndsIt() {
        val c = VehicleClassifier()
        val clock = Clock()
        c.feed(clock, 40.0, driving)
        assertTrue(c.inVehicle)
        c.feed(clock, 60.0, { 0f }, vibration = 0.05f) // red light
        assertTrue(c.inVehicle)
        c.feed(clock, 200.0, { 0f }, vibration = 0.02f) // parked
        assertFalse(c.inVehicle)
    }

    @Test
    fun resetClearsState() {
        val c = VehicleClassifier()
        val clock = Clock()
        c.feed(clock, 40.0, driving)
        assertTrue(c.inVehicle)
        c.reset()
        assertFalse(c.inVehicle)
    }
}
