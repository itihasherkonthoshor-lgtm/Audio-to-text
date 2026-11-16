import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';

export const CHUNK_STATES = {
  QUEUED: 'QUEUED',
  UPLOADING: 'UPLOADING',
  UPLOADED: 'UPLOADED',
  PROCESSING: 'PROCESSING',
  COMPLETED: 'COMPLETED',
  RETRYING: 'RETRYING',
  FAILED: 'FAILED'
};

export const JOB_STATES = {
  AWAITING_UPLOAD: 'awaiting_upload',
  QUEUED: 'queued',
  PROCESSING: 'processing',
  COMPLETED: 'completed',
  PARTIAL_FAILED: 'partial_failed',
  FAILED: 'failed',
  CANCELLED: 'cancelled'
};

export class PersistentJobStore {
  constructor(baseDir = path.join(os.tmpdir(), 'audiotext-jobs')) {
    this.baseDir = baseDir;
    this.locks = new Map();
  }

  async init() {
    await fs.mkdir(this.baseDir, { recursive: true });
  }

  jobDir(jobId) {
    const safe = String(jobId).replace(/[^a-zA-Z0-9_-]/g, '');
    return path.join(this.baseDir, safe);
  }

  chunksDir(jobId) {
    return path.join(this.jobDir(jobId), 'chunks');
  }

  jobJsonPath(jobId) {
    return path.join(this.jobDir(jobId), 'job.json');
  }

  sourceFilePath(jobId) {
    return path.join(this.jobDir(jobId), 'source.audio');
  }

  chunkFilePath(jobId, chunkIndex, ext = 'wav') {
    const cleanExt = String(ext).replace(/[^a-zA-Z0-9]/g, '') || 'wav';
    return path.join(this.chunksDir(jobId), `chunk_${String(chunkIndex).padStart(4, '0')}.${cleanExt}`);
  }

  async withJobLock(jobId, fn) {
    const prev = this.locks.get(jobId) || Promise.resolve();
    let release;
    const next = new Promise((resolve) => {
      release = resolve;
    });
    this.locks.set(jobId, prev.then(() => next));
    await prev;
    try {
      return await fn();
    } finally {
      release();
      if (this.locks.get(jobId) === next) {
        this.locks.delete(jobId);
      }
    }
  }

  async createOrGetJob({
    clientRequestId = null,
    fileName,
    mimeType = 'audio/mp4',
    totalBytes = 0,
    durationMs = 0,
    sampleRate = 16000,
    channels = 1,
    fileSha256 = '',
    language = 'bn',
    mode = 'exact',
    totalChunks = 0
  }) {
    await this.init();

    if (clientRequestId) {
      const existing = await this.findByClientRequestId(clientRequestId);
      if (existing) {
        return { job: existing, created: false };
      }
    }

    const jobId = `job_${Date.now()}_${crypto.randomBytes(6).toString('hex')}`;
    await fs.mkdir(this.chunksDir(jobId), { recursive: true });

    const chunks = [];
    for (let i = 0; i < Math.max(0, Number(totalChunks) || 0); i++) {
      chunks.push({
        index: i,
        status: CHUNK_STATES.QUEUED,
        filePath: null,
        mimeType: 'audio/wav',
        sizeBytes: 0,
        sha256: '',
        startOffsetMs: 0,
        durationMs: 0,
        overlapBeforeMs: 0,
        retryCount: 0,
        exactText: null,
        cleanText: null,
        detectedLanguage: null,
        segments: [],
        error: null,
        permanentError: false,
        processingDurationMs: 0
      });
    }

    const now = Date.now();
    const job = {
      jobId,
      clientRequestId: clientRequestId || jobId,
      fileName: fileName || 'audio',
      mimeType,
      totalBytes: Number(totalBytes) || 0,
      uploadedBytes: 0,
      durationMs: Number(durationMs) || 0,
      sampleRate: Number(sampleRate) || 16000,
      channels: Number(channels) || 1,
      fileSha256: fileSha256 || '',
      language: (language || 'bn').toLowerCase(),
      mode: (mode || 'exact').toLowerCase(),
      status: JOB_STATES.AWAITING_UPLOAD,
      totalChunks: chunks.length,
      completedChunks: 0,
      failedChunks: 0,
      currentChunkIndex: -1,
      chunks,
      detectedLanguage: language || 'bn',
      exactTranscript: null,
      cleanTranscript: null,
      segments: [],
      error: null,
      createdAt: now,
      updatedAt: now
    };

    await this.saveJob(job);
    return { job, created: true };
  }

