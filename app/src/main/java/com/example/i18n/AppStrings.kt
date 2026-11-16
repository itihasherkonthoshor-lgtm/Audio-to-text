package com.example.i18n

import com.example.domain.AppError
import com.example.domain.AppErrorType
import com.example.domain.AppLanguage
import com.example.domain.AudioLanguage
import com.example.domain.TranscriptionEngine
import com.example.domain.TranscriptionMode
import com.example.domain.TranscriptionStage

data class AppStrings(
    val isBengali: Boolean,
    val appName: String,
    val appSubtitle: String,
    val settings: String,
    val history: String,
    val home: String,
    val audioManager: String,

    // Home cards
    val uploadAudioTitle: String,
    val uploadAudioDesc: String,
    val selectAudioFile: String,
    val replaceFile: String,
    val removeFile: String,
    val supportedFormatsHint: String,

    val recordAudioTitle: String,
    val recordAudioDesc: String,
    val startRecording: String,
    val pauseRecording: String,
    val resumeRecording: String,
    val stopAndSave: String,
    val discardRecording: String,
    val recordingLive: String,
    val recordingPaused: String,
    val micPermissionNeeded: String,
    val grantMicPermission: String,

    // Transcription options
    val audioLanguageLabel: String,
    val langAuto: String,
    val langBengali: String,
    val langEnglish: String,
    val langHindi: String,
    val transcriptionModeLabel: String,
    val modeExact: String,
    val modeExactHint: String,
    val modeClean: String,
    val modeCleanHint: String,
    val keepOriginalAudioLabel: String,
    val startTranscription: String,
    val cancelTranscription: String,

    // Recent & History
    val recentTranscriptions: String,
    val viewAllHistory: String,
    val noHistoryTitle: String,
    val noHistoryDesc: String,
    val searchHistoryPlaceholder: String,
    val clearAllHistory: String,
    val clearAllHistoryConfirmTitle: String,
    val clearAllHistoryConfirmMsg: String,
    val renameTranscript: String,
    val newTitleLabel: String,
    val open: String,
    val edit: String,
    val copy: String,
    val exportLabel: String,
    val delete: String,
    val cancel: String,
    val save: String,
    val confirm: String,
    val retry: String,

    // Result screen
    val resultTitle: String,
    val fileNameLabel: String,
    val durationLabel: String,
    val detectedLanguageLabel: String,
    val statusLabel: String,
    val statusCompleted: String,
    val modeLabel: String,
    val switchToExact: String,
    val switchToClean: String,
    val generatingCleanMode: String,
    val exactPreservedNote: String,
    val charactersLabel: String,
    val wordsLabel: String,
    val searchInTranscript: String,
    val matchesFound: (Int) -> String,

    // 10 Result action buttons
    val copyAllText: String,
    val selectAndCopy: String,
    val editTranscript: String,
    val saveTranscript: String,
    val downloadTxt: String,
    val exportPdf: String,
    val exportDocx: String,
    val exportSrt: String,
    val shareText: String,
    val deleteTranscript: String,

    // Messages
    val copiedToClipboard: String,
    val transcriptSaved: String,
    val exportSuccess: String,
    val deleteTranscriptConfirmTitle: String,
    val deleteTranscriptConfirmMsg: String,
    val selectAndCopyInstruction: String,
    val close: String,
    val unsavedTranscriptBanner: String,

    // Audio player & manager
    val originalAudioPlayback: String,
    val deleteAudioOnly: String,
    val deleteAudioConfirmMsg: String,
    val noSavedAudioTitle: String,
    val noSavedAudioDesc: String,
    val totalStorageUsed: String,
    val audioDeletedMsg: String,

    // Settings
    val appLanguageSetting: String,
    val bengaliLabel: String,
    val englishLabel: String,
    val defaultModeSetting: String,
    val defaultAudioLangSetting: String,
    val themeSetting: String,
    val themeLight: String,
    val themeDark: String,
    val themeSystem: String,
    val autoSaveAudioSetting: String,
    val autoSaveTranscriptSetting: String,
    val autoStartOnSelectSetting: String,
    val transcriptionEngineSetting: String,
    val engineFirebaseGemini: String,
    val engineFirebaseGeminiDesc: String,
    val engineCustomBackend: String,
    val engineCustomBackendDesc: String,
    val backendUrlLabel: String,
    val backendTokenLabel: String,
    val apiConnectionStatus: String,
    val checkConnection: String,
    val connectionOk: String,
    val connectionFailed: String,
    val checkingConnection: String,
    val deleteAllAudioSetting: String,
    val deleteAllAudioConfirmMsg: String,
    val aboutAppTitle: String,
    val aboutAppDesc: String,
    val privacyTitle: String,
    val privacyDesc: String,
    val shareAsFile: String,
    val saveToDevice: String,
    val chooseExportAction: String,

    // Job progress & chunk recovery
    val retryFailedChunks: String,
    val chunksCompletedFormat: (completed: Int, total: Int) -> String,
    val uploadPercentFormat: (percent: Int) -> String,
    val failedChunksFormat: (indicesOneBased: List<Int>) -> String,
    val etaSecondsFormat: (seconds: Long) -> String
) {
    fun audioLanguageDisplay(lang: AudioLanguage): String = when (lang) {
        AudioLanguage.AUTO -> langAuto
        AudioLanguage.BENGALI -> langBengali
        AudioLanguage.ENGLISH -> langEnglish
        AudioLanguage.HINDI -> langHindi
    }

    fun detectedLanguageDisplay(code: String): String {
        val normalized = code.trim().lowercase()
        return when {
            normalized.contains("bn") && normalized.contains("en") ->
                if (isBengali) "বাংলা + English (মিশ্র)" else "Bengali + English (Mixed)"
            normalized.contains("hi") && normalized.contains("en") ->
                if (isBengali) "हिन्दी + English (মিশ্র)" else "Hindi + English (Mixed)"
            normalized == "bn" || normalized == "bengali" || normalized == "bangla" -> langBengali
            normalized == "en" || normalized == "english" -> langEnglish
            normalized == "hi" || normalized == "hindi" -> langHindi
            normalized == "mixed" -> if (isBengali) "মিশ্র ভাষা" else "Mixed Language"
            normalized == "auto" || normalized.isBlank() -> if (isBengali) "শনাক্তকৃত" else "Detected"
            else -> code.uppercase()
        }
    }

    fun modeDisplay(mode: TranscriptionMode): String = when (mode) {
        TranscriptionMode.EXACT -> modeExact
        TranscriptionMode.CLEAN -> modeClean
    }

    fun stageDisplay(stage: TranscriptionStage): String = when (stage) {
        TranscriptionStage.Idle -> ""
        TranscriptionStage.Validating ->
            if (isBengali) "অডিও ফাইল যাচাই করা হচ্ছে..." else "Validating audio file..."
        is TranscriptionStage.Chunking ->
            if (isBengali) "বড় অডিও ভাগ করা হচ্ছে (${stage.current}/${stage.total})..."
            else "Splitting large audio (${stage.current}/${stage.total})..."
        is TranscriptionStage.Uploading ->
            if (isBengali) "অডিও আপলোড হচ্ছে (অংশ ${stage.chunkIndex}/${stage.totalChunks}) — ${stage.uploadPercent}%"
            else "Uploading audio (part ${stage.chunkIndex}/${stage.totalChunks}) — ${stage.uploadPercent}%"
        is TranscriptionStage.Processing ->
            if (isBengali) "অডিও থেকে টেক্সট তৈরি হচ্ছে (অংশ ${stage.chunkIndex}/${stage.totalChunks})..."
            else "Transcribing audio (part ${stage.chunkIndex}/${stage.totalChunks})..."
        TranscriptionStage.CleaningText ->
            if (isBengali) "পরিমার্জিত সংস্করণ প্রস্তুত হচ্ছে..." else "Preparing Clean version..."
    }

    fun errorMessage(error: AppError): String {
        val base = when (error.type) {
            AppErrorType.NO_INTERNET ->
                if (isBengali) "ইন্টারনেট সংযোগ নেই। অনুগ্রহ করে ওয়াই-ফাই বা মোবাইল ডেটা চালু করে আবার চেষ্টা করুন।"
                else "No internet connection. Please check your network and try again."
            AppErrorType.UNSUPPORTED_FORMAT ->
                if (isBengali) "এই অডিও ফরম্যাটটি সমর্থিত নয়। অনুগ্রহ করে MP3, M4A, WAV, MP4, MPEG, OGG বা AAC ফাইল নির্বাচন করুন।"
                else "Unsupported audio format. Please select MP3, M4A, WAV, MP4, MPEG, OGG, or AAC."
            AppErrorType.EMPTY_AUDIO ->
                if (isBengali) "অডিও ফাইলটি খালি বা শূন্য দৈর্ঘ্যের। অনুগ্রহ করে একটি বৈধ অডিও ফাইল ব্যবহার করুন।"
                else "The audio file is empty or has zero duration. Please provide a valid recording."
            AppErrorType.FILE_TOO_LARGE ->
                if (isBengali) "অডিও ফাইলটি প্রসেস করার জন্য অত্যধিক বড় বা সীমা অতিক্রম করেছে।"
                else "The audio file exceeds the maximum supported size."
            AppErrorType.MIC_PERMISSION_DENIED ->
                if (isBengali) "অডিও রেকর্ডিংয়ের জন্য মাইক্রোফোনের অনুমতি প্রয়োজন। অনুগ্রহ করে অনুমতি দিন।"
                else "Microphone permission is required to record audio."
            AppErrorType.API_AUTH_FAILURE ->
                if (isBengali) "API প্রমাণীকরণ ব্যর্থ হয়েছে। সেটিংস থেকে আপনার সার্ভার বা সংযোগ পরীক্ষা করুন।"
                else "API authentication failed. Please check your server configuration in Settings."
            AppErrorType.API_RATE_LIMIT ->
                if (isBengali) "অনুরোধের সীমা অতিক্রম করেছে। কিছুক্ষণ অপেক্ষা করে আবার চেষ্টা করুন।"
                else "API rate limit reached. Please wait a moment and try again."
            AppErrorType.SERVER_UNAVAILABLE ->
                if (isBengali) "ট্রান্সক্রিপশন সার্ভারের সাথে সংযোগ করা যাচ্ছে না। কিছুক্ষণ পর আবার চেষ্টা করুন।"
                else "Transcription server is currently unavailable. Please try again shortly."
            AppErrorType.TRANSCRIPTION_FAILED ->
                if (isBengali) "অডিওটি টেক্সটে রূপান্তর করা যায়নি। অনুগ্রহ করে আবার চেষ্টা করুন।"
                else "Could not transcribe the audio. Please try again."
            AppErrorType.INSUFFICIENT_STORAGE ->
                if (isBengali) "ডিভাইসে পর্যাপ্ত স্টোরেজ নেই। অনুগ্রহ করে কিছু জায়গা খালি করুন।"
                else "Insufficient storage space on your device."
            AppErrorType.EXPORT_FAILED ->
                if (isBengali) "ফাইল রপ্তানি (Export) করা যায়নি। অনুগ্রহ করে আবার চেষ্টা করুন।"
                else "Failed to export file. Please try again."
            AppErrorType.NO_TIMESTAMPS_FOR_SRT ->
                if (isBengali) "SRT সাবটাইটেল তৈরি করতে টাইমস্ট্যাম্প-সমৃদ্ধ ট্রান্সক্রিপশন প্রয়োজন। এই ট্রান্সক্রিপ্টে নির্ভরযোগ্য টাইমস্ট্যাম্প নেই।"
                else "Timestamped subtitle export (SRT) requires timestamp-enabled transcription. Reliable timestamps are unavailable for this transcript."
            AppErrorType.UNKNOWN ->
                if (isBengali) "একটি অপ্রত্যাশিত ত্রুটি ঘটেছে। অনুগ্রহ করে আবার চেষ্টা করুন।"
                else "An unexpected error occurred. Please try again."
        }
        return if (!error.detail.isNullOrBlank()) "$base\n(${error.detail})" else base
    }
}

