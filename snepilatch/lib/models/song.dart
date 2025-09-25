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
}