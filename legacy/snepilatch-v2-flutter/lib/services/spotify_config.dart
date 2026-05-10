import 'package:shared_preferences/shared_preferences.dart';

class SpotifyConfig {
  static const String _clientIdKey = 'spotify_client_id';
  static const String _clientSecretKey = 'spotify_client_secret';

  static const String redirectUri = 'http://127.0.0.1:8888/callback';

  static const List<String> scopes = [
    'user-read-private',
    'user-read-email',
    'user-library-read',
    'user-library-modify',
    'user-read-recently-played',
    'user-top-read',
    'user-follow-read',
    'playlist-read-private',
    'playlist-read-collaborative',
    'playlist-modify-public',
    'playlist-modify-private',
  ];

  static Future<bool> hasCredentials() async {
    final prefs = await SharedPreferences.getInstance();
    final id = prefs.getString(_clientIdKey);
    final secret = prefs.getString(_clientSecretKey);
    return id != null && id.isNotEmpty && secret != null && secret.isNotEmpty;
  }

  static Future<String?> getClientId() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_clientIdKey);
  }

  static Future<String?> getClientSecret() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_clientSecretKey);
  }

  static Future<void> saveCredentials(String clientId, String clientSecret) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_clientIdKey, clientId.trim());
    await prefs.setString(_clientSecretKey, clientSecret.trim());
  }

  static Future<void> clearCredentials() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_clientIdKey);
    await prefs.remove(_clientSecretKey);
  }
}
