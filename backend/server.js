import 'dotenv/config';
import express from 'express';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import multer from 'multer';
import fs from 'node:fs/promises';
import { createWriteStream, openAsBlob } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import {
  CHUNK_STATES,
  JOB_STATES,
  PersistentJobStore,
  mergeChunkTranscripts
} from './jobStore.js';

const execFileAsync = promisify(execFile);

const ALLOWED_EXTENSIONS = new Set([
  '.mp3', '.m4a', '.wav', '.mp4', '.mpeg', '.mpga', '.ogg', '.oga', '.aac', '.webm', '.flac', '.3gp'
]);

const MAX_PROVIDER_CHUNK_BYTES = 24 * 1024 * 1024; // Safe ceiling under OpenAI 25 MB limit
const DEFAULT_CHUNK_MAX_BYTES = 8 * 1024 * 1024;

const upload = multer({
  dest: path.join(os.tmpdir(), 'audiotext-uploads'),
  limits: { fileSize: MAX_PROVIDER_CHUNK_BYTES },
  fileFilter: (_req, file, cb) => {
    const ext = path.extname(file.originalname || '').toLowerCase();
    const isAudioMime = (file.mimetype || '').startsWith('audio/') ||
      file.mimetype === 'video/mp4' ||
      file.mimetype === 'video/webm' ||
      file.mimetype === 'application/ogg';
    if (isAudioMime || ALLOWED_EXTENSIONS.has(ext)) {
      cb(null, true);
    } else {
      cb(new Error('UNSUPPORTED_FORMAT'));
    }
  }
});

export function buildExactPrompt(language, previousContext) {
  const langRule = language === 'bn'
    ? 'Audio is primarily Bengali (বাংলা). Transcribe faithfully in Bengali script. Keep spoken English loanwords as spoken; do not translate.'
    : language === 'hi'
      ? 'Audio is primarily Hindi (हिन्दी). Transcribe faithfully in Devanagari script. Keep spoken English words as spoken; do not translate.'
      : language === 'en'
        ? 'Audio is English. Transcribe verbatim.'
        : 'Preserve spoken Bengali (Bengali script), Hindi (Devanagari script), English (Latin script), or mixed speech without translating.';

  const verbatimRule = 'Preserve every spoken word, repeated words, incomplete sentences, and filler words verbatim. Do not rewrite or summarize.';
  return previousContext
    ? `${langRule} ${verbatimRule} Previous context: ${previousContext.slice(-200)}`
    : `${langRule} ${verbatimRule}`;
}

export function parseRetryAfterMs(retryAfterHeader) {
  if (!retryAfterHeader) return 0;
  const trimmed = String(retryAfterHeader).trim();
  const seconds = Number(trimmed);
  if (Number.isFinite(seconds) && seconds > 0) {
    return Math.min(seconds * 1000, 120_000);
  }
  const dateMs = Date.parse(trimmed);
  if (!Number.isNaN(dateMs)) {
    return Math.max(0, Math.min(dateMs - Date.now(), 120_000));
  }
  return 0;
}

function extFromMime(mime, fallback = 'wav') {
  const m = String(mime || '').toLowerCase();
  if (m.includes('wav')) return 'wav';
  if (m.includes('mp4') || m.includes('m4a') || m.includes('aac')) return 'm4a';
  if (m.includes('mpeg') || m.includes('mp3')) return 'mp3';
  if (m.includes('ogg')) return 'ogg';
  if (m.includes('flac')) return 'flac';
  if (m.includes('webm')) return 'webm';
  return fallback;
}

