package ch.snepilatch.app.data

import androidx.annotation.StringRes
import ch.snepilatch.app.R

enum class PlayerShortcut(val id: String, @get:StringRes val titleRes: Int, val requiresTrack: Boolean = true) {
    LIKE("like", R.string.like),
    LYRICS("lyrics", R.string.lyrics),
    ADD_TO_QUEUE("add_queue", R.string.add_to_queue),
    ADD_TO_PLAYLIST("add_playlist", R.string.add_to_playlist),
    QUEUE("queue", R.string.view_queue, false),
    ALBUM("album", R.string.visit_album),
    RADIO("radio", R.string.go_to_song_radio),
    DOWNLOAD("download", R.string.download_track),
    JAM("jam", R.string.join_jam, false),
    CODE("code", R.string.show_code),
    SHARE("share", R.string.share),
    ;

    companion object {
        fun fromId(id: String?): PlayerShortcut = entries.firstOrNull { it.id == id } ?: LIKE
    }
}
