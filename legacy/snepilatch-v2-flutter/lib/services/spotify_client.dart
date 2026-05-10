import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:spotify/spotify.dart';
import 'package:snepilatch_v2/models/playback_progress.dart';
import 'package:snepilatch_v2/models/player_control_state.dart';
import 'package:snepilatch_v2/models/home_section.dart';
import 'package:snepilatch_v2/models/recently_played_item.dart';
import 'package:snepilatch_v2/models/song_info.dart';
import 'package:snepilatch_v2/models/user_profile.dart';
import 'package:snepilatch_v2/components/spotify_auth_modal.dart';
import 'package:snepilatch_v2/components/spotify_login_modal.dart';
import 'package:snepilatch_v2/services/websocket_server.dart';
import 'package:snepilatch_v2/services/spotify_token_storage.dart';

class SpotifyClient extends ChangeNotifier {
  String? _spotifyToken;
  SongInfo? _currentTrack;
  PlaybackProgress? _playbackProgress;
  PlayerControlState? _playerControlState;
  List<RecentlyPlayedItem> _recentlyPlayedItems = [];
  List<RecentlyPlayedItem> _homeShortcuts = [];
  List<HomeSection> _homeSections = [];
  List<RecentlyPlayedItem> _personalizedMixes = [];
  List<RecentlyPlayedItem> _newReleases = [];
  List<RecentlyPlayedItem> _topArtists = [];
  List<RecentlyPlayedItem> _userPlaylists = [];
  UserProfile? _userProfile;
  final WebSocketServer _webSocketServer = WebSocketServer();
  late InAppWebViewController _webViewController;
  SpotifyApi? _spotifyApi;
  final SpotifyTokenStorage tokenStorage = SpotifyTokenStorage();
  bool _tokenInitializationFailed = false;
  bool _isLoggedIn = false;
  bool _loginModalShown = false;
  Timer? _loginDebounceTimer;
  HeadlessInAppWebView? _headlessWebView;

  final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

  bool get tokenInitializationFailed => _tokenInitializationFailed;
  bool get isLoggedIn => _isLoggedIn;

  String? get spotifyToken => _spotifyToken;
  SongInfo? get currentTrack => _currentTrack;
  PlaybackProgress? get playbackProgress => _playbackProgress;
  PlayerControlState? get playerControlState => _playerControlState;
  List<RecentlyPlayedItem> get recentlyPlayedItems => _recentlyPlayedItems;
  List<RecentlyPlayedItem> get homeShortcuts => _homeShortcuts;
  List<HomeSection> get homeSections => _homeSections;
  List<RecentlyPlayedItem> get personalizedMixes => _personalizedMixes;
  List<RecentlyPlayedItem> get newReleases => _newReleases;
  List<RecentlyPlayedItem> get topArtists => _topArtists;
  List<RecentlyPlayedItem> get userPlaylists => _userPlaylists;
  UserProfile? get userProfile => _userProfile;
  SpotifyApi? get spotifyApi => _spotifyApi;
  Color? get dominantColor => _currentTrack?.dominantColor;

  Future<void> initialize() async {
    await _webSocketServer.start();
    _webSocketServer.onMessage = handleServerMessage;

    _headlessWebView = HeadlessInAppWebView(
      initialSettings: InAppWebViewSettings(
        userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 OPR/124.0.0.0",
        javaScriptEnabled: true,
        mediaPlaybackRequiresUserGesture: false,
        allowsInlineMediaPlayback: true,
        mixedContentMode: MixedContentMode.MIXED_CONTENT_ALWAYS_ALLOW,
      ),
      initialUrlRequest: URLRequest(url: WebUri("https://open.spotify.com")),
      onPermissionRequest: (_, permissionRequest) async =>
          PermissionResponse(
            resources: permissionRequest.resources,
            action: PermissionResponseAction.GRANT,
          ),
      onLoadStop: (controller, url) async {
        _webViewController = controller;
        await controller.injectJavascriptFileFromAsset(assetFilePath: 'assets/js/spotify-adblocker.js');
        await controller.injectJavascriptFileFromAsset(assetFilePath: 'assets/js/spotify.user.js');

        // Wait a bit for the page to fully initialize React state
        await Future.delayed(Duration(seconds: 3));

        // Wait for token to be available and initialize API
        await _extractAndInitializeToken();
      },
    );

    await _headlessWebView!.run();
  }

