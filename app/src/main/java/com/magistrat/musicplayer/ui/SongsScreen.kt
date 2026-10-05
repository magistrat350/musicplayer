package com.magistrat.musicplayer.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.data.SourceType
import com.magistrat.musicplayer.data.Track
import com.magistrat.musicplayer.player.Playback
import com.magistrat.musicplayer.player.PlayerConnection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = App.instance.repo
    val tracks by remember { repo.tracks.all() }.collectAsStateWithLifecycle(emptyList())
    val player by PlayerConnection.state.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Track?>(null) }
    var addingToPlaylist by remember { mutableStateOf<Track?>(null) }
    var deleting by remember { mutableStateOf<Track?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val n = repo.importSongs(uris)
            Toast.makeText(context, "$n Song(s) importiert", Toast.LENGTH_SHORT).show()
        }
    }

    val shown = if (query.isBlank()) tracks else tracks.filter {
        it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Songs") },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, "Suchen") }
                    IconButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) { Icon(Icons.Default.FileOpen, "Lokale Dateien importieren") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Titel oder Interpret") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (!searching) item(key = "continue") { ContinueRow() }
                if (tracks.isEmpty()) item(key = "empty") {
                    EmptyHint("Noch keine Songs.\nLade über den Tab „Download“ einen YouTube-Link als MP3 herunter oder importiere vorhandene Dateien.")
                }
                itemsIndexed(shown, key = { _, t -> t.id }) { _, t ->
                    MediaRow(
                        title = t.title,
                        subtitle = listOf(t.artist, if (t.durationMs > 0) formatTime(t.durationMs) else "").filter { it.isNotBlank() }.joinToString(" · "),
                        coverPath = t.coverPath,
                        highlighted = player.itemId == t.id && player.source?.type != SourceType.AUDIOBOOK,
                        onClick = { scope.launch { Playback.playSingleTrack(context, t) } },
                        menu = listOf(
                            "Als Nächstes spielen" to { scope.launch { PlayerConnection.playNext(context, t); toast(context, "Wird als Nächstes gespielt") }; Unit },
                            "Zur Warteschlange hinzufügen" to { scope.launch { PlayerConnection.addToQueue(context, t); toast(context, "Zur Warteschlange hinzugefügt") }; Unit },
                            "Zur Playlist hinzufügen" to { addingToPlaylist = t },
                            "Bearbeiten / Bild" to { editing = t },
                            "Löschen" to { deleting = t },
                        ),
                    )
                }
            }
        }
    }

    editing?.let { t -> EditTrackDialog(t, onDismiss = { editing = null }) }
    addingToPlaylist?.let { t ->
        PlaylistPickerDialog(title = "Zur Playlist hinzufügen", onDismiss = { addingToPlaylist = null }) { pid ->
            addingToPlaylist = null
            if (pid != null) scope.launch {
                repo.addToPlaylist(pid, t.id)
                Toast.makeText(context, "Hinzugefügt", Toast.LENGTH_SHORT).show()
            }
        }
    }
    deleting?.let { t ->
        ConfirmDialog("Song löschen?", "„${t.title}“ wird aus der Bibliothek und allen Playlists entfernt.", onDismiss = { deleting = null }) {
            deleting = null
            scope.launch { repo.deleteTrack(t) }
        }
    }
}

fun toast(context: android.content.Context, text: String) =
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

@Composable
fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Titel, Interpret und Bild eines Songs bearbeiten. */
@Composable
fun EditTrackDialog(track: Track, onDismiss: () -> Unit) {
    val repo = App.instance.repo
    val scope = rememberCoroutineScope()
    val live by remember(track.id) { repo.tracks.all() }.collectAsStateWithLifecycle(emptyList())
    val current = live.firstOrNull { it.id == track.id } ?: track
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { repo.setTrackCover(current, uri) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(
                        current.coverPath, 96.dp,
                        Modifier.clickable { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        TextButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Text("Bild wählen")
                        }
                        if (current.coverPath != null) {
                            TextButton(onClick = { scope.launch { repo.removeTrackCover(current) } }) { Text("Bild entfernen") }
                        }
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titel") }, singleLine = true)
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Interpret") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                App.instance.appScope.launch { repo.tracks.update(current.copy(title = title.trim().ifBlank { current.title }, artist = artist.trim())) }
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
