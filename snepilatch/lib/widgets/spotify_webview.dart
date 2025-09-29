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

    debugPrint('🔄 [SpotifyWebViewWidget] Building WebView widget');

    // Keep WebView always in the widget tree to prevent recreation
    final webView = InAppWebView(
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
    );

    return ValueListenableBuilder<bool>(
      valueListenable: widget.spotifyController.showWebViewNotifier,
      builder: (context, showWebView, _) {
        return Stack(
          children: [
            // Always render WebView but position it off-screen when hidden
            Positioned(
              left: showWebView ? 0 : 500,
              top: 0,
              width: showWebView ? MediaQuery.of(context).size.width : 400,
              height: showWebView ? MediaQuery.of(context).size.height : 800,
              child: IgnorePointer(
                ignoring: !showWebView,
                child: ValueListenableBuilder<double>(
                  valueListenable: widget.spotifyController.webViewOpacity,
                  builder: (context, opacity, child) => Opacity(
                    opacity: showWebView ? 1.0 : opacity,
                    child: showWebView
                      ? Container(
                          color: Colors.white,
                          child: SafeArea(
                            child: Column(
                              children: [
                                _buildHeader(context),
                                Expanded(child: webView),
                              ],
                            ),
                          ),
                        )
                      : webView,
                  ),
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _buildHeader(BuildContext context) {
    return Material(
      color: Theme.of(context).colorScheme.surface,
      elevation: 4,
      child: SizedBox(
        height: 56,
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
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => widget.spotifyController.refreshWebView(),
            tooltip: 'Refresh page',
          ),
          TextButton(
            onPressed: () => widget.spotifyController.hideWebView(),
            child: const Text('Done'),
          ),
          ],
        ),
      ),
    );
  }
}