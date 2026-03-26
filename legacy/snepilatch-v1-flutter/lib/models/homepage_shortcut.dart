class HomepageShortcut {
  final String id;
  final String title;
  final String imageUrl;
  final String href;
  final String type; // 'artist', 'playlist', 'album', 'collection'
  final bool isPlaying;
  final int? progressPercentage; // For podcast/episode progress

  HomepageShortcut({
    required this.id,
    required this.title,
    required this.imageUrl,
    required this.href,
    required this.type,
    this.isPlaying = false,
    this.progressPercentage,
  });

  factory HomepageShortcut.fromJson(Map<String, dynamic> json) {
    return HomepageShortcut(
      id: json['id'] ?? '',
      title: json['title'] ?? '',
      imageUrl: json['imageUrl'] ?? '',
      href: json['href'] ?? '',
      type: json['type'] ?? 'unknown',
      isPlaying: json['isPlaying'] ?? false,
      progressPercentage: json['progressPercentage'],
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'imageUrl': imageUrl,
      'href': href,
      'type': type,
      'isPlaying': isPlaying,
      'progressPercentage': progressPercentage,
    };
  }
}

class HomepageFilter {
  final String label;
  final bool isSelected;

  HomepageFilter({
    required this.label,
    this.isSelected = false,
  });

  factory HomepageFilter.fromJson(Map<String, dynamic> json) {
    return HomepageFilter(
      label: json['label'] ?? '',
      isSelected: json['isSelected'] ?? false,
    );
  }
}
