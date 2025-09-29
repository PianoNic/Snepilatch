import 'package:just_audio/just_audio.dart';
import 'dart:async';

class WebViewStreamAudioSource extends StreamAudioSource {
  final Stream<List<int>> _audioStream;

  WebViewStreamAudioSource(this._audioStream);

  @override
  Future<StreamAudioResponse> request([int? start, int? end]) async {
    return StreamAudioResponse(
      sourceLength: null,
      contentLength: null,
      offset: start ?? 0,
      stream: _audioStream,
      contentType: 'audio/webm',
    );
  }
}