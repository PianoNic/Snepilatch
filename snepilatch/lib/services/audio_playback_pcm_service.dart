import 'dart:async';
import 'dart:typed_data';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';
import 'package:path_provider/path_provider.dart';

/// Service to play PCM audio data received from WebSocket
/// This implementation writes PCM data to a WAV file and plays it through just_audio
class AudioPlaybackPCMService {
  static AudioPlaybackPCMService? _instance;
  AudioPlayer? _player;

  // File-based streaming approach for PCM -> WAV
  File? _tempAudioFile;
  RandomAccessFile? _writeFile;
  bool _hasWrittenHeader = false;
  int _dataSize = 0;

  // Buffer for accumulating PCM chunks
  final List<int> _audioBuffer = [];
  Timer? _flushTimer;

  // Status tracking
  bool _isPlaying = false;
  bool _isInitialized = false;
  int _totalBytesReceived = 0;
  int _chunksReceived = 0;
  bool _isBuffering = true;
  final int _minBufferSize = 88200; // ~0.5 seconds at 44.1kHz stereo 16-bit

  // Audio format constants
  static const int sampleRate = 44100;
  static const int channels = 2;
  static const int bitsPerSample = 16;

  // Singleton
  static AudioPlaybackPCMService get instance {
    _instance ??= AudioPlaybackPCMService._();
    return _instance!;
  }

