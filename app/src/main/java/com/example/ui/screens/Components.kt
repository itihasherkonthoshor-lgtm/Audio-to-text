package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.audio.AudioFileHelper
import com.example.data.audio.PlaybackState
import com.example.domain.ExportFormat
import com.example.domain.TranscriptionProgress
import com.example.domain.TranscriptionStage
import com.example.i18n.AppStrings
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.RecordCoral

@Composable
fun LiveWaveformCanvas(
    amplitudes: List<Float>,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor by animateColorAsState(
        targetValue = if (isRecording) RecordCoral else MaterialTheme.colorScheme.primary,
        label = "waveColor"
    )
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        val barCount = 44
        val totalWidth = size.width
        val slotWidth = totalWidth / barCount
        val barWidth = (slotWidth * 0.56f).coerceAtLeast(3f)
        val centerY = size.height / 2f

        val padded = if (amplitudes.size >= barCount) {
            amplitudes.takeLast(barCount)
        } else {
            List(barCount - amplitudes.size) { 0.08f } + amplitudes
        }

        padded.forEachIndexed { index, amp ->
            val fraction = amp.coerceIn(0.06f, 1f)
            val barHeight = (size.height * fraction).coerceAtLeast(4f)
            val x = index * slotWidth + (slotWidth - barWidth) / 2f
            val y = centerY - barHeight / 2f
            drawRoundRect(
                color = if (index >= barCount - amplitudes.size) activeColor else trackColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun TranscriptionProgressBanner(
    progress: TranscriptionProgress,
    strings: AppStrings,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (progress.isRunning) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .testTag("transcription_progress_banner"),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = progress.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = strings.stageDisplay(progress.stage),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("cancel_transcription_button")
                    ) {
                        Text(strings.cancelTranscription)
                    }
                }

                if (progress.totalChunks > 0 || progress.etaSeconds != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (progress.totalChunks > 0) {
                            val leftLabel = if (progress.stage is TranscriptionStage.Uploading) {
                                "${strings.chunksCompletedFormat(progress.completedChunks, progress.totalChunks)} • ${strings.uploadPercentFormat(progress.uploadPercent)}"
                            } else {
                                strings.chunksCompletedFormat(progress.completedChunks, progress.totalChunks)
                            }
                            Text(
                                text = leftLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = MonoFontFamily,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                            )
                        }
                        progress.etaSeconds?.takeIf { it > 0L }?.let { eta ->
                            Text(
                                text = strings.etaSecondsFormat(eta),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = MonoFontFamily,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                when (val s = progress.stage) {
                    is TranscriptionStage.Uploading -> {
                        LinearProgressIndicator(
                            progress = { (s.uploadPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                        )
                    }
                    else -> {
                        if (progress.totalChunks > 1 && progress.completedChunks > 0) {
                            LinearProgressIndicator(
                                progress = {
                                    (progress.completedChunks.toFloat() / progress.totalChunks.toFloat()).coerceIn(0f, 1f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                }
            }
        }
    } else if (progress.error != null) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .testTag("transcription_error_banner"),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.errorMessage(progress.error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (progress.canRetryFailedChunks && progress.totalChunks > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = strings.chunksCompletedFormat(progress.completedChunks, progress.totalChunks),
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = MonoFontFamily,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                            if (progress.failedChunkIndices.isNotEmpty()) {
                                Text(
                                    text = strings.failedChunksFormat(progress.failedChunkIndices.map { it + 1 }),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = MonoFontFamily,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDismissError) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = strings.close,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.testTag("retry_transcription_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (progress.canRetryFailedChunks) strings.retryFailedChunks else strings.retry)
                    }
                }
            }
        }
    }
}

@Composable
fun AudioPlaybackCard(
    filePath: String,
    fileName: String,
    fileSizeBytes: Long,
    fallbackDurationMs: Long,
    playbackState: PlaybackState,
    strings: AppStrings,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onDeleteAudio: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isThisActive = playbackState.activeFilePath == filePath
    val isPlaying = isThisActive && playbackState.isPlaying
    val totalMs = if (isThisActive && playbackState.totalDurationMs > 0L) {
        playbackState.totalDurationMs
    } else {
        fallbackDurationMs.coerceAtLeast(1L)
    }
    val currentMs = if (isThisActive) playbackState.currentPositionMs.coerceAtMost(totalMs) else 0L

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("audio_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) strings.pauseRecording else strings.originalAudioPlayback
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${AudioFileHelper.formatFileSize(fileSizeBytes)}  •  ${AudioFileHelper.formatDuration(totalMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onDeleteAudio != null) {
                    IconButton(
                        onClick = onDeleteAudio,
                        modifier = Modifier.testTag("delete_audio_only_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = strings.deleteAudioOnly,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Slider(
                value = if (totalMs > 0L) currentMs.toFloat() / totalMs.toFloat() else 0f,
                onValueChange = { frac -> onSeek((frac * totalMs).toLong()) },
                enabled = isThisActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("audio_seek_slider")
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = AudioFileHelper.formatDuration(currentMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = AudioFileHelper.formatDuration(totalMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportActionSheet(
    format: ExportFormat,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSaveToDevice: () -> Unit,
    onShareFile: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${strings.chooseExportAction} (${format.extension.uppercase()})",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = {
                    onDismiss()
                    onSaveToDevice()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("export_save_device_button")
            ) {
                Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text(strings.saveToDevice, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onShareFile()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("export_share_file_button")
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text(strings.shareAsFile, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    cancelText: String,
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelText)
            }
        }
    )
}
