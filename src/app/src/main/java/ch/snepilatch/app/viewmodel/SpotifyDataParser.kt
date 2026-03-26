package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.DetailData
import ch.snepilatch.app.data.LibraryItem
import ch.snepilatch.app.LokiLogger
import ch.snepilatch.app.data.TrackInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "SpotifyDataParser"

internal fun parseSearchTracks(items: Any?): List<TrackInfo> {
    if (items == null) return emptyList()

    // items is a JsonElement (JsonArray) from Song.search()
    val jsonArray = when (items) {
        is JsonArray -> items
        is JsonElement -> try { items.jsonArray } catch (_: Exception) { return emptyList() }
        is List<*> -> return parseSearchTracksFromList(items)
        else -> return emptyList()
    }

    return jsonArray.mapNotNull { element ->
        try {
            val obj = element.jsonObject
            // Structure: { "item": { "data": { ... } } }
            val itemData = obj["item"]?.jsonObject?.get("data")?.jsonObject ?: return@mapNotNull null
            val uri = itemData["uri"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!uri.startsWith("spotify:track:")) return@mapNotNull null

            val name = itemData["name"]?.jsonPrimitive?.content ?: "Unknown"
            val artists = itemData["artists"]?.jsonObject?.get("items")?.jsonArray?.mapNotNull { a ->
                a.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            }
            val artistName = artists?.joinToString(", ") ?: "Unknown"
            val albumArt = itemData["albumOfTrack"]?.jsonObject
                ?.get("coverArt")?.jsonObject
                ?.get("sources")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("url")?.jsonPrimitive?.content
            val duration = itemData["duration"]?.jsonObject
                ?.get("totalMilliseconds")?.jsonPrimitive?.content?.toLongOrNull() ?: 0

            TrackInfo(uri = uri, name = name, artist = artistName, albumArt = albumArt, durationMs = duration)
        } catch (e: Exception) {
            LokiLogger.e(TAG, "parseTrack error", e)
            null
        }
    }
}

