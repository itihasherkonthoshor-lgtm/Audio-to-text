package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.audio.AudioFileHelper
import com.example.domain.AppLanguage
import com.example.domain.AppSettings
import com.example.domain.AudioLanguage
import com.example.domain.ConnectionCheckResult
import com.example.domain.ThemePreference
import com.example.domain.TranscriptionEngine
import com.example.domain.TranscriptionMode
import com.example.i18n.AppStrings
import com.example.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    strings: AppStrings,
    settings: AppSettings,
    connectionCheck: ConnectionCheckResult,
    totalAudioStorageBytes: Long,
    onBack: () -> Unit,
    onUpdateAppLanguage: (AppLanguage) -> Unit,
    onUpdateDefaultMode: (TranscriptionMode) -> Unit,
    onUpdateDefaultAudioLanguage: (AudioLanguage) -> Unit,
    onUpdateTheme: (ThemePreference) -> Unit,
    onUpdateAutoSaveAudio: (Boolean) -> Unit,
    onUpdateAutoSaveTranscript: (Boolean) -> Unit,
    onUpdateAutoStartOnSelect: (Boolean) -> Unit,
    onUpdateEngine: (TranscriptionEngine) -> Unit,
    onUpdateBackendConfig: (String, String) -> Unit,
    onCheckConnection: () -> Unit,
    onOpenAudioManager: () -> Unit,
    onClearHistory: () -> Unit,
    onDeleteAllAudio: () -> Unit
) {
    var backendUrlInput by remember(settings.backendBaseUrl) { mutableStateOf(settings.backendBaseUrl) }
    var backendTokenInput by remember(settings.backendAuthToken) { mutableStateOf(settings.backendAuthToken) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var showDeleteAudioConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (connectionCheck.isConnected == null && !connectionCheck.isChecking) {
            onCheckConnection()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(strings.settings) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.close)
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 820.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. App Language & Theme
                SettingsSectionCard {
                    Text(
                        text = strings.appLanguageSetting,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AppLanguage.entries.forEachIndexed { idx, lang ->
                            SegmentedButton(
                                selected = settings.appLanguage == lang,
                                onClick = { onUpdateAppLanguage(lang) },
                                shape = SegmentedButtonDefaults.itemShape(idx, AppLanguage.entries.size),
                                modifier = Modifier.testTag("setting_app_lang_${lang.code}")
                            ) {
                                Text(if (lang == AppLanguage.BENGALI) strings.bengaliLabel else strings.englishLabel)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = strings.themeSetting,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ThemePreference.entries.forEachIndexed { idx, theme ->
                            SegmentedButton(
                                selected = settings.themePreference == theme,
                                onClick = { onUpdateTheme(theme) },
                                shape = SegmentedButtonDefaults.itemShape(idx, ThemePreference.entries.size),
                                modifier = Modifier.testTag("setting_theme_${theme.id}")
                            ) {
                                Text(
                                    when (theme) {
                                        ThemePreference.LIGHT -> strings.themeLight
                                        ThemePreference.DARK -> strings.themeDark
                                        ThemePreference.SYSTEM -> strings.themeSystem
                                    }
                                )
                            }
                        }
                    }
                }

                // 2. Default Transcription Mode & Audio Language & Auto-Save toggles
                SettingsSectionCard {
                    Text(
                        text = strings.defaultModeSetting,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        TranscriptionMode.entries.forEachIndexed { idx, mode ->
                            SegmentedButton(
                                selected = settings.defaultMode == mode,
                                onClick = { onUpdateDefaultMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(idx, TranscriptionMode.entries.size),
                                modifier = Modifier.testTag("setting_default_mode_${mode.id}")
                            ) {
                                Text(strings.modeDisplay(mode))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = strings.defaultAudioLangSetting,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AudioLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = settings.defaultAudioLanguage == lang,
                                onClick = { onUpdateDefaultAudioLanguage(lang) },
                                label = { Text(strings.audioLanguageDisplay(lang)) },
                                modifier = Modifier.testTag("setting_default_audio_lang_${lang.code}")
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = strings.autoSaveAudioSetting,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = settings.saveOriginalAudioAuto,
                            onCheckedChange = onUpdateAutoSaveAudio,
                            modifier = Modifier.testTag("setting_auto_save_audio")
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = strings.autoSaveTranscriptSetting,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = settings.saveTranscriptsAuto,
                            onCheckedChange = onUpdateAutoSaveTranscript,
                            modifier = Modifier.testTag("setting_auto_save_transcript")
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = strings.autoStartOnSelectSetting,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = settings.autoStartOnAudioSelect,
                            onCheckedChange = onUpdateAutoStartOnSelect,
                            modifier = Modifier.testTag("setting_auto_start_on_select")
                        )
                    }
                }

                // 3. Transcription Engine & API Connection Status
                SettingsSectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.transcriptionEngineSetting,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    EngineRadioOption(
                        selected = settings.engine == TranscriptionEngine.FIREBASE_GEMINI,
                        title = strings.engineFirebaseGemini,
                        subtitle = strings.engineFirebaseGeminiDesc,
                        testTag = "engine_firebase_gemini",
                        onClick = { onUpdateEngine(TranscriptionEngine.FIREBASE_GEMINI) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    EngineRadioOption(
                        selected = settings.engine == TranscriptionEngine.CUSTOM_BACKEND_OPENAI,
                        title = strings.engineCustomBackend,
                        subtitle = strings.engineCustomBackendDesc,
                        testTag = "engine_custom_backend",
                        onClick = { onUpdateEngine(TranscriptionEngine.CUSTOM_BACKEND_OPENAI) }
                    )

                    if (settings.engine == TranscriptionEngine.CUSTOM_BACKEND_OPENAI) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = backendUrlInput,
                            onValueChange = { backendUrlInput = it },
                            label = { Text(strings.backendUrlLabel) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("backend_url_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = backendTokenInput,
                            onValueChange = { backendTokenInput = it },
                            label = { Text(strings.backendTokenLabel) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("backend_token_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                onUpdateBackendConfig(backendUrlInput, backendTokenInput)
                                onCheckConnection()
                            },
                            modifier = Modifier
                                .align(Alignment.End)
                                .testTag("save_backend_config_button")
                        ) {
                            Text(strings.save)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.apiConnectionStatus,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when {
                                    connectionCheck.isChecking -> {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(strings.checkingConnection, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    connectionCheck.isConnected == true -> {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = SuccessGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${strings.connectionOk}: ${connectionCheck.statusDetail}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = SuccessGreen
                                        )
                                    }
                                    connectionCheck.isConnected == false -> {
                                        Icon(
                                            Icons.Filled.Error,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${strings.connectionFailed}: ${connectionCheck.statusDetail}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                if (settings.engine == TranscriptionEngine.CUSTOM_BACKEND_OPENAI) {
                                    onUpdateBackendConfig(backendUrlInput, backendTokenInput)
                                }
                                onCheckConnection()
                            },
                            modifier = Modifier.testTag("check_connection_button")
                        ) {
                            Icon(Icons.Filled.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.checkConnection)
                        }
                    }
                }

                // 4. Storage & Data Management
                SettingsSectionCard {
                    OutlinedButton(
                        onClick = onOpenAudioManager,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("settings_audio_manager_button")
                    ) {
                        Icon(Icons.Outlined.LibraryMusic, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${strings.audioManager} (${AudioFileHelper.formatFileSize(totalAudioStorageBytes)})")
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showDeleteAudioConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("settings_delete_all_audio_button")
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.deleteAllAudioSetting)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showClearHistoryConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("settings_clear_history_button")
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.clearAllHistory)
                    }
                }

                // 5. Privacy & About
                SettingsSectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.privacyTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = strings.privacyDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.aboutAppTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${strings.aboutAppDesc}\nVersion 1.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showClearHistoryConfirm) {
        ConfirmDialog(
            title = strings.clearAllHistoryConfirmTitle,
            message = strings.clearAllHistoryConfirmMsg,
            confirmText = strings.delete,
            cancelText = strings.cancel,
            isDestructive = true,
            onConfirm = {
                showClearHistoryConfirm = false
                onClearHistory()
            },
            onDismiss = { showClearHistoryConfirm = false }
        )
    }

    if (showDeleteAudioConfirm) {
        ConfirmDialog(
            title = strings.deleteAllAudioSetting,
            message = strings.deleteAllAudioConfirmMsg,
            confirmText = strings.delete,
            cancelText = strings.cancel,
            isDestructive = true,
            onConfirm = {
                showDeleteAudioConfirm = false
                onDeleteAllAudio()
            },
            onDismiss = { showDeleteAudioConfirm = false }
        )
    }
}

@Composable
private fun SettingsSectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            content()
        }
    }
}

@Composable
private fun EngineRadioOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
