import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:snepilatch_v2/services/spotify_client.dart';

class ThemeService extends ChangeNotifier {
  bool _isDarkMode = true;
  Color _seedColor = const Color.fromARGB(255, 255, 255, 255);
  String? _lastCachedImageUrl;
  final SpotifyClient spotifyClient;

  ThemeService({required this.spotifyClient}) {
    // Listen to SpotifyClient changes to update theme color
    spotifyClient.addListener(_onSpotifyClientChanged);
  }

  bool get isDarkMode => _isDarkMode;
  Color get seedColor => _seedColor;

  void _onSpotifyClientChanged() {
    final track = spotifyClient.currentTrack;
    if (track?.imageUrl != null) {
      final imageUrl = track!.imageUrl!;

      if (_lastCachedImageUrl == imageUrl) {
        return;
      }

      _getDominantColor(imageUrl).then((color) {
        if (color != null) {
          _lastCachedImageUrl = imageUrl;
          _seedColor = color;
          notifyListeners();
        }
      });
    }
  }

  Future<Color?> _getDominantColor(String url) async {
    final client = http.Client();
    try {
      final response = await client.get(Uri.parse(url));
      final image = MemoryImage(response.bodyBytes);
      
      final colorScheme = await ColorScheme.fromImageProvider(
        provider: image,
        brightness: Brightness.dark,
      );
      
      return colorScheme.primary;
    } finally {
      client.close();
    }
  }

  void toggleTheme() {
    _isDarkMode = !_isDarkMode;
    notifyListeners();
  }

  void setSeedColor(Color color) {
    _seedColor = color;
    notifyListeners();
  }

  @override
  void dispose() {
    spotifyClient.removeListener(_onSpotifyClientChanged);
    super.dispose();
  }
}