import 'package:audio_service/audio_service.dart';
import 'package:flutter/foundation.dart';

class SpotifyAudioHandler extends BaseAudioHandler with SeekHandler {
  // Track the current media item
  MediaItem? _currentMediaItem;

  // Callbacks for controlling Spotify through the WebView
  VoidCallback? onPlayCallback;
  VoidCallback? onPauseCallback;
  VoidCallback? onNextCallback;
  VoidCallback? onPreviousCallback;
  Function(Duration)? onSeekCallback;
  VoidCallback? onToggleLikeCallback;

  SpotifyAudioHandler() {
    // Set initial playback state with controls in correct order
    playbackState.add(PlaybackState(
      controls: [
        MediaControl.skipToPrevious,
        MediaControl.play,
        MediaControl.skipToNext,
        likeControl,
      ],
      androidCompactActionIndices: const [0, 1, 2],
      processingState: AudioProcessingState.idle,
      playing: false,
      updatePosition: Duration.zero,
      bufferedPosition: Duration.zero,
      speed: 1.0,
    ));
  }

  // Custom media control for like button
  static final likeControl = MediaControl(
    androidIcon: 'drawable/ic_action_favorite_border',
    label: 'Like',
    action: MediaAction.custom,
    customAction: CustomMediaAction(name: 'like'),
  );

  static final likedControl = MediaControl(
    androidIcon: 'drawable/ic_action_favorite',
    label: 'Unlike',
    action: MediaAction.custom,
    customAction: CustomMediaAction(name: 'unlike'),
  );

  bool _isLiked = false;

  @override
  Future<void> play() async {
    playbackState.add(playbackState.value.copyWith(
      playing: true,
      controls: [
        MediaControl.skipToPrevious,
        MediaControl.pause,
        MediaControl.skipToNext,
        _isLiked ? likedControl : likeControl,
      ],
      systemActions: const {
        MediaAction.seek,
        MediaAction.seekForward,
        MediaAction.seekBackward,
      },
      // Show only the three main controls in compact view
      androidCompactActionIndices: const [0, 1, 2],
      processingState: AudioProcessingState.ready,
    ));
    onPlayCallback?.call();
  }

  @override
  Future<void> pause() async {
    playbackState.add(playbackState.value.copyWith(
      playing: false,
      controls: [
        MediaControl.skipToPrevious,
        MediaControl.play,
        MediaControl.skipToNext,
        _isLiked ? likedControl : likeControl,
      ],
      // Show only the three main controls in compact view
      androidCompactActionIndices: const [0, 1, 2],
    ));
    onPauseCallback?.call();
  }

  @override
  Future<void> skipToNext() async {
    onNextCallback?.call();
  }

  @override
  Future<void> skipToPrevious() async {
    onPreviousCallback?.call();
  }

  @override
  Future<void> seek(Duration position) async {
    playbackState.add(playbackState.value.copyWith(
      updatePosition: position,
    ));
    onSeekCallback?.call(position);
  }

  @override
  Future<void> stop() async {
    await super.stop();
    playbackState.add(playbackState.value.copyWith(
      playing: false,
      processingState: AudioProcessingState.idle,
    ));
  }

  // Update the currently playing track
  void setMediaItem({
    required String title,
    required String artist,
    String? album,
    String? artUri,
    Duration? duration,
  }) {
    _currentMediaItem = MediaItem(
      id: title,
      title: title,
      artist: artist,
      album: album,
      duration: duration,
      artUri: artUri != null ? Uri.parse(artUri) : null,
    );

    mediaItem.add(_currentMediaItem);
  }

  // Update playback state
  void updatePlaybackState({
    required bool isPlaying,
    Duration? position,
    Duration? duration,
    double speed = 1.0,
    bool? isLiked,
  }) {
    if (isLiked != null) {
      _isLiked = isLiked;
    }

    playbackState.add(
      PlaybackState(
        controls: [
          MediaControl.skipToPrevious,
          isPlaying ? MediaControl.pause : MediaControl.play,
          MediaControl.skipToNext,
          _isLiked ? likedControl : likeControl,
        ],
        systemActions: const {
          MediaAction.seek,
          MediaAction.seekForward,
          MediaAction.seekBackward,
        },
        // Show only the three main controls in compact view
        androidCompactActionIndices: const [0, 1, 2],
        processingState: AudioProcessingState.ready,
        playing: isPlaying,
        updatePosition: position ?? Duration.zero,
        bufferedPosition: duration ?? Duration.zero,
        speed: speed,
      ),
    );
  }

  @override
  Future<void> customAction(String name, [Map<String, dynamic>? extras]) async {
    if (name == 'like' || name == 'unlike') {
      // Toggle like state
      _isLiked = !_isLiked;

      // Call the toggle like callback
      onToggleLikeCallback?.call();

      // Update playback state with new like button
      final currentState = playbackState.value;
      playbackState.add(currentState.copyWith(
        controls: [
          MediaControl.skipToPrevious,
          currentState.playing ? MediaControl.pause : MediaControl.play,
          MediaControl.skipToNext,
          _isLiked ? likedControl : likeControl,
        ],
      ));

      // Update media item extras
      if (_currentMediaItem != null) {
        final updatedItem = _currentMediaItem!.copyWith(
          extras: {'liked': _isLiked},
        );
        _currentMediaItem = updatedItem;
        mediaItem.add(updatedItem);
      }
    } else if (name == 'setLiked') {
      // Set liked status from external source
      final liked = extras?['liked'] as bool? ?? false;
      _isLiked = liked;

      // Update playback state
      final currentState = playbackState.value;
      playbackState.add(currentState.copyWith(
        controls: [
          MediaControl.skipToPrevious,
          currentState.playing ? MediaControl.pause : MediaControl.play,
          MediaControl.skipToNext,
          _isLiked ? likedControl : likeControl,
        ],
      ));
    }
  }
}