package com.magistrat.musicplayer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Durchsucht den gewaehlten Speicherort nach Audiodateien, die die App noch nicht kennt,
 * und uebernimmt sie:
 * - "Hörbücher/<Ordner>/…" -> ein Hoerbuch je Unterordner (neue Dateien in einem bekannten
 *   Hoerbuch-Ordner werden als Kapitel angehaengt; lose Dateien -> Hoerbuch mit einem Kapitel)
 * - alles andere (z. B. "Musik/", eigene Unterordner, Dateien direkt im Ordner) -> Songs
 */
class FolderScanner(private val context: Context, private val repo: Repository) {

    data class Result(val songs: Int = 0, val books: Int = 0, val chapters: Int = 0) {
        val isEmpty get() = songs == 0 && books == 0 && chapters == 0

        fun describe(): String = buildList {
            if (songs > 0) add("$songs Song(s)")
            if (books > 0) add("$books Hörbuch/Hörbücher")
            if (chapters > 0) add("$chapters Kapitel")
        }.joinToString(", ").ifEmpty { "nichts Neues" }
    }

    private data class Entry(val docId: String, val name: String, val mime: String, val uri: Uri) {
        val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
        val isAudio get() = !isDir && (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in Repository.AUDIO_EXT)
    }

    private var lastScan = 0L

    /** Wie [scan], aber hoechstens alle [minIntervalMs] (z. B. bei jedem Oeffnen der App). */
    suspend fun scanIfDue(minIntervalMs: Long = 30_000): Result =
        if (System.currentTimeMillis() - lastScan < minIntervalMs) Result() else scan()

    suspend fun scan(): Result = withContext(Dispatchers.IO) {
        lastScan = System.currentTimeMillis()
        val tree = repo.downloadTree ?: return@withContext Result()
        try {
            repo.storeLock.withLock { scanLocked(tree) }
        } catch (e: Exception) {
            Log.w("FolderScanner", "Ordner-Suche fehlgeschlagen", e)
            Result()
        }
    }

    private suspend fun scanLocked(tree: Uri): Result {
        val rootId = DocumentsContract.getTreeDocumentId(tree)

        // Bekannte Dateien (per Dokument-ID, unabhaengig von der URI-Schreibweise)
        val trackIds = repo.tracks.allOnce().mapNotNull { docId(it.uri) }.toSet()
        val chapters = repo.audiobooks.allChaptersOnce()
        val chapterIds = chapters.mapNotNull { c -> docId(c.uri)?.let { it to c.bookId } }
        val known = trackIds + chapterIds.map { it.first }

        val newSongs = mutableListOf<Entry>()
        val bookFolders = mutableListOf<Pair<Entry, List<Entry>>>()
        val looseBooks = mutableListOf<Entry>()

        for (e in list(tree, rootId)) {
            when {
                e.isDir && isAudiobookFolder(e.name) -> {
                    for (b in list(tree, e.docId)) {
                        when {
                            b.isDir -> bookFolders += b to collectAudio(tree, b, depth = 0)
                            b.isAudio -> looseBooks += b
                        }
                    }
                }
                e.isDir -> newSongs += collectAudio(tree, e, depth = 0)
                e.isAudio -> newSongs += e
            }
        }

        var songs = 0
        var books = 0
        var addedChapters = 0

        // Songs
        for (e in newSongs.filter { it.docId !in known }) {
            importSong(e)
            songs++
        }

        // Hoerbuch-Ordner
        for ((folder, files) in bookFolders) {
            val fresh = files.filter { it.docId !in known }
            if (fresh.isEmpty()) continue
            val existingBookId = chapterIds.firstOrNull { (id, _) -> id.startsWith(folder.docId + "/") }?.second
            if (existingBookId != null && repo.audiobooks.get(existingBookId) != null) {
                var pos = repo.audiobooks.maxChapterPosition(existingBookId) + 1
                for (f in fresh) {
                    val meta = repo.readMeta(f.uri, withPicture = false)
                    repo.audiobooks.insertChapter(
                        Chapter(
                            bookId = existingBookId,
                            title = meta.title ?: f.name.substringBeforeLast('.'),
                            uri = f.uri.toString(),
                            durationMs = meta.durationMs,
                            position = pos++,
                        )
                    )
                    addedChapters++
                }
            } else {
                repo.createBook(fresh.map { it.uri to it.name }, fallbackTitle = folder.name, titleOverride = folder.name)
                books++
            }
        }

        // Lose Dateien direkt in "Hörbücher"
        for (f in looseBooks.filter { it.docId !in known }) {
            repo.createBook(listOf(f.uri to f.name), fallbackTitle = f.name.substringBeforeLast('.'))
            books++
        }

        return Result(songs, books, addedChapters)
    }

    private suspend fun importSong(e: Entry) {
        val meta = repo.readMeta(e.uri)
        val base = e.name.substringBeforeLast('.')
        // Ohne Tags: "Interpret - Titel.mp3" aus dem Dateinamen lesen
        val (fileArtist, fileTitle) = base.split(" - ", limit = 2).let { if (it.size == 2) it[0].trim() to it[1].trim() else "" to base }
        repo.tracks.insert(
            Track(
                title = meta.title ?: fileTitle,
                artist = meta.artist ?: fileArtist,
                uri = e.uri.toString(),
                coverPath = meta.picture?.let { repo.saveCoverBytes(it) },
                durationMs = meta.durationMs,
            )
        )
    }

    private fun isAudiobookFolder(name: String) =
        name.equals("Hörbücher", true) || name.equals("Hoerbuecher", true) || name.equals("Audiobooks", true) || name.equals("Hörspiele", true)

    /** Alle Audiodateien eines Ordners inkl. Unterordnern (z. B. CD1/CD2), natuerlich sortiert. */
    private fun collectAudio(tree: Uri, folder: Entry, depth: Int): List<Entry> {
        if (depth > 4) return emptyList()
        val result = mutableListOf<Pair<String, Entry>>()
        for (e in list(tree, folder.docId)) {
            when {
                e.isDir -> collectAudio(tree, e, depth + 1).forEach { result += "${e.name}/${it.name}" to it }
                e.isAudio -> result += e.name to e
            }
        }
        return result.sortedWith { a, b -> Repository.naturalCompare(a.first, b.first) }.map { it.second }
    }

    private fun list(tree: Uri, parentId: String): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = mutableListOf<Entry>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue // versteckte Dateien / unfertige Downloads
                val mime = c.getString(2) ?: ""
                out += Entry(id, name, mime, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
        return out
    }

    private fun docId(uri: String): String? {
        val u = Uri.parse(uri)
        if (u.scheme != "content") return null
        return try {
            DocumentsContract.getDocumentId(u)
        } catch (e: Exception) {
            null
        }
    }
}
