package com.magistrat.musicplayer.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
fun AudiobooksScreen(onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val repo = App.instance.repo
    val scope = rememberCoroutineScope()
    val books by remember { repo.audiobooks.allWithCount() }.collectAsStateWithLifecycle(emptyList())
    var menuOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    fun done(id: Long?) {
        importing = false
        if (id == null) Toast.makeText(context, "Keine Audiodateien gefunden", Toast.LENGTH_SHORT).show()
        else onOpen(id)
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            scope.launch { done(repo.importAudiobookFiles(uris)) }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch { done(repo.importAudiobookFolder(uri)) }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Hörbücher") }) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Add, "Hörbuch hinzufügen") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Ordner wählen (ein Hörbuch)") }, onClick = {
                        menuOpen = false
                        pickFolder.launch(null)
                    })
                    DropdownMenuItem(text = { Text("Datei(en) wählen") }, onClick = {
                        menuOpen = false
                        pickFiles.launch(arrayOf("audio/*"))
                    })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                if (books.isEmpty()) item {
                    EmptyHint("Noch keine Hörbücher.\nTippe auf +, um einen Ordner mit Kapiteln oder einzelne Dateien (mp3, m4b, …) hinzuzufügen.")
                }
                items(books, key = { it.book.id }) { b ->
                    MediaRow(
                        title = b.book.title,
                        subtitle = listOf(b.book.author, "${b.chapterCount} Kapitel").filter { it.isNotBlank() }.joinToString(" · "),
                        coverPath = b.book.coverPath,
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        onClick = { onOpen(b.book.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookDetailScreen(bookId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = App.instance.repo
    val scope = rememberCoroutineScope()
    val book by remember(bookId) { repo.audiobooks.observe(bookId) }.collectAsStateWithLifecycle(null)
    val chapters by remember(bookId) { repo.audiobooks.chapters(bookId) }.collectAsStateWithLifecycle(emptyList())
    val auto by remember(bookId) { repo.bookmarks.observeAuto(SourceType.AUDIOBOOK, bookId) }.collectAsStateWithLifecycle(null)
    val manual by remember(bookId) { repo.bookmarks.manual(SourceType.AUDIOBOOK, bookId) }.collectAsStateWithLifecycle(emptyList())
    val player by PlayerConnection.state.collectAsStateWithLifecycle()
    val isCurrent = player.source?.type == SourceType.AUDIOBOOK && player.source?.id == bookId

    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val b = book
        if (uri != null && b != null) scope.launch { repo.setAudiobookCover(b, uri) }
    }

    val totalMs = chapters.sumOf { it.durationMs }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Bearbeiten") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Löschen") }
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
                        book?.coverPath, 120.dp,
                        Modifier.clickable { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                    )
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(book?.title ?: "", style = MaterialTheme.typography.titleLarge)
                        if (!book?.author.isNullOrBlank()) Text(book?.author ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${chapters.size} Kapitel" + if (totalMs > 0) " · ${formatTime(totalMs)}" else "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Icons.Default.Image, null)
                            Text(" Bild ändern")
                        }
                    }
                }
                ResumeButtons(
                    auto = auto,
                    enabled = chapters.isNotEmpty(),
                    onResume = { scope.launch { Playback.playAudiobook(context, bookId, null) } },
                    onFromStart = { scope.launch { Playback.playAudiobook(context, bookId, 0, fromStart = true) } },
                )
            }
            bookmarkItems(
                manual,
                onPlay = { bm -> scope.launch { Playback.playBookmark(context, bm) } },
                onDelete = { bm -> scope.launch { repo.bookmarks.delete(bm) } },
            )
            item(key = "ch_header") {
                Text(
                    "Kapitel",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            itemsIndexed(chapters, key = { _, c -> "c_${c.id}" }) { index, c ->
                val active = isCurrent && player.index == index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { Playback.playAudiobook(context, bookId, index) } }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}.",
                        Modifier.padding(end = 12.dp),
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        c.title,
                        Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (c.durationMs > 0) Text(formatTime(c.durationMs), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (editing) {
        val b = book
        if (b != null) {
            var title by remember { mutableStateOf(b.title) }
            var author by remember { mutableStateOf(b.author) }
            AlertDialog(
                onDismissRequest = { editing = false },
                title = { Text("Hörbuch bearbeiten") },
                text = {
                    Column {
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titel") }, singleLine = true)
                        OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Autor") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        editing = false
                        scope.launch { repo.audiobooks.update(b.copy(title = title.trim().ifBlank { b.title }, author = author.trim())) }
                    }) { Text("Speichern") }
                },
                dismissButton = { TextButton(onClick = { editing = false }) { Text("Abbrechen") } },
            )
        }
    }
    if (deleting) {
        ConfirmDialog(
            "Hörbuch entfernen?",
            "Das Hörbuch wird aus der App entfernt (die Originaldateien bleiben auf dem Gerät).",
            confirm = "Entfernen",
            onDismiss = { deleting = false },
        ) {
            deleting = false
            val b = book
            if (b != null) App.instance.appScope.launch { repo.deleteAudiobook(b) }
            onBack()
        }
    }
}
