package com.magistrat.musicplayer.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.download.YoutubeDownloadWorker
import com.magistrat.musicplayer.download.YtdlUpdater
import com.magistrat.musicplayer.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gewaehltes Download-Ziel (Musik / Hoerbuch); kann z. B. vom Hoerbuch-Tab vorbelegt werden. */
val downloadKind = MutableStateFlow(YoutubeDownloadWorker.KIND_MUSIC)

private val URL_REGEX = Regex("https?://\\S+")

/** Holt den ersten Link aus einem (geteilten) Text. */
fun extractUrl(text: String?): String? = text?.let { URL_REGEX.find(it)?.value }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(initialUrl: String?, onUrlConsumed: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val repo = App.instance.repo
    val workManager = remember { WorkManager.getInstance(context) }
    val jobs by remember { workManager.getWorkInfosByTagFlow(YoutubeDownloadWorker.TAG) }.collectAsStateWithLifecycle(emptyList())
    val playlists by remember { repo.playlists.allWithCount() }.collectAsStateWithLifecycle(emptyList())

    var url by remember { mutableStateOf("") }
    var targetPlaylist by remember { mutableStateOf<Long?>(null) }
    var pickingPlaylist by remember { mutableStateOf(false) }
    var ytdlStatus by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }
    var wholePlaylist by remember { mutableStateOf(true) }
    var appUpdateStatus by remember { mutableStateOf("") }
    var checkingApp by remember { mutableStateOf(false) }
    val kind by downloadKind.collectAsStateWithLifecycle()
    val asBook = kind == YoutubeDownloadWorker.KIND_AUDIOBOOK
    val isPlaylistLink = YoutubeDownloadWorker.isPlaylistUrl(url)
    // Reine Playlist-Links: standardmaessig alles laden. Video-in-Playlist: bei Hoerbuechern ebenfalls
    // (z. B. Folge 1 einer Reihe geteilt), bei Musik nur das Video. YouTube-Mixe nie automatisch.
    LaunchedEffect(url, asBook) {
        wholePlaylist = !YoutubeDownloadWorker.isMixUrl(url) && (url.contains("/playlist") || asBook)
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl != null) {
            url = initialUrl
            onUrlConsumed()
        }
    }
    LaunchedEffect(Unit) {
        if (App.instance.ytdlReady.await()) ytdlStatus = "yt-dlp " + withContext(Dispatchers.IO) { YtdlUpdater.version(context) }
        else ytdlStatus = "yt-dlp konnte nicht gestartet werden"
    }

    val targetName = playlists.firstOrNull { it.playlist.id == targetPlaylist }?.playlist?.name

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("YouTube-Download") }) },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("YouTube-Link") },
                        placeholder = { Text("https://youtu.be/…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (url.isEmpty()) {
                                IconButton(onClick = { url = extractUrl(clipboard.getText()?.text) ?: clipboard.getText()?.text.orEmpty() }) {
                                    Icon(Icons.Default.ContentPaste, "Einfügen")
                                }
                            } else {
                                IconButton(onClick = { url = "" }) { Icon(Icons.Default.Close, "Leeren") }
                            }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Speichern als:", style = MaterialTheme.typography.bodyMedium)
                        FilterChip(
                            selected = !asBook,
                            onClick = { downloadKind.value = YoutubeDownloadWorker.KIND_MUSIC },
                            label = { Text("Musik") },
                            leadingIcon = { Icon(Icons.Default.MusicNote, null) },
                        )
                        FilterChip(
                            selected = asBook,
                            onClick = { downloadKind.value = YoutubeDownloadWorker.KIND_AUDIOBOOK },
                            label = { Text("Hörbuch") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                        )
                    }
                    if (isPlaylistLink) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { wholePlaylist = !wholePlaylist },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = wholePlaylist, onCheckedChange = { wholePlaylist = it })
                            Text(
                                when {
                                    asBook -> "Ganze Playlist als Hörbuch laden (jedes Video = ein Kapitel)"
                                    wholePlaylist && targetPlaylist == null -> "Ganze YouTube-Playlist laden (wird als neue Playlist angelegt)"
                                    else -> "Ganze YouTube-Playlist laden"
                                }
                            )
                        }
                    }
                    if (!asBook) {
                        OutlinedButton(onClick = { pickingPlaylist = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                            Text("  Danach zu Playlist: ${targetName ?: if (isPlaylistLink && wholePlaylist) "neue" else "keine"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Text(
                            if (isPlaylistLink && wholePlaylist) "Wurde die Playlist schon einmal geladen, werden nur neue Folgen ergänzt."
                            else "Das Video wird als Hörbuch mit einem Kapitel gespeichert.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            val link = extractUrl(url.trim())
                            if (link == null) {
                                Toast.makeText(context, "Bitte einen gültigen Link eingeben", Toast.LENGTH_SHORT).show()
                            } else {
                                YoutubeDownloadWorker.enqueue(
                                    context, link,
                                    playlistId = if (asBook) null else targetPlaylist,
                                    fullPlaylist = isPlaylistLink && wholePlaylist,
                                    kind = kind,
                                )
                                url = ""
                                Toast.makeText(context, "Download gestartet", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Download, null)
                        Text(if (asBook) "  Als Hörbuch herunterladen" else "  Als MP3 herunterladen")
                    }
                    Text(
                        "Tipp: In der YouTube-App auf „Teilen“ → „MusicPlayer“ tippen, dann ist der Link automatisch hier.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (jobs.isNotEmpty()) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloads", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { workManager.pruneWork() }) { Text("Erledigte entfernen") }
                    }
                }
            }
            items(jobs.sortedBy { it.state.isFinished }, key = { it.id.toString() }) { info ->
                DownloadCard(info, onCancel = { workManager.cancelWorkById(info.id) })
            }

            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ytdlStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        enabled = !updating,
                        onClick = {
                            updating = true
                            scope.launch {
                                val msg = YtdlUpdater.update(context)
                                ytdlStatus = msg
                                updating = false
                            }
                        },
                    ) {
                        Icon(Icons.Default.Refresh, null)
                        Text(if (updating) "  Aktualisiere…" else "  yt-dlp aktualisieren")
                    }
                    Text(
                        "Wenn Downloads plötzlich fehlschlagen, hat YouTube meist etwas geändert – dann hilft ein Update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "App-Version ${Updater.currentVersionName} (Build ${Updater.currentVersionCode})" +
                            if (appUpdateStatus.isNotBlank()) " – $appUpdateStatus" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    OutlinedButton(
                        enabled = !checkingApp,
                        onClick = {
                            checkingApp = true
                            scope.launch {
                                appUpdateStatus = try {
                                    if (Updater.checkNow() == null) "aktuell" else "Update gefunden"
                                } catch (e: Exception) {
                                    "Prüfung fehlgeschlagen"
                                }
                                checkingApp = false
                            }
                        },
                    ) {
                        Icon(Icons.Default.SystemUpdate, null)
                        Text(if (checkingApp) "  Suche…" else "  Nach App-Updates suchen")
                    }
                }
            }
        }
    }

    if (pickingPlaylist) {
        PlaylistPickerDialog(title = "Ziel-Playlist", allowNone = true, onDismiss = { pickingPlaylist = false }) { id ->
            targetPlaylist = id
            pickingPlaylist = false
        }
    }
}

