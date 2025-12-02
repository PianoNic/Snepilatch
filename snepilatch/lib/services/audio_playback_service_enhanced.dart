import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import 'package:audio_service/audio_service.dart';
import 'package:flutter_sound/flutter_sound.dart';
import 'package:just_audio/just_audio.dart' as just_audio;
import 'dart:convert';

/// Enhanced audio service that registers with Android's AudioManager
/// This ensures Wavelet and other audio apps can detect and process our audio
class AudioPlaybackServiceEnhanced extends BaseAudioHandler {
  static AudioPlaybackServiceEnhanced? _instance;
  FlutterSoundPlayer? _soundPlayer;
  final just_audio.AudioPlayer _audioPlayer = just_audio.AudioPlayer(); // For audio session management

  // Audio format from JavaScript
  int _sampleRate = 44100;
  int _bitDepth = 16;
  int _channels = 1; // Mono

  // Status tracking
  bool _isPlaying = false;
  bool _isInitialized = false;
  int _totalBytesReceived = 0;
  int _chunksReceived = 0;

  // Buffer management
  final List<Uint8List> _audioBuffer = [];
  Timer? _playbackTimer;
  bool _isProcessing = false;

  // Singleton
  static Future<AudioPlaybackServiceEnhanced> getInstance() async {
    if (_instance == null) {
      _instance = await AudioService.init(
        builder: () => AudioPlaybackServiceEnhanced._(),
        config: const AudioServiceConfig(
          androidNotificationChannelId: 'com.example.snepilatch.channel.audio',
          androidNotificationChannelName: 'Snepilatch Audio',
          androidNotificationOngoing: false,  // Changed to false to work with androidStopForegroundOnPause: false
          androidShowNotificationBadge: false,
          androidNotificationIcon: 'drawable/ic_notification',
          androidStopForegroundOnPause: false,  // Keep service running when paused for Wavelet
        ),
      );
      await _instance!._initialize();
    }
    return _instance!;
  }

  AudioPlaybackServiceEnhanced._();

  Future<void> _initialize() async {
    try {
      // Initialize audio session for system registration
      // Using a silent audio source to register with AudioManager
      await _audioPlayer.setUrl(
        'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
        initialPosition: Duration.zero,
      );
      await _audioPlayer.setVolume(0.0); // Mute the dummy audio

      // Set media item to show in system
      final mediaItem = MediaItem(
        id: 'spotify_stream',
        album: 'Spotify',
        title: 'Streaming Audio',
        artist: 'Snepilatch',
        duration: const Duration(hours: 1),
        artUri: Uri.parse('https://open.spotify.com/favicon.ico'),
      );

      // Add media item to queue
      queue.add([mediaItem]);
      playbackState.add(PlaybackState(
        controls: [
          MediaControl.play,
          MediaControl.pause,
          MediaControl.stop,
        ],
        systemActions: const {
          MediaAction.play,
          MediaAction.pause,
          MediaAction.stop,
          MediaAction.seek,
        },
        androidCompactActionIndices: const [0, 1],
        processingState: AudioProcessingState.ready,
        playing: false,
        updatePosition: Duration.zero,
        bufferedPosition: Duration.zero,
        speed: 1.0,
        queueIndex: 0,
      ));

      // Initialize flutter_sound for PCM playback
      _soundPlayer = FlutterSoundPlayer();
      await _soundPlayer!.openPlayer();

      // Start PCM feed with specified format
      await _soundPlayer!.startPlayerFromStream(
        codec: Codec.pcm16,
        numChannels: _channels,
        sampleRate: _sampleRate,
        bufferSize: 8192,
        interleaved: true,
      );

      _isInitialized = true;
      _isPlaying = true;

      debugPrint('🎵 Enhanced audio service initialized with AudioManager registration');
      debugPrint('✅ App should now be visible to Wavelet');
      debugPrint('📊 Format: ${_sampleRate}Hz, ${_bitDepth}-bit, ${_channels == 1 ? "Mono" : "Stereo"}');

      // Start audio session
      await play();

      // Start processing buffer periodically
      _startBufferProcessor();
    } catch (e) {
      debugPrint('❌ Failed to initialize enhanced audio service: $e');
    }
  }

  void _startBufferProcessor() {
    // Process buffer every 10ms for low latency
    _playbackTimer = Timer.periodic(const Duration(milliseconds: 10), (_) {
      _processBuffer();
    });
  }

  void _processBuffer() async {
    if (_isProcessing || _audioBuffer.isEmpty || !_isInitialized) return;

    _isProcessing = true;

    try {
      // Process up to 10 chunks at once
      int chunksToProcess = _audioBuffer.length > 10 ? 10 : _audioBuffer.length;

      for (int i = 0; i < chunksToProcess; i++) {
        if (_audioBuffer.isEmpty) break;

        final chunk = _audioBuffer.removeAt(0);

        // Feed PCM data to player
        if (_soundPlayer != null && _isPlaying && _soundPlayer!.uint8ListSink != null) {
          _soundPlayer!.uint8ListSink!.add(chunk);
        }
      }
    } catch (e) {
      debugPrint('⚠️ Error processing PCM buffer: $e');
    } finally {
      _isProcessing = false;
    }
  }

