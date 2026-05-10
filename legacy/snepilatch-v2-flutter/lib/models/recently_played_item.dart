import 'package:spotify/spotify.dart' as spotify;

class RecentlyPlayedItem {
  final String uri;
  final String? type; // 'artist', 'album', 'playlist', etc.
  final String? name;
  final String? cover;
  final String? artists; // For tracks and albums
  final String? description; // For playlists

  RecentlyPlayedItem({
    required this.uri,
    this.type,
    this.name,
    this.cover,
    this.artists,
    this.description,
  });

  /// Create from WebSocket data
  factory RecentlyPlayedItem.fromWebSocket(Map<String, dynamic> data) {
    final context = data['context'] as Map<String, dynamic>? ?? {};

    var uri = context['uri'] as String? ?? data['contextUri'] as String? ?? '';
    var name = context['name'] as String?;
    var cover = context['cover'] as String?;

    // Handle Spotify user collection (liked songs)
    if (uri.contains(':collection') && (name == null || name.isEmpty)) {
      name = 'Liked Songs';
      cover = 'https://misc.scdn.co/liked-songs/liked-songs-640.jpg';
    }

    return RecentlyPlayedItem(
      uri: uri,
      type: context['type'] as String?,
      name: name,
      cover: cover,
      artists: context['artists'] as String?,
      description: context['description'] as String?,
    );
  }

  /// Create from Spotify API PlaylistSimple
  factory RecentlyPlayedItem.fromPlaylistSimple(spotify.PlaylistSimple playlist) {
    return RecentlyPlayedItem(
      uri: playlist.uri ?? '',
      type: 'playlist',
      name: playlist.name,
      cover: playlist.images?.isNotEmpty == true ? playlist.images!.first.url : null,
      description: playlist.description,
    );
  }

  /// Create from Spotify API AlbumSimple
  factory RecentlyPlayedItem.fromAlbumSimple(spotify.AlbumSimple album) {
    return RecentlyPlayedItem(
      uri: album.uri ?? '',
      type: 'album',
      name: album.name,
      cover: album.images?.isNotEmpty == true ? album.images!.first.url : null,
      artists: album.artists?.map((a) => a.name).whereType<String>().join(', '),
    );
  }

  /// Create from Spotify API Artist
  factory RecentlyPlayedItem.fromArtist(spotify.Artist artist) {
    return RecentlyPlayedItem(
      uri: artist.uri ?? '',
      type: 'artist',
      name: artist.name,
      cover: artist.images?.isNotEmpty == true ? artist.images!.first.url : null,
    );
  }

  /// Convert to Map for serialization
  Map<String, dynamic> toMap() {
    return {
      'uri': uri,
      'type': type,
      'name': name,
      'cover': cover,
      'artists': artists,
      'description': description,
    };
  }
}
