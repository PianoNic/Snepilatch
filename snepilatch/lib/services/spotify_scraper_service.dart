import 'package:flutter/material.dart';
import '../models/playback_state.dart';
import '../models/user.dart';
import '../models/song.dart';
import '../models/search_result.dart';

class SpotifyScraperService {
  // JavaScript injection code
  static const String playbackInfoScript = '''
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

          if (ariaLabel.includes('smart shuffle') && ariaLabel.includes('deaktivieren')) {
            shuffleMode = 'enhanced';
          } else if (!ariaLabel.includes('smart') && ariaLabel.includes('deaktivieren')) {
            shuffleMode = 'normal';
          } else if (ariaLabel.includes('smart shuffle') && ariaLabel.includes('aktivieren')) {
            shuffleMode = 'normal';
          } else if (!ariaLabel.includes('smart') && ariaLabel.includes('aktivieren')) {
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

        // Get progress bar data
        const positionElement = document.querySelector('[data-testid="playback-position"]');
        const durationElement = document.querySelector('[data-testid="playback-duration"]');
        const progressBarInput = document.querySelector('[data-testid="playback-progressbar"] input[type="range"]');

        let currentTime = positionElement?.textContent || '0:00';
        let duration = durationElement?.textContent || '0:00';
        let progressMs = 0;
        let durationMs = 0;

        if (progressBarInput) {
          progressMs = parseInt(progressBarInput.value) || 0;
          durationMs = parseInt(progressBarInput.max) || 0;
        }

        return {
          isPlaying: isPlaying,
          track: trackElement?.textContent || '',
          artist: artistElement?.textContent || '',
          albumArt: albumArtElement?.src || '',
          isLiked: isLiked,
          shuffleMode: shuffleMode,
          repeatMode: repeatMode,
          currentTime: currentTime,
          duration: duration,
          progressMs: progressMs,
          durationMs: durationMs
        };
      } catch (e) {
        return { isPlaying: false, track: '', artist: '', albumArt: '', isLiked: false, shuffleMode: 'off', repeatMode: 'off' };
      }
    }
  ''';

  static const String userInfoScript = '''
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
  ''';

  static const String initScript = '''
    $playbackInfoScript
    $userInfoScript

    // Make functions available globally
    window.getPlayingInfo = getPlayingInfo;
    window.getUserInfo = getUserInfo;
    true;
  ''';

  // Parse playback info from JavaScript response
  static PlaybackState? parsePlaybackInfo(String data) {
    try {
      final trackMatch = RegExp(r'"track":"([^"]*)"').firstMatch(data);
      final artistMatch = RegExp(r'"artist":"([^"]*)"').firstMatch(data);
      final isPlayingMatch = RegExp(r'"isPlaying":(\w+)').firstMatch(data);
      final albumArtMatch = RegExp(r'"albumArt":"([^"]*)"').firstMatch(data);
      final isLikedMatch = RegExp(r'"isLiked":(\w+)').firstMatch(data);
      final shuffleModeMatch = RegExp(r'"shuffleMode":"([^"]*)"').firstMatch(data);
      final repeatModeMatch = RegExp(r'"repeatMode":"([^"]*)"').firstMatch(data);
      final currentTimeMatch = RegExp(r'"currentTime":"([^"]*)"').firstMatch(data);
      final durationMatch = RegExp(r'"duration":"([^"]*)"').firstMatch(data);
      final progressMsMatch = RegExp(r'"progressMs":(\d+)').firstMatch(data);
      final durationMsMatch = RegExp(r'"durationMs":(\d+)').firstMatch(data);

      final track = trackMatch?.group(1)?.trim();
      final artist = artistMatch?.group(1)?.trim();
      final isPlaying = isPlayingMatch?.group(1) == 'true';
      var albumArt = albumArtMatch?.group(1)?.trim();
      final isLiked = isLikedMatch?.group(1) == 'true';
      final shuffleMode = shuffleModeMatch?.group(1)?.trim() ?? 'off';
      final repeatMode = repeatModeMatch?.group(1)?.trim() ?? 'off';
      final currentTime = currentTimeMatch?.group(1)?.trim() ?? '0:00';
      final duration = durationMatch?.group(1)?.trim() ?? '0:00';
      final progressMs = int.tryParse(progressMsMatch?.group(1) ?? '0') ?? 0;
      final durationMs = int.tryParse(durationMsMatch?.group(1) ?? '0') ?? 0;

      // Convert to high quality image URL
      if (albumArt != null && albumArt.contains('ab67616d00004851')) {
        albumArt = albumArt.replaceAll('ab67616d00004851', 'ab67616d00001e02');
      }

      return PlaybackState(
        currentTrack: track?.isNotEmpty == true ? track : null,
        currentArtist: artist?.isNotEmpty == true ? artist : null,
        isPlaying: isPlaying,
        currentAlbumArt: albumArt?.isNotEmpty == true ? albumArt : null,
        isCurrentTrackLiked: isLiked,
        shuffleMode: ShuffleModeExtension.fromString(shuffleMode),
        repeatMode: RepeatModeExtension.fromString(repeatMode),
        currentTime: currentTime,
        duration: duration,
        progressMs: progressMs,
        durationMs: durationMs,
      );
    } catch (e) {
      debugPrint('Error parsing playback info: $e');
      return null;
    }
  }

