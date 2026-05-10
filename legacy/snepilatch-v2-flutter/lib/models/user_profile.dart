class UserProfile {
  final String id;
  final String displayName;
  final String? profileImage;
  final String? country;
  final bool? premium;
  final int? followers;
  final String? uri;
  final String? product;
  final List<UserImage> images;
  final Map<String, String>? externalUrls;
  final String? thumbnailImage;

  UserProfile({
    required this.id,
    required this.displayName,
    this.profileImage,
    this.country,
    this.premium,
    this.followers,
    this.uri,
    this.product,
    this.images = const [],
    this.externalUrls,
    this.thumbnailImage,
  });

  factory UserProfile.fromWebSocket(Map<String, dynamic> data) {
    List<UserImage> parseImages() {
      final raw = data['images'];
      if (raw is List) {
        return raw
            .whereType<Map<String, dynamic>>()
            .map(UserImage.fromJson)
            .where((img) => img.url.isNotEmpty)
            .toList();
      }
      return const [];
    }

    int? parseFollowers(dynamic raw) {
      if (raw is int) return raw;
      if (raw is num) return raw.toInt();
      if (raw is Map<String, dynamic>) {
        final total = raw['total'];
        if (total is int) return total;
        if (total is num) return total.toInt();
      }
      return null;
    }

    Map<String, String>? parseExternalUrls(dynamic raw) {
      if (raw is Map) {
        final map = <String, String>{};
        raw.forEach((key, value) {
          if (key is String && value is String) {
            map[key] = value;
          }
        });
        return map.isEmpty ? null : map;
      }
      return null;
    }

    final images = parseImages();
    final lowResImage = data['profileImage'] as String?;
    final highResImage = images.isNotEmpty
        ? (List<UserImage>.from(images)
              ..sort((a, b) => (b.width ?? 0).compareTo(a.width ?? 0)))
            .first
            .url
        : null;
    final smallestImage = images.isNotEmpty
      ? (List<UserImage>.from(images)
          ..sort((a, b) => (a.width ?? 1 << 30).compareTo(b.width ?? 1 << 30)))
        .first
        .url
      : null;

    return UserProfile(
      id: data['id'] as String? ?? 'Unknown',
      displayName: data['displayName'] as String? ?? 'Unknown',
      profileImage: highResImage ?? lowResImage,
      country: data['country'] as String?,
      premium: data['premium'] as bool?,
      followers: parseFollowers(data['followers']),
      uri: data['uri'] as String?,
      product: data['product'] as String?,
      images: images,
      externalUrls: parseExternalUrls(data['externalUrls']),
      thumbnailImage: smallestImage ?? lowResImage ?? highResImage,
    );
  }
}

class UserImage {
  final String url;
  final int? width;
  final int? height;

  const UserImage({
    required this.url,
    this.width,
    this.height,
  });

  factory UserImage.fromJson(Map<String, dynamic> json) {
    return UserImage(
      url: json['url'] as String? ?? '',
      width: (json['width'] as num?)?.toInt(),
      height: (json['height'] as num?)?.toInt(),
    );
  }
}