@Composable
private fun DownloadCard(info: WorkInfo, onCancel: () -> Unit) {
    val title = info.progress.getString(YoutubeDownloadWorker.KEY_TITLE)?.takeIf { it.isNotBlank() }
        ?: info.outputData.getString(YoutubeDownloadWorker.KEY_TITLE)?.takeIf { it.isNotBlank() }
        ?: info.tags.firstOrNull { it.startsWith("url:") }?.removePrefix("url:")
        ?: "Download"
    val progress = info.progress.getFloat(YoutubeDownloadWorker.KEY_PROGRESS, 0f)
    val status = when (info.state) {
        WorkInfo.State.ENQUEUED -> "Wartet (Internet?)…"
        WorkInfo.State.RUNNING -> info.progress.getString(YoutubeDownloadWorker.KEY_STATUS) ?: "Läuft…"
        WorkInfo.State.SUCCEEDED -> info.outputData.getString(YoutubeDownloadWorker.KEY_STATUS) ?: "Fertig"
        WorkInfo.State.FAILED -> "Fehler: " + (info.outputData.getString(YoutubeDownloadWorker.KEY_ERROR) ?: "unbekannt")
        WorkInfo.State.CANCELLED -> "Abgebrochen"
        WorkInfo.State.BLOCKED -> "Wartet…"
    }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Default.Download, null)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (info.state == WorkInfo.State.RUNNING) {
                    if (progress > 0f) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            if (!info.state.isFinished) {
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Abbrechen") }
            }
        }
    }
}
