import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

import '../models/song.dart';
import '../models/playback_state.dart';
import '../models/search_result.dart';
import '../services/webview_service.dart';
import '../services/spotify_scraper_service.dart';
import '../services/spotify_actions_service.dart';
import '../services/theme_service.dart';
import '../stores/spotify_store.dart';

class SpotifyController extends ChangeNotifier {
  final WebViewService _webViewService = WebViewService();
  final SpotifyStore store = SpotifyStore();
  final ThemeService themeService = ThemeService();
  Timer? _scrapingTimer;
  String? _lastAlbumArt;

  SpotifyController() {
    _startPeriodicScraping();
  }

  @override
  void dispose() {
    _scrapingTimer?.cancel();
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
    debugPrint('Page finished loading: $url');
    await _injectJavaScript();
    store.isInitialized.value = true;
    // Defer notification to next frame to avoid calling during build
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

    // Start periodic scraping after initial delay
    Timer(const Duration(seconds: 2), () {
      _scrapingTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
        if (store.isInitialized.value && !store.showWebView.value) {
          _scrapeCurrentInfo();
          _scrapeUserInfo();
        }
      });
    });
  }

  Future<void> _scrapeCurrentInfo() async {
    if (_webViewService.controller == null) return;

    // Skip if user is actively controlling
    if (store.isUserControlling.value) return;

    try {
      final result = await _webViewService.evaluateJavascriptWithResult(
        'JSON.stringify(getPlayingInfo())'
      );

      if (result != null && result != 'null') {
        final String jsonString = result.toString();
        final cleanJson = jsonString.replaceAll(r'\"', '"');

        if (cleanJson.contains('track')) {
          final newState = SpotifyScraperService.parsePlaybackInfo(cleanJson);
          store.updateFromScrapedData(newState);

          // Update theme if album art changed
          if (newState?.currentAlbumArt != null &&
              newState?.currentAlbumArt != _lastAlbumArt) {
            _lastAlbumArt = newState?.currentAlbumArt;
            themeService.updateThemeFromImageUrl(_lastAlbumArt);
          }

          // Defer notification to avoid calling during build
          WidgetsBinding.instance.addPostFrameCallback((_) {
            notifyListeners();
          });
        }
      }
    } catch (e) {
      debugPrint('Error scraping: $e');
    }
  }

  Future<void> _scrapeUserInfo() async {
    if (_webViewService.controller == null) return;

    try {
      final result = await _webViewService.evaluateJavascriptWithResult(
        'JSON.stringify(getUserInfo())'
      );

      if (result != null && result != 'null') {
        final String jsonString = result.toString();
        final cleanJson = jsonString.replaceAll(r'\"', '"');

        if (cleanJson.contains('isLoggedIn')) {
          final newUser = SpotifyScraperService.parseUserInfo(cleanJson);
          store.updateUserInfo(newUser);
          // Defer notification to avoid calling during build
          WidgetsBinding.instance.addPostFrameCallback((_) {
            notifyListeners();
          });
        }
      }
    } catch (e) {
      debugPrint('Error scraping user info: $e');
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
      _scrapeCurrentInfo();
    });
  }

  Future<void> previous() async {
    store.startUserControl();
    await _webViewService.evaluateJavascript(SpotifyActionsService.previousScript);
    // Force scrape after delay to get new track info
    Future.delayed(const Duration(seconds: 2), () {
      _scrapeCurrentInfo();
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