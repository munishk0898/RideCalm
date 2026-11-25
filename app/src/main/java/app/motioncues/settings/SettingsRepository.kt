package app.motioncues.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.cueDataStore: DataStore<Preferences> by preferencesDataStore(name = "cue_settings")

/** Single source of truth for user settings, shared by the UI, service, tile and boot receiver. */
class SettingsRepository private constructor(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<CueSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                Log.w(TAG, "Reading settings failed, using defaults", error)
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { it.toCueSettings() }
        .distinctUntilChanged()

    suspend fun current(): CueSettings = settings.first()

    suspend fun update(transform: (CueSettings) -> CueSettings) {
        dataStore.edit { prefs -> prefs.write(transform(prefs.toCueSettings())) }
    }

    suspend fun setMode(mode: ActivationMode) = update { it.copy(mode = mode) }

    private fun Preferences.toCueSettings(): CueSettings {
        val defaults = CueSettings()
        return CueSettings(
            mode = enumOrDefault(this[Keys.MODE], defaults.mode),
            pattern = enumOrDefault(this[Keys.PATTERN], defaults.pattern),
            color = enumOrDefault(this[Keys.COLOR], defaults.color),
            adaptiveContrast = this[Keys.ADAPTIVE_CONTRAST] ?: defaults.adaptiveContrast,
            largerDots = this[Keys.LARGER_DOTS] ?: defaults.largerDots,
            moreDots = this[Keys.MORE_DOTS] ?: defaults.moreDots,
            edgeRows = this[Keys.EDGE_ROWS] ?: defaults.edgeRows,
            sensitivity = (this[Keys.SENSITIVITY] ?: defaults.sensitivity)
                .coerceIn(CueSettings.MIN_SENSITIVITY, CueSettings.MAX_SENSITIVITY),
        )
    }

    private fun MutablePreferences.write(s: CueSettings) {
        this[Keys.MODE] = s.mode.name
        this[Keys.PATTERN] = s.pattern.name
        this[Keys.COLOR] = s.color.name
        this[Keys.ADAPTIVE_CONTRAST] = s.adaptiveContrast
        this[Keys.LARGER_DOTS] = s.largerDots
        this[Keys.MORE_DOTS] = s.moreDots
        this[Keys.EDGE_ROWS] = s.edgeRows
        this[Keys.SENSITIVITY] = s.sensitivity.coerceIn(CueSettings.MIN_SENSITIVITY, CueSettings.MAX_SENSITIVITY)
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val PATTERN = stringPreferencesKey("pattern")
        val COLOR = stringPreferencesKey("color")
        val ADAPTIVE_CONTRAST = booleanPreferencesKey("adaptive_contrast")
        val LARGER_DOTS = booleanPreferencesKey("larger_dots")
        val MORE_DOTS = booleanPreferencesKey("more_dots")
        val EDGE_ROWS = booleanPreferencesKey("edge_rows")
        val SENSITIVITY = floatPreferencesKey("sensitivity")
    }

    companion object {
        private const val TAG = "SettingsRepository"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext.cueDataStore).also { instance = it }
            }
    }
}
