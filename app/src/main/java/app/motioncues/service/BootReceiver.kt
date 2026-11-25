package app.motioncues.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.motioncues.settings.ActivationMode
import app.motioncues.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Restores On / Automatic mode after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val mode = SettingsRepository.get(appContext).current().mode
                if (mode != ActivationMode.OFF) ModeController.apply(appContext, mode)
            } finally {
                pending.finish()
            }
        }
    }
}
