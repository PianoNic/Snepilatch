import 'package:flutter/services.dart';
import 'package:flutter/material.dart';

class JavaScriptLoaderService {
  static final Map<String, String> _cache = {};

  // JavaScript asset paths
  static const String scraperScriptPath = 'assets/js/spotify-scraper.js';
  static const String actionsScriptPath = 'assets/js/spotify-actions.js';
  static const String playlistControllerScriptPath = 'assets/js/spotify-playlist-controller.js';
  static const String homepageScriptPath = 'assets/js/spotify-homepage.js';
  static const String adBlockerScriptPath = 'assets/js/spotify-adblocker.js';

  /// Load JavaScript content from an asset file
  static Future<String> loadJavaScript(String assetPath) async {
    // Check cache first
    if (_cache.containsKey(assetPath)) {
      debugPrint('📦 Loading JavaScript from cache: $assetPath');
      return _cache[assetPath]!;
    }

    try {
      debugPrint('📄 Loading JavaScript asset: $assetPath');
      final String content = await rootBundle.loadString(assetPath);
      _cache[assetPath] = content;
      debugPrint('✅ Successfully loaded: $assetPath');
      return content;
    } catch (e) {
      debugPrint('❌ Error loading JavaScript asset $assetPath: $e');
      return '';
    }
  }

  /// Load the scraper functions JavaScript
  static Future<String> loadScraperScript() async {
    return loadJavaScript(scraperScriptPath);
  }

  /// Load the action functions JavaScript
  static Future<String> loadActionsScript() async {
    return loadJavaScript(actionsScriptPath);
  }

  /// Load the PlaylistController JavaScript
  static Future<String> loadPlaylistControllerScript() async {
    return loadJavaScript(playlistControllerScriptPath);
  }

  /// Load the Homepage JavaScript
  static Future<String> loadHomepageScript() async {
    return loadJavaScript(homepageScriptPath);
  }

  /// Load the AdBlocker JavaScript
  static Future<String> loadAdBlockerScript() async {
    return loadJavaScript(adBlockerScriptPath);
  }

  /// Load all JavaScript files and return combined content
  static Future<String> loadAllScripts() async {
    final List<String> scripts = await Future.wait([
      loadScraperScript(),
      loadActionsScript(),
      loadPlaylistControllerScript(),
      loadHomepageScript(),
      loadAdBlockerScript(),
    ]);

    return scripts.where((script) => script.isNotEmpty).join('\n\n');
  }

  /// Get the content of all scripts as a single string for injection
  static Future<String> getScriptsForInjection() async {
    final scripts = await loadAllScripts();
    if (scripts.isEmpty) {
      debugPrint('⚠️ No JavaScript scripts loaded, using fallback inline scripts');
      // Return a minimal fallback script that defines empty functions
      return '''
        window.getPlayingInfo = function() { return JSON.stringify({}); };
        window.getUserInfo = function() { return JSON.stringify({}); };
        window.getSongs = function() { return JSON.stringify([]); };
        window.getSearchResults = function() { return JSON.stringify([]); };
        window.getHomepageSections = function() { return JSON.stringify([]); };
        console.warn('JavaScript files not loaded, using fallback functions');
      ''';
    }
    debugPrint('✅ All JavaScript scripts loaded successfully (${scripts.length} chars)');

    // Add a verification script to check if functions were added
    final verificationScript = '''
      ; // Ensure previous statement is terminated
      console.log('Injecting Spotify functions...');
      const functionCheck = {
        getPlayingInfo: typeof window.getPlayingInfo,
        getUserInfo: typeof window.getUserInfo,
        getSongs: typeof window.getSongs,
        getSearchResults: typeof window.getSearchResults,
        getHomepageSections: typeof window.getHomepageSections
      };
      console.log('Function status after injection:', JSON.stringify(functionCheck));
    ''';

    return scripts + verificationScript;
  }

  /// Clear the cache
  static void clearCache() {
    _cache.clear();
    debugPrint('🗑️ JavaScript cache cleared');
  }
}