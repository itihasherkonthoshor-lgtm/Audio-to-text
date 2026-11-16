package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.audio.AudioFileException
import com.example.data.audio.AudioFileHelper
import com.example.data.audio.AudioPlayerController
import com.example.data.audio.AudioRecorderManager
import com.example.data.audio.PlaybackState
import com.example.data.export.DocumentExporter
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsRepository
import com.example.data.local.TranscriptEntity
import com.example.data.local.TranscriptRepository
import com.example.data.remote.BackendTranscriptionService
import com.example.data.remote.FirebaseAiTranscriptionService
import com.example.data.remote.TranscriptionCoordinator
import com.example.data.work.TranscriptionJobManager
import com.example.domain.AppError
import com.example.domain.AppErrorType
import com.example.domain.AppLanguage
import com.example.domain.AppSettings
import com.example.domain.AudioLanguage
import com.example.domain.ConnectionCheckResult
import com.example.domain.ExportFormat
import com.example.domain.JobStatus
import com.example.domain.RecordingState
import com.example.domain.SelectedAudioInfo
import com.example.domain.ThemePreference
import com.example.domain.TranscriptSegment
import com.example.domain.TranscriptionEngine
import com.example.domain.TranscriptionMode
import com.example.domain.TranscriptionProgress
import com.example.domain.TranscriptionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "MainViewModel"

sealed interface UiEvent {
    data class OpenResult(val transcriptId: Long) : UiEvent
    data class ShowMessage(val message: String) : UiEvent
    data class ShowError(val error: AppError) : UiEvent
    data class LaunchShareIntent(val uri: Uri, val mimeType: String, val title: String) : UiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val transcriptRepo = TranscriptRepository(appContext, database.transcriptDao())
    private val settingsRepo = SettingsRepository(appContext)

    private val firebaseService = FirebaseAiTranscriptionService(appContext)
    private val backendService = BackendTranscriptionService()
    private val coordinator = TranscriptionCoordinator(
        context = appContext,
        jobDao = database.jobDao(),
        transcriptRepo = transcriptRepo,
        firebaseService = firebaseService,
        backendService = backendService
    )
    private val jobManager = TranscriptionJobManager(appContext, database.jobDao(), backendService)

    val recorderManager = AudioRecorderManager(appContext)
    val playerController = AudioPlayerController(appContext, viewModelScope)

