class HomepageItem {
  final String id;
  final String title;
  final String? subtitle;
  final String imageUrl;
  final String href;
  final String type; // 'artist', 'playlist', 'album', 'collection'
  final bool isPlaying;

  HomepageItem({
    required this.id,
    required this.title,
    this.subtitle,
    required this.imageUrl,
    required this.href,
    required this.type,
    this.isPlaying = false,
  });

  factory HomepageItem.fromJson(Map<String, dynamic> json) {
    return HomepageItem(
      id: json['id'] ?? '',
      title: json['title'] ?? '',
      subtitle: json['subtitle'],
      imageUrl: json['imageUrl'] ?? '',
      href: json['href'] ?? '',
      type: json['type'] ?? 'unknown',
      isPlaying: json['isPlaying'] ?? false,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'subtitle': subtitle,
      'imageUrl': imageUrl,
      'href': href,
      'type': type,
      'isPlaying': isPlaying,
    };
  }
}

class HomepageSection {
  final String title;
  final String? sectionId;
  final List<HomepageItem> items;

  HomepageSection({
    required this.title,
    this.sectionId,
    required this.items,
  });

  factory HomepageSection.fromJson(Map<String, dynamic> json) {
    return HomepageSection(
      title: json['title'] ?? '',
      sectionId: json['sectionId'],
      items: (json['items'] as List?)
          ?.map((item) => HomepageItem.fromJson(item))
          .toList() ?? [],
    );
  }
}