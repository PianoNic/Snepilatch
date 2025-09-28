import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

import '../models/song.dart';
import '../models/playback_state.dart' as app_models;
import '../models/search_result.dart';
import '../models/user.dart';
import '../services/webview_service.dart';
import '../services/spotify_scraper_service.dart';
import '../services/spotify_actions_service.dart';
import '../services/javascript_loader_service.dart';
import '../services/injection_monitor_service.dart';
import '../services/theme_service.dart';
import '../services/audio_handler_service.dart';
import '../stores/spotify_store.dart';
import '../main.dart';

class SpotifyController extends ChangeNotifier {
  final WebViewService _webViewService = WebViewService();
  final SpotifyStore store = SpotifyStore();
  final ThemeService themeService = ThemeService();
  late final InjectionMonitorService _injectionMonitor;
  Timer? _scrapingTimer;
  Timer? _progressTimer;
  String? _lastAlbumArt;
  String? _lastNotifiedTrack;
  String? _lastNotifiedArtist;
  bool? _lastNotifiedIsPlaying;
  VoidCallback? onLogout;
  bool _debugWebViewVisible = false;

  SpotifyController() {
    debugPrint('🚀 [SpotifyController] Constructor called');
    _injectionMonitor = InjectionMonitorService(_webViewService);
    _startPeriodicScraping();
    _startProgressAnimation();
    _setupAudioHandler();
  }

  void _setupAudioHandler() {
    // Set up callbacks for the audio handler
    if (audioHandler is SpotifyAudioHandler) {
      final handler = audioHandler as SpotifyAudioHandler;

      // Set callbacks
      handler.onPlayCallback = play;
      handler.onPauseCallback = pause;
      handler.onNextCallback = next;
      handler.onPreviousCallback = previous;
      handler.onSeekCallback = (position) {
        final percentage = position.inMilliseconds / store.durationMs.value;
        seekToPosition(percentage);
      };
      handler.onToggleLikeCallback = toggleLike;
    }
  }

  void _updateAudioServiceMetadata(app_models.PlaybackState newState) {
    if (audioHandler is SpotifyAudioHandler) {
      final handler = audioHandler as SpotifyAudioHandler;

      // Only update notification if there are significant changes
      bool shouldUpdateNotification = false;

      // Check if track info changed (ignore null values)
      if (newState.currentTrack != null &&
          (newState.currentTrack != _lastNotifiedTrack ||
           newState.currentArtist != _lastNotifiedArtist)) {
        shouldUpdateNotification = true;
        _lastNotifiedTrack = newState.currentTrack;
        _lastNotifiedArtist = newState.currentArtist;
      }

      // Check if playing state changed
      if (newState.isPlaying != _lastNotifiedIsPlaying) {
        shouldUpdateNotification = true;
        _lastNotifiedIsPlaying = newState.isPlaying;
      }

      // Only update if there are changes
      if (!shouldUpdateNotification) {
        return;
      }

      // Update media item only if we have valid track info
      if (newState.currentTrack != null) {
        handler.setMediaItem(
          title: newState.currentTrack!,
          artist: newState.currentArtist ?? 'Unknown Artist',
          artUri: newState.currentAlbumArt,
          duration: Duration(milliseconds: newState.durationMs),
        );

        // Update playback state with like status
        handler.updatePlaybackState(
          isPlaying: newState.isPlaying,
          position: Duration(milliseconds: newState.progressMs),
          duration: Duration(milliseconds: newState.durationMs),
          isLiked: newState.isCurrentTrackLiked,
        );
      }
    }
  }

  @override
  void dispose() {
    _scrapingTimer?.cancel();
    _progressTimer?.cancel();
    _injectionMonitor.dispose();
    store.dispose();
    themeService.dispose();
    super.dispose();
  }

