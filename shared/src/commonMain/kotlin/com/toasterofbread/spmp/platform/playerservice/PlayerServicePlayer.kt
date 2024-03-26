package com.toasterofbread.spmp.platform.playerservice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.sqldelight.Query
import com.toasterofbread.composekit.platform.Platform
import com.toasterofbread.composekit.platform.PlatformPreferences
import com.toasterofbread.composekit.platform.PlatformPreferencesListener
import com.toasterofbread.spmp.ProjectBuildConfig
import com.toasterofbread.spmp.model.mediaitem.MediaItem
import com.toasterofbread.spmp.model.mediaitem.db.incrementPlayCount
import com.toasterofbread.spmp.model.mediaitem.song.Song
import com.toasterofbread.spmp.model.mediaitem.getUid
import com.toasterofbread.spmp.model.mediaitem.playlist.LocalPlaylist
import com.toasterofbread.spmp.model.mediaitem.playlist.RemotePlaylist
import com.toasterofbread.spmp.model.mediaitem.playlist.RemotePlaylistData
import com.toasterofbread.spmp.model.settings.category.DiscordAuthSettings
import com.toasterofbread.spmp.model.settings.category.MiscSettings
import com.toasterofbread.spmp.model.settings.category.SystemSettings
import com.toasterofbread.spmp.platform.AppContext
import com.toasterofbread.spmp.platform.PlayerListener
import com.toasterofbread.spmp.service.playercontroller.DiscordStatusHandler
import com.toasterofbread.spmp.service.playercontroller.PersistentQueueHandler
import com.toasterofbread.spmp.service.playercontroller.RadioHandler
import com.toasterofbread.spmp.model.radio.RadioInstance
import com.toasterofbread.spmp.model.radio.RadioState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import spms.socketapi.shared.SpMsPlayerRepeatMode
import spms.socketapi.shared.SpMsPlayerState
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import kotlin.random.Random
import kotlin.random.nextInt

private const val UPDATE_INTERVAL: Long = 30000 // ms
//private const val VOL_NOTIF_SHOW_DURATION: Long = 1000
private const val SONG_MARK_WATCHED_POSITION = 1000 // ms

@Suppress("LeakingThis")
abstract class PlayerServicePlayer(private val service: PlatformPlayerService) {
    private val context: AppContext get() = service.context
    private val coroutine_scope: CoroutineScope = CoroutineScope(Dispatchers.Main)

    internal val radio: RadioHandler = RadioHandler(this, context)
    private val persistent_queue: PersistentQueueHandler = PersistentQueueHandler(this, context)
    private val discord_status: DiscordStatusHandler = DiscordStatusHandler(this, context)
    private val undo_handler: UndoHandler = UndoHandler(this, service)
    private var update_timer: Timer? = null

    private var tracking_song_index = 0
    private var song_marked_as_watched: Boolean = false

    val undo_count: Int get() = undo_handler.undo_count
    val redo_count: Int get() = undo_handler.redo_count

    var stop_after_current_song: Boolean by mutableStateOf(false)
    var session_started: Boolean by mutableStateOf(false)
    var active_queue_index: Int by mutableIntStateOf(0)

    fun redo() = undo_handler.redo()
    fun redoAll() = undo_handler.redoAll()
    fun undo() = undo_handler.undo()
    fun undoAll() = undo_handler.undoAll()

    abstract fun onUndoStateChanged()

    private val prefs_listener = object : PlatformPreferencesListener {
        override fun onChanged(prefs: PlatformPreferences, key: String) {
            when (key) {
                DiscordAuthSettings.Key.DISCORD_ACCOUNT_TOKEN.getName() -> {
                    discord_status.onDiscordAccountTokenChanged()
                }
//                Settings.KEY_ACC_VOL_INTERCEPT_NOTIFICATION.name -> {
//                    vol_notif_enabled = Settings.KEY_ACC_VOL_INTERCEPT_NOTIFICATION.get(preferences = prefs)
//                }
            }
        }
    }

