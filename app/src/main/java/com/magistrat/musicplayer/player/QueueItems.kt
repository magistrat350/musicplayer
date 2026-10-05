package com.magistrat.musicplayer.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.magistrat.musicplayer.data.Bookmark
import com.magistrat.musicplayer.data.SourceType
import java.io.File

/** Identifiziert die Quelle einer Wiedergabeliste (Bibliothek, Playlist X, Hoerbuch Y). */
data class SourceKey(val type: SourceType, val id: Long)

/** Die mediaId jedes Eintrags kodiert Quelle + Element: "PLAYLIST:5:123". */
data class ItemKey(val source: SourceKey, val itemId: Long) {
    fun encode() = "${source.type.name}:${source.id}:$itemId"

    companion object {
        fun decode(mediaId: String?): ItemKey? {
            val parts = mediaId?.split(':') ?: return null
            if (parts.size != 3) return null
            val type = runCatching { SourceType.valueOf(parts[0]) }.getOrNull() ?: return null
            val sid = parts[1].toLongOrNull() ?: return null
            val iid = parts[2].toLongOrNull() ?: return null
            return ItemKey(SourceKey(type, sid), iid)
        }
    }
}

fun buildMediaItem(
    key: ItemKey,
    uri: String,
    title: String,
    artist: String,
    album: String?,
    coverPath: String?,
): MediaItem {
    val mediaUri = Uri.parse(uri)
    return MediaItem.Builder()
        .setMediaId(key.encode())
        .setUri(mediaUri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(mediaUri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(coverPath?.let { Uri.fromFile(File(it)) })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()
}

/** Erzeugt aus dem aktuellen Player-Zustand ein Lesezeichen (oder null, wenn nichts laeuft). */
fun Player.snapshotBookmark(isAuto: Boolean, label: String? = null): Bookmark? {
    val item = currentMediaItem ?: return null
    val key = ItemKey.decode(item.mediaId) ?: return null
    if (playbackState == Player.STATE_ENDED) return null
    return Bookmark(
        sourceType = key.source.type,
        sourceId = key.source.id,
        itemId = key.itemId,
        itemIndex = currentMediaItemIndex,
        itemTitle = item.mediaMetadata.title?.toString() ?: "",
        positionMs = currentPosition.coerceAtLeast(0),
        label = label,
        isAuto = isAuto,
    )
}
