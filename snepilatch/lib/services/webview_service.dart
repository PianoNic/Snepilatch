import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

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
  }

  Future<NavigationActionPolicy> shouldOverrideUrlLoading(
      InAppWebViewController controller, NavigationAction navigationAction) async {
    var uri = navigationAction.request.url;

    if (uri != null) {
      String url = uri.toString();
      // Prevent redirects to app store or mobile app
      if (url.contains('apps.apple.com') ||
          url.contains('play.google.com') ||
          url.contains('spotify://')) {
        debugPrint('Blocked navigation to: $url');
        return NavigationActionPolicy.CANCEL;
      }
    }

    return NavigationActionPolicy.ALLOW;
  }

  Future<PermissionResponse?> onPermissionRequest(
      InAppWebViewController controller, PermissionRequest permissionRequest) async {
    debugPrint('Permission requested: ${permissionRequest.resources}');

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
      debugPrint('Error running JavaScript: $e');
    }
  }

  Future<dynamic> runJavascriptWithResult(String source) async {
    try {
      if (controller != null) {
        return await controller!.evaluateJavascript(source: source);
      }
    } catch (e) {
      debugPrint('Error running JavaScript with result: $e');
      return null;
    }
    return null;
  }
}