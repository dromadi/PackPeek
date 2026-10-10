package com.packpeek.app.data

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.packpeek.app.model.NamingConfig
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
                    ),
                )
            }

    suspend fun text(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }

    suspend fun flag(key: String, value: Boolean) {
        store.edit { it[booleanPreferencesKey(key)] = value }
    }
}
