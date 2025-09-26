import 'dart:io';
import 'package:flutter/material.dart';
import 'package:audio_service/audio_service.dart';
import 'controllers/spotify_controller.dart';
import 'screens/main_screen.dart';
import 'services/audio_handler_service.dart';
import 'widgets/app_update_dialog.dart';

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

  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final SpotifyController spotifyController = SpotifyController();

  @override
  void initState() {
    super.initState();
    _checkForUpdates();
  }

  void _checkForUpdates() {
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await Future.delayed(const Duration(seconds: 2));
      if (mounted) {
        await AppUpdateDialog.showIfAvailable(context);
      }
    });
  }

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
          navigatorKey: GlobalKey<NavigatorState>(),
        );
      },
    );
  }
}