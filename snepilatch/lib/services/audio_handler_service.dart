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

  SpotifyAudioHandler();

  @override
  Future<void> play() async {
    playbackState.add(playbackState.value.copyWith(
      playing: true,
      controls: [
        MediaControl.skipToPrevious,
        MediaControl.pause,
        MediaControl.skipToNext,
      ],
      systemActions: const {
        MediaAction.seek,
        MediaAction.seekForward,
        MediaAction.seekBackward,
      },
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
      ],
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
  }) {
    playbackState.add(
      PlaybackState(
        controls: [
          MediaControl.skipToPrevious,
          isPlaying ? MediaControl.pause : MediaControl.play,
          MediaControl.skipToNext,
        ],
        systemActions: const {
          MediaAction.seek,
          MediaAction.seekForward,
          MediaAction.seekBackward,
        },
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
    if (name == 'setLiked') {
      // Update the media item with liked status if needed
      final liked = extras?['liked'] as bool? ?? false;
      if (_currentMediaItem != null) {
        final updatedItem = _currentMediaItem!.copyWith(
          extras: {'liked': liked},
        );
        _currentMediaItem = updatedItem;
        mediaItem.add(updatedItem);
      }
    }
  }
}