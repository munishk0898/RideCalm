package app.motioncues.detection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide "are we in a vehicle" state, set by the on-device [app.motioncues.motion.VehicleClassifier]. */
object VehicleState {
    private val _inVehicle = MutableStateFlow(false)
    val inVehicle: StateFlow<Boolean> = _inVehicle.asStateFlow()

    fun update(inVehicle: Boolean) {
        _inVehicle.value = inVehicle
    }
}
