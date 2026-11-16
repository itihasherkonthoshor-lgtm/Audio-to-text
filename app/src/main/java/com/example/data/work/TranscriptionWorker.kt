package com.example.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsRepository
import com.example.data.local.TranscriptRepository
import com.example.data.remote.BackendTranscriptionService
import com.example.data.remote.FirebaseAiTranscriptionService
import com.example.data.remote.TranscriptionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private const val TAG = "TranscriptionWorker"
private const val CHANNEL_ID = "transcription_jobs"
private const val NOTIFICATION_ID = 4102

class TranscriptionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()

        ensureChannel()
        runCatching {
            setForeground(createForegroundInfo("AudioText Pro", "Processing audio transcription..."))
        }.onFailure { err ->
            Log.w(TAG, "Foreground promotion skipped (${err.javaClass.simpleName}); running as expedited/standard work")
        }

        val db = AppDatabase.getInstance(applicationContext)
        val settingsRepo = SettingsRepository(applicationContext)
        val transcriptRepo = TranscriptRepository(applicationContext, db.transcriptDao())
        val coordinator = TranscriptionCoordinator(
            context = applicationContext,
            jobDao = db.jobDao(),
            transcriptRepo = transcriptRepo,
            firebaseService = FirebaseAiTranscriptionService(applicationContext),
            backendService = BackendTranscriptionService()
        )

        val settings = settingsRepo.settingsFlow.first()
        return try {
            coordinator.executeDurableJob(jobId, settings) { title, statusMsg ->
                runCatching {
                    setForegroundAsync(createForegroundInfo(title, statusMsg))
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Worker error for job=$jobId", e)
            Result.failure()
        }
    }

    private fun createForegroundInfo(title: String, contentText: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Transcription Jobs",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background upload and transcription progress"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
    }
}
