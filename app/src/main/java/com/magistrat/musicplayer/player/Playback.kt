package com.magistrat.musicplayer.player

import android.content.Context
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.data.Bookmark
import com.magistrat.musicplayer.data.SourceType
import com.magistrat.musicplayer.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Startet Bibliothek / Playlists / Hoerbuecher – auf Wunsch am zuletzt gespeicherten Stand. */
object Playback {
    private val repo get() = App.instance.repo

    /** So weit wird beim Fortsetzen eines Hoerbuchs zurueckgespult. */
    const val AUDIOBOOK_REWIND_MS = 10_000L

    private fun trackItems(source: SourceKey, tracks: List<Track>, album: String?) = tracks.map {
        buildMediaItem(ItemKey(source, it.id), it.uri, it.title, it.artist, album, it.coverPath)
    }

    suspend fun playLibrary(context: Context, startIndex: Int) {
        val source = SourceKey(SourceType.LIBRARY, 0)
        val tracks = withContext(Dispatchers.IO) { repo.tracks.allOnce() }
        PlayerConnection.play(context, source, trackItems(source, tracks, null), startIndex, 0)
    }

    suspend fun playSingleTrack(context: Context, track: Track) {
        val tracks = withContext(Dispatchers.IO) { repo.tracks.allOnce() }
        playLibrary(context, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
    }

    /**
     * @param startIndex konkreter Song; null = am letzten Stand fortsetzen (oder von vorne, falls keiner existiert)
     */
    suspend fun playPlaylist(context: Context, playlistId: Long, startIndex: Int?, fromStart: Boolean = false) {
        val source = SourceKey(SourceType.PLAYLIST, playlistId)
        val (playlist, tracks, auto) = withContext(Dispatchers.IO) {
            Triple(
                repo.playlists.get(playlistId),
                repo.playlists.tracksOnce(playlistId),
                repo.bookmarks.auto(SourceType.PLAYLIST, playlistId),
            )
        }
        if (tracks.isEmpty()) return
        var index = startIndex ?: 0
        var pos = 0L
        if (startIndex == null && !fromStart && auto != null) {
            index = resolveIndex(auto, tracks.map { it.id })
            pos = auto.positionMs
        }
        PlayerConnection.play(context, source, trackItems(source, tracks, playlist?.name), index, pos)
    }

    suspend fun playAudiobook(context: Context, bookId: Long, startIndex: Int?, fromStart: Boolean = false) {
        val source = SourceKey(SourceType.AUDIOBOOK, bookId)
        val (book, chapters, auto) = withContext(Dispatchers.IO) {
            Triple(
                repo.audiobooks.get(bookId),
                repo.audiobooks.chaptersOnce(bookId),
                repo.bookmarks.auto(SourceType.AUDIOBOOK, bookId),
            )
        }
        if (book == null || chapters.isEmpty()) return
        var index = startIndex ?: 0
        var pos = 0L
        if (startIndex == null && !fromStart && auto != null) {
            index = resolveIndex(auto, chapters.map { it.id })
            // Ein paar Sekunden zurueck, damit man wieder in den Satz hineinfindet
            pos = (auto.positionMs - AUDIOBOOK_REWIND_MS).coerceAtLeast(0)
            val ch = chapters[index]
            if (index == chapters.lastIndex && ch.durationMs > 0 && auto.positionMs >= ch.durationMs - 3_000) {
                // Bereits fertig gehoert -> von vorne
                index = 0
                pos = 0
            }
        }
        val items = chapters.map {
            buildMediaItem(ItemKey(source, it.id), it.uri, it.title, book.author.ifBlank { book.title }, book.title, book.coverPath)
        }
        PlayerConnection.play(context, source, items, index, pos)
    }

    /** Springt zu einem (manuellen) Lesezeichen. */
    suspend fun playBookmark(context: Context, bm: Bookmark) {
        val source = SourceKey(bm.sourceType, bm.sourceId)
        when (bm.sourceType) {
            SourceType.LIBRARY -> {
                val tracks = withContext(Dispatchers.IO) { repo.tracks.allOnce() }
                PlayerConnection.play(context, source, trackItems(source, tracks, null), resolveIndex(bm, tracks.map { it.id }), bm.positionMs)
            }
            SourceType.PLAYLIST -> {
                val (playlist, tracks) = withContext(Dispatchers.IO) {
                    repo.playlists.get(bm.sourceId) to repo.playlists.tracksOnce(bm.sourceId)
                }
                PlayerConnection.play(context, source, trackItems(source, tracks, playlist?.name), resolveIndex(bm, tracks.map { it.id }), bm.positionMs)
            }
            SourceType.AUDIOBOOK -> {
                val (book, chapters) = withContext(Dispatchers.IO) {
                    repo.audiobooks.get(bm.sourceId) to repo.audiobooks.chaptersOnce(bm.sourceId)
                }
                if (book == null) return
                val items = chapters.map {
                    buildMediaItem(ItemKey(source, it.id), it.uri, it.title, book.author.ifBlank { book.title }, book.title, book.coverPath)
                }
                PlayerConnection.play(context, source, items, resolveIndex(bm, chapters.map { it.id }), bm.positionMs)
            }
        }
    }

    private fun resolveIndex(bm: Bookmark, ids: List<Long>): Int {
        val byId = ids.indexOf(bm.itemId)
        return when {
            byId >= 0 -> byId
            bm.itemIndex in ids.indices -> bm.itemIndex
            else -> 0
        }
    }
}
