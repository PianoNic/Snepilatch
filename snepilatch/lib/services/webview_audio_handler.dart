import 'package:audio_service/audio_service.dart';
import 'package:just_audio/just_audio.dart';
import 'dart:async';
import 'stream_audio_source.dart';

class WebViewAudioHandler extends BaseAudioHandler {
  final AudioPlayer _player = AudioPlayer();
  final StreamController<List<int>> _audioStreamController =
      StreamController<List<int>>.broadcast();

  WebViewAudioHandler() {
    _initialize();
  }

  Future<void> _initialize() async {
    final audioSource = WebViewStreamAudioSource(_audioStreamController.stream);
    await _player.setAudioSource(audioSource);

    _player.playbackEventStream.listen((event) {
      playbackState.add(playbackState.value.copyWith(
        playing: _player.playing,
        processingState: _player.processingState == ProcessingState.ready
            ? AudioProcessingState.ready
            : AudioProcessingState.loading,
        controls: [MediaControl.pause, MediaControl.play, MediaControl.stop],
      ));
    });

    mediaItem.add(const MediaItem(
      id: 'webview_audio_stream',
      title: 'WebView Audio',
      artist: 'Browser Content',
    ));
  }

  void addAudioData(List<int> data) {
    if (!_audioStreamController.isClosed) {
      _audioStreamController.add(data);
    }
  }

  @override
  Future<void> play() => _player.play();

  @override
  Future<void> pause() => _player.pause();

  @override
  Future<void> stop() async {
    await _player.stop();
    await _audioStreamController.close();
    await super.stop();
  }
}