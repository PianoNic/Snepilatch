import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:snepilatch_v2/components/now_playing_player.dart';
import 'package:snepilatch_v2/pages/devices_page.dart';
import 'package:snepilatch_v2/pages/home_page.dart';
import 'package:snepilatch_v2/pages/library_page.dart';
import 'package:snepilatch_v2/pages/profile_page.dart';
import 'package:snepilatch_v2/pages/setup_page.dart';
import 'package:snepilatch_v2/services/multimedia_action_handler.dart';
import 'package:snepilatch_v2/services/spotify_client.dart';
import 'package:snepilatch_v2/services/spotify_config.dart';
import 'package:snepilatch_v2/services/theme_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SnepilatchApp());
}

class SnepilatchApp extends StatefulWidget {
  const SnepilatchApp({super.key});

  /// Restarts the entire app (re-checks credentials, re-initializes services).
  static void restart(BuildContext context) {
    context.findAncestorStateOfType<_SnepilatchAppState>()?.restart();
  }

  @override
  State<SnepilatchApp> createState() => _SnepilatchAppState();
}

class _SnepilatchAppState extends State<SnepilatchApp> {
  // null = still checking, false = needs setup, true = ready
  bool? _hasCredentials;
  SpotifyClient? _spotifyClient;
  MultimediaActionHandler? _multimediaHandler;
  ThemeService? _themeService;

  @override
  void initState() {
    super.initState();
    _checkCredentials();
  }

  Future<void> _checkCredentials() async {
    final has = await SpotifyConfig.hasCredentials();
    if (has) {
      await _initializeServices();
    } else {
      setState(() => _hasCredentials = false);
    }
  }

  Future<void> _initializeServices() async {
    final client = SpotifyClient();
    await client.initialize();

    final handler = MultimediaActionHandler();
    await handler.initialize(client);

    setState(() {
      _spotifyClient = client;
      _multimediaHandler = handler;
      _themeService = ThemeService(spotifyClient: client);
      _hasCredentials = true;
    });
  }

  void _onSetupComplete() {
    setState(() => _hasCredentials = null); // show loading
    _initializeServices();
  }

  void restart() {
    _spotifyClient?.dispose();
    _multimediaHandler?.dispose();
    _themeService?.dispose();
    setState(() {
      _hasCredentials = null;
      _spotifyClient = null;
      _multimediaHandler = null;
      _themeService = null;
    });
    _checkCredentials();
  }

  @override
  Widget build(BuildContext context) {
    // Still checking credentials
    if (_hasCredentials == null) {
      return MaterialApp(
        title: 'Snepilatch',
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
          useMaterial3: true,
        ),
        darkTheme: ThemeData(
          colorScheme: ColorScheme.fromSeed(
            seedColor: Colors.deepPurple,
            brightness: Brightness.dark,
          ),
          useMaterial3: true,
        ),
        home: const Scaffold(
          body: Center(child: CircularProgressIndicator()),
        ),
      );
    }

    // No credentials — show setup page
    if (_hasCredentials == false) {
      return MaterialApp(
        title: 'Snepilatch',
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
          useMaterial3: true,
        ),
        darkTheme: ThemeData(
          colorScheme: ColorScheme.fromSeed(
            seedColor: Colors.deepPurple,
            brightness: Brightness.dark,
          ),
          useMaterial3: true,
        ),
        home: SetupPage(onComplete: _onSetupComplete),
      );
    }

    // Credentials exist and services initialized
    return MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: _spotifyClient!),
        ChangeNotifierProvider.value(value: _themeService!),
        ChangeNotifierProvider.value(value: _multimediaHandler!),
      ],
      child: const Snepilatch(),
    );
  }
}

class Snepilatch extends StatelessWidget {
  const Snepilatch({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = context.watch<ThemeService>();

    final spotifyClient = context.read<SpotifyClient>();

    return MaterialApp(
      navigatorKey: spotifyClient.navigatorKey,
      title: 'Snepilatch',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: theme.seedColor,
          brightness: Brightness.light,
        ),
        useMaterial3: true,
        progressIndicatorTheme: const ProgressIndicatorThemeData(
          year2023: false,
        ),
        sliderTheme: const SliderThemeData(
          year2023: false
        )
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: theme.seedColor,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
        progressIndicatorTheme: const ProgressIndicatorThemeData(
          year2023: false,
        ),
      ),
      themeMode: theme.isDarkMode ? ThemeMode.dark : ThemeMode.light,
      home: MainPage(),
    );
  }
}

