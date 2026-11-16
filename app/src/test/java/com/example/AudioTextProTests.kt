package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.audio.AudioChunk
import com.example.data.audio.AudioChunker
import com.example.data.audio.AudioFileException
import com.example.data.audio.AudioFileHelper
import com.example.data.export.DocumentExporter
import com.example.data.local.AppDatabase
import com.example.data.local.TranscriptEntity
import com.example.data.local.TranscriptRepository
import com.example.data.local.TranscriptionChunkEntity
import com.example.data.local.TranscriptionJobEntity
import com.example.data.remote.BackendTranscriptionService
import com.example.data.remote.FirebaseAiTranscriptionService
import com.example.data.remote.TranscriptionCoordinator
import com.example.data.work.TranscriptionJobManager
import com.example.domain.AppError
import com.example.domain.AppErrorType
import com.example.domain.AppLanguage
import com.example.domain.AppSettings
import com.example.domain.AudioLanguage
import com.example.domain.ChunkStatus
import com.example.domain.JobStatus
import com.example.domain.SelectedAudioInfo
import com.example.domain.TranscriptChunkResult
import com.example.domain.TranscriptSegment
import com.example.domain.TranscriptionEngine
import com.example.domain.TranscriptionMode
import com.example.i18n.stringsFor
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import android.net.Uri

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioTextProTests {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: TranscriptRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TranscriptRepository(context, db.transcriptDao())

        // Ensure Robolectric reports an active internet connection for network-gated flows
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet = cm.activeNetwork
        if (activeNet != null) {
            val caps = ShadowNetworkCapabilities.newInstance()
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(cm).setNetworkCapabilities(activeNet, caps)
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun exactAndCleanModesRemainSeparateInRoom() = runBlocking {
        val bengaliExact = "আমি আসলে মানে আজকে একটা নতুন ভিডিও তৈরি করব।"
        val id = repo.insert(
            TranscriptEntity(
                title = "Bengali Voice Note",
                originalFileName = "note.m4a",
                audioFilePath = null,
                audioSizeBytes = 24000,
                audioDurationMs = 5500,
                requestedLanguage = "bn",
                detectedLanguage = "bn",
                activeMode = TranscriptionMode.EXACT.id,
                exactText = bengaliExact,
                cleanText = null,
                engineUsed = "firebase_gemini"
            )
        )

        val cleanVersion = "আমি আজ একটি নতুন ভিডিও তৈরি করব।"
        repo.setActiveMode(id, TranscriptionMode.CLEAN, cleanVersion)

        val stored = repo.getById(id)
        assertNotNull(stored)
        assertEquals(bengaliExact, stored!!.exactText)
        assertEquals(cleanVersion, stored.cleanText)
        assertEquals(TranscriptionMode.CLEAN.id, stored.activeMode)

        repo.setActiveMode(id, TranscriptionMode.EXACT)
        val switchedBack = repo.getById(id)!!
        assertEquals(bengaliExact, switchedBack.textForMode(TranscriptionMode.EXACT))
    }

    @Test
    fun searchAndRenameAndDeleteWorkAcrossMultilingualContent() = runBlocking {
        val idBn = repo.insert(
            TranscriptEntity(
                title = "সাক্ষাৎকার",
                originalFileName = "interview_bn.mp3",
                audioFilePath = null,
                audioSizeBytes = 1024,
                audioDurationMs = 3000,
                requestedLanguage = "bn",
                detectedLanguage = "bn",
                exactText = "বাংলাদেশ এবং English mixed speech test",
                engineUsed = "firebase_gemini"
            )
        )
        repo.insert(
            TranscriptEntity(
                title = "Hindi Meeting",
                originalFileName = "meeting_hi.wav",
                audioFilePath = null,
                audioSizeBytes = 2048,
                audioDurationMs = 4000,
                requestedLanguage = "hi",
                detectedLanguage = "hi",
                exactText = "नमस्ते आज की मीटिंग शुरू करते हैं।",
                engineUsed = "firebase_gemini"
            )
        )

        val bnResults = repo.searchSaved("বাংলাদেশ").first()
        assertEquals(1, bnResults.size)
        assertEquals(idBn, bnResults.first().id)

        val hiResults = repo.searchSaved("मीटिंग").first()
        assertEquals(1, hiResults.size)

        repo.rename(idBn, "নতুন শিরোনাম")
        assertEquals("নতুন শিরোনাম", repo.getById(idBn)?.title)

        repo.deleteTranscriptAndAudio(idBn)
        val remaining = repo.allSaved.first()
        assertEquals(1, remaining.size)
    }

    @Test
    fun chunkMergingDeduplicatesOverlapAndShiftsTimestamps() {
        val c1 = AudioChunk(0, File("chunk0.m4a"), "audio/mp4", startOffsetMs = 0L, durationMs = 60_000L, overlapBeforeMs = 0L, isTemporary = true)
        val c2 = AudioChunk(1, File("chunk1.m4a"), "audio/mp4", startOffsetMs = 59_000L, durationMs = 60_000L, overlapBeforeMs = 1_000L, isTemporary = true)

        val r1 = TranscriptChunkResult(
            exactText = "আমি আজকে একটা নতুন ভিডিও",
            cleanText = null,
            detectedLanguage = "bn",
            segments = listOf(TranscriptSegment(1, 500L, 4500L, "আমি আজকে একটা নতুন ভিডিও"))
        )
        val r2 = TranscriptChunkResult(
            exactText = "নতুন ভিডিও তৈরি করতে যাচ্ছি",
            cleanText = null,
            detectedLanguage = "bn",
            segments = listOf(
                TranscriptSegment(1, 0L, 500L, "নতুন ভিডিও"), // inside overlap guard (mid=250 < 800) -> dropped
                TranscriptSegment(2, 1000L, 5000L, "নতুন ভিডিও তৈরি করতে যাচ্ছি")
            )
        )

        val merged = AudioChunker.mergeChunkResults(listOf(c2 to r2, c1 to r1))
        assertEquals("আমি আজকে একটা নতুন ভিডিও তৈরি করতে যাচ্ছি", merged.exactText)
        assertEquals(2, merged.segments.size)
        assertEquals(60_000L, merged.segments[1].startMs)
        assertEquals(64_000L, merged.segments[1].endMs)
    }

    @Test
    fun multiHourChunkMergingSupportsTwoPlusHoursAndMixedScripts() {
        val c1 = AudioChunk(0, File("c0.wav"), "audio/wav", startOffsetMs = 0L, durationMs = 3_600_000L, overlapBeforeMs = 0L, isTemporary = true)
        val c2 = AudioChunk(1, File("c1.wav"), "audio/wav", startOffsetMs = 3_599_000L, durationMs = 3_600_000L, overlapBeforeMs = 1_000L, isTemporary = true)
        val c3 = AudioChunk(2, File("c2.wav"), "audio/wav", startOffsetMs = 7_198_000L, durationMs = 1_800_000L, overlapBeforeMs = 1_000L, isTemporary = true)

        val r1 = TranscriptChunkResult("প্রথম ঘণ্টার বাংলা ও English lecture শেষ।", null, "bn-en", listOf(TranscriptSegment(1, 100L, 5000L, "প্রথম অংশ")))
        val r2 = TranscriptChunkResult("English lecture শেষ। দ্বিতীয় ঘণ্টার हिन्दी चर्चा शुरू होती है", null, "hi", listOf(TranscriptSegment(1, 1200L, 6000L, "দ্বিতীয় অংশ")))
        val r3 = TranscriptChunkResult("चर्चा शुरू होती है और final summary तैयार है।", null, "hi-en", listOf(TranscriptSegment(1, 1500L, 4500L, "তৃতীয় অংশ")))

        val merged = AudioChunker.mergeChunkResults(listOf(c3 to r3, c1 to r1, c2 to r2))
        assertEquals(
            "প্রথম ঘণ্টার বাংলা ও English lecture শেষ। দ্বিতীয় ঘণ্টার हिन्दी चर्चा शुरू होती है और final summary तैयार है।",
            merged.exactText
        )
        assertEquals(3, merged.segments.size)
        assertEquals(7_199_500L, merged.segments[2].startMs)
    }

    @Test
    fun streamingPcmChunkWriterSplitsOnSustainedSilenceAndOverlapWindowWithSha256() {
        val outDir = File(context.filesDir, "test_pcm_chunks_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // Configure 20-second max chunks (~640 KB), 1000ms overlap
            val writer = AudioChunker.StreamingPcmChunkWriter(
                chunkDir = outDir,
                maxChunkBytes = 640_000L,
                maxChunkDurationMs = 20_000L,
                overlapMs = 1_000L,
                estimatedTotal = 3,
                onChunkProgress = { _, _ -> }
            )

            // 13 seconds active speech (~2000 RMS amplitude) + 0.5 seconds silence
            val activeSpeech13s = generateSinePcm16k(seconds = 13, amplitude = 2500)
            val silenceHalfSec = ByteArray(16000) // 0.5s zeros -> should trigger sustained silence split!
            // Followed by 25 seconds continuous speech without silence -> should trigger hard limit WITH 1s overlap!
            val continuousSpeech25s = generateSinePcm16k(seconds = 25, amplitude = 3000)

            writer.feed(activeSpeech13s)
            writer.feed(silenceHalfSec)
            writer.feed(continuousSpeech25s)
            val chunks = writer.finish()

            assertTrue("Expected at least 3 chunks, got ${chunks.size}", chunks.size >= 3)
            assertEquals(0L, chunks[0].startOffsetMs)
            assertEquals(0L, chunks[0].overlapBeforeMs)
            // Chunk 1 split on sustained silence -> no overlap needed
            assertEquals(0L, chunks[1].overlapBeforeMs)
            // Chunk 2 split on continuous speech hard ceiling -> carries 1000ms overlap window!
            assertEquals(1_000L, chunks[2].overlapBeforeMs)
            assertTrue(chunks[2].startOffsetMs < chunks[1].startOffsetMs + chunks[1].durationMs)

            chunks.forEach { c ->
                assertTrue(c.file.exists())
                assertTrue(c.file.length() <= 640_000L)
                assertEquals(64, c.sha256.length)
                assertEquals(AudioFileHelper.computeSha256Hex(c.file), c.sha256)
            }
        } finally {
            outDir.deleteRecursively()
        }
    }

    @Test
    fun analyzeAudioDetectsWavHeaderMetadataAccurately() {
        val wavFile = File(context.cacheDir, "meta_test_${System.currentTimeMillis()}.wav")
        try {
            val pcm5s = generateSinePcm16k(seconds = 5, amplitude = 2000)
            AudioChunker.writeWavFile(wavFile, pcm5s, 16000)
            val meta = AudioFileHelper.analyzeAudio(wavFile.absolutePath, "audio/wav")
            assertEquals(16000, meta.sampleRate)
            assertEquals(1, meta.channels)
            assertEquals(5000L, meta.durationMs)
        } finally {
            wavFile.delete()
        }
    }

    @Test
    fun roomJobDaoAndJobManagerTrackPartialFailureAndRetryOnlyFailedChunks() = runBlocking {
        val jobDao = db.jobDao()
        val jobManager = TranscriptionJobManager(context, jobDao, BackendTranscriptionService())
        val jobId = "job_isolate_test"

        jobDao.insertJob(
            TranscriptionJobEntity(
                jobId = jobId,
                sourceFilePath = "/tmp/fake.wav",
                fileName = "lecture.wav",
                mimeType = "audio/wav",
                sizeBytes = 10_000_000L,
                durationMs = 480_000L,
                languageCode = "bn",
                modeId = "exact",
                engineId = TranscriptionEngine.CUSTOM_BACKEND_OPENAI.id,
                keepOriginalAudio = true,
                autoSaveTranscript = true,
                status = JobStatus.PARTIAL_FAILED.name,
                stageName = "PARTIAL_FAILED",
                totalChunks = 8,
                completedChunks = 7,
                failedChunks = 1,
                currentChunkIndex = 6,
                errorType = AppErrorType.TRANSCRIPTION_FAILED.name,
                errorDetail = "Chunk 7 failed"
            )
        )

        val chunks = (0 until 8).map { idx ->
            TranscriptionChunkEntity(
                jobId = jobId,
                chunkIndex = idx,
                filePath = "/tmp/chunk_$idx.wav",
                mimeType = "audio/wav",
                sizeBytes = 128_000L,
                sha256 = "abc$idx",
                startOffsetMs = idx * 60_000L,
                durationMs = 60_000L,
                overlapBeforeMs = 1_000L,
                isTemporary = true,
                status = if (idx == 6) ChunkStatus.FAILED.name else ChunkStatus.COMPLETED.name,
                exactText = if (idx == 6) null else "অংশ ${idx + 1}",
                errorDetail = if (idx == 6) "HTTP 502" else null
            )
        }
        jobDao.insertChunks(chunks)

        val progress = jobManager.observeLatestProgress().first()
        assertEquals(7, progress.completedChunks)
        assertEquals(1, progress.failedChunks)
        assertEquals(listOf(6), progress.failedChunkIndices)
        assertTrue(progress.canRetryFailedChunks)

        // Trigger selective retry -> ONLY chunk 6 becomes QUEUED, chunks 0..5 & 7 remain COMPLETED!
        jobManager.retryFailedChunks(jobId)
        val afterRetryChunks = jobDao.getChunksForJob(jobId)
        for (i in 0 until 8) {
            val expected = if (i == 6) ChunkStatus.QUEUED.name else ChunkStatus.COMPLETED.name
            assertEquals(expected, afterRetryChunks[i].status)
        }
    }

    @Test
    fun workManagerEnqueueAndRestartResumePreserveActiveJob() = runBlocking {
        val jobDao = db.jobDao()
        val jobManager = TranscriptionJobManager(context, jobDao, BackendTranscriptionService())
        val wavFile = File(context.cacheDir, "wm_src_${System.currentTimeMillis()}.wav")
        AudioChunker.writeWavFile(wavFile, generateSinePcm16k(2, 1500), 16000)

        try {
            val info = SelectedAudioInfo(
                sourceUri = Uri.fromFile(wavFile),
                localTempPath = wavFile.absolutePath,
                displayName = "wm_note.wav",
                mimeType = "audio/wav",
                sizeBytes = wavFile.length(),
                durationMs = 2000L,
                isRecorded = false,
                sampleRate = 16000,
                channels = 1
            )
            val jobId = jobManager.enqueueNewJob(
                audio = info,
                audioLanguage = AudioLanguage.BENGALI,
                mode = TranscriptionMode.EXACT,
                keepOriginalAudio = false,
                settings = AppSettings()
            )

            // Durable copy must exist in filesDir/pending_jobs/<jobId>
            val storedJob = jobDao.getJob(jobId)!!
            assertTrue(File(storedJob.sourceFilePath).exists())
            assertTrue(storedJob.sourceFilePath.contains("pending_jobs"))
            assertEquals(64, storedJob.fileSha256.length)

            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("audiotext_job_$jobId")
                .get()
            assertEquals(1, infos.size)
            assertTrue(infos[0].state == WorkInfo.State.ENQUEUED || infos[0].state == WorkInfo.State.RUNNING)

            // Simulate app restart resume check
            val resumedId = jobManager.resumeActiveJobIfNeeded()
            assertEquals(jobId, resumedId)
        } finally {
            wavFile.delete()
            AudioFileHelper.cleanupJobFiles(context, "")
        }
    }

    @Test
    fun coordinatorExecutesAsyncBackendJobEndToEndAndCleansUpTempFiles() = runBlocking {
        val pollCount = AtomicInteger(0)
        var uploadedSha: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/jobs") { ex ->
            val path = ex.requestURI.path
            when {
                ex.requestMethod == "POST" && path == "/api/jobs" -> {
                    val resp = JSONObject().put("jobId", "srv_job_1").put("status", "awaiting_upload").toString().toByteArray()
                    ex.sendResponseHeaders(201, resp.size.toLong())
                    ex.responseBody.use { it.write(resp) }
                }
                ex.requestMethod == "GET" && path.endsWith("/upload-status") -> {
                    val resp = JSONObject().put("jobId", "srv_job_1").put("uploadedChunkIndices", JSONArray()).toString().toByteArray()
                    ex.sendResponseHeaders(200, resp.size.toLong())
                    ex.responseBody.use { it.write(resp) }
                }
                ex.requestMethod == "PUT" && path.contains("/chunks/") -> {
                    uploadedSha = ex.requestHeaders.getFirst("X-Chunk-Sha256")
                    ex.requestBody.readBytes()
                    val resp = JSONObject().put("status", "UPLOADED").toString().toByteArray()
                    ex.sendResponseHeaders(200, resp.size.toLong())
                    ex.responseBody.use { it.write(resp) }
                }
                ex.requestMethod == "POST" && path.endsWith("/enqueue") -> {
                    val resp = JSONObject().put("jobId", "srv_job_1").put("status", "queued").toString().toByteArray()
                    ex.sendResponseHeaders(202, resp.size.toLong())
                    ex.responseBody.use { it.write(resp) }
                }
                ex.requestMethod == "GET" && path == "/api/jobs/srv_job_1" -> {
                    val n = pollCount.incrementAndGet()
                    val isDone = n >= 2
                    val payload = JSONObject().apply {
                        put("jobId", "srv_job_1")
                        put("status", if (isDone) "completed" else "processing")
                        put("totalChunks", 1)
                        put("completedChunks", if (isDone) 1 else 0)
                        put("failedChunks", 0)
                        put("currentChunkIndex", 0)
                        put("chunks", JSONArray().put(JSONObject().put("index", 0).put("status", if (isDone) "COMPLETED" else "PROCESSING")))
                        put("detectedLanguage", "bn")
                        if (isDone) {
                            put("exactTranscript", "সার্ভার জব কিউ থেকে সফলভাবে রূপান্তরিত বাংলা টেক্সট।")
                            put("segments", JSONArray().put(JSONObject().put("startMs", 0).put("endMs", 2000).put("text", "সার্ভার জব কিউ থেকে সফলভাবে রূপান্তরিত বাংলা টেক্সট।")))
                        }
                    }.toString().toByteArray()
                    ex.sendResponseHeaders(200, payload.size.toLong())
                    ex.responseBody.use { it.write(payload) }
                }
                ex.requestMethod == "DELETE" && path == "/api/jobs/srv_job_1" -> {
                    val resp = "{}".toByteArray()
                    ex.sendResponseHeaders(200, resp.size.toLong())
                    ex.responseBody.use { it.write(resp) }
                }
                else -> {
                    ex.sendResponseHeaders(404, 0)
                    ex.responseBody.close()
                }
            }
        }
        server.start()

        val sourceFile = File(context.filesDir, "pending_jobs/job_e2e_1/source.wav").apply {
            parentFile?.mkdirs()
        }
        AudioChunker.writeWavFile(sourceFile, generateSinePcm16k(2, 1800), 16000)
        val expectedSha = AudioFileHelper.computeSha256Hex(sourceFile)

        try {
            val jobDao = db.jobDao()
            jobDao.insertJob(
                TranscriptionJobEntity(
                    jobId = "job_e2e_1",
                    sourceFilePath = sourceFile.absolutePath,
                    fileName = "sample_bn.wav",
                    mimeType = "audio/wav",
                    sizeBytes = sourceFile.length(),
                    durationMs = 2000L,
                    fileSha256 = expectedSha,
                    languageCode = "bn",
                    modeId = "exact",
                    engineId = TranscriptionEngine.CUSTOM_BACKEND_OPENAI.id,
                    keepOriginalAudio = false,
                    autoSaveTranscript = true,
                    status = JobStatus.QUEUED.name,
                    stageName = "VALIDATING"
                )
            )

            val coordinator = TranscriptionCoordinator(
                context = context,
                jobDao = jobDao,
                transcriptRepo = repo,
                firebaseService = FirebaseAiTranscriptionService(context),
                backendService = BackendTranscriptionService()
            )
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            coordinator.executeDurableJob(
                jobId = "job_e2e_1",
                settings = AppSettings(
                    engine = TranscriptionEngine.CUSTOM_BACKEND_OPENAI,
                    backendBaseUrl = baseUrl
                )
            )

            assertEquals(expectedSha, uploadedSha)
            assertTrue(pollCount.get() >= 2)
            val completedJob = jobDao.getJob("job_e2e_1")!!
            assertEquals(JobStatus.COMPLETED.name, completedJob.status)
            assertNotNull(completedJob.resultTranscriptId)
            val savedTranscript = repo.getById(completedJob.resultTranscriptId!!)!!
            assertEquals("সার্ভার জব কিউ থেকে সফলভাবে রূপান্তরিত বাংলা টেক্সট।", savedTranscript.exactText)
            assertFalse("Temp source file should be deleted after job completion", sourceFile.exists())
        } finally {
            server.stop(0)
            sourceFile.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun documentExporterGeneratesValidDocxTxtAndSrt() {
        val entity = TranscriptEntity(
            id = 1L,
            title = "বাংলা ও हिन्दी Test",
            originalFileName = "sample.mp3",
            audioFilePath = null,
            audioSizeBytes = 5000,
            audioDurationMs = 8000,
            requestedLanguage = "auto",
            detectedLanguage = "bn-en",
            exactText = "আমি আসলে মানে আজকে একটি test করছি।\nनमस्ते दुनिया।",
            engineUsed = "firebase_gemini"
        )

        val txtOut = ByteArrayOutputStream()
        DocumentExporter.writeTxt(txtOut, entity.exactText)
        assertEquals(entity.exactText, txtOut.toString("UTF-8"))

        val docxOut = ByteArrayOutputStream()
        DocumentExporter.writeDocx(docxOut, entity, TranscriptionMode.EXACT, entity.exactText)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(docxOut.toByteArray())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                entry = zis.nextEntry
            }
        }
        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("_rels/.rels"))
        val docXml = entries["word/document.xml"] ?: ""
        assertTrue(docXml.contains("আমি আসলে মানে আজকে একটি test করছি।"))
        assertTrue(docXml.contains("नमस्ते दुनिया।"))

        val srt = DocumentExporter.buildSrtContent(
            listOf(TranscriptSegment(1, 0L, 3250L, "আমি বাংলায় কথা বলছি"))
        )
        assertTrue(srt.contains("00:00:00,000 --> 00:00:03,250"))
        assertTrue(srt.contains("আমি বাংলায় কথা বলছি"))

        var threwNoTimestamps = false
        try {
            DocumentExporter.buildSrtContent(emptyList())
        } catch (e: AudioFileException) {
            threwNoTimestamps = e.appError.type == AppErrorType.NO_TIMESTAMPS_FOR_SRT
        }
        assertTrue(threwNoTimestamps)
    }

    @Test
    fun bengaliLocalizationProvidesHelpfulErrorMessagesAndRetryFailedStrings() {
        val bn = stringsFor(AppLanguage.BENGALI)
        assertEquals("অডিও থেকে নির্ভুল লেখায় রূপান্তর করুন।", bn.appSubtitle)
        assertTrue(bn.errorMessage(AppError(AppErrorType.NO_INTERNET)).contains("ইন্টারনেট"))
        assertTrue(bn.errorMessage(AppError(AppErrorType.NO_TIMESTAMPS_FOR_SRT)).contains("SRT"))
        assertTrue(bn.chunksCompletedFormat(6, 8).contains("6 / 8"))
        assertTrue(bn.failedChunksFormat(listOf(7)).contains("#7"))
    }

    @Test
    fun defaultsUseBengaliAndAutoStartWithSafeChunkLimits() {
        val defaults = AppSettings()
        assertEquals(AudioLanguage.BENGALI, defaults.defaultAudioLanguage)
        assertTrue(defaults.autoStartOnAudioSelect)
        assertEquals(1L * 1024L * 1024L, AudioChunker.DEFAULT_MAX_CHUNK_BYTES)
        assertEquals(30_000L, AudioChunker.DEFAULT_MAX_CHUNK_DURATION_MS)
    }

    private fun generateSinePcm16k(seconds: Int, amplitude: Int): ByteArray {
        val totalSamples = seconds * 16000
        val bb = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) {
            val sample = (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000.0) * amplitude).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bb.putShort(sample.toShort())
        }
        return bb.array()
    }
}
