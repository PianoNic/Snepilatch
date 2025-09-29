import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';

class VideoFrameDisplay extends StatefulWidget {
  final SpotifyController spotifyController;
  final double opacity;
  final double blurAmount;

  const VideoFrameDisplay({
    super.key,
    required this.spotifyController,
    this.opacity = 0.4,
    this.blurAmount = 25.0,
  });

  @override
  State<VideoFrameDisplay> createState() => _VideoFrameDisplayState();
}

class _VideoFrameDisplayState extends State<VideoFrameDisplay> {
  Timer? _frameTimer;
  Uint8List? _currentFrame;
  bool _isCapturing = false;

  @override
  void initState() {
    super.initState();
    _startFrameCapture();
  }

  @override
  void dispose() {
    _stopFrameCapture();
    super.dispose();
  }

  void _startFrameCapture() async {
    if (_isCapturing) return;

    _isCapturing = true;

    // Start video capture in WebView
    try {
      await widget.spotifyController.runJavaScript(
        'window.startVideoCapture && window.startVideoCapture(20);'
      );
    } catch (e) {
      debugPrint('Error starting video capture: $e');
    }

    // Set up timer to fetch frames
    _frameTimer = Timer.periodic(const Duration(milliseconds: 50), (_) async {
      _fetchFrame();
    });
  }

  void _stopFrameCapture() async {
    _isCapturing = false;
    _frameTimer?.cancel();

    // Stop video capture in WebView
    try {
      await widget.spotifyController.runJavaScript(
        'window.stopVideoCapture && window.stopVideoCapture();'
      );
    } catch (e) {
      debugPrint('Error stopping video capture: $e');
    }
  }

