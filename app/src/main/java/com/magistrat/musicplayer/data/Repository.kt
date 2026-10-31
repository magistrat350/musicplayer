package com.magistrat.musicplayer.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class Repository(private val context: Context, private val db: AppDatabase) {

    val tracks get() = db.tracks()
    val playlists get() = db.playlists()
    val audiobooks get() = db.audiobooks()
    val bookmarks get() = db.bookmarks()

    val musicDir: File
        get() = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music")).apply { mkdirs() }

    private val coversDir: File
        get() = File(context.filesDir, "covers").apply { mkdirs() }

    // ---------- Cover ----------

    /** Kopiert ein vom Nutzer gewaehltes Bild in den App-Speicher (verkleinert) und liefert den Pfad. */
    suspend fun saveCover(source: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bmp = context.contentResolver.openInputStream(source)?.use { decodeScaled(it.readBytes()) }
                ?: return@withContext null
            writeCover(bmp)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveCoverFile(file: File): String? = withContext(Dispatchers.IO) {
        try {
            val bmp = decodeScaled(file.readBytes()) ?: return@withContext null
            writeCover(bmp)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveCoverBytes(bytes: ByteArray): String? =
        try {
            decodeScaled(bytes)?.let { writeCover(it) }
        } catch (e: Exception) {
            null
        }

    private fun decodeScaled(bytes: ByteArray, maxSize: Int = 1024): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun writeCover(bmp: Bitmap): String {
        val f = File(coversDir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f.absolutePath
    }

    fun deleteCover(path: String?) {
        if (path != null && path.startsWith(coversDir.absolutePath)) File(path).delete()
    }

    // ---------- Songs ----------

    suspend fun setTrackCover(track: Track, image: Uri) {
        val path = saveCover(image) ?: return
        deleteCover(track.coverPath)
        tracks.update(track.copy(coverPath = path))
    }

    suspend fun removeTrackCover(track: Track) {
        deleteCover(track.coverPath)
        tracks.update(track.copy(coverPath = null))
    }

    suspend fun deleteTrack(track: Track) = withContext(Dispatchers.IO) {
        tracks.delete(track)
        deleteCover(track.coverPath)
        // Nur selbst heruntergeladene Dateien loeschen, importierte Originale bleiben
        if (track.youtubeId != null) deleteDownloadedFile(track.uri)
        else deleteDownloadedFile(track.uri, appStorageOnly = true)
    }

    /** Importiert lokale Audiodateien (z. B. vorhandene MP3s) in die Bibliothek. */
    suspend fun importSongs(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (uri in uris) {
            persist(uri)
            val meta = readMeta(uri)
            tracks.insert(
                Track(
                    title = meta.title ?: displayName(uri) ?: "Unbekannt",
                    artist = meta.artist ?: "",
                    uri = uri.toString(),
                    coverPath = meta.picture?.let { saveCoverBytes(it) },
                    durationMs = meta.durationMs,
                )
            )
            count++
        }
        count
    }

    // ---------- Playlists ----------

    suspend fun createPlaylist(name: String): Long = playlists.insert(Playlist(name = name))

    suspend fun addToPlaylist(playlistId: Long, trackId: Long) {
        playlists.addTrack(PlaylistTrack(playlistId, trackId, playlists.maxPosition(playlistId) + 1))
    }

    /** Fuegt an fester Position ein (z. B. Reihenfolge einer YouTube-Playlist). */
    suspend fun addToPlaylistAt(playlistId: Long, trackId: Long, position: Int) {
        playlists.addTrack(PlaylistTrack(playlistId, trackId, position))
    }

    suspend fun moveInPlaylist(playlistId: Long, from: Int, to: Int) {
        val ids = playlists.tracksOnce(playlistId).map { it.id }.toMutableList()
        if (from !in ids.indices || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        playlists.setOrder(playlistId, ids)
    }

    suspend fun setPlaylistCover(p: Playlist, image: Uri) {
        val path = saveCover(image) ?: return
        deleteCover(p.coverPath)
        playlists.update(p.copy(coverPath = path))
    }

    suspend fun deletePlaylist(p: Playlist) {
        playlists.delete(p)
        bookmarks.deleteForSource(SourceType.PLAYLIST, p.id)
        deleteCover(p.coverPath)
    }

    // ---------- Hoerbuecher ----------

    /** Hoerbuch aus einzelnen Dateien (Reihenfolge nach Dateiname). */
    suspend fun importAudiobookFiles(uris: List<Uri>): Long? = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext null
        val named = uris.map { it to (displayName(it) ?: it.lastPathSegment ?: "") }
            .sortedWith { a, b -> naturalCompare(a.second, b.second) }
        uris.forEach { persist(it) }
        createBook(named, fallbackTitle = null)
    }

    /** Hoerbuch aus einem ganzen Ordner (alle Audiodateien, nach Name sortiert). */
    suspend fun importAudiobookFolder(tree: Uri): Long? = withContext(Dispatchers.IO) {
        persist(tree)
        val dir = DocumentFile.fromTreeUri(context, tree) ?: return@withContext null
        val files = dir.listFiles()
            .filter { it.isFile && (it.type?.startsWith("audio/") == true || (it.name?.substringAfterLast('.')?.lowercase() ?: "") in AUDIO_EXT) }
            .map { it.uri to (it.name ?: "") }
            .sortedWith { a, b -> naturalCompare(a.second, b.second) }
        if (files.isEmpty()) return@withContext null
        createBook(files, fallbackTitle = dir.name)
    }

    private suspend fun createBook(files: List<Pair<Uri, String>>, fallbackTitle: String?): Long {
        val first = readMeta(files.first().first)
        val title = first.album ?: fallbackTitle ?: files.first().second.substringBeforeLast('.')
        val bookId = audiobooks.insert(
            Audiobook(
                title = title,
                author = first.artist ?: "",
                coverPath = first.picture?.let { saveCoverBytes(it) },
            )
        )
        val chapters = mutableListOf<Chapter>()
        files.forEachIndexed { i, (uri, name) ->
            val m = if (i == 0) first else readMeta(uri, withPicture = false)
            val fileTitle = m.title ?: name.substringBeforeLast('.')
            // m4b/m4a mit eingebetteten Kapitelmarken -> jedes Kapitel als eigener Abschnitt
            val marks = Mp4Chapters.read(context, uri)
            if (marks.size >= 2) {
                marks.forEachIndexed { j, mark ->
                    val end = marks.getOrNull(j + 1)?.startMs ?: m.durationMs.takeIf { it > mark.startMs }
                    chapters += Chapter(
                        bookId = bookId,
                        title = mark.title.ifBlank { "$fileTitle – Kapitel ${j + 1}" },
                        uri = uri.toString(),
                        durationMs = if (end != null) end - mark.startMs else 0,
                        position = chapters.size,
                        startMs = mark.startMs,
                        endMs = end,
                    )
                }
            } else {
                chapters += Chapter(
                    bookId = bookId,
                    title = fileTitle,
                    uri = uri.toString(),
                    durationMs = m.durationMs,
                    position = chapters.size,
                )
            }
        }
        audiobooks.insertChapters(chapters)
        return bookId
    }

    suspend fun setAudiobookCover(b: Audiobook, image: Uri) {
        val path = saveCover(image) ?: return
        deleteCover(b.coverPath)
        audiobooks.update(b.copy(coverPath = path))
    }

    val audiobookDir: File
        get() = File(musicDir, "audiobooks").apply { mkdirs() }

    suspend fun deleteAudiobook(b: Audiobook) = withContext(Dispatchers.IO) {
        // Von YouTube geladene Kapitel mitloeschen (importierte Originaldateien bleiben)
        audiobooks.chaptersOnce(b.id).map { it.uri }.distinct().forEach { uri ->
            deleteDownloadedFile(uri, appStorageOnly = b.sourceUrl == null)
        }
        audiobooks.delete(b)
        bookmarks.deleteForSource(SourceType.AUDIOBOOK, b.id)
        deleteCover(b.coverPath)
    }

    // ---------- Speicherort ----------

    private val storagePrefs get() = context.getSharedPreferences("storage", Context.MODE_PRIVATE)

    /** Vom Nutzer gewaehlter Download-Ordner (oder null = App-Speicher). */
    val downloadTree: Uri?
        get() = storagePrefs.getString("tree", null)?.let { Uri.parse(it) }?.takeIf { tree ->
            context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        }

    fun setDownloadTree(tree: Uri?) {
        if (tree != null) {
            context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val old = storagePrefs.getString("tree", null)?.let { Uri.parse(it) }
        storagePrefs.edit().putString("tree", tree?.toString()).apply()
        // Alte Freigabe zurueckgeben (Dateien darin bleiben natuerlich nutzbar, solange auch Lesezugriff besteht)
        if (old != null && old != tree) {
            try {
                context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
    }

    /** Lesbarer Name des gewaehlten Ordners, z. B. "MusicPlayer". */
    fun downloadFolderName(): String? = downloadTree?.let { DocumentFile.fromTreeUri(context, it)?.name }

    /**
     * Legt eine fertig heruntergeladene Datei am gewaehlten Speicherort ab.
     * Ohne gewaehlten Ordner bleibt sie im App-Speicher. Liefert die URI zum Abspielen.
     */
    suspend fun storeDownload(file: File, name: String, subPath: List<String>): String = withContext(Dispatchers.IO) {
        val tree = downloadTree ?: return@withContext Uri.fromFile(file).toString()
        try {
            val root = DocumentFile.fromTreeUri(context, tree) ?: throw IllegalStateException("Ordner nicht verfügbar")
            val dir = ensureDir(root, subPath)
            val target = dir.createFile("audio/mpeg", safeFileName(name)) ?: throw IllegalStateException("Datei konnte nicht angelegt werden")
            context.contentResolver.openOutputStream(target.uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Datei konnte nicht geschrieben werden")
            file.delete()
            target.uri.toString()
        } catch (e: Exception) {
            // Ordner nicht mehr erreichbar -> Datei bleibt im App-Speicher, nichts geht verloren
            Uri.fromFile(file).toString()
        }
    }

    private fun ensureDir(root: DocumentFile, path: List<String>): DocumentFile {
        var dir = root
        for (raw in path) {
            val name = safeFileName(raw)
            dir = dir.findFile(name)?.takeIf { it.isDirectory } ?: dir.createDirectory(name)
                ?: throw IllegalStateException("Ordner $name konnte nicht angelegt werden")
        }
        return dir
    }

    /** Dateinamen ohne verbotene Zeichen, nicht zu lang. */
    fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), " ").trim().trim('.').take(120).ifBlank { "Audio" }

    private fun isInAppStorage(uri: String): Boolean {
        val u = Uri.parse(uri)
        return u.scheme == "file" && u.path?.startsWith(musicDir.absolutePath) == true
    }

    private fun deleteDownloadedFile(uri: String, appStorageOnly: Boolean = false) {
        val u = Uri.parse(uri)
        try {
            when {
                u.scheme == "file" -> if (isInAppStorage(uri)) u.path?.let { File(it).delete() }
                u.scheme == "content" && !appStorageOnly -> DocumentsContract.deleteDocument(context.contentResolver, u)
            }
        } catch (_: Exception) {
        }
    }

    /** Anzahl heruntergeladener Dateien, die noch im (versteckten) App-Speicher liegen. */
    suspend fun countFilesInAppStorage(): Int = withContext(Dispatchers.IO) {
        tracks.allOnce().count { isInAppStorage(it.uri) } +
            audiobooks.allChaptersOnce().map { it.uri }.distinct().count { isInAppStorage(it) }
    }

    /**
     * Verschiebt alle Downloads aus dem App-Speicher in den gewaehlten Ordner.
     * Playlists, Lesezeichen und Fortschritt bleiben erhalten (die IDs aendern sich nicht).
     */
    suspend fun moveDownloadsToTree(onProgress: (done: Int, total: Int) -> Unit): Int = withContext(Dispatchers.IO) {
        if (downloadTree == null) return@withContext 0
        val trackList = tracks.allOnce().filter { isInAppStorage(it.uri) }
        val chapterGroups = audiobooks.allChaptersOnce().filter { isInAppStorage(it.uri) }.groupBy { it.uri }
        val total = trackList.size + chapterGroups.size
        var done = 0
        var moved = 0

        for (t in trackList) {
            val file = File(Uri.parse(t.uri).path!!)
            if (file.exists()) {
                val newUri = storeDownload(file, musicFileName(t.title, t.artist), listOf("Musik"))
                if (newUri != t.uri) {
                    tracks.update(t.copy(uri = newUri))
                    moved++
                }
            }
            withContext(Dispatchers.Main) { onProgress(++done, total) }
        }
        for ((uri, chapters) in chapterGroups) {
            val file = File(Uri.parse(uri).path!!)
            val first = chapters.minBy { it.position }
            val book = audiobooks.get(first.bookId)
            if (file.exists()) {
                val name = if (chapters.size > 1) (book?.title ?: first.title) else "%03d - %s".format(first.position + 1, first.title)
                val newUri = storeDownload(file, name, listOf("Hörbücher", book?.title ?: "Hörbuch"))
                if (newUri != uri) {
                    audiobooks.replaceChapterUri(uri, newUri)
                    moved++
                }
            }
            withContext(Dispatchers.Main) { onProgress(++done, total) }
        }
        moved
    }

    /** "Interpret - Titel", ausser der Titel enthaelt den Interpreten schon (typisch bei YouTube). */
    fun musicFileName(title: String, artist: String): String =
        if (artist.isBlank() || title.contains(" - ") || title.contains(artist, ignoreCase = true)) title else "$artist - $title"

    // ---------- Helfer ----------

    private fun persist(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    data class Meta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val picture: ByteArray?,
    )

    fun readMeta(uri: Uri, withPicture: Boolean = true): Meta {
        val r = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(context, uri)
            Meta(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
                artist = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.takeIf { it.isNotBlank() },
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                picture = if (withPicture) r.embeddedPicture else null,
            )
        } catch (e: Exception) {
            Meta(null, null, null, 0, null)
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav")

        /** Sortiert "Kapitel 2" vor "Kapitel 10". */
        fun naturalCompare(a: String, b: String): Int {
            val re = Regex("\\d+|\\D+")
            val pa = re.findAll(a.lowercase()).map { it.value }.toList()
            val pb = re.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    val nx = x.trimStart('0').ifEmpty { "0" }
                    val ny = y.trimStart('0').ifEmpty { "0" }
                    if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
                } else x.compareTo(y)
                if (c != 0) return c
            }
            return pa.size - pb.size
        }
    }
}
