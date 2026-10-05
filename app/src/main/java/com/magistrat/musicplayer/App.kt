package com.magistrat.musicplayer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.magistrat.musicplayer.data.AppDatabase
import com.magistrat.musicplayer.data.Repository
import com.magistrat.musicplayer.download.YtdlUpdater
import com.magistrat.musicplayer.widget.PlayerWidget
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var repo: Repository
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Wird abgeschlossen, sobald yt-dlp und ffmpeg einsatzbereit sind (true) oder fehlgeschlagen sind (false). */
    val ytdlReady = CompletableDeferred<Boolean>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.create(this)
        repo = Repository(this, db)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )

        PlayerWidget.refresh(this)

        appScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(this@App)
                FFmpeg.getInstance().init(this@App)
                ytdlReady.complete(true)
                YtdlUpdater.updateIfStale(this@App)
            } catch (e: Throwable) {
                Log.e("App", "yt-dlp init failed", e)
                ytdlReady.complete(false)
            }
        }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        lateinit var instance: App
            private set
    }
}
