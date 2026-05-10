import 'package:snepilatch_v2/models/recently_played_item.dart';

class HomeSection {
  final String type;
  final String title;
  final String? subtitle;
  final String? uri;
  final List<RecentlyPlayedItem> items;

  HomeSection({
    required this.type,
    required this.title,
    this.subtitle,
    this.uri,
    required this.items,
  });

  bool get isShortcuts => type.contains('Shortcut');

  factory HomeSection.fromMap(Map<String, dynamic> data) {
    final rawItems = data['items'] as List? ?? [];
    return HomeSection(
      type: data['type'] as String? ?? 'unknown',
      title: data['title'] as String? ?? '',
      subtitle: data['subtitle'] as String?,
      uri: data['uri'] as String?,
      items: rawItems
          .whereType<Map<String, dynamic>>()
          .map((item) {
            final artistsList = item['artists'];
            String? artists;
            if (artistsList is List) {
              artists = artistsList.whereType<String>().join(', ');
            }

            return RecentlyPlayedItem(
              uri: item['uri'] as String? ?? '',
              type: item['type'] as String?,
              name: item['name'] as String?,
              cover: item['imageUrl'] as String?,
              artists: artists,
              description: item['description'] as String?,
            );
          })
          .where((item) => item.name != null && item.name!.isNotEmpty)
          .toList(),
    );
  }
}