export function createApp({
  fetchImpl = fetch,
  config = process.env,
  jobStore = new PersistentJobStore(config.JOB_DATA_DIR || path.join(os.tmpdir(), 'audiotext-jobs')),
  sleepImpl = (ms) => new Promise((r) => setTimeout(r, ms))
} = {}) {
  const app = express();
  app.use(helmet());
  app.use(express.json({ limit: '4mb' }));

  const limiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 1200, // Allow frequent lightweight status polling & chunk uploads for hours-long jobs
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'Too many requests, please wait before retrying.' }
  });
  app.use('/api/', limiter);

  // Optional personal bearer token gate
  app.use('/api/', (req, res, next) => {
    const requiredToken = (config.API_ACCESS_TOKEN || '').trim();
    if (!requiredToken) return next();
    const auth = req.headers.authorization || '';
    if (auth !== `Bearer ${requiredToken}`) {
      return res.status(401).json({ error: 'Unauthorized: invalid or missing access token.' });
    }
    return next();
  });

  const activeJobs = new Set();

  async function callOpenAiChunk({ filePath, mimeType, originalName, language, previousContext, durationMs }) {
    const apiKey = (config.OPENAI_API_KEY || '').trim();
    if (!apiKey) {
      const err = new Error('Server missing OPENAI_API_KEY.');
      err.status = 500;
      err.permanent = true;
      throw err;
    }

    const primaryModel = config.OPENAI_TRANSCRIBE_MODEL || 'gpt-4o-transcribe';
    const providerTimeoutMs = Number(config.OPENAI_TIMEOUT_MS || 90_000);
    const stat = await fs.stat(filePath);
    if (stat.size <= 0) {
      const err = new Error('Empty audio chunk.');
      err.status = 400;
      err.permanent = true;
      throw err;
    }
    if (stat.size > 25 * 1024 * 1024) {
      const err = new Error('Chunk exceeds OpenAI 25 MB single-request limit.');
      err.status = 413;
      err.permanent = true;
      throw err;
    }

    const blob = await openAsBlob(filePath, { type: mimeType || 'audio/wav' });
    const form = new FormData();
    form.append('file', blob, originalName || `chunk.${extFromMime(mimeType)}`);
    form.append('model', primaryModel);
    if (language && language !== 'auto' && ['bn', 'en', 'hi'].includes(language)) {
      form.append('language', language);
    }
    form.append('prompt', buildExactPrompt(language, previousContext));
    form.append('response_format', primaryModel === 'whisper-1' ? 'verbose_json' : 'json');
    if (primaryModel === 'whisper-1') {
      form.append('timestamp_granularities[]', 'segment');
    }

    const openaiResp = await fetchImpl('https://api.openai.com/v1/audio/transcriptions', {
      method: 'POST',
      headers: { Authorization: `Bearer ${apiKey}` },
      body: form,
      signal: AbortSignal.timeout(providerTimeoutMs)
    });

    if (!openaiResp.ok) {
      const errBody = await openaiResp.text().catch(() => '');
      const retryAfterMs = parseRetryAfterMs(openaiResp.headers?.get?.('retry-after'));
      const err = new Error(`OpenAI transcription failed (${openaiResp.status}): ${errBody.slice(0, 180)}`);
      err.status = openaiResp.status;
      err.retryAfterMs = retryAfterMs;
      err.permanent = [400, 401, 403, 413, 415, 422].includes(openaiResp.status);
      throw err;
    }

    const data = await openaiResp.json();
    const exactText = (data.text || '').trim();
    if (!exactText) {
      const err = new Error('No speech detected in chunk.');
      err.status = 422;
      err.permanent = true;
      throw err;
    }

    const segments = Array.isArray(data.segments) && data.segments.length > 0
      ? data.segments.map((s, idx) => ({
          index: idx + 1,
          startMs: Math.round((s.start || 0) * 1000),
          endMs: Math.round((s.end || 0) * 1000),
          text: String(s.text || '').trim()
        })).filter((s) => s.endMs > s.startMs && s.text)
      : (Number(durationMs) > 0
          ? [{ index: 1, startMs: 0, endMs: Number(durationMs), text: exactText }]
          : []);

    return {
      exactText,
      detectedLanguage: data.language || language || 'auto',
      segments
    };
  }

  async function processSingleChunk(jobId, chunkIndex, previousContext) {
    const maxRetries = Number(config.MAX_CHUNK_RETRIES ?? 4);
    let chunkSnapshot = await jobStore.withJobLock(jobId, async () => {
      const job = await jobStore.loadJob(jobId);
      if (!job) return null;
      const chunk = job.chunks.find((c) => c.index === chunkIndex);
      if (!chunk || chunk.status === CHUNK_STATES.COMPLETED) return null;
      chunk.status = CHUNK_STATES.PROCESSING;
      job.currentChunkIndex = chunkIndex;
      await jobStore.saveJob(job);
      return { chunk, language: job.language };
    });

    if (!chunkSnapshot) return;
    const { chunk, language } = chunkSnapshot;
    let attempt = 0;

    while (true) {
      const startedAt = Date.now();
      try {
        const res = await callOpenAiChunk({
          filePath: chunk.filePath,
          mimeType: chunk.mimeType,
          originalName: path.basename(chunk.filePath || `chunk_${chunkIndex}.wav`),
          language,
          previousContext,
          durationMs: chunk.durationMs
        });
        const elapsedMs = Date.now() - startedAt;

        await jobStore.withJobLock(jobId, async () => {
          const job = await jobStore.loadJob(jobId);
          if (!job) return;
          const target = job.chunks.find((c) => c.index === chunkIndex);
          if (!target) return;
          target.status = CHUNK_STATES.COMPLETED;
          target.exactText = res.exactText;
          target.detectedLanguage = res.detectedLanguage;
          target.segments = res.segments;
          target.error = null;
          target.permanentError = false;
          target.processingDurationMs = elapsedMs;
          job.completedChunks = job.chunks.filter((c) => c.status === CHUNK_STATES.COMPLETED).length;
          job.failedChunks = job.chunks.filter((c) => c.status === CHUNK_STATES.FAILED).length;
          await jobStore.saveJob(job);
        });

        // Remove processed temporary chunk audio from server disk
        if (chunk.filePath) {
          await fs.unlink(chunk.filePath).catch(() => {});
        }
        return res.exactText;
      } catch (err) {
        const isPermanent = Boolean(err.permanent) || attempt >= maxRetries;
        if (isPermanent) {
          await jobStore.withJobLock(jobId, async () => {
            const job = await jobStore.loadJob(jobId);
            if (!job) return;
            const target = job.chunks.find((c) => c.index === chunkIndex);
            if (!target) return;
            target.status = CHUNK_STATES.FAILED;
            target.retryCount = attempt;
            target.error = err.message || 'Chunk failed';
            target.permanentError = Boolean(err.permanent);
            job.completedChunks = job.chunks.filter((c) => c.status === CHUNK_STATES.COMPLETED).length;
            job.failedChunks = job.chunks.filter((c) => c.status === CHUNK_STATES.FAILED).length;
            await jobStore.saveJob(job);
          });
          return null;
        }

        attempt += 1;
        const baseBackoff = Math.min(45_000, 1200 * (2 ** (attempt - 1)));
        const jitter = Math.floor(Math.random() * 500);
        const waitMs = Math.max(err.retryAfterMs || 0, baseBackoff) + jitter;

        await jobStore.withJobLock(jobId, async () => {
          const job = await jobStore.loadJob(jobId);
          if (!job) return;
          const target = job.chunks.find((c) => c.index === chunkIndex);
          if (!target) return;
          target.status = CHUNK_STATES.RETRYING;
          target.retryCount = attempt;
          target.error = err.message;
          await jobStore.saveJob(job);
        });

        await sleepImpl(waitMs);
      }
    }
  }

  async function runJobWorker(jobId) {
    if (activeJobs.has(jobId)) return;
    activeJobs.add(jobId);

    try {
      const job = await jobStore.withJobLock(jobId, async () => {
        const j = await jobStore.loadJob(jobId);
        if (!j || j.status === JOB_STATES.CANCELLED || j.status === JOB_STATES.COMPLETED) return null;
        j.status = JOB_STATES.PROCESSING;
        j.error = null;
        await jobStore.saveJob(j);
        return j;
      });
      if (!job) return;

      // Process chunks sequentially or with bounded concurrency while carrying previous chunk context
      const concurrency = Math.max(1, Number(config.WORKER_CONCURRENCY || 2));
      let previousContext = '';

      if (concurrency === 1) {
        const ordered = [...job.chunks].sort((a, b) => a.index - b.index);
        for (const c of ordered) {
          const latest = await jobStore.loadJob(jobId);
          if (!latest || latest.status === JOB_STATES.CANCELLED) return;
          const latestChunk = latest.chunks.find((x) => x.index === c.index);
          if (latestChunk?.status === CHUNK_STATES.COMPLETED) {
            previousContext = (latestChunk.exactText || '').slice(-220);
            continue;
          }
          if (latestChunk?.status !== CHUNK_STATES.QUEUED) continue;
          const txt = await processSingleChunk(jobId, c.index, previousContext);
          if (txt) previousContext = txt.slice(-220);
        }
      } else {
        const ordered = [...job.chunks].sort((a, b) => a.index - b.index);
        const queue = ordered.filter((c) => c.status === CHUNK_STATES.QUEUED).map((c) => c.index);
        const workers = Array.from({ length: Math.min(concurrency, queue.length) }, async () => {
          while (queue.length > 0) {
            const idx = queue.shift();
            if (idx === undefined) break;
            const latest = await jobStore.loadJob(jobId);
            if (!latest || latest.status === JOB_STATES.CANCELLED) return;
            await processSingleChunk(jobId, idx, '');
          }
        });
        await Promise.all(workers);
      }

      // Finalize job: only mark COMPLETED if ALL chunks are COMPLETED
      const finalJob = await jobStore.loadJob(jobId);
      if (!finalJob || finalJob.status === JOB_STATES.CANCELLED) return;

      const failed = finalJob.chunks.filter((c) => c.status === CHUNK_STATES.FAILED);
      const completed = finalJob.chunks.filter((c) => c.status === CHUNK_STATES.COMPLETED);

      if (failed.length > 0 || completed.length < finalJob.chunks.length) {
        await jobStore.withJobLock(jobId, async () => {
          const j = await jobStore.loadJob(jobId);
          if (!j) return;
          j.completedChunks = j.chunks.filter((c) => c.status === CHUNK_STATES.COMPLETED).length;
          j.failedChunks = j.chunks.filter((c) => c.status === CHUNK_STATES.FAILED).length;
          j.status = j.completedChunks > 0 ? JOB_STATES.PARTIAL_FAILED : JOB_STATES.FAILED;
          j.error = `${j.failedChunks} chunk(s) failed. Retry failed chunks without restarting completed ones.`;
          await jobStore.saveJob(j);
        });
        return;
      }

      const merged = mergeChunkTranscripts(finalJob.chunks, finalJob.language);
      let cleanTranscript = null;
      if (finalJob.mode === 'clean' && merged.exactTranscript) {
        const apiKey = (config.OPENAI_API_KEY || '').trim();
        cleanTranscript = await generateCleanVersion(
          merged.exactTranscript,
          merged.detectedLanguage,
          apiKey,
          config,
          fetchImpl
        );
      }

      await jobStore.withJobLock(jobId, async () => {
        const j = await jobStore.loadJob(jobId);
        if (!j) return;
        j.exactTranscript = merged.exactTranscript;
        j.cleanTranscript = cleanTranscript;
        j.detectedLanguage = merged.detectedLanguage;
        j.segments = merged.segments;
        j.completedChunks = j.chunks.length;
        j.failedChunks = 0;
        j.status = JOB_STATES.COMPLETED;
        j.currentChunkIndex = -1;
        await jobStore.saveJob(j);
      });

      await fs.unlink(jobStore.sourceFilePath(jobId)).catch(() => {});
    } catch (err) {
      console.error(`Worker failed for job ${jobId}:`, err?.message);
      await jobStore.withJobLock(jobId, async () => {
        const j = await jobStore.loadJob(jobId);
        if (!j) return;
        j.status = JOB_STATES.FAILED;
        j.error = err?.message || 'Unexpected worker failure';
        await jobStore.saveJob(j);
      });
    } finally {
      activeJobs.delete(jobId);
    }
  }

  function scheduleJob(jobId) {
    setImmediate(() => {
      runJobWorker(jobId).catch((err) => {
        console.error('Unhandled worker schedule error:', err);
      });
    });
  }

  async function splitSourceAudioIntoChunksIfNeeded(job) {
    const sourcePath = jobStore.sourceFilePath(job.jobId);
    const stat = await fs.stat(sourcePath);
    if (stat.size <= MAX_PROVIDER_CHUNK_BYTES) {
      const ext = path.extname(job.fileName || '').replace('.', '') || extFromMime(job.mimeType);
      const chunkPath = jobStore.chunkFilePath(job.jobId, 0, ext);
      await fs.mkdir(jobStore.chunksDir(job.jobId), { recursive: true });
      await fs.copyFile(sourcePath, chunkPath);
      job.chunks = [{
        index: 0,
        status: CHUNK_STATES.QUEUED,
        filePath: chunkPath,
        mimeType: job.mimeType || 'audio/mp4',
        sizeBytes: stat.size,
        sha256: '',
        startOffsetMs: 0,
        durationMs: job.durationMs || 0,
        overlapBeforeMs: 0,
        retryCount: 0,
        exactText: null,
        cleanText: null,
        detectedLanguage: null,
        segments: [],
        error: null,
        permanentError: false,
        processingDurationMs: 0
      }];
      job.totalChunks = 1;
      return;
    }

    // Try server-side ffmpeg segmentation into 60s 16kHz mono MP3/WAV chunks (< 2 MB each)
    const outPattern = path.join(jobStore.chunksDir(job.jobId), 'chunk_%04d.mp3');
    await fs.mkdir(jobStore.chunksDir(job.jobId), { recursive: true });
    try {
      await execFileAsync('ffmpeg', [
        '-y', '-i', sourcePath,
        '-ac', '1', '-ar', '16000', '-b:a', '64k',
        '-f', 'segment', '-segment_time', '60', '-reset_timestamps', '1',
        outPattern
      ]);
      const files = (await fs.readdir(jobStore.chunksDir(job.jobId)))
        .filter((f) => f.startsWith('chunk_') && f.endsWith('.mp3'))
        .sort();
      if (files.length === 0) throw new Error('ffmpeg produced zero chunks');
      job.chunks = await Promise.all(files.map(async (f, idx) => {
        const full = path.join(jobStore.chunksDir(job.jobId), f);
        const cStat = await fs.stat(full);
        return {
          index: idx,
          status: CHUNK_STATES.QUEUED,
          filePath: full,
          mimeType: 'audio/mpeg',
          sizeBytes: cStat.size,
          sha256: '',
          startOffsetMs: idx * 60_000,
          durationMs: 60_000,
          overlapBeforeMs: 0,
          retryCount: 0,
          exactText: null,
          cleanText: null,
          detectedLanguage: null,
          segments: [],
          error: null,
          permanentError: false,
          processingDurationMs: 0
        };
      }));
      job.totalChunks = job.chunks.length;
    } catch {
      throw new Error('Server file exceeds 24 MB single-request limit and ffmpeg is unavailable; upload pre-chunked stream.');
    }
  }

  // Health Check
  app.get('/api/health', (_req, res) => {
    const hasKey = Boolean((config.OPENAI_API_KEY || '').trim());
    if (!hasKey) {
      return res.status(503).json({ status: 'misconfigured', error: 'OPENAI_API_KEY is not set on server.' });
    }
    return res.json({
      status: 'ok',
      model: config.OPENAI_TRANSCRIBE_MODEL || 'gpt-4o-transcribe',
      asyncJobsSupported: true,
      maxProviderChunkBytes: MAX_PROVIDER_CHUNK_BYTES
    });
  });

  // 1. Create / Resume Async Transcription Job (Idempotent)
  app.post('/api/jobs', async (req, res) => {
    try {
      const apiKey = (config.OPENAI_API_KEY || '').trim();
      if (!apiKey) {
        return res.status(500).json({ error: 'Server missing OPENAI_API_KEY.' });
      }
      const {
        clientRequestId,
        fileName,
        mimeType,
        totalBytes,
        durationMs,
        sampleRate,
        channels,
        fileSha256,
        language,
        mode,
        totalChunks
      } = req.body || {};

      const { job, created } = await jobStore.createOrGetJob({
        clientRequestId,
        fileName,
        mimeType,
        totalBytes,
        durationMs,
        sampleRate,
        channels,
        fileSha256,
        language,
        mode,
        totalChunks
      });

      return res.status(created ? 201 : 200).json({
        jobId: job.jobId,
        status: job.status,
        totalChunks: job.totalChunks,
        uploadedChunks: job.chunks.filter((c) => c.status === CHUNK_STATES.UPLOADED || c.status === CHUNK_STATES.COMPLETED).length,
        completedChunks: job.completedChunks,
        uploadedBytes: job.uploadedBytes,
        maxProviderChunkBytes: DEFAULT_CHUNK_MAX_BYTES
      });
    } catch (err) {
      return res.status(500).json({ error: err?.message || 'Failed to create job.' });
    }
  });

  // 2A. Streaming Chunk Upload (zero full-file RAM buffering, SHA-256 verified, idempotent)
  app.put(
    '/api/jobs/:jobId/chunks/:chunkIndex',
    async (req, res) => {
      const { jobId } = req.params;
      const chunkIndex = Number(req.params.chunkIndex);
      if (!Number.isInteger(chunkIndex) || chunkIndex < 0) {
        return res.status(400).json({ error: 'Invalid chunk index.' });
      }

      const job = await jobStore.loadJob(jobId);
      if (!job) return res.status(404).json({ error: 'Job not found.' });

      const expectedSha = String(req.headers['x-chunk-sha256'] || '').trim().toLowerCase();
      const mimeType = String(req.headers['x-chunk-mime'] || req.headers['content-type'] || 'audio/wav');
      const startOffsetMs = Number(req.headers['x-start-offset-ms'] || 0);
      const durationMs = Number(req.headers['x-duration-ms'] || 0);
      const overlapBeforeMs = Number(req.headers['x-overlap-before-ms'] || 0);

      // Idempotency shortcut: already uploaded or completed with matching hash
      const existingChunk = job.chunks.find((c) => c.index === chunkIndex);
      if (
        existingChunk &&
        (existingChunk.status === CHUNK_STATES.UPLOADED || existingChunk.status === CHUNK_STATES.COMPLETED) &&
        (!expectedSha || existingChunk.sha256 === expectedSha)
      ) {
        return res.json({
          jobId,
          chunkIndex,
          status: existingChunk.status,
          sizeBytes: existingChunk.sizeBytes,
          sha256: existingChunk.sha256,
          deduplicated: true
        });
      }

      const ext = extFromMime(mimeType);
      await fs.mkdir(jobStore.chunksDir(jobId), { recursive: true });
      const targetPath = jobStore.chunkFilePath(jobId, chunkIndex, ext);
      const tmpPath = `${targetPath}.${crypto.randomBytes(3).toString('hex')}.part`;

      try {
        const hash = crypto.createHash('sha256');
        let receivedBytes = 0;

        await new Promise((resolve, reject) => {
          const out = createWriteStream(tmpPath);
          req.on('data', (chunk) => {
            receivedBytes += chunk.length;
            if (receivedBytes > MAX_PROVIDER_CHUNK_BYTES) {
              req.destroy(new Error('CHUNK_TOO_LARGE'));
              return;
            }
            hash.update(chunk);
          });
          req.on('error', reject);
          out.on('error', reject);
          out.on('finish', resolve);
          req.pipe(out);
        });

        const actualSha = hash.digest('hex');
        if (receivedBytes <= 0) {
          await fs.unlink(tmpPath).catch(() => {});
          return res.status(400).json({ error: 'Empty chunk payload.' });
        }
        if (expectedSha && actualSha !== expectedSha) {
          await fs.unlink(tmpPath).catch(() => {});
          return res.status(400).json({ error: 'SHA256_MISMATCH', expected: expectedSha, actual: actualSha });
        }

        await fs.rename(tmpPath, targetPath);

        const updated = await jobStore.withJobLock(jobId, async () => {
          const j = await jobStore.loadJob(jobId);
          if (!j) return null;
          let target = j.chunks.find((c) => c.index === chunkIndex);
          if (!target) {
            target = {
              index: chunkIndex,
              retryCount: 0,
              exactText: null,
              cleanText: null,
              detectedLanguage: null,
              segments: [],
              error: null,
              permanentError: false,
              processingDurationMs: 0
            };
            j.chunks.push(target);
            j.chunks.sort((a, b) => a.index - b.index);
            j.totalChunks = Math.max(j.totalChunks, j.chunks.length);
          }
          target.status = CHUNK_STATES.UPLOADED;
          target.filePath = targetPath;
          target.mimeType = mimeType;
          target.sizeBytes = receivedBytes;
          target.sha256 = actualSha;
          target.startOffsetMs = startOffsetMs;
          target.durationMs = durationMs;
          target.overlapBeforeMs = overlapBeforeMs;
          target.error = null;
          j.uploadedBytes = j.chunks.reduce((acc, c) => acc + (c.sizeBytes || 0), 0);
          await jobStore.saveJob(j);
          return target;
        });

        return res.json({
          jobId,
          chunkIndex,
          status: updated.status,
          sizeBytes: updated.sizeBytes,
          sha256: updated.sha256
        });
      } catch (err) {
        await fs.unlink(tmpPath).catch(() => {});
        if (err?.message === 'CHUNK_TOO_LARGE') {
          return res.status(413).json({ error: 'Single chunk exceeds 24 MB provider safety limit.' });
        }
        return res.status(500).json({ error: err?.message || 'Chunk upload failed.' });
      }
    }
  );

  // 2B. Whole-File Resumable Byte-Range Upload (supports Content-Range streaming)
  app.put('/api/jobs/:jobId/upload', async (req, res) => {
    const { jobId } = req.params;
    const job = await jobStore.loadJob(jobId);
    if (!job) return res.status(404).json({ error: 'Job not found.' });

    const rangeHeader = String(req.headers['content-range'] || '').trim();
    let startByte = 0;
    let totalBytes = job.totalBytes || 0;
    const match = rangeHeader.match(/^bytes\s+(\d+)-(\d+)\/(\d+|\*)$/i);
    if (match) {
      startByte = Number(match[1]);
      if (match[3] !== '*') totalBytes = Number(match[3]);
    }

    const sourcePath = jobStore.sourceFilePath(jobId);
    await fs.mkdir(jobStore.jobDir(jobId), { recursive: true });
    let fh;
    try {
      fh = await fs.open(sourcePath, startByte === 0 ? 'w+' : 'r+').catch(() => fs.open(sourcePath, 'w+'));
      let offset = startByte;
      let bytesWritten = 0;
      for await (const buf of req) {
        await fh.write(buf, 0, buf.length, offset);
        offset += buf.length;
        bytesWritten += buf.length;
      }
      await fh.close();
      fh = null;

      const stat = await fs.stat(sourcePath);
      await jobStore.withJobLock(jobId, async () => {
        const j = await jobStore.loadJob(jobId);
        if (!j) return;
        j.uploadedBytes = Math.max(j.uploadedBytes, offset, stat.size);
        if (totalBytes > 0) j.totalBytes = totalBytes;
        await jobStore.saveJob(j);
      });

      return res.json({
        jobId,
        uploadedBytes: offset,
        totalBytes,
        bytesWritten,
        complete: totalBytes > 0 ? offset >= totalBytes : true
      });
    } catch (err) {
      if (fh) await fh.close().catch(() => {});
      return res.status(500).json({ error: err?.message || 'Resumable upload failed.' });
    }
  });

  app.get('/api/jobs/:jobId/upload-status', async (req, res) => {
    const job = await jobStore.loadJob(req.params.jobId);
    if (!job) return res.status(404).json({ error: 'Job not found.' });
    const uploadedChunkIndices = (job.chunks || [])
      .filter((c) => c.status === CHUNK_STATES.UPLOADED || c.status === CHUNK_STATES.COMPLETED)
      .map((c) => c.index);
    return res.json({
      jobId: job.jobId,
      uploadedBytes: job.uploadedBytes || 0,
      totalBytes: job.totalBytes || 0,
      uploadedChunkIndices
    });
  });

  // 3. Enqueue Job (Immediate 202 Accepted — never blocks on full transcription)
  app.post('/api/jobs/:jobId/enqueue', async (req, res) => {
    const { jobId } = req.params;
    try {
      const enqueuedJob = await jobStore.withJobLock(jobId, async () => {
        const job = await jobStore.loadJob(jobId);
        if (!job) return null;

        if (job.status === JOB_STATES.COMPLETED) {
          return job;
        }

        const hasPreUploadedChunks = (job.chunks || []).some(
          (c) => c.status === CHUNK_STATES.UPLOADED || c.status === CHUNK_STATES.COMPLETED
        );

        if (!hasPreUploadedChunks) {
          const sourceExists = await fs.stat(jobStore.sourceFilePath(jobId)).then(() => true).catch(() => false);
          if (!sourceExists) {
            const e = new Error('No audio uploaded for this job.');
            e.code = 'NO_AUDIO';
            throw e;
          }
          await splitSourceAudioIntoChunksIfNeeded(job);
        } else {
          for (const chunk of job.chunks) {
            if (chunk.status === CHUNK_STATES.UPLOADED || chunk.status === CHUNK_STATES.FAILED) {
              chunk.status = CHUNK_STATES.QUEUED;
              chunk.error = null;
            }
          }
          job.totalChunks = job.chunks.length;
        }

        job.status = JOB_STATES.QUEUED;
        job.completedChunks = job.chunks.filter((c) => c.status === CHUNK_STATES.COMPLETED).length;
        job.failedChunks = 0;
        await jobStore.saveJob(job);
        return job;
      });

      if (!enqueuedJob) return res.status(404).json({ error: 'Job not found.' });
      if (enqueuedJob.status !== JOB_STATES.COMPLETED) {
        scheduleJob(jobId);
      }

      return res.status(202).json({
        jobId: enqueuedJob.jobId,
        status: enqueuedJob.status,
        totalChunks: enqueuedJob.totalChunks,
        completedChunks: enqueuedJob.completedChunks
      });
    } catch (err) {
      if (err?.code === 'NO_AUDIO') {
        return res.status(400).json({ error: err.message });
      }
      return res.status(500).json({ error: err?.message || 'Failed to enqueue job.' });
    }
  });

  // 4. Lightweight Job Status Endpoint
  app.get('/api/jobs/:jobId', async (req, res) => {
    const job = await jobStore.loadJob(req.params.jobId);
    if (!job) return res.status(404).json({ error: 'Job not found.' });

    const completedWithDuration = (job.chunks || []).filter(
      (c) => c.status === CHUNK_STATES.COMPLETED && c.processingDurationMs > 0
    );
    const avgChunkMs = completedWithDuration.length > 0
      ? Math.round(
          completedWithDuration.reduce((sum, c) => sum + c.processingDurationMs, 0) /
          completedWithDuration.length
        )
      : 0;
    const remainingChunks = Math.max(0, job.totalChunks - job.completedChunks);
    const etaSeconds = avgChunkMs > 0 ? Math.ceil((remainingChunks * avgChunkMs) / 1000) : null;

    return res.json({
      jobId: job.jobId,
      status: job.status,
      fileName: job.fileName,
      totalChunks: job.totalChunks,
      completedChunks: job.completedChunks,
      failedChunks: job.failedChunks,
      currentChunkIndex: job.currentChunkIndex,
      etaSeconds,
      error: job.error,
      chunks: (job.chunks || []).map((c) => ({
        index: c.index,
        status: c.status,
        retryCount: c.retryCount,
        startOffsetMs: c.startOffsetMs,
        durationMs: c.durationMs,
        error: c.error
      })),
      detectedLanguage: job.detectedLanguage,
      exactTranscript: job.status === JOB_STATES.COMPLETED ? job.exactTranscript : null,
      cleanTranscript: job.status === JOB_STATES.COMPLETED ? job.cleanTranscript : null,
      segments: job.status === JOB_STATES.COMPLETED ? job.segments : []
    });
  });

  // 5. Retry ONLY Failed Chunks (never restarts completed chunks)
  app.post('/api/jobs/:jobId/retry', async (req, res) => {
    const { jobId } = req.params;
    const targetIndices = Array.isArray(req.body?.chunkIndices) ? new Set(req.body.chunkIndices) : null;

    const updated = await jobStore.withJobLock(jobId, async () => {
      const job = await jobStore.loadJob(jobId);
      if (!job) return null;
      let retried = 0;
      for (const chunk of job.chunks || []) {
        const shouldRetry = chunk.status === CHUNK_STATES.FAILED &&
          (!targetIndices || targetIndices.has(chunk.index));
        if (shouldRetry) {
          chunk.status = CHUNK_STATES.QUEUED;
          chunk.error = null;
          chunk.permanentError = false;
          retried++;
        }
      }
      if (retried > 0) {
        job.status = JOB_STATES.QUEUED;
        job.failedChunks = job.chunks.filter((c) => c.status === CHUNK_STATES.FAILED).length;
        job.error = null;
        await jobStore.saveJob(job);
      }
      return { job, retried };
    });

    if (!updated) return res.status(404).json({ error: 'Job not found.' });
    if (updated.retried > 0) {
      scheduleJob(jobId);
    }
    return res.status(202).json({
      jobId,
      status: updated.job.status,
      retriedChunks: updated.retried,
      completedChunks: updated.job.completedChunks,
      totalChunks: updated.job.totalChunks
    });
  });

  // 6. Delete Job & Temporary Server Audio Files
  app.delete('/api/jobs/:jobId', async (req, res) => {
    const { jobId } = req.params;
    await jobStore.withJobLock(jobId, async () => {
      const job = await jobStore.loadJob(jobId);
      if (job) {
        job.status = JOB_STATES.CANCELLED;
        await jobStore.saveJob(job);
      }
    });
    await jobStore.deleteJob(jobId);
    return res.json({ jobId, deleted: true });
  });

  // Legacy single-chunk direct endpoint (kept for quick single-chunk calls <= 24 MB)
  app.post('/api/transcribe', upload.single('audio'), async (req, res) => {
    const uploadedPath = req.file?.path;
    try {
      const apiKey = (config.OPENAI_API_KEY || '').trim();
      if (!apiKey) {
        return res.status(500).json({ error: 'Server missing OPENAI_API_KEY.' });
      }
      if (!req.file || req.file.size === 0) {
        return res.status(400).json({ error: 'Empty or missing audio file.' });
      }

      const language = (req.body?.language || 'auto').toLowerCase();
      const mode = (req.body?.mode || 'exact').toLowerCase();
      const previousContext = String(req.body?.previousContext || '');

      const chunkRes = await callOpenAiChunk({
        filePath: uploadedPath,
        mimeType: req.file.mimetype || 'audio/mp4',
        originalName: req.file.originalname || 'audio.m4a',
        language,
        previousContext,
        durationMs: 0
      });

      let cleanTranscript = null;
      if (mode === 'clean') {
        cleanTranscript = await generateCleanVersion(chunkRes.exactText, language, apiKey, config, fetchImpl);
      }

      return res.json({
        detectedLanguage: chunkRes.detectedLanguage,
        exactTranscript: chunkRes.exactText,
        cleanTranscript,
        segments: chunkRes.segments
      });
    } catch (err) {
      return res.status(err?.status || 500).json({ error: err?.message || 'Transcription processing failed.' });
    } finally {
      if (uploadedPath) {
        await fs.unlink(uploadedPath).catch(() => {});
      }
    }
  });

  app.post('/api/clean', async (req, res) => {
    try {
      const apiKey = (config.OPENAI_API_KEY || '').trim();
      if (!apiKey) {
        return res.status(500).json({ error: 'Server missing OPENAI_API_KEY.' });
      }
      const exactText = String(req.body?.exactText || '').trim();
      const language = String(req.body?.language || 'auto');
      if (!exactText) {
        return res.status(400).json({ error: 'exactText is required.' });
      }
      const cleanTranscript = await generateCleanVersion(exactText, language, apiKey, config, fetchImpl);
      return res.json({ cleanTranscript });
    } catch (err) {
      return res.status(500).json({ error: err?.message || 'Clean formatting failed.' });
    }
  });

  // Multer & validation error handler
  app.use((err, _req, res, _next) => {
    if (err?.code === 'LIMIT_FILE_SIZE') {
      return res.status(413).json({ error: 'Single request exceeds 24 MB provider limit; use /api/jobs chunked upload.' });
    }
    if (err?.message === 'UNSUPPORTED_FORMAT') {
      return res.status(415).json({ error: 'Unsupported audio file format.' });
    }
    return res.status(400).json({ error: err?.message || 'Invalid request.' });
  });

  // Expose recovery helper for crash/restart durability
  app.recoverPendingJobs = async () => {
    const ids = await jobStore.listRecoverableJobIds();
    for (const id of ids) {
      scheduleJob(id);
    }
    return ids;
  };

  return app;
}

