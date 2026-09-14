package ch.snepilatch.app.data

/** What the button beside the full-screen player's track details does; [requiresTrack] greys it out while nothing plays. */
enum class PlayerShortcut(val id: String, val requiresTrack: Boolean = true) {
    LIKE("like"),
    LYRICS("lyrics"),
    ADD_TO_QUEUE("add_queue"),
    ADD_TO_PLAYLIST("add_playlist"),
    QUEUE("queue", false),
    ALBUM("album"),
    RADIO("radio"),
    DOWNLOAD("download"),
    JAM("jam", false),
    CODE("code"),
    SHARE("share"),
    ;

    companion object {
        fun fromId(id: String?, fallback: PlayerShortcut = LIKE): PlayerShortcut =
            entries.firstOrNull { it.id == id } ?: fallback
    }
}