  // Getters that delegate to store (for backward compatibility)
  bool get isInitialized => store.isInitialized.value;
  bool get isPlaying => store.isPlaying.value;
  bool get isLoggedIn => store.isLoggedIn.value;
  bool get showWebView => store.showWebView.value;
  String? get currentTrack => store.currentTrack.value;
  String? get currentArtist => store.currentArtist.value;
  String? get currentAlbumArt => store.currentAlbumArt.value;
  String? get username => store.username.value;
  String? get userEmail => store.userEmail.value;
  String? get userProfileImage => store.userProfileImage.value;
  List<Song> get songs => store.songs.value;
  bool get isLoadingSongs => store.isLoadingSongs.value;
  bool get isCurrentTrackLiked => store.isCurrentTrackLiked.value;
  bool get debugWebViewVisible => _debugWebViewVisible;
  String get shuffleMode => store.shuffleMode.value.value;
  String get repeatMode => store.repeatMode.value.value;
  String get currentTime => store.currentTime.value;
  String get duration => store.duration.value;
  int get progressMs => store.progressMs.value;
  int get durationMs => store.durationMs.value;
  double get progressPercentage => durationMs > 0 ? (progressMs / durationMs).clamp(0.0, 1.0) : 0.0;

  // Get current theme hex color
  String? get currentThemeHex => themeService.currentHexColor;

  // Direct access to notifiers for UI binding
  ValueNotifier<bool> get showWebViewNotifier => store.showWebView;
  ValueNotifier<bool> get isLoggedInNotifier => store.isLoggedIn;

  // WebView initialization methods
  InAppWebViewSettings getWebViewSettings() => _webViewService.getSettings();

  void onWebViewCreated(InAppWebViewController controller) {
    _webViewService.setController(controller);
    // Start monitoring for JavaScript injection
    _injectionMonitor.startMonitoring();
  }

  Future<void> onLoadStop(InAppWebViewController controller, WebUri? url) async {
    debugPrint('🌐 [SpotifyController] Page finished loading: $url');

    // Check if functions are already present
    final alreadyInjected = await _injectionMonitor.areFunctionsInjected();

    if (!alreadyInjected) {
      debugPrint('💉 [SpotifyController] Functions not found, injecting JavaScript...');
      await _injectJavaScript();
    } else {
      debugPrint('✅ [SpotifyController] Functions already present, skipping injection');
    }

    final urlString = url?.toString() ?? '';

    // Add small delay to ensure JavaScript is fully executed
    await Future.delayed(const Duration(milliseconds: 500));

    store.isInitialized.value = true;
    debugPrint('✅ [SpotifyController] WebView initialized and ready for scraping');

    // Start periodic scraping now that JavaScript is injected
    _startPeriodicScrapingActual();

    // Only defer this specific notification since it happens during build
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
  }

  Future<NavigationActionPolicy> shouldOverrideUrlLoading(
      InAppWebViewController controller, NavigationAction navigationAction) async {
    return _webViewService.shouldOverrideUrlLoading(controller, navigationAction);
  }

  Future<PermissionResponse?> onPermissionRequest(
      InAppWebViewController controller, PermissionRequest permissionRequest) async {
    return _webViewService.onPermissionRequest(controller, permissionRequest);
  }

  Future<void> _injectJavaScript() async {
    try {
      // First test basic JavaScript execution
      final basicTest = await _webViewService.runJavascriptWithResult('1 + 1');
      debugPrint('🧪 Basic JS test (1+1): $basicTest');

      // Load all JavaScript from files and inject into WebView
      final scripts = await JavaScriptLoaderService.getScriptsForInjection();
      debugPrint('📝 JavaScript loaded, length: ${scripts.length} characters');

      await _webViewService.runJavascript(scripts);
      debugPrint('✅ JavaScript injected successfully from files');

      // Wait a moment for JavaScript to execute
      await Future.delayed(const Duration(milliseconds: 100));

      // Verify functions exist
      final testResult = await _webViewService.runJavascriptWithResult(
        'typeof window.getPlayingInfo'
      );
      debugPrint('🔍 window.getPlayingInfo type: $testResult');

      // List all functions on window object starting with 'get' or 'spotify'
      final functionsCheck = await _webViewService.runJavascriptWithResult('''
        Object.keys(window).filter(key =>
          key.startsWith('get') ||
          key.startsWith('spotify') ||
          key === 'PlaylistController'
        ).join(', ')
      ''');
      debugPrint('📋 Available functions: $functionsCheck');
    } catch (e, stackTrace) {
      debugPrint('❌ Error injecting JavaScript: $e');
      debugPrint('Stack trace: $stackTrace');
    }
  }