  // Parse user info from JavaScript response
  static User? parseUserInfo(String data) {
    try {
      final isLoggedInMatch = RegExp(r'"isLoggedIn":(\w+)').firstMatch(data);
      final usernameMatch = RegExp(r'"username":"([^"]*)"').firstMatch(data);
      final emailMatch = RegExp(r'"email":"([^"]*)"').firstMatch(data);
      final profileImageMatch = RegExp(r'"profileImage":"([^"]*)"').firstMatch(data);

      final isLoggedIn = isLoggedInMatch?.group(1) == 'true';
      final username = usernameMatch?.group(1)?.trim();
      final email = emailMatch?.group(1)?.trim();
      var profileImage = profileImageMatch?.group(1)?.trim();

      // Convert to high quality image URL if it's a Spotify CDN image
      if (profileImage != null && profileImage.contains('ab67616d00004851')) {
        profileImage = profileImage.replaceAll('ab67616d00004851', 'ab67616d00001e02');
      }

      return User(
        isLoggedIn: isLoggedIn,
        username: username?.isNotEmpty == true ? username : null,
        email: email?.isNotEmpty == true ? email : null,
        profileImageUrl: profileImage?.isNotEmpty == true ? profileImage : null,
      );
    } catch (e) {
      debugPrint('Error parsing user info: $e');
      return null;
    }
  }

  // Parse songs from JavaScript response
  static List<Song> parseSongs(String jsonString) {
    try {
      // Clean the JSON string
      String cleanJson = jsonString;
      if (cleanJson.startsWith('"') && cleanJson.endsWith('"')) {
        cleanJson = cleanJson.substring(1, cleanJson.length - 1);
      }
      cleanJson = cleanJson.replaceAll(r'\"', '"');
      cleanJson = cleanJson.replaceAll(r'\\', r'\');

      // Parse songs from JSON
      final List<Song> songs = [];
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
          var imageUrl = imageMatch?.group(1)?.replaceAll(r'\/', '/');
          // Convert to high quality image URL
          if (imageUrl != null && imageUrl.contains('ab67616d00004851')) {
            imageUrl = imageUrl.replaceAll('ab67616d00004851', 'ab67616d00001e02');
          }

          songs.add(Song(
            title: titleMatch.group(1) ?? '',
            artist: artistMatch?.group(1) ?? '',
            album: albumMatch?.group(1) ?? '',
            imageUrl: imageUrl,
            duration: durationMatch?.group(1),
            index: int.tryParse(indexMatch?.group(1) ?? '0') ?? 0,
          ));
        }
      }

      return songs;
    } catch (e) {
      debugPrint('Error parsing songs: $e');
      return [];
    }
  }

  // Parse search results from JavaScript response
  static List<SearchResult> parseSearchResults(String jsonString) {
    final List<SearchResult> results = [];
    try {
      final matches = RegExp(r'\{title:([^,}]+),artist:([^}]+)\}').allMatches(jsonString);
      int index = 0;
      for (final match in matches) {
        results.add(SearchResult(
          title: match.group(1)?.trim() ?? '',
          artist: match.group(2)?.trim() ?? '',
          index: index++,
        ));
      }
    } catch (e) {
      debugPrint('Error parsing search results: $e');
    }
    return results;
  }
}