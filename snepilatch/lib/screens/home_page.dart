import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../widgets/homepage_section_widget.dart';

class HomePage extends StatelessWidget {
  final SpotifyController spotifyController;
  const HomePage({super.key, required this.spotifyController});

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
        animation: spotifyController,
        builder: (context, child) {
          final homepageSections = spotifyController.homepageSections;
          final isLoggedIn = spotifyController.isLoggedIn;

          return SingleChildScrollView(
            padding: const EdgeInsets.only(top: 16.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16.0),
                  child: _buildWelcomeCard(context),
                ),
                const SizedBox(height: 24),
                if (!isLoggedIn)
                  Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Center(
                      child: Column(
                        children: [
                          const Icon(
                            Icons.login,
                            size: 64,
                            color: Colors.grey,
                          ),
                          const SizedBox(height: 16),
                          const Text(
                            'Please log in to Spotify',
                            style: TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          const SizedBox(height: 8),
                          const Text(
                            'Log in to see your personalized content',
                            style: TextStyle(
                              fontSize: 14,
                              color: Colors.grey,
                            ),
                          ),
                        ],
                      ),
                    ),
                  )
                else if (homepageSections.isEmpty)
                  Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Center(
                      child: Column(
                        children: [
                          const CircularProgressIndicator(),
                          const SizedBox(height: 16),
                          const Text(
                            'Loading your content...',
                            style: TextStyle(
                              fontSize: 14,
                              color: Colors.grey,
                            ),
                          ),
                        ],
                      ),
                    ),
                  )
                else
                  ...homepageSections.map((section) =>
                    Padding(
                      padding: const EdgeInsets.only(bottom: 24.0),
                      child: HomepageSectionWidget(
                        section: section,
                        controller: spotifyController,
                      ),
                    ),
                  ),
              ],
            ),
          );
        },
      );
  }

  Widget _buildWelcomeCard(BuildContext context) {
    final username = spotifyController.username ?? 'Guest';
    final isLoggedIn = spotifyController.isLoggedIn;
    final isDarkMode = Theme.of(context).brightness == Brightness.dark;

    return Card(
      elevation: 4,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: isDarkMode
                ? [
                    Theme.of(context).colorScheme.primaryContainer,
                    Theme.of(context).colorScheme.secondaryContainer,
                  ]
                : [
                    Theme.of(context).colorScheme.primary,
                    Theme.of(context).colorScheme.secondary,
                  ],
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              isLoggedIn ? 'Welcome back,' : 'Welcome to Snepilatch',
              style: TextStyle(
                fontSize: 18,
                color: isDarkMode
                    ? Theme.of(context).colorScheme.onPrimaryContainer.withValues(alpha: 0.8)
                    : Colors.white70,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              isLoggedIn ? username : 'Your Spotify Controller',
              style: TextStyle(
                fontSize: 28,
                fontWeight: FontWeight.bold,
                color: isDarkMode
                    ? Theme.of(context).colorScheme.onPrimaryContainer
                    : Colors.white,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              isLoggedIn
                  ? spotifyController.currentTrack != null
                      ? 'Now playing: ${spotifyController.currentTrack}'
                      : 'No music playing'
                  : 'Login to start controlling Spotify',
              style: TextStyle(
                fontSize: 14,
                color: isDarkMode
                    ? Theme.of(context).colorScheme.onPrimaryContainer.withValues(alpha: 0.8)
                    : Colors.white70,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
    );
  }

}