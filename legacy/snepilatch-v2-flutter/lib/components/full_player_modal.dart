import 'package:flutter/material.dart';
import 'dart:ui' as ui;
import 'package:provider/provider.dart';
import 'package:snepilatch_v2/pages/detail_page.dart';
import 'package:snepilatch_v2/services/spotify_client.dart';
import 'package:snepilatch_v2/utils/playlist_actions.dart';

class _FullWidthSliderTrackShape extends GappedSliderTrackShape {
  @override
  Rect getPreferredRect({
    required RenderBox parentBox,
    Offset offset = Offset.zero,
    required SliderThemeData sliderTheme,
    bool isEnabled = false,
    bool isDiscrete = false,
  }) {
    final trackHeight = sliderTheme.trackHeight ?? 4;
    final trackLeft = offset.dx;
    final trackTop = offset.dy + (parentBox.size.height - trackHeight) / 2;
    final trackWidth = parentBox.size.width;
    return Rect.fromLTWH(trackLeft, trackTop, trackWidth, trackHeight);
  }
}

class FullPlayerModal extends StatefulWidget {
  const FullPlayerModal({super.key});

  @override
  State<FullPlayerModal> createState() => _FullPlayerModalState();
}

class _FullPlayerModalState extends State<FullPlayerModal> {
  double? _draggedProgress;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final textTheme = theme.textTheme;
    final spotifyClient = context.watch<SpotifyClient>();
    final currentTrack = spotifyClient.currentTrack;
    final playbackProgress = spotifyClient.playbackProgress;
    final playerControlState = spotifyClient.playerControlState;

    final isPlaying = playerControlState?.playPause == 'playing';
    final loopState = playerControlState?.loop ?? 'loop_off';
    final shuffleState = playerControlState?.shuffle ?? 'shuffle_off';

