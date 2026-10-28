package cz.shiftmanager.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cz.shiftmanager.model.PaySettings
import cz.shiftmanager.model.WorkEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "shift_settings")

class Storage(private val context: Context) {
    private val settingsKey = stringPreferencesKey("pay_settings")
    private val entriesKey = stringPreferencesKey("work_entries")
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<PaySettings> = context.dataStore.data.map {
        it[settingsKey]?.let { s -> runCatching { json.decodeFromString<PaySettings>(s) }.getOrNull() } ?: PaySettings()
    }
    val entries: Flow<List<WorkEntry>> = context.dataStore.data.map {
        it[entriesKey]?.let { s -> runCatching { json.decodeFromString<List<WorkEntry>>(s) }.getOrDefault(emptyList()) } ?: emptyList()
    }
    suspend fun saveSettings(v: PaySettings) { context.dataStore.edit { it[settingsKey] = json.encodeToString(v) } }
    suspend fun saveEntries(v: List<WorkEntry>) { context.dataStore.edit { it[entriesKey] = json.encodeToString(v) } }
    fun upsertEntry(cur: List<WorkEntry>, e: WorkEntry) = (cur.filterNot { it.date == e.date } + e).sortedBy { it.date }
}
