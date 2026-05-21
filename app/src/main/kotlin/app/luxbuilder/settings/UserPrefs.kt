package app.luxbuilder.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.luxbuilder.state.Preset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

class UserPrefs(private val context: Context) {

    private object Keys {
        val PRESETS_JSON = stringPreferencesKey("presets_json")
    }

    val presets: Flow<List<Preset>> = context.dataStore.data.map { prefs ->
        PresetSerializer.decodeList(prefs[Keys.PRESETS_JSON] ?: "")
    }

    suspend fun savePresets(list: List<Preset>) {
        context.dataStore.edit { it[Keys.PRESETS_JSON] = PresetSerializer.encodeList(list) }
    }
}
