import 'package:flutter/material.dart';

class SongInfo {
  final String? title;
  final String? artist;
  final String? imageUrl;
  final String? uri;
  final Color? dominantColor;
  final bool? liked;

  SongInfo({
    this.title,
    this.artist,
    this.imageUrl,
    this.uri,
    this.dominantColor,
    this.liked,
  });

  /// Create SongInfo from WebSocket data
  factory SongInfo.fromWebSocket(Map<String, dynamic> data) {
    return SongInfo(
      title: data['title'] as String?,
      artist: data['artist'] as String?,
      imageUrl: data['cover'] as String?,
      uri: data['uri'] as String?,
      liked: data['liked'] as bool?,
    );
  }

  /// Create a copy with a dominant color
  SongInfo copyWithDominantColor(Color color) {
    return SongInfo(
      title: title,
      artist: artist,
      imageUrl: imageUrl,
      uri: uri,
      dominantColor: color,
      liked: liked,
    );
  }

  /// Create a copy with updated fields
  SongInfo copyWith({
    String? title,
    String? artist,
    String? imageUrl,
    String? uri,
    Color? dominantColor,
    bool? liked,
  }) {
    return SongInfo(
      title: title ?? this.title,
      artist: artist ?? this.artist,
      imageUrl: imageUrl ?? this.imageUrl,
      uri: uri ?? this.uri,
      dominantColor: dominantColor ?? this.dominantColor,
      liked: liked ?? this.liked,
    );
  }

  /// Convert to Map for serialization
  Map<String, dynamic> toMap() {
    return {
      'title': title,
      'artist': artist,
      'cover': imageUrl,
      'uri': uri,
      'liked': liked,
    };
  }
}
