import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

import '../models/song.dart';
import '../models/playback_state.dart' as app_models;
import '../models/search_result.dart';
import '../models/user.dart';
import '../models/homepage_item.dart';
import '../models/homepage_shortcut.dart';
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
    // Progress animation removed - using scraped values only
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
  List<HomepageSection> get homepageSections => store.homepageSections.value;
  List<HomepageShortcut> get homepageShortcuts => store.homepageShortcuts.value;
  String get shuffleMode => store.shuffleMode.value.value;
  String get repeatMode => store.repeatMode.value.value;
  String get currentTime => store.currentTime.value;
  String get duration => store.duration.value;
  int get progressMs => store.progressMs.value;
  int get durationMs => store.durationMs.value;
  double get progressPercentage => durationMs > 0 ? (progressMs / durationMs).clamp(0.0, 1.0) : 0.0;

  // Get current theme hex color
  String? get currentThemeHex => themeService.currentHexColor;

  // WebView flash opacity control
  final ValueNotifier<double> webViewOpacity = ValueNotifier(0.0);

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

    // Skip injection and checks if we're on the login page
    if (url != null && url.toString().contains('accounts.spotify.com')) {
      debugPrint('🔐 On login page, skipping injection and checks');
      return;
    }

    // Check if functions are already present
    final alreadyInjected = await _injectionMonitor.areFunctionsInjected();

    if (!alreadyInjected) {
      debugPrint('💉 [SpotifyController] Functions not found, injecting JavaScript...');
      await _injectJavaScript();
    } else {
      debugPrint('✅ [SpotifyController] Functions already present, skipping injection');
    }

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

      // Quick login status check
      await _checkLoginStatusQuick();
    } catch (e, stackTrace) {
      debugPrint('❌ Error injecting JavaScript: $e');
      debugPrint('Stack trace: $stackTrace');
    }
  }

  Future<void> _checkLoginStatusQuick() async {
    try {
      final loginStatus = await _webViewService.runJavascriptWithResult(
        'window.checkLoginStatus ? window.checkLoginStatus() : false'
      );

      final isLoggedIn = loginStatus == 'true' || loginStatus == true;
      debugPrint('🔐 Quick login check: ${isLoggedIn ? "Logged In" : "Not Logged In"}');

      // Update store immediately
      store.isLoggedIn.value = isLoggedIn;

      // Notify listeners to update UI immediately
      WidgetsBinding.instance.addPostFrameCallback((_) {
        notifyListeners();
      });

      // If on login page and user is logged in, navigate back to home
      final currentUrl = await _webViewService.controller?.getUrl();
      if (isLoggedIn && currentUrl != null && currentUrl.toString().contains('accounts.spotify.com')) {
        debugPrint('✅ Login completed, navigating back to Spotify...');
        await _webViewService.controller?.loadUrl(
          urlRequest: URLRequest(url: WebUri('https://open.spotify.com'))
        );
      }

      // If not logged in and on Spotify main page, update UI to show login prompt
      if (!isLoggedIn && currentUrl != null && !currentUrl.toString().contains('accounts.spotify.com')) {
        debugPrint('📍 User needs to log in - showing login prompt');
        // Make sure homepage sections are empty so loading doesn't appear
        store.homepageSections.value = [];
      }
    } catch (e) {
      debugPrint('Error in quick login check: $e');
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

    debugPrint('✅ [SpotifyController] Starting periodic scraping (every 500ms)');
    _scrapingTimer = Timer.periodic(const Duration(milliseconds: 500), (timer) {
      if (store.isInitialized.value && !store.showWebView.value) {
        _scrapeAllInfo();
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

    // Check if we're on login page and need to monitor for completion
    final currentUrl = await _webViewService.controller?.getUrl();
    if (currentUrl != null && currentUrl.toString().contains('accounts.spotify.com')) {
      await _monitorLoginCompletion();
      return; // Skip normal scraping on login page
    }

    try {
      // Batch scrape all info at once - functions return JSON strings
      final playbackResult = await _webViewService.runJavascriptWithResult(
        'window.getPlayingInfo()'
      );

      final userResult = await _webViewService.runJavascriptWithResult(
        'window.getUserInfo()'
      );

      final homepageResult = await _webViewService.runJavascriptWithResult(
        'window.getHomepageSections ? window.getHomepageSections() : "[]"'
      );

      final shortcutsResult = await _webViewService.runJavascriptWithResult(
        'window.getHomepageShortcuts ? window.getHomepageShortcuts() : "[]"'
      );

      app_models.PlaybackState? newState;
      User? newUser;
      List<HomepageSection>? newHomepageSections;
      List<HomepageShortcut>? newHomepageShortcuts;

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

      // Parse homepage sections
      if (homepageResult != null && homepageResult != 'null' && homepageResult != '[]') {
        final String jsonString = homepageResult.toString();
        newHomepageSections = SpotifyScraperService.parseHomepageSections(jsonString);
        if (newHomepageSections.isNotEmpty) {
          store.homepageSections.value = newHomepageSections;
        }
      }

      // Parse homepage shortcuts
      if (shortcutsResult != null && shortcutsResult != 'null' && shortcutsResult != '[]') {
        final String jsonString = shortcutsResult.toString();
        newHomepageShortcuts = SpotifyScraperService.parseHomepageShortcuts(jsonString);
        if (newHomepageShortcuts.isNotEmpty) {
          store.homepageShortcuts.value = newHomepageShortcuts;
        }
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

      // Enforce 75% volume limit (runs every scrape cycle)
      await _enforceVolumeLimit();
    } catch (e) {
      debugPrint('❌ [SpotifyController] Error scraping: $e');
    }
  }

  // Enforce 75% volume limit in the background
  Future<void> _enforceVolumeLimit() async {
    try {
      final result = await _webViewService.runJavascriptWithResult(
        'window.spotifyEnforceVolumeLimit && window.spotifyEnforceVolumeLimit()'
      );

      if (result != null && result != 'null') {
        final data = jsonDecode(result.toString());

        // Only log when volume was actually reduced
        if (data['success'] == true && data['previousVolume'] != null) {
          debugPrint('🔊 [VolumeLimit] ${data['message']}');
        }
      }
    } catch (e) {
      // Silently fail - volume limiting is not critical
      // debugPrint('Error enforcing volume limit: $e');
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

  // Navigation methods
  Future<void> navigateToHomepage() async {
    debugPrint('🏠 Navigating to homepage...');

    try {
      // Use SPA navigation to go back to homepage
      final result = await _webViewService.runJavascriptWithResult('''
        (function() {
          try {
            const history = window.history;
            const targetUrl = 'https://open.spotify.com';

            // Push state without reload
            history.pushState({}, '', targetUrl);

            // Trigger a popstate event to let Spotify's router handle it
            const popstateEvent = new PopStateEvent('popstate', { state: {} });
            window.dispatchEvent(popstateEvent);

            return "spa_navigation";
          } catch (e) {
            console.error('Homepage navigation failed:', e);
            return false;
          }
        })()
      ''');

      debugPrint('🏠 Homepage navigation result: $result');

      if (result == '"spa_navigation"') {
        debugPrint('🚀 Using SPA navigation to Homepage (no page reload)');
        await Future.delayed(const Duration(milliseconds: 800));
        // Force scrape to update homepage sections
        _scrapeAllInfo();
      } else {
        debugPrint('⚠️ Could not navigate to Homepage via SPA, trying direct navigation');
        await Future.delayed(const Duration(milliseconds: 1000));
      }
    } catch (e) {
      debugPrint('❌ Error navigating to homepage: $e');
      await Future.delayed(const Duration(milliseconds: 1000));
    }
  }

  // Search methods
  Future<void> navigateToSearchPage() async {
    debugPrint('🔍 Navigating to search page...');

    try {
      final result = await _webViewService.runJavascriptWithResult(
        'window.spotifyNavigateToSearch()'
      );

      debugPrint('🔍 Search navigation result: $result');

      if (result == '"spa_navigation"') {
        debugPrint('🚀 Using SPA navigation to Search (no page reload)');
        await Future.delayed(const Duration(milliseconds: 800));
      } else if (result == 'true' || result == true) {
        debugPrint('✅ Successfully navigated to Search page');
        await Future.delayed(const Duration(milliseconds: 800));
      } else {
        debugPrint('⚠️ Could not navigate to Search page');
        await Future.delayed(const Duration(milliseconds: 1000));
      }
    } catch (e) {
      debugPrint('❌ Error navigating to search: $e');
      await Future.delayed(const Duration(milliseconds: 1000));
    }
  }

  Future<void> typeInSearchBox(String query) async {
    debugPrint('🔍 Typing in search box: $query');
    await _webViewService.runJavascript(
      'window.spotifySearch(${jsonEncode(query)})'
    );
  }

  Future<void> search(String query) async {
    await _webViewService.runJavascript(SpotifyActionsService.searchScript(query));
  }

  Future<List<SearchResult>> searchAndGetResults(String query) async {
    // Type in the search box instead of using the old method
    await typeInSearchBox(query);
    await Future.delayed(const Duration(seconds: 2));

    try {
      final result = await _webViewService.runJavascriptWithResult(
        'window.getSearchResults ? window.getSearchResults() : "[]"'
      );
      if (result != null && result != 'null' && result != '[]') {
        return SpotifyScraperService.parseSearchResults(result.toString());
      }
    } catch (e) {
      debugPrint('Error getting search results: $e');
    }
    return [];
  }

  Future<void> playSearchResultAtIndex(int index) async {
    debugPrint('🎵 Playing search result #$index');
    await _webViewService.runJavascript(
      'window.playSearchResult && window.playSearchResult($index)'
    );
  }

  // Monitor login completion when on login page
  Future<void> _monitorLoginCompletion() async {
    try {
      // Check if login is successful by looking for absence of login button
      final loginStatus = await _webViewService.runJavascriptWithResult(
        'window.checkLoginStatus ? window.checkLoginStatus() : false'
      );

      if (loginStatus == 'true' || loginStatus == true) {
        debugPrint('✅ Login successful! Redirecting to Spotify...');
        store.isLoggedIn.value = true;

        // Navigate back to Spotify
        await _webViewService.controller?.loadUrl(
          urlRequest: URLRequest(url: WebUri('https://open.spotify.com'))
        );

        // Hide WebView after successful login
        await Future.delayed(const Duration(seconds: 2));
        store.showWebView.value = false;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          notifyListeners();
        });
      }
    } catch (e) {
      debugPrint('Error monitoring login: $e');
    }
  }

  // Navigation methods
  Future<void> navigateToLogin() async {
    debugPrint('🔐 Navigating to login page...');

    // Show the WebView first
    store.showWebView.value = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });

    // Wait a moment for WebView to be visible and ready
    await Future.delayed(const Duration(milliseconds: 300));

    // Check if controller is ready
    if (_webViewService.controller == null) {
      debugPrint('⚠️ WebView controller not ready, waiting...');
      await Future.delayed(const Duration(milliseconds: 500));
    }

    // Navigate to login page
    const loginUrl = 'https://accounts.spotify.com/v2/login';
    debugPrint('📍 Loading URL: $loginUrl');

    // Try JavaScript navigation first (more reliable)
    final jsNavResult = await _webViewService.runJavascriptWithResult(
      'window.location.href = "$loginUrl"; true;'
    );

    debugPrint('JS navigation result: $jsNavResult');

    // If JS navigation didn't work, try loadUrl
    if (jsNavResult != 'true' && jsNavResult != true) {
      debugPrint('JS navigation may have failed, trying loadUrl...');
      await _webViewService.controller?.loadUrl(
        urlRequest: URLRequest(url: WebUri(loginUrl))
      );
    }

    // Wait and check if we actually navigated
    await Future.delayed(const Duration(milliseconds: 500));
    final currentUrl = await _webViewService.controller?.getUrl();
    debugPrint('Current URL after navigation attempt: $currentUrl');

    if (currentUrl != null && !currentUrl.toString().contains('accounts.spotify.com')) {
      debugPrint('⚠️ Navigation failed, forcing navigation with loadUrl');
      await _webViewService.controller?.loadUrl(
        urlRequest: URLRequest(url: WebUri(loginUrl))
      );
    }

    debugPrint('Login: Navigation complete');
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

  Future<void> _flashWebView() async {
    debugPrint('✨ Flashing WebView to show library loaded');

    // Animate opacity from 0.0 to 1.0
    webViewOpacity.value = 1.0;

    // Wait for 800ms to let user see the library
    await Future.delayed(const Duration(milliseconds: 800));

    // Fade back to 0.0
    webViewOpacity.value = 0.0;
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
    debugPrint('🎵 Attempting to play track #$index');

    // Try multiple times to find and play the track
    int attempts = 0;
    const maxAttempts = 5;

    while (attempts < maxAttempts) {
      // Scroll to the track
      await syncScrollToTrackIndex(index);

      // Wait for scroll and DOM update
      await Future.delayed(Duration(milliseconds: attempts == 0 ? 300 : 600));

      // Try to play the track
      final result = await _webViewService.runJavascriptWithResult(
        SpotifyActionsService.playTrackAtIndexScript(index)
      );

      // Check if successful
      if (result == 'true') {
        debugPrint('✅ Successfully played track #$index on attempt ${attempts + 1}');
        break;
      }

      attempts++;
      if (attempts < maxAttempts) {
        debugPrint('⚠️ Track #$index not found, retrying... (attempt ${attempts + 1}/$maxAttempts)');
        // Wait a bit before retrying
        await Future.delayed(const Duration(milliseconds: 500));
      } else {
        debugPrint('❌ Failed to play track #$index after $maxAttempts attempts');
      }
    }
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
      debugPrint('🔍 Attempting to navigate to Liked Songs...');

      // Click on the Liked Songs element
      final result = await _webViewService.runJavascriptWithResult(
        SpotifyActionsService.openLikedSongsScript
      );

      debugPrint('🔍 Navigation result: $result');

      // Check the result and handle accordingly
      if (result == '"spa_navigation"') {
        debugPrint('🚀 Using SPA navigation (no page reload)');
        await Future.delayed(const Duration(milliseconds: 1000));
      } else if (result == '"navigating_to_library"') {
        debugPrint('📚 Navigating to library page first...');
        await Future.delayed(const Duration(seconds: 2));

        // Try again to click on Liked Songs
        final retryResult = await _webViewService.runJavascriptWithResult(
          SpotifyActionsService.openLikedSongsScript
        );

        debugPrint('🔍 Retry result: $retryResult');

        if (retryResult == 'true' || retryResult == true || retryResult == '"spa_navigation"') {
          debugPrint('✅ Successfully navigated to Liked Songs!');
        } else {
          debugPrint('⚠️ Still could not find Liked Songs element');
        }
      } else if (result == true || result == 'true') {
        debugPrint('✅ Successfully clicked Liked Songs element!');
      } else {
        debugPrint('⚠️ Could not find Liked Songs element, result: $result');
      }

      // Wait for navigation to complete
      await Future.delayed(const Duration(seconds: 2));

      // Initialize the PlaylistController and get initial batch
      await initializePlaylistController();
    } catch (e) {
      debugPrint('❌ Error opening liked songs: $e');
      // Try to initialize anyway
      await initializePlaylistController();
    }
  }


  Future<void> initializePlaylistController() async {
    // PlaylistController is already initialized when scripts are loaded
    debugPrint('✅ PlaylistController already initialized from loaded scripts');

    // Wait longer for Spotify to load the tracks in DOM
    await Future.delayed(const Duration(seconds: 2));

    // Force a rescan of current tracks in DOM using window function
    await _webViewService.runJavascript(
      'window.PlaylistController && window.PlaylistController.reset()'
    );

    // Small additional delay
    await Future.delayed(const Duration(milliseconds: 500));

    // Get initial batch of tracks with flash on first load
    await updateTracksFromController(isInitialLoad: true);

    // If we got initial tracks, try to load more immediately
    if (store.songs.value.isNotEmpty) {
      debugPrint('📚 Got ${store.songs.value.length} initial tracks, loading more...');
      await loadMoreSongs();
    }
  }

  Future<void> updateTracksFromController({bool isInitialLoad = false}) async {
    try {
      final result = await _webViewService.runJavascriptWithResult(
        SpotifyActionsService.getLoadedTracksScript
      );

      debugPrint('📚 PlaylistController result: $result');

      if (result != null && result != 'null') {
        final data = jsonDecode(result.toString());

        if (data == null) {
          debugPrint('⚠️ Parsed data is null');
          store.isLoadingSongs.value = false;
          return;
        }

        final tracks = data['tracks'] as List?;
        if (tracks == null || tracks.isEmpty) {
          debugPrint('⚠️ No tracks found in data. Checking DOM...');

          // Try to debug what's in the DOM
          final domCheck = await _webViewService.runJavascriptWithResult('''
            JSON.stringify({
              trackRows: document.querySelectorAll('[data-testid="tracklist-row"]').length,
              url: window.location.href,
              hasController: !!window.PlaylistController,
              tracksInController: window.PlaylistController ? window.PlaylistController.tracks.size : 0
            })
          ''');
          debugPrint('🔍 DOM Check: $domCheck');

          store.isLoadingSongs.value = false;
          return;
        }

        store.songs.value = tracks.map((track) => Song.fromJson({
          'index': track['position'] ?? track['index'] ?? 0,
          'title': track['title'] ?? '',
          'artist': (track['artists'] as List?)?.join(', ') ?? '',
          'album': track['album'] ?? '',
          'image': track['coverUrl'],
          'duration': track['duration'],
        })).toList();

        debugPrint('✅ Updated songs: ${store.songs.value.length} tracks');

        // Flash the WebView briefly ONLY on initial library load
        if (isInitialLoad && store.songs.value.isNotEmpty) {
          await _flashWebView();
        }

        store.isLoadingSongs.value = false;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          notifyListeners();
        });
      } else {
        debugPrint('⚠️ Result is null or "null"');
        store.isLoadingSongs.value = false;
      }
    } catch (e, stackTrace) {
      debugPrint('❌ Error updating tracks from controller: $e');
      debugPrint('Stack trace: $stackTrace');
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

  Future<void> syncScrollWithWebView(double scrollPercentage) async {
    // Sync scroll position with WebView
    final result = await _webViewService.runJavascriptWithResult(
      'window.syncScrollToPosition($scrollPercentage)'
    );

    if (result != null) {
      debugPrint('📜 Sync scroll result: $result');

      // After scrolling, update tracks if new ones are loaded
      await Future.delayed(const Duration(milliseconds: 500));
      await updateTracksFromController();
    }
  }

  Future<void> syncScrollToTrackIndex(int trackIndex) async {
    // Scroll to a specific track by its index number
    final result = await _webViewService.runJavascriptWithResult(
      'window.scrollToTrackByIndex($trackIndex)'
    );

    if (result != null) {
      debugPrint('📜 Scrolled to track #$trackIndex: $result');

      // After scrolling, update tracks if new ones are loaded
      await Future.delayed(const Duration(milliseconds: 800));
      await updateTracksFromController();
    }
  }

  // Homepage item methods
  Future<void> playHomepageItem(String itemId, String itemType) async {
    debugPrint('🎵 Playing homepage item: $itemId (type: $itemType)');

    // Escape quotes in itemId to prevent JavaScript errors
    final escapedItemId = itemId.replaceAll('"', '\\"').replaceAll("'", "\\'");
    final escapedItemType = itemType.replaceAll('"', '\\"').replaceAll("'", "\\'");

    final result = await _webViewService.runJavascriptWithResult(
      'window.playHomepageItem && window.playHomepageItem("$escapedItemId", "$escapedItemType")'
    );

    if (result == 'true' || result == true) {
      debugPrint('✅ Successfully triggered play for homepage item');
      // Force scrape after a short delay to get updated playback info
      Future.delayed(const Duration(milliseconds: 1000), () {
        _scrapeAllInfo();
      });
    } else {
      debugPrint('⚠️ Failed to play homepage item');
    }
  }

  Future<void> navigateToHomepageItem(String href) async {
    debugPrint('🔗 Navigating to homepage item: $href');

    final result = await _webViewService.runJavascriptWithResult(
      'window.navigateToHomepageItem && window.navigateToHomepageItem("$href")'
    );

    if (result == 'true' || result == true) {
      debugPrint('✅ Successfully navigated to homepage item');
      // Give time for navigation to complete
      await Future.delayed(const Duration(milliseconds: 1000));
    } else {
      debugPrint('⚠️ Failed to navigate to homepage item');
    }
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

        // Get the last loaded track position
        final lastTrackPosition = store.songs.value.isNotEmpty
            ? store.songs.value.last.index
            : 0;

        debugPrint('📜 Scrolling to track #$lastTrackPosition to load more...');

        // Call the window function to scroll
        final scrollResult = await _webViewService.runJavascriptWithResult(
          'window.scrollToLoadMore($lastTrackPosition)'
        );

        if (scrollResult != null) {
          debugPrint('📜 Scroll result: $scrollResult');
        }

        // Wait for Spotify to load new tracks
        await Future.delayed(const Duration(seconds: 2));

        // Force rescan after scroll using window function
        await _webViewService.runJavascript(
          'window.PlaylistController && window.PlaylistController._scanCurrentTracks()'
        );

        // Wait a bit more for DOM updates
        await Future.delayed(const Duration(milliseconds: 500));

        // Update tracks from the controller
        await updateTracksFromController();

        final newCount = store.songs.value.length;
        if (newCount > previousCount) {
          debugPrint('✅ Loaded ${newCount - previousCount} new songs (total: $newCount)');
        } else {
          debugPrint('⚠️ No new songs loaded. May have reached the end.');
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