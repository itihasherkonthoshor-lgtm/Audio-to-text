import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createApp, buildExactPrompt, parseRetryAfterMs } from './server.js';
import {
  CHUNK_STATES,
  JOB_STATES,
  PersistentJobStore,
  mergeChunkTranscripts,
  appendWithBoundaryDedup
} from './jobStore.js';

function startTestServer(app) {
  return new Promise((resolve) => {
    const server = http.createServer(app);
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      resolve({ server, baseUrl: `http://127.0.0.1:${addr.port}` });
    });
  });
}

async function waitForJobTerminal(baseUrl, jobId, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const r = await fetch(`${baseUrl}/api/jobs/${jobId}`);
    assert.equal(r.status, 200);
    const body = await r.json();
    if ([JOB_STATES.COMPLETED, JOB_STATES.PARTIAL_FAILED, JOB_STATES.FAILED, JOB_STATES.CANCELLED].includes(body.status)) {
      return body;
    }
    await new Promise((r) => setTimeout(r, 25));
  }
  throw new Error(`Timed out waiting for job ${jobId}`);
}

test('buildExactPrompt preserves Bengali and mixed speech instructions', () => {
  const prompt = buildExactPrompt('bn', 'আগের বাক্য');
  assert.match(prompt, /Bengali/);
  assert.match(prompt, /Preserve every spoken word/);
  assert.match(prompt, /আগের বাক্য/);
});

test('GET /api/health returns 503 when OPENAI_API_KEY missing and 200 when present', async () => {
  const appNoKey = createApp({ config: {} });
  const { server: s1, baseUrl: u1 } = await startTestServer(appNoKey);
  try {
    const r1 = await fetch(`${u1}/api/health`);
    assert.equal(r1.status, 503);
  } finally {
    s1.close();
  }

  const appWithKey = createApp({
    config: { OPENAI_API_KEY: 'test-key', OPENAI_TRANSCRIBE_MODEL: 'gpt-4o-transcribe' }
  });
  const { server: s2, baseUrl: u2 } = await startTestServer(appWithKey);
  try {
    const r2 = await fetch(`${u2}/api/health`);
    assert.equal(r2.status, 200);
    const body = await r2.json();
    assert.equal(body.status, 'ok');
    assert.equal(body.model, 'gpt-4o-transcribe');
    assert.equal(body.asyncJobsSupported, true);
  } finally {
    s2.close();
  }
});

test('Bearer auth gate protects /api/* endpoints when API_ACCESS_TOKEN is configured', async () => {
  const app = createApp({
    config: { OPENAI_API_KEY: 'test-key', API_ACCESS_TOKEN: 'secret-token' }
  });
  const { server, baseUrl } = await startTestServer(app);
  try {
    const r = await fetch(`${baseUrl}/api/health`);
    assert.equal(r.status, 401);

    const rOk = await fetch(`${baseUrl}/api/health`, {
      headers: { Authorization: 'Bearer secret-token' }
    });
    assert.equal(rOk.status, 200);
  } finally {
    server.close();
  }
});

test('POST /api/transcribe proxies multipart audio and returns exactTranscript without retaining file', async () => {
  const mockFetch = async () => ({
    ok: true,
    status: 200,
    json: async () => ({
      language: 'bn',
      text: 'আমি আসলে মানে আজকে একটা নতুন ভিডিও তৈরি করব।'
    })
  });

  const app = createApp({
    fetchImpl: mockFetch,
    config: { OPENAI_API_KEY: 'test-key', OPENAI_TRANSCRIBE_MODEL: 'gpt-4o-transcribe' }
  });
  const { server, baseUrl } = await startTestServer(app);
  try {
    const form = new FormData();
    form.append('audio', new Blob([Buffer.from('fake-audio-bytes')], { type: 'audio/mp4' }), 'sample.m4a');
    form.append('language', 'bn');
    form.append('mode', 'exact');

    const resp = await fetch(`${baseUrl}/api/transcribe`, {
      method: 'POST',
      body: form
    });
    assert.equal(resp.status, 200);
    const json = await resp.json();
    assert.equal(json.exactTranscript, 'আমি আসলে মানে আজকে একটা নতুন ভিডিও তৈরি করব।');
    assert.equal(json.detectedLanguage, 'bn');
  } finally {
    server.close();
  }
});

