import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import '../controllers/spotify_controller.dart';

class SpotifyWebViewWidget extends StatefulWidget {
  final SpotifyController spotifyController;
  const SpotifyWebViewWidget({super.key, required this.spotifyController});

  @override
  State<SpotifyWebViewWidget> createState() => _SpotifyWebViewWidgetState();
}

class _SpotifyWebViewWidgetState extends State<SpotifyWebViewWidget> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);

    if (!Platform.isAndroid && !Platform.isIOS) {
      return const SizedBox.shrink();
    }

    // Listen to both showWebView and debugWebViewVisible changes
    return AnimatedBuilder(
      animation: widget.spotifyController,
      builder: (context, _) {
        final showWebView = widget.spotifyController.showWebView;
        final debugVisible = widget.spotifyController.debugWebViewVisible;

        return ValueListenableBuilder<bool>(
          valueListenable: widget.spotifyController.showWebViewNotifier,
          builder: (context, _, child) {
            return Stack(
              children: [
                // Hidden WebView for background operations
                // Make it larger so scrolling works properly
                if (!showWebView && !debugVisible)
                  Positioned(
                    left: -1000, // Move off-screen instead of making tiny
                    top: 0,
                    width: 400,
                    height: 800,
                    child: IgnorePointer(
                      child: Opacity(
                        opacity: 0,
                        child: child!,
                      ),
                    ),
                  ),
                // Debug view - show WebView continuously when debug mode is enabled
                if (!showWebView && debugVisible)
                  Positioned(
                    bottom: 100,
                    left: 20,
                    right: 20,
                    height: MediaQuery.of(context).size.height * 0.3,
                    child: Container(
                      decoration: BoxDecoration(
                        border: Border.all(color: Colors.green, width: 3),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(10),
                        child: Stack(
                          children: [
                            child!,
                            Positioned(
                              top: 0,
                              left: 0,
                              right: 0,
                              child: Container(
                                color: Colors.green.withOpacity(0.8),
                                padding: const EdgeInsets.all(4),
                                child: const Text(
                                  'DEBUG: WebView',
                                  style: TextStyle(
                                    color: Colors.white,
                                    fontWeight: FontWeight.bold,
                                    fontSize: 12,
                                  ),
                                  textAlign: TextAlign.center,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                // Visible WebView when showing
                if (showWebView)
                  Positioned.fill(
                    child: Container(
                      color: Colors.white,
                      child: SafeArea(
                        child: Column(
                          children: [
                            _buildHeader(context),
                            Expanded(
                              child: child!,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
              ],
            );
          },
          child: InAppWebView(
            key: const Key('spotify_webview'),
            initialUrlRequest: URLRequest(
              url: WebUri('https://open.spotify.com')
            ),
            initialSettings: widget.spotifyController.getWebViewSettings(),
            onWebViewCreated: widget.spotifyController.onWebViewCreated,
            onLoadStop: widget.spotifyController.onLoadStop,
            shouldOverrideUrlLoading: widget.spotifyController.shouldOverrideUrlLoading,
            onPermissionRequest: widget.spotifyController.onPermissionRequest,
            onConsoleMessage: (controller, consoleMessage) {
              if (widget.spotifyController.showWebView || widget.spotifyController.debugWebViewVisible) {
                debugPrint('Console: ${consoleMessage.message}');
              }
            },
          ),
        );
      },
    );
  }

  Widget _buildHeader(BuildContext context) {
    return Container(
      height: 56,
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.1),
            blurRadius: 4,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Row(
        children: [
          IconButton(
            icon: const Icon(Icons.close),
            onPressed: () => widget.spotifyController.hideWebView(),
          ),
          ValueListenableBuilder<bool>(
            valueListenable: widget.spotifyController.isLoggedInNotifier,
            builder: (context, isLoggedIn, child) {
              return Text(
                isLoggedIn ? 'Spotify Web' : 'Login to Spotify',
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w500,
                ),
              );
            },
          ),
          const Spacer(),
          TextButton(
            onPressed: () => widget.spotifyController.hideWebView(),
            child: const Text('Done'),
          ),
        ],
      ),
    );
  }
}