    private val player_listener = object : PlayerListener() {
        var current_song: Song? = null

        val song_metadata_listener = Query.Listener {
            discord_status.updateDiscordStatus(current_song)
        }

        override fun onSongTransition(song: Song?, manual: Boolean) {
            if (manual && stop_after_current_song) {
                stop_after_current_song = false
            }

            with(context.database) {
                current_song?.also { current ->
                    mediaItemQueries.titleById(current.id).removeListener(song_metadata_listener)
                    songQueries.artistById(current.id).removeListener(song_metadata_listener)
                }
                current_song = song
                current_song?.also { current ->
                    mediaItemQueries.titleById(current.id).addListener(song_metadata_listener)
                    songQueries.artistById(current.id).addListener(song_metadata_listener)
                }
            }

            coroutine_scope.launch {
                persistent_queue.savePersistentQueue()
            }

            if (current_song_index == tracking_song_index + 1) {
                onSongEnded()
            }
            tracking_song_index = current_song_index
            song_marked_as_watched = false

            radio.checkAutoRadioContinuation()
            discord_status.updateDiscordStatus(song)

            coroutine_scope.launch {
                sendStatusWebhook(song)
            }

            if (manual) {
                play()
            }
        }

        private suspend fun sendStatusWebhook(song: Song?): Result<Unit> = runCatching {
            val webhook_url: String? = MiscSettings.Key.STATUS_WEBHOOK_URL.get(context)
            if (webhook_url.isNullOrBlank()) {
                return@runCatching
            }

            val payload: MutableMap<String, JsonElement>

            val user_payload: String? = MiscSettings.Key.STATUS_WEBHOOK_PAYLOAD.get(context)
            if (!user_payload.isNullOrBlank()) {
                payload =
                    try {
                        Json.decodeFromString(user_payload)
                    }
                    catch (e: Throwable) {
                        e.printStackTrace()
                        mutableMapOf()
                    }
            }
            else {
                payload = mutableMapOf()
            }

            payload["youtube_video_id"] = JsonPrimitive(song?.id)

            val response: HttpResponse =
                HttpClient(CIO).post(webhook_url) {
                    setBody(payload)
                }

            if (response.status.value !in 200 .. 299) {
                throw IOException("${response.status.value}: ${response.bodyAsText()}")
            }
        }

        override fun onSongMoved(from: Int, to: Int) {
            radio.instance.onQueueSongMoved(from, to)
            radio.checkAutoRadioContinuation()
        }

        override fun onSongRemoved(index: Int, song: Song) {
            radio.instance.onQueueSongRemoved(index, song)
            radio.checkAutoRadioContinuation()
        }

        override fun onStateChanged(state: SpMsPlayerState) {
            if (state == SpMsPlayerState.ENDED) {
                onSongEnded()
            }
        }

        override fun onSongAdded(index: Int, song: Song) {}
    }

    private fun onSongEnded() {
        if (stop_after_current_song) {
            pause()
            stop_after_current_song = false
        }
    }

    init {
        if (ProjectBuildConfig.MUTE_PLAYER == true && !Platform.DESKTOP.isCurrent()) {
            service.volume = 0f
        }

        service.addListener(player_listener)
        context.getPrefs().addListener(prefs_listener)
        discord_status.onDiscordAccountTokenChanged()

        if (update_timer == null) {
            update_timer = createUpdateTimer()
        }

        coroutine_scope.launch {
            persistent_queue.loadPersistentQueue()
        }
    }

    fun release() {
        update_timer?.cancel()
        update_timer = null

        service.removeListener(player_listener)
        context.getPrefs().removeListener(prefs_listener)
        discord_status.release()
    }

    fun updateActiveQueueIndex(delta: Int = 0) {
        if (delta != 0) {
            setActiveQueueIndex(active_queue_index + delta)
        }
        else if (active_queue_index >= service.song_count) {
            active_queue_index = service.current_song_index
        }
    }

    fun setActiveQueueIndex(value: Int) {
        active_queue_index = value.coerceAtLeast(service.current_song_index).coerceAtMost(service.song_count - 1)
    }

    fun cancelSession() {
        pause()
        clearQueue()
        session_started = false
    }

    fun playSong(song: Song, start_radio: Boolean = true, shuffle: Boolean = false, at_index: Int = 0) {
        require(start_radio || !shuffle)
        require(at_index >= 0)

        undo_handler.undoableAction(song_count > 0) {
            if (at_index == 0 && song.id == service.getSong()?.id && start_radio) {
                clearQueue(keep_current = true, save = false)
            }
            else {
                clearQueue(at_index, keep_current = false, save = false, cancel_radio = !start_radio)
                addToQueue(song, at_index)

                if (!start_radio) {
                    return@undoableAction
                }
            }

            startRadioAtIndex(
                at_index + 1,
                song,
                at_index,
                skip_first = true,
                shuffle = shuffle
            )
        }
    }

