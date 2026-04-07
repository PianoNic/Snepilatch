package ch.snepilatch.app.data

import ch.snepilatch.app.data.DetailData
import ch.snepilatch.app.data.RelatedAlbum
import ch.snepilatch.app.data.RelatedArtist
import ch.snepilatch.app.data.TrackInfo
import kotify.api.album.AlbumInfo
import kotify.api.album.AlbumTrack
import kotify.api.artist.ArtistInfo
import kotify.api.artist.ArtistTopTrack
import kotify.api.playerstatus.QueueTrack
import kotify.api.playlist.LikedSong
import kotify.api.playlist.LikedSongsPage
import kotify.api.playlist.PlaylistInfo
import kotify.api.playlist.PlaylistTrack
import kotify.api.song.SearchTrack
import kotify.api.song.TrackDetail
import ch.snepilatch.app.data.LibraryItem as UiLibraryItem
import kotify.api.playlist.Library as KLibrary
import kotify.api.playlist.LibraryItem as KLibraryItem

/**
 * Conversion helpers from KotifyClient typed DTOs into snepilatch's UI models.
 *
 * KotifyClient returns rich DTOs across the entire HTTP and WebSocket surface
 * (PRs #23–#45 in PianoNic/KotifyClient). The snepilatch UI uses simpler
 * `TrackInfo` / `LibraryItem` / `DetailData` shapes designed for Compose, so
 * this file is the one place where the two model worlds meet. Adding a new
 * field to a UI model? Edit the relevant `toX` extension here.
 */

// --- TrackInfo from every track-like DTO ---

fun SearchTrack.toTrackInfo() = TrackInfo(
    uri = uri,
    name = name,
    artist = artists.joinToString(", ") { it.name },
    albumArt = album.coverArtUrl,
    durationMs = durationMs,
)

fun TrackDetail.toTrackInfo() = TrackInfo(
    uri = uri,
    name = name,
    artist = artists.joinToString(", ") { it.name },
    albumArt = album.coverArtUrl,
    durationMs = durationMs,
)

fun PlaylistTrack.toTrackInfo() = TrackInfo(
    uri = uri,
    name = name,
    artist = artists.joinToString(", "),
    albumArt = coverArtUrl,
    durationMs = durationMs,
    uid = uid,
)

fun LikedSong.toTrackInfo() = TrackInfo(
    uri = uri,
    name = name,
    artist = artists.joinToString(", ") { it.name },
    albumArt = album.coverArtUrl,
    durationMs = durationMs,
)

/** Album tracks don't carry their own cover art — pass the album's. */
fun AlbumTrack.toTrackInfo(albumArtUrl: String?) = TrackInfo(
    uri = uri,
    name = name,
    artist = artists.joinToString(", "),
    albumArt = albumArtUrl,
    durationMs = durationMs,
)

/** Artist top tracks need the artist name passed in (the DTO carries a list of co-artist names). */
fun ArtistTopTrack.toTrackInfo(artistDisplayName: String) = TrackInfo(
    uri = uri,
    name = name,
    artist = artistDisplayName,
    albumArt = coverArtUrl,
    durationMs = durationMs,
)

/** A track from the dealer cluster_update queue (state.next_tracks / state.prev_tracks). */
fun QueueTrack.toTrackInfo() = TrackInfo(
    uri = uri,
    name = name ?: "Unknown",
    artist = artistName ?: "Unknown",
    albumArt = ch.snepilatch.app.util.normalizeSpotifyImageUrl(imageUrl),
    durationMs = durationMs,
    uid = uid,
)

// --- LibraryItem ---

fun KLibraryItem.toUiLibraryItem() = UiLibraryItem(
    uri = uri,
    name = name,
    imageUrl = imageUrl,
    type = type,
    owner = ownerName,
)

fun KLibrary.toUiLibraryList(): List<UiLibraryItem> = items.map { it.toUiLibraryItem() }

// --- DetailData ---

fun PlaylistInfo.toDetailData(playlistId: String) = DetailData(
    name = name,
    imageUrl = imageUrl,
    description = description.takeIf { it.isNotBlank() },
    uri = "spotify:playlist:$playlistId",
    type = "playlist",
    totalCount = totalTracks,
    loadedOffset = tracks.size,
    ownerName = owner.name,
    followers = followers,
    tracks = tracks.map { it.toTrackInfo() },
)

fun LikedSongsPage.toDetailData(offset: Int) = DetailData(
    name = "Liked Songs",
    imageUrl = "https://image-cdn-ak.spotifycdn.com/image/ab67706c0000da84587ecba4a27774b2f6f07174",
    tracks = items.map { it.toTrackInfo() },
    uri = "spotify:collection:tracks",
    totalCount = total,
    loadedOffset = offset + items.size,
)

fun AlbumInfo.toDetailData(albumId: String): DetailData {
    val mappedTracks = tracks.map { it.toTrackInfo(coverArtUrl) }
    val totalMs = mappedTracks.sumOf { it.durationMs }
    val totalMin = totalMs / 60000
    val description = "${mappedTracks.size} Songs · $totalMin Min."
    val firstArtist = artists.firstOrNull()
    return DetailData(
        name = name,
        imageUrl = coverArtUrl,
        description = description,
        tracks = mappedTracks,
        uri = "spotify:album:$albumId",
        type = "album",
        artistName = firstArtist?.name,
        artistUri = firstArtist?.uri,
        albumType = type,
        releaseDate = releaseDate,
        copyright = copyrights.joinToString("\n").takeIf { it.isNotBlank() },
        moreByArtist = moreByArtist.map { r ->
            RelatedAlbum(
                uri = r.uri,
                name = r.name,
                imageUrl = r.coverArtUrl,
                year = r.year?.toString(),
                albumType = r.type,
            )
        },
        totalCount = mappedTracks.size,
        loadedOffset = mappedTracks.size,
    )
}

fun ArtistInfo.toDetailData(artistId: String) = DetailData(
    name = name,
    imageUrl = avatarUrl ?: headerImageUrl,
    uri = "spotify:artist:$artistId",
    type = "artist",
    monthlyListeners = monthlyListeners,
    biography = biography,
    tracks = topTracks.map { it.toTrackInfo(name) },
    topTrackPlaycounts = topTracks.map { it.playcount?.toString().orEmpty() },
    popularReleases = popularReleases.map { r ->
        RelatedAlbum(
            uri = r.uri,
            name = r.name,
            imageUrl = r.coverArtUrl,
            year = r.year?.toString(),
            albumType = r.type,
        )
    },
    relatedArtists = relatedArtists.map { a ->
        RelatedArtist(
            uri = a.uri,
            name = a.name,
            imageUrl = a.avatarUrl,
        )
    },
)
