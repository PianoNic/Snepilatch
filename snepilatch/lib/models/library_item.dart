import 'dart:convert';

/// Represents an item in the user's library (playlist, artist, album, liked songs, etc.)
class LibraryItem {
  final String id; // Spotify URI like "spotify:playlist:xxx" or "spotify:artist:xxx"
  final String title;
  final String subtitle; // "Playlist • Username", "Künstler*in", etc.
  final String imageUrl;
  final String type; // "playlist", "artist", "album", "collection"
  final bool isPinned; // Whether the item is pinned
  final String? owner; // Playlist owner name

  LibraryItem({
    required this.id,
    required this.title,
    required this.subtitle,
    required this.imageUrl,
    required this.type,
    this.isPinned = false,
    this.owner,
  });

  factory LibraryItem.fromJson(Map<String, dynamic> json) {
    return LibraryItem(
      id: json['id'] ?? '',
      title: json['title'] ?? '',
      subtitle: json['subtitle'] ?? '',
      imageUrl: json['imageUrl'] ?? '',
      type: json['type'] ?? 'unknown',
      isPinned: json['isPinned'] ?? false,
      owner: json['owner'],
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'subtitle': subtitle,
      'imageUrl': imageUrl,
      'type': type,
      'isPinned': isPinned,
      'owner': owner,
    };
  }

  static List<LibraryItem> fromJsonList(String jsonString) {
    try {
      final List<dynamic> jsonList = jsonDecode(jsonString);
      return jsonList.map((json) => LibraryItem.fromJson(json)).toList();
    } catch (e) {
      return [];
    }
  }

  @override
  String toString() {
    return 'LibraryItem{id: $id, title: $title, type: $type}';
  }
}