    fun startRadioAtIndex(
        index: Int,
        item: MediaItem? = null,
        item_index: Int? = null,
        skip_first: Boolean = false,
        shuffle: Boolean = false,
        onSuccessfulLoad: () -> Unit = {}
    ) {
        require(item_index == null || item != null)

        synchronized(radio) {
            val final_item: MediaItem = item ?: getSong(index)!!
            val final_index: Int? = if (item != null) item_index else index

            coroutine_scope.launch {
                if (final_item !is Song) {
                    final_item.incrementPlayCount(context)
                }

                val playlist_data: RemotePlaylistData? =
                    (final_item as? RemotePlaylist)?.loadData(context)?.getOrNull()

                undo_handler.customUndoableAction { furtherAction ->
                    if (playlist_data == null || playlist_data?.continuation != null) {
                        clearQueue(from = index, keep_current = false, save = false, cancel_radio = false)
                    }

                    return@customUndoableAction radio.setUndoableRadioState(
                        RadioState(
                            item_uid = final_item.getUid(),
                            item_queue_index = final_index,
                            shuffle = shuffle
                        ),
                        furtherAction = { a: PlayerServicePlayer.() -> UndoRedoAction? ->
                            furtherAction {
                                a()
                            }
                        },
                        onSuccessfulLoad = onSuccessfulLoad,
                        insertion_index = index
                    )
                }
            }
        }
    }

    fun clearQueue(from: Int = 0, keep_current: Boolean = false, save: Boolean = true, cancel_radio: Boolean = true) {
        if (cancel_radio) {
            radio.instance.cancelRadio()
        }

        undo_handler.undoableAction(null) {
            for (i in song_count - 1 downTo from) {
                if (keep_current && i == current_song_index) {
                    continue
                }
                removeFromQueue(i, save = false)
            }
        }

        if (save) {
            savePersistentQueue()
        }

        updateActiveQueueIndex()
    }

    fun shuffleQueue(start: Int = 0, end: Int = -1) {
        require(start >= 0)

        val shuffle_end = if (end < 0) song_count -1 else end
        val range: IntRange =
            if (song_count - start <= 1) {
                return
            }
            else {
                start..shuffle_end
            }
        shuffleQueue(range)
    }

    private fun shuffleQueue(range: IntRange) {
        undo_handler.undoableAction(null) {
            for (i in range) {
                val swap = Random.nextInt(range)
                swapQueuePositions(i, swap, false)
            }
        }
        savePersistentQueue()
    }

    fun shuffleQueueIndices(indices: List<Int>) {
        undo_handler.undoableAction(null) {
            for (i in indices.withIndex()) {
                val swap_index = Random.nextInt(indices.size)
                swapQueuePositions(i.value, indices[swap_index], false)
            }
        }
        savePersistentQueue()
    }

    fun swapQueuePositions(a: Int, b: Int, save: Boolean = true) {
        if (a == b) {
            return
        }

        assert(a in 0 until song_count)
        assert(b in 0 until song_count)

        val offset_b = b + (if (b > a) -1 else 1)

        undo_handler.undoableAction(null) {
            performAction(UndoHandler.MoveAction(a, b))
            performAction(UndoHandler.MoveAction(offset_b, a))
        }

        if (save) {
            savePersistentQueue()
        }
    }

    fun addToQueue(song: Song, index: Int? = null, is_active_queue: Boolean = false, start_radio: Boolean = false, save: Boolean = true): Int {
        val add_to_index: Int
        if (index == null) {
            add_to_index = (song_count - 1).coerceAtLeast(0)
        }
        else {
            add_to_index = if (index < song_count) index else (song_count - 1).coerceAtLeast(0)
        }

        if (is_active_queue) {
            active_queue_index = add_to_index
        }

        undo_handler.customUndoableAction(null) { furtherAction ->
            performAction(UndoHandler.AddAction(song, add_to_index))
            if (start_radio) {
                clearQueue(add_to_index + 1, save = false, cancel_radio = false)

                synchronized(radio) {
                    return@customUndoableAction radio.setUndoableRadioState(
                        RadioState(
                            item_uid = song.getUid(),
                            item_queue_index = add_to_index
                        ),
                        furtherAction = { a ->
                            furtherAction {
                                a()
                            }
                        }
                    )
                }
            }
            else if (save) {
                savePersistentQueue()
            }

            return@customUndoableAction null
        }

        return add_to_index
    }

