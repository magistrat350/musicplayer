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
import com.magistrat.musicplayer.data.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val connected: Boolean = false,
    val hasItem: Boolean = false,
    val source: SourceKey? = null,
    /** Track- bzw. Kapitel-ID des aktuellen Eintrags (null bei Warteschlangen-Eintraegen) */
    val itemId: Long? = null,
    val isQueued: Boolean = false,
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

data class QueueEntry(
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUri: String?,
)

/** Schlaftimer: entweder Endzeitpunkt oder "am Ende des Titels/Kapitels". */
data class SleepTimerState(val endAt: Long? = null, val endOfItem: Boolean = false)

/** Verbindung der Oberflaeche zum PlaybackService (ueber einen MediaController). */
object PlayerConnection {
    private var controllerDeferred = CompletableDeferred<MediaController>()
    private var connecting = false
    var controller: MediaController? = null
        private set

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue

    private val _sleep = MutableStateFlow<SleepTimerState?>(null)
    val sleepTimer: StateFlow<SleepTimerState?> = _sleep
    private var sleepJob: Job? = null
    private val mainScope = MainScope()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) refreshQueue()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (_sleep.value?.endOfItem == true && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.let {
                    it.pause()
                    it.seekTo(it.currentMediaItemIndex, 0)
                }
                cancelSleepTimer()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && _sleep.value?.endOfItem == true) cancelSleepTimer()
        }
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
                refreshQueue()
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
        val key = ItemKey.decode(item?.mediaId)
        _state.value = PlayerUiState(
            connected = true,
            hasItem = item != null,
            source = key?.source,
            itemId = key?.itemId,
            isQueued = item != null && key == null,
            title = md?.title?.toString() ?: "",
            artist = md?.artist?.toString() ?: "",
            album = md?.albumTitle?.toString() ?: "",
            artworkUri = md?.artworkUri?.toString(),
            isPlaying = c.isPlaying,
            durationMs = c.duration.takeIf { it > 0 } ?: 0L,
            index = c.currentMediaItemIndex,
            count = c.mediaItemCount,
            speed = c.playbackParameters.speed,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
    }

    private fun refreshQueue() {
        val c = controller ?: return
        _queue.value = (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            QueueEntry(
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString() ?: "",
                artist = item.mediaMetadata.artist?.toString() ?: "",
                artworkUri = item.mediaMetadata.artworkUri?.toString(),
            )
        }
    }

    fun position(): Long = controller?.currentPosition ?: 0

    // ---------- Warteschlange ----------

    private fun queuedItem(track: Track) = buildMediaItem(
        mediaId = "Q:${track.id}:${System.nanoTime()}",
        uri = track.uri,
        title = track.title,
        artist = track.artist,
        album = null,
        coverPath = track.coverPath,
    )

    /** "Als Naechstes spielen": direkt hinter dem aktuellen Titel einreihen. */
    suspend fun playNext(context: Context, track: Track) {
        connect(context)
        val c = awaitController()
        if (c.mediaItemCount == 0) return startQueue(context, c, track)
        c.addMediaItem(c.currentMediaItemIndex + 1, queuedItem(track))
    }

    /** "Zur Warteschlange hinzufuegen": ans Ende anhaengen. */
    suspend fun addToQueue(context: Context, track: Track) {
        connect(context)
        val c = awaitController()
        if (c.mediaItemCount == 0) return startQueue(context, c, track)
        c.addMediaItem(queuedItem(track))
    }

    private fun startQueue(context: Context, c: MediaController, track: Track) {
        c.setMediaItems(listOf(queuedItem(track)))
        c.playbackParameters = PlaybackParameters(prefs(context).getFloat("speed_${SourceType.LIBRARY.name}", 1f))
        c.prepare()
        c.play()
    }

    fun playQueueIndex(index: Int) {
        val c = controller ?: return
        c.seekTo(index, 0)
        c.play()
    }

    fun removeFromQueue(index: Int) = controller?.removeMediaItem(index)

    fun moveInQueue(from: Int, to: Int) = controller?.moveMediaItem(from, to)

    // ---------- Schlaftimer ----------

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        val endAt = System.currentTimeMillis() + minutes * 60_000L
        _sleep.value = SleepTimerState(endAt = endAt)
        sleepJob = mainScope.launch {
            delay(minutes * 60_000L - FADE_MS)
            // Sanft ausblenden, dann pausieren
            val c = controller
            if (c != null && c.isPlaying) {
                val steps = 20
                for (i in steps downTo 1) {
                    c.volume = i / steps.toFloat()
                    delay(FADE_MS / steps)
                }
                c.pause()
                c.volume = 1f
            }
            _sleep.value = null
        }
    }

    fun startSleepTimerEndOfItem() {
        cancelSleepTimer()
        _sleep.value = SleepTimerState(endOfItem = true)
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        controller?.volume = 1f
        _sleep.value = null
    }

    private const val FADE_MS = 10_000L

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
