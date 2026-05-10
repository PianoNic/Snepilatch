import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';
import 'package:snepilatch_v2/models/playback_progress.dart';
import 'package:snepilatch_v2/models/player_control_state.dart';
import 'package:snepilatch_v2/models/song_info.dart';
import 'package:snepilatch_v2/services/spotify_client.dart';

final _likeAction = MediaControl(
  androidIcon: 'drawable/ic_favorite_border',
  label: 'Like',
  action: MediaAction.custom,
  customAction: const CustomMediaAction(name: 'like'),
);

final _unlikeAction = MediaControl(
  androidIcon: 'drawable/ic_favorite',
  label: 'Unlike',
  action: MediaAction.custom,
  customAction: const CustomMediaAction(name: 'unlike'),
);

class MultimediaActionHandler extends ChangeNotifier {
  late SnepilatchAudioHandler _audioHandler;
  SpotifyClient? _spotifyClient;

  // Track previous values to avoid redundant updates
  String? _lastTrackUri;

  Future<void> initialize(SpotifyClient spotifyClient) async {
    _spotifyClient = spotifyClient;

    _audioHandler = await AudioService.init(
      builder: () => SnepilatchAudioHandler(),
      config: const AudioServiceConfig(
        androidNotificationChannelId: 'com.pianonic.snepilatch.channel.audio',
        androidNotificationChannelName: 'Audio playback',
        androidNotificationOngoing: true,
        androidStopForegroundOnPause: true,
        androidNotificationIcon: 'mipmap/ic_launcher',
      ),
    );

    _audioHandler.setSpotifyClient(spotifyClient);

    // Listen to SpotifyClient state changes
    spotifyClient.addListener(_onSpotifyClientChanged);
  }

  void _onSpotifyClientChanged() {
    final client = _spotifyClient;
    if (client == null) return;

    final track = client.currentTrack;
    final progress = client.playbackProgress;
    final controlState = client.playerControlState;

    // Update MediaItem when track changes
    if (track != null && track.uri != _lastTrackUri) {
      _lastTrackUri = track.uri;
      _updateMediaItem(track, progress);
    }

    // Update PlaybackState when play/pause, progress, or liked changes
    if (controlState != null || progress != null) {
      _updatePlaybackState(controlState, progress, track?.liked ?? false);
    }
  }

  void _updateMediaItem(SongInfo track, PlaybackProgress? playbackProgress) {
    _audioHandler.mediaItem.add(MediaItem(
      id: track.uri ?? '',
      title: track.title ?? 'Unknown',
      artist: track.artist ?? 'Unknown',
      artUri: track.imageUrl != null ? Uri.parse(track.imageUrl!) : null,
      duration: playbackProgress != null
          ? Duration(milliseconds: playbackProgress.durationMs)
          : null,
    ));
  }

  void _updatePlaybackState(
    PlayerControlState? controlState,
    PlaybackProgress? playbackProgress,
    bool isLiked,
  ) {
    final isPlaying = controlState?.playPause == 'playing';
    final progressPercent = playbackProgress?.progress ?? 0.0;
    final durationMs = playbackProgress?.durationMs ?? 0;
    final positionMs = ((progressPercent / 100.0) * durationMs).toInt();

    _audioHandler.playbackState.add(PlaybackState(
      controls: [
        isLiked ? _unlikeAction : _likeAction,
        MediaControl.skipToPrevious,
        if (isPlaying) MediaControl.pause else MediaControl.play,
        MediaControl.skipToNext,
      ],
      systemActions: const {
        MediaAction.seek,
        MediaAction.seekForward,
        MediaAction.seekBackward,
      },
      androidCompactActionIndices: const [1, 2, 3],
      processingState: AudioProcessingState.ready,
      playing: isPlaying,
      updatePosition: Duration(milliseconds: positionMs),
    ));
  }

  @override
  void dispose() {
    _spotifyClient?.removeListener(_onSpotifyClientChanged);
    super.dispose();
  }
}

class SnepilatchAudioHandler extends BaseAudioHandler with SeekHandler {
  SpotifyClient? _spotifyClient;

  void setSpotifyClient(SpotifyClient client) {
    _spotifyClient = client;
  }

  @override
  Future<void> play() async {
    _spotifyClient?.togglePlayPause();
  }

  @override
  Future<void> pause() async {
    _spotifyClient?.togglePlayPause();
  }

  @override
  Future<void> skipToNext() async {
    _spotifyClient?.skipNext();
  }

  @override
  Future<void> skipToPrevious() async {
    _spotifyClient?.skipPrevious();
  }

  @override
  Future<void> seek(Duration position) async {
    _spotifyClient?.seekTo(position.inMilliseconds);
  }

  @override
  Future<void> stop() async {
    _spotifyClient?.togglePlayPause();
  }

  @override
  Future<void> customAction(String name, [Map<String, dynamic>? extras]) async {
    if (name == 'like' || name == 'unlike') {
      _spotifyClient?.toggleLiked();
    }
  }
}
