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
}