  async findByClientRequestId(clientRequestId) {
    await this.init();
    const entries = await fs.readdir(this.baseDir, { withFileTypes: true }).catch(() => []);
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      const job = await this.loadJob(entry.name);
      if (job && job.clientRequestId === clientRequestId) {
        return job;
      }
    }
    return null;
  }

  async loadJob(jobId) {
    try {
      const raw = await fs.readFile(this.jobJsonPath(jobId), 'utf8');
      return JSON.parse(raw);
    } catch {
      return null;
    }
  }

  async saveJob(job) {
    job.updatedAt = Date.now();
    const dir = this.jobDir(job.jobId);
    await fs.mkdir(dir, { recursive: true });
    const target = this.jobJsonPath(job.jobId);
    const tmp = `${target}.${crypto.randomBytes(4).toString('hex')}.tmp`;
    await fs.writeFile(tmp, JSON.stringify(job, null, 2), 'utf8');
    await fs.rename(tmp, target);
    return job;
  }

  async listRecoverableJobIds() {
    await this.init();
    const entries = await fs.readdir(this.baseDir, { withFileTypes: true }).catch(() => []);
    const recoverable = [];
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      const job = await this.loadJob(entry.name);
      if (!job) continue;
      if (job.status === JOB_STATES.QUEUED || job.status === JOB_STATES.PROCESSING) {
        let changed = false;
        for (const chunk of job.chunks || []) {
          if (chunk.status === CHUNK_STATES.PROCESSING || chunk.status === CHUNK_STATES.RETRYING) {
            chunk.status = CHUNK_STATES.QUEUED;
            changed = true;
          }
        }
        if (changed) {
          await this.saveJob(job);
        }
        recoverable.push(job.jobId);
      }
    }
    return recoverable;
  }

  async deleteJob(jobId) {
    await fs.rm(this.jobDir(jobId), { recursive: true, force: true });
  }
}

export function mergeChunkTranscripts(chunks, fallbackLang = 'bn') {
  const completed = [...(chunks || [])]
    .filter((c) => c.status === CHUNK_STATES.COMPLETED && (c.exactText || '').trim().length > 0)
    .sort((a, b) => a.index - b.index);

  if (completed.length === 0) {
    return { exactTranscript: '', detectedLanguage: fallbackLang, segments: [] };
  }

  let exactMerged = '';
  const mergedSegments = [];
  let segIndex = 1;
  const detectedLang =
    completed.map((c) => c.detectedLanguage).find((l) => l && l !== 'auto') || fallbackLang;

  for (const chunk of completed) {
    exactMerged = appendWithBoundaryDedup(exactMerged, chunk.exactText || '');
    const offsetMs = Number(chunk.startOffsetMs) || 0;
    const overlapGuardMs = Math.max(0, (Number(chunk.overlapBeforeMs) || 0) - 200);
    for (const seg of chunk.segments || []) {
      const start = Number(seg.startMs) || 0;
      const end = Number(seg.endMs) || 0;
      const text = String(seg.text || '').trim();
      if (!text || end <= start) continue;
      const midPoint = (start + end) / 2;
      if (chunk.index > 0 && overlapGuardMs > 0 && midPoint < overlapGuardMs) {
        continue;
      }
      mergedSegments.push({
        index: segIndex++,
        startMs: offsetMs + start,
        endMs: offsetMs + end,
        text
      });
    }
  }

  return {
    exactTranscript: exactMerged.trim(),
    detectedLanguage: detectedLang,
    segments: mergedSegments
  };
}

export function appendWithBoundaryDedup(existing, incoming) {
  const left = (existing || '').trimEnd();
  const right = (incoming || '').trimStart();
  if (!left) return right;
  if (!right) return left;

  const leftWords = left.split(/\s+/);
  const rightWords = right.split(/\s+/);
  const maxCheck = Math.min(12, leftWords.length, rightWords.length);
  const normalizeWord = (w) => w.replace(/^[.,?!।,:;"'()\[\]]+|[.,?!।,:;"'()\[\]]+$/g, '').toLowerCase();

  let overlapCount = 0;
  for (let k = maxCheck; k >= 1; k--) {
    const tail = leftWords.slice(-k).map(normalizeWord);
    const head = rightWords.slice(0, k).map(normalizeWord);
    if (tail.every((val, i) => val && val === head[i])) {
      overlapCount = k;
      break;
    }
  }

  if (overlapCount > 0) {
    const remainder = rightWords.slice(overlapCount).join(' ');
    return remainder ? `${left} ${remainder}` : left;
  }
  return `${left} ${right}`;
}
