package com.toasterofbread.spmp.api.lyrics

import com.toasterofbread.spmp.model.SongLyrics
import com.toasterofbread.spmp.model.mediaitem.Song
import androidx.compose.ui.graphics.Color
import com.toasterofbread.spmp.model.Settings
import com.toasterofbread.spmp.resources.getStringTODO
import kotlin.reflect.KClass

data class LyricsReference(val id: String, val source_idx: Int)

sealed class LyricsSource(val source_idx: Int) {
    data class SearchResult(
        var id: String,
        var name: String,
        var sync_type: SongLyrics.SyncType,
        var artist_name: String?,
        var album_name: String?
    )
    
    abstract fun getReadable(): String
    abstract fun getColour(): Color
    
    abstract suspend fun getLyrics(lyrics_id: String): Result<SongLyrics>
    abstract suspend fun searchForLyrics(title: String, artist_name: String? = null): Result<List<SearchResult>>

    companion object {
        private val lyrics_sources: List<KClass<out LyricsSource>> = listOf(
            PetitLyricsSource::class,
            KugouLyricsSource::class
        )
        val SOURCE_AMOUNT: Int get() = lyrics_sources.size

        @Suppress("NO_REFLECTION_IN_CLASS_PATH")
        fun fromIdx(source_idx: Int): LyricsSource {
            val cls = lyrics_sources[source_idx]
            return cls.constructors.first().call(source_idx)
        }

        inline fun iterateByPriority(default: Int = Settings.KEY_LYRICS_DEFAULT_SOURCE.get(), action: (LyricsSource) -> Unit) {
            for (i in 0 until SOURCE_AMOUNT) {
                val source = fromIdx(if (i == 0) default else if (i > default) i - 1 else i)
                action(source)
            }
        }
    }
}

suspend fun getSongLyrics(song: Song, reference: LyricsReference? = null): Result<SongLyrics> {
    val title = song.title ?: return Result.failure(RuntimeException("Song has no title"))

    if (reference != null) {
        val source = LyricsSource.fromIdx(reference.source_idx)
        return source.getLyrics(reference.id)
    }

    var fail_result: Result<SongLyrics>? = null
    LyricsSource.iterateByPriority { source ->
        val result: LyricsSource.SearchResult = source.searchForLyrics(title, song.artist?.title).fold(
            { results ->
                if (results.isEmpty()) {
                    fail_result = null
                    null
                }
                else {
                    results.first()
                }
            },
            { 
                if (fail_result == null) {
                    fail_result = Result.failure(it)
                }
                null
            }
        ) ?: return@iterateByPriority

        val lyrics_result = source.getLyrics(result.id)
        if (lyrics_result.isSuccess) {
            return lyrics_result
        }

        if (fail_result == null) {
            fail_result = lyrics_result
        }
    }

    return fail_result ?: Result.failure(RuntimeException(getStringTODO("No lyrics found")))
}