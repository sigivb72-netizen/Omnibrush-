package app.mygachalist.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

data class Settings(
    val region: Region = Region.GLOBAL,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val notifyRelease: Boolean = true,
    val notifyPrereg: Boolean = true,
    val showAdult: Boolean = false,
    val lastRefresh: Long = 0,
    val lastError: String = "",
)

private val Context.dataStore by preferencesDataStore("settings")

class Prefs(private val context: Context) {
    private val kRegion = stringPreferencesKey("region")
    private val kTheme = stringPreferencesKey("theme")
    private val kNotifyRelease = booleanPreferencesKey("notify_release")
    private val kNotifyPrereg = booleanPreferencesKey("notify_prereg")
    private val kAdult = booleanPreferencesKey("show_adult")
    private val kLastRefresh = longPreferencesKey("last_refresh")
    private val kLastError = stringPreferencesKey("last_error")

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            region = Region.fromKey(p[kRegion]) ?: Region.GLOBAL,
            theme = ThemeMode.entries.firstOrNull { it.name == p[kTheme] } ?: ThemeMode.SYSTEM,
            notifyRelease = p[kNotifyRelease] ?: true,
            notifyPrereg = p[kNotifyPrereg] ?: true,
            showAdult = p[kAdult] ?: false,
            lastRefresh = p[kLastRefresh] ?: 0,
            lastError = p[kLastError].orEmpty(),
        )
    }

    suspend fun setRegion(r: Region) = context.dataStore.edit { it[kRegion] = r.key }
    suspend fun setTheme(t: ThemeMode) = context.dataStore.edit { it[kTheme] = t.name }
    suspend fun setNotifyRelease(v: Boolean) = context.dataStore.edit { it[kNotifyRelease] = v }
    suspend fun setNotifyPrereg(v: Boolean) = context.dataStore.edit { it[kNotifyPrereg] = v }
    suspend fun setShowAdult(v: Boolean) = context.dataStore.edit { it[kAdult] = v }
    suspend fun setRefreshResult(ok: Boolean, error: String) = context.dataStore.edit {
        if (ok) it[kLastRefresh] = System.currentTimeMillis()
        it[kLastError] = error
    }
}
