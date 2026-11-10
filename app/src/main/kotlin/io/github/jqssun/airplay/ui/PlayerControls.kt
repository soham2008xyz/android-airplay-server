package io.github.jqssun.airplay.ui

import android.graphics.Bitmap
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.ui.compose.state.rememberNextButtonState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPreviousButtonState
import io.github.jqssun.airplay.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Stand-ins for the media3-ui-compose-material3 widgets, which need minSdk 23.
// Built on the media3-ui-compose 1.8 state holders, which support minSdk 21.

private val ControlSize = 48.dp
private val MediumIconSize = 32.dp

@Composable
private fun ControlIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    iconSize: Dp,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription, modifier = Modifier.size(iconSize))
    }
}

@AndroidxOptIn(UnstableApi::class)
@Composable
fun PlayPauseButton(player: Player, modifier: Modifier = Modifier, iconSize: Dp = MediumIconSize) {
    val state = rememberPlayPauseButtonState(player)
    ControlIconButton(
        icon = if (state.showPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
        contentDescription = stringResource(R.string.cd_play_pause),
        enabled = state.isEnabled,
        onClick = state::onClick,
        modifier = modifier,
        iconSize = iconSize,
    )
}

@AndroidxOptIn(UnstableApi::class)
@Composable
fun NextButton(player: Player, modifier: Modifier = Modifier, iconSize: Dp = MediumIconSize) {
    val state = rememberNextButtonState(player)
    ControlIconButton(
        Icons.Rounded.SkipNext, stringResource(R.string.cd_next), state.isEnabled, state::onClick, modifier, iconSize
    )
}

@AndroidxOptIn(UnstableApi::class)
@Composable
fun PreviousButton(player: Player, modifier: Modifier = Modifier, iconSize: Dp = MediumIconSize) {
    val state = rememberPreviousButtonState(player)
    ControlIconButton(
        Icons.Rounded.SkipPrevious, stringResource(R.string.cd_previous), state.isEnabled, state::onClick, modifier, iconSize
    )
}

// position, duration and metadata of a player, refreshed on player events and on a tick
@Stable
private class PlayerProgressState(private val player: Player) {
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(C.TIME_UNSET)
        private set
    var canSeek by mutableStateOf(false)
        private set
    var metadata by mutableStateOf(MediaMetadata.EMPTY)
        private set

    fun refresh() {
        positionMs = player.currentPosition.coerceAtLeast(0L)
        durationMs = player.duration
        canSeek = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        metadata = player.mediaMetadata
    }

    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

@Composable
private fun rememberPlayerProgressState(player: Player, tickMs: Long = 500L): PlayerProgressState {
    val state = remember(player) { PlayerProgressState(player) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = state.refresh()
        }
        state.refresh()
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, tickMs) {
        while (true) {
            state.refresh()
            delay(tickMs)
        }
    }
    return state
}

private fun formatTime(ms: Long): String = if (ms < 0 || ms == C.TIME_UNSET) "--:--" else formatVideoTime(ms)

@Composable
fun PositionText(player: Player, modifier: Modifier = Modifier) {
    val state = rememberPlayerProgressState(player)
    Text(formatTime(state.positionMs), modifier = modifier, style = MaterialTheme.typography.labelMedium)
}

@Composable
fun DurationText(player: Player, modifier: Modifier = Modifier) {
    val state = rememberPlayerProgressState(player)
    Text(formatTime(state.durationMs), modifier = modifier, style = MaterialTheme.typography.labelMedium)
}

@Composable
fun ProgressSlider(player: Player, modifier: Modifier = Modifier) {
    val state = rememberPlayerProgressState(player)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Slider(
        value = if (dragging) dragValue else state.fraction,
        onValueChange = {
            dragging = true
            dragValue = it
        },
        onValueChangeFinished = {
            if (state.durationMs > 0) player.seekTo((dragValue * state.durationMs).toLong())
            dragging = false
        },
        enabled = state.canSeek && state.durationMs > 0,
        modifier = modifier,
    )
}

@AndroidxOptIn(UnstableApi::class)
@Composable
private fun Artwork(player: Player, metadata: MediaMetadata, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val loader = remember { DataSourceBitmapLoader(context) }
    // keep the old bitmap until the new one arrives, to avoid a flicker
    var bitmap by remember(player) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(metadata) {
        val clearStale = launch {
            delay(1000)
            bitmap = null
        }
        val loaded = runCatching {
            loader.loadBitmapFromMetadata(metadata)?.let { f -> withContext(Dispatchers.IO) { f.get() } }
        }.getOrNull()
        clearStale.cancel()
        bitmap = loaded
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier)
    }
}

// compact now-playing card: artwork, title/artist, previous/play/next, progress bar
@Composable
fun MiniController(player: Player, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val state = rememberPlayerProgressState(player)
    Card(
        onClick = onClick,
        modifier = modifier,
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Artwork(player, state.metadata, Modifier.size(48.dp))
                Column(modifier = Modifier.weight(1f)) {
                    state.metadata.title?.toString()?.let {
                        Text(
                            it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            maxLines = 1, modifier = Modifier.basicMarquee()
                        )
                    }
                    state.metadata.artist?.toString()?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.basicMarquee())
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PreviousButton(player, modifier = Modifier.size(ControlSize))
                    PlayPauseButton(player, modifier = Modifier.size(ControlSize))
                    NextButton(player, modifier = Modifier.size(ControlSize))
                }
            }
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}
