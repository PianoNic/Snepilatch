import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import '../utils/logger.dart';

class WebViewService {
  InAppWebViewController? controller;

  InAppWebViewSettings getSettings() {
    String userAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36 OPR/122.0.0.0';

    return InAppWebViewSettings(
      userAgent: userAgent,
      javaScriptEnabled: true,
      mediaPlaybackRequiresUserGesture: false,
      allowsInlineMediaPlayback: true,
      iframeAllow: "camera; microphone; payment; encrypted-media;",
      iframeAllowFullscreen: true,
      useShouldOverrideUrlLoading: true,
      useHybridComposition: true,
      allowContentAccess: true,
      allowFileAccess: true,
      domStorageEnabled: true,
      databaseEnabled: true,
      clearSessionCache: false,
      thirdPartyCookiesEnabled: true,
      supportZoom: false,
      useWideViewPort: true,
      displayZoomControls: false,
      verticalScrollBarEnabled: true,
      horizontalScrollBarEnabled: true,
      transparentBackground: false,
      disableDefaultErrorPage: false,
    );
  }

  void setController(InAppWebViewController webController) {
    controller = webController;
    logInfo('WebView controller initialized', source: 'WebViewService');
  }

  Future<NavigationActionPolicy> shouldOverrideUrlLoading(
      InAppWebViewController controller, NavigationAction navigationAction) async {
    var uri = navigationAction.request.url;

    if (uri != null) {
      String url = uri.toString();
      logDebug('Navigation request to: $url', source: 'WebViewService');

      // Prevent redirects to app store or mobile app
      if (url.contains('apps.apple.com') ||
          url.contains('play.google.com') ||
          url.contains('spotify://')) {
        logWarning('Blocked navigation to app store/mobile: $url', source: 'WebViewService');
        return NavigationActionPolicy.CANCEL;
      }

      // Allow navigation to login page
      if (url.contains('accounts.spotify.com')) {
        logInfo('Allowing navigation to login page', source: 'WebViewService');
        return NavigationActionPolicy.ALLOW;
      }
    }

    logDebug('Allowing navigation', source: 'WebViewService');
    return NavigationActionPolicy.ALLOW;
  }

  Future<PermissionResponse?> onPermissionRequest(
      InAppWebViewController controller, PermissionRequest permissionRequest) async {
    logDebug('Permission requested: ${permissionRequest.resources}', source: 'WebViewService');

    // Always grant permission for DRM content
    return PermissionResponse(
      resources: permissionRequest.resources,
      action: PermissionResponseAction.GRANT
    );
  }

  Future<void> runJavascript(String source) async {
    try {
      if (controller != null) {
        await controller!.evaluateJavascript(source: source);
      }
    } catch (e) {
      logError('Error running JavaScript', source: 'WebViewService', error: e);
    }
  }

  Future<dynamic> runJavascriptWithResult(String source) async {
    try {
      if (controller != null) {
        return await controller!.evaluateJavascript(source: source);
      }
    } catch (e) {
      logError('Error running JavaScript with result', source: 'WebViewService', error: e);
      return null;
    }
    return null;
  }
}