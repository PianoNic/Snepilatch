import 'dart:convert';

class Song {
  final String title;
  final String artist;
  final String album;
  final String? imageUrl;
  final String? duration;
  final int index;

  Song({
    required this.title,
    required this.artist,
    required this.album,
    this.imageUrl,
    this.duration,
    required this.index,
  });

  factory Song.fromMap(Map<String, String> map) {
    return Song(
      title: map['title'] ?? 'Unknown Song',
      artist: map['artist'] ?? 'Unknown Artist',
      album: map['album'] ?? '',
      imageUrl: map['image'],
      duration: map['duration'],
      index: int.tryParse(map['index'] ?? '0') ?? 0,
    );
  }

  Map<String, String> toMap() {
    return {
      'title': title,
      'artist': artist,
      'album': album,
      if (imageUrl != null) 'image': imageUrl!,
      if (duration != null) 'duration': duration!,
      'index': index.toString(),
    };
  }

  Map<String, dynamic> toJson() => {
        'title': title,
        'artist': artist,
        'album': album,
        'image': imageUrl,
        'duration': duration,
        'index': index,
      };

  factory Song.fromJson(Map<String, dynamic> json) {
    // Convert to high quality image URL
    String? imageUrl = json['image'];
    if (imageUrl != null && imageUrl.contains('ab67616d00004851')) {
      imageUrl = imageUrl.replaceAll('ab67616d00004851', 'ab67616d00001e02');
    }

    return Song(
      title: json['title'] ?? 'Unknown Song',
      artist: json['artist'] ?? 'Unknown Artist',
      album: json['album'] ?? '',
      imageUrl: imageUrl,
      duration: json['duration'],
      index: json['index'] ?? 0,
    );
  }

  static List<Song> fromJsonList(String jsonString) {
    try {
      final List<dynamic> jsonList = jsonDecode(jsonString);
      return jsonList.map((json) => Song.fromJson(json)).toList();
    } catch (e) {
      return [];
    }
  }
}