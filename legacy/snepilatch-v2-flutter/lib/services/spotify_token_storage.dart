import 'package:oauth2/oauth2.dart' as oauth2;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:spotify/spotify.dart';
import 'package:snepilatch_v2/services/spotify_config.dart';

class SpotifyTokenStorage {
  static const String _accessTokenKey = 'spotify_access_token';
  static const String _refreshTokenKey = 'spotify_refresh_token';
  static const String _tokenExpiryKey = 'spotify_token_expiry';

  oauth2.AuthorizationCodeGrant? _grant;

  /// Creates an OAuth authorization grant and returns the authorization URL.
  Future<Uri> getAuthorizationUrl() async {
    final clientId = await SpotifyConfig.getClientId();
    final clientSecret = await SpotifyConfig.getClientSecret();
    final credentials = SpotifyApiCredentials(clientId!, clientSecret!);
    _grant = SpotifyApi.authorizationCodeGrant(credentials);
    return _grant!.getAuthorizationUrl(
      Uri.parse(SpotifyConfig.redirectUri),
      scopes: SpotifyConfig.scopes,
    );
  }

  /// Exchanges the authorization response for an authenticated SpotifyApi
  /// and persists the OAuth credentials.
  Future<SpotifyApi> handleAuthResponse(Uri responseUri) async {
    final client = await _grant!.handleAuthorizationResponse(
      responseUri.queryParameters,
    );

    // Persist the OAuth credentials for next app launch
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_accessTokenKey, client.credentials.accessToken);
    if (client.credentials.refreshToken != null) {
      await prefs.setString(_refreshTokenKey, client.credentials.refreshToken!);
    }
    if (client.credentials.expiration != null) {
      await prefs.setInt(_tokenExpiryKey, client.credentials.expiration!.millisecondsSinceEpoch);
    }

    return SpotifyApi.fromClient(client);
  }

  /// Tries to restore a previous OAuth session from stored credentials.
  /// Returns a SpotifyApi if successful, null otherwise.
  Future<SpotifyApi?> restoreSession() async {
    final prefs = await SharedPreferences.getInstance();
    final accessToken = prefs.getString(_accessTokenKey);

    print('[SpotifyTokenStorage] Attempting to restore session...');
    if (accessToken == null || accessToken.isEmpty) {
      print('[SpotifyTokenStorage] No stored access token found');
      return null;
    }

    try {
      final refreshToken = prefs.getString(_refreshTokenKey);
      final expiryMs = prefs.getInt(_tokenExpiryKey);
      final expiration = expiryMs != null ? DateTime.fromMillisecondsSinceEpoch(expiryMs) : null;

      print('[SpotifyTokenStorage] Found stored token (${accessToken.substring(0, 10)}...), refresh: ${refreshToken != null}, expiry: ${expiration?.toIso8601String()}');

      // Create credentials object
      // tokenEndpoint is required for auto-refresh to work
      final credentials = oauth2.Credentials(
        accessToken,
        refreshToken: refreshToken,
        tokenEndpoint: Uri.parse('https://accounts.spotify.com/api/token'),
        expiration: expiration,
      );

      // If token is expired and we have no refresh token, clear and return null
      if (credentials.isExpired && credentials.refreshToken == null) {
        print('[SpotifyTokenStorage] Token expired and no refresh token available');
        await clearSession();
        return null;
      }

      final clientId = await SpotifyConfig.getClientId();
      final clientSecret = await SpotifyConfig.getClientSecret();
      if (clientId == null || clientSecret == null) {
        print('[SpotifyTokenStorage] Missing client ID or secret');
        return null;
      }

      // Create an HTTP client with the credentials
      // The client will automatically refresh the token if it's expired and a refresh token is available
      final client = oauth2.Client(
        credentials,
        identifier: clientId,
        secret: clientSecret,
        onCredentialsRefreshed: (credentials) async {
          // When token is refreshed, save the new one
          print('[SpotifyTokenStorage] Token refreshed, saving new credentials');
          await prefs.setString(_accessTokenKey, credentials.accessToken);
          if (credentials.refreshToken != null) {
            await prefs.setString(_refreshTokenKey, credentials.refreshToken!);
          }
          if (credentials.expiration != null) {
            await prefs.setInt(_tokenExpiryKey, credentials.expiration!.millisecondsSinceEpoch);
          }
        },
      );

      print('[SpotifyTokenStorage] Successfully restored SpotifyApi from stored token');
      return SpotifyApi.fromClient(client);
    } catch (e) {
      print('[SpotifyTokenStorage] Restore error: $e');
      await clearSession();
      return null;
    }
  }

  /// Clears stored OAuth credentials.
  Future<void> clearSession() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_accessTokenKey);
    await prefs.remove(_refreshTokenKey);
    await prefs.remove(_tokenExpiryKey);
  }
}
