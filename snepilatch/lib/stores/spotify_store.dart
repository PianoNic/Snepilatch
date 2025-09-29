import 'package:flutter/foundation.dart';
import '../models/playback_state.dart';
import '../models/user.dart';
import '../models/song.dart';
import '../models/homepage_item.dart';

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
  final ValueNotifier<String?> videoUrl = ValueNotifier(null);
  final ValueNotifier<String?> videoThumbnail = ValueNotifier(null);
  final ValueNotifier<Map<String, dynamic>?> videoData = ValueNotifier(null);

  // User state signals
  final ValueNotifier<bool> isLoggedIn = ValueNotifier(false);
  final ValueNotifier<String?> username = ValueNotifier(null);
  final ValueNotifier<String?> userEmail = ValueNotifier(null);
  final ValueNotifier<String?> userProfileImage = ValueNotifier(null);

  // Songs state
  final ValueNotifier<List<Song>> songs = ValueNotifier([]);
  final ValueNotifier<bool> isLoadingSongs = ValueNotifier(false);

  // Homepage sections
  final ValueNotifier<List<HomepageSection>> homepageSections = ValueNotifier([]);

  // UI state
  final ValueNotifier<bool> showWebView = ValueNotifier(false);
  final ValueNotifier<bool> isInitialized = ValueNotifier(false);

  // Control state - tracks if user is controlling playback
  final ValueNotifier<bool> isUserControlling = ValueNotifier(false);
  DateTime? _lastUserAction;
  DateTime? _lastScrapedTime;
  final int _lastScrapedProgressMs = 0;

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
        if (videoUrl.value != newState.videoUrl) {
          debugPrint('  🎬 Video URL updated');
          videoUrl.value = newState.videoUrl;
        }
        if (videoThumbnail.value != newState.videoThumbnail) {
          // Don't log thumbnail changes as they happen frequently
          videoThumbnail.value = newState.videoThumbnail;
        }
      } else {
        // Update all fields if user is not controlling
        if (isPlaying.value != newState.isPlaying) {
          debugPrint('  ▶️ Playing: $isPlaying → ${newState.isPlaying}');
          isPlaying.value = newState.isPlaying;
        }
        // Don't update track to null if we already have a track (prevents UI flicker)
        if (currentTrack.value != newState.currentTrack) {
          // Only update if new value is not null OR if current value is null
          if (newState.currentTrack != null || currentTrack.value == null) {
            debugPrint('  🎵 Track: "${currentTrack.value}" → "${newState.currentTrack}"');
            currentTrack.value = newState.currentTrack;
          }
        }
        if (currentArtist.value != newState.currentArtist) {
          // Only update if new value is not null OR if current value is null
          if (newState.currentArtist != null || currentArtist.value == null) {
            debugPrint('  🎤 Artist: "${currentArtist.value}" → "${newState.currentArtist}"');
            currentArtist.value = newState.currentArtist;
          }
        }
        if (currentAlbumArt.value != newState.currentAlbumArt) {
          // Only update if new value is not null OR if current value is null
          if (newState.currentAlbumArt != null || currentAlbumArt.value == null) {
            debugPrint('  🖼️ Album Art updated');
            currentAlbumArt.value = newState.currentAlbumArt;
          }
        }
        if (videoUrl.value != newState.videoUrl) {
          // Always update video URL even if it's null (track might not have video)
          debugPrint('  🎬 Video URL updated');
          videoUrl.value = newState.videoUrl;
        }
        if (videoThumbnail.value != newState.videoThumbnail) {
          // Don't log thumbnail changes as they happen frequently
          videoThumbnail.value = newState.videoThumbnail;
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
        // Update duration (this shouldn't jump around)
        if (duration.value != newState.duration) {
          duration.value = newState.duration ?? '0:00';
        }
        if (durationMs.value != newState.durationMs) {
          durationMs.value = newState.durationMs;
        }

        // Update progress and time from scraped values
        if (!isUserControlling.value) {
          // Update both progress and time display from scraped values
          // The progress bar will interpolate smoothly between these updates
          progressMs.value = newState.progressMs;
          currentTime.value = newState.currentTime ?? '0:00';
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

  /// Update progress smoothly for animation (now just updates directly)
  void updateProgressSmoothly(int newProgressMs) {
    // Direct update from scraped values only
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

    // Auto-release control after 2 seconds
    Future.delayed(const Duration(seconds: 2), () {
      if (_lastUserAction != null &&
          DateTime.now().difference(_lastUserAction!).inSeconds >= 2) {
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

  /// Clear user data on logout
  void clearUserData() {
    debugPrint('🚪 [SpotifyStore] Clearing user data on logout');

    // Clear user info
    isLoggedIn.value = false;
    username.value = null;
    userEmail.value = null;
    userProfileImage.value = null;

    // Clear playback state
    isPlaying.value = false;
    currentTrack.value = null;
    currentArtist.value = null;
    currentAlbumArt.value = null;
    isCurrentTrackLiked.value = false;
    shuffleMode.value = ShuffleMode.off;
    repeatMode.value = RepeatMode.off;
    currentTime.value = '0:00';
    duration.value = '0:00';
    progressMs.value = 0;
    durationMs.value = 0;

    // Clear songs
    songs.value = [];
    isLoadingSongs.value = false;

    // Clear homepage sections
    homepageSections.value = [];

    // Hide WebView
    showWebView.value = false;
  }

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
    homepageSections.dispose();
    showWebView.dispose();
    isInitialized.dispose();
    isUserControlling.dispose();
  }
}