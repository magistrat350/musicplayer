package com.magistrat.musicplayer.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.magistrat.musicplayer.data.SourceType
import com.magistrat.musicplayer.player.PlayerConnection
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
fun PlayerScreen(onClose: () -> Unit, onOpenQueue: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by PlayerConnection.state.collectAsStateWithLifecycle()
    val position by rememberPlayerPosition()
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var addingBookmark by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    val sleep by PlayerConnection.sleepTimer.collectAsStateWithLifecycle()
    val now by rememberTicker()
    val isBook = state.source?.type == SourceType.AUDIOBOOK

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Schließen") }
            Text(
                when (state.source?.type) {
                    null -> if (state.isQueued) "Warteschlange" else ""
                    SourceType.AUDIOBOOK -> "Hörbuch · ${state.album}"
                    SourceType.PLAYLIST -> "Playlist · ${state.album}"
                    else -> "Bibliothek"
                },
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = { addingBookmark = true }, enabled = state.hasItem) {
                Icon(Icons.Default.BookmarkAdd, "Lesezeichen setzen")
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Cover(state.artworkUri, 320.dp, icon = Icons.Default.MusicNote)
        }
        Spacer(Modifier.height(24.dp))

        Text(state.title.ifBlank { "Nichts ausgewählt" }, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(state.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state.count > 1) {
            Text(
                (if (isBook) "Kapitel " else "Titel ") + "${state.index + 1} von ${state.count}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        val duration = state.durationMs.coerceAtLeast(1)
        Slider(
            value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { PlayerConnection.seekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = state.durationMs > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(dragging?.let { (it * duration).toLong() } ?: position), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { PlayerConnection.previous() }) { Icon(Icons.Default.SkipPrevious, "Zurück", Modifier.size(32.dp)) }
            IconButton(onClick = { PlayerConnection.seekBack() }) { Icon(Icons.Default.Replay10, "10 s zurück", Modifier.size(32.dp)) }
            FilledIconButton(
                onClick = { PlayerConnection.togglePlay() },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", Modifier.size(40.dp))
            }
            IconButton(onClick = { PlayerConnection.seekForward() }) { Icon(Icons.Default.Forward30, "30 s vor", Modifier.size(32.dp)) }
            IconButton(onClick = { PlayerConnection.next() }) { Icon(Icons.Default.SkipNext, "Weiter", Modifier.size(32.dp)) }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { PlayerConnection.toggleShuffle() }) {
                Icon(
                    Icons.Default.Shuffle, "Zufall",
                    tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { speedMenu = true }) { Text("${formatSpeed(state.speed)}×") }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    SPEEDS.forEach { s ->
                        DropdownMenuItem(text = { Text("${formatSpeed(s)}×") }, onClick = {
                            speedMenu = false
                            PlayerConnection.setSpeed(context, s)
                        })
                    }
                }
            }
            Box {
                IconButton(onClick = { sleepMenu = true }) {
                    Icon(
                        Icons.Default.Bedtime, "Schlaftimer",
                        tint = if (sleep != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                    listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                        DropdownMenuItem(text = { Text("$m Minuten") }, onClick = {
                            sleepMenu = false
                            PlayerConnection.startSleepTimer(m)
                        })
                    }
                    DropdownMenuItem(text = { Text(if (isBook) "Ende des Kapitels" else "Ende des Titels") }, onClick = {
                        sleepMenu = false
                        PlayerConnection.startSleepTimerEndOfItem()
                    })
                    if (sleep != null) {
                        DropdownMenuItem(text = { Text("Timer aus") }, onClick = {
                            sleepMenu = false
                            PlayerConnection.cancelSleepTimer()
                        })
                    }
                }
            }
            IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Warteschlange") }
            IconButton(onClick = { PlayerConnection.cycleRepeat() }) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Wiederholen",
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        sleep?.let { t ->
            Text(
                if (t.endOfItem) "Schlaftimer: stoppt am Ende " + (if (isBook) "des Kapitels" else "des Titels")
                else "Schlaftimer: noch ${formatTime((t.endAt ?: now) - now)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (addingBookmark) {
        TextInputDialog(
            title = "Lesezeichen setzen",
            label = "Bezeichnung (optional)",
            confirm = "Speichern",
            onDismiss = { addingBookmark = false },
        ) { label ->
            addingBookmark = false
            scope.launch {
                val bm = PlayerConnection.addManualBookmark(label)
                Toast.makeText(
                    context,
                    if (bm != null) "Lesezeichen bei ${formatTime(bm.positionMs)} gespeichert" else "Nichts zum Speichern",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

@Composable
private fun rememberTicker(): androidx.compose.runtime.State<Long> {
    val t = remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            t.longValue = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    return t
}

private fun formatSpeed(s: Float): String =
    if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')

/** Kleine Leiste ueber der Navigation mit aktuellem Titel. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val state by PlayerConnection.state.collectAsStateWithLifecycle()
    if (!state.hasItem) return
    val position by rememberPlayerPosition()
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen)
    ) {
        LinearProgressIndicator(
            progress = { if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(state.artworkUri, 44.dp)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { PlayerConnection.togglePlay() }) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause")
            }
            IconButton(onClick = { PlayerConnection.next() }) { Icon(Icons.Default.SkipNext, "Weiter") }
        }
    }
}
