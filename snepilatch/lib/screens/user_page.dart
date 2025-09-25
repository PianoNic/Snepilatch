import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';

class UserPage extends StatelessWidget {
  final SpotifyController spotifyController;
  const UserPage({super.key, required this.spotifyController});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Profile'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () {},
          ),
        ],
      ),
      body: AnimatedBuilder(
        animation: spotifyController,
        builder: (context, child) {
          return SingleChildScrollView(
            child: Column(
              children: [
                const SizedBox(height: 24),
                _buildProfileAvatar(context),
                const SizedBox(height: 16),
                _buildUserInfo(context),
                const SizedBox(height: 24),
                _buildActionButtons(context),
                const SizedBox(height: 24),
                if (spotifyController.isLoggedIn) ...[
                  _buildStatistics(context),
                  const SizedBox(height: 32),
                  _buildLibrarySection(context),
                ],
                const SizedBox(height: 32),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildProfileAvatar(BuildContext context) {
    return CircleAvatar(
      radius: 60,
      backgroundColor: Theme.of(context).colorScheme.primary,
      backgroundImage: spotifyController.userProfileImage != null
          ? NetworkImage(spotifyController.userProfileImage!)
          : null,
      child: spotifyController.userProfileImage == null
          ? Icon(
              spotifyController.isLoggedIn ? Icons.account_circle : Icons.person,
              size: 60,
              color: Colors.white,
            )
          : null,
    );
  }

  Widget _buildUserInfo(BuildContext context) {
    return Column(
      children: [
        Text(
          spotifyController.isLoggedIn
            ? (spotifyController.username ?? 'Spotify User')
            : 'Not Logged In',
          style: const TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          spotifyController.isLoggedIn
            ? 'Connected to Spotify'
            : 'Sign in to access your music',
          style: TextStyle(
            fontSize: 16,
            color: Colors.grey[600],
          ),
        ),
      ],
    );
  }

  Widget _buildActionButtons(BuildContext context) {
    if (!spotifyController.isLoggedIn) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32.0),
        child: ElevatedButton.icon(
          onPressed: () => spotifyController.navigateToLogin(),
          icon: const Icon(Icons.login),
          label: const Text('Login to Spotify'),
          style: ElevatedButton.styleFrom(
            minimumSize: const Size(double.infinity, 48),
            backgroundColor: Theme.of(context).colorScheme.primary,
            foregroundColor: Colors.white,
          ),
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 32.0),
      child: Column(
        children: [
          ElevatedButton.icon(
            onPressed: () => spotifyController.openWebView(),
            icon: const Icon(Icons.open_in_browser),
            label: const Text('Open Spotify Web'),
            style: ElevatedButton.styleFrom(
              minimumSize: const Size(double.infinity, 48),
              backgroundColor: Theme.of(context).colorScheme.primary,
              foregroundColor: Colors.white,
            ),
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: () => spotifyController.logout(),
            icon: const Icon(Icons.logout),
            label: const Text('Logout'),
            style: OutlinedButton.styleFrom(
              minimumSize: const Size(double.infinity, 48),
              foregroundColor: Colors.red,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStatistics(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: [
        _buildStatCard(context, '256', 'Songs'),
        _buildStatCard(context, '12', 'Playlists'),
        _buildStatCard(context, '48', 'Following'),
      ],
    );
  }

  Widget _buildStatCard(BuildContext context, String count, String label) {
    return Column(
      children: [
        Text(
          count,
          style: const TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          label,
          style: TextStyle(
            fontSize: 14,
            color: Colors.grey[600],
          ),
        ),
      ],
    );
  }

  Widget _buildLibrarySection(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Your Library',
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 16),
          _buildLibraryTile(context, Icons.favorite, 'Liked Songs', '250 songs'),
          _buildLibraryTile(context, Icons.playlist_play, 'Playlists', '12 playlists'),
          _buildLibraryTile(context, Icons.download, 'Downloads', '150 songs'),
          _buildLibraryTile(context, Icons.history, 'Recently Played', '30 songs'),
          _buildLibraryTile(context, Icons.album, 'Albums', '45 albums'),
          _buildLibraryTile(context, Icons.person, 'Following Artists', '89 artists'),
        ],
      ),
    );
  }

  Widget _buildLibraryTile(BuildContext context, IconData icon, String title, String subtitle) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: Icon(
          icon,
          color: Theme.of(context).colorScheme.primary,
        ),
        title: Text(title),
        subtitle: Text(subtitle),
        trailing: const Icon(Icons.chevron_right),
        onTap: () {},
      ),
    );
  }
}