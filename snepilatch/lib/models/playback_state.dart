class PlaybackState {
  final bool isPlaying;
  final String? currentTrack;
  final String? currentArtist;
  final String? currentAlbumArt;
  final bool isCurrentTrackLiked;
  final ShuffleMode shuffleMode;
  final RepeatMode repeatMode;

  PlaybackState({
    this.isPlaying = false,
    this.currentTrack,
    this.currentArtist,
    this.currentAlbumArt,
    this.isCurrentTrackLiked = false,
    this.shuffleMode = ShuffleMode.off,
    this.repeatMode = RepeatMode.off,
  });

  PlaybackState copyWith({
    bool? isPlaying,
    String? currentTrack,
    String? currentArtist,
    String? currentAlbumArt,
    bool? isCurrentTrackLiked,
    ShuffleMode? shuffleMode,
    RepeatMode? repeatMode,
  }) {
    return PlaybackState(
      isPlaying: isPlaying ?? this.isPlaying,
      currentTrack: currentTrack ?? this.currentTrack,
      currentArtist: currentArtist ?? this.currentArtist,
      currentAlbumArt: currentAlbumArt ?? this.currentAlbumArt,
      isCurrentTrackLiked: isCurrentTrackLiked ?? this.isCurrentTrackLiked,
      shuffleMode: shuffleMode ?? this.shuffleMode,
      repeatMode: repeatMode ?? this.repeatMode,
    );
  }
}

enum ShuffleMode { off, normal, enhanced }

enum RepeatMode { off, all, one }

extension ShuffleModeExtension on ShuffleMode {
  String get value {
    switch (this) {
      case ShuffleMode.off:
        return 'off';
      case ShuffleMode.normal:
        return 'normal';
      case ShuffleMode.enhanced:
        return 'enhanced';
    }
  }

  static ShuffleMode fromString(String value) {
    switch (value) {
      case 'normal':
        return ShuffleMode.normal;
      case 'enhanced':
        return ShuffleMode.enhanced;
      default:
        return ShuffleMode.off;
    }
  }
}

extension RepeatModeExtension on RepeatMode {
  String get value {
    switch (this) {
      case RepeatMode.off:
        return 'off';
      case RepeatMode.all:
        return 'all';
      case RepeatMode.one:
        return 'one';
    }
  }

  static RepeatMode fromString(String value) {
    switch (value) {
      case 'all':
        return RepeatMode.all;
      case 'one':
        return RepeatMode.one;
      default:
        return RepeatMode.off;
    }
  }
}