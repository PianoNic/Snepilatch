class SearchResult {
  final String title;
  final String artist;
  final String? album;
  final String? imageUrl;
  final String? duration;
  final int index;

  SearchResult({
    required this.title,
    required this.artist,
    this.album,
    this.imageUrl,
    this.duration,
    required this.index,
  });

  factory SearchResult.fromMap(Map<String, dynamic> map, int index) {
    return SearchResult(
      title: map['title']?.toString() ?? '',
      artist: map['artist']?.toString() ?? '',
      album: map['album']?.toString(),
      imageUrl: map['imageUrl']?.toString() ?? map['image']?.toString(),
      duration: map['duration']?.toString(),
      index: map['index'] ?? index,
    );
  }
}