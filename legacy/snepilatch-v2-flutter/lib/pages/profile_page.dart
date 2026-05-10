import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:snepilatch_v2/main.dart';
import 'package:snepilatch_v2/services/spotify_client.dart';
import 'package:snepilatch_v2/services/spotify_config.dart';
import 'package:snepilatch_v2/services/spotify_token_storage.dart';

class ProfileTab extends StatelessWidget {
  const ProfileTab({super.key});

  @override
  Widget build(BuildContext context) {
    final spotifyClient = context.watch<SpotifyClient>();
    final userProfile = spotifyClient.userProfile;
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Scaffold(
      body: SafeArea(
        child: userProfile == null
            ? Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Padding(
                      padding: const EdgeInsets.only(bottom: 24),
                      child: spotifyClient.tokenInitializationFailed
                          ? Icon(
                              Icons.error_outline_rounded,
                              size: 64,
                              color: colorScheme.error,
                            )
                          : CircularProgressIndicator(
                              valueColor: AlwaysStoppedAnimation<Color>(
                                  colorScheme.primary),
                            ),
                    ),
                    Padding(
                      padding: const EdgeInsets.only(bottom: 16),
                      child: Text(
                        spotifyClient.tokenInitializationFailed
                            ? 'Authentication Failed'
                            : 'Loading Your Profile',
                        style: theme.textTheme.titleLarge,
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 32),
                      child: Text(
                        spotifyClient.tokenInitializationFailed
                            ? 'Could not authenticate with Spotify. Make sure you\'re logged into Spotify in your browser.'
                            : 'Authenticating with Spotify...',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ),
                    if (spotifyClient.tokenInitializationFailed)
                      Padding(
                        padding: const EdgeInsets.only(top: 32),
                        child: OutlinedButton(
                          onPressed: () {
                            Navigator.pop(context);
                          },
                          child: const Text('Dismiss'),
                        ),
                      ),
                  ],
                ),
              )
            : SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
                child: Column(
                  children: [
                    // -- Profile header --
                    Card.outlined(
                      shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(16)),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 24, vertical: 28),
                        child: Column(
                          children: [
                            CircleAvatar(
                              radius: 48,
                              backgroundColor:
                                  colorScheme.surfaceContainerHighest,
                              backgroundImage: userProfile.profileImage != null
                                  ? NetworkImage(userProfile.profileImage!)
                                  : null,
                              onBackgroundImageError: (_, __) {},
                              child: userProfile.profileImage == null
                                  ? Icon(Icons.person_rounded,
                                      size: 44, color: colorScheme.primary)
                                  : null,
                            ),
                            const SizedBox(height: 16),
                            Text(
                              userProfile.displayName,
                              style: theme.textTheme.headlineSmall?.copyWith(
                                fontWeight: FontWeight.w800,
                                color: colorScheme.onSurface,
                              ),
                              textAlign: TextAlign.center,
                            ),
                            const SizedBox(height: 4),
                            Text(
                              '@${userProfile.id}',
                              style: theme.textTheme.bodyMedium?.copyWith(
                                color: colorScheme.onSurfaceVariant,
                              ),
                              textAlign: TextAlign.center,
                            ),
                            if (userProfile.premium == true) ...[
                              const SizedBox(height: 12),
                              Badge(
                                backgroundColor:
                                    colorScheme.primaryContainer,
                                textColor:
                                    colorScheme.onPrimaryContainer,
                                label: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(Icons.star_rounded,
                                        size: 14,
                                        color: colorScheme
                                            .onPrimaryContainer),
                                    const SizedBox(width: 4),
                                    Text(
                                      'Premium',
                                      style: theme.textTheme.labelSmall
                                          ?.copyWith(
                                        color: colorScheme
                                            .onPrimaryContainer,
                                        fontWeight: FontWeight.w700,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),

                    const SizedBox(height: 12),

                    // -- Account details --
                    Card.outlined(
                      shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(16)),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Padding(
                            padding:
                                const EdgeInsets.fromLTRB(16, 16, 16, 4),
                            child: Text(
                              'Account',
                              style: theme.textTheme.titleSmall?.copyWith(
                                color: colorScheme.primary,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                          if (userProfile.country != null)
                            _DetailTile(
                              icon: Icons.language_rounded,
                              label: 'Country',
                              trailing: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  _FlagEmoji(
                                      code: userProfile.country!),
                                  const SizedBox(width: 6),
                                  Text(
                                    userProfile.country!,
                                    style: theme.textTheme.bodyLarge
                                        ?.copyWith(
                                      color: colorScheme.onSurface,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          if (userProfile.premium != null)
                            _DetailTile(
                              icon: userProfile.premium!
                                  ? Icons.star_rounded
                                  : Icons.music_note_rounded,
                              label: 'Plan',
                              value: userProfile.premium!
                                  ? 'Premium'
                                  : 'Free',
                            ),
                          if (userProfile.followers != null)
                            _DetailTile(
                              icon: Icons.people_outline_rounded,
                              label: 'Followers',
                              value: _formatNumber(
                                  userProfile.followers!),
                            ),
                          _DetailTile(
                            icon: Icons.alternate_email_rounded,
                            label: 'User ID',
                            value: userProfile.id,
                          ),
                          const SizedBox(height: 8),
                        ],
                      ),
                    ),

                    const SizedBox(height: 24),

                    // -- Reset credentials --
                    SizedBox(
                      width: double.infinity,
                      child: OutlinedButton.icon(
                        icon: Icon(Icons.logout_rounded,
                            color: colorScheme.error),
                        label: Text('Reset Credentials',
                            style: TextStyle(color: colorScheme.error)),
                        style: OutlinedButton.styleFrom(
                          side: BorderSide(color: colorScheme.error),
                        ),
                        onPressed: () async {
                          final confirmed = await showDialog<bool>(
                            context: context,
                            builder: (ctx) => AlertDialog(
                              title: const Text('Reset Credentials'),
                              content: const Text(
                                'This will clear your saved credentials and OAuth session. The app will restart to the setup screen.',
                              ),
                              actions: [
                                TextButton(
                                  onPressed: () => Navigator.pop(ctx, false),
                                  child: const Text('Cancel'),
                                ),
                                FilledButton(
                                  onPressed: () => Navigator.pop(ctx, true),
                                  child: const Text('Reset'),
                                ),
                              ],
                            ),
                          );
                          if (confirmed == true) {
                            await SpotifyConfig.clearCredentials();
                            await SpotifyTokenStorage().clearSession();
                            if (context.mounted) {
                              SnepilatchApp.restart(context);
                            }
                          }
                        },
                      ),
                    ),
                  ],
                ),
              ),
      ),
    );
  }

  String _formatNumber(int n) {
    if (n >= 1000000) return '${(n / 1000000).toStringAsFixed(1)}M';
    if (n >= 1000) return '${(n / 1000).toStringAsFixed(1)}K';
    return n.toString();
  }
}

class _DetailTile extends StatelessWidget {
  const _DetailTile({
    required this.icon,
    required this.label,
    this.value,
    this.trailing,
  });

  final IconData icon;
  final String label;
  final String? value;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return ListTile(
      leading: Icon(icon, color: colorScheme.onSurfaceVariant, size: 22),
      title: Text(
        label,
        style: theme.textTheme.bodyMedium?.copyWith(
          color: colorScheme.onSurfaceVariant,
        ),
      ),
      trailing: trailing ??
          Text(
            value ?? '',
            style: theme.textTheme.bodyLarge?.copyWith(
              color: colorScheme.onSurface,
              fontWeight: FontWeight.w600,
            ),
          ),
    );
  }
}

class _FlagEmoji extends StatelessWidget {
  const _FlagEmoji({required this.code});

  final String code;

  @override
  Widget build(BuildContext context) {
    final normalized = code.trim().toUpperCase();
    if (normalized.length != 2) return Text(normalized);
    final int first = normalized.codeUnitAt(0) - 0x41 + 0x1F1E6;
    final int second = normalized.codeUnitAt(1) - 0x41 + 0x1F1E6;
    if (first < 0x1F1E6 ||
        first > 0x1F1FF ||
        second < 0x1F1E6 ||
        second > 0x1F1FF) {
      return Text(normalized);
    }
    return Text(
      String.fromCharCodes([first, second]),
      style: const TextStyle(fontSize: 16),
    );
  }
}