    return Stack(
      fit: StackFit.expand,
      children: [
        // Blurred background
        if (currentTrack?.imageUrl != null)
          ImageFiltered(
            imageFilter: ui.ImageFilter.blur(sigmaX: 50, sigmaY: 50),
            child: Image.network(
              currentTrack!.imageUrl!,
              fit: BoxFit.cover,
              errorBuilder: (context, error, stackTrace) {
                return Container(color: colorScheme.surface);
              },
            ),
          )
        else
          Container(color: colorScheme.surface),

        // Dark overlay
        Container(color: Colors.black.withValues(alpha: 0.5)),

        // Content overlay
        SafeArea(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final bottomInset = MediaQuery.of(context).viewInsets.bottom;
              final topInset = MediaQuery.of(context).viewPadding.top;
              final coverSize = (constraints.maxWidth - 32).clamp(220.0, 420.0);

              return Padding(
                padding: EdgeInsets.fromLTRB(
                  16,
                  topInset + 40,
                  16,
                  16 + bottomInset,
                ),
                child: SizedBox(
                  height: constraints.maxHeight,
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      // Header
                      Row(
                        children: [
                          IconButton(
                            style: IconButton.styleFrom(
                              backgroundColor: colorScheme.surfaceContainerHigh.withValues(alpha: 0.3),
                              overlayColor: WidgetStateColor.resolveWith((states) {
                                if (states.contains(WidgetState.pressed)) {
                                  return colorScheme.primary.withValues(alpha: 0.1);
                                }
                                if (states.contains(WidgetState.hovered)) {
                                  return colorScheme.primary.withValues(alpha: 0.08);
                                }
                                return Colors.transparent;
                              }),
                            ),
                            icon: const Icon(Icons.keyboard_arrow_down_rounded),
                            onPressed: () => Navigator.pop(context),
                          ),
                          const Spacer(),
                          Text(
                            'Now playing',
                            style: textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.w600,
                              color: colorScheme.onSurface,
                            ),
                          ),
                          const Spacer(),
                          PopupMenuButton<String>(
                            icon: const Icon(Icons.more_vert_rounded),
                            style: IconButton.styleFrom(
                              backgroundColor: colorScheme.surfaceContainerHigh.withValues(alpha: 0.3),
                              overlayColor: WidgetStateColor.resolveWith((states) {
                                if (states.contains(WidgetState.pressed)) {
                                  return colorScheme.primary.withValues(alpha: 0.1);
                                }
                                if (states.contains(WidgetState.hovered)) {
                                  return colorScheme.primary.withValues(alpha: 0.08);
                                }
                                return Colors.transparent;
                              }),
                            ),
                            onSelected: (value) async {
                              if (value == 'share' && currentTrack?.uri != null) {
                                shareSpotifyItem(uri: currentTrack!.uri!);
                              } else if (value == 'addToPlaylist' &&
                                  currentTrack?.uri != null) {
                                showAddToPlaylistModal(context, trackUris: [currentTrack!.uri!]);
                              } else if ((value == 'album' || value == 'artist') &&
                                  currentTrack?.uri != null) {
                                final api = context.read<SpotifyClient>().spotifyApi;
                                if (api == null) return;
                                final trackId = currentTrack!.uri!.split(':').last;
                                try {
                                  final track = await api.tracks.get(trackId);
                                  if (!mounted) return;
                                  final rootNav = Navigator.of(context, rootNavigator: true);
                                  rootNav.pop();
                                  if (value == 'album' && track.album != null) {
                                    final album = track.album!;
                                    final imageUrl = album.images?.isNotEmpty == true
                                        ? album.images!.first.url
                                        : null;
                                    rootNav.push(MaterialPageRoute(
                                      builder: (_) => DetailPage(
                                        name: album.name ?? '',
                                        imageUrl: imageUrl,
                                        uri: album.uri ?? '',
                                        itemType: 'album',
                                      ),
                                    ));
                                  } else if (value == 'artist' &&
                                      track.artists?.isNotEmpty == true) {
                                    final artist = track.artists!.first;
                                    rootNav.push(MaterialPageRoute(
                                      builder: (_) => DetailPage(
                                        name: artist.name ?? '',
                                        uri: artist.uri ?? '',
                                        itemType: 'artist',
                                      ),
                                    ));
                                  }
                                } catch (e) {
                                  debugPrint('[FullPlayerModal] Error fetching track: $e');
                                }
                              }
                            },
                            itemBuilder: (context) => [
                              const PopupMenuItem(
                                value: 'addToPlaylist',
                                child: ListTile(
                                  leading: Icon(Icons.playlist_add_rounded),
                                  title: Text('Add to Playlist'),
                                  contentPadding: EdgeInsets.zero,
                                  visualDensity: VisualDensity.compact,
                                ),
                              ),
                              const PopupMenuItem(
                                value: 'album',
                                child: ListTile(
                                  leading: Icon(Icons.album_rounded),
                                  title: Text('View Album'),
                                  contentPadding: EdgeInsets.zero,
                                  visualDensity: VisualDensity.compact,
                                ),
                              ),
                              const PopupMenuItem(
                                value: 'artist',
                                child: ListTile(
                                  leading: Icon(Icons.person_rounded),
                                  title: Text('View Artist'),
                                  contentPadding: EdgeInsets.zero,
                                  visualDensity: VisualDensity.compact,
                                ),
                              ),
                              const PopupMenuItem(
                                value: 'share',
                                child: ListTile(
                                  leading: Icon(Icons.share_rounded),
                                  title: Text('Share'),
                                  contentPadding: EdgeInsets.zero,
                                  visualDensity: VisualDensity.compact,
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                      const SizedBox(height: 20),
                      // Artwork
                      ClipRRect(
                        borderRadius: BorderRadius.circular(22),
                        child: SizedBox(
                          width: coverSize.toDouble(),
                          height: coverSize.toDouble(),
                          child: currentTrack?.imageUrl != null
                              ? Image.network(
                                  currentTrack!.imageUrl!,
                                  fit: BoxFit.cover,
                                  errorBuilder: (context, error, stackTrace) {
                                    return Center(
                                      child: Icon(Icons.music_note, size: 64),
                                    );
                                  },
                                )
                              : Center(
                                  child: Icon(Icons.music_note, size: 64),
                                ),
                        ),
                      ),
                      const SizedBox(height: 20),
                      // Song Info
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  currentTrack?.title ?? 'No track',
                                  style: textTheme.headlineSmall?.copyWith(
                                    fontWeight: FontWeight.w700,
                                    color: colorScheme.onSurface,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  currentTrack?.artist ?? 'Unknown artist',
                                  style: textTheme.titleMedium?.copyWith(
                                    color: colorScheme.onSurfaceVariant,
                                    fontWeight: FontWeight.w500,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ],
                            ),
                          ),
                          const SizedBox(width: 12),
                          IconButton.filledTonal(
                            icon: Icon(
                              currentTrack?.liked == true
                                  ? Icons.favorite_rounded
                                  : Icons.favorite_border_rounded,
                            ),
                            onPressed: () {
                              print('[FullPlayerModal] Like button pressed');
                              spotifyClient.toggleLiked();
                            },
                            style: IconButton.styleFrom(
                              backgroundColor: currentTrack?.liked == true
                                  ? colorScheme.primary.withValues(alpha: 0.2)
                                  : colorScheme.surfaceContainerHigh.withValues(alpha: 0.3),
                              foregroundColor: currentTrack?.liked == true
                                  ? colorScheme.primary
                                  : colorScheme.onSurfaceVariant,
                              overlayColor: WidgetStateColor.resolveWith((states) {
                                if (states.contains(WidgetState.pressed)) {
                                  return colorScheme.primary.withValues(alpha: 0.1);
                                }
                                return Colors.transparent;
                              }),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),
                      // Progress Bar - Full Width
                      SliderTheme(
                        data: SliderThemeData(
                          trackHeight: 10,
                          trackShape: _FullWidthSliderTrackShape(),
                          year2023: false,
                          thumbSize: WidgetStateProperty.resolveWith<Size>((
                            states,
                          ) {
                            if (states.contains(WidgetState.pressed)) {
                              return const Size(4, 25);
                            }
                            return const Size(5, 25);
                          }),
                          inactiveTrackColor:
                              colorScheme.surfaceContainerHighest,
                        ),
                        child: Slider(
                          value: _draggedProgress ?? playbackProgress?.progress ?? 0,
                          min: 0,
                          max: 100,
                          onChanged: (value) {
                            setState(() {
                              _draggedProgress = value;
                            });
                          },
                          onChangeEnd: (value) {
                            if (playbackProgress != null) {
                              final positionMs = ((value / 100) * playbackProgress.durationMs).toInt();
                              spotifyClient.seekTo(positionMs);
                            }
                            setState(() {
                              _draggedProgress = null;
                            });
                          },
                        ),
                      ),
                      Transform.translate(
                        offset: const Offset(0, -8),
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text(
                              playbackProgress?.currentTime ?? '0:00',
                              style: textTheme.labelSmall?.copyWith(
                                color: colorScheme.onSurfaceVariant,
                              ),
                            ),
                            Text(
                              playbackProgress?.duration ?? '0:00',
                              style: textTheme.labelSmall?.copyWith(
                                color: colorScheme.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 18),
                      // Control Buttons
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          IconButton.filledTonal(
                            icon: Icon(
                              shuffleState == 'shuffle_smart'
                                  ? Icons.auto_fix_high_rounded
                                  : Icons.shuffle_rounded,
                            ),
                            onPressed: () {
                              print('[FullPlayerModal] Shuffle button pressed');
                              spotifyClient.toggleShuffle();
                            },
                            style: IconButton.styleFrom(
                              backgroundColor: shuffleState != 'shuffle_off'
                                  ? colorScheme.primary.withValues(alpha: 0.2)
                                  : colorScheme.surfaceContainerHigh.withValues(alpha: 0.3),
                              foregroundColor: shuffleState != 'shuffle_off'
                                  ? colorScheme.primary
                                  : colorScheme.onSurfaceVariant,
                              overlayColor: WidgetStateColor.resolveWith((states) {
                                if (states.contains(WidgetState.pressed)) {
                                  return colorScheme.primary.withValues(alpha: 0.1);
                                }
                                return Colors.transparent;
                              }),
                            ),
                          ),
                          IconButton.filledTonal(
                            iconSize: 32,
                            icon: const Icon(Icons.skip_previous_rounded),
                            onPressed: () {
                              print(
                                '[FullPlayerModal] Skip Previous button pressed',
                              );
                              spotifyClient.skipPrevious();
                            },
                            style: IconButton.styleFrom(
                              minimumSize: const Size.square(56),
                              backgroundColor: colorScheme.surfaceContainerHigh.withValues(alpha: 0.3),
                              foregroundColor: colorScheme.onSurface,
                              overlayColor: WidgetStateColor.resolveWith((states) {
                                if (states.contains(WidgetState.pressed)) {
                                  return colorScheme.primary.withValues(alpha: 0.1);
                                }
                                return Colors.transparent;
                              }),
                            ),
                          ),
                          IconButton.filled(
                            iconSize: 36,
                            onPressed: () {
                              print(
                                '[FullPlayerModal] Play/Pause button pressed',
                              );
                              spotifyClient.togglePlayPause();
                            },
                            style: IconButton.styleFrom(
                              backgroundColor: colorScheme.primary,
                              foregroundColor: colorScheme.onPrimary,
                              minimumSize: const Size.square(72),
                              shape: const CircleBorder(),
                            ),
                            icon: Icon(
                              isPlaying
                                  ? Icons.pause_rounded
                                  : Icons.play_arrow_rounded,
                            ),
                          ),
                          IconButton.filledTonal(
                            iconSize: 32,
                            icon: const Icon(Icons.skip_next_rounded),
                            onPressed: () {
                              print(
                                '[FullPlayerModal] Skip Next button pressed',
                              );
                              spotifyClient.skipNext();
                            },
                            style: IconButton.styleFrom(
                              minimumSize: const Size.square(56),
                              backgroundColor: colorScheme.surfaceContainerHigh.withValues(alpha: 0.3),
                              foregroundColor: colorScheme.onSurface,
                              overlayColor: WidgetStateColor.resolveWith((states) {
                                if (states.contains(WidgetState.pressed)) {
                                  return colorScheme.primary.withValues(alpha: 0.1);
                                }
                                return Colors.transparent;
                              }),
                            ),
                          ),
                          IconButton.filledTonal(
                            icon: Icon(
                              loopState == 'loop_one'
                                  ? Icons.repeat_one_rounded
                                  : Icons.repeat_rounded,
                            ),
                            onPressed: () {
                              print(
                                '[FullPlayerModal] Repeat button pressed',
                              );
                              spotifyClient.toggleRepeat();
                            },
                            style: IconButton.styleFrom(
                              backgroundColor: loopState != 'loop_off'
                                  ? colorScheme.primary.withValues(alpha: 0.2)
                                  : colorScheme.surfaceContainerHigh.withValues(alpha: 0.3),
                              foregroundColor: loopState != 'loop_off'
                                  ? colorScheme.primary
                                  : colorScheme.onSurfaceVariant,
                              overlayColor: WidgetStateColor.resolveWith((states) {
                                if (states.contains(WidgetState.pressed)) {
                                  return colorScheme.primary.withValues(alpha: 0.1);
                                }
                                return Colors.transparent;
                              }),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}
