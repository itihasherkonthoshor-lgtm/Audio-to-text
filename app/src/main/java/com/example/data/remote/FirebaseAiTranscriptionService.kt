package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.data.audio.AudioChunk
import com.example.data.audio.AudioFileException
import com.example.domain.AppError
import com.example.domain.AppErrorType
import com.example.domain.AudioLanguage
import com.example.domain.TranscriptChunkResult
import com.example.domain.TranscriptSegment
import com.example.domain.TranscriptionMode
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val TAG = "FirebaseAiTranscribe"
private const val STREAM_INACTIVITY_TIMEOUT_MS = 90_000L
private const val STREAM_HARD_CEILING_MS = 300_000L

class FirebaseAiTranscriptionService(private val context: Context) {

    private val transcriptionSchema = Schema.obj(
        properties = mapOf(
            "detectedLanguage" to Schema.string(
                description = "Primary detected language code: 'bn' for Bengali, 'en' for English, 'hi' for Hindi, 'bn-en' for Bengali-English mixed, 'hi-en' for Hindi-English mixed"
            ),
            "exactTranscript" to Schema.string(
                description = "Verbatim exact transcription preserving all spoken words, repetitions, fillers, incomplete sentences, and original script(s) without translating or rewriting."
            ),
            "cleanTranscript" to Schema.string(
                description = "Readable clean transcription with proper punctuation, paragraph breaks, and without unnecessary stuttering repetitions while keeping all facts and original language."
            ),
            "segments" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "startMs" to Schema.integer(description = "Segment start time in milliseconds from start of this audio clip"),
                        "endMs" to Schema.integer(description = "Segment end time in milliseconds from start of this audio clip"),
                        "text" to Schema.string(description = "Exact spoken text for this time segment")
                    )
                ),
                description = "Chronological subtitle segments with accurate start and end timestamps in milliseconds."
            )
        ),
        optionalProperties = listOf("cleanTranscript")
    )

    private val cleanOnlySchema = Schema.obj(
        properties = mapOf(
            "cleanTranscript" to Schema.string(
                description = "Cleaned, punctuated, paragraphed transcript preserving exact meaning, facts, and original script/language."
            )
        )
    )

    private val transcribeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.0f
                responseMimeType = "application/json"
                responseSchema = transcriptionSchema
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.LOW
                }
            }
        )
    }

    private val transcribeModelDefaultThinking by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.0f
                responseMimeType = "application/json"
                responseSchema = transcriptionSchema
            }
        )
    }

    private val cleanModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
                responseSchema = cleanOnlySchema
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.LOW
                }
            }
        )
    }

    private val cleanModelDefaultThinking by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
                responseSchema = cleanOnlySchema
            }
        )
    }

    private val pingModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.0f
                maxOutputTokens = 8
            }
        )
    }

    suspend fun prewarmAppCheck() {
        runCatching {
            withTimeout(15_000L) {
                awaitAppCheckToken()
            }
        }
    }

    suspend fun transcribeChunk(
        chunk: AudioChunk,
        languageHint: AudioLanguage,
        mode: TranscriptionMode,
        previousContext: String
    ): TranscriptChunkResult = withContext(Dispatchers.IO) {
        val bytes = chunk.file.readBytes()
        if (bytes.isEmpty()) {
            throw AudioFileException(AppError(AppErrorType.EMPTY_AUDIO))
        }
        // Keep single inline payload safely below Firebase AI 20 MB limit
        if (bytes.size > 18 * 1024 * 1024) {
            throw AudioFileException(AppError(AppErrorType.FILE_TOO_LARGE, "Chunk ${chunk.index + 1} exceeds 18 MB"))
        }

        val tokenStart = System.currentTimeMillis()
        val tokenResult = try {
            withTimeout(20_000L) { awaitAppCheckToken() }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "App Check attestation timed out after 20s for chunk=${chunk.index}", e)
            throw AudioFileException(
                AppError(AppErrorType.API_AUTH_FAILURE, "App Check timeout (20s)")
            )
        }
        tokenResult.onFailure { err ->
            Log.e(TAG, "App Check token retrieval failed for chunk=${chunk.index}", err)
            throw AudioFileException(
                AppError(AppErrorType.API_AUTH_FAILURE, err.localizedMessage ?: "App Check failed")
            )
        }
        Log.i(
            TAG,
            "Starting chunk=${chunk.index} sizeBytes=${bytes.size} mime=${chunk.mimeType} " +
                "durationMs=${chunk.durationMs} appCheckMs=${System.currentTimeMillis() - tokenStart}"
        )

        val prompt = buildTranscriptionPrompt(languageHint, mode, previousContext)
        val accumulated = StringBuilder()

        val requestContent = content {
            inlineData(bytes, chunk.mimeType)
            text(prompt)
        }

        val startedAt = System.currentTimeMillis()
        try {
            try {
                collectStreamWithActivityTimeout(
                    flow = transcribeModel.generateContentStream(requestContent),
                    inactivityTimeoutMs = STREAM_INACTIVITY_TIMEOUT_MS,
                    totalCapMs = STREAM_HARD_CEILING_MS,
                    onToken = { accumulated.append(it) }
                )
            } catch (e: Exception) {
                if (e is CancellationException && e !is StreamStallException) throw e
                if (e.localizedMessage.orEmpty().contains("thinking", ignoreCase = true)) {
                    Log.w(TAG, "ThinkingLevel.LOW rejected for chunk=${chunk.index}, retrying with default thinking")
                    accumulated.clear()
                    collectStreamWithActivityTimeout(
                        flow = transcribeModelDefaultThinking.generateContentStream(requestContent),
                        inactivityTimeoutMs = STREAM_INACTIVITY_TIMEOUT_MS,
                        totalCapMs = STREAM_HARD_CEILING_MS,
                        onToken = { accumulated.append(it) }
                    )
                } else {
                    throw e
                }
            }

            val jsonStr = accumulated.toString().takeIf { it.isNotBlank() }
                ?: throw AudioFileException(AppError(AppErrorType.TRANSCRIPTION_FAILED, "Empty model response"))

            Log.i(
                TAG,
                "Completed chunk=${chunk.index} in ${System.currentTimeMillis() - startedAt}ms chars=${jsonStr.length}"
            )
            parseTranscriptionJson(jsonStr, languageHint)
        } catch (e: StreamStallException) {
            Log.e(
                TAG,
                "Stream stalled for chunk=${chunk.index} after ${e.stalledMs}ms (emittedChars=${accumulated.length})",
                e
            )
            throw AudioFileException(
                AppError(
                    AppErrorType.SERVER_UNAVAILABLE,
                    "Stream stalled at chunk ${chunk.index + 1} (${accumulated.length} chars)"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioFileException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI transcription failed chunk=${chunk.index}", e)
            throw mapFirebaseException(e)
        }
    }

    suspend fun generateCleanFromExact(
        exactText: String,
        languageCode: String
    ): String = withContext(Dispatchers.IO) {
        if (exactText.isBlank()) return@withContext ""
        val prompt = """
            You are converting an EXACT verbatim transcript into a CLEAN, readable transcript.
            Language hint: $languageCode

            STRICT RULES:
            - Improve punctuation and organize into natural paragraphs.
            - Remove only unnecessary filler stuttering or accidental word repetitions where appropriate.
            - Preserve ALL original facts, names, English/mixed loanwords, and the speaker's intended meaning.
            - Do NOT translate, do NOT summarize, do NOT add any information not present in the original text.
            - Keep Bengali in Bengali script, Hindi in Devanagari script, and English in Latin script.

            EXACT TRANSCRIPT:
            $exactText
        """.trimIndent()

        val accumulated = StringBuilder()
        try {
            try {
                collectStreamWithActivityTimeout(
                    flow = cleanModel.generateContentStream(prompt),
                    inactivityTimeoutMs = STREAM_INACTIVITY_TIMEOUT_MS,
                    totalCapMs = STREAM_HARD_CEILING_MS,
                    onToken = { accumulated.append(it) }
                )
            } catch (e: Exception) {
                if (e is CancellationException && e !is StreamStallException) throw e
                if (e.localizedMessage.orEmpty().contains("thinking", ignoreCase = true)) {
                    Log.w(TAG, "ThinkingLevel.LOW rejected for clean pass, retrying with default")
                    accumulated.clear()
                    collectStreamWithActivityTimeout(
                        flow = cleanModelDefaultThinking.generateContentStream(prompt),
                        inactivityTimeoutMs = STREAM_INACTIVITY_TIMEOUT_MS,
                        totalCapMs = STREAM_HARD_CEILING_MS,
                        onToken = { accumulated.append(it) }
                    )
                } else {
                    throw e
                }
            }
            val jsonStr = accumulated.toString().takeIf { it.isNotBlank() }
                ?: throw AudioFileException(AppError(AppErrorType.TRANSCRIPTION_FAILED))
            val json = JSONObject(jsonStr)
            json.optString("cleanTranscript", exactText).ifBlank { exactText }
        } catch (e: StreamStallException) {
            Log.e(TAG, "Clean generation stream stalled", e)
            throw AudioFileException(AppError(AppErrorType.SERVER_UNAVAILABLE, "Clean stream stalled"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioFileException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI clean generation failed", e)
            throw mapFirebaseException(e)
        }
    }

    /**
     * Activity-based stream collector: resets the inactivity deadline whenever any chunk arrives
     * from the server, preventing false timeouts on slow-but-active multi-sentence audio streams.
     */
    private suspend fun collectStreamWithActivityTimeout(
        flow: Flow<GenerateContentResponse>,
        inactivityTimeoutMs: Long,
        totalCapMs: Long,
        onToken: (String) -> Unit
    ) = coroutineScope {
        val lastActivityAt = AtomicLong(System.currentTimeMillis())
        val startedAt = System.currentTimeMillis()
        var collectorJob: Job? = null

        val watchdog = launch {
            while (true) {
                delay(1_000L)
                val now = System.currentTimeMillis()
                val idleMs = now - lastActivityAt.get()
                val totalMs = now - startedAt
                if (idleMs >= inactivityTimeoutMs || totalMs >= totalCapMs) {
                    collectorJob?.cancel(StreamStallException(idleMs))
                    break
                }
            }
        }

        collectorJob = launch {
            flow.collect { part ->
                lastActivityAt.set(System.currentTimeMillis())
                part.text?.let(onToken)
            }
        }

        try {
            collectorJob.join()
        } finally {
            watchdog.cancel()
        }
    }

    suspend fun checkReadiness(): Result<String> {
        return try {
            val apps = FirebaseApp.getApps(context)
            if (apps.isEmpty()) {
                return Result.failure(IllegalStateException("FirebaseApp not initialized"))
            }
            val projectId = FirebaseApp.getInstance().options.projectId ?: "configured"
            val tokenRes = withTimeout(15_000L) { awaitAppCheckToken() }
            if (tokenRes.isFailure) {
                return Result.failure(
                    tokenRes.exceptionOrNull() ?: IllegalStateException("App Check failed")
                )
            }
            withTimeout(20_000L) {
                pingModel.generateContent("Reply with OK")
            }
            Result.success("Firebase AI Logic ($projectId)")
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Connection timed out"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun awaitAppCheckToken(): Result<String> {
        return suspendCancellableCoroutine { cont ->
            FirebaseAppCheck.getInstance().getAppCheckToken(false)
                .addOnSuccessListener { token ->
                    if (cont.isActive) {
                        if (token.token.isNotBlank()) {
                            cont.resume(Result.success(token.token))
                        } else {
                            cont.resume(Result.failure(IllegalStateException("Empty App Check token")))
                        }
                    }
                }
                .addOnFailureListener { err ->
                    if (cont.isActive) {
                        cont.resume(Result.failure(err))
                    }
                }
        }
    }

    private fun buildTranscriptionPrompt(
        languageHint: AudioLanguage,
        mode: TranscriptionMode,
        previousContext: String
    ): String {
        val langInstruction = when (languageHint) {
            AudioLanguage.AUTO ->
                "Automatically detect the spoken language(s) (Bengali, English, Hindi, or mixed code-switched speech such as Bengali+English). Do NOT translate."
            AudioLanguage.BENGALI ->
                "The primary spoken language is Bengali (বাংলা). Transcribe Bengali words in Bengali script. If the speaker uses English words mid-sentence, preserve those English words in Latin script or natural spoken form—do NOT translate."
            AudioLanguage.ENGLISH ->
                "The primary spoken language is English. Transcribe in English (Latin script)."
            AudioLanguage.HINDI ->
                "The primary spoken language is Hindi (हिन्दी). Transcribe Hindi words in Devanagari script. Preserve any spoken English words as spoken—do NOT translate."
        }

        val contextBlock = if (previousContext.isNotBlank()) {
            "\nPrevious chunk ending context (for continuity only, do NOT repeat it unless spoken again in this clip):\n\"$previousContext\"\n"
        } else {
            ""
        }

        val cleanInstruction = if (mode == TranscriptionMode.CLEAN) {
            "Also populate 'cleanTranscript' with improved punctuation, paragraph breaks, and without unnecessary stuttering repetitions while keeping 100% of the speaker's meaning and facts."
        } else {
            "Leave 'cleanTranscript' empty or omitted to keep response fast; only 'exactTranscript' is required."
        }

        return """
            Transcribe the attached audio faithfully and accurately.

            LANGUAGE INSTRUCTIONS:
            $langInstruction
            $contextBlock
            EXACT TRANSCRIPTION RULES (HIGHEST PRIORITY for 'exactTranscript'):
            - Preserve EVERY spoken word faithfully in the original language and script.
            - Preserve repeated words, incomplete sentences, colloquial phrasing, and filler words (e.g., "আসলে", "মানে", "uh", "um", "मतलब") where spoken.
            - Do NOT translate.
            - Do NOT summarize.
            - Do NOT rewrite colloquial speech into formal/bookish language.
            - Do NOT silently correct the speaker's grammar.
            - Do NOT invent or guess unheard speech. If a word or phrase is genuinely unintelligible, insert [অস্পষ্ট] (for Bengali/Hindi) or [unclear] (for English).

            CLEAN TRANSCRIPTION RULES ('cleanTranscript'):
            $cleanInstruction

            TIMESTAMPS ('segments'):
            Provide concise chronological sentence-level segments with startMs and endMs (in milliseconds relative to this audio clip) covering the spoken audio.
        """.trimIndent()
    }

    private fun parseTranscriptionJson(jsonStr: String, hint: AudioLanguage): TranscriptChunkResult {
        val json = JSONObject(jsonStr)
        val detected = json.optString("detectedLanguage", hint.code).ifBlank { hint.code }
        val exact = json.optString("exactTranscript", "").trim()
        val clean = json.optString("cleanTranscript", "").trim().takeIf { it.isNotBlank() }

        val segments = mutableListOf<TranscriptSegment>()
        val segArray = json.optJSONArray("segments")
        if (segArray != null) {
            for (i in 0 until segArray.length()) {
                val obj = segArray.optJSONObject(i) ?: continue
                val start = obj.optLong("startMs", -1L)
                val end = obj.optLong("endMs", -1L)
                val text = obj.optString("text", "").trim()
                if (start >= 0 && end > start && text.isNotBlank()) {
                    segments.add(
                        TranscriptSegment(
                            index = segments.size + 1,
                            startMs = start,
                            endMs = end,
                            text = text
                        )
                    )
                }
            }
        }

        if (exact.isBlank()) {
            throw AudioFileException(AppError(AppErrorType.TRANSCRIPTION_FAILED, "No speech detected in audio"))
        }

        return TranscriptChunkResult(
            exactText = exact,
            cleanText = clean,
            detectedLanguage = detected,
            segments = segments
        )
    }

    private fun mapFirebaseException(e: Exception): AudioFileException {
        val msg = e.localizedMessage.orEmpty()
        val lower = msg.lowercase()
        val type = when {
            e is IOException || lower.contains("network") || lower.contains("unable to resolve host") ->
                AppErrorType.NO_INTERNET
            lower.contains("403") || lower.contains("app check") || lower.contains("unauthorized") || lower.contains("permission") ->
                AppErrorType.API_AUTH_FAILURE
            lower.contains("429") || lower.contains("quota") || lower.contains("resource_exhausted") ->
                AppErrorType.API_RATE_LIMIT
            lower.contains("413") || lower.contains("too large") ->
                AppErrorType.FILE_TOO_LARGE
            lower.contains("503") || lower.contains("unavailable") || lower.contains("timeout") ->
                AppErrorType.SERVER_UNAVAILABLE
            else -> AppErrorType.TRANSCRIPTION_FAILED
        }
        return AudioFileException(AppError(type, msg.take(140)))
    }
}

private class StreamStallException(val stalledMs: Long) :
    CancellationException("Stream stalled after ${stalledMs}ms")
