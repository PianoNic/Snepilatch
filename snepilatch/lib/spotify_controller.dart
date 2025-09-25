import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

class SpotifyController extends ChangeNotifier {
  InAppWebViewController? webViewController;
  bool _isInitialized = false;
  bool _isPlaying = false;
  bool _isLoggedIn = false;
  bool _showWebView = false;
  String? _currentTrack;
  String? _currentArtist;
  String? _currentAlbumArt;
  String? _username;
  String? _userEmail;
  String? _userProfileImage;

  bool get isInitialized => _isInitialized;
  bool get isPlaying => _isPlaying;
  bool get isLoggedIn => _isLoggedIn;
  bool get showWebView => _showWebView;
  String? get currentTrack => _currentTrack;
  String? get currentArtist => _currentArtist;
  String? get currentAlbumArt => _currentAlbumArt;
  String? get username => _username;
  String? get userEmail => _userEmail;
  String? get userProfileImage => _userProfileImage;

  SpotifyController() {
    _startPeriodicScraping();
  }

  InAppWebViewSettings getWebViewSettings() {
    String userAgent = '';

    if (Platform.isAndroid) {
      userAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
    } else if (Platform.isIOS) {
      userAgent = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15';
    } else {
      userAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
    }

    return InAppWebViewSettings(
      userAgent: userAgent,
      javaScriptEnabled: true,
      mediaPlaybackRequiresUserGesture: false,
      allowsInlineMediaPlayback: true,
      iframeAllow: "camera; microphone; payment; encrypted-media;",
      iframeAllowFullscreen: true,
      useShouldOverrideUrlLoading: true,
      useHybridComposition: true,
      allowContentAccess: true,
      allowFileAccess: true,
      domStorageEnabled: true,
      databaseEnabled: true,
      clearSessionCache: false,
      thirdPartyCookiesEnabled: true,
      supportZoom: false,
      useWideViewPort: true,
      displayZoomControls: false,
      verticalScrollBarEnabled: true,
      horizontalScrollBarEnabled: true,
      transparentBackground: false,
      disableDefaultErrorPage: false,
    );
  }

  void onWebViewCreated(InAppWebViewController controller) {
    webViewController = controller;
  }

  Future<void> onLoadStop(InAppWebViewController controller, WebUri? url) async {
    debugPrint('Page finished loading: $url');
    _injectJavaScript();
    _isInitialized = true;
    notifyListeners();
  }

  Future<NavigationActionPolicy> shouldOverrideUrlLoading(
      InAppWebViewController controller, NavigationAction navigationAction) async {
    var uri = navigationAction.request.url;

    if (uri != null) {
      String url = uri.toString();
      // Prevent redirects to app store or mobile app
      if (url.contains('apps.apple.com') ||
          url.contains('play.google.com') ||
          url.contains('spotify://')) {
        debugPrint('Blocked navigation to: $url');
        return NavigationActionPolicy.CANCEL;
      }
    }

    return NavigationActionPolicy.ALLOW;
  }

  Future<PermissionResponse?> onPermissionRequest(
      InAppWebViewController controller, PermissionRequest permissionRequest) async {
    // Grant all permissions, especially RESOURCE_PROTECTED_MEDIA_ID for DRM
    debugPrint('Permission requested: ${permissionRequest.resources}');

    // Always grant permission for DRM content
    return PermissionResponse(
      resources: permissionRequest.resources,
      action: PermissionResponseAction.GRANT
    );
  }