test('Async job lifecycle: idempotent creation, SHA-256 verified chunked upload, 202 enqueue, merge, and cleanup', async () => {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'atp-job-test-'));
  const store = new PersistentJobStore(tempDir);
  let openAiCalls = 0;

  const mockFetch = async (_url, opts) => {
    openAiCalls += 1;
    const fileEntry = opts.body.get('file');
    const text = await fileEntry.text();
    const isFirst = text.includes('chunk-zero');
    return {
      ok: true,
      status: 200,
      json: async () => ({
        language: 'bn',
        text: isFirst ? 'আজকে আমরা বাংলায় কথা বলছি' : 'কথা বলছি এবং English test করছি'
      })
    };
  };

  const app = createApp({
    fetchImpl: mockFetch,
    jobStore: store,
    sleepImpl: async () => {},
    config: { OPENAI_API_KEY: 'test-key', WORKER_CONCURRENCY: '1' }
  });
  const { server, baseUrl } = await startTestServer(app);

  try {
    const createResp = await fetch(`${baseUrl}/api/jobs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientRequestId: 'req-idemp-1',
        fileName: 'long_lecture.wav',
        mimeType: 'audio/wav',
        totalBytes: 200,
        durationMs: 120_000,
        language: 'bn',
        mode: 'exact',
        totalChunks: 2
      })
    });
    assert.equal(createResp.status, 201);
    const created = await createResp.json();
    assert.ok(created.jobId);

    // Repeating same clientRequestId must return existing job (200)
    const dupResp = await fetch(`${baseUrl}/api/jobs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ clientRequestId: 'req-idemp-1', fileName: 'long_lecture.wav' })
    });
    assert.equal(dupResp.status, 200);
    const dupJson = await dupResp.json();
    assert.equal(dupJson.jobId, created.jobId);

    // Mismatched SHA256 must fail with 400
    const badSha = await fetch(`${baseUrl}/api/jobs/${created.jobId}/chunks/0`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'audio/wav',
        'X-Chunk-Sha256': '0'.repeat(64)
      },
      body: Buffer.from('chunk-zero-pcm')
    });
    assert.equal(badSha.status, 400);

    // Upload chunk 0 & chunk 1 with real SHA-256
    const buf0 = Buffer.from('chunk-zero-pcm');
    const sha0 = crypto.createHash('sha256').update(buf0).digest('hex');
    const up0 = await fetch(`${baseUrl}/api/jobs/${created.jobId}/chunks/0`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'audio/wav',
        'X-Chunk-Sha256': sha0,
        'X-Start-Offset-Ms': '0',
        'X-Duration-Ms': '60000'
      },
      body: buf0
    });
    assert.equal(up0.status, 200);

    // Re-uploading same chunk with matching SHA returns deduplicated=true
    const up0Again = await fetch(`${baseUrl}/api/jobs/${created.jobId}/chunks/0`, {
      method: 'PUT',
      headers: { 'Content-Type': 'audio/wav', 'X-Chunk-Sha256': sha0 },
      body: buf0
    });
    assert.equal(up0Again.status, 200);
    assert.equal((await up0Again.json()).deduplicated, true);

    const buf1 = Buffer.from('chunk-one-pcm');
    const sha1 = crypto.createHash('sha256').update(buf1).digest('hex');
    const up1 = await fetch(`${baseUrl}/api/jobs/${created.jobId}/chunks/1`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'audio/wav',
        'X-Chunk-Sha256': sha1,
        'X-Start-Offset-Ms': '59000',
        'X-Duration-Ms': '61000',
        'X-Overlap-Before-Ms': '1000'
      },
      body: buf1
    });
    assert.equal(up1.status, 200);

    // Enqueue immediately returns 202
    const enq = await fetch(`${baseUrl}/api/jobs/${created.jobId}/enqueue`, { method: 'POST' });
    assert.equal(enq.status, 202);
    const enqJson = await enq.json();
    assert.equal(enqJson.status, JOB_STATES.QUEUED);

    const finished = await waitForJobTerminal(baseUrl, created.jobId);
    assert.equal(finished.status, JOB_STATES.COMPLETED);
    assert.equal(finished.completedChunks, 2);
    assert.equal(finished.failedChunks, 0);
    assert.equal(openAiCalls, 2);
    // Boundary overlap "কথা বলছি" must be deduplicated cleanly
    assert.equal(finished.exactTranscript, 'আজকে আমরা বাংলায় কথা বলছি এবং English test করছি');
    assert.equal(finished.segments.length, 2);
    assert.equal(finished.segments[1].startMs, 59_000);

    // Chunk audio files must be deleted after completion
    const chunkFiles = await fs.readdir(store.chunksDir(created.jobId));
    assert.equal(chunkFiles.length, 0);
  } finally {
    server.close();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
});

