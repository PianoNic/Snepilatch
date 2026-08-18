package ch.snepilatch.app.data

import androidx.compose.ui.graphics.Color

enum class Screen {
    LOGIN, HOME, SEARCH, LIBRARY, NOW_PLAYING, PLAYLIST_DETAIL, ALBUM_DETAIL,
    ARTIST_DETAIL, SHOW_DETAIL, ACCOUNT, LYRICS, EQUALIZER, DOWNLOADS
}

data class TrackInfo(
    val uri: String,
    val name: String,
    val artist: String,
    val albumArt: String?,
    val durationMs: Long = 0,
    val albumName: String? = null,
    val uid: String? = null,
    /** Queue identity: uid plus the repeat iteration, since uid alone recurs on the next pass. */
    val qid: String? = null,
    /**
     * Position in the server's unfiltered `next_tracks`. The queue we display hides the delimiter
     * and anything flagged, so a row's position on screen is not the index a queue write wants.
     */
    val queueIndex: Int? = null
)

data class PlaybackUiState(
    val track: TrackInfo? = null,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isShuffling: Boolean = false,
    val repeatMode: String = "off",
    val volume: Double = 0.5,
    // True while an ad is being skipped: a local silent clip plays for ~1s and the UI shows a
    // "Skipping ad…" placeholder instead of track metadata. Cleared when the next real track loads.
    val isAd: Boolean = false
)

data class LibraryItem(
    val uri: String,
    val name: String,
    val imageUrl: String?,
    val type: String,
    val owner: String? = null
)

data class DetailData(
    val name: String = "",
    val imageUrl: String? = null,
    val description: String? = null,
    val tracks: List<TrackInfo> = emptyList(),
    val uri: String = "",
    val type: String = "",
    val totalCount: Int = -1,
    val loadedOffset: Int = 0,
    // Album-specific
    val artistName: String? = null,
    val artistUri: String? = null,
    val albumType: String? = null,
    val releaseDate: String? = null,
    val copyright: String? = null,
    val moreByArtist: List<RelatedAlbum> = emptyList(),
    // Playlist-specific. Ownership checks use [ownerUri] (`spotify:user:<username>`); [ownerName] is
    // a display name and can't be matched against an account.
    val ownerName: String? = null,
    val ownerUri: String? = null,
    val followers: Long? = null,
    val savedInLibrary: Boolean = false,
    // Artist-specific
    val monthlyListeners: Long? = null,
    val biography: String? = null,
    val popularReleases: List<RelatedAlbum> = emptyList(),
    val relatedArtists: List<RelatedArtist> = emptyList(),
    val topTrackPlaycounts: List<String> = emptyList(),
    // Show-specific (podcast). Episodes are carried in [tracks] as episode-URI TrackInfos.
    val publisher: String? = null
)

/** True when this detail is a playlist [username] owns — the only case where a track may be removed. */
fun DetailData.isPlaylistOwnedBy(username: String): Boolean =
    type == "playlist" &&
        username.isNotBlank() &&
        ownerUri?.substringAfterLast(':') == username

data class RelatedArtist(
    val uri: String,
    val name: String,
    val imageUrl: String?
)

data class RelatedAlbum(
    val uri: String,
    val name: String,
    val imageUrl: String?,
    val year: String?,
    val albumType: String?
)

data class AccountInfo(
    val username: String = "",
    val displayName: String = "",
    val isPremium: Boolean = false,
    val profileImageUrl: String? = null,
    val userId: String = "",
    val followers: Int = 0,
    val playlistCount: Int = 0
)

data class ThemeColors(
    val primary: Color = Color(0xFFB3B3B3),
    val primaryDark: Color = Color(0xFF808080),
    val surface: Color = Color(0xFF282828),
    val gradientTop: Color = Color(0xFF282828),
    val gradientBottom: Color = Color(0xFF121212)
)
