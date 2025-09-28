import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import 'package:package_info_plus/package_info_plus.dart';

class LoadingScreen extends StatefulWidget {
  final SpotifyController spotifyController;
  final VoidCallback? onComplete;

  const LoadingScreen({
    super.key,
    required this.spotifyController,
    this.onComplete,
  });

  @override
  State<LoadingScreen> createState() => _LoadingScreenState();
}

class _LoadingScreenState extends State<LoadingScreen> {
  String _currentStepText = '';
  bool _hasStartedInit = false;
  bool _showLoginButton = false;
  String _appVersion = '1.0.0';

  @override
  void initState() {
    super.initState();
    _loadVersion();
  }

  Future<void> _loadVersion() async {
    try {
      final info = await PackageInfo.fromPlatform();
      if (mounted) {
        setState(() {
          _appVersion = info.version;
        });
      }
    } catch (e) {
      // Keep default version
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_hasStartedInit) {
      _hasStartedInit = true;
      _initializeApp();
    }
  }

  @override
  void dispose() {
    super.dispose();
  }

  Future<void> _initializeApp() async {
    // Step 1: Show loading message
    if (mounted) {
      setState(() {
        _currentStepText = 'Initializing Snepilatch...';
      });
    }

    // Wait for app initialization
    await Future.delayed(const Duration(milliseconds: 500));
    if (!mounted) return;

    // Step 2: Start monitoring for Spotify data
    if (mounted) {
      setState(() {
        _currentStepText = 'Checking login status...';
      });
    }

    // Wait for WebView to initialize and quick login check (1-2 seconds)
    await Future.delayed(const Duration(milliseconds: 1500));
    if (!mounted) return;

    // Step 3: Check login status from controller
    // The controller's quick login check should have run by now
    final isLoggedIn = widget.spotifyController.isLoggedIn;

    if (!isLoggedIn) {
      // User is not logged in, show login button immediately
      setState(() {
        _currentStepText = 'Please log in to Spotify';
        _showLoginButton = true;
      });
      return;
    }

    // User is logged in, continue loading
    if (mounted) {
      setState(() {
        _currentStepText = 'Loading your music...';
      });
    }

    // Wait a bit more for data to load
    await Future.delayed(const Duration(seconds: 1));
    if (!mounted) return;

    // Check if we have user data
    final hasUserProfile = _hasUsefulData();

    if (!hasUserProfile) {
      // Still no data, might need login
      setState(() {
        _currentStepText = 'Please log in to Spotify';
        _showLoginButton = true;
      });
      return;
    }

    // If we have user profile data, complete initialization
    setState(() {
      _currentStepText = 'Ready!';
    });

    await Future.delayed(const Duration(milliseconds: 500));
    if (!mounted) return;

    // Call completion callback
    widget.onComplete?.call();
  }

  // Helper method to check if we have useful data from scraping
  bool _hasUsefulData() {
    final controller = widget.spotifyController;

    // Check primarily for user profile data to determine login status
    // User is considered logged in if we have username or profile image
    return controller.username != null ||
           controller.userProfileImage != null;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDarkMode = theme.brightness == Brightness.dark;

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
                    // App Logo
                    ClipRRect(
                      borderRadius: BorderRadius.circular(24),
                      child: Image.asset(
                        isDarkMode
                            ? 'assets/snepilatch_Logo.png'
                            : 'assets/snepilatch_Logo.png',
                        width: 100,
                        height: 100,
                        errorBuilder: (context, error, stackTrace) {
                          return Container(
                            width: 100,
                            height: 100,
                            decoration: BoxDecoration(
                              color: theme.colorScheme.primaryContainer,
                              borderRadius: BorderRadius.circular(24),
                            ),
                            child: Icon(
                              Icons.music_note,
                              size: 60,
                              color: theme.colorScheme.primary,
                            ),
                          );
                        },
                      ),
                    ),
                    const SizedBox(height: 48),

                    // App Name
                    Text(
                      'Snepilatch',
                      style: theme.textTheme.headlineMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                        color: theme.colorScheme.onSurface,
                        letterSpacing: 1.2,
                      ),
                    ),
                    const SizedBox(height: 8),

                    // Tagline
                    Text(
                      'Experience your music your way',
                      style: theme.textTheme.bodyLarge?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(height: 48),

                    // Loading indicator or login button
                    if (_showLoginButton) ...[
                      FilledButton.icon(
                        onPressed: () {
                          // Close loading screen and navigate to login
                          widget.onComplete?.call();
                          // Small delay to ensure main screen is loaded
                          Future.delayed(const Duration(milliseconds: 100), () {
                            widget.spotifyController.navigateToLogin();
                          });
                        },
                        icon: const Icon(Icons.login),
                        label: const Text('Login to Spotify'),
                        style: FilledButton.styleFrom(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 24,
                            vertical: 12,
                          ),
                        ),
                      ),
                    ] else ...[
                      SizedBox(
                        width: 32,
                        height: 32,
                        child: CircularProgressIndicator(
                          strokeWidth: 3,
                          valueColor: AlwaysStoppedAnimation<Color>(
                            theme.colorScheme.primary,
                          ),
                        ),
                      ),
                    ],
                    const SizedBox(height: 24),

                    // Status text
                    AnimatedSwitcher(
                      duration: const Duration(milliseconds: 300),
                      child: Text(
                        _currentStepText,
                        key: ValueKey(_currentStepText),
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ),

                    // Version at bottom
                    const SizedBox(height: 80),
                    Text(
                      'Version $_appVersion',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant
                            .withValues(alpha: 0.5),
                      ),
                    ),
                  ],
                ),
      ),
    );
  }
}