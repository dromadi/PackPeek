package com.packpeek.app.data

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.packpeek.app.model.NamingConfig
import com.packpeek.app.model.SplitFormat
import java.io.IOException
import kotlinx.coroutines.flow.*

private val Context.packSettings by
    preferencesDataStore(
        "packpeek",
        produceMigrations = { listOf(SharedPreferencesMigration(it, "omniextract_prefs")) },
    )

data class Settings(
    val folder: String = "",
    val theme: String = "SYSTEM",
    val accent: String = "DYNAMIC",
    val confirm: Boolean = true,
    val onboarding: Boolean = false,
    val sort: String = "NAME_ASC",
    val naming: NamingConfig = NamingConfig(),
    val favorites: Set<String> = emptySet(),
    /** Successful backups kept per app; 0 keeps every backup. */
    val keep: Int = 0,
    /** What tapping an app in the list opens: FULL details or the QUICK sheet. */
    val tap: String = "FULL",
    /** The user's own VirusTotal API key for the optional hash lookup; empty when not set. */
    val virusTotalKey: String = "",
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.packSettings
    val flow: Flow<Settings> =
        store.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { p ->
                Settings(
                    p[stringPreferencesKey("folder")] ?: "",
                    p[stringPreferencesKey("theme")] ?: "SYSTEM",
                    p[stringPreferencesKey("accent")] ?: "DYNAMIC",
                    p[booleanPreferencesKey("confirm")] ?: true,
                    p[booleanPreferencesKey("onboarding")] ?: false,
                    p[stringPreferencesKey("sort")] ?: "NAME_ASC",
                    NamingConfig(
                        p[booleanPreferencesKey("name")] ?: true,
                        p[booleanPreferencesKey("pkg")] ?: false,
                        p[booleanPreferencesKey("version")] ?: true,
                        p[booleanPreferencesKey("code")] ?: false,
                        SplitFormat.of(p[stringPreferencesKey("split_format")]),
                    ),
                    p[stringSetPreferencesKey("favorites")] ?: emptySet(),
                    p[intPreferencesKey("keep")] ?: 0,
                    p[stringPreferencesKey("tap")] ?: "FULL",
                    p[stringPreferencesKey("vt_key")] ?: "",
                )
            }

    suspend fun text(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }

    suspend fun number(key: String, value: Int) {
        store.edit { it[intPreferencesKey(key)] = value }
    }

    suspend fun toggleFavorite(pkg: String) {
        store.edit {
            val key = stringSetPreferencesKey("favorites")
            val now = it[key] ?: emptySet()
            it[key] = if (pkg in now) now - pkg else now + pkg
        }
    }

    suspend fun flag(key: String, value: Boolean) {
        store.edit { it[booleanPreferencesKey(key)] = value }
    }
}
