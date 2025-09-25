import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:audio_service/audio_service.dart';

import '../models/song.dart';
import '../models/playback_state.dart' as app_models;
import '../models/search_result.dart';
import '../models/user.dart';
import '../services/webview_service.dart';
import '../services/spotify_scraper_service.dart';
import '../services/spotify_actions_service.dart';
import '../services/theme_service.dart';
import '../services/audio_handler_service.dart';
import '../stores/spotify_store.dart';
import '../main.dart';

class SpotifyController extends ChangeNotifier {
  final WebViewService _webViewService = WebViewService();
  final SpotifyStore store = SpotifyStore();
  final ThemeService themeService = ThemeService();
  Timer? _scrapingTimer;
  Timer? _progressTimer;
  String? _lastAlbumArt;

  SpotifyController() {
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
    }
  }

  void _updateAudioServiceMetadata(app_models.PlaybackState newState) {
    if (audioHandler is SpotifyAudioHandler) {
      final handler = audioHandler as SpotifyAudioHandler;

      // Update media item
      if (newState.currentTrack != null) {
        handler.setMediaItem(
          title: newState.currentTrack!,
          artist: newState.currentArtist ?? 'Unknown Artist',
          artUri: newState.currentAlbumArt,
          duration: Duration(milliseconds: newState.durationMs),
        );
      }

      // Update playback state
      handler.updatePlaybackState(
        isPlaying: newState.isPlaying,
        position: Duration(milliseconds: newState.progressMs),
        duration: Duration(milliseconds: newState.durationMs),
      );
    }
  }

  @override
  void dispose() {
    _scrapingTimer?.cancel();
    _progressTimer?.cancel();
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
  }

  Future<void> onLoadStop(InAppWebViewController controller, WebUri? url) async {
    debugPrint('🌐 [SpotifyController] Page finished loading: $url');
    debugPrint('💉 [SpotifyController] Injecting JavaScript scraping functions...');
    await _injectJavaScript();
    store.isInitialized.value = true;
    debugPrint('✅ [SpotifyController] WebView initialized and ready for scraping');
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
    await _webViewService.evaluateJavascript(SpotifyScraperService.initScript);
  }

  void _startPeriodicScraping() {
    _scrapingTimer?.cancel();

    debugPrint('⏰ [SpotifyController] Starting periodic scraping in 2 seconds...');

    // Start periodic scraping after initial delay
    Timer(const Duration(seconds: 2), () {
      debugPrint('✅ [SpotifyController] Periodic scraping started (every 1 second)');
      _scrapingTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
        if (store.isInitialized.value && !store.showWebView.value) {
          _scrapeAllInfo();
        }
      });
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

    try {
      // Batch scrape all info at once
      final playbackResult = await _webViewService.evaluateJavascriptWithResult(
        'JSON.stringify(getPlayingInfo())'
      );

      final userResult = await _webViewService.evaluateJavascriptWithResult(
        'JSON.stringify(getUserInfo())'
      );

      app_models.PlaybackState? newState;
      User? newUser;

      // Parse playback info
      if (playbackResult != null && playbackResult != 'null') {
        final String jsonString = playbackResult.toString();
        final cleanJson = jsonString.replaceAll(r'\"', '"');
        if (cleanJson.contains('track')) {
          newState = SpotifyScraperService.parsePlaybackInfo(cleanJson);
        }
      }

      // Parse user info
      if (userResult != null && userResult != 'null') {
        final String jsonString = userResult.toString();
        final cleanJson = jsonString.replaceAll(r'\"', '"');
        if (cleanJson.contains('isLoggedIn')) {
          newUser = SpotifyScraperService.parseUserInfo(cleanJson);
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
    await _webViewService.evaluateJavascript(SpotifyActionsService.playScript);
  }

  Future<void> pause() async {
    store.setPlaying(false);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    await _webViewService.evaluateJavascript(SpotifyActionsService.pauseScript);
  }

  Future<void> next() async {
    store.startUserControl();
    await _webViewService.evaluateJavascript(SpotifyActionsService.nextScript);
    // Force scrape after delay to get new track info
    Future.delayed(const Duration(seconds: 2), () {
      _scrapeAllInfo();
    });
  }

  Future<void> previous() async {
    store.startUserControl();
    await _webViewService.evaluateJavascript(SpotifyActionsService.previousScript);
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
    await _webViewService.evaluateJavascript(SpotifyActionsService.toggleShuffleScript);
  }

  Future<void> toggleRepeat() async {
    store.toggleRepeat();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    await _webViewService.evaluateJavascript(SpotifyActionsService.toggleRepeatScript);
  }

  Future<void> toggleLike() async {
    store.toggleLike();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    await _webViewService.evaluateJavascript(SpotifyActionsService.toggleLikeScript);
  }

  Future<void> seekToPosition(double percentage) async {
    store.startUserControl();

    // Immediate optimistic update of progress
    final newProgressMs = (store.durationMs.value * percentage).round();
    store.updateProgressSmoothly(newProgressMs);
    notifyListeners();

    await _webViewService.evaluateJavascript(
      SpotifyActionsService.seekToPositionScript(percentage)
    );

    // Force scrape after a delay to sync with actual position
    Future.delayed(const Duration(milliseconds: 500), () {
      _scrapeAllInfo();
    });
  }

  // Search methods
  Future<void> search(String query) async {
    await _webViewService.evaluateJavascript(SpotifyActionsService.searchScript(query));
  }

  Future<List<SearchResult>> searchAndGetResults(String query) async {
    await search(query);
    await Future.delayed(const Duration(seconds: 2));

    try {
      final result = await _webViewService.evaluateJavascriptWithResult(
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
    store.showWebView.value = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    await _webViewService.loadUrl('https://accounts.spotify.com/login');
  }

  Future<void> navigateToSpotify() async {
    await _webViewService.loadUrl('https://open.spotify.com');
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

  Future<void> logout() async {
    await _webViewService.evaluateJavascript(SpotifyActionsService.logoutScript);
  }

  // Track methods
  Future<void> playTrackAtIndex(int index) async {
    await _webViewService.evaluateJavascript(
      SpotifyActionsService.playTrackAtIndexScript(index)
    );
  }

  Future<void> navigateToLikedSongs() async {
    store.isLoadingSongs.value = true;
    store.songs.value = [];
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });

    await _webViewService.loadUrl('https://open.spotify.com/collection/tracks');

    // Wait for page to load
    await Future.delayed(const Duration(seconds: 2));
    await scrapeSongs();
  }

  Future<void> scrapeSongs() async {
    if (_webViewService.controller == null) return;

    try {
      final result = await _webViewService.evaluateJavascriptWithResult(
        SpotifyActionsService.scrapeSongsScript
      );

      if (result != null && result != 'null' && result != '[]') {
        store.songs.value = SpotifyScraperService.parseSongs(result.toString());
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
    await _webViewService.evaluateJavascript(
      SpotifyActionsService.scrollSpotifyPageScript(offset)
    );
  }

  Future<void> loadMoreSongs() async {
    await _webViewService.evaluateJavascript(SpotifyActionsService.loadMoreSongsScript);
    await Future.delayed(const Duration(milliseconds: 500));
    await scrapeSongs();
  }
}