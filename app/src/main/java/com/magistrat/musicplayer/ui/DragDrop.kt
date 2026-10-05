package com.magistrat.musicplayer.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.zIndex

/**
 * Einfaches Drag & Drop fuer LazyColumn-Eintraege (per Griff-Symbol).
 * [canDropOn] legt fest, welche Eintraege (per Key) Ziel sein duerfen (z. B. keine Kopfzeilen).
 */
class DragDropState(
    private val listState: LazyListState,
    private val canDropOn: (Any) -> Boolean,
    private val onMove: (fromKey: Any, toKey: Any) -> Unit,
    private val onDrop: () -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    fun start(key: Any) {
        draggingKey = key
        offset = 0f
    }

    fun drag(delta: Float) {
        val key = draggingKey ?: return
        offset += delta
        val items = listState.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == key } ?: return
        val center = current.offset + offset + current.size / 2f
        val target = items.firstOrNull {
            it.key != key && canDropOn(it.key) && center >= it.offset && center <= it.offset + it.size
        } ?: return
        onMove(key, target.key)
        offset += current.offset - target.offset
    }

    fun end() {
        if (draggingKey != null) onDrop()
        draggingKey = null
        offset = 0f
    }
}

@Composable
fun rememberDragDropState(
    listState: LazyListState,
    canDropOn: (Any) -> Boolean,
    onMove: (fromKey: Any, toKey: Any) -> Unit,
    onDrop: () -> Unit,
): DragDropState = remember(listState) { DragDropState(listState, canDropOn, onMove, onDrop) }

/** Modifier fuer den gezogenen Eintrag (folgt dem Finger). */
fun Modifier.draggedItem(state: DragDropState, key: Any): Modifier =
    if (state.draggingKey == key) this.zIndex(1f).graphicsLayer { translationY = state.offset; shadowElevation = 8f } else this

/** Griff-Symbol, an dem gezogen wird. */
@Composable
fun DragHandle(state: DragDropState, key: Any) {
    Icon(
        Icons.Default.DragHandle,
        contentDescription = "Verschieben",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(8.dp)
            .pointerInput(key) {
                detectDragGestures(
                    onDragStart = { state.start(key) },
                    onDragEnd = { state.end() },
                    onDragCancel = { state.end() },
                    onDrag = { change, amount ->
                        change.consume()
                        state.drag(amount.y)
                    },
                )
            },
    )
}