  void _injectJavaScript() async {
    if (webViewController == null) return;

    const String js = '''
      // Function to get current playing info
      function getPlayingInfo() {
        try {
          const playButton = document.querySelector('[data-testid="control-button-playpause"]');
          const isPlaying = playButton?.getAttribute('aria-label')?.includes('Pause') || false;

          const trackElement = document.querySelector('[data-testid="context-item-info-title"]') ||
                              document.querySelector('[data-testid="now-playing-widget"] [data-testid="context-item-link"]');
          const artistElement = document.querySelector('[data-testid="context-item-info-artist"]') ||
                               document.querySelector('[data-testid="now-playing-widget"] [data-testid="context-item-info-subtitles"]');
          const albumArtElement = document.querySelector('[data-testid="now-playing-widget"] img') ||
                                 document.querySelector('[data-testid="cover-art-image"]');

          return {
            isPlaying: isPlaying,
            track: trackElement?.textContent || '',
            artist: artistElement?.textContent || '',
            albumArt: albumArtElement?.src || ''
          };
        } catch (e) {
          return { isPlaying: false, track: '', artist: '', albumArt: '' };
        }
      }

      // Function to get user info
      function getUserInfo() {
        try {
          // Check if user is logged in
          const loginButton = document.querySelector('[data-testid="login-button"]');
          const signupButton = document.querySelector('[data-testid="signup-button"]');

          if (loginButton || signupButton) {
            return { isLoggedIn: false, username: '', email: '', profileImage: '' };
          }

          let username = '';
          let profileImage = '';

          // Get username and profile image from user widget button - primary method
          const userButton = document.querySelector('[data-testid="user-widget-link"]');
          if (userButton) {
            // Try aria-label first (most reliable)
            username = userButton.getAttribute('aria-label')?.trim() || '';

            // If not in aria-label, try text content
            if (!username) {
              username = userButton.textContent?.trim() || '';
            }

            // Get profile image from img element inside the button
            const imgElement = userButton.querySelector('img');
            if (imgElement) {
              profileImage = imgElement.getAttribute('src') || '';

              // Also check for username in img alt text if not found yet
              if (!username) {
                username = imgElement.getAttribute('alt')?.trim() || '';
              }
            }
          }

          // Fallback methods if primary doesn't work
          if (!username) {
            const profileLink = document.querySelector('[href*="/user/"]');
            if (profileLink) {
              username = profileLink.textContent?.trim() || '';
            }
          }

          if (!username) {
            const userMenuButton = document.querySelector('[data-testid="user-menu-button"]');
            if (userMenuButton) {
              username = userMenuButton.getAttribute('aria-label')?.replace('User menu for', '').trim() || '';
            }
          }

          // Try to get username from the page title or other elements
          if (!username) {
            const profileName = document.querySelector('h1')?.textContent;
            if (profileName && !profileName.includes('Spotify')) {
              username = profileName;
            }
          }

          return {
            isLoggedIn: true,
            username: username || 'Spotify User',
            email: '',
            profileImage: profileImage || ''
          };
        } catch (e) {
          return { isLoggedIn: false, username: '', email: '', profileImage: '' };
        }
      }

      // Make functions available globally
      window.getPlayingInfo = getPlayingInfo;
      window.getUserInfo = getUserInfo;
      true;
    ''';

    await webViewController?.evaluateJavascript(source: js);
  }

  void _startPeriodicScraping() {
    Timer(const Duration(seconds: 2), () {
      Timer.periodic(const Duration(seconds: 1), (timer) {
        if (_isInitialized) {
          _scrapeCurrentInfo();
          _scrapeUserInfo();
        }
      });
    });
  }

  Future<void> _scrapeCurrentInfo() async {
    if (webViewController == null) return;

    try {
      final result = await webViewController!.evaluateJavascript(
        source: 'JSON.stringify(getPlayingInfo())'
      );

      if (result != null && result != 'null') {
        final String jsonString = result.toString();
        final cleanJson = jsonString.replaceAll(r'\"', '"');

        if (cleanJson.contains('track')) {
          _parseAndUpdateInfo(cleanJson);
        }
      }
    } catch (e) {
      debugPrint('Error scraping: $e');
    }
  }

  void _parseAndUpdateInfo(String data) {
    try {
      final trackMatch = RegExp(r'"track":"([^"]*)"').firstMatch(data);
      final artistMatch = RegExp(r'"artist":"([^"]*)"').firstMatch(data);
      final isPlayingMatch = RegExp(r'"isPlaying":(\w+)').firstMatch(data);
      final albumArtMatch = RegExp(r'"albumArt":"([^"]*)"').firstMatch(data);

      final newTrack = trackMatch?.group(1)?.trim() ?? '';
      final newArtist = artistMatch?.group(1)?.trim() ?? '';
      final newIsPlaying = isPlayingMatch?.group(1) == 'true';
      final newAlbumArt = albumArtMatch?.group(1)?.trim() ?? '';

      if (newTrack != _currentTrack ||
          newArtist != _currentArtist ||
          newIsPlaying != _isPlaying ||
          newAlbumArt != _currentAlbumArt) {
        _currentTrack = newTrack.isNotEmpty ? newTrack : null;
        _currentArtist = newArtist.isNotEmpty ? newArtist : null;
        _isPlaying = newIsPlaying;
        _currentAlbumArt = newAlbumArt.isNotEmpty ? newAlbumArt : null;
        notifyListeners();
      }
    } catch (e) {
      debugPrint('Error parsing info: $e');
    }
  }

  Future<void> _scrapeUserInfo() async {
    if (webViewController == null) return;

    try {
      final result = await webViewController!.evaluateJavascript(
        source: 'JSON.stringify(getUserInfo())'
      );

      if (result != null && result != 'null') {
        final String jsonString = result.toString();
        final cleanJson = jsonString.replaceAll(r'\"', '"');

        if (cleanJson.contains('isLoggedIn')) {
          _parseAndUpdateUserInfo(cleanJson);
        }
      }
    } catch (e) {
      debugPrint('Error scraping user info: $e');
    }
  }