  Future<void> _extractAndInitializeToken() async {
    print('[SpotifyClient] Waiting for token extraction...');

    // Retry up to 120 times (60 seconds total) to get token
    for (int i = 0; i < 120; i++) {
      try {
        final result = await _webViewController.evaluateJavascript(
          source: 'window.getSpotifyToken();',
        );

        print('[SpotifyClient] Token extraction attempt ${i + 1}: result type = ${result.runtimeType}');

        if (result != null) {
          String? token;

          // Handle different response formats
          if (result is Map<String, dynamic>) {
            token = result['token'] as String?;
            print('[SpotifyClient] Extracted token from map: ${token?.substring(0, 20) ?? "null"}...');
          } else if (result is String) {
            // If it's returned as a string, try to use it directly
            token = result.isNotEmpty && result != 'null' ? result : null;
            print('[SpotifyClient] Got string token: ${token?.substring(0, 20) ?? "null"}...');
          }

          if (token != null && token.isNotEmpty && token != 'null') {
            print('[SpotifyClient] Successfully extracted token: ${token.substring(0, 20)}...');
            setApiToken(token);
            notifyListeners();
            return;
          }
        } else {
          print('[SpotifyClient] getSpotifyToken() returned null');
        }

        if (i % 10 == 0) {
          print('[SpotifyClient] Still waiting for token... (attempt ${i + 1}/120)');
        }
      } catch (e) {
        if (i % 10 == 0) {
          print('[SpotifyClient] Token extraction error: $e');
        }
      }

      // Wait 500ms before retrying
      await Future.delayed(Duration(milliseconds: 500));
    }

    print('[SpotifyClient] Failed to extract token after 120 attempts (60 seconds)');
    print('[SpotifyClient] Make sure you are logged into Spotify at https://open.spotify.com');
    _tokenInitializationFailed = true;
    notifyListeners();
  }

  void setSpotifyApi(SpotifyApi api) {
    _spotifyApi = api;
    fetchHomeData();
  }

  void setApiToken(String accessToken) {
    print('[SpotifyClient] Playback token received');
    _startOAuthIfNeeded();
  }

  bool _oauthStarted = false;

  Future<void> _startOAuthIfNeeded() async {
    if (_spotifyApi != null || _oauthStarted) return;
    _oauthStarted = true;

    // Try restoring a previous session first
    try {
      final restoredApi = await tokenStorage.restoreSession();
      if (restoredApi != null) {
        _spotifyApi = restoredApi;
        print('[SpotifyClient] Restored OAuth session from storage');
        notifyListeners();
        fetchHomeData();
        return;
      }
    } catch (e) {
      print('[SpotifyClient] Session restore failed: $e');
    }

    final authUrl = await tokenStorage.getAuthorizationUrl();
    print('[SpotifyClient] Starting OAuth flow...');

    final navigator = navigatorKey.currentState;
    if (navigator == null) {
      print('[SpotifyClient] Navigator not available for OAuth');
      _oauthStarted = false;
      return;
    }

    SpotifyAuthModal.show(
      navigator.context,
      authUrl: authUrl,
      onAuthComplete: (responseUri) async {
        try {
          final api = await tokenStorage.handleAuthResponse(responseUri);
          _spotifyApi = api;
          print('[SpotifyClient] OAuth completed successfully');
          notifyListeners();
          fetchHomeData();
        } catch (e) {
          print('[SpotifyClient] OAuth error: $e');
          _oauthStarted = false;
        }
      },
    );
  }

