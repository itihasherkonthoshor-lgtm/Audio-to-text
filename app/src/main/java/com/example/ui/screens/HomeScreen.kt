package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.audio.AudioFileHelper
import com.example.data.local.TranscriptEntity
import com.example.domain.AudioLanguage
import com.example.domain.RecordingState
import com.example.domain.RecordingStatus
import com.example.domain.SelectedAudioInfo
import com.example.domain.TranscriptionMode
import com.example.domain.TranscriptionProgress
import com.example.i18n.AppStrings
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.RecordCoral
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    strings: AppStrings,
    selectedAudio: SelectedAudioInfo?,
    recordingState: RecordingState,
    selectedAudioLanguage: AudioLanguage,
    selectedMode: TranscriptionMode,
    keepOriginalAudio: Boolean,
    transcriptionProgress: TranscriptionProgress,
    recentTranscriptions: List<TranscriptEntity>,
    onImportUri: (android.net.Uri) -> Unit,
    onRemoveSelectedAudio: () -> Unit,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopAndSaveRecording: () -> Unit,
    onDiscardRecording: () -> Unit,
    onSelectAudioLanguage: (AudioLanguage) -> Unit,
    onSelectMode: (TranscriptionMode) -> Unit,
    onToggleKeepOriginalAudio: (Boolean) -> Unit,
    onStartTranscription: () -> Unit,
    onCancelTranscription: () -> Unit,
    onRetryTranscription: () -> Unit,
    onDismissError: () -> Unit,
    onOpenTranscript: (Long) -> Unit,
    onNavigateHistory: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateAudioManager: () -> Unit
) {
    val context = LocalContext.current
    var micPermissionDenied by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportUri(uri)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micPermissionDenied = false
            onStartRecording()
        } else {
            micPermissionDenied = true
        }
    }

    val launchFilePicker = {
        filePickerLauncher.launch(
            arrayOf(
                "audio/*",
                "video/mp4",
                "application/ogg",
                "audio/mpeg",
                "audio/mp4",
                "audio/wav",
                "audio/x-wav"
            )
        )
    }

    val handleStartRecordClick = {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            micPermissionDenied = false
            onStartRecording()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            val isWide = maxWidth >= 680.dp

            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                item {
                    HomeHeader(
                        strings = strings,
                        onOpenHistory = onNavigateHistory,
                        onOpenSettings = onNavigateSettings
                    )
                }

                // Active Transcription Progress or Error Banner
                if (transcriptionProgress.isRunning || transcriptionProgress.error != null) {
                    item {
                        TranscriptionProgressBanner(
                            progress = transcriptionProgress,
                            strings = strings,
                            onCancel = onCancelTranscription,
                            onRetry = onRetryTranscription,
                            onDismissError = onDismissError
                        )
                    }
                }

                // Large Action Cards & Transcription Config
                // On compact/phone screens, show TranscriptionConfigCard directly below UploadAudioCard so language/mode/start controls are immediately visible without scrolling past RecordAudioCard.
                item {
                    val isBusy = recordingState.status == RecordingStatus.RECORDING ||
                        recordingState.status == RecordingStatus.PAUSED ||
                        transcriptionProgress.isRunning
                    if (isWide) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            UploadAudioCard(
                                strings = strings,
                                selectedAudio = selectedAudio,
                                isBusy = isBusy,
                                onSelectFile = launchFilePicker,
                                onRemoveFile = onRemoveSelectedAudio,
                                modifier = Modifier.weight(1f)
                            )
                            RecordAudioCard(
                                strings = strings,
                                recordingState = recordingState,
                                micPermissionDenied = micPermissionDenied,
                                onStartRecord = handleStartRecordClick,
                                onPauseRecord = onPauseRecording,
                                onResumeRecord = onResumeRecording,
                                onStopAndSave = onStopAndSaveRecording,
                                onDiscard = onDiscardRecording,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            UploadAudioCard(
                                strings = strings,
                                selectedAudio = selectedAudio,
                                isBusy = isBusy,
                                onSelectFile = launchFilePicker,
                                onRemoveFile = onRemoveSelectedAudio
                            )
                            AnimatedVisibility(visible = selectedAudio != null) {
                                if (selectedAudio != null) {
                                    TranscriptionConfigCard(
                                        strings = strings,
                                        selectedAudio = selectedAudio,
                                        selectedLanguage = selectedAudioLanguage,
                                        selectedMode = selectedMode,
                                        keepOriginalAudio = keepOriginalAudio,
                                        isTranscribing = transcriptionProgress.isRunning,
                                        onSelectLanguage = onSelectAudioLanguage,
                                        onSelectMode = onSelectMode,
                                        onToggleKeepOriginalAudio = onToggleKeepOriginalAudio,
                                        onStartTranscription = onStartTranscription
                                    )
                                }
                            }
                            RecordAudioCard(
                                strings = strings,
                                recordingState = recordingState,
                                micPermissionDenied = micPermissionDenied,
                                onStartRecord = handleStartRecordClick,
                                onPauseRecord = onPauseRecording,
                                onResumeRecord = onResumeRecording,
                                onStopAndSave = onStopAndSaveRecording,
                                onDiscard = onDiscardRecording
                            )
                        }
                    }
                }

                // Wide layout: Transcription Configuration & Start CTA below the two-column cards
                if (isWide) {
                    item {
                        AnimatedVisibility(visible = selectedAudio != null) {
                            if (selectedAudio != null) {
                                TranscriptionConfigCard(
                                    strings = strings,
                                    selectedAudio = selectedAudio,
                                    selectedLanguage = selectedAudioLanguage,
                                    selectedMode = selectedMode,
                                    keepOriginalAudio = keepOriginalAudio,
                                    isTranscribing = transcriptionProgress.isRunning,
                                    onSelectLanguage = onSelectAudioLanguage,
                                    onSelectMode = onSelectMode,
                                    onToggleKeepOriginalAudio = onToggleKeepOriginalAudio,
                                    onStartTranscription = onStartTranscription
                                )
                            }
                        }
                    }
                }

                // Recent Transcriptions Header + Quick Actions
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.recentTranscriptions,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = onNavigateAudioManager,
                                modifier = Modifier.testTag("home_audio_manager_button")
                            ) {
                                Icon(
                                    Icons.Outlined.LibraryMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(strings.audioManager)
                            }
                            TextButton(
                                onClick = onNavigateHistory,
                                modifier = Modifier.testTag("view_all_history_button")
                            ) {
                                Text(strings.viewAllHistory)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                if (recentTranscriptions.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.GraphicEq,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = strings.noHistoryTitle,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = strings.noHistoryDesc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(recentTranscriptions, key = { it.id }) { item ->
                        RecentTranscriptCard(
                            entity = item,
                            strings = strings,
                            onClick = { onOpenTranscript(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    strings: AppStrings,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_audiotext_emblem_1794638062155),
            contentDescription = strings.appName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = strings.appName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = strings.appSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onOpenHistory,
            modifier = Modifier
                .size(48.dp)
                .testTag("header_history_button")
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = strings.history
            )
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .size(48.dp)
                .testTag("header_settings_button")
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = strings.settings
            )
        }
    }
}

@Composable
private fun UploadAudioCard(
    strings: AppStrings,
    selectedAudio: SelectedAudioInfo?,
    isBusy: Boolean,
    onSelectFile: () -> Unit,
    onRemoveFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.UploadFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.uploadAudioTitle,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = strings.uploadAudioDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (selectedAudio != null && !selectedAudio.isRecorded) {
                SelectedAudioSummaryBox(
                    audio = selectedAudio,
                    isBusy = isBusy,
                    onReplace = onSelectFile,
                    onRemove = onRemoveFile,
                    replaceLabel = strings.replaceFile,
                    removeLabel = strings.removeFile
                )
            } else {
                Button(
                    onClick = onSelectFile,
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("upload_audio_button")
                ) {
                    Icon(Icons.Filled.AudioFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.selectAudioFile, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.supportedFormatsHint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecordAudioCard(
    strings: AppStrings,
    recordingState: RecordingState,
    micPermissionDenied: Boolean,
    onStartRecord: () -> Unit,
    onPauseRecord: () -> Unit,
    onResumeRecord: () -> Unit,
    onStopAndSave: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRecording = recordingState.status == RecordingStatus.RECORDING
    val isPaused = recordingState.status == RecordingStatus.PAUSED
    val isActive = isRecording || isPaused

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (isActive) RecordCoral.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        tint = if (isActive) RecordCoral else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.recordAudioTitle,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = when {
                            isRecording -> strings.recordingLive
                            isPaused -> strings.recordingPaused
                            else -> strings.recordAudioDesc
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isRecording) RecordCoral else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isActive) {
                    Text(
                        text = AudioFileHelper.formatDuration(recordingState.durationMs),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = MonoFontFamily,
                        color = if (isRecording) RecordCoral else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isActive) {
                LiveWaveformCanvas(
                    amplitudes = recordingState.amplitudes,
                    isRecording = isRecording
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = if (isRecording) onPauseRecord else onResumeRecord,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("record_pause_resume_button")
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isRecording) strings.pauseRecording else strings.resumeRecording)
                    }
                    Button(
                        onClick = onStopAndSave,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("record_stop_save_button")
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.stopAndSave, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = onDiscard,
                    modifier = Modifier
                        .align(Alignment.End)
                        .testTag("record_discard_button")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(strings.discardRecording, color = MaterialTheme.colorScheme.error)
                }
            } else {
                if (micPermissionDenied) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Text(
                            text = strings.micPermissionNeeded,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                Button(
                    onClick = onStartRecord,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("start_recording_button")
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (micPermissionDenied) strings.grantMicPermission else strings.startRecording,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedAudioSummaryBox(
    audio: SelectedAudioInfo,
    isBusy: Boolean,
    replaceLabel: String,
    removeLabel: String,
    onReplace: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = audio.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${AudioFileHelper.formatFileSize(audio.sizeBytes)}  •  ${AudioFileHelper.formatDuration(audio.durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReplace,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("replace_audio_button")
                ) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(replaceLabel)
                }
                OutlinedButton(
                    onClick = onRemove,
                    enabled = !isBusy,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("remove_audio_button")
                ) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = removeLabel,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptionConfigCard(
    strings: AppStrings,
    selectedAudio: SelectedAudioInfo,
    selectedLanguage: AudioLanguage,
    selectedMode: TranscriptionMode,
    keepOriginalAudio: Boolean,
    isTranscribing: Boolean,
    onSelectLanguage: (AudioLanguage) -> Unit,
    onSelectMode: (TranscriptionMode) -> Unit,
    onToggleKeepOriginalAudio: (Boolean) -> Unit,
    onStartTranscription: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transcription_config_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.AudioFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedAudio.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${AudioFileHelper.formatFileSize(selectedAudio.sizeBytes)} • ${AudioFileHelper.formatDuration(selectedAudio.durationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = strings.audioLanguageLabel,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AudioLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = selectedLanguage == lang,
                        onClick = { onSelectLanguage(lang) },
                        label = { Text(strings.audioLanguageDisplay(lang)) },
                        modifier = Modifier.testTag("audio_lang_chip_${lang.code}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = strings.transcriptionModeLabel,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TranscriptionMode.entries.forEachIndexed { idx, mode ->
                    SegmentedButton(
                        selected = selectedMode == mode,
                        onClick = { onSelectMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = idx,
                            count = TranscriptionMode.entries.size
                        ),
                        modifier = Modifier.testTag("mode_segment_${mode.id}")
                    ) {
                        Text(strings.modeDisplay(mode))
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (selectedMode == TranscriptionMode.EXACT) strings.modeExactHint else strings.modeCleanHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = strings.keepOriginalAudioLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = keepOriginalAudio,
                    onCheckedChange = onToggleKeepOriginalAudio,
                    modifier = Modifier.testTag("keep_original_audio_switch")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onStartTranscription,
                enabled = !isTranscribing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("start_transcription_button")
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = strings.startTranscription,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun RecentTranscriptCard(
    entity: TranscriptEntity,
    strings: AppStrings,
    onClick: () -> Unit
) {
    val activeMode = TranscriptionMode.fromId(entity.activeMode)
    val preview = entity.textForMode(activeMode).replace('\n', ' ').trim()
    val dateStr = remember(entity.createdAt) {
        SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(entity.createdAt))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("recent_transcript_card_${entity.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entity.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = strings.detectedLanguageDisplay(entity.detectedLanguage),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$dateStr  •  ${AudioFileHelper.formatDuration(entity.audioDurationMs)}  •  ${strings.modeDisplay(activeMode)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
