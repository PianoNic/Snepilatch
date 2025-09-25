import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

import '../models/song.dart';
import '../models/playback_state.dart';
import '../models/user.dart';
import '../models/search_result.dart';
import '../services/webview_service.dart';
import '../services/spotify_scraper_service.dart';
import '../services/spotify_actions_service.dart';

class SpotifyController extends ChangeNotifier {
  final WebViewService _webViewService = WebViewService();
  Timer? _scrapingTimer;

  // State
  bool _isInitialized = false;
  PlaybackState _playbackState = PlaybackState();
  User _user = User.guest();
  List<Song> _songs = [];
  bool _isLoadingSongs = false;
  bool _showWebView = false;

  // ValueNotifiers for specific UI updates
  final ValueNotifier<bool> showWebViewNotifier = ValueNotifier<bool>(false);
  final ValueNotifier<bool> isLoggedInNotifier = ValueNotifier<bool>(false);

  // Getters
  bool get isInitialized => _isInitialized;
  bool get isPlaying => _playbackState.isPlaying;
  bool get isLoggedIn => _user.isLoggedIn;
  bool get showWebView => _showWebView;
  String? get currentTrack => _playbackState.currentTrack;
  String? get currentArtist => _playbackState.currentArtist;
  String? get currentAlbumArt => _playbackState.currentAlbumArt;
  String? get username => _user.username;
  String? get userEmail => _user.email;
  String? get userProfileImage => _user.profileImageUrl;
  List<Song> get songs => _songs;
  bool get isLoadingSongs => _isLoadingSongs;
  bool get isCurrentTrackLiked => _playbackState.isCurrentTrackLiked;
  String get shuffleMode => _playbackState.shuffleMode.value;
  String get repeatMode => _playbackState.repeatMode.value;

  SpotifyController() {
    _startPeriodicScraping();
  }

  @override
  void dispose() {
    _scrapingTimer?.cancel();
    showWebViewNotifier.dispose();
    isLoggedInNotifier.dispose();
    super.dispose();
  }

  // WebView initialization methods
  InAppWebViewSettings getWebViewSettings() => _webViewService.getSettings();

  void onWebViewCreated(InAppWebViewController controller) {
    _webViewService.setController(controller);
  }

  Future<void> onLoadStop(InAppWebViewController controller, WebUri? url) async {
    debugPrint('Page finished loading: $url');
    await _injectJavaScript();
    _isInitialized = true;
    // Schedule notification for next frame to avoid setState during build
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
    // Cancel any existing timer
    _scrapingTimer?.cancel();

    // Wait before starting periodic scraping
    Timer(const Duration(seconds: 2), () {
      _scrapingTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
        if (_isInitialized && !_showWebView) {
          _scrapeCurrentInfo();
          _scrapeUserInfo();
        }
      });
    });
  }

  Future<void> _scrapeCurrentInfo() async {
    if (_webViewService.controller == null) return;

    try {
      final result = await _webViewService.evaluateJavascriptWithResult(
        'JSON.stringify(getPlayingInfo())'
      );

      if (result != null && result != 'null') {
        final String jsonString = result.toString();
        final cleanJson = jsonString.replaceAll(r'\"', '"');

        if (cleanJson.contains('track')) {
          final newState = SpotifyScraperService.parsePlaybackInfo(cleanJson);
          if (newState != null && _hasPlaybackStateChanged(newState)) {
            _playbackState = newState;
            // Schedule notification for next frame to avoid setState during build
            WidgetsBinding.instance.addPostFrameCallback((_) {
              notifyListeners();
            });
          }
        }
      }
    } catch (e) {
      debugPrint('Error scraping: $e');
    }
  }

  bool _hasPlaybackStateChanged(PlaybackState newState) {
    return newState.currentTrack != _playbackState.currentTrack ||
           newState.currentArtist != _playbackState.currentArtist ||
           newState.isPlaying != _playbackState.isPlaying ||
           newState.currentAlbumArt != _playbackState.currentAlbumArt ||
           newState.isCurrentTrackLiked != _playbackState.isCurrentTrackLiked ||
           newState.shuffleMode != _playbackState.shuffleMode ||
           newState.repeatMode != _playbackState.repeatMode;
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
          if (newUser != null && _hasUserChanged(newUser)) {
            _user = newUser;
            isLoggedInNotifier.value = newUser.isLoggedIn;
            // Schedule notification for next frame to avoid setState during build
            WidgetsBinding.instance.addPostFrameCallback((_) {
              notifyListeners();
            });
          }
        }
      }
    } catch (e) {
      debugPrint('Error scraping user info: $e');
    }
  }

  bool _hasUserChanged(User newUser) {
    return newUser.isLoggedIn != _user.isLoggedIn ||
           newUser.username != _user.username ||
           newUser.email != _user.email ||
           newUser.profileImageUrl != _user.profileImageUrl;
  }

  // Playback control methods
  Future<void> play() async {
    await _webViewService.evaluateJavascript(SpotifyActionsService.playScript);
  }

  Future<void> pause() async {
    await _webViewService.evaluateJavascript(SpotifyActionsService.pauseScript);
  }

  Future<void> next() async {
    await _webViewService.evaluateJavascript(SpotifyActionsService.nextScript);
  }

  Future<void> previous() async {
    await _webViewService.evaluateJavascript(SpotifyActionsService.previousScript);
  }

  Future<void> toggleShuffle() async {
    await _webViewService.evaluateJavascript(SpotifyActionsService.toggleShuffleScript);
  }

  Future<void> toggleRepeat() async {
    await _webViewService.evaluateJavascript(SpotifyActionsService.toggleRepeatScript);
  }

  Future<void> toggleLike() async {
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
    _showWebView = true;
    showWebViewNotifier.value = true;
    // Schedule notification for next frame to avoid setState during build
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    await _webViewService.loadUrl('https://accounts.spotify.com/login');
  }

  Future<void> navigateToSpotify() async {
    await _webViewService.loadUrl('https://open.spotify.com');
  }

  void openWebView() {
    _showWebView = true;
    showWebViewNotifier.value = true;
    // Schedule notification for next frame to avoid setState during build
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
  }

  void hideWebView() {
    _showWebView = false;
    showWebViewNotifier.value = false;
    // Schedule notification for next frame to avoid setState during build
    WidgetsBinding.instance.addPostFrameCallback((_) {
      notifyListeners();
    });
    if (!_user.isLoggedIn) {
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
    _isLoadingSongs = true;
    _songs = [];
    // Schedule notification for next frame to avoid setState during build
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
        _songs = SpotifyScraperService.parseSongs(result.toString())
            .map((s) => s)
            .toList();
      }

      _isLoadingSongs = false;
      // Schedule notification for next frame to avoid setState during build
      WidgetsBinding.instance.addPostFrameCallback((_) {
        notifyListeners();
      });
    } catch (e) {
      debugPrint('Error scraping songs: $e');
      _isLoadingSongs = false;
      // Schedule notification for next frame to avoid setState during build
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