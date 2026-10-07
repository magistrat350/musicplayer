package com.magistrat.musicplayer.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.player.PlayerConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Cover-Bild (Pfad im App-Speicher) mit Platzhalter-Icon. */
@Composable
fun Cover(
    path: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.MusicNote,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(if (size > 100.dp) 16.dp else 8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            val model: Any = if (path.startsWith("file:") || path.startsWith("content:")) Uri.parse(path) else File(path)
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(icon, null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun rememberPlayerPosition(): State<Long> {
    val pos = remember { mutableLongStateOf(PlayerConnection.position()) }
    LaunchedEffect(Unit) {
        while (true) {
            pos.longValue = PlayerConnection.position()
            delay(500)
        }
    }
    return pos
}

/** Listeneintrag mit Cover, Titel, Untertitel und Kontextmenue. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    title: String,
    subtitle: String,
    coverPath: String?,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    icon: ImageVector = Icons.Default.MusicNote,
    menu: List<Pair<String, () -> Unit>> = emptyList(),
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** null = keine Auswahl aktiv; sonst wird statt des Menues eine Checkbox gezeigt */
    selected: Boolean? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier
            .background(if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(coverPath, 52.dp, icon = icon)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected != null) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else if (menu.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menü") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            menuOpen = false
                            action()
                        })
                    }
                }
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String = "Name",
    confirm: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Löschen", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Auswahl einer Playlist (inkl. "Neue Playlist"). */
@Composable
fun PlaylistPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    allowNone: Boolean = false,
    onPick: (Long?) -> Unit,
) {
    val repo = App.instance.repo
    val playlists by remember { repo.playlists.allWithCount() }.collectAsStateWithLifecycle(emptyList())
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (creating) {
        TextInputDialog(title = "Neue Playlist", confirm = "Erstellen", onDismiss = { creating = false }) { name ->
            if (name.isNotBlank()) {
                scope.launch {
                    val id = repo.createPlaylist(name)
                    onPick(id)
                }
            }
            creating = false
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { creating = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Add, null)
                        Text("Neue Playlist", Modifier.padding(start = 12.dp))
                    }
                    HorizontalDivider()
                }
                if (allowNone) {
                    item {
                        Text(
                            "Keine Playlist",
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(null) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
                items(playlists, key = { it.playlist.id }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(p.playlist.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(p.playlist.coverPath, 36.dp, icon = Icons.AutoMirrored.Filled.QueueMusic)
                        Text("${p.playlist.name} (${p.trackCount})", Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
