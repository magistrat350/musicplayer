package com.magistrat.musicplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.MainActivity
import com.magistrat.musicplayer.data.Bookmark
import com.magistrat.musicplayer.data.SourceType
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hintergrund-Wiedergabe inkl. Benachrichtigung / Sperrbildschirm-Steuerung.
 * Speichert automatisch den letzten Stand (Auto-Lesezeichen) der aktuellen Playlist bzw. des Hoerbuchs.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = MainScope()
    private var ticker: Job? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startTicker() else {
                    ticker?.cancel()
                    saveAuto()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) saveAuto()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val item = player.currentMediaItem ?: return
                    val key = ItemKey.decode(item.mediaId) ?: return
                    val app = App.instance
                    if (key.source.type == SourceType.AUDIOBOOK) {
                        // Hoerbuch fertig: Stand ans Ende setzen (zaehlt als 100 %, naechstes Mal geht's von vorne los)
                        val bm = Bookmark(
                            sourceType = key.source.type,
                            sourceId = key.source.id,
                            itemId = key.itemId,
                            itemIndex = player.currentMediaItemIndex,
                            itemTitle = item.mediaMetadata.title?.toString() ?: "",
                            positionMs = player.duration.coerceAtLeast(0),
                            isAuto = true,
                        )
                        app.appScope.launch { app.repo.bookmarks.upsertAuto(bm) }
                    } else {
                        // Playlist komplett durchgehoert -> naechstes Mal wieder von vorne
                        app.appScope.launch {
                            app.repo.bookmarks.auto(key.source.type, key.source.id)?.let { app.repo.bookmarks.delete(it) }
                        }
                    }
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // MediaItems vom Controller verlieren ihre URI -> aus requestMetadata wiederherstellen
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    val resolved = mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri
                        if (item.localConfiguration == null && uri != null) item.buildUpon().setUri(uri).build() else item
                    }.toMutableList()
                    return Futures.immediateFuture(resolved)
                }
            })
            .build()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(10_000)
                saveAuto()
            }
        }
    }

    private fun saveAuto() {
        val bm = player.snapshotBookmark(isAuto = true) ?: return
        val app = App.instance
        app.appScope.launch { app.repo.bookmarks.upsertAuto(bm) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveAuto()
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveAuto()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
