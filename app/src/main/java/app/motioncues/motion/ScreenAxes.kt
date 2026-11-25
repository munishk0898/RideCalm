package app.motioncues.motion

/**
 * Maps between the sensor (device) coordinate system and the current screen orientation.
 *
 * Android sensors always report values in the device's natural orientation:
 * +X to the right, +Y towards the top and +Z out of the screen. When the display is rotated
 * the on-screen "right" and "up" directions are different device axes. Rotation values are the
 * same integers as `android.view.Surface.ROTATION_0..ROTATION_270` (0, 1, 2, 3).
 */
object ScreenAxes {

    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    /** Direction pointing out of the screen towards the viewer, in device coordinates. */
    val SCREEN_NORMAL = Vec3(0f, 0f, 1f)

    /** Screen-right and screen-up unit vectors, expressed in device coordinates. */
    data class Axes(val right: Vec3, val up: Vec3)

    private val AXES_0 = Axes(right = Vec3(1f, 0f, 0f), up = Vec3(0f, 1f, 0f))
    private val AXES_90 = Axes(right = Vec3(0f, -1f, 0f), up = Vec3(1f, 0f, 0f))
    private val AXES_180 = Axes(right = Vec3(-1f, 0f, 0f), up = Vec3(0f, -1f, 0f))
    private val AXES_270 = Axes(right = Vec3(0f, 1f, 0f), up = Vec3(-1f, 0f, 0f))

    fun forRotation(rotation: Int): Axes = when (rotation and 3) {
        ROTATION_90 -> AXES_90
        ROTATION_180 -> AXES_180
        ROTATION_270 -> AXES_270
        else -> AXES_0
    }

    /**
     * Projects a device-space vector onto the screen plane. Returns (x to the right, y upwards),
     * matching the canonical remapping used for accelerometer data on rotated displays.
     */
    fun toScreen(v: Vec3, rotation: Int): Pair<Float, Float> {
        val axes = forRotation(rotation)
        return (v dot axes.right) to (v dot axes.up)
    }
}