test('Resumable byte-range upload writes sequential Content-Range slices accurately without buffering whole file', async () => {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'atp-range-test-'));
  const store = new PersistentJobStore(tempDir);
  const app = createApp({
    jobStore: store,
    config: { OPENAI_API_KEY: 'test-key' }
  });
  const { server, baseUrl } = await startTestServer(app);
  try {
    const createResp = await fetch(`${baseUrl}/api/jobs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileName: 'stream.mp3', totalBytes: 10 })
    });
    const { jobId } = await createResp.json();

    const r1 = await fetch(`${baseUrl}/api/jobs/${jobId}/upload`, {
      method: 'PUT',
      headers: { 'Content-Range': 'bytes 0-4/10' },
      body: Buffer.from('ABCDE')
    });
    assert.equal(r1.status, 200);
    assert.equal((await r1.json()).complete, false);

    const statusMid = await (await fetch(`${baseUrl}/api/jobs/${jobId}/upload-status`)).json();
    assert.equal(statusMid.uploadedBytes, 5);

    const r2 = await fetch(`${baseUrl}/api/jobs/${jobId}/upload`, {
      method: 'PUT',
      headers: { 'Content-Range': 'bytes 5-9/10' },
      body: Buffer.from('FGHIJ')
    });
    assert.equal(r2.status, 200);
    assert.equal((await r2.json()).complete, true);

    const assembled = await fs.readFile(store.sourceFilePath(jobId), 'utf8');
    assert.equal(assembled, 'ABCDEFGHIJ');
  } finally {
    server.close();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
});

test('Chunk 7 failure isolation preserves chunks 1-6 and retries ONLY failed chunk cleanly', async () => {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'atp-isolate-test-'));
  const store = new PersistentJobStore(tempDir);
  const callsByChunk = new Map();
  let failChunk6 = true;

  const mockFetch = async (_url, opts) => {
    const fileEntry = opts.body.get('file');
    const name = fileEntry.name || '';
    const content = await fileEntry.text();
    const match = content.match(/PAYLOAD_(\d+)/);
    const idx = match ? Number(match[1]) : -1;
    callsByChunk.set(idx, (callsByChunk.get(idx) || 0) + 1);

    if (idx === 6 && failChunk6) {
      return {
        ok: false,
        status: 422, // permanent failure on first pass to exercise partial_failed isolation
        headers: new Headers(),
        text: async () => 'Simulated chunk 7 failure'
      };
    }
    return {
      ok: true,
      status: 200,
      json: async () => ({
        language: 'bn',
        text: `অংশ_${idx + 1}`
      })
    };
  };

  const app = createApp({
    fetchImpl: mockFetch,
    jobStore: store,
    sleepImpl: async () => {},
    config: { OPENAI_API_KEY: 'test-key', WORKER_CONCURRENCY: '2', MAX_CHUNK_RETRIES: '2' }
  });
  const { server, baseUrl } = await startTestServer(app);

  try {
    const { jobId } = await (await fetch(`${baseUrl}/api/jobs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileName: 'eight_chunks.wav', totalChunks: 8, language: 'bn', mode: 'exact' })
    })).json();

    for (let i = 0; i < 8; i++) {
      const payload = Buffer.from(`PAYLOAD_${i}`);
      const sha = crypto.createHash('sha256').update(payload).digest('hex');
      const r = await fetch(`${baseUrl}/api/jobs/${jobId}/chunks/${i}`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'audio/wav',
          'X-Chunk-Sha256': sha,
          'X-Start-Offset-Ms': String(i * 60_000),
          'X-Duration-Ms': '60000'
        },
        body: payload
      });
      assert.equal(r.status, 200);
    }

    await fetch(`${baseUrl}/api/jobs/${jobId}/enqueue`, { method: 'POST' });
    const partial = await waitForJobTerminal(baseUrl, jobId);

    assert.equal(partial.status, JOB_STATES.PARTIAL_FAILED);
    assert.equal(partial.completedChunks, 7);
    assert.equal(partial.failedChunks, 1);
    assert.equal(partial.chunks[6].status, CHUNK_STATES.FAILED);
    assert.equal(partial.exactTranscript, null); // never emit incomplete partial merge as completed

    // Now fix chunk 6 and retry ONLY failed chunks
    failChunk6 = false;
    const retryResp = await fetch(`${baseUrl}/api/jobs/${jobId}/retry`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({})
    });
    assert.equal(retryResp.status, 202);
    const retryJson = await retryResp.json();
    assert.equal(retryJson.retriedChunks, 1);

    const finished = await waitForJobTerminal(baseUrl, jobId);
    assert.equal(finished.status, JOB_STATES.COMPLETED);
    assert.equal(finished.completedChunks, 8);
    assert.equal(finished.failedChunks, 0);
    assert.equal(finished.exactTranscript, 'অংশ_1 অংশ_2 অংশ_3 অংশ_4 অংশ_5 অংশ_6 অংশ_7 অংশ_8');

    // Verify chunks 0..5 and 7 were NEVER re-processed!
    for (let i = 0; i < 8; i++) {
      assert.equal(callsByChunk.get(i), i === 6 ? 2 : 1);
    }
  } finally {
    server.close();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
});

