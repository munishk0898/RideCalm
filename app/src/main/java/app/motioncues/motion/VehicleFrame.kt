package app.motioncues.motion

/**
 * Orthonormal basis describing the vehicle's horizontal motion as seen by the person holding
 * the phone. All vectors are in device (sensor) coordinates.
 *
 * @property forward horizontal direction the viewer is facing (direction of travel).
 * @property right horizontal direction to the viewer's right.
 * @property up opposite of gravity.
 */
data class VehicleBasis(val forward: Vec3, val right: Vec3, val up: Vec3)

object VehicleFrame {

    /** Below this length the forward estimate is too ill conditioned to trust. */
    private const val MIN_FORWARD_LENGTH = 0.25f

    /**
     * Estimates the vehicle frame from the gravity vector and the screen rotation.
     *
     * The viewer is assumed to look at the screen while facing the direction of travel, which is
     * how passengers normally hold a phone. Two cues point "forward" in the horizontal plane:
     * - the screen's up axis (dominant when the phone lies flat, top edge pointing ahead), and
     * - the direction into the screen (dominant when the phone is held upright).
     * Adding their horizontal projections gives a forward estimate that works for any tilt
     * between flat and upright, in portrait or landscape.
     *
     * @param gravity gravity reading in device coordinates (points up, about 9.81 m/s²).
     * @param rotation display rotation, 0..3 (see [ScreenAxes]).
     * @return the basis, or null when gravity is unknown.
     */
    fun basis(gravity: Vec3, rotation: Int): VehicleBasis? {
        val up = gravity.normalized() ?: return null
        val axes = ScreenAxes.forRotation(rotation)

        val screenUpHorizontal = horizontal(axes.up, up)
        val intoScreenHorizontal = horizontal(-ScreenAxes.SCREEN_NORMAL, up)
        val candidate = screenUpHorizontal + intoScreenHorizontal

        val forwardOrNull: Vec3? = if (candidate.length() >= MIN_FORWARD_LENGTH) {
            candidate.normalized()
        } else {
            // Screen faces downwards at roughly 45°: fall back to the screen's up axis.
            screenUpHorizontal.normalized()
        }
        val forward = forwardOrNull ?: return null

        val right = (forward cross up).normalized() ?: return null
        return VehicleBasis(forward = forward, right = right, up = up)
    }

    /** Removes the component of [v] along the unit vector [up]. */
    fun horizontal(v: Vec3, up: Vec3): Vec3 = v - up * (v dot up)
}