  void handleServerMessage(Map<String, dynamic> data) {
    final action = data['action'] as String?;
    switch (action) {
      case 'trackChange':
        final trackData = data['track'] as Map<String, dynamic>?;
        if (trackData != null) {
          _currentTrack = SongInfo.fromWebSocket(trackData);
          notifyListeners();
        }
        break;

      case 'token':
        _spotifyToken = data['token'] as String?;
        print('[SpotifyClient] WebSocket token received (for playback only)');
        notifyListeners();
        break;

      case 'playbackProgress':
        _playbackProgress = PlaybackProgress.fromWebSocket(data);
        notifyListeners();
        break;

      case 'playerControl':
        _playerControlState = PlayerControlState.fromWebSocket(data);
        notifyListeners();
        break;

      case 'likedStatusUpdated':
        final uri = data['uri'] as String?;
        final liked = data['liked'] as bool?;
        if (uri != null && liked != null && _currentTrack?.uri == uri) {
          _currentTrack = _currentTrack!.copyWith(liked: liked);
          notifyListeners();
        }
        break;

      case 'recentlyPlayedData':
        final items = data['items'] as List?;
        if (items != null) {
          _recentlyPlayedItems = items
              .map((item) => RecentlyPlayedItem.fromWebSocket(item as Map<String, dynamic>))
              .toList();
          notifyListeners();
        }
        break;

      case 'userProfile':
        final profileData = data['profile'] as Map<String, dynamic>?;
        if (profileData != null) {
          _userProfile = UserProfile.fromWebSocket(profileData);
          notifyListeners();
        }
        break;

      case 'homeSections':
        final rawSections = data['sections'] as List? ?? [];
        final allSections = rawSections
            .whereType<Map<String, dynamic>>()
            .map((s) => HomeSection.fromMap(s))
            .where((s) => s.items.isNotEmpty)
            .toList();
        // Separate shortcuts section from carousel sections
        // First section is always the quick-access grid on Spotify
        _homeShortcuts = [];
        _homeSections = [];
        bool shortcutsFound = false;
        for (final section in allSections) {
          if (!shortcutsFound && section.isShortcuts) {
            _homeShortcuts = section.items;
            shortcutsFound = true;
          } else {
            _homeSections.add(section);
          }
        }
        // Fallback: if no section has Shortcut type, use the first section
        if (!shortcutsFound && allSections.isNotEmpty) {
          _homeShortcuts = allSections.first.items;
          _homeSections = allSections.skip(1).toList();
        }
        notifyListeners();
        print('[SpotifyClient] Received ${_homeShortcuts.length} shortcuts, ${_homeSections.length} sections');
        break;

      case 'loginStatus':
        _isLoggedIn = data['loggedIn'] as bool? ?? false;
        print('[SpotifyClient] Login status: $_isLoggedIn');
        notifyListeners();
        if (_isLoggedIn) {
          _loginDebounceTimer?.cancel();
          _dismissLoginModal();
        } else {
          _scheduleLoginModal();
        }
        break;
    }
  }

  void _scheduleLoginModal() {
    _loginDebounceTimer?.cancel();
    _loginDebounceTimer = Timer(const Duration(seconds: 5), () {
      if (!_isLoggedIn) {
        // Reset OAuth flag so it can try again after login
        _oauthStarted = false;
        _showLoginModal();
      }
    });
  }

  void _showLoginModal() {
    final navigator = navigatorKey.currentState;
    if (navigator == null || _loginModalShown) return;
    _loginModalShown = true;
    SpotifyLoginModal.show(
      navigator.context,
      onDismissed: () {
        _loginModalShown = false;
        restart();
      },
    );
  }

  void _dismissLoginModal() {
    if (!_loginModalShown) return;
    final navigator = navigatorKey.currentState;
    if (navigator != null && navigator.canPop()) {
      navigator.pop();
    }
    _loginModalShown = false;
  }