class MainPage extends StatefulWidget {
  const MainPage({super.key});

  @override
  State<MainPage> createState() => _MainPageState();
}

class _MainPageState extends State<MainPage> {
  int _selectedIndex = 0;
  final GlobalKey<NavigatorState> _contentNavigatorKey = GlobalKey<NavigatorState>();
  final ValueNotifier<int> _tabNotifier = ValueNotifier<int>(0);
  final List<Widget> _tabs = [
    const HomeTab(),
    const LibraryTab(),
    const DevicesTab(),
    const ProfileTab(),
  ];

  @override
  void dispose() {
    _tabNotifier.dispose();
    super.dispose();
  }

  void _onTabSelected(int index) {
    _contentNavigatorKey.currentState?.popUntil((route) => route.isFirst);
    setState(() {
      _selectedIndex = index;
      _tabNotifier.value = index;
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = context.watch<ThemeService>();
    final spotifyClient = context.watch<SpotifyClient>();
    final colorScheme = Theme.of(context).colorScheme;

    return AnnotatedRegion(
      value: SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: theme.isDarkMode ? Brightness.light : Brightness.dark,
        systemNavigationBarColor: Theme.of(context).colorScheme.surfaceContainer,
        systemNavigationBarIconBrightness: theme.isDarkMode ? Brightness.light : Brightness.dark,
        systemNavigationBarContrastEnforced: true,
      ),
      child: PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) {
          if (didPop) return;
          final nav = _contentNavigatorKey.currentState;
          if (nav != null && nav.canPop()) {
            nav.pop();
          }
        },
        child: Scaffold(
        body: Column(
          children: [
            Expanded(
              child: Navigator(
                key: _contentNavigatorKey,
                onGenerateRoute: (_) => MaterialPageRoute(
                  builder: (_) => ValueListenableBuilder<int>(
                    valueListenable: _tabNotifier,
                    builder: (context, index, _) => AnimatedSwitcher(
                      duration: const Duration(milliseconds: 150),
                      transitionBuilder: (child, animation) {
                        return FadeTransition(
                          opacity: animation,
                          child: child,
                        );
                      },
                      child: KeyedSubtree(
                        key: ValueKey<int>(index),
                        child: _tabs[index],
                      ),
                    ),
                  ),
                ),
              ),
            ),
            const NowPlayingPlayer(),
          ],
        ),
        bottomNavigationBar: NavigationBar(
          onDestinationSelected: _onTabSelected,
          selectedIndex: _selectedIndex,
          destinations: [
            NavigationDestination(
              icon: Icon(Icons.home_rounded),
              selectedIcon: Icon(Icons.home_rounded),
              label: 'Home',
            ),
            NavigationDestination(
              icon: Icon(Icons.library_music_rounded),
              selectedIcon: Icon(Icons.library_music_rounded),
              label: 'Library',
            ),
            NavigationDestination(
              icon: Icon(Icons.devices_rounded),
              selectedIcon: Icon(Icons.devices_rounded),
              label: 'Devices',
            ),
            NavigationDestination(
              icon: spotifyClient.userProfile?.thumbnailImage != null
                  ? CircleAvatar(
                      radius: 12,
                      backgroundImage: NetworkImage(spotifyClient.userProfile!.thumbnailImage!),
                      backgroundColor: colorScheme.surfaceContainer,
                    )
                  : Icon(Icons.person_rounded),
              selectedIcon: spotifyClient.userProfile?.thumbnailImage != null
                  ? Container(
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        border: Border.all(
                          color: colorScheme.primary,
                          width: 2,
                        ),
                      ),
                      child: CircleAvatar(
                        radius: 12,
                        backgroundImage: NetworkImage(spotifyClient.userProfile!.thumbnailImage!),
                        backgroundColor: colorScheme.primary,
                      ),
                    )
                  : Icon(
                      Icons.person_rounded,
                      color: colorScheme.primary,
                    ),
              label: 'Profile',
            ),
          ],
        ),
      ),
      ),
    );
  }
}
