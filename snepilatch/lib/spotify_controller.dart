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
  List<Map<String, String>> _songs = [];
  bool _isLoadingSongs = false;

  // ValueNotifiers for specific UI updates
  final ValueNotifier<bool> showWebViewNotifier = ValueNotifier<bool>(false);
  final ValueNotifier<bool> isLoggedInNotifier = ValueNotifier<bool>(false);

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
  List<Map<String, String>> get songs => _songs;
  bool get isLoadingSongs => _isLoadingSongs;

  bool _isCurrentTrackLiked = false;
  String _shuffleMode = 'off'; // 'off', 'normal', 'enhanced'
  String _repeatMode = 'off'; // 'off', 'all', 'one'

  bool get isCurrentTrackLiked => _isCurrentTrackLiked;
  String get shuffleMode => _shuffleMode;
  String get repeatMode => _repeatMode;

  SpotifyController() {
    _startPeriodicScraping();
  }

  @override
  void dispose() {
    showWebViewNotifier.dispose();
    isLoggedInNotifier.dispose();
    super.dispose();
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

          // Check if current track is liked
          const likeButton = document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="Lieblingssongs"], [data-testid="now-playing-widget"] button[aria-label*="Playlist"], [data-testid="now-playing-widget"] button[aria-label*="favorite"], [data-testid="now-playing-widget"] button[aria-label*="like"]');
          const isLiked = likeButton?.getAttribute('aria-checked') === 'true' || false;

          // Check shuffle state - detect off, normal, or enhanced
          const shuffleButton = document.querySelector('button[aria-label*="shuffle" i], button[aria-label*="Shuffle" i]');
          let shuffleMode = 'off';

          if (shuffleButton) {
            const ariaLabel = shuffleButton.getAttribute('aria-label')?.toLowerCase() || '';

            // Detect based on aria-label text patterns
            // "deaktivieren" = deactivate (means it's currently ON)
            // "aktivieren" = activate (means it's currently OFF or ready to upgrade)

            if (ariaLabel.includes('smart shuffle') && ariaLabel.includes('deaktivieren')) {
              // Smart Shuffle is ON (enhanced mode)
              shuffleMode = 'enhanced';
            } else if (!ariaLabel.includes('smart') && ariaLabel.includes('deaktivieren')) {
              // Normal shuffle is ON
              shuffleMode = 'normal';
            } else if (ariaLabel.includes('smart shuffle') && ariaLabel.includes('aktivieren')) {
              // Normal shuffle is ON, can activate Smart Shuffle
              shuffleMode = 'normal';
            } else if (!ariaLabel.includes('smart') && ariaLabel.includes('aktivieren')) {
              // Shuffle is OFF
              shuffleMode = 'off';
            }
          }

          // Check repeat state
          const repeatButton = document.querySelector('[data-testid="control-button-repeat"]');
          const repeatAriaChecked = repeatButton?.getAttribute('aria-checked');
          let repeatMode = 'off';
          if (repeatAriaChecked === 'true') {
            repeatMode = 'all';
          } else if (repeatAriaChecked === 'mixed') {
            repeatMode = 'one';
          }

          return {
            isPlaying: isPlaying,
            track: trackElement?.textContent || '',
            artist: artistElement?.textContent || '',
            albumArt: albumArtElement?.src || '',
            isLiked: isLiked,
            shuffleMode: shuffleMode,
            repeatMode: repeatMode
          };
        } catch (e) {
          return { isPlaying: false, track: '', artist: '', albumArt: '', isLiked: false, shuffleMode: 'off', repeatMode: 'off' };
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
      final isLikedMatch = RegExp(r'"isLiked":(\w+)').firstMatch(data);
      final shuffleModeMatch = RegExp(r'"shuffleMode":"([^"]*)"').firstMatch(data);
      final repeatModeMatch = RegExp(r'"repeatMode":"([^"]*)"').firstMatch(data);

      final newTrack = trackMatch?.group(1)?.trim() ?? '';
      final newArtist = artistMatch?.group(1)?.trim() ?? '';
      final newIsPlaying = isPlayingMatch?.group(1) == 'true';
      final newAlbumArt = albumArtMatch?.group(1)?.trim() ?? '';
      final newIsLiked = isLikedMatch?.group(1) == 'true';
      final newShuffleMode = shuffleModeMatch?.group(1)?.trim() ?? 'off';
      final newRepeatMode = repeatModeMatch?.group(1)?.trim() ?? 'off';

      if (newTrack != _currentTrack ||
          newArtist != _currentArtist ||
          newIsPlaying != _isPlaying ||
          newAlbumArt != _currentAlbumArt ||
          newIsLiked != _isCurrentTrackLiked ||
          newShuffleMode != _shuffleMode ||
          newRepeatMode != _repeatMode) {
        _currentTrack = newTrack.isNotEmpty ? newTrack : null;
        _currentArtist = newArtist.isNotEmpty ? newArtist : null;
        _isPlaying = newIsPlaying;
        _currentAlbumArt = newAlbumArt.isNotEmpty ? newAlbumArt : null;
        _isCurrentTrackLiked = newIsLiked;
        _shuffleMode = newShuffleMode;
        _repeatMode = newRepeatMode;
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
        isLoggedInNotifier.value = newIsLoggedIn;
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
      (function() {
        const playButton = document.querySelector('[data-testid="control-button-playpause"]');
        if (playButton && playButton.getAttribute('aria-label')?.includes('Play')) {
          playButton.click();
        }
      })();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> pause() async {
    const String js = '''
      (function() {
        const pauseButton = document.querySelector('[data-testid="control-button-playpause"]');
        if (pauseButton && pauseButton.getAttribute('aria-label')?.includes('Pause')) {
          pauseButton.click();
        }
      })();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> next() async {
    const String js = '''
      (function() {
        const nextButton = document.querySelector('[data-testid="control-button-skip-forward"]');
        if (nextButton) nextButton.click();
      })();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> previous() async {
    const String js = '''
      (function() {
        const prevButton = document.querySelector('[data-testid="control-button-skip-back"]');
        if (prevButton) prevButton.click();
      })();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> toggleShuffle() async {
    const String js = '''
      (function() {
        // Find shuffle button by looking for button with shuffle-related aria-label
        const buttons = document.querySelectorAll('button[aria-label]');
        const shuffleButton = Array.from(buttons).find(button =>
          button.getAttribute('aria-label')?.toLowerCase().includes('shuffle')
        );
        if (shuffleButton) {
          shuffleButton.click();
        }
      })();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> toggleRepeat() async {
    const String js = '''
      (function() {
        const repeatButton = document.querySelector('[data-testid="control-button-repeat"]');
        if (repeatButton) repeatButton.click();
      })();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> toggleLike() async {
    const String js = '''
      (function() {
        // Find the like button in the now-playing widget
        const likeButton = document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="Lieblingssongs"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="Playlist"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="favorite"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-label*="like"]') ||
                          document.querySelector('[data-testid="now-playing-widget"] button[aria-checked]');
        if (likeButton) {
          likeButton.click();
        }
      })();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> search(String query) async {
    final String js = '''
      (function() {
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
      })();
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
    showWebViewNotifier.value = true;
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
    showWebViewNotifier.value = true;
    notifyListeners();
  }

  void hideWebView() {
    _showWebView = false;
    showWebViewNotifier.value = false;
    notifyListeners();
    if (!_isLoggedIn) {
      navigateToSpotify();
    }
  }

  Future<void> logout() async {
    const String js = '''
      (function() {
        const accountButton = document.querySelector('[data-testid="user-widget-link"]');
        if (accountButton) {
          accountButton.click();
          setTimeout(() => {
            const logoutButton = document.querySelector('[data-testid="user-widget-dropdown-logout"]');
            if (logoutButton) logoutButton.click();
          }, 500);
        }
      })();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> playTrackAtIndex(int index) async {
    final String js = '''
      (function() {
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
      })();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> navigateToLikedSongs() async {
    _isLoadingSongs = true;
    _songs = [];
    notifyListeners();

    await webViewController?.loadUrl(
      urlRequest: URLRequest(url: WebUri('https://open.spotify.com/collection/tracks'))
    );

    // Wait for page to load
    await Future.delayed(const Duration(seconds: 2));
    await scrapeSongs();
  }

  Future<void> scrapeSongs() async {
    if (webViewController == null) return;

    const String js = '''
      const songs = [];
      const songRows = document.querySelectorAll('[data-testid="tracklist-row"]');

      songRows.forEach((row, index) => {
        const titleElement = row.querySelector('[data-testid="internal-track-link"] div');
        const artistElement = row.querySelector('[data-testid="internal-track-link"]')?.parentElement?.nextElementSibling?.querySelector('a');
        const albumElement = row.querySelector('[data-testid="internal-track-link"]')?.parentElement?.nextElementSibling?.nextElementSibling?.querySelector('a');
        const imageElement = row.querySelector('img');
        const durationElement = row.querySelector('[data-testid="track-duration"]');

        if (titleElement) {
          songs.push({
            title: titleElement.textContent || '',
            artist: artistElement?.textContent || '',
            album: albumElement?.textContent || '',
            image: imageElement?.src || '',
            duration: durationElement?.textContent || '',
            index: index
          });
        }
      });

      JSON.stringify(songs);
    ''';

    try {
      final result = await webViewController!.evaluateJavascript(source: js);

      if (result != null && result != 'null' && result != '[]') {
        _parseSongs(result.toString());
      }

      _isLoadingSongs = false;
      notifyListeners();
    } catch (e) {
      debugPrint('Error scraping songs: $e');
      _isLoadingSongs = false;
      notifyListeners();
    }
  }

  void _parseSongs(String jsonString) {
    try {
      // Clean the JSON string
      String cleanJson = jsonString;
      if (cleanJson.startsWith('"') && cleanJson.endsWith('"')) {
        cleanJson = cleanJson.substring(1, cleanJson.length - 1);
      }
      cleanJson = cleanJson.replaceAll(r'\"', '"');
      cleanJson = cleanJson.replaceAll(r'\\', r'\');

      // Parse songs from JSON
      final List<Map<String, String>> parsedSongs = [];
      final matches = RegExp(r'\{[^}]+\}').allMatches(cleanJson);

      for (final match in matches) {
        final songData = match.group(0) ?? '';

        final titleMatch = RegExp(r'"title":"([^"]*)"').firstMatch(songData);
        final artistMatch = RegExp(r'"artist":"([^"]*)"').firstMatch(songData);
        final albumMatch = RegExp(r'"album":"([^"]*)"').firstMatch(songData);
        final imageMatch = RegExp(r'"image":"([^"]*)"').firstMatch(songData);
        final durationMatch = RegExp(r'"duration":"([^"]*)"').firstMatch(songData);
        final indexMatch = RegExp(r'"index":(\d+)').firstMatch(songData);

        if (titleMatch != null) {
          parsedSongs.add({
            'title': titleMatch.group(1) ?? '',
            'artist': artistMatch?.group(1) ?? '',
            'album': albumMatch?.group(1) ?? '',
            'image': imageMatch?.group(1)?.replaceAll(r'\/', '/') ?? '',
            'duration': durationMatch?.group(1) ?? '',
            'index': indexMatch?.group(1) ?? '0',
          });
        }
      }

      _songs = parsedSongs;
      notifyListeners();
    } catch (e) {
      debugPrint('Error parsing songs: $e');
    }
  }

  Future<void> scrollSpotifyPage(double offset) async {
    final String js = '''
      (function() {
        const mainView = document.querySelector('[data-testid="playlist-page"]') ||
                         document.querySelector('.main-view-container__scroll-node') ||
                         document.querySelector('[data-testid="track-list"]')?.parentElement;
        if (mainView) {
          mainView.scrollTop = $offset;
        }
      })();
    ''';
    await webViewController?.evaluateJavascript(source: js);
  }

  Future<void> loadMoreSongs() async {
    // Scroll down to trigger lazy loading
    final String js = '''
      (function() {
        const mainView = document.querySelector('[data-testid="playlist-page"]') ||
                         document.querySelector('.main-view-container__scroll-node') ||
                         document.querySelector('[data-testid="track-list"]')?.parentElement;
        if (mainView) {
          mainView.scrollTop = mainView.scrollHeight;
        }
      })();
    ''';
    await webViewController?.evaluateJavascript(source: js);

    // Wait for new content to load
    await Future.delayed(const Duration(milliseconds: 500));

    // Scrape again to get new songs
    await scrapeSongs();
  }
}