  void _parseAndUpdateUserInfo(String data) {
    try {
      final isLoggedInMatch = RegExp(r'"isLoggedIn":(\w+)').firstMatch(data);
      final usernameMatch = RegExp(r'"username":"([^"]*)"').firstMatch(data);
      final emailMatch = RegExp(r'"email":"([^"]*)"').firstMatch(data);
      final profileImageMatch = RegExp(r'"profileImage":"([^"]*)"').firstMatch(data);

      final newIsLoggedIn = isLoggedInMatch?.group(1) == 'true';
      final newUsername = usernameMatch?.group(1)?.trim() ?? '';
      final newEmail = emailMatch?.group(1)?.trim() ?? '';
      final newProfileImage = profileImageMatch?.group(1)?.trim() ?? '';

      if (newIsLoggedIn != _isLoggedIn ||
          newUsername != _username ||
          newEmail != _userEmail ||
          newProfileImage != _userProfileImage) {
        _isLoggedIn = newIsLoggedIn;
        _username = newUsername.isNotEmpty ? newUsername : null;
        _userEmail = newEmail.isNotEmpty ? newEmail : null;
        _userProfileImage = newProfileImage.isNotEmpty ? newProfileImage : null;
        notifyListeners();
      }
    } catch (e) {
      debugPrint('Error parsing user info: $e');
    }
  }

  Future<void> play() async {
    const String js = '''
      const playButton = document.querySelector('[data-testid="control-button-playpause"]');
      if (playButton && playButton.getAttribute('aria-label')?.includes('Play')) {
        playButton.click();
      }
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> pause() async {
    const String js = '''
      const pauseButton = document.querySelector('[data-testid="control-button-playpause"]');
      if (pauseButton && pauseButton.getAttribute('aria-label')?.includes('Pause')) {
        pauseButton.click();
      }
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> next() async {
    const String js = '''
      const nextButton = document.querySelector('[data-testid="control-button-skip-forward"]');
      if (nextButton) nextButton.click();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> previous() async {
    const String js = '''
      const prevButton = document.querySelector('[data-testid="control-button-skip-back"]');
      if (prevButton) prevButton.click();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> search(String query) async {
    final String js = '''
      const searchButton = document.querySelector('[href="/search"]');
      if (searchButton) {
        searchButton.click();
        setTimeout(() => {
          const searchInput = document.querySelector('input[data-testid="search-input"]');
          if (searchInput) {
            searchInput.value = '$query';
            searchInput.dispatchEvent(new Event('input', { bubbles: true }));
            searchInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
          }
        }, 500);
      }
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<List<Map<String, String>>> searchAndGetResults(String query) async {
    await search(query);
    await Future.delayed(const Duration(seconds: 2));

    const String js = '''
      const results = [];
      const songs = document.querySelectorAll('[data-testid="tracklist-row"]');
      songs.forEach((song, index) => {
        if (index < 10) {
          const title = song.querySelector('[data-testid="internal-track-link"] div')?.textContent || '';
          const artist = song.querySelector('[data-testid="internal-track-link"] + div a')?.textContent || '';
          results.push({ title, artist });
        }
      });
      JSON.stringify(results);
    ''';

    try {
      final result = await webViewController?.evaluateJavascript(source: js);
      if (result != null && result != 'null') {
        return _parseSearchResults(result.toString());
      }
    } catch (e) {
      debugPrint('Error getting search results: $e');
    }
    return [];
  }

  List<Map<String, String>> _parseSearchResults(String jsonString) {
    final List<Map<String, String>> results = [];
    try {
      final matches = RegExp(r'\{title:([^,}]+),artist:([^}]+)\}').allMatches(jsonString);
      for (final match in matches) {
        results.add({
          'title': match.group(1)?.trim() ?? '',
          'artist': match.group(2)?.trim() ?? '',
        });
      }
    } catch (e) {
      debugPrint('Error parsing search results: $e');
    }
    return results;
  }

  Future<void> navigateToLogin() async {
    _showWebView = true;
    notifyListeners();
    await webViewController?.loadUrl(
      urlRequest: URLRequest(url: WebUri('https://accounts.spotify.com/login'))
    );
  }

  Future<void> navigateToSpotify() async {
    await webViewController?.loadUrl(
      urlRequest: URLRequest(url: WebUri('https://open.spotify.com'))
    );
  }

  void openWebView() {
    _showWebView = true;
    notifyListeners();
  }

  void hideWebView() {
    _showWebView = false;
    notifyListeners();
    if (!_isLoggedIn) {
      navigateToSpotify();
    }
  }

  Future<void> logout() async {
    const String js = '''
      const accountButton = document.querySelector('[data-testid="user-widget-link"]');
      if (accountButton) {
        accountButton.click();
        setTimeout(() => {
          const logoutButton = document.querySelector('[data-testid="user-widget-dropdown-logout"]');
          if (logoutButton) logoutButton.click();
        }, 500);
      }
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> playTrackAtIndex(int index) async {
    final String js = '''
      const songs = document.querySelectorAll('[data-testid="tracklist-row"]');
      if (songs[$index]) {
        const playButton = songs[$index].querySelector('[data-testid="more-button"]');
        if (playButton) {
          songs[$index].click();
          setTimeout(() => {
            const doubleClick = new MouseEvent('dblclick', {
              view: window,
              bubbles: true,
              cancelable: true
            });
            songs[$index].dispatchEvent(doubleClick);
          }, 100);
        }
      }
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }
}