    fun addMultipleToQueue(
        songs: List<Song>,
        index: Int = 0,
        skip_first: Boolean = false,
        save: Boolean = true,
        is_active_queue: Boolean = false,
        skip_existing: Boolean = false,
        clear: Boolean = false
    ) {
        val to_add: List<Song> =
            if (!skip_existing) {
                songs
            }
            else {
                songs.toMutableList().apply {
                    iterateSongs { _, song ->
                        removeAll { it.id == song.id }
                    }
                }
            }

        if (to_add.isEmpty()) {
            return
        }

        val index_offset = if (skip_first) -1 else 0

        undo_handler.undoableAction(null) {
            if (clear) {
                clearQueue(save = false)
            }

            for (song in to_add.withIndex()) {
                if (skip_first && song.index == 0) {
                    continue
                }

                val item_index = index + song.index + index_offset
                performAction(UndoHandler.AddAction(song.value, item_index))
            }
        }

        if (is_active_queue) {
            active_queue_index = index + to_add.size - 1 + index_offset
        }

        if (save) {
            savePersistentQueue()
        }
    }

    fun moveSong(from: Int, to: Int) {
        undo_handler.undoableAction(null) {
            performAction(UndoHandler.MoveAction(from, to))
        }
    }

    fun removeFromQueue(index: Int, save: Boolean = true): Song {
        val song = getSong(index)!!

        undo_handler.performAction(UndoHandler.RemoveAction(index))

        if (save) {
            savePersistentQueue()
        }
        return song
    }

    fun seekBy(delta_ms: Long) {
        seekTo((current_position_ms + delta_ms).coerceIn(0, duration_ms))
    }

    inline fun iterateSongs(action: (i: Int, song: Song) -> Unit) {
        for (i in 0 until song_count) {
            action(i, getSong(i)!!)
        }
    }

    private fun createUpdateTimer(): Timer {
        return Timer().apply {
            scheduleAtFixedRate(
                object : TimerTask() {
                    override fun run() {
                        coroutine_scope.launch(Dispatchers.Main) {
                            savePersistentQueue()
                            markWatched()
                        }
                    }

                    suspend fun markWatched() = withContext(Dispatchers.Main) {
                        if (
                            !song_marked_as_watched
                            && is_playing
                            && current_position_ms >= SONG_MARK_WATCHED_POSITION
                        ) {
                            song_marked_as_watched = true

                            val song: Song = getSong() ?: return@withContext

                            withContext(Dispatchers.IO) {
                                song.incrementPlayCount(context)

                                val mark_endpoint = context.ytapi.user_auth_state?.MarkSongAsWatched
                                if (mark_endpoint?.isImplemented() == true && SystemSettings.Key.ADD_SONGS_TO_HISTORY.get(context)) {
                                    val result = mark_endpoint.markSongAsWatched(song.id)
                                    result.onFailure {
                                        context.sendNotification(it)
                                    }
                                }
                            }
                        }
                    }
                },
                0,
                UPDATE_INTERVAL
            )
        }
    }

    // --- UndoHandler ---

    fun undoableAction(action: PlayerServicePlayer.(furtherAction: (PlayerServicePlayer.() -> Unit) -> Unit) -> Unit) {
        undo_handler.undoableAction { a ->
            action { b ->
                a {
                    b()
                }
            }
        }
    }

    fun customUndoableAction(action: PlayerServicePlayer.(furtherAction: (PlayerServicePlayer.() -> UndoRedoAction?) -> Unit) -> UndoRedoAction?) =
        undo_handler.customUndoableAction { a: (UndoHandler.() -> UndoRedoAction?) -> Unit ->
            action(player) { b: PlayerServicePlayer.() -> UndoRedoAction? ->
                a {
                    b()
                }
            }
        }

    // --- Service ---

    val state: SpMsPlayerState get() = service.state
    val is_playing: Boolean get() = service.is_playing
    val song_count: Int get() = service.song_count
    val current_song_index: Int get() = service.current_song_index
    val current_position_ms: Long get() = service.current_position_ms
    val duration_ms: Long get() = service.duration_ms
    val has_focus: Boolean get() = service.has_focus

    val radio_instance: RadioInstance get() = radio.instance

    var repeat_mode: SpMsPlayerRepeatMode
        get() = service.repeat_mode
        set(value) {
            service.repeat_mode = value
        }
    var volume: Float
        get() = service.volume
        set(value) {
            service.volume = value
        }

    fun play() = service.play()
    fun pause() = service.pause()
    fun playPause() = service.playPause()

    fun seekTo(position_ms: Long) = service.seekTo(position_ms)
    fun seekToSong(index: Int) = service.seekToSong(index)
    fun seekToNext() = service.seekToNext()
    fun seekToPrevious() = service.seekToPrevious()

    fun getSong(): Song? = service.getSong()
    fun getSong(index: Int): Song? = service.getSong(index)

    fun savePersistentQueue() {
        coroutine_scope.launch {
            persistent_queue.savePersistentQueue()
        }
    }
}
