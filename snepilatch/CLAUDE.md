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

## Project Structure

```
lib/
├── controllers/          # State management and business logic
│   └── spotify_controller.dart
├── models/              # Data models and entities
│   ├── playback_state.dart
│   ├── search_result.dart
│   ├── song.dart
│   └── user.dart
├── screens/             # Full page screens
│   ├── home_page.dart
│   ├── main_screen.dart
│   ├── search_page.dart
│   ├── songs_page.dart
│   └── user_page.dart
├── services/            # Business logic and external integrations
│   ├── spotify_actions_service.dart
│   ├── spotify_scraper_service.dart
│   └── webview_service.dart
├── widgets/             # Reusable UI components
│   ├── expanded_player.dart
│   ├── mini_player.dart
│   └── spotify_webview.dart
└── main.dart           # App entry point
```

## Architecture

### Core Components

1. **SpotifyController** (`lib/controllers/spotify_controller.dart`)
   - Central state management using ChangeNotifier
   - Coordinates between services and UI
   - Manages playback state, user info, and songs list
   - Provides ValueNotifiers for specific UI updates

2. **Services Layer**
   - **WebViewService**: Manages WebView configuration and JavaScript execution
   - **SpotifyScraperService**: Contains JavaScript injection code and parsing logic
   - **SpotifyActionsService**: Contains all Spotify control JavaScript scripts

3. **Models**
   - **PlaybackState**: Current track, play status, shuffle/repeat modes
   - **User**: User authentication and profile information
   - **Song**: Individual song data structure
   - **SearchResult**: Search result data structure

4. **Main App Structure**
   - **MainScreen**: Navigation container with bottom nav bar
   - **HomePage**: Playback controls and current track display
   - **SongsPage**: Liked songs list with lazy loading
   - **SearchPage**: Search interface for Spotify content
   - **UserPage**: User profile and authentication

### Key Features
- Real-time playback info scraping (track, artist, album art)
- User authentication detection and profile info extraction
- Liked songs library browsing with lazy loading
- Search functionality with results parsing
- Playback controls (play/pause, next/previous, shuffle, repeat, like)
- Expandable player with full-screen playback controls
- Mini player for quick access while browsing

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
- ValueNotifiers for optimized UI updates (showWebView, isLoggedIn)

### WebView Integration
- Periodic scraping (1-second intervals) for real-time updates
- JavaScript functions injected: `getPlayingInfo()`, `getUserInfo()`
- DOM selectors target Spotify's data-testid attributes
- Handles both visible and hidden WebView modes

### Key Spotify DOM Selectors
- Play/Pause: `[data-testid="control-button-playpause"]`
- Skip Forward: `[data-testid="control-button-skip-forward"]`
- Skip Back: `[data-testid="control-button-skip-back"]`
- Shuffle: Button with aria-label containing "shuffle" (language-agnostic)
- Repeat: `[data-testid="control-button-repeat"]`
- Like: Button in now-playing widget with aria-checked attribute
- Track Info: `[data-testid="context-item-info-title"]`
- Artist Info: `[data-testid="context-item-info-artist"]`

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
- Models use strongly typed data structures - update model classes when adding new fields
- Services contain reusable logic - keep UI-specific code in widgets/screens
- Use the existing folder structure when adding new features