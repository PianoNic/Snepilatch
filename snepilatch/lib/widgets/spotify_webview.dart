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

class _SpotifyWebViewWidgetState extends State<SpotifyWebViewWidget> {
  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid && !Platform.isIOS) {
      return const SizedBox.shrink();
    }

    // Listen only to showWebView changes
    return ValueListenableBuilder<bool>(
      valueListenable: widget.spotifyController.showWebViewNotifier,
      builder: (context, showWebView, child) {
        return Positioned(
          left: showWebView ? 0 : 0,
          top: showWebView ? 0 : 0,
          width: showWebView ? MediaQuery.of(context).size.width : 1,
          height: showWebView ? MediaQuery.of(context).size.height : 1,
          child: showWebView
            ? Container(
                color: Colors.white,
                child: SafeArea(
                  child: Column(
                    children: [
                      _buildHeader(context),
                      Expanded(
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
                            debugPrint('Console: ${consoleMessage.message}');
                          },
                        ),
                      ),
                    ],
                  ),
                ),
              )
            : SizedBox(
                width: 1,
                height: 1,
                child: InAppWebView(
                  key: const Key('spotify_webview_hidden'),
                  initialUrlRequest: URLRequest(
                    url: WebUri('https://open.spotify.com')
                  ),
                  initialSettings: widget.spotifyController.getWebViewSettings(),
                  onWebViewCreated: widget.spotifyController.onWebViewCreated,
                  onLoadStop: widget.spotifyController.onLoadStop,
                  shouldOverrideUrlLoading: widget.spotifyController.shouldOverrideUrlLoading,
                  onPermissionRequest: widget.spotifyController.onPermissionRequest,
                ),
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