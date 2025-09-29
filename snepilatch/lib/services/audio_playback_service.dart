import 'dart:async';
import 'dart:typed_data';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';
import 'package:path_provider/path_provider.dart';

/// Service to play audio data received from WebSocket
/// This implementation writes WebM data to a temp file and plays it through just_audio
/// The audio goes through Android's music stream so Wavelet can process it
class AudioPlaybackService {
  static AudioPlaybackService? _instance;
  AudioPlayer? _player;

  // File-based streaming approach for WebM
  File? _tempAudioFile;
  RandomAccessFile? _writeFile;
  bool _hasWrittenData = false;
  int _fileWritePosition = 0;

  // Status tracking
  bool _isPlaying = false;
  bool _isInitialized = false;
  int _totalBytesReceived = 0;
  int _chunksReceived = 0;
  bool _isBuffering = true;
  final int _minBufferSize = 102400; // 100KB minimum before starting playback

  // Singleton
  static AudioPlaybackService get instance {
    _instance ??= AudioPlaybackService._();
    return _instance!;
  }

  AudioPlaybackService._() {
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      _player = AudioPlayer();

      // Note: Audio will go through the default Android music stream
      // which Wavelet should be able to intercept
      // just_audio uses music stream by default

      // Create temp file for streaming WebM data
      final tempDir = await getTemporaryDirectory();
      final timestamp = DateTime.now().millisecondsSinceEpoch;
      _tempAudioFile = File('${tempDir.path}/spotify_stream_$timestamp.webm');

      // Open file for writing
      _writeFile = await _tempAudioFile!.open(mode: FileMode.write);

      _isInitialized = true;
      debugPrint('🎵 Audio playback service initialized');
      debugPrint('📁 Temp file: ${_tempAudioFile!.path}');
      debugPrint('🎧 Audio will route through Android music stream for Wavelet processing');
    } catch (e) {
      debugPrint('❌ Failed to initialize audio player: $e');
    }
  }

  /// Add audio data from WebSocket
  void addAudioData(Uint8List data) async {
    if (!_isInitialized || data.isEmpty) return;

    _totalBytesReceived += data.length;
    _chunksReceived++;

    // Write directly to file
    try {
      await _writeFile!.writeFrom(data);
      _fileWritePosition += data.length;
      _hasWrittenData = true;

      // Log progress
      if (_chunksReceived % 20 == 0) {
        debugPrint('🎵 Audio data written: ${(_fileWritePosition / 1024).toFixed(1)}KB total');
      }

      // Start playback once we have enough buffered data
      if (_isBuffering && _fileWritePosition >= _minBufferSize) {
        _isBuffering = false;
        _startPlayback();
      }
    } catch (e) {
      debugPrint('❌ Error writing audio data: $e');
    }
  }

  Future<void> _startPlayback() async {
    if (_isPlaying || !_hasWrittenData) return;

    try {
      debugPrint('▶️ Starting audio playback through Android music stream');
      debugPrint('📊 Buffer size: ${(_fileWritePosition / 1024).toFixed(1)}KB');

      // Flush any pending writes
      await _writeFile!.flush();

      // Set the audio source to our temp file
      // just_audio will handle WebM/Opus decoding
      await _player!.setAudioSource(
        AudioSource.file(_tempAudioFile!.path),
        preload: false, // Don't preload entire file
      );

      // Start playback
      _player!.play();
      _isPlaying = true;

      debugPrint('✅ Audio playback started - Wavelet should now process the audio');

      // Monitor playback position
      _player!.positionStream.listen((position) {
        if (position.inSeconds > 0 && position.inSeconds % 10 == 0) {
          debugPrint('🎵 Playback position: ${position.inSeconds}s');
        }
      });

      // Handle playback completion
      _player!.processingStateStream.listen((state) {
        if (state == ProcessingState.completed) {
          debugPrint('✅ Playback completed');
          _reset();
        }
      });
    } catch (e) {
      debugPrint('❌ Error starting playback: $e');
      debugPrint('ℹ️ Note: just_audio may not support WebM on all platforms');
      debugPrint('ℹ️ Trying alternative approach...');
      _tryAlternativePlayback();
    }
  }

  Future<void> _tryAlternativePlayback() async {
    // Alternative: Use media3 ExoPlayer directly which has better WebM support
    debugPrint('⚠️ WebM playback failed with just_audio');
    debugPrint('ℹ️ Alternative solutions:');
    debugPrint('  1. Use audioplayers package instead');
    debugPrint('  2. Capture PCM audio instead of WebM');
    debugPrint('  3. Transcode WebM to a supported format');

    // For now, let's try with audioplayers if available
    // You can add audioplayers to pubspec.yaml and use it as fallback
  }

  void pause() {
    if (_isPlaying) {
      _player?.pause();
      debugPrint('⏸️ Audio playback paused');
    }
  }

  void resume() {
    if (_isPlaying) {
      _player?.play();
      debugPrint('▶️ Audio playback resumed');
    }
  }

  void stop() {
    _player?.stop();
    _isPlaying = false;
    _isBuffering = true;
    debugPrint('⏹️ Audio playback stopped');
  }

  Future<void> _reset() async {
    stop();

    // Close current file
    await _writeFile?.close();

    // Delete old temp file
    if (_tempAudioFile?.existsSync() == true) {
      await _tempAudioFile!.delete();
    }

    // Create new temp file for next stream
    if (_isInitialized) {
      final tempDir = await getTemporaryDirectory();
      final timestamp = DateTime.now().millisecondsSinceEpoch;
      _tempAudioFile = File('${tempDir.path}/spotify_stream_$timestamp.webm');
      _writeFile = await _tempAudioFile!.open(mode: FileMode.write);
    }

    _fileWritePosition = 0;
    _hasWrittenData = false;
    _totalBytesReceived = 0;
    _chunksReceived = 0;

    debugPrint('🔄 Audio service reset');
  }

  void dispose() {
    stop();
    _player?.dispose();
    _writeFile?.close();
    _tempAudioFile?.delete();
  }

  // Status getters
  bool get isPlaying => _isPlaying;
  bool get isBuffering => _isBuffering;
  int get bufferSize => _fileWritePosition;
  int get totalBytesReceived => _totalBytesReceived;
  int get chunksReceived => _chunksReceived;
}

// Extension for number formatting
extension on double {
  String toFixed(int decimals) => toStringAsFixed(decimals);
}