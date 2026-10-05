package com.magistrat.musicplayer.download

import android.content.Context
import com.magistrat.musicplayer.App
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** YouTube aendert regelmaessig etwas – yt-dlp muss deshalb aktuell gehalten werden. */
object YtdlUpdater {
    private const val PREFS = "ytdl"
    private const val KEY_LAST = "last_update"

    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext as App
        if (!app.ytdlReady.await()) return@withContext "yt-dlp nicht verfügbar"
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST, System.currentTimeMillis()).apply()
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "Aktualisiert auf ${version(context)}"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Bereits aktuell (${version(context)})"
                else -> "Status: $status"
            }
        } catch (e: Exception) {
            "Update fehlgeschlagen: ${e.message}"
        }
    }

    /** Einmal pro Tag automatisch im Hintergrund aktualisieren. */
    suspend fun updateIfStale(context: Context) {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - last > 24L * 60 * 60 * 1000) update(context)
    }

    fun version(context: Context): String =
        try {
            YoutubeDL.getInstance().versionName(context) ?: YoutubeDL.getInstance().version(context) ?: "?"
        } catch (e: Exception) {
            "?"
        }
}
