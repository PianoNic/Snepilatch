import 'package:flutter/foundation.dart';
import '../models/playback_state.dart';
import '../models/user.dart';
import '../models/song.dart';

/// Reactive store for Spotify state using ValueNotifiers (similar to Angular Signals)
class SpotifyStore {
  // Playback state signals
  final ValueNotifier<bool> isPlaying = ValueNotifier(false);
  final ValueNotifier<String?> currentTrack = ValueNotifier(null);
  final ValueNotifier<String?> currentArtist = ValueNotifier(null);
  final ValueNotifier<String?> currentAlbumArt = ValueNotifier(null);
  final ValueNotifier<bool> isCurrentTrackLiked = ValueNotifier(false);
  final ValueNotifier<ShuffleMode> shuffleMode = ValueNotifier(ShuffleMode.off);
  final ValueNotifier<RepeatMode> repeatMode = ValueNotifier(RepeatMode.off);
  final ValueNotifier<String> currentTime = ValueNotifier('0:00');
  final ValueNotifier<String> duration = ValueNotifier('0:00');
  final ValueNotifier<int> progressMs = ValueNotifier(0);
  final ValueNotifier<int> durationMs = ValueNotifier(0);

  // User state signals
  final ValueNotifier<bool> isLoggedIn = ValueNotifier(false);
  final ValueNotifier<String?> username = ValueNotifier(null);
  final ValueNotifier<String?> userEmail = ValueNotifier(null);
  final ValueNotifier<String?> userProfileImage = ValueNotifier(null);

  // Songs state
  final ValueNotifier<List<Song>> songs = ValueNotifier([]);
  final ValueNotifier<bool> isLoadingSongs = ValueNotifier(false);

  // UI state
  final ValueNotifier<bool> showWebView = ValueNotifier(false);
  final ValueNotifier<bool> isInitialized = ValueNotifier(false);

  // Control state - tracks if user is controlling playback
  final ValueNotifier<bool> isUserControlling = ValueNotifier(false);
  DateTime? _lastUserAction;

  /// Batch update all values at once (once per second)
  void batchUpdate(PlaybackState? newState, User? newUser) {
    debugPrint('🔄 [SpotifyStore] Batch update from WebView...');

    // Update playback state if provided
    if (newState != null) {
      // If user is controlling, only update non-control fields
      if (isUserControlling.value) {
        debugPrint('  ⚠️ User is controlling - only updating track info');
        // Update track info but not playback controls
        if (currentTrack.value != newState.currentTrack) {
          debugPrint('  🎵 Track: "${currentTrack.value}" → "${newState.currentTrack}"');
          currentTrack.value = newState.currentTrack;
        }
        if (currentArtist.value != newState.currentArtist) {
          debugPrint('  🎤 Artist: "${currentArtist.value}" → "${newState.currentArtist}"');
          currentArtist.value = newState.currentArtist;
        }
        if (currentAlbumArt.value != newState.currentAlbumArt) {
          debugPrint('  🖼️ Album Art updated');
          currentAlbumArt.value = newState.currentAlbumArt;
        }
      } else {
        // Update all fields if user is not controlling
        if (isPlaying.value != newState.isPlaying) {
          debugPrint('  ▶️ Playing: $isPlaying → ${newState.isPlaying}');
          isPlaying.value = newState.isPlaying;
        }
        if (currentTrack.value != newState.currentTrack) {
          debugPrint('  🎵 Track: "${currentTrack.value}" → "${newState.currentTrack}"');
          currentTrack.value = newState.currentTrack;
        }
        if (currentArtist.value != newState.currentArtist) {
          debugPrint('  🎤 Artist: "${currentArtist.value}" → "${newState.currentArtist}"');
          currentArtist.value = newState.currentArtist;
        }
        if (currentAlbumArt.value != newState.currentAlbumArt) {
          debugPrint('  🖼️ Album Art updated');
          currentAlbumArt.value = newState.currentAlbumArt;
        }
        if (isCurrentTrackLiked.value != newState.isCurrentTrackLiked) {
          debugPrint('  ❤️ Liked: $isCurrentTrackLiked → ${newState.isCurrentTrackLiked}');
          isCurrentTrackLiked.value = newState.isCurrentTrackLiked;
        }
        if (shuffleMode.value != newState.shuffleMode) {
          debugPrint('  🔀 Shuffle: ${shuffleMode.value} → ${newState.shuffleMode}');
          shuffleMode.value = newState.shuffleMode;
        }
        if (repeatMode.value != newState.repeatMode) {
          debugPrint('  🔁 Repeat: ${repeatMode.value} → ${newState.repeatMode}');
          repeatMode.value = newState.repeatMode;
        }
        // Update time from scraping (will be overridden by smooth updates)
        if (currentTime.value != newState.currentTime) {
          currentTime.value = newState.currentTime ?? '0:00';
        }
        if (duration.value != newState.duration) {
          duration.value = newState.duration ?? '0:00';
        }
        // Only update progress from scraping if significantly different (>1 second)
        if ((progressMs.value - newState.progressMs).abs() > 1000) {
          progressMs.value = newState.progressMs;
        }
        if (durationMs.value != newState.durationMs) {
          durationMs.value = newState.durationMs;
        }
      }
    }

    // Update user info if provided
    if (newUser != null) {
      if (isLoggedIn.value != newUser.isLoggedIn) {
        debugPrint('  🔐 Logged in: $isLoggedIn → ${newUser.isLoggedIn}');
        isLoggedIn.value = newUser.isLoggedIn;
      }
      if (username.value != newUser.username) {
        debugPrint('  👤 Username: "${username.value}" → "${newUser.username}"');
        username.value = newUser.username;
      }
      if (userEmail.value != newUser.email) {
        debugPrint('  📧 Email: "${userEmail.value}" → "${newUser.email}"');
        userEmail.value = newUser.email;
      }
      if (userProfileImage.value != newUser.profileImageUrl) {
        debugPrint('  🖼️ Profile image updated');
        userProfileImage.value = newUser.profileImageUrl;
      }
    }
  }

