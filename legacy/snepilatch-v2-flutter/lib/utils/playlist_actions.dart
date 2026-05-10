import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:spotify/spotify.dart' as spotify;
import 'package:snepilatch_v2/services/spotify_client.dart';

/// Shows a modal bottom sheet to pick a playlist, then adds the given track(s).
///
/// [trackUris] can be a single URI or multiple URIs to add.
void showAddToPlaylistModal(
  BuildContext context, {
  required List<String> trackUris,
}) async {
  final spotifyClient = context.read<SpotifyClient>();
  final api = spotifyClient.spotifyApi;
  if (api == null) return;

  final currentUserId = spotifyClient.userProfile?.id;
  final messenger = ScaffoldMessenger.maybeOf(context);

  List<spotify.PlaylistSimple>? playlists;
  try {
    final page = await api.playlists.me.getPage(50);
    final allPlaylists = (page.items ?? []).toList();
    if (currentUserId != null) {
      playlists =
          allPlaylists.where((p) => p.owner?.id == currentUserId).toList();
    } else {
      playlists = allPlaylists;
    }
  } catch (e) {
    debugPrint('[PlaylistActions] Error fetching playlists: $e');
  }

  if (playlists == null) return;
  if (!context.mounted) return;

  showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
    ),
    builder: (sheetContext) {
      final theme = Theme.of(sheetContext);
      final colorScheme = theme.colorScheme;
      return DraggableScrollableSheet(
        initialChildSize: 0.5,
        minChildSize: 0.3,
        maxChildSize: 0.85,
        expand: false,
        builder: (_, scrollController) {
          return Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 16, 24, 8),
                child: Row(
                  children: [
                    Text(
                      'Add to Playlist',
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w700,
                        color: colorScheme.onSurface,
                      ),
                    ),
                    const Spacer(),
                    IconButton(
                      icon: const Icon(Icons.close),
                      onPressed: () => Navigator.pop(sheetContext),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),
              Expanded(
                child: playlists!.isEmpty
                    ? Center(
                        child: Text(
                          'No playlists found',
                          style: theme.textTheme.bodyLarge?.copyWith(
                            color: colorScheme.onSurfaceVariant,
                          ),
                        ),
                      )
                    : ListView.builder(
                        controller: scrollController,
                        itemCount: playlists.length,
                        itemBuilder: (_, index) {
                          final playlist = playlists![index];
                          final imageUrl =
                              playlist.images?.isNotEmpty == true
                                  ? playlist.images!.first.url
                                  : null;
                          return ListTile(
                            leading: ClipRRect(
                              borderRadius: BorderRadius.circular(4),
                              child: imageUrl != null
                                  ? Image.network(
                                      imageUrl,
                                      width: 48,
                                      height: 48,
                                      fit: BoxFit.cover,
                                      errorBuilder: (_, __, ___) =>
                                          Container(
                                        width: 48,
                                        height: 48,
                                        color: colorScheme
                                            .surfaceContainerHighest,
                                        child: Icon(
                                          Icons.music_note,
                                          color:
                                              colorScheme.onSurfaceVariant,
                                        ),
                                      ),
                                    )
                                  : Container(
                                      width: 48,
                                      height: 48,
                                      decoration: BoxDecoration(
                                        color: colorScheme
                                            .surfaceContainerHighest,
                                        borderRadius:
                                            BorderRadius.circular(4),
                                      ),
                                      child: Icon(
                                        Icons.music_note,
                                        color:
                                            colorScheme.onSurfaceVariant,
                                      ),
                                    ),
                            ),
                            title: Text(
                              playlist.name ?? '',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            subtitle: Text(
                              '${playlist.tracksLink?.total ?? 0} tracks',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: colorScheme.onSurfaceVariant,
                              ),
                            ),
                            onTap: () async {
                              final playlistId = playlist.id ?? '';
                              if (playlistId.isEmpty) return;
                              Navigator.pop(sheetContext);
                              try {
                                debugPrint(
                                  '[PlaylistActions] Adding ${trackUris.length} track(s) to playlist $playlistId',
                                );
                                if (trackUris.length == 1) {
                                  await api.playlists
                                      .addTrack(trackUris.first, playlistId);
                                } else {
                                  await api.playlists
                                      .addTracks(trackUris, playlistId);
                                }
                                debugPrint(
                                  '[PlaylistActions] Successfully added track(s)',
                                );
                                messenger?.showSnackBar(
                                  SnackBar(
                                    content: Text(
                                      'Added to ${playlist.name}',
                                    ),
                                    behavior: SnackBarBehavior.floating,
                                  ),
                                );
                              } catch (e, st) {
                                debugPrint(
                                  '[PlaylistActions] Error adding track(s): $e\n$st',
                                );
                                messenger?.showSnackBar(
                                  const SnackBar(
                                    content:
                                        Text('Failed to add to playlist'),
                                    behavior: SnackBarBehavior.floating,
                                  ),
                                );
                              }
                            },
                          );
                        },
                      ),
              ),
            ],
          );
        },
      );
    },
  );
}

/// Shares a Spotify item link via the system share sheet.
void shareSpotifyItem({
  required String uri,
}) {
  final parts = uri.split(':');
  if (parts.length < 3) return;
  final type = parts[parts.length - 2]; // track, album, artist, playlist
  final id = parts.last;
  SharePlus.instance.share(
    ShareParams(text: 'https://open.spotify.com/$type/$id'),
  );
}
