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
    INFINIPLAY("infiniplay"),
    JAM("jam", false),
    CODE("code"),
    SHARE("share"),
    ;

    companion object {
        /**
         * What a swipe on a list row may do: the shortcuts that act on the row's own track. LIKE is out
         * because a row does not know its liked state, LYRICS because the lyrics view follows the player,
         * and INFINIPLAY because it remixes whatever is streaming, not the row you swiped.
         */
        val perTrack: List<PlayerShortcut> =
            entries.filter { it.requiresTrack && it != LIKE && it != LYRICS && it != INFINIPLAY }

        fun fromId(id: String?, fallback: PlayerShortcut = LIKE): PlayerShortcut =
            entries.firstOrNull { it.id == id } ?: fallback

        /** [fromId] limited to [perTrack], so a stored choice that no longer applies to rows falls back. */
        fun perTrackFromId(id: String?, fallback: PlayerShortcut): PlayerShortcut =
            perTrack.firstOrNull { it.id == id } ?: fallback
    }
}