  AudioPlaybackPCMService._() {
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      _player = AudioPlayer();

      // Create temp WAV file
      final tempDir = await getTemporaryDirectory();
      final timestamp = DateTime.now().millisecondsSinceEpoch;
      _tempAudioFile = File('${tempDir.path}/spotify_pcm_$timestamp.wav');

      // Open file and write WAV header
      _writeFile = await _tempAudioFile!.open(mode: FileMode.write);
      await _writeWavHeader();

      // Start flush timer to periodically write buffered data
      _flushTimer = Timer.periodic(const Duration(milliseconds: 100), (_) {
        _flushBuffer();
      });

      _isInitialized = true;
      debugPrint('🎵 PCM Audio playback service initialized');
      debugPrint('📁 Temp WAV file: ${_tempAudioFile!.path}');
      debugPrint('🎧 Audio format: ${sampleRate}Hz, $channels channels, $bitsPerSample-bit');
    } catch (e) {
      debugPrint('❌ Failed to initialize PCM audio player: $e');
    }
  }

  Future<void> _writeWavHeader() async {
    // WAV file header (44 bytes)
    final header = ByteData(44);

    // "RIFF" chunk descriptor
    header.setUint8(0, 0x52); // R
    header.setUint8(1, 0x49); // I
    header.setUint8(2, 0x46); // F
    header.setUint8(3, 0x46); // F

    // File size (will be updated later)
    header.setUint32(4, 0, Endian.little);

    // "WAVE" format
    header.setUint8(8, 0x57);  // W
    header.setUint8(9, 0x41);  // A
    header.setUint8(10, 0x56); // V
    header.setUint8(11, 0x45); // E

    // "fmt " subchunk
    header.setUint8(12, 0x66); // f
    header.setUint8(13, 0x6D); // m
    header.setUint8(14, 0x74); // t
    header.setUint8(15, 0x20); // space

    // Subchunk1 size (16 for PCM)
    header.setUint32(16, 16, Endian.little);

    // Audio format (1 = PCM)
    header.setUint16(20, 1, Endian.little);

    // Number of channels
    header.setUint16(22, channels, Endian.little);

    // Sample rate
    header.setUint32(24, sampleRate, Endian.little);

    // Byte rate (SampleRate * NumChannels * BitsPerSample/8)
    header.setUint32(28, sampleRate * channels * bitsPerSample ~/ 8, Endian.little);

    // Block align (NumChannels * BitsPerSample/8)
    header.setUint16(32, channels * bitsPerSample ~/ 8, Endian.little);

    // Bits per sample
    header.setUint16(34, bitsPerSample, Endian.little);

    // "data" subchunk
    header.setUint8(36, 0x64); // d
    header.setUint8(37, 0x61); // a
    header.setUint8(38, 0x74); // t
    header.setUint8(39, 0x61); // a

    // Subchunk2 size (will be updated later)
    header.setUint32(40, 0, Endian.little);

    await _writeFile!.writeFrom(header.buffer.asUint8List());
    _hasWrittenHeader = true;
  }

  /// Add PCM audio data from WebSocket
  void addAudioData(Uint8List data) {
    if (!_isInitialized || data.isEmpty) return;

    _totalBytesReceived += data.length;
    _chunksReceived++;

    // Add to buffer
    _audioBuffer.addAll(data);

    // Log progress
    if (_chunksReceived % 50 == 0) {
      final seconds = _dataSize / (sampleRate * channels * 2);
      debugPrint('🎵 PCM data buffered: ${(_totalBytesReceived / 1024).toFixed(1)}KB (${seconds.toFixed(1)}s)');
    }

    // Start playback once we have enough buffered data
    if (_isBuffering && _audioBuffer.length >= _minBufferSize) {
      _isBuffering = false;
      _startPlayback();
    }
  }

  void _flushBuffer() async {
    if (_audioBuffer.isEmpty || !_hasWrittenHeader) return;

    try {
      // Write buffered data to file
      await _writeFile!.writeFrom(Uint8List.fromList(_audioBuffer));
      _dataSize += _audioBuffer.length;
      _audioBuffer.clear();

      // Update WAV header with new size
      await _updateWavHeader();
    } catch (e) {
      debugPrint('❌ Error flushing PCM buffer: $e');
    }
  }

  Future<void> _updateWavHeader() async {
    final position = await _writeFile!.position();

    // Update file size
    await _writeFile!.setPosition(4);
    await _writeFile!.writeFrom(_int32LEBytes(36 + _dataSize));

    // Update data chunk size
    await _writeFile!.setPosition(40);
    await _writeFile!.writeFrom(_int32LEBytes(_dataSize));

    // Return to end of file
    await _writeFile!.setPosition(position);
  }

  Uint8List _int32LEBytes(int value) {
    final bytes = Uint8List(4);
    bytes[0] = value & 0xFF;
    bytes[1] = (value >> 8) & 0xFF;
    bytes[2] = (value >> 16) & 0xFF;
    bytes[3] = (value >> 24) & 0xFF;
    return bytes;
  }

  Future<void> _startPlayback() async {
    if (_isPlaying || _dataSize == 0) return;

    try {
      // Flush any remaining buffer
      _flushBuffer();
      await _writeFile!.flush();

      debugPrint('▶️ Starting PCM audio playback');
      debugPrint('📊 Audio duration: ${(_dataSize / (sampleRate * channels * 2)).toFixed(1)}s');

      // Set the audio source to our WAV file
      await _player!.setAudioSource(
        AudioSource.file(_tempAudioFile!.path),
        preload: false,
      );

      // Start playback
      _player!.play();
      _isPlaying = true;

      debugPrint('✅ PCM playback started - audio should play through system');

      // Monitor playback
      _player!.positionStream.listen((position) {
        if (position.inSeconds > 0 && position.inSeconds % 5 == 0) {
          debugPrint('🎵 Playback position: ${position.inSeconds}s');
        }
      });

      // Handle completion
      _player!.processingStateStream.listen((state) {
        if (state == ProcessingState.completed) {
          debugPrint('✅ Playback completed');
          _reset();
        }
      });
    } catch (e) {
      debugPrint('❌ Error starting PCM playback: $e');
    }
  }

  Future<void> _reset() async {
    stop();

    // Cancel timers
    _flushTimer?.cancel();

    // Close current file
    await _writeFile?.close();

    // Delete old temp file
    if (_tempAudioFile?.existsSync() == true) {
      await _tempAudioFile!.delete();
    }

    // Reinitialize for next stream
    await _initialize();
  }

  void stop() {
    _player?.stop();
    _isPlaying = false;
    _isBuffering = true;
    _audioBuffer.clear();
    debugPrint('⏹️ PCM playback stopped');
  }

  void dispose() {
    _flushTimer?.cancel();
    stop();
    _player?.dispose();
    _writeFile?.close();
    _tempAudioFile?.delete();
  }

  // Status getters
  bool get isPlaying => _isPlaying;
  bool get isBuffering => _isBuffering;
  int get bufferSize => _audioBuffer.length;
  int get totalBytesReceived => _totalBytesReceived;
  int get chunksReceived => _chunksReceived;
  double get durationInSeconds => _dataSize / (sampleRate * channels * 2);
}

// Extension for number formatting
extension on double {
  String toFixed(int decimals) => toStringAsFixed(decimals);
}