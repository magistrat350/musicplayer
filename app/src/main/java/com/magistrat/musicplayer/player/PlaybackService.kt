package com.magistrat.musicplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.MainActivity
import com.magistrat.musicplayer.data.Bookmark
import com.magistrat.musicplayer.data.SourceType
import com.magistrat.musicplayer.widget.NowPlaying
import com.magistrat.musicplayer.widget.PlayerWidget
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
        // Konstante-Bitrate-Suche: genaue Spruenge in MP3-Hoerbuechern (wichtig fuer Kapitel innerhalb einer Datei)
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
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

        AudioEffects.attach(this, player.audioSessionId)

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioEffects.attach(this@PlaybackService, audioSessionId)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED,
                    )
                ) updateWidget()
            }

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

                // Play ohne Wiedergabeliste (Widget, Kopfhoerer-Taste, Sperrbildschirm nach Neustart):
                // zuletzt gehoerte Playlist bzw. Hoerbuch am gespeicherten Stand fortsetzen.
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    scope.launch {
                        val p = try {
                            Playback.prepareMostRecent()
                        } catch (e: Exception) {
                            null
                        }
                        if (p == null) {
                            future.setException(UnsupportedOperationException("Nichts zum Fortsetzen"))
                        } else {
                            val speed = getSharedPreferences("player", MODE_PRIVATE).getFloat("speed_${p.source.type.name}", 1f)
                            player.playbackParameters = PlaybackParameters(speed)
                            future.set(MediaSession.MediaItemsWithStartPosition(p.items, p.index, p.positionMs))
                        }
                    }
                    return future
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

    private fun updateWidget() {
        val item = player.currentMediaItem
        val key = ItemKey.decode(item?.mediaId)
        val state = item?.let {
            NowPlaying(
                title = it.mediaMetadata.title?.toString() ?: "",
                sourceTitle = it.mediaMetadata.albumTitle?.toString() ?: "",
                coverPath = it.mediaMetadata.artworkUri?.toString(),
                isPlaying = player.isPlaying,
                isAudiobook = key?.source?.type == SourceType.AUDIOBOOK,
            )
        }
        PlayerWidget.update(this, state)
    }

    override fun onDestroy() {
        AudioEffects.release()
        PlayerWidget.update(this, null)
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
