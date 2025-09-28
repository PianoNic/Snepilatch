import 'dart:convert';

class User {
  final bool isLoggedIn;
  final String? username;
  final String? email;
  final String? profileImageUrl;

  User({
    this.isLoggedIn = false,
    this.username,
    this.email,
    this.profileImageUrl,
  });

  User copyWith({
    bool? isLoggedIn,
    String? username,
    String? email,
    String? profileImageUrl,
  }) {
    return User(
      isLoggedIn: isLoggedIn ?? this.isLoggedIn,
      username: username ?? this.username,
      email: email ?? this.email,
      profileImageUrl: profileImageUrl ?? this.profileImageUrl,
    );
  }

  factory User.guest() {
    return User(isLoggedIn: false);
  }

  String get displayName => username ?? 'Spotify User';

  Map<String, dynamic> toJson() => {
        'isLoggedIn': isLoggedIn,
        'username': username,
        'email': email,
        'profileImage': profileImageUrl,
      };

  factory User.fromJson(Map<String, dynamic> json) {
    // Convert to high quality image URL if it's a Spotify CDN image
    String? profileImage = json['profileImage'];
    if (profileImage != null) {
      // Handle album art format
      if (profileImage.contains('ab67616d00004851')) {
        profileImage = profileImage.replaceAll('ab67616d00004851', 'ab67616d00001e02');
      }
      // Handle profile picture format - convert to high-res
      if (profileImage.contains('ab67757000003b82')) {
        profileImage = profileImage.replaceAll('ab67757000003b82', 'ab6775700000ee85');
      }
    }

    return User(
      isLoggedIn: json['isLoggedIn'] ?? false,
      username: json['username']?.toString().isNotEmpty == true ? json['username'] : null,
      email: json['email']?.toString().isNotEmpty == true ? json['email'] : null,
      profileImageUrl: profileImage?.isNotEmpty == true ? profileImage : null,
    );
  }

  static User? fromJsonString(String jsonString) {
    try {
      final json = jsonDecode(jsonString);
      return User.fromJson(json);
    } catch (e) {
      return null;
    }
  }
}