internal fun parseSearchTracksFromList(items: List<*>): List<TrackInfo> {
    return items.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val itemData = map["item"] as? Map<*, *> ?: map["data"] as? Map<*, *> ?: map
        val content = itemData["content"] as? Map<*, *> ?: itemData
        val data = content["data"] as? Map<*, *> ?: content
        val uri = data["uri"]?.toString() ?: return@mapNotNull null
        if (!uri.startsWith("spotify:track:")) return@mapNotNull null
        val name = data["name"]?.toString() ?: "Unknown"
        val artists = (data["artists"] as? Map<*, *>)?.let { ar ->
            (ar["items"] as? List<*>)?.mapNotNull {
                (it as? Map<*, *>)?.get("profile")?.let { p ->
                    (p as? Map<*, *>)?.get("name")?.toString()
                }
            }
        }
        val albumArt = (data["albumOfTrack"] as? Map<*, *>)?.let { alb ->
            (alb["coverArt"] as? Map<*, *>)?.let { ca ->
                (ca["sources"] as? List<*>)?.firstOrNull()?.let {
                    (it as? Map<*, *>)?.get("url")?.toString()
                }
            }
        }
        val duration = (data["duration"] as? Map<*, *>)?.get("totalMilliseconds")?.toString()?.toLongOrNull() ?: 0
        TrackInfo(uri = uri, name = name, artist = artists?.joinToString(", ") ?: "", albumArt = albumArt, durationMs = duration)
    }
}
internal fun parseLibrary(data: Map<String, Any?>): List<LibraryItem> {
    val me = (data["data"] as? Map<*, *>)?.get("me") as? Map<*, *> ?: return emptyList()
    val libraryV3 = me["libraryV3"] as? Map<*, *> ?: return emptyList()
    val items = libraryV3["items"] as? List<*> ?: return emptyList()
    return items.mapNotNull { item ->
        val i = item as? Map<*, *> ?: return@mapNotNull null
        val itemData = (i["item"] as? Map<*, *>)?.get("data") as? Map<*, *> ?: return@mapNotNull null
        val uri = itemData["uri"]?.toString() ?: return@mapNotNull null
        val typeName = itemData["__typename"]?.toString() ?: ""

        val name: String
        val imageUrl: String?
        val type: String
        val owner: String?

        when {
            typeName == "PseudoPlaylist" || uri.contains("collection:tracks") -> {
                name = itemData["name"]?.toString() ?: "Liked Songs"
                imageUrl = null
                type = "collection"
                owner = null
            }
            typeName.contains("Playlist") -> {
                name = itemData["name"]?.toString() ?: "Playlist"
                imageUrl = (itemData["images"] as? Map<*, *>)?.let { im ->
                    (im["items"] as? List<*>)?.firstOrNull()?.let {
                        (it as? Map<*, *>)?.get("sources")?.let { s ->
                            (s as? List<*>)?.firstOrNull()?.let { (it as? Map<*, *>)?.get("url")?.toString() }
                        }
                    }
                }
                type = "playlist"
                owner = (itemData["ownerV2"] as? Map<*, *>)?.let {
                    (it["data"] as? Map<*, *>)?.get("name")?.toString()
                }
            }
            typeName.contains("Album") -> {
                name = itemData["name"]?.toString() ?: "Album"
                imageUrl = (itemData["coverArt"] as? Map<*, *>)?.let { ca ->
                    (ca["sources"] as? List<*>)?.firstOrNull()?.let {
                        (it as? Map<*, *>)?.get("url")?.toString()
                    }
                }
                type = "album"
                owner = (itemData["artists"] as? Map<*, *>)?.let { ar ->
                    (ar["items"] as? List<*>)?.firstOrNull()?.let {
                        (it as? Map<*, *>)?.get("profile")?.let { p ->
                            (p as? Map<*, *>)?.get("name")?.toString()
                        }
                    }
                }
            }
            typeName.contains("Artist") -> {
                name = (itemData["profile"] as? Map<*, *>)?.get("name")?.toString() ?: "Artist"
                imageUrl = (itemData["visuals"] as? Map<*, *>)?.let { v ->
                    (v["avatarImage"] as? Map<*, *>)?.let { ai ->
                        (ai["sources"] as? List<*>)?.firstOrNull()?.let {
                            (it as? Map<*, *>)?.get("url")?.toString()
                        }
                    }
                }
                type = "artist"
                owner = null
            }
            else -> {
                name = itemData["name"]?.toString() ?: "Unknown"
                imageUrl = null
                type = "unknown"
                owner = null
            }
        }
        LibraryItem(uri = uri, name = name, imageUrl = imageUrl, type = type, owner = owner)
    }
}

@Suppress("UNCHECKED_CAST")
internal fun parseLikedSongsDetail(data: Map<String, Any?>, offset: Int): DetailData {
    val me = (data["data"] as? Map<*, *>)?.get("me") as? Map<*, *> ?: return DetailData(name = "Liked Songs")
    val library = me["library"] as? Map<*, *> ?: return DetailData(name = "Liked Songs")
    val tracksPage = library["tracks"] as? Map<*, *> ?: return DetailData(name = "Liked Songs")
    val items = tracksPage["items"] as? List<*> ?: return DetailData(name = "Liked Songs")
    val totalCount = tracksPage["totalCount"]?.toString()?.toIntOrNull() ?: -1

    val tracks = parseLikedSongsTracks(items)

    return DetailData(
        name = "Liked Songs",
        imageUrl = "https://image-cdn-ak.spotifycdn.com/image/ab67706c0000da84587ecba4a27774b2f6f07174",
        tracks = tracks,
        uri = "spotify:collection:tracks",
        totalCount = totalCount,
        loadedOffset = offset + tracks.size
    )
}

internal fun parseLikedSongsTracks(items: List<*>): List<TrackInfo> {
    return items.mapNotNull { item ->
        val i = item as? Map<*, *> ?: return@mapNotNull null
        val trackWrapper = i["track"] as? Map<*, *> ?: return@mapNotNull null
        val uri = trackWrapper["_uri"]?.toString() ?: return@mapNotNull null
        val trackData = trackWrapper["data"] as? Map<*, *> ?: return@mapNotNull null

        val trackName = trackData["name"]?.toString() ?: "Unknown"
        val artist = parseArtistsFromMap(trackData)
        val art = (trackData["albumOfTrack"] as? Map<*, *>)?.let { alb ->
            (alb["coverArt"] as? Map<*, *>)?.let { ca ->
                (ca["sources"] as? List<*>)?.firstOrNull()?.let {
                    (it as? Map<*, *>)?.get("url")?.toString()
                }
            }
        }
        val dur = (trackData["duration"] as? Map<*, *>)?.get("totalMilliseconds")?.toString()?.toLongOrNull() ?: 0
        TrackInfo(uri = uri, name = trackName, artist = artist, albumArt = art, durationMs = dur)
    }
}

