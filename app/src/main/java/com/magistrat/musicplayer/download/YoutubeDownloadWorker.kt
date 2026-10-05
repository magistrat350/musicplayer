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
 * Laedt einen YouTube-Link als MP3 herunter (yt-dlp + ffmpeg), speichert das Vorschaubild
 * als Cover und legt den Song in der Bibliothek an (optional direkt in einer Playlist).
 *
 * Mit [KEY_FULL_PLAYLIST] wird eine ganze YouTube-Playlist nacheinander geladen und
 * daraus eine Playlist in der App angelegt (Reihenfolge bleibt erhalten).
 */
class YoutubeDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app = context.applicationContext as App
    private val notifId = id.hashCode()
    private val ytdl get() = YoutubeDL.getInstance()
    private val processId = id.toString()

    private class Downloaded(val trackId: Long, val title: String, val existed: Boolean)

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure(workDataOf(KEY_ERROR to "Kein Link"))
        val playlistId = inputData.getLong(KEY_PLAYLIST, -1L)
        val fullPlaylist = inputData.getBoolean(KEY_FULL_PLAYLIST, false)

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
                if (fullPlaylist) downloadPlaylist(url, playlistId) else downloadSingle(url, playlistId)
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

    private suspend fun downloadSingle(url: String, playlistId: Long): Result {
        val d = downloadOne(url, prefix = "")
        if (playlistId > 0) app.repo.addToPlaylist(playlistId, d.trackId)
        return Result.success(workDataOf(KEY_TITLE to d.title, KEY_STATUS to if (d.existed) "Bereits vorhanden" else "Fertig"))
    }

    private suspend fun downloadPlaylist(url: String, targetPlaylistId: Long): Result {
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
        val listTitle = json.optString("title").ifBlank { "YouTube-Playlist" }
        val entries = json.optJSONArray("entries")
        val videoUrls = buildList {
            if (entries != null) for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val id = e.optString("id")
                val u = e.optString("url")
                when {
                    id.isNotBlank() -> add("https://www.youtube.com/watch?v=$id")
                    u.startsWith("http") -> add(u)
                }
            }
        }
        if (videoUrls.isEmpty()) {
            return Result.failure(workDataOf(KEY_TITLE to listTitle, KEY_ERROR to "Keine Videos in der Playlist gefunden"))
        }

        val playlistId = if (targetPlaylistId > 0) targetPlaylistId else app.repo.createPlaylist(listTitle)
        val startPos = app.repo.playlists.maxPosition(playlistId) + 1
        var ok = 0
        var failed = 0
        videoUrls.forEachIndexed { i, videoUrl ->
            val prefix = "${i + 1}/${videoUrls.size}"
            try {
                val d = downloadOne(videoUrl, prefix, listTitle)
                app.repo.addToPlaylistAt(playlistId, d.trackId, startPos + i)
                ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        val status = "$ok von ${videoUrls.size} geladen" + if (failed > 0) ", $failed fehlgeschlagen" else ""
        return if (ok == 0) {
            Result.failure(workDataOf(KEY_TITLE to listTitle, KEY_ERROR to status))
        } else {
            Result.success(workDataOf(KEY_TITLE to listTitle, KEY_STATUS to status))
        }
    }

    /** Laedt ein einzelnes Video als MP3 und legt den Song an (oder liefert den vorhandenen). */
    private suspend fun downloadOne(url: String, prefix: String, displayTitle: String? = null): Downloaded {
        val head = if (prefix.isEmpty()) "" else "$prefix · "
        report(displayTitle ?: "", 0f, "${head}Infos werden geladen…")
        val info = runInterruptible {
            ytdl.getInfo(YoutubeDLRequest(url).apply { addOption("--no-playlist") })
        }
        val title = info.title ?: info.fulltitle ?: "YouTube Audio"
        val artist = info.uploader ?: ""
        val ytId = info.id
        val shownTitle = displayTitle ?: title

        if (ytId != null) {
            app.repo.tracks.byYoutubeId(ytId)?.let { return Downloaded(it.id, title, existed = true) }
        }

        val base = "yt_${ytId ?: System.currentTimeMillis()}_${System.currentTimeMillis()}"
        val outDir = app.repo.musicDir
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--audio-quality", "0")
            addOption("--embed-metadata")
            addOption("--write-thumbnail")
            addOption("--convert-thumbnails", "jpg")
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
        val coverPath = thumb?.let { app.repo.saveCoverFile(it) }
        thumb?.delete()

        val meta = app.repo.readMeta(Uri.fromFile(mp3), withPicture = false)
        val trackId = app.repo.tracks.insert(
            Track(
                title = title,
                artist = artist,
                uri = Uri.fromFile(mp3).toString(),
                coverPath = coverPath,
                durationMs = if (meta.durationMs > 0) meta.durationMs else info.duration * 1000L,
                youtubeId = ytId,
            )
        )
        return Downloaded(trackId, title, existed = false)
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
            .setContentTitle("YouTube → MP3")
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
        const val KEY_TITLE = "title"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"

        /** true, wenn der Link auf eine YouTube-Playlist zeigt (oder ein Video innerhalb einer Playlist). */
        fun isPlaylistUrl(url: String): Boolean =
            Regex("[?&]list=[\\w-]+").containsMatchIn(url) || url.contains("/playlist")

        fun enqueue(context: Context, url: String, playlistId: Long?, fullPlaylist: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<YoutubeDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_URL to url,
                        KEY_PLAYLIST to (playlistId ?: -1L),
                        KEY_FULL_PLAYLIST to fullPlaylist,
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
