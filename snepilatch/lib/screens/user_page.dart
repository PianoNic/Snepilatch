import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import '../controllers/spotify_controller.dart';
import '../widgets/theme_settings.dart';
import '../widgets/app_update_dialog.dart';
import '../services/update_service.dart';

class UserPage extends StatefulWidget {
  final SpotifyController spotifyController;
  const UserPage({super.key, required this.spotifyController});

  @override
  State<UserPage> createState() => _UserPageState();
}

class _UserPageState extends State<UserPage> {
  String _appVersion = '';
  bool _isCheckingUpdate = false;

  @override
  void initState() {
    super.initState();
    _loadAppVersion();
  }

  Future<void> _loadAppVersion() async {
    final packageInfo = await PackageInfo.fromPlatform();
    setState(() {
      _appVersion = packageInfo.version;
    });
  }

  Future<void> _checkForUpdates() async {
    setState(() {
      _isCheckingUpdate = true;
    });

    try {
      final updateInfo = await UpdateService.checkForUpdates();
      if (!mounted) return;

      if (updateInfo != null) {
        showDialog(
          context: context,
          barrierDismissible: false,
          builder: (context) => AppUpdateDialog(updateInfo: updateInfo),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('You have the latest version!'),
            duration: Duration(seconds: 2),
          ),
        );
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Failed to check for updates: ${e.toString()}'),
          duration: const Duration(seconds: 3),
        ),
      );
    } finally {
      if (mounted) {
        setState(() {
          _isCheckingUpdate = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
        animation: Listenable.merge([
          widget.spotifyController,
          widget.spotifyController.themeService,
        ]),
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
                const SizedBox(height: 32),
                // Theme Settings Section
                ThemeSettings(
                  themeService: widget.spotifyController.themeService,
                ),
                const SizedBox(height: 16),
                _buildUpdateSection(context),
                if (widget.spotifyController.isLoggedIn) ...[
                  const SizedBox(height: 16),
                  _buildStatistics(context),
                ],
                const SizedBox(height: 32),
              ],
            ),
          );
        },
      );
  }

  Widget _buildProfileAvatar(BuildContext context) {
    return CircleAvatar(
      radius: 60,
      backgroundColor: Theme.of(context).colorScheme.primary,
      backgroundImage: widget.spotifyController.userProfileImage != null
          ? NetworkImage(widget.spotifyController.userProfileImage!)
          : null,
      child: widget.spotifyController.userProfileImage == null
          ? Icon(
              widget.spotifyController.isLoggedIn ? Icons.account_circle : Icons.person,
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
          widget.spotifyController.isLoggedIn
            ? (widget.spotifyController.username ?? 'Spotify User')
            : 'Not Logged In',
          style: const TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          widget.spotifyController.isLoggedIn
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
    if (!widget.spotifyController.isLoggedIn) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32.0),
        child: ElevatedButton.icon(
          onPressed: () => widget.spotifyController.navigateToLogin(),
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
            onPressed: () => widget.spotifyController.openWebView(),
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
            onPressed: () => widget.spotifyController.logout(),
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

  Widget _buildUpdateSection(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 32.0),
      padding: const EdgeInsets.all(16.0),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'App Version',
                    style: TextStyle(
                      fontSize: 14,
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    _appVersion.isEmpty ? 'Loading...' : 'v$_appVersion',
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ],
              ),
              ElevatedButton.icon(
                onPressed: _isCheckingUpdate ? null : _checkForUpdates,
                icon: _isCheckingUpdate
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                        ),
                      )
                    : const Icon(Icons.system_update, size: 18),
                label: Text(_isCheckingUpdate ? 'Checking...' : 'Check for Updates'),
                style: ElevatedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}