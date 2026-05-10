import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

class SpotifyLoginModal extends StatelessWidget {
  final VoidCallback onDismissed;

  const SpotifyLoginModal({super.key, required this.onDismissed});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Log in to Spotify'),
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: () => Navigator.of(context).pop(),
        ),
        backgroundColor: colorScheme.surface,
      ),
      body: InAppWebView(
        initialUrlRequest:
            URLRequest(url: WebUri("https://accounts.spotify.com/login")),
        initialSettings: InAppWebViewSettings(
          userAgent:
              "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
          javaScriptEnabled: true,
        ),
        onLoadStop: (controller, url) async {
          final result = await controller.evaluateJavascript(
            source: "document.querySelector('[data-testid=\"status-logged-in\"]') !== null",
          );
          if (result == true && context.mounted) {
            Navigator.of(context).maybePop();
          }
        },
      ),
    );
  }

  static Future<void> show(BuildContext context,
      {required VoidCallback onDismissed}) {
    return Navigator.of(context)
        .push(
          MaterialPageRoute(
            fullscreenDialog: true,
            builder: (_) => SpotifyLoginModal(onDismissed: onDismissed),
          ),
        )
        .then((_) => onDismissed());
  }
}
