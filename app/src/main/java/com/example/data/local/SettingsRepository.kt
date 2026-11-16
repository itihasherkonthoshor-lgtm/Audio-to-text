package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.AppLanguage
import com.example.domain.AppSettings
import com.example.domain.AudioLanguage
import com.example.domain.ThemePreference
import com.example.domain.TranscriptionEngine
import com.example.domain.TranscriptionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "audiotext_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val DEFAULT_AUDIO_LANGUAGE = stringPreferencesKey("default_audio_language")
        val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        val SAVE_ORIGINAL_AUDIO_AUTO = booleanPreferencesKey("save_original_audio_auto")
        val SAVE_TRANSCRIPTS_AUTO = booleanPreferencesKey("save_transcripts_auto")
        val AUTO_START_ON_SELECT = booleanPreferencesKey("auto_start_on_select")
        val ENGINE = stringPreferencesKey("transcription_engine")
        val BACKEND_BASE_URL = stringPreferencesKey("backend_base_url")
        val BACKEND_AUTH_TOKEN = stringPreferencesKey("backend_auth_token")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            appLanguage = AppLanguage.fromCode(prefs[Keys.APP_LANGUAGE]),
            defaultMode = TranscriptionMode.fromId(prefs[Keys.DEFAULT_MODE]),
            defaultAudioLanguage = prefs[Keys.DEFAULT_AUDIO_LANGUAGE]
                ?.let { AudioLanguage.fromCode(it) } ?: AudioLanguage.BENGALI,
            themePreference = ThemePreference.fromId(prefs[Keys.THEME_PREFERENCE]),
            saveOriginalAudioAuto = prefs[Keys.SAVE_ORIGINAL_AUDIO_AUTO] ?: true,
            saveTranscriptsAuto = prefs[Keys.SAVE_TRANSCRIPTS_AUTO] ?: true,
            autoStartOnAudioSelect = prefs[Keys.AUTO_START_ON_SELECT] ?: true,
            engine = TranscriptionEngine.fromId(prefs[Keys.ENGINE]),
            backendBaseUrl = prefs[Keys.BACKEND_BASE_URL] ?: "http://10.0.2.2:3000",
            backendAuthToken = prefs[Keys.BACKEND_AUTH_TOKEN] ?: ""
        )
    }

    suspend fun setAppLanguage(language: AppLanguage) {
        context.dataStore.edit { it[Keys.APP_LANGUAGE] = language.code }
    }

    suspend fun setDefaultMode(mode: TranscriptionMode) {
        context.dataStore.edit { it[Keys.DEFAULT_MODE] = mode.id }
    }

    suspend fun setDefaultAudioLanguage(language: AudioLanguage) {
        context.dataStore.edit { it[Keys.DEFAULT_AUDIO_LANGUAGE] = language.code }
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        context.dataStore.edit { it[Keys.THEME_PREFERENCE] = theme.id }
    }

    suspend fun setSaveOriginalAudioAuto(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SAVE_ORIGINAL_AUDIO_AUTO] = enabled }
    }

    suspend fun setSaveTranscriptsAuto(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SAVE_TRANSCRIPTS_AUTO] = enabled }
    }

    suspend fun setAutoStartOnAudioSelect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_START_ON_SELECT] = enabled }
    }

    suspend fun setEngine(engine: TranscriptionEngine) {
        context.dataStore.edit { it[Keys.ENGINE] = engine.id }
    }

    suspend fun setBackendConfig(url: String, token: String) {
        context.dataStore.edit {
            it[Keys.BACKEND_BASE_URL] = url.trim().trimEnd('/')
            it[Keys.BACKEND_AUTH_TOKEN] = token.trim()
        }
    }
}
