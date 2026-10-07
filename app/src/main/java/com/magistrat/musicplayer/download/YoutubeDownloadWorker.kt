package com.magistrat.musicplayer.download

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.data.Audiobook
import com.magistrat.musicplayer.data.Chapter
import com.magistrat.musicplayer.data.Track
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Laedt YouTube-Audio herunter (yt-dlp + ffmpeg).
 *
 * - Musik: als MP3 in die Bibliothek (optional direkt in eine Playlist).
 *   Mit [KEY_FULL_PLAYLIST] wird eine ganze YouTube-Playlist als App-Playlist angelegt.
 * - Hoerbuch ([KIND_AUDIOBOOK]): jedes Video wird ein Kapitel. Bei einer Playlist entsteht ein Hoerbuch
 *   mit allen Folgen; erneutes Laden derselben Playlist ergaenzt nur neue Folgen.
 */
class YoutubeDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app = context.applicationContext as App
    private val repo get() = app.repo
    private val notifId = id.hashCode()
    private val ytdl get() = YoutubeDL.getInstance()
    private val processId = id.toString()

    /** Heruntergeladene Audiodatei inkl. Metadaten. */
    private class Fetched(
        val title: String,
        val artist: String,
        val ytId: String?,
        val file: File,
        val coverPath: String?,
        val durationMs: Long,
        /** YouTube-Kapitel (Start in ms, Titel) – nur wenn der Abschnitt-Schnitt nicht veraendert wurde */
        val chapters: List<Pair<Long, String>>,
    )

    private class PlaylistInfo(val title: String, val uploader: String, val entries: List<Pair<String?, String>>)

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure(workDataOf(KEY_ERROR to "Kein Link"))
        val playlistId = inputData.getLong(KEY_PLAYLIST, -1L)
        val fullPlaylist = inputData.getBoolean(KEY_FULL_PLAYLIST, false)
        val audiobook = inputData.getString(KEY_KIND) == KIND_AUDIOBOOK

        try {
            setForeground(foregroundInfo("Download wird vorbereitet…"))
        } catch (_: Exception) {
            // Foreground nicht erlaubt (z. B. aus dem Hintergrund gestartet) – Download laeuft trotzdem.
        }

        if (!app.ytdlReady.await()) {
            return Result.failure(workDataOf(KEY_ERROR to "yt-dlp konnte nicht gestartet werden"))
        }

        return try {
            withContext(Dispatchers.IO) {
                when {
                    inputData.getString(KEY_KIND) == KIND_SPOTIFY -> downloadSpotify(url, playlistId)
                    audiobook -> downloadAudiobook(url, fullPlaylist)
                    fullPlaylist -> downloadMusicPlaylist(url, playlistId)
                    else -> downloadMusicSingle(url, playlistId)
                }
            }
        } catch (e: CancellationException) {
            ytdl.destroyProcessById(processId)
            throw e
        } catch (e: Exception) {
            ytdl.destroyProcessById(processId)
            Result.failure(workDataOf(KEY_ERROR to errorText(e)))
        } finally {
            applicationContext.getSystemService(NotificationManager::class.java).cancel(notifId)
        }
    }

    // ---------- Musik ----------

    private suspend fun downloadMusicSingle(url: String, playlistId: Long): Result {
        report("", 0f, "Infos werden geladen…")
        val info = runInterruptible { ytdl.getInfo(YoutubeDLRequest(url).apply { addOption("--no-playlist") }) }
        val existing = info.id?.let { repo.tracks.byYoutubeId(it) }
        val (trackId, title) = if (existing != null) {
            existing.id to existing.title
        } else {
            val f = fetch(url, prefix = "", displayTitle = null, speech = false, outDir = repo.musicDir)
            insertTrack(f) to f.title
        }
        if (playlistId > 0) repo.addToPlaylist(playlistId, trackId)
        return Result.success(workDataOf(KEY_TITLE to title, KEY_STATUS to if (existing != null) "Bereits vorhanden" else "Fertig"))
    }

    private suspend fun downloadMusicPlaylist(url: String, targetPlaylistId: Long): Result {
        val list = readPlaylist(url)
        val playlistId = if (targetPlaylistId > 0) targetPlaylistId else repo.createPlaylist(list.title)
        val startPos = repo.playlists.maxPosition(playlistId) + 1
        var ok = 0
        var failed = 0
        list.entries.forEachIndexed { i, (ytId, videoUrl) ->
            val prefix = "${i + 1}/${list.entries.size}"
            try {
                val existing = ytId?.let { repo.tracks.byYoutubeId(it) }
                val trackId = existing?.id ?: insertTrack(fetch(videoUrl, prefix, list.title, speech = false, outDir = repo.musicDir))
                repo.addToPlaylistAt(playlistId, trackId, startPos + i)
                ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        return summary(list.title, ok, failed, list.entries.size)
    }

    private suspend fun insertTrack(f: Fetched): Long = repo.tracks.insert(
        Track(
            title = f.title,
            artist = f.artist,
            uri = Uri.fromFile(f.file).toString(),
            coverPath = f.coverPath,
            durationMs = f.durationMs,
            youtubeId = f.ytId,
        )
    )

    // ---------- Hoerbuch ----------

    private suspend fun downloadAudiobook(url: String, fullPlaylist: Boolean): Result {
        val outDir = repo.audiobookDir

        if (!fullPlaylist) {
            // Einzelnes (meist langes) Video -> Hoerbuch mit einem Kapitel
            val f = fetch(url, prefix = "", displayTitle = null, speech = true, outDir = outDir)
            val bookId = repo.audiobooks.insert(
                Audiobook(title = f.title, author = f.artist, coverPath = f.coverPath, sourceUrl = url)
            )
            val fileUri = Uri.fromFile(f.file).toString()
            if (f.chapters.size >= 2) {
                // YouTube-Kapitelmarken -> echte Kapitel (Abschnitte derselben Datei)
                f.chapters.forEachIndexed { i, (start, chTitle) ->
                    val end = f.chapters.getOrNull(i + 1)?.first ?: f.durationMs.takeIf { it > start }
                    repo.audiobooks.insertChapter(
                        Chapter(
                            bookId = bookId,
                            title = chTitle.ifBlank { "Kapitel ${i + 1}" },
                            uri = fileUri,
                            durationMs = if (end != null) end - start else 0,
                            position = i,
                            youtubeId = if (i == 0) f.ytId else null,
                            startMs = start,
                            endMs = end,
                        )
                    )
                }
                return Result.success(workDataOf(KEY_TITLE to f.title, KEY_STATUS to "Als Hörbuch mit ${f.chapters.size} Kapiteln gespeichert"))
            }
            repo.audiobooks.insertChapter(
                Chapter(bookId = bookId, title = f.title, uri = fileUri, durationMs = f.durationMs, position = 0, youtubeId = f.ytId)
            )
            return Result.success(workDataOf(KEY_TITLE to f.title, KEY_STATUS to "Als Hörbuch gespeichert"))
        }

        // Einheitliche Playlist-URL, damit "Neue Folgen laden" dasselbe Hoerbuch wiederfindet
        val listUrl = canonicalPlaylistUrl(url) ?: url
        val list = readPlaylist(listUrl)
        // Gleiche Playlist schon vorhanden? Dann nur neue Folgen ergaenzen.
        val existingBook = repo.audiobooks.bySourceUrl(listUrl)
        val bookId = existingBook?.id ?: repo.audiobooks.insert(
            Audiobook(title = list.title, author = list.uploader, sourceUrl = listUrl)
        )
        val have = repo.audiobooks.youtubeIds(bookId).toSet()
        val todo = list.entries.withIndex().filter { (_, e) -> e.first.let { id -> id == null || id !in have } }
        if (todo.isEmpty()) {
            return Result.success(workDataOf(KEY_TITLE to list.title, KEY_STATUS to "Keine neuen Folgen"))
        }
        // Neue Folgen hinter die vorhandenen haengen, Playlist-Reihenfolge beibehalten
        val basePos = if (existingBook != null) repo.audiobooks.maxChapterPosition(bookId) + 1 else 0

        var ok = 0
        var failed = 0
        todo.forEachIndexed { n, (_, entry) ->
            val prefix = "${n + 1}/${todo.size}"
            try {
                val f = fetch(entry.second, prefix, list.title, speech = true, outDir = outDir)
                repo.audiobooks.insertChapter(
                    Chapter(
                        bookId = bookId,
                        title = f.title,
                        uri = Uri.fromFile(f.file).toString(),
                        durationMs = f.durationMs,
                        position = basePos + n,
                        youtubeId = f.ytId ?: entry.first,
                    )
                )
                // Erstes Vorschaubild wird zum Hoerbuch-Cover
                val book = repo.audiobooks.get(bookId)
                if (book != null && book.coverPath == null && f.coverPath != null) {
                    repo.audiobooks.update(book.copy(coverPath = f.coverPath))
                } else {
                    repo.deleteCover(f.coverPath)
                }
                ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        if (ok == 0 && existingBook == null) repo.audiobooks.get(bookId)?.let { repo.audiobooks.delete(it) }
        return summary(list.title, ok, failed, todo.size, unit = if (existingBook != null) "neuen Folgen" else "Folgen")
    }

    // ---------- Spotify ----------

    /**
     * Uebernimmt eine Spotify-Playlist (bzw. Album/Song): jeder Titel wird auf YouTube gesucht,
     * der beste Treffer als MP3 geladen und in der Spotify-Reihenfolge in eine App-Playlist gelegt.
     * Erneuter Aufruf mit demselben Link ergaenzt nur fehlende Titel.
     */
    private suspend fun downloadSpotify(url: String, targetPlaylistId: Long): Result {
        report("", 0f, "Spotify-Playlist wird gelesen…")
        val canon = Spotify.canonical(url) ?: url
        val list = Spotify.fetch(canon)

        val existing = if (targetPlaylistId > 0) repo.playlists.get(targetPlaylistId) else repo.playlists.bySourceUrl(canon)
        val playlistId = existing?.id ?: repo.playlists.insert(
            com.magistrat.musicplayer.data.Playlist(name = list.name, sourceUrl = canon)
        )
        if (existing == null && list.coverUrl != null) {
            downloadCover(list.coverUrl)?.let { path ->
                repo.playlists.get(playlistId)?.let { repo.playlists.update(it.copy(coverPath = path)) }
            }
        }
        val inPlaylist = repo.playlists.tracksOnce(playlistId)
            .map { it.title.lowercase() to it.artist.lowercase() }.toSet()
        val basePos = repo.playlists.maxPosition(playlistId) + 1

        var ok = 0
        var skipped = 0
        val notFound = mutableListOf<String>()
        list.tracks.forEachIndexed { i, t ->
            val prefix = "${i + 1}/${list.tracks.size}"
            if ((t.title.lowercase() to t.artists.lowercase()) in inPlaylist) {
                skipped++
                return@forEachIndexed
            }
            try {
                val trackId = repo.tracks.byTitleArtist(t.title, t.artists)?.id ?: run {
                    report(list.name, 0f, "$prefix · ${t.mainArtist} – ${t.title}: suche auf YouTube…")
                    val videoId = searchYoutube(t) ?: throw IllegalStateException("nicht gefunden")
                    insertTrack(
                        fetch(
                            "https://www.youtube.com/watch?v=$videoId", prefix, list.name,
                            speech = false, outDir = repo.musicDir,
                            titleOverride = t.title, artistOverride = t.artists,
                        )
                    )
                }
                repo.addToPlaylistAt(playlistId, trackId, if (existing == null) i else basePos + ok)
                ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notFound += "${t.mainArtist} – ${t.title}"
            }
        }

        var status = when {
            ok == 0 && notFound.isEmpty() -> "Alles schon vorhanden"
            existing != null -> "$ok neue Titel übernommen"
            else -> "$ok von ${list.tracks.size} Titeln übernommen"
        }
        if (notFound.isNotEmpty()) {
            status += ", nicht gefunden: " + notFound.take(5).joinToString("; ") + if (notFound.size > 5) " …" else ""
        }
        if (skipped > 0 && existing != null) status += " ($skipped schon vorhanden)"
        return if (ok == 0 && notFound.isNotEmpty() && skipped == 0) {
            Result.failure(workDataOf(KEY_TITLE to list.name, KEY_ERROR to status))
        } else {
            Result.success(workDataOf(KEY_TITLE to list.name, KEY_STATUS to status))
        }
    }

    /** YouTube-Suche (5 Treffer) und Auswahl des passendsten Videos. */
    private suspend fun searchYoutube(t: SpotifyTrack): String? {
        val query = "${t.mainArtist} - ${t.title}".replace("\"", "")
        val response = runInterruptible {
            ytdl.execute(
                YoutubeDLRequest("ytsearch6:$query").apply {
                    addOption("--flat-playlist")
                    addOption("-J")
                },
                processId,
            )
        }
        val entries = JSONObject(response.out).optJSONArray("entries") ?: return null
        val candidates = (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Spotify.Candidate(
                id = id,
                title = e.optString("title"),
                channel = e.optString("channel").ifBlank { e.optString("uploader") },
                durationSec = e.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it },
            )
        }
        return candidates.maxByOrNull { Spotify.score(it, t) }?.id
    }

    private suspend fun downloadCover(url: String): String? = try {
        val tmp = File(applicationContext.cacheDir, "spotify_cover.jpg")
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = 15_000
            readTimeout = 15_000
            try {
                inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                disconnect()
            }
        }
        repo.saveCoverFile(tmp).also { tmp.delete() }
    } catch (e: Exception) {
        null
    }

    // ---------- yt-dlp ----------

    private suspend fun readPlaylist(url: String): PlaylistInfo {
        report("", 0f, "Playlist wird gelesen…")
        val response = runInterruptible {
            ytdl.execute(
                YoutubeDLRequest(url).apply {
                    addOption("--flat-playlist")
                    addOption("--yes-playlist")
                    addOption("-J")
                },
                processId,
            )
        }
        val json = JSONObject(response.out)
        val title = json.optString("title").ifBlank { "YouTube-Playlist" }
        val uploader = json.optString("uploader").ifBlank { json.optString("channel") }
        val arr = json.optJSONArray("entries")
        val entries = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val id = e.optString("id").takeIf { it.isNotBlank() }
                val u = e.optString("url")
                when {
                    id != null -> add(id to "https://www.youtube.com/watch?v=$id")
                    u.startsWith("http") -> add(null to u)
                }
            }
        }
        if (entries.isEmpty()) throw IllegalStateException("Keine Videos in der Playlist gefunden")
        return PlaylistInfo(title, uploader, entries)
    }

    /**
     * Laedt ein einzelnes Video als MP3. [speech] = Hoerbuch/Podcast: geringere Bitrate
     * (spart viel Speicher bei langen Aufnahmen, fuer Sprache voellig ausreichend).
     */
    private suspend fun fetch(
        url: String,
        prefix: String,
        displayTitle: String?,
        speech: Boolean,
        outDir: File,
        titleOverride: String? = null,
        artistOverride: String? = null,
    ): Fetched {
        val head = if (prefix.isEmpty()) "" else "$prefix · "
        report(displayTitle ?: "", 0f, "${head}Infos werden geladen…")
        val info = JSONObject(
            runInterruptible {
                ytdl.execute(YoutubeDLRequest(url).apply { addOption("--no-playlist"); addOption("-J") }, processId)
            }.out
        )
        val title = titleOverride ?: info.optString("title").ifBlank { info.optString("fulltitle") }.ifBlank { "YouTube Audio" }
        val artist = artistOverride ?: info.optString("uploader").ifBlank { info.optString("channel") }
        val ytId = info.optString("id").takeIf { it.isNotBlank() }
        val infoDurationMs = (info.optDouble("duration", 0.0).takeIf { !it.isNaN() } ?: 0.0).times(1000).toLong()
        val shownTitle = displayTitle ?: title
        val ytChapters = info.optJSONArray("chapters")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { c -> (c.optDouble("start_time", 0.0) * 1000).toLong() to c.optString("title") }
            }
        }.orEmpty()
        // Bei Hoerbuechern mit Kapiteln nichts herausschneiden, sonst passen die Kapitelzeiten nicht mehr
        val keepChapters = speech && ytChapters.size >= 2
        val opts = DownloadSettings.get(applicationContext)

        val base = "yt_${ytId ?: System.currentTimeMillis()}_${System.currentTimeMillis()}"
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--audio-quality", if (speech) "96K" else "0")
            addOption("--embed-metadata")
            addOption("--write-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            if (opts.sponsorBlock && !keepChapters) {
                // Werbung, Eigenwerbung, "Abonniert"-Aufrufe und bei Musik die Nicht-Musik-Teile entfernen
                addOption("--sponsorblock-remove", if (speech) "sponsor,selfpromo,interaction" else "sponsor,selfpromo,interaction,music_offtopic")
            }
            if (opts.normalize) {
                // Einheitliche Lautheit (EBU R128); Sprache etwas leiser als Musik
                val target = if (speech) "I=-16:TP=-1.5:LRA=11" else "I=-14:TP=-1.5:LRA=11"
                addOption("--postprocessor-args", "ExtractAudio+ffmpeg_o:-af loudnorm=$target -ar 44100")
            }
            addOption("-o", "${outDir.absolutePath}/$base.%(ext)s")
        }
        report(shownTitle, 0f, "${head}$title – lädt…")
        var lastPercent = -1
        runInterruptible {
            ytdl.execute(request, processId) { progress, _, _ ->
                val p = progress.toInt().coerceIn(0, 100)
                if (p != lastPercent) {
                    lastPercent = p
                    setProgressAsync(workDataOf(KEY_TITLE to shownTitle, KEY_PROGRESS to progress, KEY_STATUS to "${head}$title – $p %"))
                    notify(if (prefix.isEmpty()) title else "$prefix $title", p)
                }
            }
        }
        report(shownTitle, 100f, "${head}$title – wird gespeichert…")

        val mp3 = File(outDir, "$base.mp3")
        if (!mp3.exists()) throw IllegalStateException("MP3-Datei wurde nicht erstellt")

        val thumb = outDir.listFiles()?.firstOrNull { it.name.startsWith(base) && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
        val coverPath = thumb?.let { repo.saveCoverFile(it) }
        thumb?.delete()

        val meta = repo.readMeta(Uri.fromFile(mp3), withPicture = false)
        return Fetched(
            title = title,
            artist = artist,
            ytId = ytId,
            file = mp3,
            coverPath = coverPath,
            durationMs = if (meta.durationMs > 0) meta.durationMs else infoDurationMs,
            chapters = if (keepChapters) ytChapters else emptyList(),
        )
    }

    private fun summary(title: String, ok: Int, failed: Int, total: Int, unit: String = "Titel"): Result {
        val status = "$ok von $total $unit geladen" + if (failed > 0) ", $failed fehlgeschlagen" else ""
        return if (ok == 0) {
            Result.failure(workDataOf(KEY_TITLE to title, KEY_ERROR to status))
        } else {
            Result.success(workDataOf(KEY_TITLE to title, KEY_STATUS to status))
        }
    }

    private suspend fun report(title: String, progress: Float, status: String) {
        setProgress(workDataOf(KEY_TITLE to title, KEY_PROGRESS to progress, KEY_STATUS to status))
    }

    private fun errorText(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return (msg.lines().lastOrNull { it.contains("ERROR", ignoreCase = true) } ?: msg).take(400)
    }

    private fun buildNotification(text: String, percent: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(applicationContext, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YouTube-Download")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, indeterminate)
            .build()

    private fun foregroundInfo(text: String): ForegroundInfo {
        val n = buildNotification(text, 0, true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
    }

    private fun notify(title: String, percent: Int) {
        try {
            applicationContext.getSystemService(NotificationManager::class.java)
                .notify(notifId, buildNotification(title, percent, false))
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val TAG = "yt_download"
        const val KEY_URL = "url"
        const val KEY_PLAYLIST = "playlist"
        const val KEY_FULL_PLAYLIST = "full_playlist"
        const val KEY_KIND = "kind"
        const val KEY_TITLE = "title"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"

        const val KIND_MUSIC = "music"
        const val KIND_AUDIOBOOK = "audiobook"
        const val KIND_SPOTIFY = "spotify"

        /** true, wenn der Link auf eine YouTube-Playlist zeigt (oder ein Video innerhalb einer Playlist). */
        fun isPlaylistUrl(url: String): Boolean =
            Regex("[?&]list=[\\w-]+").containsMatchIn(url) || url.contains("/playlist")

        /** https://www.youtube.com/playlist?list=ID aus beliebigem Link mit list=-Parameter. */
        fun canonicalPlaylistUrl(url: String): String? =
            Regex("[?&]list=([\\w-]+)").find(url)?.groupValues?.get(1)?.let { "https://www.youtube.com/playlist?list=$it" }

        /** YouTube-Mixe ("Radio", list=RD...) sind automatisch erzeugt und quasi endlos. */
        fun isMixUrl(url: String): Boolean = Regex("[?&]list=RD").containsMatchIn(url)

        fun enqueue(
            context: Context,
            url: String,
            playlistId: Long?,
            fullPlaylist: Boolean = false,
            kind: String = KIND_MUSIC,
        ) {
            val req = OneTimeWorkRequestBuilder<YoutubeDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_URL to url,
                        KEY_PLAYLIST to (playlistId ?: -1L),
                        KEY_FULL_PLAYLIST to fullPlaylist,
                        KEY_KIND to kind,
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .addTag("url:$url")
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