internal fun parseTrackUnion(data: Map<String, Any?>): TrackInfo? {
    val trackUnion = (data["data"] as? Map<*, *>)?.get("trackUnion") as? Map<*, *> ?: return null
    val uri = trackUnion["uri"]?.toString() ?: return null
    val name = trackUnion["name"]?.toString() ?: "Unknown"

    // Try multiple artist structures: artists.items, firstArtist, artistsV2
    val artists = parseArtistsFromMap(trackUnion)

    val art = (trackUnion["albumOfTrack"] as? Map<*, *>)?.let { alb ->
        (alb["coverArt"] as? Map<*, *>)?.let { ca ->
            (ca["sources"] as? List<*>)?.firstOrNull()?.let {
                (it as? Map<*, *>)?.get("url")?.toString()
            }
        }
    }
    val dur = (trackUnion["duration"] as? Map<*, *>)?.get("totalMilliseconds")?.toString()?.toLongOrNull() ?: 0

    LokiLogger.i("SpotifyDataParser", "parseTrackUnion: name=$name, artists=$artists, keys=${trackUnion.keys}")
    return TrackInfo(uri = uri, name = name, artist = artists, albumArt = art, durationMs = dur)
}

private fun parseArtistsFromMap(trackData: Map<*, *>): String {
    // Pattern 1: artists.items[].profile.name (search results, playlist tracks)
    (trackData["artists"] as? Map<*, *>)?.let { ar ->
        val names = (ar["items"] as? List<*>)?.mapNotNull {
            (it as? Map<*, *>)?.get("profile")?.let { p ->
                (p as? Map<*, *>)?.get("name")?.toString()
            }
        }
        if (!names.isNullOrEmpty()) return names.joinToString(", ")
    }

    // Pattern 2: firstArtist.items[].profile.name (trackUnion from getTrack)
    (trackData["firstArtist"] as? Map<*, *>)?.let { fa ->
        val names = (fa["items"] as? List<*>)?.mapNotNull {
            (it as? Map<*, *>)?.get("profile")?.let { p ->
                (p as? Map<*, *>)?.get("name")?.toString()
            }
        }
        if (!names.isNullOrEmpty()) return names.joinToString(", ")
    }

    // Pattern 3: artistsV2.items[].profile.name
    (trackData["artistsV2"] as? Map<*, *>)?.let { ar ->
        val names = (ar["items"] as? List<*>)?.mapNotNull {
            (it as? Map<*, *>)?.get("profile")?.let { p ->
                (p as? Map<*, *>)?.get("name")?.toString()
            }
        }
        if (!names.isNullOrEmpty()) return names.joinToString(", ")
    }

    return ""
}

internal fun parsePlaylistDetail(data: Map<String, Any?>, playlistId: String): DetailData {
    val pv2 = (data["data"] as? Map<*, *>)?.get("playlistV2") as? Map<*, *> ?: return DetailData()
    val name = pv2["name"]?.toString() ?: "Playlist"
    val imageUrl = (pv2["images"] as? Map<*, *>)?.let { im ->
        (im["items"] as? List<*>)?.firstOrNull()?.let {
            (it as? Map<*, *>)?.get("sources")?.let { s ->
                (s as? List<*>)?.firstOrNull()?.let { (it as? Map<*, *>)?.get("url")?.toString() }
            }
        }
    }
    val description = pv2["description"]?.toString()
    val tracks = parsePlaylistTracks(data)
    val totalCount = parsePlaylistTotalCount(data)
    return DetailData(name = name, imageUrl = imageUrl, description = description, tracks = tracks, uri = "spotify:playlist:$playlistId", type = "playlist", totalCount = totalCount, loadedOffset = tracks.size)
}

