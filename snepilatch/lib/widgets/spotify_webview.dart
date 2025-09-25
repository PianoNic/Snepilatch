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

    // Listen only to showWebView changes
    return ValueListenableBuilder<bool>(
      valueListenable: widget.spotifyController.showWebViewNotifier,
      builder: (context, showWebView, child) {
        return Stack(
          children: [
            // Hidden WebView for background operations
            if (!showWebView)
              Positioned(
                left: 0,
                top: 0,
                width: 1,
                height: 1,
                child: IgnorePointer(
                  child: Opacity(
                    opacity: 0,
                    child: child!,
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
          if (widget.spotifyController.showWebView) {
            debugPrint('Console: ${consoleMessage.message}');
          }
        },
      ),
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