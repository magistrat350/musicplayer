package com.magistrat.musicplayer.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magistrat.musicplayer.player.PlayerConnection
import com.magistrat.musicplayer.player.QueueEntry

/** Aktuelle Wiedergabeliste: antippen = abspielen, per Griff verschieben, entfernen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(onBack: () -> Unit) {
    val queue by PlayerConnection.queue.collectAsStateWithLifecycle()
    val state by PlayerConnection.state.collectAsStateWithLifecycle()

    val local = remember { mutableStateListOf<QueueEntry>() }
    val listState = rememberLazyListState()
    val dragState = rememberDragDropState(
        listState = listState,
        canDropOn = { true },
        onMove = { from, to ->
            val f = local.indexOfFirst { it.mediaId == from }
            val t = local.indexOfFirst { it.mediaId == to }
            if (f >= 0 && t >= 0) {
                local.add(t, local.removeAt(f))
                PlayerConnection.moveInQueue(f, t)
            }
        },
        onDrop = {},
    )
    LaunchedEffect(queue) {
        if (dragState.draggingKey == null) {
            local.clear()
            local.addAll(queue)
        }
    }
    LaunchedEffect(Unit) {
        // Zum aktuellen Titel scrollen
        if (state.index > 0) listState.scrollToItem(state.index)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Warteschlange (${local.size})") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { padding ->
        if (local.isEmpty()) {
            EmptyHint("Die Warteschlange ist leer.")
        }
        LazyColumn(
            Modifier
                .padding(padding)
                .navigationBarsPadding()
                .fillMaxSize(),
            state = listState,
        ) {
            itemsIndexed(local, key = { _, e -> e.mediaId }) { index, e ->
                MediaRow(
                    title = e.title,
                    subtitle = e.artist,
                    coverPath = e.artworkUri,
                    highlighted = index == state.index,
                    onClick = { PlayerConnection.playQueueIndex(index) },
                    modifier = Modifier.draggedItem(dragState, e.mediaId),
                    menu = listOf("Aus Warteschlange entfernen" to { PlayerConnection.removeFromQueue(index); Unit }),
                    trailing = { DragHandle(dragState, e.mediaId) },
                )
            }
        }
    }
}
