package ch.snepilatch.app.data

import androidx.compose.ui.graphics.Color

enum class Screen { LOGIN, HOME, SEARCH, LIBRARY, NOW_PLAYING, QUEUE, PLAYLIST_DETAIL, ALBUM_DETAIL, ARTIST_DETAIL, ACCOUNT, LYRICS }

data class TrackInfo(
    val uri: String,
    val name: String,
    val artist: String,
    val albumArt: String?,
    val durationMs: Long = 0,
    val albumName: String? = null,
    val uid: String? = null
)

data class PlaybackUiState(
    val track: TrackInfo? = null,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isShuffling: Boolean = false,
    val repeatMode: String = "off",
    val volume: Double = 0.5
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
    // Playlist-specific
    val ownerName: String? = null,
    val followers: Long? = null,
    // Artist-specific
    val monthlyListeners: Long? = null,
    val biography: String? = null,
    val popularReleases: List<RelatedAlbum> = emptyList(),
    val relatedArtists: List<RelatedArtist> = emptyList(),
    val topTrackPlaycounts: List<String> = emptyList()
)

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
