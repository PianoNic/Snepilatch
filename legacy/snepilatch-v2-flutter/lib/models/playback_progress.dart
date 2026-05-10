class PlaybackProgress {
  final double progress; // 0-100
  final String currentTime; // MM:SS format
  final String duration; // MM:SS format
  final int durationMs; // Total duration in milliseconds

  PlaybackProgress({
    required this.progress,
    required this.currentTime,
    required this.duration,
    required this.durationMs,
  });

  /// Create PlaybackProgress from WebSocket data
  factory PlaybackProgress.fromWebSocket(Map<String, dynamic> data) {
    final rawProgress = double.tryParse(data['progress'].toString()) ?? 0.0;
    final durationStr = data['duration'] as String? ?? '00:00';
    final durationMs = _parseTimeStringToMs(durationStr);

    return PlaybackProgress(
      progress: rawProgress.clamp(0.0, 100.0),
      currentTime: data['currentTime'] as String? ?? '00:00',
      duration: durationStr,
      durationMs: durationMs,
    );
  }

  /// Parse time string (MM:SS or H:MM:SS) to milliseconds
  static int _parseTimeStringToMs(String timeStr) {
    final parts = timeStr.split(':').map(int.tryParse).whereType<int>().toList();
    int totalSeconds = 0;

    if (parts.length == 2) {
      // MM:SS format
      totalSeconds = parts[0] * 60 + parts[1];
    } else if (parts.length == 3) {
      // H:MM:SS format
      totalSeconds = parts[0] * 3600 + parts[1] * 60 + parts[2];
    }

    return totalSeconds * 1000;
  }

  /// Get progress as a fraction (0.0 - 1.0) for progress bar widgets
  double get progressFraction => (progress / 100).clamp(0.0, 1.0);

  @override
  String toString() => 'PlaybackProgress($currentTime / $duration - $progress%)';
}
