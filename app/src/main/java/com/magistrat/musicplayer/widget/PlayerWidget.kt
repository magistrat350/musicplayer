package com.magistrat.musicplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.session.MediaButtonReceiver
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.MainActivity
import com.magistrat.musicplayer.R
import com.magistrat.musicplayer.data.SourceType
import com.magistrat.musicplayer.ui.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Homescreen-Widget: zeigt, was gerade laeuft bzw. zuletzt gehoert wurde, mit Play/Pause, Zurueck, Weiter. */
class PlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        PlayerWidget.refresh(context)
    }
}

/** Was der PlaybackService gerade abspielt (null = Player leer bzw. Service beendet). */
data class NowPlaying(
    val title: String,
    val sourceTitle: String,
    val coverPath: String?,
    val isPlaying: Boolean,
    val isAudiobook: Boolean,
)

object PlayerWidget {
    @Volatile
    var nowPlaying: NowPlaying? = null
        private set

    private var job: Job? = null

    /** Vom PlaybackService aufgerufen, wenn sich etwas geaendert hat. */
    fun update(context: Context, state: NowPlaying?) {
        if (state == nowPlaying) return
        nowPlaying = state
        refresh(context)
    }

    fun refresh(context: Context) {
        val app = context.applicationContext as App
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(ComponentName(app, PlayerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        job?.cancel()
        job = app.appScope.launch(Dispatchers.IO) {
            delay(400) // mehrere schnelle Aenderungen zusammenfassen (und gerade gespeicherte Lesezeichen abwarten)
            val views = buildViews(app)
            manager.updateAppWidget(ids, views)
        }
    }

    private suspend fun buildViews(app: App): RemoteViews {
        val views = RemoteViews(app.packageName, R.layout.widget_player)
        val np = nowPlaying

        val label: String
        val title: String
        val subtitle: String
        val cover: String?
        val playing: Boolean
        if (np != null) {
            label = when {
                np.isPlaying -> "Läuft gerade"
                np.isAudiobook -> "Hörbuch · pausiert"
                else -> "Pausiert"
            }
            title = np.sourceTitle.ifBlank { np.title }
            subtitle = if (np.sourceTitle.isBlank()) "" else np.title
            cover = np.coverPath
            playing = np.isPlaying
        } else {
            val recent = app.repo.bookmarks.recentOnce().firstOrNull()
            if (recent != null) {
                val bm = recent.bookmark
                label = if (bm.sourceType == SourceType.AUDIOBOOK) "Weiterhören · Hörbuch" else "Weiterhören · Playlist"
                title = recent.sourceTitle
                subtitle = "${bm.itemTitle} · ${formatTime(bm.positionMs)}"
                cover = recent.sourceCover
            } else {
                label = "MusicPlayer"
                title = "Noch nichts gehört"
                subtitle = "Tippen, um die App zu öffnen"
                cover = null
            }
            playing = false
        }

        views.setTextViewText(R.id.widget_label, label)
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_subtitle, subtitle)
        val bmp = cover?.let { loadCover(it) }
        if (bmp != null) views.setImageViewBitmap(R.id.widget_cover, bmp)
        else views.setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_music)
        views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        views.setContentDescription(R.id.widget_play, if (playing) "Pause" else "Abspielen")

        val openApp = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_cover, openApp)
        views.setOnClickPendingIntent(R.id.widget_texts, openApp)
        // Play/Pause ueber Medientasten-Events: startet bei Bedarf den Service und setzt
        // die zuletzt gehoerte Playlist bzw. das Hoerbuch fort (onPlaybackResumption).
        views.setOnClickPendingIntent(R.id.widget_play, mediaButton(app, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.widget_next, mediaButton(app, KeyEvent.KEYCODE_MEDIA_NEXT))
        views.setOnClickPendingIntent(R.id.widget_prev, mediaButton(app, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        return views
    }

    private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        return PendingIntent.getBroadcast(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Kleines Bitmap fuers Widget (RemoteViews haben ein Groessenlimit). */
    private fun loadCover(pathOrUri: String): Bitmap? = try {
        val path = if (pathOrUri.startsWith("file:")) android.net.Uri.parse(pathOrUri).path else pathOrUri
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { Bitmap.createScaledBitmap(it, 256, 256 * it.height / it.width.coerceAtLeast(1), true) }
        }
    } catch (e: Exception) {
        null
    }
}
