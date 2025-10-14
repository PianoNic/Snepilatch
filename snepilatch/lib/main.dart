import 'package:flutter/material.dart';
import 'package:audio_service/audio_service.dart';
import 'controllers/spotify_controller.dart';
import 'screens/main_screen.dart';
import 'pages/loading_screen.dart';
import 'services/audio_handler_service.dart';
import 'services/logging_service.dart';
import 'utils/logger.dart';
import 'widgets/app_update_dialog.dart';
import 'widgets/spotify_webview.dart';

late AudioHandler audioHandler;
late LoggingService loggingService;

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize logging service
  loggingService = LoggingService();
  Logger.init(loggingService);

  logInfo('Application starting', source: 'main');

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

  logInfo('Audio service initialized', source: 'main');

  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  late final SpotifyController spotifyController;
  bool _showLoadingScreen = true;
  bool _webViewInitialized = false;
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();

  @override
  void initState() {
    super.initState();
    spotifyController = SpotifyController();
    spotifyController.onLogout = _handleLogout;
    _checkForUpdates();
  }

  void _handleLogout() {
    setState(() {
      _showLoadingScreen = true;
    });
  }

  void _checkForUpdates() {
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      // Wait for loading to complete before checking updates
      if (!_showLoadingScreen) {
        await Future.delayed(const Duration(seconds: 2));

        // Check if State is still mounted after async gap
        if (!mounted) return;

        // Use navigator context which has MaterialLocalizations
        final navContext = _navigatorKey.currentContext;
        if (navContext != null) {
          // ignore: use_build_context_synchronously
          await AppUpdateDialog.showIfAvailable(navContext);
        }
      }
    });
  }

  void _onLoadingComplete() {
    setState(() {
      _showLoadingScreen = false;
      _webViewInitialized = true; // WebView is initialized during loading
    });
    // Check for updates after loading complete
    _checkForUpdates();
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
          home: Stack(
            children: [
              // Main screen or loading screen
              _showLoadingScreen
                  ? LoadingScreen(
                      spotifyController: spotifyController,
                      onComplete: _onLoadingComplete,
                    )
                  : MainScreen(spotifyController: spotifyController),
              // WebView - only rendered after loading screen starts
              if (_showLoadingScreen || _webViewInitialized)
                SpotifyWebViewWidget(spotifyController: spotifyController),
            ],
          ),
          debugShowCheckedModeBanner: false,
          navigatorKey: _navigatorKey,
        );
      },
    );
  }
}