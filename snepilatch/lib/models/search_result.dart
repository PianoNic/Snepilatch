class SearchResult {
  final String title;
  final String artist;
  final String? album;
  final String? imageUrl;
  final int index;

  SearchResult({
    required this.title,
    required this.artist,
    this.album,
    this.imageUrl,
    required this.index,
  });

  factory SearchResult.fromMap(Map<String, String> map, int index) {
    return SearchResult(
      title: map['title'] ?? '',
      artist: map['artist'] ?? '',
      album: map['album'],
      imageUrl: map['image'],
      index: index,
    );
  }
}