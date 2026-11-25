package app.motioncues.motion

import kotlin.math.sqrt

/** Small immutable 3D vector used by the motion math. Pure Kotlin so it can be unit tested on the JVM. */
data class Vec3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    fun length(): Float = sqrt(this dot this)

    /** Unit vector in the same direction, or null when the vector is (almost) zero. */
    fun normalized(): Vec3? {
        val len = length()
        return if (len < EPSILON) null else this * (1f / len)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        private const val EPSILON = 1e-6f
    }
}