async function generateCleanVersion(exactText, language, apiKey, config, fetchImpl) {
  const model = config.OPENAI_CLEAN_MODEL || 'gpt-4o-mini';
  const resp = await fetchImpl('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model,
      temperature: 0.1,
      messages: [
        {
          role: 'system',
          content:
            'Convert the EXACT transcript into a CLEAN readable version. Improve punctuation and paragraphs and remove only unnecessary filler stuttering. Preserve all facts, original language/script (Bengali, Devanagari, English), and speaker meaning. Do NOT translate or add new facts.'
        },
        {
          role: 'user',
          content: `Language: ${language}\n\n${exactText}`
        }
      ]
    }),
    signal: AbortSignal.timeout(60_000)
  });
  if (!resp.ok) return exactText;
  const json = await resp.json();
  return json?.choices?.[0]?.message?.content?.trim() || exactText;
}

if (process.argv[1] && process.argv[1].endsWith('server.js')) {
  const port = Number(process.env.PORT || 3000);
  const app = createApp();
  app.recoverPendingJobs()
    .then((ids) => {
      if (ids.length > 0) {
        console.log(`Recovered ${ids.length} unfinished transcription job(s) from disk.`);
      }
    })
    .catch((err) => console.error('Recovery error:', err));
  const server = app.listen(port, () => {
    console.log(`AudioText Pro backend listening on port ${port}`);
  });
  server.requestTimeout = 300_000;
  server.headersTimeout = 60_000;
}
