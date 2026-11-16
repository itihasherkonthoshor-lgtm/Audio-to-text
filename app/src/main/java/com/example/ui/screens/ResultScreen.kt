package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.audio.AudioFileHelper
import com.example.data.audio.PlaybackState
import com.example.data.export.DocumentExporter
import com.example.data.local.TranscriptEntity
import com.example.domain.ExportFormat
import com.example.domain.TranscriptionMode
import com.example.i18n.AppStrings
import com.example.ui.theme.MonoFontFamily
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResultScreen(
    entity: TranscriptEntity?,
    strings: AppStrings,
    playbackState: PlaybackState,
    isGeneratingClean: Boolean,
    onBack: () -> Unit,
    onSwitchMode: (TranscriptEntity, TranscriptionMode) -> Unit,
    onSaveEditedText: (Long, TranscriptionMode, String) -> Unit,
    onSaveToHistory: (Long) -> Unit,
    onDeleteTranscript: (Long) -> Unit,
    onDeleteAudioOnly: (Long) -> Unit,
    onToggleAudioPlayPause: (String) -> Unit,
    onSeekAudio: (Long) -> Unit,
    onExportToUri: (android.net.Uri, TranscriptEntity, TranscriptionMode, ExportFormat) -> Unit,
    onShareExportFile: (TranscriptEntity, TranscriptionMode, ExportFormat) -> Unit,
    onSharePlainText: (String, String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current

    if (entity == null) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val activeMode = TranscriptionMode.fromId(entity.activeMode)
    val currentDisplayText = entity.textForMode(activeMode)

    var isEditing by remember(entity.id) { mutableStateOf(false) }
    var editedText by remember(entity.id, activeMode, currentDisplayText) {
        mutableStateOf(currentDisplayText)
    }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSelectCopyDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeleteAudioConfirm by remember { mutableStateOf(false) }
    var activeExportSheetFormat by remember { mutableStateOf<ExportFormat?>(null) }
    var pendingSafFormat by remember { mutableStateOf<ExportFormat?>(null) }

    LaunchedEffect(activeMode, currentDisplayText) {
        if (!isEditing) {
            editedText = currentDisplayText
        }
    }

    val safExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val fmt = pendingSafFormat
        pendingSafFormat = null
        if (uri != null && fmt != null) {
            onExportToUri(uri, entity, activeMode, fmt)
        }
    }

    val activeTextForStats = if (isEditing) editedText else currentDisplayText
    val charCount = activeTextForStats.length
    val wordCount = remember(activeTextForStats) {
        activeTextForStats.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    }

    val matchCount = remember(currentDisplayText, searchQuery) {
        if (searchQuery.isBlank()) 0
        else Regex(Regex.escape(searchQuery), RegexOption.IGNORE_CASE)
            .findAll(currentDisplayText).count()
    }

    val highlightedTranscript = remember(
        currentDisplayText,
        searchQuery,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        if (searchQuery.isBlank()) {
            AnnotatedString(currentDisplayText)
        } else {
            buildAnnotatedString {
                append(currentDisplayText)
                val regex = Regex(Regex.escape(searchQuery), RegexOption.IGNORE_CASE)
                regex.findAll(currentDisplayText).forEach { match ->
                    addStyle(
                        style = SpanStyle(
                            background = androidx.compose.ui.graphics.Color(0xFFFDE047),
                            color = androidx.compose.ui.graphics.Color(0xFF0F172A),
                            fontWeight = FontWeight.Bold
                        ),
                        start = match.range.first,
                        end = match.range.last + 1
                    )
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.resultTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("result_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.close)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showSearchBar = !showSearchBar },
                        modifier = Modifier.testTag("result_search_toggle_button")
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = strings.searchInTranscript)
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Unsaved notice if auto-save transcripts is OFF
                if (!entity.isSavedToHistory) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.unsavedTranscriptBanner,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = { onSaveToHistory(entity.id) },
                                modifier = Modifier.testTag("save_unsaved_banner_button")
                            ) {
                                Icon(Icons.Filled.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(strings.save)
                            }
                        }
                    }
                }

                // Metadata Header Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = entity.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MetaPill("${strings.fileNameLabel}: ${entity.originalFileName}")
                            MetaPill("${strings.durationLabel}: ${AudioFileHelper.formatDuration(entity.audioDurationMs)}")
                            MetaPill("${strings.detectedLanguageLabel}: ${strings.detectedLanguageDisplay(entity.detectedLanguage)}")
                            MetaPill("${strings.statusLabel}: ${strings.statusCompleted}")
                            MetaPill("${strings.modeLabel}: ${strings.modeDisplay(activeMode)}")
                        }
                    }
                }

                // Mode Switcher (Exact vs Clean) — never overwrites Exact!
                Column {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        TranscriptionMode.entries.forEachIndexed { idx, mode ->
                            SegmentedButton(
                                selected = activeMode == mode,
                                onClick = {
                                    if (!isEditing) onSwitchMode(entity, mode)
                                },
                                enabled = !isGeneratingClean && !isEditing,
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = idx,
                                    count = TranscriptionMode.entries.size
                                ),
                                modifier = Modifier.testTag("result_mode_switch_${mode.id}")
                            ) {
                                Text(
                                    if (mode == TranscriptionMode.EXACT) strings.switchToExact
                                    else strings.switchToClean
                                )
                            }
                        }
                    }
                    if (isGeneratingClean) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = strings.generatingCleanMode,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (activeMode == TranscriptionMode.CLEAN) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.exactPreservedNote,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Media3 Audio Player if original audio file exists on disk
                val audioPath = entity.audioFilePath
                if (!audioPath.isNullOrBlank() && File(audioPath).exists()) {
                    AudioPlaybackCard(
                        filePath = audioPath,
                        fileName = entity.originalFileName,
                        fileSizeBytes = entity.audioSizeBytes,
                        fallbackDurationMs = entity.audioDurationMs,
                        playbackState = playbackState,
                        strings = strings,
                        onTogglePlayPause = { onToggleAudioPlayPause(audioPath) },
                        onSeek = onSeekAudio,
                        onDeleteAudio = { showDeleteAudioConfirm = true }
                    )
                }

                // Collapsible Search Bar within transcript
                if (showSearchBar) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(strings.searchInTranscript) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = strings.close)
                                }
                            }
                        },
                        supportingText = if (searchQuery.isNotBlank()) {
                            { Text(strings.matchesFound(matchCount)) }
                        } else null,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("result_search_input")
                    )
                }

                // Character & Word Count Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${strings.charactersLabel}: $charCount  •  ${strings.wordsLabel}: $wordCount",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isEditing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    isEditing = false
                                    editedText = currentDisplayText
                                }
                            ) {
                                Text(strings.cancel)
                            }
                            Button(
                                onClick = {
                                    onSaveEditedText(entity.id, activeMode, editedText)
                                    isEditing = false
                                },
                                modifier = Modifier.testTag("confirm_edit_save_button")
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(strings.save)
                            }
                        }
                    }
                }

                // Transcript Body (View or Edit)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    if (isEditing) {
                        OutlinedTextField(
                            value = editedText,
                            onValueChange = { editedText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .testTag("result_edit_textfield"),
                            minLines = 8,
                            textStyle = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = highlightedTranscript,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp)
                                    .testTag("result_transcript_text")
                            )
                        }
                    }
                }

                // 10 Action Buttons Grid
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 1. Copy All Text
                    ActionChipButton(
                        icon = Icons.Filled.ContentCopy,
                        label = strings.copyAllText,
                        testTag = "action_copy_all",
                        isPrimary = true,
                        onClick = {
                            clipboard.setText(AnnotatedString(activeTextForStats))
                            onShowMessage(strings.copiedToClipboard)
                        }
                    )
                    // 2. Select and Copy
                    ActionChipButton(
                        icon = Icons.Filled.SelectAll,
                        label = strings.selectAndCopy,
                        testTag = "action_select_copy",
                        onClick = { showSelectCopyDialog = true }
                    )
                    // 3. Edit Transcript
                    ActionChipButton(
                        icon = Icons.Filled.Edit,
                        label = strings.editTranscript,
                        testTag = "action_edit_transcript",
                        onClick = {
                            editedText = currentDisplayText
                            isEditing = true
                        }
                    )
                    // 4. Save Transcript
                    ActionChipButton(
                        icon = Icons.Filled.Save,
                        label = strings.saveTranscript,
                        testTag = "action_save_transcript",
                        onClick = {
                            if (isEditing) {
                                onSaveEditedText(entity.id, activeMode, editedText)
                                isEditing = false
                            } else {
                                onSaveToHistory(entity.id)
                            }
                        }
                    )
                    // 5. Download TXT
                    ActionChipButton(
                        icon = Icons.Outlined.Article,
                        label = strings.downloadTxt,
                        testTag = "action_export_txt",
                        onClick = { activeExportSheetFormat = ExportFormat.TXT }
                    )
                    // 6. Export PDF
                    ActionChipButton(
                        icon = Icons.Filled.PictureAsPdf,
                        label = strings.exportPdf,
                        testTag = "action_export_pdf",
                        onClick = { activeExportSheetFormat = ExportFormat.PDF }
                    )
                    // 7. Export DOCX
                    ActionChipButton(
                        icon = Icons.Outlined.Description,
                        label = strings.exportDocx,
                        testTag = "action_export_docx",
                        onClick = { activeExportSheetFormat = ExportFormat.DOCX }
                    )
                    // 8. Export SRT
                    ActionChipButton(
                        icon = Icons.Filled.Subtitles,
                        label = strings.exportSrt,
                        testTag = "action_export_srt",
                        onClick = { activeExportSheetFormat = ExportFormat.SRT }
                    )
                    // 9. Share Text
                    ActionChipButton(
                        icon = Icons.Filled.Share,
                        label = strings.shareText,
                        testTag = "action_share_text",
                        onClick = { onSharePlainText(entity.title, activeTextForStats) }
                    )
                    // 10. Delete Transcript
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("action_delete_transcript")
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.deleteTranscript)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Export Action Bottom Sheet (Save to Device via SAF OR Share as File)
    activeExportSheetFormat?.let { fmt ->
        ExportActionSheet(
            format = fmt,
            strings = strings,
            onDismiss = { activeExportSheetFormat = null },
            onSaveToDevice = {
                pendingSafFormat = fmt
                val safeName = DocumentExporter.sanitizeFilename(entity.title)
                safExportLauncher.launch("${safeName}.${fmt.extension}")
            },
            onShareFile = {
                onShareExportFile(entity, activeMode, fmt)
            }
        )
    }

    // Select & Copy Dialog
    if (showSelectCopyDialog) {
        AlertDialog(
            onDismissRequest = { showSelectCopyDialog = false },
            title = { Text(strings.selectAndCopy) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = strings.selectAndCopyInstruction,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SelectionContainer {
                        Text(
                            text = activeTextForStats,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSelectCopyDialog = false }) {
                    Text(strings.close)
                }
            }
        )
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = strings.deleteTranscriptConfirmTitle,
            message = strings.deleteTranscriptConfirmMsg,
            confirmText = strings.delete,
            cancelText = strings.cancel,
            isDestructive = true,
            onConfirm = {
                showDeleteConfirm = false
                onDeleteTranscript(entity.id)
                onBack()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    if (showDeleteAudioConfirm) {
        ConfirmDialog(
            title = strings.deleteAudioOnly,
            message = strings.deleteAudioConfirmMsg,
            confirmText = strings.delete,
            cancelText = strings.cancel,
            isDestructive = true,
            onConfirm = {
                showDeleteAudioConfirm = false
                onDeleteAudioOnly(entity.id)
            },
            onDismiss = { showDeleteAudioConfirm = false }
        )
    }
}

@Composable
private fun MetaPill(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ActionChipButton(
    icon: ImageVector,
    label: String,
    testTag: String,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    if (isPrimary) {
        Button(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier
                .height(48.dp)
                .testTag(testTag)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label)
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier
                .height(48.dp)
                .testTag(testTag)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label)
        }
    }
}
