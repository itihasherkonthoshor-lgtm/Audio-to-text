# AudioText Pro — Personal Bengali, English & Hindi Audio Transcription App

**AudioText Pro** is a privacy-focused Android application (Kotlin + Jetpack Compose + Material 3) with a companion Node.js/Express OpenAI proxy backend for converting uploaded or recorded audio into accurate, readable text in **Bengali (বাংলা)**, **English**, and **Hindi (हिन्दी)**.

---

## Key Features

1. **Two Transcription Modes (Non-Destructive)**
   - **Exact Mode (হুবহু)**: Preserves spoken words, repetitions, fillers, incomplete sentences, and mixed-language code-switching (e.g., Bengali + English) without translating, rewriting, or summarizing. Marks unintelligible audio as `[অস্পষ্ট]` / `[unclear]` instead of fabricating text.
   - **Clean Mode (পরিমার্জিত)**: Improves punctuation, organizes paragraphs, and removes only unnecessary stuttering while preserving facts and meaning. **Original Exact text is always preserved separately**—switching modes never overwrites Exact text.
2. **Audio Upload & Live Microphone Recording**
   - Storage Access Framework (`OpenDocument`) for **MP3, M4A, WAV, MP4, MPEG, OGG, AAC, FLAC, WEBM** (zero storage permissions required).
   - Direct microphone recording via `MediaRecorder` with **Pause**, **Resume**, **Stop & Save**, **Discard**, live duration timer, and real-time audio waveform visualization.
3. **Dual Transcription Engine Architecture**
   - **Built-in AI (Firebase AI Logic — Gemini 3.8 Flash)**: Works out-of-the-box with zero API keys in the APK, protected by Firebase App Check, producing accurate Bengali, Hindi, English, mixed-speech transcripts and real millisecond timestamps for SRT subtitles.
   - **Custom Backend (OpenAI `gpt-4o-transcribe`)**: Connect to the included `/backend` Node.js server so your `OPENAI_API_KEY` stays strictly on your server—never inside the Android APK.
4. **Large Audio File Support**
   - `AudioChunker` splits oversized files into ordered segments (lossless `MediaExtractor`/`MediaMuxer` for AAC/MP4/M4A, or 16kHz mono WAV with low-energy pause splitting), forwards previous chunk context, deduplicates boundary overlaps, and merges chronological segments + timestamps.
5. **Local Room Database History & Media3 Playback**
   - Searchable local transcript history, rename, edit, delete, clear all, and optional audio retention with **Jetpack Media3 ExoPlayer** playback and storage manager.
6. **Multi-Format Export System**
   - **TXT** (UTF-8)
   - **PDF** (Multi-page `PdfDocument` with bundled `Noto Sans Bengali`, `Noto Sans Devanagari`, and `Plus Jakarta Sans` fonts for crisp Unicode rendering)
   - **DOCX** (Standard OOXML `.docx` ZIP package with UTF-8 Bengali/Hindi/English paragraphs)
   - **SRT** (SubRip subtitles generated only when real timestamps exist; never fabricated)

---

## Project Structure

- `app/` — Android Jetpack Compose application (`com.example`)
  - `data/local/` — Room (`AppDatabase`, `TranscriptEntity`, `TranscriptDao`, `TranscriptRepository`) & DataStore (`SettingsRepository`)
  - `data/audio/` — `AudioRecorderManager`, `AudioPlayerController` (Media3), `AudioFileHelper`, `AudioChunker`
  - `data/remote/` — `FirebaseAiTranscriptionService`, `BackendTranscriptionService`, `TranscriptionCoordinator`
  - `data/export/` — `DocumentExporter` (TXT, PDF, DOCX, SRT)
  - `i18n/` — `AppStrings` (Bengali default + English)
  - `ui/screens/` — `HomeScreen`, `ResultScreen`, `HistoryScreen`, `AudioManagerScreen`, `SettingsScreen`
- `backend/` — Secure Node.js/Express backend proxy for OpenAI `/v1/audio/transcriptions`

---

## Backend Setup (OpenAI `gpt-4o-transcribe`)

1. Navigate to `/backend` and install dependencies:
   ```bash
   cd backend
   npm install
   ```
2. Create `.env` from `.env.example`:
   ```bash
   cp .env.example .env
   ```
3. Set your credentials in `backend/.env`:
   ```env
   OPENAI_API_KEY=sk-your-real-openai-key
   OPENAI_TRANSCRIBE_MODEL=gpt-4o-transcribe
   API_ACCESS_TOKEN=your-optional-personal-bearer-token
   PORT=3000
   ```
4. Run tests and start the server:
   ```bash
   npm test
   npm start
   ```
5. In the **AudioText Pro** Android app:
   - Open **Settings** -> **Transcription Engine** -> Select **Custom Backend (OpenAI gpt-4o-transcribe)**.
   - Enter your server URL (e.g., `http://10.0.2.2:3000` on Android Emulator or `https://your-domain.com` on a physical device) and optional access token, then tap **Check Connection**.

### Note on Bengali Transcription Quality
OpenAI `gpt-4o-transcribe` and `whisper-1` support Bengali (`bn`), Hindi (`hi`), and English (`en`), while `gemini-3.8-flash` (built-in Firebase AI Logic) provides strong Bengali + English code-switching fidelity and accurate segment timestamps. You can switch between both engines anytime in **Settings**.

---

## Building the Android APK

1. Open the project in Android Studio or run Gradle from the project root:
   ```bash
   gradle :app:assembleDebug
   ```
2. Run unit & Robolectric tests:
   ```bash
   gradle :app:testDebugUnitTest
   ```
3. Output APK location: `app/build/outputs/apk/debug/app-debug.apk`.

---

## Security & Privacy
- **No OpenAI API keys are embedded in the Android APK.**
- **History and audio stay locally on-device** using Room Database and app-private storage (`files/audio/`).
- The backend deletes temporary uploaded audio files immediately in a `finally` block and never logs private transcript content.
