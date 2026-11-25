package app.motioncues.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.motioncues.R
import app.motioncues.service.ModeController
import app.motioncues.settings.ActivationMode
import app.motioncues.settings.SettingsRepository
import app.motioncues.ui.MainActivity
import app.motioncues.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Quick Settings tile cycling Off, On, Automatic (like the iOS Control Center control). */
class MotionCueTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = scope.launch {
            SettingsRepository.get(applicationContext).settings
                .map { it.mode }
                .distinctUntilChanged()
                .collect { render(it) }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext
        scope.launch {
            val repository = SettingsRepository.get(app)
            val next = repository.current().mode.next()

            if (next != ActivationMode.OFF && !Permissions.canDrawOverlays(app)) {
                // Can't show anything yet: open the app on its permission card.
                repository.setMode(next)
                render(next)
                openApp()
                return@launch
            }

            repository.setMode(next)
            render(next)
            if (!ModeController.apply(app, next)) openApp()
        }
    }

    private fun render(mode: ActivationMode) {
        val tile = qsTile ?: return
        tile.state = if (mode == ActivationMode.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        val modeLabel = getString(
            when (mode) {
                ActivationMode.OFF -> R.string.mode_off
                ActivationMode.ON -> R.string.mode_on
                ActivationMode.AUTOMATIC -> R.string.mode_automatic
            },
        )
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = modeLabel
        }
        tile.contentDescription = "${getString(R.string.tile_label)}, $modeLabel"
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
