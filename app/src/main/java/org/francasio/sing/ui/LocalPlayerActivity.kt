package org.francasio.sing.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.delay
import org.francasio.sing.R
import org.francasio.sing.audio.LocalStemAudioProcessor
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.ui.theme.AppleMusicSingTheme
import org.francasio.sing.util.ModelLoader
import java.util.Locale

private data class LocalPlayerUiState(
    val isEngineReady: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val trackName: String? = null,
    val status: String = "",
    val error: String? = null
)

@OptIn(UnstableApi::class)
class LocalPlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var stemProcessor: LocalStemAudioProcessor
    private val uiState = mutableStateOf(LocalPlayerUiState())

    private val openAudioFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playSelectedUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SingSettings.loadLocal(this)
        ModelLoader.loadModels(this)

        uiState.value = if (SingSettings.selectedModelId.isBlank()) {
            LocalPlayerUiState(status = getString(R.string.local_player_select_model))
        } else {
            LocalPlayerUiState(status = getString(R.string.local_player_loading_model))
        }

        stemProcessor = LocalStemAudioProcessor(
            context = this,
            moduleApkPath = applicationInfo.sourceDir,
            onEngineReady = {
                runOnUiThread {
                    val hasModel = SingSettings.selectedModelId.isNotBlank()
                    uiState.value = uiState.value.copy(
                        isEngineReady = hasModel,
                        status = getString(
                            if (hasModel) R.string.local_player_processing_ready
                            else R.string.local_player_select_model
                        ),
                        error = null
                    )
                }
            },
            onEngineError = { message ->
                runOnUiThread {
                    uiState.value = uiState.value.copy(
                        isEngineReady = false,
                        status = message
                    )
                }
            }
        )

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(stemProcessor))
                    .build()
            }
        }
        player = ExoPlayer.Builder(this, renderersFactory).build()
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                uiState.value = uiState.value.copy(isPlaying = isPlaying, error = null)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                uiState.value = uiState.value.copy(
                    isPlaying = player.isPlaying,
                    isBuffering = playbackState == Player.STATE_BUFFERING
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                uiState.value = uiState.value.copy(
                    isPlaying = false,
                    error = getString(
                        R.string.local_player_playback_error,
                        error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.local_player_open_error)
                    )
                )
            }
        })

        setContent {
            AppleMusicSingTheme(dynamicColor = SingSettings.materialYou) {
                LocalPlayerScreen(
                    player = player,
                    state = uiState.value,
                    vocalLevel = stemProcessor.vocalLevel,
                    onChooseAudio = { openAudioFile.launch(arrayOf("audio/*")) },
                    onVocalLevelChange = { stemProcessor.vocalLevel = it },
                    onStop = {
                        player.stop()
                        player.clearMediaItems()
                        uiState.value = uiState.value.copy(trackName = null, isPlaying = false, error = null)
                    },
                    onBackToSettings = { finish() }
                )
            }
        }
    }

    private fun playSelectedUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Could not persist permission for selected audio URI", error)
        }

        try {
            val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameColumn >= 0) {
                        cursor.getString(nameColumn)
                    } else {
                        null
                    }
                }
                ?: uri.lastPathSegment
                ?: getString(R.string.local_player_title)

            uiState.value = uiState.value.copy(trackName = displayName, error = null)
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.play()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to open selected audio URI", error)
            uiState.value = uiState.value.copy(error = getString(R.string.local_player_open_error))
        }
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        if (::stemProcessor.isInitialized) stemProcessor.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Sing_LocalPlayer"
    }
}

@Composable
private fun LocalPlayerScreen(
    player: ExoPlayer,
    state: LocalPlayerUiState,
    vocalLevel: Float,
    onChooseAudio: () -> Unit,
    onVocalLevelChange: (Float) -> Unit,
    onStop: () -> Unit,
    onBackToSettings: () -> Unit
) {
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var sliderValue by remember { mutableFloatStateOf(vocalLevel) }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0L } ?: 0L
            delay(250L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.local_player_title),
            style = MaterialTheme.typography.headlineMedium
        )
        TextButton(
            onClick = onBackToSettings,
            modifier = Modifier.align(Alignment.Start)
        ) {
            Text(stringResource(R.string.local_player_back_to_settings))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = state.trackName ?: stringResource(R.string.local_player_no_track),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (state.isBuffering) stringResource(R.string.local_player_processing) else state.status,
            style = MaterialTheme.typography.bodyMedium
        )

        if (state.trackName != null) {
            Spacer(Modifier.height(16.dp))
            Slider(
                value = positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                onValueChange = { player.seekTo(it.toLong()) },
                enabled = durationMs > 0L,
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(positionMs))
                Text(formatTime(durationMs))
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onChooseAudio,
                enabled = state.isEngineReady
            ) {
                Text(stringResource(R.string.local_player_choose_audio))
            }
            Button(
                onClick = {
                    if (state.isPlaying) player.pause() else player.play()
                },
                enabled = state.isEngineReady && state.trackName != null
            ) {
                Text(
                    stringResource(
                        if (state.isPlaying) R.string.local_player_pause else R.string.local_player_play
                    )
                )
            }
        }
        if (state.trackName != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onStop) {
                Text(stringResource(R.string.local_player_stop))
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.local_player_vocal_level))
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                onVocalLevelChange(it)
            },
            enabled = state.isEngineReady,
            valueRange = 0f..1f
        )
        Text(String.format(Locale.getDefault(), "%.0f%%", sliderValue * 100f))

        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    return String.format(
        Locale.getDefault(),
        "%d:%02d",
        totalSeconds / 60,
        totalSeconds % 60
    )
}
