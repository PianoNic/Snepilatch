package ch.snepilatch.app.data

/**
 * What the bottom bar's fourth button opens, chosen in the interface settings. Whatever sits there
 * leaves the drawer, so nothing shows in both places; [NONE] leaves the bar at three buttons.
 */
enum class NavSlot(val id: String, val screen: Screen?) {
    NONE("none", null),
    SETTINGS("settings", Screen.SETTINGS),
    DOWNLOADS("downloads", Screen.DOWNLOADS),
    FRIEND_ACTIVITY("friends", Screen.FRIEND_ACTIVITY),
    WHATS_NEW("whats_new", Screen.WHATS_NEW),
    ;

    companion object {
        fun fromId(id: String?): NavSlot = entries.firstOrNull { it.id == id } ?: SETTINGS
    }
}
