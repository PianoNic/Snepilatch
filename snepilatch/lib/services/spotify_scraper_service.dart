import 'dart:convert';
import 'package:flutter/material.dart';
import '../models/playback_state.dart';
import '../models/user.dart';
import '../models/song.dart';
import '../models/search_result.dart';

class SpotifyScraperService {
  // JavaScript functions are loaded from assets/js/spotify-scraper.js
  // Functions injected into window:
  // - window.getPlayingInfo() - Returns playback state as JSON
  // - window.getUserInfo() - Returns user info as JSON
  // - window.getSongs() - Returns song list as JSON
  // - window.getSearchResults() - Returns search results as JSON

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
      return jsonList.map((json) => SearchResult(
        title: json['title'] ?? '',
        artist: json['artist'] ?? '',
        index: json['index'] ?? 0,
      )).toList();
    } catch (e) {
      debugPrint('Error parsing search results: $e');
      return [];
    }
  }
}