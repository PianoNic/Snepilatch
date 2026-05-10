import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:snepilatch_v2/services/spotify_config.dart';

class SpotifyAuthModal extends StatelessWidget {
  final Uri authUrl;
  final void Function(Uri responseUri) onAuthComplete;

  const SpotifyAuthModal({
    super.key,
    required this.authUrl,
    required this.onAuthComplete,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Authorize Spotify'),
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: () => Navigator.of(context).maybePop(),
        ),
        backgroundColor: colorScheme.surface,
      ),
      body: InAppWebView(
        initialUrlRequest: URLRequest(url: WebUri.uri(authUrl)),
        initialSettings: InAppWebViewSettings(
          userAgent:
              "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
          javaScriptEnabled: true,
        ),
        shouldOverrideUrlLoading: (controller, navigationAction) async {
          final url = navigationAction.request.url;
          if (url != null &&
              url.toString().startsWith(SpotifyConfig.redirectUri)) {
            onAuthComplete(url);
            if (context.mounted) {
              Navigator.of(context).maybePop();
            }
            return NavigationActionPolicy.CANCEL;
          }
          return NavigationActionPolicy.ALLOW;
        },
      ),
    );
  }

  static Future<void> show(
    BuildContext context, {
    required Uri authUrl,
    required void Function(Uri responseUri) onAuthComplete,
  }) {
    return Navigator.of(context).push(
      MaterialPageRoute(
        fullscreenDialog: true,
        builder: (_) => SpotifyAuthModal(
          authUrl: authUrl,
          onAuthComplete: onAuthComplete,
        ),
      ),
    );
  }
}