  Future<void> restart() async {
    print('[SpotifyClient] Restarting headless webview...');
    _tokenInitializationFailed = false;
    notifyListeners();
    await _webViewController.loadUrl(
      urlRequest: URLRequest(url: WebUri("https://open.spotify.com")),
    );
  }

  Future<bool> playTrack(String uri) async {
    try {
      final result = await _webViewController.evaluateJavascript(
        source: "window.spotifyPlay('$uri');",
      );
      return result == true || result == 'true';
    } catch (e) {
      return false;
    }
  }

  void togglePlayPause() {
    print('[SpotifyClient] togglePlayPause called');
    try {
      _webSocketServer.broadcast({'action': 'togglePlayPause'});
      print('[SpotifyClient] togglePlayPause broadcasted successfully');
    } catch (e) {
      print('[SpotifyClient] togglePlayPause error: $e');
    }
  }

  void skipNext() {
    print('[SpotifyClient] skipNext called');
    try {
      _webSocketServer.broadcast({'action': 'skipNext'});
      print('[SpotifyClient] skipNext broadcasted successfully');
    } catch (e) {
      print('[SpotifyClient] skipNext error: $e');
    }
  }

  void skipPrevious() {
    print('[SpotifyClient] skipPrevious called');
    try {
      _webSocketServer.broadcast({'action': 'skipPrevious'});
      print('[SpotifyClient] skipPrevious broadcasted successfully');
    } catch (e) {
      print('[SpotifyClient] skipPrevious error: $e');
    }
  }

  void seekTo(int positionMs) {
    print('[SpotifyClient] seekTo called with position: $positionMs ms');
    try {
      _webSocketServer.broadcast({'action': 'seek', 'position': positionMs});
      print('[SpotifyClient] seekTo broadcasted successfully');
    } catch (e) {
      print('[SpotifyClient] seekTo error: $e');
    }
  }

  void toggleRepeat() {
    print('[SpotifyClient] toggleRepeat called');
    try {
      _webSocketServer.broadcast({'action': 'toggleLoop'});
      print('[SpotifyClient] toggleRepeat broadcasted successfully');
    } catch (e) {
      print('[SpotifyClient] toggleRepeat error: $e');
    }
  }

  void toggleShuffle() {
    print('[SpotifyClient] toggleShuffle called');
    try {
      _webSocketServer.broadcast({'action': 'toggleShuffle'});
      print('[SpotifyClient] toggleShuffle broadcasted successfully');
    } catch (e) {
      print('[SpotifyClient] toggleShuffle error: $e');
    }
  }

  Future<void> toggleLiked() async {
    print('[SpotifyClient] toggleLiked called');
    if (_spotifyApi == null || _currentTrack?.uri == null) {
      print('[SpotifyClient] SpotifyApi or current track not available');
      return;
    }
    try {
      final trackId = _currentTrack!.uri!.split(':').last;
      final isLikedMap = await _spotifyApi!.tracks.me.containsTracks([trackId]);
      final isLiked = isLikedMap[trackId] ?? false;

      if (isLiked) {
        await _spotifyApi!.tracks.me.remove([trackId]);
      } else {
        await _spotifyApi!.tracks.me.save([trackId]);
      }

      _currentTrack = _currentTrack!.copyWith(liked: !isLiked);
      notifyListeners();
      print('[SpotifyClient] toggleLiked succeeded');
    } catch (e) {
      print('[SpotifyClient] toggleLiked error: $e');
    }
  }

