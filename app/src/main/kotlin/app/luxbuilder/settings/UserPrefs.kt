package app.luxbuilder.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val STRIP_WB = booleanPreferencesKey("strip_shooting_wb")
        val HQ_MATCH = booleanPreferencesKey("high_quality_match")
    }

    val presets: Flow<List<Preset>> = context.dataStore.data.map { prefs ->
        PresetSerializer.decodeList(prefs[Keys.PRESETS_JSON] ?: "")
    }

    /** Whether the auto-fit pipeline neutralizes per-ref shooting WB. Default ON. */
    val stripShootingWb: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.STRIP_WB] ?: true
    }

    /** Whether the auto-fit runs the IDT high-quality refinement. Default OFF. */
    val highQualityMatch: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HQ_MATCH] ?: false
    }

    suspend fun savePresets(list: List<Preset>) {
        context.dataStore.edit { it[Keys.PRESETS_JSON] = PresetSerializer.encodeList(list) }
    }

    suspend fun setStripShootingWb(enabled: Boolean) {
        context.dataStore.edit { it[Keys.STRIP_WB] = enabled }
    }

    suspend fun setHighQualityMatch(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HQ_MATCH] = enabled }
    }
}
