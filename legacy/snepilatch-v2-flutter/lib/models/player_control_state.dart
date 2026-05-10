class PlayerControlState {
  final String? playPause; // 'playing' or 'paused'
  final String? loop; // 'loop_off', 'loop_all', or 'loop_one'
  final String? shuffle; // 'shuffle_off', 'shuffle_on', or 'shuffle_smart'

  PlayerControlState({
    this.playPause,
    this.loop,
    this.shuffle,
  });

  /// Create PlayerControlState from WebSocket data
  factory PlayerControlState.fromWebSocket(Map<String, dynamic> data) {
    return PlayerControlState(
      playPause: data['playPause'] as String?,
      loop: data['loop'] as String?,
      shuffle: data['shuffle'] as String?,
    );
  }

  @override
  String toString() => 'PlayerControlState(playPause: $playPause, loop: $loop, shuffle: $shuffle)';
}
