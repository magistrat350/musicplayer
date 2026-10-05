package com.magistrat.musicplayer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.data.SourceType
import com.magistrat.musicplayer.player.Playback
import com.magistrat.musicplayer.player.PlayerConnection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(onOpen: (Long) -> Unit) {
    val repo = App.instance.repo
    val scope = rememberCoroutineScope()
    val playlists by remember { repo.playlists.allWithCount() }.collectAsStateWithLifecycle(emptyList())
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Playlists") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Neue Playlist") }
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (playlists.isEmpty()) item { EmptyHint("Noch keine Playlists. Tippe auf +, um eine zu erstellen.") }
            items(playlists, key = { it.playlist.id }) { p ->
                MediaRow(
                    title = p.playlist.name,
                    subtitle = "${p.trackCount} Songs",
                    coverPath = p.playlist.coverPath,
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    onClick = { onOpen(p.playlist.id) },
                )
            }
        }
    }

    if (creating) {
        TextInputDialog(title = "Neue Playlist", confirm = "Erstellen", onDismiss = { creating = false }) { name ->
            creating = false
            if (name.isNotBlank()) scope.launch { onOpen(repo.createPlaylist(name)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(playlistId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = App.instance.repo
    val scope = rememberCoroutineScope()
    val playlist by remember(playlistId) { repo.playlists.observe(playlistId) }.collectAsStateWithLifecycle(null)
    val tracks by remember(playlistId) { repo.playlists.tracks(playlistId) }.collectAsStateWithLifecycle(emptyList())
    val auto by remember(playlistId) { repo.bookmarks.observeAuto(SourceType.PLAYLIST, playlistId) }.collectAsStateWithLifecycle(null)
    val manual by remember(playlistId) { repo.bookmarks.manual(SourceType.PLAYLIST, playlistId) }.collectAsStateWithLifecycle(emptyList())
    val player by PlayerConnection.state.collectAsStateWithLifecycle()
    val isCurrent = player.source?.type == SourceType.PLAYLIST && player.source?.id == playlistId

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val p = playlist
        if (uri != null && p != null) scope.launch { repo.setPlaylistCover(p, uri) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = {
                    IconButton(onClick = { adding = true }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Songs hinzufügen") }
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Umbenennen") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Playlist löschen") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            item(key = "header") {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(
                        playlist?.coverPath, 120.dp,
                        Modifier.clickable { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                    )
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(playlist?.name ?: "", style = MaterialTheme.typography.titleLarge)
                        Text("${tracks.size} Songs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Icons.Default.Image, null)
                            Text(" Bild ändern")
                        }
                    }
                }
                ResumeButtons(
                    auto = auto,
                    enabled = tracks.isNotEmpty(),
                    onResume = { scope.launch { Playback.playPlaylist(context, playlistId, null) } },
                    onFromStart = { scope.launch { Playback.playPlaylist(context, playlistId, 0, fromStart = true) } },
                )
            }
            bookmarkItems(
                manual,
                onPlay = { bm -> scope.launch { Playback.playBookmark(context, bm) } },
                onDelete = { bm -> scope.launch { repo.bookmarks.delete(bm) } },
            )
            if (tracks.isEmpty()) {
                item { EmptyHint("Diese Playlist ist leer. Füge über das Symbol oben Songs hinzu.") }
            }
            itemsIndexed(tracks, key = { _, t -> "t_${t.id}" }) { index, t ->
                MediaRow(
                    title = t.title,
                    subtitle = t.artist,
                    coverPath = t.coverPath,
                    highlighted = isCurrent && player.index == index,
                    onClick = { scope.launch { Playback.playPlaylist(context, playlistId, index) } },
                    menu = buildList<Pair<String, () -> Unit>> {
                        if (index > 0) add("Nach oben" to { scope.launch { repo.moveInPlaylist(playlistId, index, index - 1) }; Unit })
                        if (index < tracks.lastIndex) add("Nach unten" to { scope.launch { repo.moveInPlaylist(playlistId, index, index + 1) }; Unit })
                        add("Aus Playlist entfernen" to { scope.launch { repo.playlists.removeTrack(playlistId, t.id) }; Unit })
                    },
                )
            }
        }
    }

    if (renaming) {
        TextInputDialog(title = "Playlist umbenennen", initial = playlist?.name ?: "", onDismiss = { renaming = false }) { name ->
            renaming = false
            val p = playlist
            if (p != null && name.isNotBlank()) scope.launch { repo.playlists.update(p.copy(name = name)) }
        }
    }
    if (deleting) {
        ConfirmDialog("Playlist löschen?", "Die Songs bleiben in der Bibliothek erhalten.", onDismiss = { deleting = false }) {
            deleting = false
            val p = playlist
            if (p != null) App.instance.appScope.launch { repo.deletePlaylist(p) }
            onBack()
        }
    }
    if (adding) {
        AddSongsDialog(
            excludeIds = tracks.map { it.id }.toSet(),
            onDismiss = { adding = false },
        ) { ids ->
            adding = false
            scope.launch { ids.forEach { repo.addToPlaylist(playlistId, it) } }
        }
    }
}

@Composable
fun AddSongsDialog(excludeIds: Set<Long>, onDismiss: () -> Unit, onAdd: (List<Long>) -> Unit) {
    val repo = App.instance.repo
    val all by remember { repo.tracks.all() }.collectAsStateWithLifecycle(emptyList())
    val candidates = all.filter { it.id !in excludeIds }
    val selected = remember { mutableStateListOf<Long>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Songs hinzufügen") },
        text = {
            if (candidates.isEmpty()) {
                Text("Keine weiteren Songs in der Bibliothek.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.id }) { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { if (t.id in selected) selected.remove(t.id) else selected.add(t.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = t.id in selected, onCheckedChange = { c -> if (c) selected.add(t.id) else selected.remove(t.id) })
                            Cover(t.coverPath, 36.dp)
                            Text(t.title, Modifier.padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(selected.toList()) }, enabled = selected.isNotEmpty()) { Text("Hinzufügen (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
