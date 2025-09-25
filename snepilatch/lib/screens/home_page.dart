import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';

class HomePage extends StatelessWidget {
  final SpotifyController spotifyController;
  const HomePage({super.key, required this.spotifyController});

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
        animation: spotifyController,
        builder: (context, child) {
          return SingleChildScrollView(
            padding: const EdgeInsets.all(16.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildWelcomeCard(context),
                const SizedBox(height: 24),
                _buildQuickActions(context),
                const SizedBox(height: 24),
                _buildRecentActivity(context),
                const SizedBox(height: 24),
                _buildStats(context),
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
                    ? Theme.of(context).colorScheme.onPrimaryContainer.withOpacity(0.8)
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
                    ? Theme.of(context).colorScheme.onPrimaryContainer.withOpacity(0.8)
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

  Widget _buildQuickActions(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Quick Actions',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 12),
        GridView.count(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          crossAxisCount: 2,
          mainAxisSpacing: 12,
          crossAxisSpacing: 12,
          childAspectRatio: 1.5,
          children: [
            _buildActionCard(
              context,
              icon: Icons.favorite,
              title: 'Liked Songs',
              subtitle: '${spotifyController.songs.length} songs',
              color: Colors.red,
              onTap: () {
                // Navigate to liked songs would be handled by the main screen navigation
              },
            ),
            _buildActionCard(
              context,
              icon: Icons.search,
              title: 'Search',
              subtitle: 'Find music',
              color: Colors.blue,
              onTap: () {
                // Navigate to search would be handled by the main screen navigation
              },
            ),
            _buildActionCard(
              context,
              icon: Icons.playlist_play,
              title: 'Playlists',
              subtitle: 'Browse playlists',
              color: Colors.green,
              onTap: () {
                spotifyController.openWebView();
              },
            ),
            _buildActionCard(
              context,
              icon: Icons.explore,
              title: 'Discover',
              subtitle: 'New music',
              color: Colors.purple,
              onTap: () {
                spotifyController.openWebView();
              },
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildActionCard(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String subtitle,
    required Color color,
    required VoidCallback onTap,
  }) {
    return Card(
      elevation: 2,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.all(12),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, color: color, size: 28),
              const SizedBox(height: 6),
              Text(
                title,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              Text(
                subtitle,
                style: TextStyle(
                  fontSize: 11,
                  color: Theme.of(context).colorScheme.secondary,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildRecentActivity(BuildContext context) {
    if (!spotifyController.isLoggedIn || spotifyController.currentTrack == null) {
      return const SizedBox.shrink();
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Currently Playing',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 12),
        Card(
          child: ListTile(
            leading: spotifyController.currentAlbumArt != null
                ? Container(
                    width: 56,
                    height: 56,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(8),
                      image: DecorationImage(
                        image: NetworkImage(spotifyController.currentAlbumArt!),
                        fit: BoxFit.cover,
                      ),
                    ),
                  )
                : Container(
                    width: 56,
                    height: 56,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(8),
                      color: Theme.of(context).colorScheme.primaryContainer,
                    ),
                    child: const Icon(Icons.music_note),
                  ),
            title: Text(
              spotifyController.currentTrack ?? 'No track',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            subtitle: Text(
              spotifyController.currentArtist ?? '',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            trailing: Icon(
              spotifyController.isPlaying ? Icons.volume_up : Icons.volume_mute,
              color: Theme.of(context).colorScheme.primary,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildStats(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Your Stats',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            Expanded(
              child: _buildStatCard(
                context,
                icon: Icons.music_note,
                value: '${spotifyController.songs.length}',
                label: 'Liked Songs',
                color: Colors.orange,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _buildStatCard(
                context,
                icon: Icons.account_circle,
                value: spotifyController.isLoggedIn ? 'Online' : 'Offline',
                label: 'Status',
                color: spotifyController.isLoggedIn ? Colors.green : Colors.grey,
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            Expanded(
              child: _buildStatCard(
                context,
                icon: Icons.shuffle,
                value: spotifyController.shuffleMode,
                label: 'Shuffle',
                color: Colors.blue,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _buildStatCard(
                context,
                icon: Icons.repeat,
                value: spotifyController.repeatMode,
                label: 'Repeat',
                color: Colors.purple,
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildStatCard(
    BuildContext context, {
    required IconData icon,
    required String value,
    required String label,
    required Color color,
  }) {
    return Card(
      child: Container(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            Icon(icon, color: color, size: 28),
            const SizedBox(height: 8),
            Text(
              value,
              style: const TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
            Text(
              label,
              style: TextStyle(
                fontSize: 12,
                color: Theme.of(context).colorScheme.secondary,
              ),
            ),
          ],
        ),
      ),
    );
  }
}