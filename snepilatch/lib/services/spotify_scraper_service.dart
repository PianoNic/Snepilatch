import 'dart:convert';
import 'package:flutter/material.dart';
import '../models/playback_state.dart';
import '../models/user.dart';
import '../models/song.dart';
import '../models/search_result.dart';
import '../models/homepage_item.dart';
import '../models/homepage_shortcut.dart';

class SpotifyScraperService {
  // JavaScript functions are loaded from assets/js/spotify-scraper.js and spotify-homepage.js
  // Functions injected into window:
  // - window.getPlayingInfo() - Returns playback state as JSON
  // - window.getUserInfo() - Returns user info as JSON
  // - window.getSongs() - Returns song list as JSON
  // - window.getSearchResults() - Returns search results as JSON
  // - window.getHomepageSections() - Returns homepage sections as JSON
  // - window.getHomepageShortcuts() - Returns homepage shortcuts as JSON

  // Parse playback info from JavaScript response using proper JSON
  static PlaybackState? parsePlaybackInfo(String jsonString) {
    try {
      return PlaybackState.fromJsonString(jsonString);
    } catch (e) {
      debugPrint('Error parsing playback info: $e');
      return null;
    }
  }

  // Parse user info from JavaScript response using proper JSON
  static User? parseUserInfo(String jsonString) {
    try {
      return User.fromJsonString(jsonString);
    } catch (e) {
      debugPrint('Error parsing user info: $e');
      return null;
    }
  }

  // Parse songs from JavaScript response using proper JSON
  static List<Song> parseSongs(String jsonString) {
    try {
      return Song.fromJsonList(jsonString);
    } catch (e) {
      debugPrint('Error parsing songs: $e');
      return [];
    }
  }

  // Parse search results from JavaScript response using proper JSON
  static List<SearchResult> parseSearchResults(String jsonString) {
    try {
      final List<dynamic> jsonList = jsonDecode(jsonString);
      return jsonList.map((json) {
        // Convert low-quality image to high-quality like the Song model does
        String? imageUrl = json['imageUrl']?.toString() ?? json['image']?.toString();
        if (imageUrl != null && imageUrl.contains('ab67616d00004851')) {
          imageUrl = imageUrl.replaceAll('ab67616d00004851', 'ab67616d00001e02');
        }

        return SearchResult(
          title: json['title'] ?? '',
          artist: json['artist'] ?? '',
          album: json['album'],
          imageUrl: imageUrl,
          duration: json['duration']?.toString(),
          index: json['index'] ?? 0,
        );
      }).toList();
    } catch (e) {
      debugPrint('Error parsing search results: $e');
      return [];
    }
  }

  // Parse homepage sections from JavaScript response using proper JSON
  static List<HomepageSection> parseHomepageSections(String jsonString) {
    try {
      final List<dynamic> jsonList = jsonDecode(jsonString);
      return jsonList.map((json) => HomepageSection.fromJson(json)).toList();
    } catch (e) {
      debugPrint('Error parsing homepage sections: $e');
      return [];
    }
  }

  // Parse homepage shortcuts from JavaScript response using proper JSON
  static List<HomepageShortcut> parseHomepageShortcuts(String jsonString) {
    try {
      final List<dynamic> jsonList = jsonDecode(jsonString);
      return jsonList.map((json) => HomepageShortcut.fromJson(json)).toList();
    } catch (e) {
      debugPrint('Error parsing homepage shortcuts: $e');
      return [];
    }
  }
}