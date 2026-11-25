package app.motioncues.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class CoordinateTransformTest {

    private val eps = 1e-4f

    private fun assertVec(expected: Vec3, actual: Vec3, tolerance: Float = eps) {
        assertEquals("x", expected.x, actual.x, tolerance)
        assertEquals("y", expected.y, actual.y, tolerance)
        assertEquals("z", expected.z, actual.z, tolerance)
    }

    // ---- ScreenAxes ------------------------------------------------------------------------

    @Test
    fun screenAxes_matchCanonicalDisplayRemapping() {
        // Reference remap for accelerometer data on a rotated display:
        //   0°: ( x,  y)   90°: (-y,  x)   180°: (-x, -y)   270°: ( y, -x)
        val random = Random(1)
        repeat(50) {
            val v = Vec3(random.nextFloat() * 20 - 10, random.nextFloat() * 20 - 10, random.nextFloat() * 20 - 10)
            val expected = mapOf(
                0 to (v.x to v.y),
                1 to (-v.y to v.x),
                2 to (-v.x to -v.y),
                3 to (v.y to -v.x),
            )
            for ((rotation, pair) in expected) {
                val (sx, sy) = ScreenAxes.toScreen(v, rotation)
                assertEquals(pair.first, sx, eps)
                assertEquals(pair.second, sy, eps)
            }
        }
    }

    @Test
    fun screenAxes_areRightHandedForEveryRotation() {
        for (rotation in 0..3) {
            val axes = ScreenAxes.forRotation(rotation)
            assertVec(ScreenAxes.SCREEN_NORMAL, axes.right cross axes.up)
            assertEquals(0f, axes.right dot axes.up, eps)
        }
    }

    @Test
    fun screenAxes_outOfRangeRotationWraps() {
        assertEquals(ScreenAxes.forRotation(1), ScreenAxes.forRotation(5))
    }

    // ---- VehicleFrame ----------------------------------------------------------------------

    @Test
    fun flatPortrait_forwardIsTopOfPhone() {
        val basis = VehicleFrame.basis(Vec3(0f, 0f, 9.81f), ScreenAxes.ROTATION_0)!!
        assertVec(Vec3(0f, 1f, 0f), basis.forward)
        assertVec(Vec3(1f, 0f, 0f), basis.right)
        assertVec(Vec3(0f, 0f, 1f), basis.up)
    }

    @Test
    fun uprightPortrait_forwardIsIntoTheScreen() {
        val basis = VehicleFrame.basis(Vec3(0f, 9.81f, 0f), ScreenAxes.ROTATION_0)!!
        assertVec(Vec3(0f, 0f, -1f), basis.forward)
        assertVec(Vec3(1f, 0f, 0f), basis.right)
    }

    @Test
    fun uprightLandscape_rightFollowsScreenNotDevice() {
        // Rotated 90°: the device's +X edge points up, screen-right is device -Y.
        val basis = VehicleFrame.basis(Vec3(9.81f, 0f, 0f), ScreenAxes.ROTATION_90)!!
        assertVec(Vec3(0f, 0f, -1f), basis.forward)
        assertVec(Vec3(0f, -1f, 0f), basis.right)

        val basis270 = VehicleFrame.basis(Vec3(-9.81f, 0f, 0f), ScreenAxes.ROTATION_270)!!
        assertVec(Vec3(0f, 0f, -1f), basis270.forward)
        assertVec(Vec3(0f, 1f, 0f), basis270.right)
    }

    @Test
    fun flatLandscape_forwardIsScreenUp() {
        val basis = VehicleFrame.basis(Vec3(0f, 0f, 9.81f), ScreenAxes.ROTATION_90)!!
        assertVec(Vec3(1f, 0f, 0f), basis.forward)
        assertVec(Vec3(0f, -1f, 0f), basis.right)
    }

    @Test
    fun tiltedPhone_forwardIsHorizontalAndPointsAhead() {
        // Portrait phone tilted back 50° from upright, as typically held while reading.
        val tilt = Math.toRadians(50.0)
        val gravity = Vec3(0f, (9.81 * cos(tilt)).toFloat(), (9.81 * sin(tilt)).toFloat())
        val basis = VehicleFrame.basis(gravity, ScreenAxes.ROTATION_0)!!
        assertEquals(0f, basis.forward dot basis.up, eps)
        // Points ahead of the viewer: away from the viewer (-Z) and towards the top edge (+Y).
        assertTrue(basis.forward.z < 0f)
        assertTrue(basis.forward.y > 0f)
        assertVec(Vec3(1f, 0f, 0f), basis.right)
    }

    @Test
    fun basis_isOrthonormalForArbitraryOrientations() {
        val random = Random(7)
        repeat(500) {
            val gravity = Vec3(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1) * 9.81f
            if (gravity.length() < 1f) return@repeat
            val basis = VehicleFrame.basis(gravity, random.nextInt(4)) ?: return@repeat
            assertEquals(1f, basis.forward.length(), 1e-3f)
            assertEquals(1f, basis.right.length(), 1e-3f)
            assertEquals(0f, basis.forward dot basis.up, 1e-3f)
            assertEquals(0f, basis.right dot basis.up, 1e-3f)
            assertEquals(0f, basis.forward dot basis.right, 1e-3f)
            assertVec(basis.right, basis.forward cross basis.up, 1e-3f)
        }
    }

    @Test
    fun basis_requiresGravity() {
        assertNull(VehicleFrame.basis(Vec3.ZERO, 0))
        assertNotNull(VehicleFrame.basis(Vec3(0f, 0f, 1f), 0))
    }

    // ---- Vec3 -----------------------------------------------------------------------------

    @Test
    fun vec3_basicAlgebra() {
        val a = Vec3(1f, 2f, 3f)
        val b = Vec3(-2f, 0.5f, 4f)
        assertEquals(1f * -2f + 2f * 0.5f + 3f * 4f, a dot b, eps)
        assertVec(Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f) cross Vec3(0f, 1f, 0f))
        assertEquals(1f, a.normalized()!!.length(), eps)
        assertNull(Vec3.ZERO.normalized())
    }
}