internal fun parsePlaylistTracks(data: Map<String, Any?>): List<TrackInfo> {
    val pv2 = (data["data"] as? Map<*, *>)?.get("playlistV2") as? Map<*, *> ?: return emptyList()
    val content = pv2["content"] as? Map<*, *>
    val items = (content?.get("items") as? List<*>) ?: return emptyList()
    return items.mapNotNull { item ->
        val i = item as? Map<*, *> ?: return@mapNotNull null
        val itemData = (i["itemV2"] as? Map<*, *>)?.get("data") as? Map<*, *> ?: return@mapNotNull null
        val uri = itemData["uri"]?.toString() ?: return@mapNotNull null
        val trackName = itemData["name"]?.toString() ?: "Unknown"
        val artists = (itemData["artists"] as? Map<*, *>)?.let { ar ->
            (ar["items"] as? List<*>)?.mapNotNull {
                (it as? Map<*, *>)?.get("profile")?.let { p ->
                    (p as? Map<*, *>)?.get("name")?.toString()
                }
            }
        }
        val art = (itemData["albumOfTrack"] as? Map<*, *>)?.let { alb ->
            (alb["coverArt"] as? Map<*, *>)?.let { ca ->
                (ca["sources"] as? List<*>)?.firstOrNull()?.let {
                    (it as? Map<*, *>)?.get("url")?.toString()
                }
            }
        }
        val dur = (itemData["duration"] as? Map<*, *>)?.get("totalMilliseconds")?.toString()?.toLongOrNull() ?: 0
        TrackInfo(uri = uri, name = trackName, artist = artists?.joinToString(", ") ?: "", albumArt = art, durationMs = dur)
    }
}

internal fun parsePlaylistTotalCount(data: Map<String, Any?>): Int {
    val pv2 = (data["data"] as? Map<*, *>)?.get("playlistV2") as? Map<*, *> ?: return -1
    val content = pv2["content"] as? Map<*, *> ?: return -1
    return content["totalCount"]?.toString()?.toIntOrNull() ?: -1
}

