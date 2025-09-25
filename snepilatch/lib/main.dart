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
    return MaterialApp(
      title: 'Snepilatch',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: MainScreen(spotifyController: spotifyController),
    );
  }
}