  Future<void> _fetchFrame() async {
    if (!mounted || !_isCapturing) return;

    try {
      // Get video info with thumbnail
      final result = await widget.spotifyController.runJavaScriptWithResult(
        'window.getVideoInfo ? window.getVideoInfo() : "{}";'
      );

      if (result != null && result is String && result.isNotEmpty) {
        final videoInfo = jsonDecode(result);

        if (videoInfo['hasVideo'] == true && videoInfo['thumbnail'] != null) {
          final String thumbnail = videoInfo['thumbnail'];

          // Extract base64 data (remove data:image/jpeg;base64, prefix)
          if (thumbnail.contains('base64,')) {
            final base64Data = thumbnail.split('base64,')[1];
            final frameData = base64Decode(base64Data);

            if (mounted) {
              setState(() {
                _currentFrame = frameData;
              });
            }
          }
        } else {
          // No video, clear frame
          if (mounted && _currentFrame != null) {
            setState(() {
              _currentFrame = null;
            });
          }
        }
      }
    } catch (e) {
      debugPrint('Error fetching video frame: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_currentFrame == null) {
      return const SizedBox.shrink();
    }

    return Stack(
      fit: StackFit.expand,
      children: [
        // Display captured frame - fill entire screen
        Positioned.fill(
          child: Image.memory(
            _currentFrame!,
            fit: BoxFit.cover,
            gaplessPlayback: true,
            filterQuality: FilterQuality.medium,
            alignment: Alignment.center,
          ),
        ),
        // Apply blur
        Positioned.fill(
          child: BackdropFilter(
            filter: ui.ImageFilter.blur(
              sigmaX: widget.blurAmount,
              sigmaY: widget.blurAmount,
            ),
            child: Container(
              color: Colors.black.withValues(alpha: widget.opacity),
            ),
          ),
        ),
        // Gradient overlay for better text readability
        Positioned.fill(
          child: Container(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [
                  Colors.black.withValues(alpha: 0.2),
                  Colors.black.withValues(alpha: 0.4),
                  Colors.black.withValues(alpha: 0.6),
                ],
                stops: const [0.0, 0.5, 1.0],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

// Alternative: Static video thumbnail display
class VideoThumbnailDisplay extends StatefulWidget {
  final SpotifyController spotifyController;
  final double opacity;
  final double blurAmount;

  const VideoThumbnailDisplay({
    super.key,
    required this.spotifyController,
    this.opacity = 0.4,
    this.blurAmount = 25.0,
  });

  @override
  State<VideoThumbnailDisplay> createState() => _VideoThumbnailDisplayState();
}

class _VideoThumbnailDisplayState extends State<VideoThumbnailDisplay> {
  Uint8List? _thumbnail;
  Timer? _updateTimer;
  late final ValueNotifier<String?> _videoThumbnailNotifier;
  String? _lastThumbnailData;
  DateTime? _lastUpdateTime;

  @override
  void initState() {
    super.initState();
    _videoThumbnailNotifier = widget.spotifyController.store.videoThumbnail;
    _videoThumbnailNotifier.addListener(_onThumbnailChanged);
    _onThumbnailChanged(); // Initial load

    // Update every 100ms for balanced performance (10 FPS)
    // Only fetch if we actually have video data
    _updateTimer = Timer.periodic(const Duration(milliseconds: 100), (_) {
      if (_videoThumbnailNotifier.value != null || _thumbnail != null) {
        _fetchThumbnail();
      }
    });
  }

  @override
  void dispose() {
    _videoThumbnailNotifier.removeListener(_onThumbnailChanged);
    _updateTimer?.cancel();
    super.dispose();
  }

  void _onThumbnailChanged() {
    // Rate limit updates to prevent excessive rendering
    final now = DateTime.now();
    if (_lastUpdateTime != null && now.difference(_lastUpdateTime!).inMilliseconds < 33) {
      return; // Skip if less than 33ms since last update (30 FPS max)
    }

    final thumbnailData = _videoThumbnailNotifier.value;
    if (thumbnailData != null && thumbnailData.isNotEmpty && thumbnailData.contains('base64,')) {
      // Skip if it's the same data
      if (thumbnailData == _lastThumbnailData) {
        return;
      }

      try {
        final base64Data = thumbnailData.split('base64,')[1];
        final decodedData = base64Decode(base64Data);
        if (mounted) {
          setState(() {
            _thumbnail = decodedData;
            _lastThumbnailData = thumbnailData;
            _lastUpdateTime = now;
          });
        }
      } catch (e) {
        // Silently handle errors to reduce logs
      }
    }
  }

  Future<void> _fetchThumbnail() async {
    if (!mounted) return;

    try {
      // First try to get from store
      if (_videoThumbnailNotifier.value != null && _videoThumbnailNotifier.value!.isNotEmpty) {
        _onThumbnailChanged();
        return;
      }

      // If not in store, try direct capture
      final result = await widget.spotifyController.runJavaScriptWithResult(
        'window.captureVideoFrame ? window.captureVideoFrame() : null;'
      );

      if (result != null && result is String && result.contains('base64,')) {
        final base64Data = result.split('base64,')[1];
        final thumbnailData = base64Decode(base64Data);

        if (mounted) {
          setState(() {
            _thumbnail = thumbnailData;
          });
        }
      }
    } catch (e) {
      // Silently handle errors to reduce logs
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_thumbnail == null) {
      return const SizedBox.shrink();
    }

    return Stack(
      fit: StackFit.expand,
      children: [
        // Display thumbnail without animation to prevent flicker
        // Use cover to fill the entire screen, cropping if necessary
        Positioned.fill(
          child: Image.memory(
            _thumbnail!,
            fit: BoxFit.cover,
            gaplessPlayback: true,
            filterQuality: FilterQuality.medium,
            alignment: Alignment.center,
          ),
        ),
        // Apply blur
        Positioned.fill(
          child: BackdropFilter(
            filter: ui.ImageFilter.blur(
              sigmaX: widget.blurAmount,
              sigmaY: widget.blurAmount,
            ),
            child: Container(
              color: Colors.black.withValues(alpha: widget.opacity),
            ),
          ),
        ),
      ],
    );
  }
}