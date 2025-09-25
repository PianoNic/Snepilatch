import 'dart:io';
import 'package:flutter/material.dart';
import 'controllers/spotify_controller.dart';
import 'screens/main_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize WebView for supported platforms
  if (Platform.isAndroid || Platform.isIOS) {
    // WebView is supported
  }

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