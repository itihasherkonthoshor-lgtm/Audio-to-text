package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.audio.AudioFileHelper
import com.example.data.export.DocumentExporter
import com.example.data.local.TranscriptEntity
import com.example.domain.ExportFormat
import com.example.domain.TranscriptionMode
import com.example.i18n.AppStrings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    strings: AppStrings,
    searchQuery: String,
    items: List<TranscriptEntity>,
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenTranscript: (Long) -> Unit,
    onRenameTranscript: (Long, String) -> Unit,
    onDeleteTranscript: (Long) -> Unit,
    onClearAllHistory: () -> Unit,
    onExportToUri: (android.net.Uri, TranscriptEntity, TranscriptionMode, ExportFormat) -> Unit,
    onShareExportFile: (TranscriptEntity, TranscriptionMode, ExportFormat) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var showClearAllDialog by remember { mutableStateOf(false) }
    var itemToRename by remember { mutableStateOf<TranscriptEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<TranscriptEntity?>(null) }
    var exportTarget by remember { mutableStateOf<Pair<TranscriptEntity, ExportFormat>?>(null) }
    var pendingSafTarget by remember { mutableStateOf<Pair<TranscriptEntity, ExportFormat>?>(null) }

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val target = pendingSafTarget
        pendingSafTarget = null
        if (uri != null && target != null) {
            val (entity, format) = target
            onExportToUri(uri, entity, TranscriptionMode.fromId(entity.activeMode), format)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(strings.history) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("history_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.close)
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearAllDialog = true },
                            modifier = Modifier.testTag("clear_all_history_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = strings.clearAllHistory,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
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
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text(strings.searchHistoryPlaceholder) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = strings.close)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .testTag("history_search_input")
                )

                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = strings.noHistoryTitle,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = strings.noHistoryDesc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(items, key = { it.id }) { entity ->
                            HistoryItemCard(
                                entity = entity,
                                strings = strings,
                                onOpen = { onOpenTranscript(entity.id) },
                                onCopy = {
                                    val active = TranscriptionMode.fromId(entity.activeMode)
                                    clipboard.setText(AnnotatedString(entity.textForMode(active)))
                                    onShowMessage(strings.copiedToClipboard)
                                },
                                onRename = {
                                    renameText = entity.title
                                    itemToRename = entity
                                },
                                onExport = { format ->
                                    exportTarget = entity to format
                                },
                                onDelete = { itemToDelete = entity }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearAllDialog) {
        ConfirmDialog(
            title = strings.clearAllHistoryConfirmTitle,
            message = strings.clearAllHistoryConfirmMsg,
            confirmText = strings.delete,
            cancelText = strings.cancel,
            isDestructive = true,
            onConfirm = {
                showClearAllDialog = false
                onClearAllHistory()
            },
            onDismiss = { showClearAllDialog = false }
        )
    }

    itemToDelete?.let { target ->
        ConfirmDialog(
            title = strings.deleteTranscriptConfirmTitle,
            message = strings.deleteTranscriptConfirmMsg,
            confirmText = strings.delete,
            cancelText = strings.cancel,
            isDestructive = true,
            onConfirm = {
                itemToDelete = null
                onDeleteTranscript(target.id)
            },
            onDismiss = { itemToDelete = null }
        )
    }

    itemToRename?.let { target ->
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text(strings.renameTranscript) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(strings.newTitleLabel) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_transcript_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameTranscript(target.id, renameText)
                        itemToRename = null
                    }
                ) {
                    Text(strings.save)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    exportTarget?.let { (entity, format) ->
        ExportActionSheet(
            format = format,
            strings = strings,
            onDismiss = { exportTarget = null },
            onSaveToDevice = {
                pendingSafTarget = entity to format
                val safe = DocumentExporter.sanitizeFilename(entity.title)
                safLauncher.launch("${safe}.${format.extension}")
            },
            onShareFile = {
                onShareExportFile(entity, TranscriptionMode.fromId(entity.activeMode), format)
            }
        )
    }
}

@Composable
private fun HistoryItemCard(
    entity: TranscriptEntity,
    strings: AppStrings,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onDelete: () -> Unit
) {
    val activeMode = TranscriptionMode.fromId(entity.activeMode)
    val preview = entity.textForMode(activeMode).replace('\n', ' ').trim()
    val dateStr = remember(entity.createdAt) {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(entity.createdAt))
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var exportMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("history_card_${entity.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${entity.originalFileName}  •  $dateStr",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("history_menu_button_${entity.id}")
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = strings.open)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.open) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onOpen()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.copy) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onCopy()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.renameTranscript) },
                            leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.exportLabel) },
                            leadingIcon = { Icon(Icons.Filled.IosShare, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                exportMenuExpanded = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.delete, color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }

                    DropdownMenu(
                        expanded = exportMenuExpanded,
                        onDismissRequest = { exportMenuExpanded = false }
                    ) {
                        ExportFormat.entries.forEach { fmt ->
                            DropdownMenuItem(
                                text = { Text(fmt.extension.uppercase()) },
                                onClick = {
                                    exportMenuExpanded = false
                                    onExport(fmt)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                Text(
                    text = "${AudioFileHelper.formatDuration(entity.audioDurationMs)}  •  ${strings.modeDisplay(activeMode)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
