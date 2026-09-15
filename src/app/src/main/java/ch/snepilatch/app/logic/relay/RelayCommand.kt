package ch.snepilatch.app.logic.relay

/** A guest's request for the host to apply; [kind] is one of the constants below and decides which fields are set. */
data class RelayCommand(
    val kind: String,
    val positionMs: Long? = null,
    val uri: String? = null,
    val contextUri: String? = null,
    val uid: String? = null,
    val index: Int? = null,
    val toIndex: Int? = null,
) {
    companion object {
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val SEEK = "seek"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
        const val PLAY = "play"
        const val ADD_TO_QUEUE = "addToQueue"
        const val REMOVE_FROM_QUEUE = "removeFromQueue"
        const val MOVE_QUEUE = "moveQueue"
    }
}