  /// Update progress smoothly for animation
  void updateProgressSmoothly(int newProgressMs) {
    progressMs.value = newProgressMs;
    // Update time display
    final seconds = (newProgressMs ~/ 1000) % 60;
    final minutes = newProgressMs ~/ 60000;
    currentTime.value = '$minutes:${seconds.toString().padLeft(2, '0')}';
  }

  /// Update from scraped data (only if user is not actively controlling)
  void updateFromScrapedData(PlaybackState? newState) {
    batchUpdate(newState, null);
  }

  /// Update user info from scraped data
  void updateUserInfo(User? newUser) {
    batchUpdate(null, newUser);
  }

  /// Mark that user is actively controlling playback
  void startUserControl() {
    isUserControlling.value = true;
    _lastUserAction = DateTime.now();

    // Auto-release control after 5 seconds
    Future.delayed(const Duration(seconds: 5), () {
      if (_lastUserAction != null &&
          DateTime.now().difference(_lastUserAction!).inSeconds >= 5) {
        isUserControlling.value = false;
      }
    });
  }

  /// Optimistic update for play
  void setPlaying(bool playing) {
    startUserControl();
    isPlaying.value = playing;
  }

  /// Optimistic update for shuffle
  void toggleShuffle() {
    startUserControl();
    if (shuffleMode.value == ShuffleMode.off) {
      shuffleMode.value = ShuffleMode.normal;
    } else if (shuffleMode.value == ShuffleMode.normal) {
      shuffleMode.value = ShuffleMode.enhanced;
    } else {
      shuffleMode.value = ShuffleMode.off;
    }
  }

  /// Optimistic update for repeat
  void toggleRepeat() {
    startUserControl();
    if (repeatMode.value == RepeatMode.off) {
      repeatMode.value = RepeatMode.all;
    } else if (repeatMode.value == RepeatMode.all) {
      repeatMode.value = RepeatMode.one;
    } else {
      repeatMode.value = RepeatMode.off;
    }
  }

  /// Optimistic update for like
  void toggleLike() {
    startUserControl();
    isCurrentTrackLiked.value = !isCurrentTrackLiked.value;
  }

  /// Get current playback state as object
  PlaybackState get currentPlaybackState => PlaybackState(
    isPlaying: isPlaying.value,
    currentTrack: currentTrack.value,
    currentArtist: currentArtist.value,
    currentAlbumArt: currentAlbumArt.value,
    isCurrentTrackLiked: isCurrentTrackLiked.value,
    shuffleMode: shuffleMode.value,
    repeatMode: repeatMode.value,
    currentTime: currentTime.value,
    duration: duration.value,
    progressMs: progressMs.value,
    durationMs: durationMs.value,
  );

  /// Dispose all notifiers
  void dispose() {
    isPlaying.dispose();
    currentTrack.dispose();
    currentArtist.dispose();
    currentAlbumArt.dispose();
    isCurrentTrackLiked.dispose();
    shuffleMode.dispose();
    repeatMode.dispose();
    currentTime.dispose();
    duration.dispose();
    progressMs.dispose();
    durationMs.dispose();
    isLoggedIn.dispose();
    username.dispose();
    userEmail.dispose();
    userProfileImage.dispose();
    songs.dispose();
    isLoadingSongs.dispose();
    showWebView.dispose();
    isInitialized.dispose();
    isUserControlling.dispose();
  }
}