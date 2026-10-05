package com.magistrat.musicplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.data.SourceType
import com.magistrat.musicplayer.player.Playback
import kotlinx.coroutines.launch

/** "Weiterhoeren": zuletzt gehoerte Playlists und Hoerbuecher mit ihrem gespeicherten Stand. */
@Composable
fun ContinueRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recent by remember { App.instance.repo.bookmarks.recent() }.collectAsStateWithLifecycle(emptyList())
    if (recent.isEmpty()) return

    Column {
        Text(
            "Weiterhören",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(recent, key = { "${it.bookmark.sourceType}_${it.bookmark.sourceId}" }) { r ->
                val bm = r.bookmark
                val isBook = bm.sourceType == SourceType.AUDIOBOOK
                val resume = {
                    scope.launch {
                        if (isBook) Playback.playAudiobook(context, bm.sourceId, null)
                        else Playback.playPlaylist(context, bm.sourceId, null)
                    }
                    Unit
                }
                Column(
                    Modifier
                        .width(128.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClick = resume)
                ) {
                    Box {
                        Cover(
                            r.sourceCover, 128.dp,
                            icon = if (isBook) Icons.AutoMirrored.Filled.MenuBook else Icons.AutoMirrored.Filled.QueueMusic,
                        )
                        FilledIconButton(
                            onClick = resume,
                            shape = CircleShape,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .size(36.dp),
                        ) { Icon(Icons.Default.PlayArrow, "Fortsetzen") }
                    }
                    Text(r.sourceTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "${bm.itemTitle} · ${formatTime(bm.positionMs)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
