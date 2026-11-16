package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.i18n.stringsFor
import com.example.ui.screens.AudioManagerScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ResultScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AudioTextProTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.UiEvent
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val AUDIO_MANAGER = "audio_manager"
    const val RESULT = "result/{transcriptId}"

    fun result(id: Long) = "result/$id"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            AudioTextProApp(viewModel = viewModel)
        }
    }
}

@Composable
fun AudioTextProApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val strings = remember(settings.appLanguage) { stringsFor(settings.appLanguage) }

    val selectedAudio by viewModel.selectedAudio.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val selectedAudioLang by viewModel.selectedAudioLanguage.collectAsStateWithLifecycle()
    val selectedMode by viewModel.selectedMode.collectAsStateWithLifecycle()
    val keepOriginalAudio by viewModel.keepOriginalAudio.collectAsStateWithLifecycle()
    val transcriptionProgress by viewModel.transcriptionProgress.collectAsStateWithLifecycle()
    val recentTranscriptions by viewModel.recentTranscriptions.collectAsStateWithLifecycle()
    val historySearchQuery by viewModel.historySearchQuery.collectAsStateWithLifecycle()
    val historyItems by viewModel.historyItems.collectAsStateWithLifecycle()
    val audioItems by viewModel.audioItems.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val isGeneratingClean by viewModel.isGeneratingClean.collectAsStateWithLifecycle()
    val connectionCheck by viewModel.connectionCheck.collectAsStateWithLifecycle()
    val totalAudioStorageBytes by viewModel.totalAudioStorageBytes.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, strings) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.OpenResult -> {
                    navController.navigate(Routes.result(event.transcriptId))
                }
                is UiEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is UiEvent.ShowError -> {
                    snackbarHostState.showSnackbar(strings.errorMessage(event.error))
                }
                is UiEvent.LaunchShareIntent -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        putExtra(Intent.EXTRA_SUBJECT, event.title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, event.title))
                }
            }
        }
    }

    AudioTextProTheme(themePreference = settings.themePreference) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
            ) { _ ->
                NavHost(
                    navController = navController,
                    startDestination = Routes.HOME,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            strings = strings,
                            selectedAudio = selectedAudio,
                            recordingState = recordingState,
                            selectedAudioLanguage = selectedAudioLang,
                            selectedMode = selectedMode,
                            keepOriginalAudio = keepOriginalAudio,
                            transcriptionProgress = transcriptionProgress,
                            recentTranscriptions = recentTranscriptions,
                            onImportUri = viewModel::importAudioUri,
                            onRemoveSelectedAudio = viewModel::removeSelectedAudio,
                            onStartRecording = viewModel::startRecording,
                            onPauseRecording = viewModel::pauseRecording,
                            onResumeRecording = viewModel::resumeRecording,
                            onStopAndSaveRecording = viewModel::stopAndSaveRecording,
                            onDiscardRecording = viewModel::discardRecording,
                            onSelectAudioLanguage = viewModel::setSelectedAudioLanguage,
                            onSelectMode = viewModel::setSelectedMode,
                            onToggleKeepOriginalAudio = viewModel::setKeepOriginalAudio,
                            onStartTranscription = viewModel::startTranscription,
                            onCancelTranscription = viewModel::cancelTranscription,
                            onRetryTranscription = viewModel::retryTranscription,
                            onDismissError = viewModel::clearTranscriptionError,
                            onOpenTranscript = { id -> navController.navigate(Routes.result(id)) },
                            onNavigateHistory = { navController.navigate(Routes.HISTORY) },
                            onNavigateSettings = { navController.navigate(Routes.SETTINGS) },
                            onNavigateAudioManager = {
                                viewModel.refreshAudioStorageSize()
                                navController.navigate(Routes.AUDIO_MANAGER)
                            }
                        )
                    }

                    composable(
                        route = Routes.RESULT,
                        arguments = listOf(navArgument("transcriptId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val transcriptId = backStackEntry.arguments?.getLong("transcriptId") ?: 0L
                        val entity by viewModel.observeTranscript(transcriptId)
                            .collectAsStateWithLifecycle(initialValue = null)

                        ResultScreen(
                            entity = entity,
                            strings = strings,
                            playbackState = playbackState,
                            isGeneratingClean = isGeneratingClean,
                            onBack = { navController.popBackStack() },
                            onSwitchMode = viewModel::switchResultMode,
                            onSaveEditedText = { id, mode, text ->
                                viewModel.saveEditedTranscript(id, mode, text, strings.transcriptSaved)
                            },
                            onSaveToHistory = { id ->
                                viewModel.saveTranscriptToHistory(id, strings.transcriptSaved)
                            },
                            onDeleteTranscript = viewModel::deleteTranscript,
                            onDeleteAudioOnly = { id ->
                                viewModel.deleteAudioOnly(id, strings.audioDeletedMsg)
                            },
                            onToggleAudioPlayPause = viewModel.playerController::togglePlayPause,
                            onSeekAudio = viewModel.playerController::seekTo,
                            onExportToUri = { uri, item, mode, format ->
                                viewModel.exportToSafUri(uri, item, mode, format, strings.exportSuccess)
                            },
                            onShareExportFile = viewModel::exportAndShareFile,
                            onSharePlainText = { title, text ->
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, title)
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, title))
                            },
                            onShowMessage = { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        )
                    }

                    composable(Routes.HISTORY) {
                        HistoryScreen(
                            strings = strings,
                            searchQuery = historySearchQuery,
                            items = historyItems,
                            onSearchQueryChange = viewModel::updateHistorySearchQuery,
                            onBack = { navController.popBackStack() },
                            onOpenTranscript = { id -> navController.navigate(Routes.result(id)) },
                            onRenameTranscript = viewModel::renameTranscript,
                            onDeleteTranscript = viewModel::deleteTranscript,
                            onClearAllHistory = viewModel::clearAllHistory,
                            onExportToUri = { uri, item, mode, format ->
                                viewModel.exportToSafUri(uri, item, mode, format, strings.exportSuccess)
                            },
                            onShareExportFile = viewModel::exportAndShareFile,
                            onShowMessage = { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        )
                    }

                    composable(Routes.AUDIO_MANAGER) {
                        AudioManagerScreen(
                            strings = strings,
                            audioItems = audioItems,
                            totalStorageBytes = totalAudioStorageBytes,
                            playbackState = playbackState,
                            onBack = { navController.popBackStack() },
                            onOpenTranscript = { id -> navController.navigate(Routes.result(id)) },
                            onTogglePlayPause = viewModel.playerController::togglePlayPause,
                            onSeek = viewModel.playerController::seekTo,
                            onDeleteAudioOnly = { id ->
                                viewModel.deleteAudioOnly(id, strings.audioDeletedMsg)
                            },
                            onDeleteAllAudio = {
                                viewModel.deleteAllStoredAudio(strings.audioDeletedMsg)
                            }
                        )
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            strings = strings,
                            settings = settings,
                            connectionCheck = connectionCheck,
                            totalAudioStorageBytes = totalAudioStorageBytes,
                            onBack = { navController.popBackStack() },
                            onUpdateAppLanguage = viewModel::updateAppLanguage,
                            onUpdateDefaultMode = viewModel::updateDefaultMode,
                            onUpdateDefaultAudioLanguage = viewModel::updateDefaultAudioLanguage,
                            onUpdateTheme = viewModel::updateThemePreference,
                            onUpdateAutoSaveAudio = viewModel::updateSaveOriginalAudioAuto,
                            onUpdateAutoSaveTranscript = viewModel::updateSaveTranscriptsAuto,
                            onUpdateAutoStartOnSelect = viewModel::updateAutoStartOnAudioSelect,
                            onUpdateEngine = viewModel::updateEngine,
                            onUpdateBackendConfig = viewModel::updateBackendConfig,
                            onCheckConnection = viewModel::checkApiConnection,
                            onOpenAudioManager = {
                                viewModel.refreshAudioStorageSize()
                                navController.navigate(Routes.AUDIO_MANAGER)
                            },
                            onClearHistory = viewModel::clearAllHistory,
                            onDeleteAllAudio = {
                                viewModel.deleteAllStoredAudio(strings.audioDeletedMsg)
                            }
                        )
                    }
                }
            }
        }
    }
}
