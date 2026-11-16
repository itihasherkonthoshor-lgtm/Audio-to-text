package com.example.data.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val activeFilePath: String? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L
)

class AudioPlayerController(
    context: Context,
    private val scope: CoroutineScope
) {
    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(
                    isPlaying = isPlaying,
                    totalDurationMs = player.duration.coerceAtLeast(0L)
                )
                if (isPlaying) {
                    startProgressPolling()
                } else {
                    progressJob?.cancel()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    progressJob?.cancel()
                    player.seekTo(0L)
                    player.pause()
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        currentPositionMs = 0L
                    )
                } else if (playbackState == Player.STATE_READY) {
                    _state.value = _state.value.copy(
                        totalDurationMs = player.duration.coerceAtLeast(0L)
                    )
                }
            }
        })
    }

    fun togglePlayPause(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return

        if (_state.value.activeFilePath == filePath) {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        } else {
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
            _state.value = PlaybackState(
                activeFilePath = filePath,
                isPlaying = true,
                currentPositionMs = 0L,
                totalDurationMs = 0L
            )
        }
    }

    fun seekTo(positionMs: Long) {
        if (_state.value.activeFilePath != null) {
            val clamped = positionMs.coerceIn(0L, _state.value.totalDurationMs.coerceAtLeast(0L))
            player.seekTo(clamped)
            _state.value = _state.value.copy(currentPositionMs = clamped)
        }
    }

    fun stopIfPlaying(filePath: String? = null) {
        if (filePath == null || _state.value.activeFilePath == filePath) {
            progressJob?.cancel()
            player.stop()
            player.clearMediaItems()
            _state.value = PlaybackState()
        }
    }

    fun release() {
        progressJob?.cancel()
        player.release()
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive && player.isPlaying) {
                _state.value = _state.value.copy(
                    currentPositionMs = player.currentPosition.coerceAtLeast(0L),
                    totalDurationMs = player.duration.coerceAtLeast(_state.value.totalDurationMs)
                )
                delay(200L)
            }
        }
    }
}
