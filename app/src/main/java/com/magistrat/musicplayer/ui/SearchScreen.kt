package com.magistrat.musicplayer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.data.Audiobook
import com.magistrat.musicplayer.data.ChapterHit
import com.magistrat.musicplayer.data.PlaylistWithCount
import com.magistrat.musicplayer.data.Track
import com.magistrat.musicplayer.player.Playback
import com.magistrat.musicplayer.player.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val playlists: List<PlaylistWithCount> = emptyList(),
    val books: List<Audiobook> = emptyList(),
    val chapters: List<ChapterHit> = emptyList(),
) {
    val isEmpty get() = tracks.isEmpty() && playlists.isEmpty() && books.isEmpty() && chapters.isEmpty()
}

/** Suche ueber Songs, Playlists, Hoerbuecher und Kapitel. */
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenPlaylist: (Long) -> Unit, onOpenAudiobook: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = App.instance.repo
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(SearchResults()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = SearchResults()
            return@LaunchedEffect
        }
        delay(200)
        results = withContext(Dispatchers.IO) {
            SearchResults(
                tracks = repo.tracks.search(q),
                playlists = repo.playlists.search(q),
                books = repo.audiobooks.search(q),
                chapters = repo.audiobooks.searchChapters(q),
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Songs, Playlists, Hörbücher, Kapitel") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Leeren") }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                        .focusRequester(focus),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .navigationBarsPadding()
                .fillMaxSize()
        ) {
            if (query.isNotBlank() && results.isEmpty) item { EmptyHint("Nichts gefunden.") }

            section("Songs", results.tracks.size)
            items(results.tracks, key = { "t${it.id}" }) { t ->
                MediaRow(
                    title = t.title,
                    subtitle = t.artist,
                    coverPath = t.coverPath,
                    onClick = { scope.launch { Playback.playSingleTrack(context, t) } },
                    menu = listOf(
                        "Als Nächstes spielen" to { scope.launch { PlayerConnection.playNext(context, t) }; Unit },
                        "Zur Warteschlange hinzufügen" to { scope.launch { PlayerConnection.addToQueue(context, t) }; Unit },
                    ),
                )
            }

            section("Playlists", results.playlists.size)
            items(results.playlists, key = { "p${it.playlist.id}" }) { p ->
                MediaRow(
                    title = p.playlist.name,
                    subtitle = "${p.trackCount} Songs",
                    coverPath = p.playlist.coverPath,
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    onClick = { onOpenPlaylist(p.playlist.id) },
                )
            }

            section("Hörbücher", results.books.size)
            items(results.books, key = { "b${it.id}" }) { b ->
                MediaRow(
                    title = b.title,
                    subtitle = b.author,
                    coverPath = b.coverPath,
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    onClick = { onOpenAudiobook(b.id) },
                )
            }

            section("Kapitel", results.chapters.size)
            items(results.chapters, key = { "c${it.chapter.id}" }) { h ->
                MediaRow(
                    title = h.chapter.title,
                    subtitle = h.bookTitle + if (h.chapter.durationMs > 0) " · ${formatTime(h.chapter.durationMs)}" else "",
                    coverPath = h.bookCover,
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    onClick = { scope.launch { Playback.playChapter(context, h.chapter.bookId, h.chapter.id) } },
                    menu = listOf("Hörbuch öffnen" to { onOpenAudiobook(h.chapter.bookId) }),
                )
            }
        }
    }
}

private fun LazyListScope.section(title: String, count: Int) {
    if (count == 0) return
    item(key = "h_$title") {
        Text(
            "$title ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}
