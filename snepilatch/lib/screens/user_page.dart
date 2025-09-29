import 'package:flutter/material.dart';
import 'dart:async';
import 'package:package_info_plus/package_info_plus.dart';
import '../controllers/spotify_controller.dart';
import '../widgets/theme_settings.dart';
import '../widgets/app_update_dialog.dart';
import '../pages/release_notes_page.dart';
import '../services/update_service.dart';
import '../services/webview_audio_streamer.dart';

class UserPage extends StatefulWidget {
  final SpotifyController spotifyController;
  const UserPage({super.key, required this.spotifyController});

  @override
  State<UserPage> createState() => _UserPageState();
}

class _UserPageState extends State<UserPage> {
  String _appVersion = '';
  bool _isCheckingUpdate = false;
  Timer? _audioStatusTimer;

  @override
  void initState() {
    super.initState();
    _loadAppVersion();
    // Update audio status every second
    _audioStatusTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  Future<void> _loadAppVersion() async {
    final packageInfo = await PackageInfo.fromPlatform();
    setState(() {
      _appVersion = packageInfo.version;
    });
  }

  @override
  void dispose() {
    _audioStatusTimer?.cancel();
    super.dispose();
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
                const SizedBox(height: 16),
                // Profile section
                _buildProfileSection(context),
                // Theme Settings Section
                ThemeSettings(
                  themeService: widget.spotifyController.themeService,
                ),
                // Update Section
                _buildUpdateSection(context),
                // Audio Streaming Status Section
                _buildAudioStatusSection(context),
                const SizedBox(height: 16),
              ],
            ),
          );
        },
      );
  }

  Widget _buildProfileSection(BuildContext context) {
    return Card(
      margin: const EdgeInsets.all(16),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Profile',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                CircleAvatar(
                  radius: 40,
                  backgroundColor: Theme.of(context).colorScheme.primary,
                  backgroundImage: widget.spotifyController.userProfileImage != null
                      ? NetworkImage(widget.spotifyController.userProfileImage!)
                      : null,
                  child: widget.spotifyController.userProfileImage == null
                      ? Icon(
                          widget.spotifyController.isLoggedIn ? Icons.account_circle : Icons.person,
                          size: 40,
                          color: Colors.white,
                        )
                      : null,
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        widget.spotifyController.isLoggedIn
                          ? (widget.spotifyController.username ?? 'Spotify User')
                          : 'Not Logged In',
                        style: const TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        widget.spotifyController.isLoggedIn
                          ? 'Connected to Spotify'
                          : 'Sign in to access your music',
                        style: TextStyle(
                          fontSize: 14,
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            const Divider(),
            const SizedBox(height: 16),
            _buildActionButtons(context),
          ],
        ),
      ),
    );
  }

  Widget _buildActionButtons(BuildContext context) {
    if (!widget.spotifyController.isLoggedIn) {
      return SizedBox(
        width: double.infinity,
        child: ElevatedButton.icon(
          onPressed: () => widget.spotifyController.navigateToLogin(),
          icon: const Icon(Icons.login),
          label: const Text('Login to Spotify'),
          style: ElevatedButton.styleFrom(
            minimumSize: const Size(double.infinity, 48),
            backgroundColor: Theme.of(context).brightness == Brightness.dark
                ? Theme.of(context).colorScheme.primaryContainer
                : Theme.of(context).colorScheme.primary,
            foregroundColor: Theme.of(context).brightness == Brightness.dark
                ? Theme.of(context).colorScheme.onPrimaryContainer
                : Colors.white,
          ),
        ),
      );
    }

    return Column(
      children: [
        SizedBox(
          width: double.infinity,
          child: ElevatedButton.icon(
            onPressed: () => widget.spotifyController.openWebView(),
            icon: const Icon(Icons.open_in_browser),
            label: const Text('Open Spotify Web'),
            style: ElevatedButton.styleFrom(
              minimumSize: const Size(double.infinity, 48),
              backgroundColor: Theme.of(context).brightness == Brightness.dark
                  ? Theme.of(context).colorScheme.primaryContainer
                  : Theme.of(context).colorScheme.primary,
              foregroundColor: Theme.of(context).brightness == Brightness.dark
                  ? Theme.of(context).colorScheme.onPrimaryContainer
                  : Colors.white,
            ),
          ),
        ),
        const SizedBox(height: 12),
        SizedBox(
          width: double.infinity,
          child: OutlinedButton.icon(
            onPressed: () => widget.spotifyController.logout(),
            icon: const Icon(Icons.logout),
            label: const Text('Logout'),
            style: OutlinedButton.styleFrom(
              minimumSize: const Size(double.infinity, 48),
              foregroundColor: Colors.red,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildUpdateSection(BuildContext context) {
    return Card(
      margin: const EdgeInsets.all(16),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'App Updates',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 16),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Version',
                        style: TextStyle(
                          fontSize: 14,
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        _appVersion.isEmpty ? 'Loading...' : (_appVersion.startsWith('v') ? _appVersion : 'v$_appVersion'),
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
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
                  label: Text(_isCheckingUpdate ? 'Checking...' : 'Updates'),
                  style: ElevatedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (context) => const ReleaseNotesPage(),
                    ),
                  );
                },
                icon: const Icon(Icons.notes, size: 18),
                label: const Text('Release Notes'),
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAudioStatusSection(BuildContext context) {
    final audioStreamer = WebViewAudioStreamer.instance;
    final isConnected = audioStreamer.isConnected;
    final status = audioStreamer.status;
    final bytesReceived = audioStreamer.bytesReceived;
    final packetsReceived = audioStreamer.packetsReceived;

    return Card(
      margin: const EdgeInsets.all(16),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  isConnected ? Icons.wifi_tethering : Icons.wifi_tethering_off,
                  color: isConnected ? Colors.green : Colors.grey,
                ),
                const SizedBox(width: 8),
                Text(
                  'Audio Streaming',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Container(
                  width: 12,
                  height: 12,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: isConnected ? Colors.green : Colors.red,
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  status,
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
              ],
            ),
            if (isConnected) ...[
              const SizedBox(height: 8),
              Text(
                'Packets: $packetsReceived',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              Text(
                'Data: ${(bytesReceived / 1024).toStringAsFixed(2)} KB',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
            const Divider(height: 24),
            Text(
              'How to test audio routing:',
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const SizedBox(height: 8),
            const Text(
              '1. Play music in Spotify WebView\n'
              '2. Check if status shows "Streaming"\n'
              '3. Open Wavelet or other EQ app\n'
              '4. Wavelet should detect Snepilatch audio\n'
              '5. Audio plays through Flutter, not Chrome',
              style: TextStyle(fontSize: 12),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: () {
                // Force re-inject audio script
                final controller = widget.spotifyController.webViewService.controller;
                if (controller != null) {
                  WebViewAudioStreamer.instance.injectAudioScript(controller);
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text('Audio script re-injected'),
                      duration: Duration(seconds: 2),
                    ),
                  );
                }
              },
              icon: const Icon(Icons.refresh),
              label: const Text('Re-inject Audio Script'),
            ),
          ],
        ),
      ),
    );
  }
}