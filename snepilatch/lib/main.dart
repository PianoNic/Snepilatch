import 'dart:io';
import 'package:flutter/material.dart';
import 'package:audio_service/audio_service.dart';
import 'controllers/spotify_controller.dart';
import 'screens/main_screen.dart';
import 'services/audio_handler_service.dart';

late AudioHandler audioHandler;

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize WebView for supported platforms
  if (Platform.isAndroid || Platform.isIOS) {
    // WebView is supported
  }

  // Initialize audio service
  audioHandler = await AudioService.init(
    builder: () => SpotifyAudioHandler(),
    config: const AudioServiceConfig(
      androidNotificationChannelId: 'ch.snepilatch.app.channel.audio',
      androidNotificationChannelName: 'Snepilatch Playback',
      androidNotificationOngoing: true,
      androidStopForegroundOnPause: true,
      androidShowNotificationBadge: true,
      notificationColor: Color(0xFF1DB954), // Spotify green
    ),
  );

  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  MyApp({super.key});

  final SpotifyController spotifyController = SpotifyController();

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: spotifyController.themeService,
      builder: (context, _) {
        return MaterialApp(
          title: 'Snepilatch',
          theme: spotifyController.themeService.lightTheme(),
          darkTheme: spotifyController.themeService.darkTheme(),
          themeMode: spotifyController.themeService.themeMode,
          home: MainScreen(spotifyController: spotifyController),
          debugShowCheckedModeBanner: false,
        );
      },
    );
  }
}