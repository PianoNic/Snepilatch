package ch.snepilatch.app.ui.shared

/** The entries of a track menu that depend on where it opens; the rest is always there. */
data class TrackMenuOptions(
    val removeFromPlaylist: (() -> Unit)? = null,
    val radio: Boolean = false,
    val visitAlbum: Boolean = true,
    val visitArtist: Boolean = true,
)