internal fun parseAlbumDetail(data: Map<String, Any?>, albumId: String): DetailData {
    val album = (data["data"] as? Map<*, *>)?.get("albumUnion") as? Map<*, *> ?: return DetailData()
    val name = album["name"]?.toString() ?: "Album"
    val imageUrl = (album["coverArt"] as? Map<*, *>)?.let { ca ->
        (ca["sources"] as? List<*>)?.firstOrNull()?.let {
            (it as? Map<*, *>)?.get("url")?.toString()
        }
    }
    // Artist info
    val artistItems = (album["artists"] as? Map<*, *>)?.get("items") as? List<*>
    val firstArtist = (artistItems?.firstOrNull() as? Map<*, *>)
    val artistName = (firstArtist?.get("profile") as? Map<*, *>)?.get("name")?.toString()
    val artistUri = firstArtist?.get("uri")?.toString()

    // Album metadata
    val albumType = album["type"]?.toString()
    val releaseDate = (album["date"] as? Map<*, *>)?.let { d ->
        // Try isoString first (e.g. "2024-07-01T00:00:00Z"), fallback to year
        val iso = d["isoString"]?.toString()
        if (iso != null && iso.length >= 10) {
            // Parse "2024-07-01" → "1 Jul. 2024" style
            try {
                val parts = iso.substring(0, 10).split("-")
                val year = parts[0]
                val month = parts[1].toInt()
                val day = parts[2].toInt()
                val monthNames = listOf("", "Jan.", "Feb.", "Mar.", "Apr.", "May", "Jun.", "Jul.", "Aug.", "Sep.", "Oct.", "Nov.", "Dec.")
                "$day ${monthNames[month]} $year"
            } catch (_: Exception) { d["year"]?.toString() }
        } else {
            d["year"]?.toString()
        }
    }

    // Copyright
    val copyrights = (album["copyright"] as? Map<*, *>)?.get("items") as? List<*>
    val copyright = copyrights?.mapNotNull { (it as? Map<*, *>)?.get("text")?.toString() }?.joinToString("\n")

    // More by artist
    val moreByArtist = try {
        val moreAlbums = (album["moreAlbumsByArtist"] as? Map<*, *>)?.get("items") as? List<*>
        moreAlbums?.mapNotNull { item ->
            val disc = (item as? Map<*, *>)?.get("discography") as? Map<*, *> ?: return@mapNotNull null
            val relUri = disc["uri"]?.toString() ?: return@mapNotNull null
            val relName = disc["name"]?.toString() ?: ""
            val relType = disc["type"]?.toString()
            val relYear = (disc["date"] as? Map<*, *>)?.get("year")?.toString()
            val relArt = (disc["coverArt"] as? Map<*, *>)?.let { ca ->
                (ca["sources"] as? List<*>)?.firstOrNull()?.let { (it as? Map<*, *>)?.get("url")?.toString() }
            }
            ch.snepilatch.app.data.RelatedAlbum(uri = relUri, name = relName, imageUrl = relArt, year = relYear, albumType = relType)
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    val tracksData = (album["tracksV2"] as? Map<*, *>)?.get("items") as? List<*> ?: emptyList<Any>()
    val tracks = tracksData.mapNotNull { item ->
        val i = item as? Map<*, *> ?: return@mapNotNull null
        val trackData = i["track"] as? Map<*, *> ?: return@mapNotNull null
        val uri = trackData["uri"]?.toString() ?: return@mapNotNull null
        val trackName = trackData["name"]?.toString() ?: "Unknown"
        val artists = (trackData["artists"] as? Map<*, *>)?.let { ar ->
            (ar["items"] as? List<*>)?.mapNotNull {
                (it as? Map<*, *>)?.get("profile")?.let { p ->
                    (p as? Map<*, *>)?.get("name")?.toString()
                }
            }
        }
        val dur = (trackData["duration"] as? Map<*, *>)?.get("totalMilliseconds")?.toString()?.toLongOrNull() ?: 0
        TrackInfo(uri = uri, name = trackName, artist = artists?.joinToString(", ") ?: "", albumArt = imageUrl, durationMs = dur)
    }

    // Track count + total duration summary
    val totalMs = tracks.sumOf { it.durationMs }
    val totalMin = totalMs / 60000
    val description = "${tracks.size} Songs · $totalMin Min."

    return DetailData(
        name = name, imageUrl = imageUrl, description = description, tracks = tracks,
        uri = "spotify:album:$albumId", type = "album",
        artistName = artistName, artistUri = artistUri,
        albumType = albumType, releaseDate = releaseDate,
        copyright = copyright, moreByArtist = moreByArtist
    )
}

internal fun parseArtistDetail(data: Map<String, Any?>, artistId: String): DetailData {
    val artist = (data["data"] as? Map<*, *>)?.get("artistUnion") as? Map<*, *> ?: return DetailData()
    val name = (artist["profile"] as? Map<*, *>)?.get("name")?.toString() ?: "Artist"
    val imageUrl = (artist["visuals"] as? Map<*, *>)?.let { v ->
        (v["avatarImage"] as? Map<*, *>)?.let { ai ->
            (ai["sources"] as? List<*>)?.firstOrNull()?.let {
                (it as? Map<*, *>)?.get("url")?.toString()
            }
        }
    }
    val topTracks = (artist["discography"] as? Map<*, *>)?.let { disc ->
        (disc["topTracks"] as? Map<*, *>)?.get("items") as? List<*>
    }
    val tracks = topTracks?.mapNotNull { item ->
        val i = item as? Map<*, *> ?: return@mapNotNull null
        val trackData = i["track"] as? Map<*, *> ?: return@mapNotNull null
        val uri = trackData["uri"]?.toString() ?: return@mapNotNull null
        val trackName = trackData["name"]?.toString() ?: "Unknown"
        val art = (trackData["albumOfTrack"] as? Map<*, *>)?.let { alb ->
            (alb["coverArt"] as? Map<*, *>)?.let { ca ->
                (ca["sources"] as? List<*>)?.firstOrNull()?.let {
                    (it as? Map<*, *>)?.get("url")?.toString()
                }
            }
        }
        val dur = (trackData["duration"] as? Map<*, *>)?.get("totalMilliseconds")?.toString()?.toLongOrNull() ?: 0
        TrackInfo(uri = uri, name = trackName, artist = name, albumArt = art, durationMs = dur)
    } ?: emptyList()
    return DetailData(name = name, imageUrl = imageUrl, tracks = tracks, uri = "spotify:artist:$artistId", type = "artist")
}
