import 'dart:io';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:flutter/material.dart';

class WebViewService {
  InAppWebViewController? controller;

  InAppWebViewSettings getSettings() {
    String userAgent = '';

    if (Platform.isAndroid) {
      userAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
    } else if (Platform.isIOS) {
      userAgent = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15';
    } else {
      userAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
    }

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

  Future<void> evaluateJavascript(String source) async {
    await controller?.evaluateJavascript(source: source);
  }

  Future<dynamic> evaluateJavascriptWithResult(String source) async {
    return await controller?.evaluateJavascript(source: source);
  }

  Future<void> loadUrl(String url) async {
    await controller?.loadUrl(
      urlRequest: URLRequest(url: WebUri(url))
    );
  }
}