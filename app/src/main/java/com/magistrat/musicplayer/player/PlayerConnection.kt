package com.magistrat.musicplayer.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.data.Bookmark
import com.magistrat.musicplayer.data.SourceType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val connected: Boolean = false,
    val hasItem: Boolean = false,
    val source: SourceKey? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val index: Int = 0,
    val count: Int = 0,
    val speed: Float = 1f,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

/** Verbindung der Oberflaeche zum PlaybackService (ueber einen MediaController). */
object PlayerConnection {
    private var controllerDeferred = CompletableDeferred<MediaController>()
    private var connecting = false
    var controller: MediaController? = null
        private set

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    fun connect(context: Context) {
        if (connecting) return
        connecting = true
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(listener)
                controllerDeferred.complete(c)
                refresh()
            } catch (e: Exception) {
                connecting = false
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private suspend fun awaitController(): MediaController = controllerDeferred.await()

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val md = item?.mediaMetadata
        _state.value = PlayerUiState(
            connected = true,
            hasItem = item != null,
            source = ItemKey.decode(item?.mediaId)?.source,
            title = md?.title?.toString() ?: "",
            artist = md?.artist?.toString() ?: "",
            album = md?.albumTitle?.toString() ?: "",
            artworkUri = md?.artworkUri?.toString(),
            isPlaying = c.isPlaying,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            index = c.currentMediaItemIndex,
            count = c.mediaItemCount,
            speed = c.playbackParameters.speed,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
    }

    fun position(): Long = controller?.currentPosition ?: 0

    // ---------- Steuerung ----------

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
            c.play()
        }
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun seekBack() = controller?.seekBack()
    fun seekForward() = controller?.seekForward()
    fun toggleShuffle() = controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = controller?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSpeed(context: Context, speed: Float) {
        val c = controller ?: return
        c.playbackParameters = PlaybackParameters(speed)
        val type = ItemKey.decode(c.currentMediaItem?.mediaId)?.source?.type ?: return
        prefs(context).edit().putFloat("speed_${type.name}", speed).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    /**
     * Startet eine neue Wiedergabeliste. Vorher wird der Stand der bisherigen Quelle
     * automatisch als Lesezeichen gespeichert.
     */
    suspend fun play(context: Context, source: SourceKey, items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) return
        connect(context)
        val c = awaitController()
        c.snapshotBookmark(isAuto = true)?.let { bm ->
            withContext(Dispatchers.IO) { App.instance.repo.bookmarks.upsertAuto(bm) }
        }
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), startPositionMs.coerceAtLeast(0))
        c.playbackParameters = PlaybackParameters(prefs(context).getFloat("speed_${source.type.name}", 1f))
        c.shuffleModeEnabled = false
        c.prepare()
        c.play()
    }

    /** Manuelles Lesezeichen an der aktuellen Stelle. */
    suspend fun addManualBookmark(label: String?): Bookmark? {
        val c = controller ?: return null
        val bm = c.snapshotBookmark(isAuto = false, label = label?.takeIf { it.isNotBlank() }) ?: return null
        val id = withContext(Dispatchers.IO) { App.instance.repo.bookmarks.insert(bm) }
        return bm.copy(id = id)
    }

    /** Speichert den aktuellen Stand sofort (z. B. wenn die App in den Hintergrund geht). */
    suspend fun saveNow() {
        val bm = controller?.snapshotBookmark(isAuto = true) ?: return
        withContext(Dispatchers.IO) { App.instance.repo.bookmarks.upsertAuto(bm) }
    }

    fun isAudiobook() = _state.value.source?.type == SourceType.AUDIOBOOK
}