fun stringsFor(language: AppLanguage): AppStrings = when (language) {
    AppLanguage.BENGALI -> BengaliStrings
    AppLanguage.ENGLISH -> EnglishStrings
}

private val BengaliStrings = AppStrings(
    isBengali = true,
    appName = "AudioText Pro",
    appSubtitle = "অডিও থেকে নির্ভুল লেখায় রূপান্তর করুন।",
    settings = "সেটিংস",
    history = "ইতিহাস",
    home = "হোম",
    audioManager = "সংরক্ষিত অডিও",

    uploadAudioTitle = "অডিও আপলোড",
    uploadAudioDesc = "ডিভাইস থেকে MP3, M4A, WAV, MP4, OGG ফাইল নির্বাচন করুন",
    selectAudioFile = "ফাইল নির্বাচন করুন",
    replaceFile = "ফাইল বদলান",
    removeFile = "মুছুন",
    supportedFormatsHint = "সমর্থিত: MP3, M4A, WAV, MP4, MPEG, OGG, AAC",

    recordAudioTitle = "অডিও রেকর্ড",
    recordAudioDesc = "সরাসরি মাইক্রোফোন থেকে ভয়েস রেকর্ড ও বিরতি-পুনরায় শুরু করুন",
    startRecording = "রেকর্ড শুরু",
    pauseRecording = "বিরতি",
    resumeRecording = "আবার শুরু",
    stopAndSave = "থামান ও সংরক্ষণ",
    discardRecording = "বাতিল",
    recordingLive = "রেকর্ডিং চলছে...",
    recordingPaused = "রেকর্ডিং বিরতিতে",
    micPermissionNeeded = "ভয়েস রেকর্ড করতে মাইক্রোফোনের অনুমতি দিন",
    grantMicPermission = "অনুমতি দিন",

    audioLanguageLabel = "অডিওর ভাষা",
    langAuto = "স্বয়ংক্রিয়",
    langBengali = "বাংলা",
    langEnglish = "English",
    langHindi = "हिन्दी",
    transcriptionModeLabel = "রূপান্তরের ধরন",
    modeExact = "হুবহু (Exact)",
    modeExactHint = "বক্তার উচ্চারিত প্রতিটি শব্দ, পুনরাবৃত্তি ও ভাষা অপরিবর্তিত থাকে",
    modeClean = "পরিমার্জিত (Clean)",
    modeCleanHint = "বিরামচিহ্ন ও অনুচ্ছেদ সাজানো, অপ্রয়োজনীয় পুনরাবৃত্তি কমানো",
    keepOriginalAudioLabel = "রূপান্তরের পর মূল অডিও সংরক্ষণ করুন",
    startTranscription = "লেখায় রূপান্তর করুন",
    cancelTranscription = "বাতিল করুন",

    recentTranscriptions = "সাম্প্রতিক রূপান্তর",
    viewAllHistory = "সব ইতিহাস দেখুন",
    noHistoryTitle = "এখনো কোনো ট্রান্সক্রিপ্ট নেই",
    noHistoryDesc = "উপরে অডিও আপলোড বা রেকর্ড করে আপনার প্রথম ট্রান্সক্রিপশন শুরু করুন।",
    searchHistoryPlaceholder = "ফাইলের নাম বা লেখা খুঁজুন...",
    clearAllHistory = "সব ইতিহাস মুছুন",
    clearAllHistoryConfirmTitle = "সব ইতিহাস মুছে ফেলবেন?",
    clearAllHistoryConfirmMsg = "সংরক্ষিত সমস্ত ট্রান্সক্রিপ্ট স্থায়ীভাবে মুছে যাবে। এই কাজটি পূর্বাবস্থায় ফেরানো যাবে না।",
    renameTranscript = "নাম পরিবর্তন করুন",
    newTitleLabel = "নতুন শিরোনাম",
    open = "খুলুন",
    edit = "সম্পাদনা",
    copy = "কপি",
    exportLabel = "রপ্তানি (Export)",
    delete = "মুছুন",
    cancel = "বাতিল",
    save = "সংরক্ষণ",
    confirm = "নিশ্চিত করুন",
    retry = "আবার চেষ্টা করুন",

    resultTitle = "ট্রান্সক্রিপশন ফলাফল",
    fileNameLabel = "অডিও ফাইল",
    durationLabel = "সময়কাল",
    detectedLanguageLabel = "শনাক্তকৃত ভাষা",
    statusLabel = "অবস্থা",
    statusCompleted = "সম্পন্ন",
    modeLabel = "মোড",
    switchToExact = "হুবহু (Exact) দেখুন",
    switchToClean = "পরিমার্জিত (Clean) দেখুন",
    generatingCleanMode = "পরিমার্জিত সংস্করণ তৈরি হচ্ছে...",
    exactPreservedNote = "মূল হুবহু (Exact) প্রতিলিপি সর্বদা নিরাপদে সংরক্ষিত থাকে।",
    charactersLabel = "অক্ষর",
    wordsLabel = "শব্দ",
    searchInTranscript = "লেখার মধ্যে খুঁজুন...",
    matchesFound = { count -> "${count}টি মিল পাওয়া গেছে" },

    copyAllText = "সব কপি করুন",
    selectAndCopy = "বেছে কপি",
    editTranscript = "সম্পাদনা",
    saveTranscript = "সংরক্ষণ",
    downloadTxt = "TXT",
    exportPdf = "PDF",
    exportDocx = "DOCX",
    exportSrt = "SRT",
    shareText = "শেয়ার",
    deleteTranscript = "মুছুন",

    copiedToClipboard = "ক্লিপবোর্ডে কপি করা হয়েছে",
    transcriptSaved = "ট্রান্সক্রিপ্ট সংরক্ষিত হয়েছে",
    exportSuccess = "ফাইল সফলভাবে সংরক্ষিত হয়েছে",
    deleteTranscriptConfirmTitle = "ট্রান্সক্রিপ্ট মুছে ফেলবেন?",
    deleteTranscriptConfirmMsg = "এই ট্রান্সক্রিপ্টটি স্থায়ীভাবে মুছে ফেলা হবে।",
    selectAndCopyInstruction = "যেকোনো অংশ চেপে ধরে নির্বাচন করুন এবং কপি করুন:",
    close = "বন্ধ করুন",
    unsavedTranscriptBanner = "স্বয়ংক্রিয় সংরক্ষণ বন্ধ আছে — রাখতে 'সংরক্ষণ' চাপুন।",

    originalAudioPlayback = "মূল অডিও প্লেব্যাক",
    deleteAudioOnly = "শুধু অডিও মুছুন",
    deleteAudioConfirmMsg = "মূল অডিও ফাইলটি ডিভাইস থেকে মুছে ফেলা হবে, তবে লেখা সংরক্ষিত থাকবে।",
    noSavedAudioTitle = "কোনো সংরক্ষিত অডিও নেই",
    noSavedAudioDesc = "অডিও সংরক্ষণ চালু রেখে ট্রান্সক্রাইব করলে ফাইল এখানে দেখা যাবে।",
    totalStorageUsed = "মোট ব্যবহৃত স্টোরেজ",
    audioDeletedMsg = "অডিও ফাইল মুছে ফেলা হয়েছে",

    appLanguageSetting = "অ্যাপের ভাষা",
    bengaliLabel = "বাংলা (ডিফল্ট)",
    englishLabel = "English",
    defaultModeSetting = "ডিফল্ট রূপান্তর মোড",
    defaultAudioLangSetting = "ডিফল্ট অডিও ভাষা",
    themeSetting = "থিম (Theme)",
    themeLight = "হালকা (Light)",
    themeDark = "গাঢ় (Dark)",
    themeSystem = "সিস্টেম",
    autoSaveAudioSetting = "স্বয়ংক্রিয়ভাবে মূল অডিও সংরক্ষণ",
    autoSaveTranscriptSetting = "স্বয়ংক্রিয়ভাবে ট্রান্সক্রিপ্ট সংরক্ষণ",
    autoStartOnSelectSetting = "অডিও দিলেই স্বয়ংক্রিয় রূপান্তর শুরু",
    transcriptionEngineSetting = "ট্রান্সক্রিপশন ইঞ্জিন",
    engineFirebaseGemini = "বিল্ট-ইন AI (Firebase Gemini)",
    engineFirebaseGeminiDesc = "কোনো API কী ছাড়াই বাংলা, ইংরেজি ও হিন্দিতে সরাসরি রূপান্তর ও টাইমস্ট্যাম্প",
    engineCustomBackend = "নিজস্ব ব্যাকএন্ড (OpenAI gpt-4o-transcribe)",
    engineCustomBackendDesc = "আপনার নিরাপদ সার্ভারের মাধ্যমে OpenAI Audio API ব্যবহার করুন",
    backendUrlLabel = "ব্যাকএন্ড সার্ভার URL",
    backendTokenLabel = "অ্যাক্সেস টোকেন (ঐচ্ছিক)",
    apiConnectionStatus = "API সংযোগের অবস্থা",
    checkConnection = "সংযোগ পরীক্ষা করুন",
    connectionOk = "সংযোগ সক্রিয়",
    connectionFailed = "সংযোগ ব্যর্থ",
    checkingConnection = "সংযোগ যাচাই করা হচ্ছে...",
    deleteAllAudioSetting = "সংরক্ষিত সব অডিও মুছুন",
    deleteAllAudioConfirmMsg = "ডিভাইসে সংরক্ষিত সমস্ত অডিও ফাইল মুছে ফেলা হবে (ট্রান্সক্রিপ্ট লেখা থাকবে)।",
    aboutAppTitle = "AudioText Pro সম্পর্কে",
    aboutAppDesc = "ব্যক্তিগত ব্যবহারের জন্য নির্মিত নির্ভরযোগ্য বাংলা, ইংরেজি ও হিন্দি অডিও-টু-টেক্সট রূপান্তর অ্যাপ।",
    privacyTitle = "গোপনীয়তা ও ডেটা নিয়ন্ত্রণ",
    privacyDesc = "আপনার সমস্ত ট্রান্সক্রিপ্ট ইতিহাস ও অডিও শুধুমাত্র আপনার ডিভাইসেই সংরক্ষিত থাকে। অডিও শুধুমাত্র রূপান্তরের জন্য নির্বাচিত ট্রান্সক্রিপশন প্রোভাইডারে পাঠানো হয় এবং কোনো পাবলিক অ্যাকাউন্ট বা ক্লাউড ইতিহাস সংরক্ষণ করা হয় না।",
    shareAsFile = "ফাইল হিসেবে শেয়ার করুন",
    saveToDevice = "ডিভাইসে সংরক্ষণ করুন",
    chooseExportAction = "রপ্তানির মাধ্যম নির্বাচন করুন",

    retryFailedChunks = "ব্যর্থ অংশ আবার চেষ্টা করুন",
    chunksCompletedFormat = { completed, total -> "$completed / $total অংশ সম্পন্ন" },
    uploadPercentFormat = { pct -> "আপলোড: $pct%" },
    failedChunksFormat = { indices -> "ব্যর্থ অংশ: #${indices.joinToString(", #")}" },
    etaSecondsFormat = { secs ->
        val mins = secs / 60L
        val rem = secs % 60L
        if (mins > 0) "আনুমানিক বাকি: ${mins}মি ${rem}সে" else "আনুমানিক বাকি: ${rem}সে"
    }
)

private val EnglishStrings = AppStrings(
    isBengali = false,
    appName = "AudioText Pro",
    appSubtitle = "Convert audio into accurate, readable text.",
    settings = "Settings",
    history = "History",
    home = "Home",
    audioManager = "Saved Audio",

    uploadAudioTitle = "Upload Audio",
    uploadAudioDesc = "Select MP3, M4A, WAV, MP4, or OGG from device storage",
    selectAudioFile = "Select File",
    replaceFile = "Replace",
    removeFile = "Remove",
    supportedFormatsHint = "Supported: MP3, M4A, WAV, MP4, MPEG, OGG, AAC",

    recordAudioTitle = "Record Audio",
    recordAudioDesc = "Record directly with pause, resume, and live waveform",
    startRecording = "Start Recording",
    pauseRecording = "Pause",
    resumeRecording = "Resume",
    stopAndSave = "Stop & Save",
    discardRecording = "Discard",
    recordingLive = "Recording...",
    recordingPaused = "Paused",
    micPermissionNeeded = "Microphone permission is needed to record voice",
    grantMicPermission = "Grant Permission",

    audioLanguageLabel = "Audio Language",
    langAuto = "Auto Detect",
    langBengali = "বাংলা (Bengali)",
    langEnglish = "English",
    langHindi = "हिन्दी (Hindi)",
    transcriptionModeLabel = "Transcription Mode",
    modeExact = "Exact",
    modeExactHint = "Preserves spoken words, repetitions, fillers, and original language",
    modeClean = "Clean",
    modeCleanHint = "Improves punctuation & paragraphs without changing meaning",
    keepOriginalAudioLabel = "Keep original audio after transcription",
    startTranscription = "Transcribe Audio",
    cancelTranscription = "Cancel",

    recentTranscriptions = "Recent Transcriptions",
    viewAllHistory = "View All History",
    noHistoryTitle = "No transcripts yet",
    noHistoryDesc = "Upload an audio file or record your voice above to create your first transcript.",
    searchHistoryPlaceholder = "Search filename or transcript...",
    clearAllHistory = "Clear All History",
    clearAllHistoryConfirmTitle = "Clear all history?",
    clearAllHistoryConfirmMsg = "All saved transcripts will be permanently deleted. This cannot be undone.",
    renameTranscript = "Rename Transcript",
    newTitleLabel = "New Title",
    open = "Open",
    edit = "Edit",
    copy = "Copy",
    exportLabel = "Export",
    delete = "Delete",
    cancel = "Cancel",
    save = "Save",
    confirm = "Confirm",
    retry = "Retry",

    resultTitle = "Transcription Result",
    fileNameLabel = "Audio File",
    durationLabel = "Duration",
    detectedLanguageLabel = "Detected Language",
    statusLabel = "Status",
    statusCompleted = "Completed",
    modeLabel = "Mode",
    switchToExact = "Exact Mode",
    switchToClean = "Clean Mode",
    generatingCleanMode = "Generating Clean version...",
    exactPreservedNote = "Original Exact transcript is always preserved separately.",
    charactersLabel = "Chars",
    wordsLabel = "Words",
    searchInTranscript = "Search in transcript...",
    matchesFound = { count -> "$count matches found" },

    copyAllText = "Copy All",
    selectAndCopy = "Select & Copy",
    editTranscript = "Edit",
    saveTranscript = "Save",
    downloadTxt = "TXT",
    exportPdf = "PDF",
    exportDocx = "DOCX",
    exportSrt = "SRT",
    shareText = "Share",
    deleteTranscript = "Delete",

    copiedToClipboard = "Copied to clipboard",
    transcriptSaved = "Transcript saved",
    exportSuccess = "File exported successfully",
    deleteTranscriptConfirmTitle = "Delete transcript?",
    deleteTranscriptConfirmMsg = "This transcript will be permanently removed from your device.",
    selectAndCopyInstruction = "Long-press any text below to select and copy:",
    close = "Close",
    unsavedTranscriptBanner = "Auto-save is OFF — tap Save to keep this transcript in history.",

    originalAudioPlayback = "Original Audio Playback",
    deleteAudioOnly = "Delete Audio",
    deleteAudioConfirmMsg = "The audio file will be deleted from storage while keeping the text transcript.",
    noSavedAudioTitle = "No saved audio files",
    noSavedAudioDesc = "Retained recordings and uploaded audio copies will appear here.",
    totalStorageUsed = "Total Storage Used",
    audioDeletedMsg = "Audio file deleted",

    appLanguageSetting = "App Language",
    bengaliLabel = "বাংলা (Default)",
    englishLabel = "English",
    defaultModeSetting = "Default Transcription Mode",
    defaultAudioLangSetting = "Default Audio Language",
    themeSetting = "Theme",
    themeLight = "Light",
    themeDark = "Dark",
    themeSystem = "System",
    autoSaveAudioSetting = "Save original audio automatically",
    autoSaveTranscriptSetting = "Save transcripts automatically",
    autoStartOnSelectSetting = "Auto-transcribe when audio is added",
    transcriptionEngineSetting = "Transcription Engine",
    engineFirebaseGemini = "Built-in AI (Firebase Gemini)",
    engineFirebaseGeminiDesc = "Zero-key Bengali, English & Hindi transcription with timestamps",
    engineCustomBackend = "Custom Backend (OpenAI gpt-4o-transcribe)",
    engineCustomBackendDesc = "Connect to your own secure Node.js server with OpenAI API",
    backendUrlLabel = "Backend Server URL",
    backendTokenLabel = "Access Token (Optional)",
    apiConnectionStatus = "API Connection Status",
    checkConnection = "Check Connection",
    connectionOk = "Connected",
    connectionFailed = "Connection Failed",
    checkingConnection = "Checking connection...",
    deleteAllAudioSetting = "Delete All Stored Audio",
    deleteAllAudioConfirmMsg = "All saved audio files will be deleted from device storage (text transcripts remain).",
    aboutAppTitle = "About AudioText Pro",
    aboutAppDesc = "Personal Bengali, English, and Hindi audio-to-text workstation built for faithful transcription and document export.",
    privacyTitle = "Privacy & Data Control",
    privacyDesc = "All transcription history and saved audio stay locally on your device. Audio is sent only to your chosen transcription provider for processing with no public accounts or cloud history storage.",
    shareAsFile = "Share as File",
    saveToDevice = "Save to Device",
    chooseExportAction = "Choose Export Action",

    retryFailedChunks = "Retry Failed Chunks",
    chunksCompletedFormat = { completed, total -> "$completed / $total chunks completed" },
    uploadPercentFormat = { pct -> "Upload: $pct%" },
    failedChunksFormat = { indices -> "Failed chunks: #${indices.joinToString(", #")}" },
    etaSecondsFormat = { secs ->
        val mins = secs / 60L
        val rem = secs % 60L
        if (mins > 0) "Est. remaining: ${mins}m ${rem}s" else "Est. remaining: ${rem}s"
    }
)
