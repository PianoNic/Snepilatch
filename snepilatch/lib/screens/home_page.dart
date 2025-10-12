import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../widgets/homepage_section_widget.dart';
import '../widgets/homepage_shortcuts_widget.dart';

class HomePage extends StatelessWidget {
  final SpotifyController spotifyController;
  const HomePage({super.key, required this.spotifyController});

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
        animation: spotifyController,
        builder: (context, child) {
          final homepageSections = spotifyController.homepageSections;
          final homepageShortcuts = spotifyController.homepageShortcuts;
          final isLoggedIn = spotifyController.isLoggedIn;

          return SingleChildScrollView(
            padding: const EdgeInsets.only(top: 16.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Shortcuts section (recently played items in grid)
                if (homepageShortcuts.isNotEmpty) ...[
                  HomepageShortcutsWidget(
                    shortcuts: homepageShortcuts,
                    controller: spotifyController,
                  ),
                  const SizedBox(height: 32),
                ],

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
                else if (homepageSections.isEmpty && homepageShortcuts.isEmpty)
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
}