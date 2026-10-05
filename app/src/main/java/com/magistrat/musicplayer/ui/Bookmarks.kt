package com.magistrat.musicplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magistrat.musicplayer.data.Bookmark
import java.text.DateFormat
import java.util.Date

/** "Fortsetzen"-Bereich mit dem automatisch gespeicherten letzten Stand. */
@Composable
fun ResumeButtons(auto: Bookmark?, enabled: Boolean, onResume: () -> Unit, onFromStart: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (auto != null) {
            Button(onClick = onResume, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.History, null)
                Text(
                    "  Fortsetzen: ${auto.itemTitle} · ${formatTime(auto.positionMs)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = onFromStart, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Von vorne abspielen") }
        } else {
            Button(onClick = onFromStart, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Abspielen") }
        }
    }
}

fun LazyListScope.bookmarkItems(
    bookmarks: List<Bookmark>,
    onPlay: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
) {
    if (bookmarks.isEmpty()) return
    item(key = "bm_header") {
        Text(
            "Lesezeichen",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(bookmarks, key = { "bm_${it.id}" }) { bm ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onPlay(bm) }
                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(bm.label ?: bm.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (bm.label != null) "${bm.itemTitle} · " else "") + formatTime(bm.positionMs) + " · " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(bm.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onDelete(bm) }) { Icon(Icons.Default.Delete, "Lesezeichen löschen") }
        }
    }
}
