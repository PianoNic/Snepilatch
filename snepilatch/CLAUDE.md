# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview
Snepilatch is a Flutter application that provides a custom interface for controlling Spotify playback through an embedded WebView. The app uses `flutter_inappwebview` to interact with Spotify's web player and scrapes the DOM to extract playback information and control the player.

## Key Commands

### Development
- `flutter run` - Run the app on a connected device or emulator
- `flutter build apk` - Build APK for Android
- `flutter build ios` - Build for iOS
- `flutter build windows` - Build for Windows desktop
- `flutter pub get` - Install dependencies
- `flutter clean` - Clean build artifacts

### Testing
- `flutter test` - Run all tests
- `flutter test test/widget_test.dart` - Run specific test file

### Code Quality
- `flutter analyze` - Run static analysis
- `dart format .` - Format all Dart files

## Architecture

### Core Components

1. **SpotifyController** (`lib/spotify_controller.dart`)
   - Central state management using ChangeNotifier
   - WebView control and JavaScript injection for DOM manipulation
   - Handles all Spotify interactions (play, pause, skip, search, like, etc.)
   - Scrapes current track info, user data, liked songs, and like status
   - Key selectors for Spotify controls:
     - Play/Pause: `[data-testid="control-button-playpause"]`
     - Skip Forward: `[data-testid="control-button-skip-forward"]`
     - Skip Back: `[data-testid="control-button-skip-back"]`
     - Shuffle: Button with aria-label containing "shuffle" (language-agnostic)
     - Repeat: `[data-testid="control-button-repeat"]`
     - Like: Button in now-playing widget with aria-checked attribute

2. **Main App Structure** (`lib/main.dart`)
   - MainScreen with bottom navigation (Home, Songs, Search, User)
   - Embedded InAppWebView for Spotify Web Player
   - Full-screen WebView overlay mode for login/browsing
   - Hidden 1x1 WebView for background operations

### Key Features
- Real-time playback info scraping (track, artist, album art)
- User authentication detection and profile info extraction
- Liked songs library browsing with lazy loading
- Search functionality with results parsing
- Playback controls (play/pause, next/previous, track selection)

### WebView Configuration
- Desktop user agents to avoid mobile app redirects
- Permission handling for DRM content (RESOURCE_PROTECTED_MEDIA_ID)
- URL filtering to prevent app store redirects
- JavaScript injection for DOM manipulation and data extraction

## Important Implementation Details

### State Management
- Uses Flutter's built-in ChangeNotifier pattern
- SpotifyController acts as a single source of truth
- AnimatedBuilder widgets subscribe to controller changes

### WebView Integration
- Periodic scraping (1-second intervals) for real-time updates
- JavaScript functions injected: `getPlayingInfo()`, `getUserInfo()`
- DOM selectors target Spotify's data-testid attributes
- Handles both visible and hidden WebView modes

### Platform Support
- Android and iOS: Full WebView support
- Windows/macOS/Linux: WebView components conditionally rendered
- Platform-specific user agents for optimal compatibility

## Dependencies
- `flutter_inappwebview: ^6.0.0` - WebView implementation
- `cupertino_icons: ^1.0.8` - iOS-style icons
- Flutter SDK: ^3.9.2

## Development Tips
- When updating Spotify control selectors, check the HTML structure provided by the user for current button classes and data-testid attributes
- The app relies on Spotify's web player DOM structure; changes to Spotify's UI may require selector updates
- Use the periodic scraping mechanism to detect state changes rather than relying on events
- Always test WebView functionality on actual devices as emulators may have limitations