  Future<void> getRecentlyPlayed({int limit = 50}) async {
    print('[SpotifyClient] getRecentlyPlayed called with limit: $limit');
    if (_spotifyApi == null) {
      print('[SpotifyClient] SpotifyApi not initialized');
      return;
    }
    try {
      final recentlyPlayedPage = await _spotifyApi!.me.recentlyPlayed(limit: limit).first();
      _recentlyPlayedItems = (recentlyPlayedPage.items ?? [])
          .map((playHistory) => RecentlyPlayedItem.fromWebSocket({
                'track': playHistory.track?.toJson(),
                'played_at': playHistory.playedAt?.toIso8601String(),
              }))
          .toList();
      notifyListeners();
      print('[SpotifyClient] getRecentlyPlayed succeeded with ${_recentlyPlayedItems.length} items');
    } catch (e) {
      print('[SpotifyClient] getRecentlyPlayed error: $e');
    }
  }

  Future<void> likeTrack(String trackId) async {
    if (_spotifyApi == null) {
      print('[SpotifyClient] SpotifyApi not initialized');
      return;
    }
    try {
      await _spotifyApi!.tracks.me.save([trackId]);
      print('[SpotifyClient] likeTrack succeeded for $trackId');
    } catch (e) {
      print('[SpotifyClient] likeTrack error: $e');
    }
  }

  Future<void> unlikeTrack(String trackId) async {
    if (_spotifyApi == null) {
      print('[SpotifyClient] SpotifyApi not initialized');
      return;
    }
    try {
      await _spotifyApi!.tracks.me.remove([trackId]);
      print('[SpotifyClient] unlikeTrack succeeded for $trackId');
    } catch (e) {
      print('[SpotifyClient] unlikeTrack error: $e');
    }
  }

  Future<void> fetchHomeData() async {
    if (_spotifyApi == null) return;
    await Future.wait([
      _fetchPlaylists(),
      _fetchNewReleases(),
      _fetchTopArtists(),
    ]);
  }

  Future<void> _fetchPlaylists() async {
    try {
      final page = await _spotifyApi!.playlists.me.getPage(50);
      final allPlaylists = page.items ?? [];

      // Spotify-generated personalized playlists (Daily Mixes, Discover Weekly, Release Radar)
      _personalizedMixes = allPlaylists
          .where((p) => p.owner?.id == 'spotify')
          .map((p) => RecentlyPlayedItem.fromPlaylistSimple(p))
          .toList();

      // User's own playlists
      _userPlaylists = allPlaylists
          .where((p) => p.owner?.id != 'spotify')
          .map((p) => RecentlyPlayedItem.fromPlaylistSimple(p))
          .toList();

      notifyListeners();
      print('[SpotifyClient] Fetched ${_personalizedMixes.length} personalized mixes, ${_userPlaylists.length} user playlists');
    } catch (e) {
      print('[SpotifyClient] fetchPlaylists error: $e');
    }
  }

  Future<void> _fetchNewReleases() async {
    try {
      final page = await _spotifyApi!.browse.newReleases().getPage(20);
      _newReleases = (page.items ?? [])
          .map((a) => RecentlyPlayedItem.fromAlbumSimple(a))
          .toList();
      notifyListeners();
      print('[SpotifyClient] Fetched ${_newReleases.length} new releases');
    } catch (e) {
      print('[SpotifyClient] fetchNewReleases error: $e');
    }
  }

  Future<void> _fetchTopArtists() async {
    try {
      final page = await _spotifyApi!.me.topArtists().getPage(20);
      _topArtists = (page.items ?? [])
          .map((a) => RecentlyPlayedItem.fromArtist(a))
          .toList();
      notifyListeners();
      print('[SpotifyClient] Fetched ${_topArtists.length} top artists');
    } catch (e) {
      print('[SpotifyClient] fetchTopArtists error: $e');
    }
  }

  Future<bool> isTrackLiked(String trackId) async {
    if (_spotifyApi == null) {
      print('[SpotifyClient] SpotifyApi not initialized');
      return false;
    }
    try {
      final isLikedMap = await _spotifyApi!.tracks.me.containsTracks([trackId]);
      return isLikedMap[trackId] ?? false;
    } catch (e) {
      print('[SpotifyClient] isTrackLiked error: $e');
      return false;
    }
  }
}
