package ch.snepilatch.app.logic.shared

/** The id of a spfy uri such as spotify:track:id: its last segment. */
fun spfyId(uri: String): String = uri.substringAfterLast(':')