  void _startPeriodicScraping() {
    // Cancel any existing timer
    _scrapingTimer?.cancel();
    _scrapingTimer = null;

    debugPrint('⏰ [SpotifyController] Periodic scraping will start after WebView initialization...');
  }

  void _startPeriodicScrapingActual() {
    // Cancel any existing timer
    _scrapingTimer?.cancel();
    _scrapingTimer = null;

    debugPrint('✅ [SpotifyController] Starting periodic scraping (every 1 second)');
    _scrapingTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (store.isInitialized.value && !store.showWebView.value) {
        _scrapeAllInfo();
      }
    });
  }

  void _startProgressAnimation() {
    // Smooth progress updates every 100ms
    _progressTimer = Timer.periodic(const Duration(milliseconds: 100), (timer) {
      if (store.isPlaying.value && store.durationMs.value > 0) {
        // Don't update if user is seeking
        if (!store.isUserControlling.value) {
          // Increment progress smoothly
          final newProgress = store.progressMs.value + 100;
          if (newProgress <= store.durationMs.value) {
            store.updateProgressSmoothly(newProgress);
            notifyListeners();
          }
        }
      }
    });
  }

  Future<void> _scrapeAllInfo() async {
    if (_webViewService.controller == null) return;

    // Skip if user is actively controlling
    if (store.isUserControlling.value) {
      debugPrint('🚫 [SpotifyController] Skipping scrape - user is controlling');
      return;
    }

    // Quick check if functions are still present before scraping
    final functionsPresent = await _injectionMonitor.areFunctionsInjected();
    if (!functionsPresent) {
      debugPrint('⚠️ [SpotifyController] Functions missing during scrape, monitor will reinject');
      return; // Skip this scrape, let monitor handle reinjection
    }

    try {
      // Batch scrape all info at once - functions return JSON strings
      final playbackResult = await _webViewService.runJavascriptWithResult(
        'window.getPlayingInfo()'
      );

      final userResult = await _webViewService.runJavascriptWithResult(
        'window.getUserInfo()'
      );

      app_models.PlaybackState? newState;
      User? newUser;

      // Parse playback info using proper JSON parsing
      if (playbackResult != null && playbackResult != 'null') {
        final String jsonString = playbackResult.toString();
        newState = SpotifyScraperService.parsePlaybackInfo(jsonString);
      }

      // Parse user info using proper JSON parsing
      if (userResult != null && userResult != 'null') {
        final String jsonString = userResult.toString();
        newUser = SpotifyScraperService.parseUserInfo(jsonString);
      }

      // Update store with all values at once
      if (newState != null || newUser != null) {
        store.batchUpdate(newState, newUser);

        // Update theme if album art changed
        if (newState?.currentAlbumArt != null &&
            newState?.currentAlbumArt != _lastAlbumArt) {
          debugPrint('🎨 [SpotifyController] Album art changed - updating theme');
          _lastAlbumArt = newState?.currentAlbumArt;
          themeService.updateThemeFromImageUrl(_lastAlbumArt);
        }

        // Update audio service metadata
        if (newState != null) {
          _updateAudioServiceMetadata(newState);
        }

        // Single notification for all updates
        notifyListeners();
      }
    } catch (e) {
      debugPrint('❌ [SpotifyController] Error scraping: $e');
    }
  }


  // Playback control methods with optimistic updates
  Future<void> play() async {
    store.setPlaying(true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    await _webViewService.runJavascript(SpotifyActionsService.playScript);
  }

  Future<void> pause() async {
    store.setPlaying(false);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    await _webViewService.runJavascript(SpotifyActionsService.pauseScript);
  }

  Future<void> next() async {
    store.startUserControl();
    await _webViewService.runJavascript(SpotifyActionsService.nextScript);
    // Force scrape after delay to get new track info
    Future.delayed(const Duration(seconds: 2), () {
      _scrapeAllInfo();
    });
  }

  Future<void> previous() async {
    store.startUserControl();
    await _webViewService.runJavascript(SpotifyActionsService.previousScript);
    // Force scrape after delay to get new track info
    Future.delayed(const Duration(seconds: 2), () {
      _scrapeAllInfo();
    });
  }

  Future<void> toggleShuffle() async {
    store.toggleShuffle();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    await _webViewService.runJavascript(SpotifyActionsService.toggleShuffleScript);
  }

  Future<void> toggleRepeat() async {
    store.toggleRepeat();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    await _webViewService.runJavascript(SpotifyActionsService.toggleRepeatScript);
  }

  Future<void> toggleLike() async {
    store.toggleLike();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    await _webViewService.runJavascript(SpotifyActionsService.toggleLikeScript);
  }

  Future<void> seekToPosition(double percentage) async {
    store.startUserControl();

    // Immediate optimistic update of progress
    final newProgressMs = (store.durationMs.value * percentage).round();
    store.updateProgressSmoothly(newProgressMs);
    notifyListeners();

    await _webViewService.runJavascript(
      SpotifyActionsService.seekToPositionScript(percentage)
    );

    // Force scrape after a delay to sync with actual position
    Future.delayed(const Duration(milliseconds: 500), () {
      _scrapeAllInfo();
    });
  }

  // Search methods
  Future<void> search(String query) async {
    await _webViewService.runJavascript(SpotifyActionsService.searchScript(query));
  }

  Future<List<SearchResult>> searchAndGetResults(String query) async {
    await search(query);
    await Future.delayed(const Duration(seconds: 2));

    try {
      final result = await _webViewService.runJavascriptWithResult(
        SpotifyActionsService.searchResultsScript
      );
      if (result != null && result != 'null') {
        return SpotifyScraperService.parseSearchResults(result.toString());
      }
    } catch (e) {
      debugPrint('Error getting search results: $e');
    }
    return [];
  }

  // Navigation methods
  Future<void> navigateToLogin() async {
    // Just show the WebView, don't reload
    store.showWebView.value = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    // REMOVED: Don't reload URL - WebView should stay on current page
    debugPrint('Login: Showing WebView without reloading');
  }

  Future<void> navigateToSpotify() async {
    // REMOVED: Don't reload URL
    debugPrint('NavigateToSpotify: Disabled URL loading');
  }

  void openWebView() {
    store.showWebView.value = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
  }

  void hideWebView() {
    store.showWebView.value = false;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    if (!store.isLoggedIn.value) {
      navigateToSpotify();
    }
  }

  void setDebugWebViewVisible(bool value) {
    _debugWebViewVisible = value;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
  }

  Future<void> logout() async {
    // Clear user data from store
    store.clearUserData();

    // Execute logout script in WebView
    await _webViewService.runJavascript(SpotifyActionsService.logoutScript);

    // Navigate back to Spotify home
    await navigateToSpotify();

    // Trigger logout callback to show loading screen
    onLogout?.call();
  }

  // Track methods
  Future<void> playTrackAtIndex(int index) async {
    await _webViewService.runJavascript(
      SpotifyActionsService.playTrackAtIndexScript(index)
    );
  }

  Future<void> navigateToLikedSongs() async {
    debugPrint('🎵 [navigateToLikedSongs] Starting navigation to liked songs...');

    if (_webViewService.controller == null) {
      debugPrint('❌ WebView controller is null!');
      return;
    }

    store.isLoadingSongs.value = true;
    store.songs.value = [];
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });

    // Click on Liked Songs element to navigate there
    await openLikedSongsPage();
  }

  Future<void> openLikedSongsPage() async {
    if (_webViewService.controller == null) {
      debugPrint('❌ WebView controller is null in openLikedSongsPage!');
      return;
    }

    try {
      debugPrint('🔍 Attempting to click on Liked Songs element...');

      // Click on the Liked Songs element
      final result = await _webViewService.runJavascriptWithResult(
        SpotifyActionsService.openLikedSongsScript
      );

      debugPrint('🔍 Click result: $result');

      if (result == true || result == 'true') {
        debugPrint('✅ Successfully clicked Liked Songs element, waiting for navigation...');

        // Wait for the page to load
        await Future.delayed(const Duration(seconds: 2));

        // Initialize the PlaylistController and get initial batch
        await initializePlaylistController();
      } else {
        debugPrint('⚠️ Could not find Liked Songs element to click, result: $result');
        // Fallback to direct navigation if clicking fails
        await _navigateToLikedSongsOldMethod();
      }
    } catch (e) {
      debugPrint('❌ Error opening liked songs: $e');
      // Fallback to the old method if the new one fails
      await _navigateToLikedSongsOldMethod();
    }
  }

  Future<void> _navigateToLikedSongsOldMethod() async {
    // REMOVED: Don't reload URL - must use click navigation
    debugPrint('Fallback navigation disabled - using click navigation only');
    await Future.delayed(const Duration(seconds: 2));

    // Initialize the PlaylistController and get initial batch
    await initializePlaylistController();
  }

  Future<void> initializePlaylistController() async {
    // PlaylistController is already initialized when scripts are loaded
    debugPrint('✅ PlaylistController already initialized from loaded scripts');

    // Small delay to ensure DOM is ready
    await Future.delayed(const Duration(milliseconds: 500));

    // Get initial batch of tracks using pc.get()
    await updateTracksFromController();
  }

  Future<void> updateTracksFromController() async {
    try {
      final result = await _webViewService.runJavascriptWithResult(
        SpotifyActionsService.getLoadedTracksScript
      );

      if (result != null && result != 'null') {
        final data = jsonDecode(result.toString());
        final tracks = data['tracks'] as List;

        store.songs.value = tracks.map((track) => Song(
          index: track['position'] ?? track['index'] ?? 0,
          title: track['title'] ?? '',
          artist: (track['artists'] as List?)?.join(', ') ?? '',
          album: track['album'] ?? '',
          imageUrl: track['coverUrl'],
          duration: track['duration'],
        )).toList();

        debugPrint('Updated songs: ${store.songs.value.length} tracks');
        store.isLoadingSongs.value = false;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          notifyListeners();
        });
      }
    } catch (e) {
      debugPrint('Error updating tracks from controller: $e');
      store.isLoadingSongs.value = false;
    }
  }

  Future<void> scrapeSongs({bool append = false}) async {
    if (_webViewService.controller == null) return;

    try {
      final result = await _webViewService.runJavascriptWithResult(
        SpotifyActionsService.scrapeSongsScript
      );

      if (result != null && result != 'null' && result != '[]') {
        final newSongs = SpotifyScraperService.parseSongs(result.toString());

        if (append) {
          // When appending, we need to filter out duplicates
          final existingSongTitles = store.songs.value.map((s) => '${s.title}-${s.artist}').toSet();
          final uniqueNewSongs = newSongs.where((song) =>
            !existingSongTitles.contains('${song.title}-${song.artist}')
          ).toList();

          if (uniqueNewSongs.isNotEmpty) {
            store.songs.value = [...store.songs.value, ...uniqueNewSongs];
            debugPrint('Added ${uniqueNewSongs.length} new songs (total: ${store.songs.value.length})');
          }
        } else {
          // Replace all songs
          store.songs.value = newSongs;
          debugPrint('Loaded ${newSongs.length} songs');
        }
      }

      store.isLoadingSongs.value = false;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        notifyListeners();
      });
    } catch (e) {
      debugPrint('Error scraping songs: $e');
      store.isLoadingSongs.value = false;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        notifyListeners();
      });
    }
  }

  Future<void> scrollSpotifyPage(double offset) async {
    await _webViewService.runJavascript(
      SpotifyActionsService.scrollSpotifyPageScript(offset)
    );
  }

  Future<void> loadMoreSongs() async {
    // Prevent multiple simultaneous load requests
    if (store.isLoadingSongs.value) return;

    try {
      final currentUrl = await _webViewService.runJavascriptWithResult(
        'window.location.href'
      );

      if (currentUrl != null && currentUrl.toString().contains('/collection/tracks')) {
        store.isLoadingSongs.value = true;
        final previousCount = store.songs.value.length;

        // Use PlaylistController to load more tracks
        await _webViewService.runJavascriptWithResult(
          SpotifyActionsService.loadMoreSongsScript
        );

        // Update tracks from the controller
        await updateTracksFromController();

        final newCount = store.songs.value.length;
        if (newCount > previousCount) {
          debugPrint('Loaded ${newCount - previousCount} new songs (total: $newCount)');
        }

        // Always reset loading state
        store.isLoadingSongs.value = false;
      }
    } catch (e) {
      debugPrint('Error loading more songs: $e');
      store.isLoadingSongs.value = false;
    }
  }
}