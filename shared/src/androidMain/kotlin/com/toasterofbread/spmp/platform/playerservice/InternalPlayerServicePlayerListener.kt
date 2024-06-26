package com.toasterofbread.spmp.platform.playerservice

import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.toasterofbread.spmp.model.mediaitem.song.Song

class InternalPlayerServicePlayerListener(
    private val service: ForegroundPlayerService
): Player.Listener {
    override fun onMediaItemTransition(media_item: MediaItem?, reason: Int) {
        val song: Song? = media_item?.getSong()
        if (song?.id == service.current_song?.id) {
            return
        }

        service.current_song = song
        service.updatePlayerCustomActions()

        if (service.loudness_enhancer == null) {
            service.loudness_enhancer = LoudnessEnhancer(service.player.audioSessionId)
        }

        service.loudness_enhancer?.update(song, service.context)
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        service.loudness_enhancer?.release()
        service.loudness_enhancer = LoudnessEnhancer(audioSessionId).apply {
            update(service.current_song, service.context)
            enabled = true
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (service.device_connection_changed_playing_status) {
            service.device_connection_changed_playing_status = false
        }
        else {
            service.paused_by_device_disconnect = false
        }
    }
}