  /// Handle format information from JavaScript
  void handleFormatInfo(String jsonData) {
    try {
      final format = json.decode(jsonData);
      if (format['type'] == 'format') {
        _sampleRate = format['sampleRate'] ?? 44100;
        _bitDepth = format['bitDepth'] ?? 16;
        _channels = format['channels'] ?? 1;

        debugPrint('📋 Received PCM format info:');
        debugPrint('   Sample Rate: $_sampleRate Hz');
        debugPrint('   Bit Depth: $_bitDepth bits');
        debugPrint('   Channels: $_channels');

        // Restart player with new format if needed
        if (_isInitialized) {
          _restartWithNewFormat();
        }
      }
    } catch (e) {
      debugPrint('⚠️ Error parsing format info: $e');
    }
  }

  Future<void> _restartWithNewFormat() async {
    try {
      // Stop current playback
      if (_soundPlayer != null && _soundPlayer!.isPlaying) {
        await _soundPlayer!.stopPlayer();
      }

      // Restart with new format
      await _soundPlayer!.startPlayerFromStream(
        codec: Codec.pcm16,
        numChannels: _channels,
        sampleRate: _sampleRate,
        bufferSize: 8192,
        interleaved: true,
      );

      _isPlaying = true;
      debugPrint('🔄 PCM player restarted with new format');
    } catch (e) {
      debugPrint('❌ Error restarting with new format: $e');
    }
  }

  /// Add PCM audio data from WebSocket
  void addAudioData(Uint8List data) {
    if (!_isInitialized) return;

    // Check if this is format info (starts with '{')
    if (data.isNotEmpty && data[0] == 123) { // ASCII for '{'
      try {
        final jsonStr = utf8.decode(data);
        if (jsonStr.startsWith('{')) {
          handleFormatInfo(jsonStr);
          return;
        }
      } catch (_) {
        // Not JSON, treat as audio data
      }
    }

    _totalBytesReceived += data.length;
    _chunksReceived++;

    // Add to buffer for processing
    _audioBuffer.add(data);

    // Log progress
    if (_chunksReceived == 1) {
      debugPrint('🎉 First PCM packet received!');
      debugPrint('📊 Packet size: ${data.length} bytes');
    }

    if (_chunksReceived % 100 == 0) {
      debugPrint('🎵 PCM chunks: $_chunksReceived, Total: ${(_totalBytesReceived / 1024).toFixed(1)}KB');
      debugPrint('📦 Buffer size: ${_audioBuffer.length} chunks waiting');
    }

    // Warn if buffer is getting too large (potential playback issue)
    if (_audioBuffer.length > 100) {
      debugPrint('⚠️ Large buffer detected: ${_audioBuffer.length} chunks. Possible playback lag.');
    }
  }

  @override
  Future<void> play() async {
    if (!_isPlaying) {
      // Start just_audio player to register audio session
      await _audioPlayer.play();

      // Resume PCM playback
      if (_soundPlayer != null) {
        await _soundPlayer!.resumePlayer();
      }

      _isPlaying = true;

      // Update playback state for system
      playbackState.add(playbackState.value.copyWith(
        playing: true,
        processingState: AudioProcessingState.ready,
      ));

      debugPrint('▶️ Audio playback started - visible to Wavelet');
    }
  }

  @override
  Future<void> pause() async {
    if (_isPlaying) {
      // Pause just_audio
      await _audioPlayer.pause();

      // Pause PCM playback
      if (_soundPlayer != null) {
        await _soundPlayer!.pausePlayer();
      }

      _isPlaying = false;

      // Update playback state
      playbackState.add(playbackState.value.copyWith(
        playing: false,
      ));

      debugPrint('⏸️ Audio playback paused');
    }
  }

  @override
  Future<void> stop() async {
    _isPlaying = false;
    _playbackTimer?.cancel();

    // Stop just_audio
    await _audioPlayer.stop();

    // Stop PCM player
    if (_soundPlayer != null) {
      try {
        await _soundPlayer!.stopPlayer();
        await _soundPlayer!.closePlayer();
      } catch (e) {
        debugPrint('⚠️ Error stopping player: $e');
      }
    }

    _audioBuffer.clear();

    // Update playback state
    playbackState.add(playbackState.value.copyWith(
      playing: false,
      processingState: AudioProcessingState.idle,
    ));

    debugPrint('⏹️ Audio playback stopped');
  }

  void dispose() {
    stop();
    _audioPlayer.dispose();
    _soundPlayer = null;
    _instance = null;
  }

  // Status getters
  bool get isPlaying => _isPlaying;
  int get bufferSize => _audioBuffer.length;
  int get totalBytesReceived => _totalBytesReceived;
  int get chunksReceived => _chunksReceived;
}

// Extension for number formatting
extension on double {
  String toFixed(int decimals) => toStringAsFixed(decimals);
}