import 'dart:async';
import 'package:flutter/material.dart';
import 'webview_service.dart';
import 'javascript_loader_service.dart';

class InjectionMonitorService {
  final WebViewService _webViewService;
  Timer? _monitorTimer;
  bool _isMonitoring = false;
  int _reinjectionCount = 0;
  DateTime? _lastInjectionTime;

  InjectionMonitorService(this._webViewService);

  void startMonitoring() {
    if (_isMonitoring) return;

    debugPrint('🔍 [InjectionMonitor] Starting JavaScript injection monitoring...');
    _isMonitoring = true;
    _reinjectionCount = 0;

    // Check every 500ms
    _monitorTimer = Timer.periodic(const Duration(milliseconds: 500), (timer) async {
      await _checkAndReinjectIfNeeded();
    });
  }

  void stopMonitoring() {
    debugPrint('🛑 [InjectionMonitor] Stopping JavaScript injection monitoring');
    _isMonitoring = false;
    _monitorTimer?.cancel();
    _monitorTimer = null;
  }

  Future<bool> areFunctionsInjected() async {
    if (_webViewService.controller == null) return false;

    try {
      // Check if critical functions exist
      final result = await _webViewService.runJavascriptWithResult('''
        (function() {
          const functions = ['getPlayingInfo', 'getUserInfo', 'getSongs', 'spotifyPlay', 'spotifyPause'];
          const missing = [];

          for (const func of functions) {
            if (typeof window[func] !== 'function') {
              missing.push(func);
            }
          }

          if (missing.length === 0) {
            return 'all_present';
          } else {
            return 'missing:' + missing.join(',');
          }
        })()
      ''');

      if (result == 'all_present') {
        return true;
      } else if (result != null && result.toString().startsWith('missing:')) {
        final missing = result.toString().substring(8);
        debugPrint('⚠️ [InjectionMonitor] Missing functions: $missing');
        return false;
      }

      return false;
    } catch (e) {
      debugPrint('❌ [InjectionMonitor] Error checking functions: $e');
      return false;
    }
  }

  Future<void> _checkAndReinjectIfNeeded() async {
    if (_webViewService.controller == null) return;

    try {
      final injected = await areFunctionsInjected();

      if (!injected) {
        // Avoid reinjecting too frequently (wait at least 1 second between reinjections)
        if (_lastInjectionTime != null &&
            DateTime.now().difference(_lastInjectionTime!).inMilliseconds < 1000) {
          return;
        }

        debugPrint('🚨 [InjectionMonitor] Functions missing! Reinjecting JavaScript...');
        await reinjectJavaScript();
      }
    } catch (e) {
      debugPrint('❌ [InjectionMonitor] Error in monitoring check: $e');
    }
  }

  Future<void> reinjectJavaScript() async {
    try {
      _reinjectionCount++;
      _lastInjectionTime = DateTime.now();

      debugPrint('💉 [InjectionMonitor] Reinjecting JavaScript (attempt #$_reinjectionCount)...');

      // Load and inject all scripts
      final scripts = await JavaScriptLoaderService.getScriptsForInjection();
      await _webViewService.runJavascript(scripts);

      // Verify injection was successful
      await Future.delayed(const Duration(milliseconds: 100));
      final success = await areFunctionsInjected();

      if (success) {
        debugPrint('✅ [InjectionMonitor] JavaScript successfully reinjected!');
      } else {
        debugPrint('⚠️ [InjectionMonitor] Reinjection may have failed, will retry...');
      }
    } catch (e) {
      debugPrint('❌ [InjectionMonitor] Error reinjecting JavaScript: $e');
    }
  }

  void dispose() {
    stopMonitoring();
  }

  // Statistics
  int get reinjectionCount => _reinjectionCount;
  bool get isMonitoring => _isMonitoring;
  DateTime? get lastInjectionTime => _lastInjectionTime;
}