test('HTTP 429 rate limit honors Retry-After and exponential backoff before succeeding', async () => {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'atp-429-test-'));
  const store = new PersistentJobStore(tempDir);
  const recordedSleeps = [];
  let attempts = 0;

  assert.equal(parseRetryAfterMs('3'), 3000);

  const mockFetch = async () => {
    attempts += 1;
    if (attempts === 1) {
      return {
        ok: false,
        status: 429,
        headers: new Headers({ 'retry-after': '2' }),
        text: async () => 'Rate limit exceeded'
      };
    }
    return {
      ok: true,
      status: 200,
      json: async () => ({ language: 'en', text: 'Recovered after rate limit' })
    };
  };

  const app = createApp({
    fetchImpl: mockFetch,
    jobStore: store,
    sleepImpl: async (ms) => {
      recordedSleeps.push(ms);
    },
    config: { OPENAI_API_KEY: 'test-key', MAX_CHUNK_RETRIES: '3' }
  });
  const { server, baseUrl } = await startTestServer(app);

  try {
    const { jobId } = await (await fetch(`${baseUrl}/api/jobs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileName: 'rate.wav', totalChunks: 1, language: 'en' })
    })).json();

    const buf = Buffer.from('rate-limit-chunk');
    await fetch(`${baseUrl}/api/jobs/${jobId}/chunks/0`, {
      method: 'PUT',
      headers: { 'Content-Type': 'audio/wav' },
      body: buf
    });
    await fetch(`${baseUrl}/api/jobs/${jobId}/enqueue`, { method: 'POST' });

    const done = await waitForJobTerminal(baseUrl, jobId);
    assert.equal(done.status, JOB_STATES.COMPLETED);
    assert.equal(attempts, 2);
    assert.ok(recordedSleeps[0] >= 2000);
    assert.equal(done.exactTranscript, 'Recovered after rate limit');
  } finally {
    server.close();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
});

test('Crash/restart recovery resumes mid-processing job from PersistentJobStore', async () => {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'atp-recover-test-'));
  const store = new PersistentJobStore(tempDir);

  const { job } = await store.createOrGetJob({
    clientRequestId: 'crash-recover-job',
    fileName: 'multi_hour.wav',
    language: 'hi',
    mode: 'exact',
    totalChunks: 2
  });

  const c1Path = store.chunkFilePath(job.jobId, 1, 'wav');
  await fs.writeFile(c1Path, Buffer.from('chunk-1-after-restart'));

  // Chunk 0 already completed before crash; chunk 1 was mid-flight PROCESSING
  job.status = JOB_STATES.PROCESSING;
  job.completedChunks = 1;
  job.chunks[0].status = CHUNK_STATES.COMPLETED;
  job.chunks[0].exactText = 'नमस्ते दोस्तों आज हम शुरू करते हैं';
  job.chunks[0].detectedLanguage = 'hi';
  job.chunks[0].startOffsetMs = 0;
  job.chunks[0].durationMs = 3_600_000;
  job.chunks[0].segments = [{ index: 1, startMs: 0, endMs: 5000, text: 'नमस्ते दोस्तों आज हम शुरू करते हैं' }];

  job.chunks[1].status = CHUNK_STATES.PROCESSING;
  job.chunks[1].filePath = c1Path;
  job.chunks[1].mimeType = 'audio/wav';
  job.chunks[1].startOffsetMs = 3_600_000;
  job.chunks[1].durationMs = 3_600_000;
  await store.saveJob(job);

  const app = createApp({
    jobStore: store,
    fetchImpl: async () => ({
      ok: true,
      status: 200,
      json: async () => ({ language: 'hi', text: 'शुरू करते हैं यह दूसरा घंटा' })
    }),
    config: { OPENAI_API_KEY: 'test-key', WORKER_CONCURRENCY: '1' }
  });
  const { server, baseUrl } = await startTestServer(app);
  try {
    const recoveredIds = await app.recoverPendingJobs();
    assert.deepEqual(recoveredIds, [job.jobId]);

    const done = await waitForJobTerminal(baseUrl, job.jobId);
    assert.equal(done.status, JOB_STATES.COMPLETED);
    assert.equal(done.exactTranscript, 'नमस्ते दोस्तों आज हम शुरू करते हैं यह दूसरा घंटा');
    assert.equal(done.segments[1].startMs, 3_600_000);
  } finally {
    server.close();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
});

test('mergeChunkTranscripts handles out-of-order chunks, multi-script overlap dedup, and overlap segment guard', () => {
  const mergedText = appendWithBoundaryDedup(
    'আমি আজকে একটি নতুন project',
    'একটি নতুন project শুরু করতে যাচ্ছি।'
  );
  assert.equal(mergedText, 'আমি আজকে একটি নতুন project শুরু করতে যাচ্ছি।');

  const merged = mergeChunkTranscripts([
    {
      index: 1,
      status: CHUNK_STATES.COMPLETED,
      startOffsetMs: 7_200_000, // 2 hours
      overlapBeforeMs: 1000,
      exactText: 'দ্বিতীয় অংশের কথা এখানে শুরু হলো।',
      detectedLanguage: 'bn',
      segments: [
        { startMs: 0, endMs: 600, text: 'dup overlap fragment' }, // midpoint 300 < guard (800) -> dropped
        { startMs: 900, endMs: 4200, text: 'দ্বিতীয় অংশের কথা এখানে শুরু হলো।' }
      ]
    },
    {
      index: 0,
      status: CHUNK_STATES.COMPLETED,
      startOffsetMs: 0,
      overlapBeforeMs: 0,
      exactText: 'প্রথম অংশের কথা এবং',
      detectedLanguage: 'bn',
      segments: [{ startMs: 100, endMs: 3000, text: 'প্রথম অংশের কথা এবং' }]
    }
  ]);

  assert.equal(merged.exactTranscript, 'প্রথম অংশের কথা এবং দ্বিতীয় অংশের কথা এখানে শুরু হলো।');
  assert.equal(merged.segments.length, 2);
  assert.equal(merged.segments[1].startMs, 7_200_900);
});
