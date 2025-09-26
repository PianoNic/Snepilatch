import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';

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

  @override
  void initState() {
    super.initState();
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
    await Future.delayed(const Duration(seconds: 1));
    if (!mounted) return;

    // Step 2: Start monitoring for Spotify data
    if (mounted) {
      setState(() {
        _currentStepText = 'Connecting to Spotify...';
      });
    }

    // Wait for WebView to initialize and scraping to start (2-3 seconds based on logs)
    await Future.delayed(const Duration(seconds: 3));
    if (!mounted) return;

    if (mounted) {
      setState(() {
        _currentStepText = 'Loading your music...';
      });
    }

    // Step 3: Monitor for useful data for up to 30 seconds
    const maxWaitTime = 30; // seconds
    const checkInterval = 500; // milliseconds
    final maxChecks = (maxWaitTime * 1000) ~/ checkInterval;

    bool hasUsefulData = false;
    int checks = 0;

    while (checks < maxChecks && mounted && !hasUsefulData) {
      // Check if we have any useful data from scraping
      hasUsefulData = _hasUsefulData();

      if (!hasUsefulData) {
        await Future.delayed(const Duration(milliseconds: checkInterval));
        checks++;
      }
    }

    if (!mounted) return;

    // Step 4: After waiting, check what we have
    final hasUserProfile = _hasUsefulData();

    // If no user profile data after 30 seconds, assume user needs to log in
    if (!hasUserProfile) {
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
                      'Version 1.0.0',
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