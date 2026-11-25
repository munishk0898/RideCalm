package app.motioncues.settings

enum class ActivationMode {
    OFF, ON, AUTOMATIC;

    /** Order used by the Quick Settings tile: Off, On, Automatic, Off... */
    fun next(): ActivationMode = when (this) {
        OFF -> ON
        ON -> AUTOMATIC
        AUTOMATIC -> OFF
    }
}

enum class PatternStyle { REGULAR, DYNAMIC }

enum class DotColor(val argb: Int) {
    WHITE(0xFFFFFFFF.toInt()),
    BLACK(0xFF000000.toInt()),
    GRAY(0xFF8E8E93.toInt()),
    BLUE(0xFF0A84FF.toInt()),
    ORANGE(0xFFFF9F0A.toInt()),
    GREEN(0xFF30D158.toInt()),
}

data class CueSettings(
    val mode: ActivationMode = ActivationMode.OFF,
    val pattern: PatternStyle = PatternStyle.REGULAR,
    val color: DotColor = DotColor.WHITE,
    val adaptiveContrast: Boolean = true,
    val largerDots: Boolean = false,
    val moreDots: Boolean = false,
    val edgeRows: Boolean = false,
    val sensitivity: Float = DEFAULT_SENSITIVITY,
) {
    companion object {
        const val MIN_SENSITIVITY = 0.25f
        const val MAX_SENSITIVITY = 2.0f
        const val DEFAULT_SENSITIVITY = 1.0f
    }
}