    val settings: StateFlow<AppSettings> = settingsRepo.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    val recentTranscriptions: StateFlow<List<TranscriptEntity>> = transcriptRepo.recentSaved(5).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _historySearchQuery = MutableStateFlow("")
    val historySearchQuery: StateFlow<String> = _historySearchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyItems: StateFlow<List<TranscriptEntity>> = _historySearchQuery
        .flatMapLatest { query -> transcriptRepo.searchSaved(query) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val audioItems: StateFlow<List<TranscriptEntity>> = transcriptRepo.itemsWithAudio.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val recordingState: StateFlow<RecordingState> = recorderManager.state
    val playbackState: StateFlow<PlaybackState> = playerController.state

    private val _selectedAudio = MutableStateFlow<SelectedAudioInfo?>(null)
    val selectedAudio: StateFlow<SelectedAudioInfo?> = _selectedAudio.asStateFlow()

    private val _selectedAudioLanguage = MutableStateFlow(AudioLanguage.BENGALI)
    val selectedAudioLanguage: StateFlow<AudioLanguage> = _selectedAudioLanguage.asStateFlow()

    private val _selectedMode = MutableStateFlow(TranscriptionMode.EXACT)
    val selectedMode: StateFlow<TranscriptionMode> = _selectedMode.asStateFlow()

    private val _keepOriginalAudio = MutableStateFlow(true)
    val keepOriginalAudio: StateFlow<Boolean> = _keepOriginalAudio.asStateFlow()

    private val _transcriptionProgress = MutableStateFlow(TranscriptionProgress())
    val transcriptionProgress: StateFlow<TranscriptionProgress> = _transcriptionProgress.asStateFlow()

    private val _isGeneratingClean = MutableStateFlow(false)
    val isGeneratingClean: StateFlow<Boolean> = _isGeneratingClean.asStateFlow()

    private val _connectionCheck = MutableStateFlow(ConnectionCheckResult())
    val connectionCheck: StateFlow<ConnectionCheckResult> = _connectionCheck.asStateFlow()

    private val _totalAudioStorageBytes = MutableStateFlow(0L)
    val totalAudioStorageBytes: StateFlow<Long> = _totalAudioStorageBytes.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var defaultsSynced = false
    private val trackedSessionJobIds = mutableSetOf<String>()
    private val openedResultJobIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { s ->
                if (!defaultsSynced) {
                    _selectedAudioLanguage.value = s.defaultAudioLanguage
                    _selectedMode.value = s.defaultMode
                    _keepOriginalAudio.value = s.saveOriginalAudioAuto
                    defaultsSynced = true
                }
            }
        }
        viewModelScope.launch {
            firebaseService.prewarmAppCheck()
        }
        viewModelScope.launch {
            val resumedId = jobManager.resumeActiveJobIfNeeded()
            if (resumedId != null) {
                trackedSessionJobIds.add(resumedId)
            }
        }
        viewModelScope.launch {
            jobManager.observeLatestProgress().collect { progress ->
                if (progress.isRunning && !progress.jobId.isNullOrBlank()) {
                    trackedSessionJobIds.add(progress.jobId)
                }
                _transcriptionProgress.value = progress

                val jid = progress.jobId
                val tid = progress.resultTranscriptId
                if (progress.jobStatus == JobStatus.COMPLETED &&
                    tid != null &&
                    !jid.isNullOrBlank() &&
                    trackedSessionJobIds.contains(jid) &&
                    openedResultJobIds.add(jid)
                ) {
                    _selectedAudio.value = null
                    recorderManager.resetToIdle()
                    refreshAudioStorageSize()
                    _events.emit(UiEvent.OpenResult(tid))
                }
            }
        }
        refreshAudioStorageSize()
    }

    fun observeTranscript(id: Long): Flow<TranscriptEntity?> = transcriptRepo.observeById(id)

    fun decodeSegments(json: String): List<TranscriptSegment> = transcriptRepo.decodeSegments(json)

    fun setSelectedAudioLanguage(language: AudioLanguage) {
        _selectedAudioLanguage.value = language
    }

    fun setSelectedMode(mode: TranscriptionMode) {
        _selectedMode.value = mode
    }

    fun setKeepOriginalAudio(keep: Boolean) {
        _keepOriginalAudio.value = keep
    }

    fun importAudioUri(uri: Uri) {
        viewModelScope.launch {
            try {
                cleanupSelectedTemp()
                val info = AudioFileHelper.importFromUri(appContext, uri)
                _selectedAudio.value = info
                _transcriptionProgress.value = _transcriptionProgress.value.copy(error = null)
                if (settings.value.autoStartOnAudioSelect && !_transcriptionProgress.value.isRunning) {
                    startTranscription()
                }
            } catch (e: AudioFileException) {
                _events.emit(UiEvent.ShowError(e.appError))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import audio URI", e)
                _events.emit(UiEvent.ShowError(AppError(AppErrorType.UNSUPPORTED_FORMAT, e.localizedMessage)))
            }
        }
    }

    fun removeSelectedAudio() {
        playerController.stopIfPlaying(_selectedAudio.value?.localTempPath)
        cleanupSelectedTemp()
        _selectedAudio.value = null
        recorderManager.resetToIdle()
    }

    fun startRecording() {
        val result = recorderManager.startRecording(viewModelScope)
        result.onFailure { e ->
            Log.e(TAG, "Failed to start recording", e)
            viewModelScope.launch {
                _events.emit(UiEvent.ShowError(AppError(AppErrorType.TRANSCRIPTION_FAILED, e.localizedMessage)))
            }
        }
    }

    fun pauseRecording() {
        recorderManager.pauseRecording()
    }

    fun resumeRecording() {
        recorderManager.resumeRecording(viewModelScope)
    }

    fun stopAndSaveRecording() {
        val durationSnapshot = recorderManager.state.value.durationMs
        recorderManager.stopAndSave().onSuccess { file ->
            viewModelScope.launch {
                try {
                    cleanupSelectedTemp()
                    val info = AudioFileHelper.prepareRecordedFile(file, durationSnapshot)
                    _selectedAudio.value = info
                    _transcriptionProgress.value = _transcriptionProgress.value.copy(error = null)
                    if (settings.value.autoStartOnAudioSelect && !_transcriptionProgress.value.isRunning) {
                        startTranscription()
                    }
                } catch (e: AudioFileException) {
                    _events.emit(UiEvent.ShowError(e.appError))
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to stop recording", e)
            viewModelScope.launch {
                _events.emit(UiEvent.ShowError(AppError(AppErrorType.EMPTY_AUDIO)))
            }
        }
    }

    fun discardRecording() {
        recorderManager.discard()
    }

    fun startTranscription() {
        val audio = _selectedAudio.value ?: return
        if (_transcriptionProgress.value.isRunning) return

        val lang = _selectedAudioLanguage.value
        val mode = _selectedMode.value
        val retainAudio = _keepOriginalAudio.value
        val currentSettings = settings.value

        viewModelScope.launch {
            if (!coordinator.isNetworkAvailable()) {
                _transcriptionProgress.value = TranscriptionProgress(
                    isRunning = false,
                    fileName = audio.displayName,
                    stage = TranscriptionStage.Idle,
                    error = AppError(AppErrorType.NO_INTERNET)
                )
                return@launch
            }
            try {
                _transcriptionProgress.value = TranscriptionProgress(
                    isRunning = true,
                    jobStatus = JobStatus.QUEUED,
                    fileName = audio.displayName,
                    stage = TranscriptionStage.Validating
                )
                val jobId = jobManager.enqueueNewJob(
                    audio = audio,
                    audioLanguage = lang,
                    mode = mode,
                    keepOriginalAudio = retainAudio,
                    settings = currentSettings
                )
                trackedSessionJobIds.add(jobId)
            } catch (e: AudioFileException) {
                Log.e(TAG, "Failed to enqueue job: ${e.appError}", e)
                _transcriptionProgress.value = TranscriptionProgress(
                    isRunning = false,
                    fileName = audio.displayName,
                    stage = TranscriptionStage.Idle,
                    error = e.appError
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected enqueue failure", e)
                _transcriptionProgress.value = TranscriptionProgress(
                    isRunning = false,
                    fileName = audio.displayName,
                    stage = TranscriptionStage.Idle,
                    error = AppError(AppErrorType.TRANSCRIPTION_FAILED, e.localizedMessage)
                )
            }
        }
    }

    fun retryTranscription() {
        val currentJobId = _transcriptionProgress.value.jobId
        if (!currentJobId.isNullOrBlank()) {
            viewModelScope.launch {
                if (!coordinator.isNetworkAvailable()) {
                    _events.emit(UiEvent.ShowError(AppError(AppErrorType.NO_INTERNET)))
                    return@launch
                }
                trackedSessionJobIds.add(currentJobId)
                jobManager.retryFailedChunks(currentJobId)
            }
        } else {
            startTranscription()
        }
    }

    fun cancelTranscription() {
        val currentJobId = _transcriptionProgress.value.jobId
        viewModelScope.launch {
            if (!currentJobId.isNullOrBlank()) {
                jobManager.cancelJob(currentJobId, settings.value)
            }
            _transcriptionProgress.value = TranscriptionProgress()
        }
    }

    fun clearTranscriptionError() {
        val currentJobId = _transcriptionProgress.value.jobId
        viewModelScope.launch {
            if (!currentJobId.isNullOrBlank()) {
                jobManager.dismissErrorJob(currentJobId, settings.value)
            }
            _transcriptionProgress.value = TranscriptionProgress()
        }
    }

    fun switchResultMode(entity: TranscriptEntity, targetMode: TranscriptionMode) {
        if (targetMode == TranscriptionMode.EXACT) {
            viewModelScope.launch {
                transcriptRepo.setActiveMode(entity.id, TranscriptionMode.EXACT)
            }
            return
        }

        // Switching to Clean: NEVER overwrite exactText!
        if (!entity.cleanText.isNullOrBlank()) {
            viewModelScope.launch {
                transcriptRepo.setActiveMode(entity.id, TranscriptionMode.CLEAN)
            }
            return
        }

        // Generate clean version from exactText on demand
        viewModelScope.launch {
            _isGeneratingClean.value = true
            try {
                val clean = coordinator.generateCleanText(
                    exactText = entity.exactText,
                    languageCode = entity.detectedLanguage,
                    settings = settings.value
                )
                transcriptRepo.setActiveMode(entity.id, TranscriptionMode.CLEAN, clean)
            } catch (e: AudioFileException) {
                _events.emit(UiEvent.ShowError(e.appError))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Clean generation failed", e)
                _events.emit(UiEvent.ShowError(AppError(AppErrorType.TRANSCRIPTION_FAILED, e.localizedMessage)))
            } finally {
                _isGeneratingClean.value = false
            }
        }
    }

    fun saveEditedTranscript(id: Long, mode: TranscriptionMode, updatedText: String, successMsg: String) {
        viewModelScope.launch {
            transcriptRepo.updateTextForMode(id, mode, updatedText)
            _events.emit(UiEvent.ShowMessage(successMsg))
        }
    }

    fun saveTranscriptToHistory(id: Long, successMsg: String) {
        viewModelScope.launch {
            transcriptRepo.saveToHistory(id)
            _events.emit(UiEvent.ShowMessage(successMsg))
        }
    }

    fun renameTranscript(id: Long, newTitle: String) {
        viewModelScope.launch {
            transcriptRepo.rename(id, newTitle)
        }
    }

    fun deleteTranscript(id: Long) {
        viewModelScope.launch {
            val entity = transcriptRepo.getById(id)
            entity?.audioFilePath?.let { playerController.stopIfPlaying(it) }
            transcriptRepo.deleteTranscriptAndAudio(id)
            refreshAudioStorageSize()
        }
    }

    fun deleteAudioOnly(id: Long, successMsg: String) {
        viewModelScope.launch {
            val entity = transcriptRepo.getById(id)
            entity?.audioFilePath?.let { playerController.stopIfPlaying(it) }
            transcriptRepo.deleteAudioOnly(id)
            refreshAudioStorageSize()
            _events.emit(UiEvent.ShowMessage(successMsg))
        }
    }

    fun deleteAllStoredAudio(successMsg: String) {
        viewModelScope.launch {
            playerController.stopIfPlaying(null)
            transcriptRepo.deleteAllAudioFiles()
            refreshAudioStorageSize()
            _events.emit(UiEvent.ShowMessage(successMsg))
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            playerController.stopIfPlaying(null)
            transcriptRepo.clearAllHistory()
            refreshAudioStorageSize()
        }
    }

    fun updateHistorySearchQuery(query: String) {
        _historySearchQuery.value = query
    }

    fun exportToSafUri(
        uri: Uri,
        entity: TranscriptEntity,
        mode: TranscriptionMode,
        format: ExportFormat,
        successMsg: String
    ) {
        viewModelScope.launch {
            try {
                val segments = transcriptRepo.decodeSegments(entity.segmentsJson)
                DocumentExporter.exportToUri(appContext, uri, entity, mode, segments, format)
                _events.emit(UiEvent.ShowMessage(successMsg))
            } catch (e: AudioFileException) {
                _events.emit(UiEvent.ShowError(e.appError))
            } catch (e: Exception) {
                Log.e(TAG, "Export to URI failed", e)
                _events.emit(UiEvent.ShowError(AppError(AppErrorType.EXPORT_FAILED, e.localizedMessage)))
            }
        }
    }

    fun exportAndShareFile(
        entity: TranscriptEntity,
        mode: TranscriptionMode,
        format: ExportFormat
    ) {
        viewModelScope.launch {
            try {
                val segments = transcriptRepo.decodeSegments(entity.segmentsJson)
                val file = DocumentExporter.exportToShareCacheFile(appContext, entity, mode, segments, format)
                val authority = "${appContext.packageName}.fileprovider"
                val contentUri = FileProvider.getUriForFile(appContext, authority, file)
                _events.emit(UiEvent.LaunchShareIntent(contentUri, format.mimeType, entity.title))
            } catch (e: AudioFileException) {
                _events.emit(UiEvent.ShowError(e.appError))
            } catch (e: Exception) {
                Log.e(TAG, "Export for share failed", e)
                _events.emit(UiEvent.ShowError(AppError(AppErrorType.EXPORT_FAILED, e.localizedMessage)))
            }
        }
    }

    fun updateAppLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsRepo.setAppLanguage(language) }
    }

    fun updateDefaultMode(mode: TranscriptionMode) {
        viewModelScope.launch {
            settingsRepo.setDefaultMode(mode)
            _selectedMode.value = mode
        }
    }

    fun updateDefaultAudioLanguage(language: AudioLanguage) {
        viewModelScope.launch {
            settingsRepo.setDefaultAudioLanguage(language)
            _selectedAudioLanguage.value = language
        }
    }

    fun updateThemePreference(theme: ThemePreference) {
        viewModelScope.launch { settingsRepo.setThemePreference(theme) }
    }

    fun updateSaveOriginalAudioAuto(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setSaveOriginalAudioAuto(enabled)
            _keepOriginalAudio.value = enabled
        }
    }

    fun updateSaveTranscriptsAuto(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setSaveTranscriptsAuto(enabled) }
    }

    fun updateAutoStartOnAudioSelect(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoStartOnAudioSelect(enabled) }
    }

    fun updateEngine(engine: TranscriptionEngine) {
        viewModelScope.launch {
            settingsRepo.setEngine(engine)
            _connectionCheck.value = ConnectionCheckResult()
        }
    }

    fun updateBackendConfig(url: String, token: String) {
        viewModelScope.launch {
            settingsRepo.setBackendConfig(url, token)
        }
    }

    fun checkApiConnection() {
        viewModelScope.launch {
            _connectionCheck.value = ConnectionCheckResult(isChecking = true)
            if (!coordinator.isNetworkAvailable()) {
                _connectionCheck.value = ConnectionCheckResult(
                    isChecking = false,
                    isConnected = false,
                    statusDetail = "Offline"
                )
                return@launch
            }
            val current = settings.value
            val result = when (current.engine) {
                TranscriptionEngine.FIREBASE_GEMINI -> firebaseService.checkReadiness()
                TranscriptionEngine.CUSTOM_BACKEND_OPENAI ->
                    backendService.checkHealth(current.backendBaseUrl, current.backendAuthToken)
            }
            _connectionCheck.value = result.fold(
                onSuccess = { detail ->
                    ConnectionCheckResult(isChecking = false, isConnected = true, statusDetail = detail)
                },
                onFailure = { err ->
                    ConnectionCheckResult(
                        isChecking = false,
                        isConnected = false,
                        statusDetail = err.localizedMessage ?: "Error"
                    )
                }
            )
        }
    }

    fun refreshAudioStorageSize() {
        viewModelScope.launch {
            _totalAudioStorageBytes.value = transcriptRepo.calculateSavedAudioBytes()
        }
    }

    private fun cleanupSelectedTemp() {
        val current = _selectedAudio.value ?: return
        if (current.localTempPath.contains("/cache/")) {
            runCatching { File(current.localTempPath).delete() }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerController.release()
        recorderManager.discard()
    }
}
