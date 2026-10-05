package com.magistrat.musicplayer.player

import android.content.Context
import androidx.media3.common.MediaItem
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

    /** Fertig zusammengestellte Wiedergabeliste inkl. Startpunkt. */
    class Prepared(val source: SourceKey, val items: List<MediaItem>, val index: Int, val positionMs: Long)

    /**
     * @param startIndex konkreter Song; null = am letzten Stand fortsetzen (oder von vorne, falls keiner existiert)
     */
    suspend fun preparePlaylist(playlistId: Long, startIndex: Int?, fromStart: Boolean = false): Prepared? {
        val source = SourceKey(SourceType.PLAYLIST, playlistId)
        val (playlist, tracks, auto) = withContext(Dispatchers.IO) {
            Triple(
                repo.playlists.get(playlistId),
                repo.playlists.tracksOnce(playlistId),
                repo.bookmarks.auto(SourceType.PLAYLIST, playlistId),
            )
        }
        if (tracks.isEmpty()) return null
        var index = startIndex ?: 0
        var pos = 0L
        if (startIndex == null && !fromStart && auto != null) {
            index = resolveIndex(auto, tracks.map { it.id })
            pos = auto.positionMs
        }
        return Prepared(source, trackItems(source, tracks, playlist?.name), index, pos)
    }

    suspend fun prepareAudiobook(bookId: Long, startIndex: Int?, fromStart: Boolean = false): Prepared? {
        val source = SourceKey(SourceType.AUDIOBOOK, bookId)
        val (book, chapters, auto) = withContext(Dispatchers.IO) {
            Triple(
                repo.audiobooks.get(bookId),
                repo.audiobooks.chaptersOnce(bookId),
                repo.bookmarks.auto(SourceType.AUDIOBOOK, bookId),
            )
        }
        if (book == null || chapters.isEmpty()) return null
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
        return Prepared(source, items, index, pos)
    }

    /** Zuletzt gehoerte Playlist bzw. zuletzt gehoertes Hoerbuch am gespeicherten Stand (fuer Widget / Medientasten). */
    suspend fun prepareMostRecent(): Prepared? {
        val recent = withContext(Dispatchers.IO) { repo.bookmarks.recentOnce() }
        for (r in recent) {
            val bm = r.bookmark
            val prepared = when (bm.sourceType) {
                SourceType.PLAYLIST -> preparePlaylist(bm.sourceId, null)
                SourceType.AUDIOBOOK -> prepareAudiobook(bm.sourceId, null)
                SourceType.LIBRARY -> null
            }
            if (prepared != null) return prepared
        }
        return null
    }

    private suspend fun play(context: Context, p: Prepared?) {
        if (p != null) PlayerConnection.play(context, p.source, p.items, p.index, p.positionMs)
    }

    suspend fun playPlaylist(context: Context, playlistId: Long, startIndex: Int?, fromStart: Boolean = false) =
        play(context, preparePlaylist(playlistId, startIndex, fromStart))

    suspend fun playAudiobook(context: Context, bookId: Long, startIndex: Int?, fromStart: Boolean = false) =
        play(context, prepareAudiobook(bookId, startIndex, fromStart))

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
