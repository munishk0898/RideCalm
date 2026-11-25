package app.motioncues.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.motioncues.service.ModeController
import app.motioncues.settings.ActivationMode
import app.motioncues.settings.CueSettings
import app.motioncues.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val repository = SettingsRepository.get(application)

    /** Null until the stored settings have been read, so the UI never flashes defaults. */
    val settings: StateFlow<CueSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setMode(mode: ActivationMode) {
        viewModelScope.launch { ModeController.setMode(app, mode) }
    }

    fun update(transform: (CueSettings) -> CueSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun syncService() {
        viewModelScope.launch { ModeController.apply(app, repository.current().mode) }
    }
}
