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
import java.io.File

/**
 * Laedt einen YouTube-Link als MP3 herunter (yt-dlp + ffmpeg), speichert das Vorschaubild
 * als Cover und legt den Song in der Bibliothek an (optional direkt in einer Playlist).
 */
class YoutubeDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app = context.applicationContext as App
    private val notifId = id.hashCode()

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure(workDataOf(KEY_ERROR to "Kein Link"))
        val playlistId = inputData.getLong(KEY_PLAYLIST, -1L)

        try {
            setForeground(foregroundInfo("Download wird vorbereitet…", 0, true))
        } catch (_: Exception) {
            // Foreground nicht erlaubt (z. B. aus dem Hintergrund gestartet) – Download laeuft trotzdem.
        }

        if (!app.ytdlReady.await()) {
            return Result.failure(workDataOf(KEY_ERROR to "yt-dlp konnte nicht gestartet werden"))
        }
        val ytdl = YoutubeDL.getInstance()
        val processId = id.toString()

        return try {
            withContext(Dispatchers.IO) {
                // 1) Infos holen (Titel, Kanal, ID)
                setProgress(workDataOf(KEY_TITLE to "", KEY_PROGRESS to 0f, KEY_STATUS to "Infos werden geladen…"))
                val info = runInterruptible {
                    ytdl.getInfo(YoutubeDLRequest(url).apply { addOption("--no-playlist") })
                }
                val title = info.title ?: info.fulltitle ?: "YouTube Audio"
                val artist = info.uploader ?: ""
                val ytId = info.id

                if (ytId != null) {
                    val existing = app.repo.tracks.byYoutubeId(ytId)
                    if (existing != null) {
                        if (playlistId > 0) app.repo.addToPlaylist(playlistId, existing.id)
                        return@withContext Result.success(workDataOf(KEY_TITLE to title, KEY_STATUS to "Bereits vorhanden"))
                    }
                }

                // 2) Herunterladen + nach MP3 konvertieren
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
                setProgress(workDataOf(KEY_TITLE to title, KEY_PROGRESS to 0f, KEY_STATUS to "Lädt…"))
                var lastPercent = -1
                runInterruptible {
                    ytdl.execute(request, processId) { progress, _, _ ->
                        val p = progress.toInt().coerceIn(0, 100)
                        if (p != lastPercent) {
                            lastPercent = p
                            setProgressAsync(workDataOf(KEY_TITLE to title, KEY_PROGRESS to progress, KEY_STATUS to "Lädt… $p %"))
                            notify(title, p)
                        }
                    }
                }
                setProgress(workDataOf(KEY_TITLE to title, KEY_PROGRESS to 100f, KEY_STATUS to "Wird gespeichert…"))

                val mp3 = File(outDir, "$base.mp3")
                if (!mp3.exists()) {
                    return@withContext Result.failure(workDataOf(KEY_TITLE to title, KEY_ERROR to "MP3-Datei wurde nicht erstellt"))
                }

                // 3) Vorschaubild als Cover uebernehmen
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
                if (playlistId > 0) app.repo.addToPlaylist(playlistId, trackId)
                Result.success(workDataOf(KEY_TITLE to title, KEY_STATUS to "Fertig"))
            }
        } catch (e: CancellationException) {
            ytdl.destroyProcessById(processId)
            throw e
        } catch (e: Exception) {
            ytdl.destroyProcessById(processId)
            val msg = (e.message ?: e.javaClass.simpleName).lines()
                .lastOrNull { it.contains("ERROR", ignoreCase = true) } ?: (e.message ?: e.javaClass.simpleName)
            Result.failure(workDataOf(KEY_ERROR to msg.take(400)))
        } finally {
            applicationContext.getSystemService(NotificationManager::class.java).cancel(notifId)
        }
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

    private fun foregroundInfo(text: String, percent: Int, indeterminate: Boolean): ForegroundInfo {
        val n = buildNotification(text, percent, indeterminate)
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
        const val KEY_TITLE = "title"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"

        fun enqueue(context: Context, url: String, playlistId: Long?) {
            val req = OneTimeWorkRequestBuilder<YoutubeDownloadWorker>()
                .setInputData(workDataOf(KEY_URL to url, KEY_PLAYLIST to (playlistId ?: -1L)))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .addTag("url:$url")
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
