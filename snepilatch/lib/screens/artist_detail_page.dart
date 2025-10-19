import 'package:flutter/material.dart';
import 'dart:async';
import '../controllers/spotify_controller.dart';
import '../models/homepage_item.dart';
import '../models/song.dart';

class ArtistDetailPage extends StatefulWidget {
  final HomepageItem artist;
  final SpotifyController controller;

  const ArtistDetailPage({
    super.key,
    required this.artist,
    required this.controller,
  });

  @override
  State<ArtistDetailPage> createState() => _ArtistDetailPageState();
}

class _ArtistDetailPageState extends State<ArtistDetailPage> {
  List<Song> topTracks = [];
  bool isLoading = true;
  String? artistName;
  String? artistImage;
  String? monthlyListeners;
  bool? isFollowed;
  bool? isArtistPlaying;
  Timer? _statusCheckTimer;
  bool _isTogglingFollow = false; // Prevent batch updates during toggle

  @override
  void initState() {
    super.initState();
    _loadArtistDetails();
    _startPeriodicStatusCheck();
  }

  void _startPeriodicStatusCheck() {
    // Check follow status and play status every 1.5 seconds (similar to playback scraping)
    _statusCheckTimer = Timer.periodic(const Duration(milliseconds: 1500), (_) async {
      if (mounted && !_isTogglingFollow) {
        final currentFollowStatus = await widget.controller.isArtistFollowed();
        final currentPlayStatus = await widget.controller.isArtistPlaying();

        if (mounted && !_isTogglingFollow) {
          bool hasChanges = false;

          if (currentFollowStatus != isFollowed) {
            debugPrint('🔄 Follow status changed, updating UI: $currentFollowStatus');
            hasChanges = true;
          }

          if (currentPlayStatus != isArtistPlaying) {
            debugPrint('🔄 Play status changed, updating UI: $currentPlayStatus');
            hasChanges = true;
          }

          if (hasChanges) {
            setState(() {
              isFollowed = currentFollowStatus;
              isArtistPlaying = currentPlayStatus;
            });
          }
        }
      }
    });
  }

  @override
  void dispose() {
    _statusCheckTimer?.cancel();
    super.dispose();
  }

  Future<void> _loadArtistDetails() async {
    try {
      // Navigate to the artist in the WebView
      await widget.controller.navigateToHomepageItem(widget.artist.href);

      // Wait for page to load
      await Future.delayed(const Duration(seconds: 2));

      // Fetch tracks from the artist page
      final fetchedTracks = await widget.controller.fetchArtistTracks();

      // Get monthly listeners
      final listeners = await widget.controller.getArtistMonthlyListeners();

      // Check if followed
      final followed = await widget.controller.isArtistFollowed();

      if (mounted) {
        setState(() {
          topTracks = fetchedTracks;
          artistName = widget.artist.title;
          artistImage = widget.artist.imageUrl;
          monthlyListeners = listeners;
          isFollowed = followed;
          isLoading = false;
        });
      }
    } catch (e) {
      debugPrint('Error loading artist details: $e');
      if (mounted) {
        setState(() {
          isLoading = false;
        });
      }
    }
  }

  Future<void> _toggleFollow() async {
    if (_isTogglingFollow) return; // Prevent multiple simultaneous toggles

    try {
      _isTogglingFollow = true; // Disable batch updates during toggle
      debugPrint('🔄 Starting follow toggle...');

      // toggleFollowArtist now returns the new follow status
      final newFollowStatus = await widget.controller.toggleFollowArtist();
      if (!mounted) return;

      // Wait a bit longer to ensure Spotify has updated
      await Future.delayed(const Duration(milliseconds: 500));

      // Update UI with the new status
      setState(() {
        isFollowed = newFollowStatus;
      });

      // Show feedback
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              newFollowStatus ? 'Artist added to your library' : 'Artist removed from your library',
            ),
            duration: const Duration(seconds: 2),
          ),
        );
      }

      debugPrint('✅ Follow toggle complete');
    } catch (e) {
      debugPrint('Error toggling follow: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Error updating follow status'),
            duration: Duration(seconds: 2),
          ),
        );
      }
    } finally {
      _isTogglingFollow = false; // Re-enable batch updates
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, child) {
        return Scaffold(
          appBar: AppBar(
            title: const Text('Artist'),
            elevation: 0,
          ),
          body: isLoading
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              child: Column(
                children: [
                  // Artist header
                  Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Artist image
                        if (artistImage != null && artistImage!.isNotEmpty)
                          Center(
                            child: ClipRRect(
                              borderRadius: BorderRadius.circular(100),
                              child: Image.network(
                                artistImage!,
                                width: 200,
                                height: 200,
                                fit: BoxFit.cover,
                                errorBuilder: (context, error, stackTrace) {
                                  return Container(
                                    width: 200,
                                    height: 200,
                                    decoration: BoxDecoration(
                                      shape: BoxShape.circle,
                                      color: Colors.grey[800],
                                    ),
                                    child: const Icon(
                                      Icons.person,
                                      size: 64,
                                      color: Colors.white54,
                                    ),
                                  );
                                },
                              ),
                            ),
                          ),
                        const SizedBox(height: 16),
                        // Artist name
                        if (artistName != null)
                          Text(
                            artistName!,
                            style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                        const SizedBox(height: 8),
                        // Monthly listeners
                        if (monthlyListeners != null)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 8.0),
                            child: Row(
                              children: [
                                const Icon(
                                  Icons.people,
                                  size: 16,
                                  color: Colors.grey,
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  monthlyListeners!,
                                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                    color: Colors.grey[400],
                                  ),
                                ),
                              ],
                            ),
                          ),
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // Play status indicator
                            if (isArtistPlaying == true)
                              Padding(
                                padding: const EdgeInsets.only(bottom: 8.0),
                                child: Row(
                                  children: [
                                    Icon(
                                      Icons.play_circle,
                                      size: 16,
                                      color: Theme.of(context).primaryColor,
                                    ),
                                    const SizedBox(width: 6),
                                    Text(
                                      'Now Playing',
                                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                        color: Theme.of(context).primaryColor,
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            Text(
                              'Top Tracks',
                              style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                                color: Colors.grey[400],
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 16),
                        // Play and Follow buttons
                        Row(
                          children: [
                            Expanded(
                              flex: 2,
                              child: ElevatedButton.icon(
                                icon: Icon(
                                  isArtistPlaying == true ? Icons.pause : Icons.play_arrow,
                                ),
                                label: Text(
                                  isArtistPlaying == true ? 'Pause' : 'Play',
                                ),
                                onPressed: () async {
                                  if (isArtistPlaying == true) {
                                    // If already playing, toggle to pause
                                    final newStatus = await widget.controller.toggleArtistPlayPause();
                                    if (mounted) {
                                      setState(() {
                                        isArtistPlaying = newStatus;
                                      });
                                    }
                                  } else {
                                    // If not playing, play the artist
                                    await widget.controller.playHomepageItem(
                                      widget.artist.id,
                                      widget.artist.type,
                                    );
                                    // Wait for Spotify to update and refresh status
                                    await Future.delayed(const Duration(milliseconds: 500));
                                    if (mounted) {
                                      final newStatus = await widget.controller.isArtistPlaying();
                                      setState(() {
                                        isArtistPlaying = newStatus;
                                      });
                                    }
                                  }
                                },
                              ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: OutlinedButton.icon(
                                icon: Icon(
                                  isFollowed == true ? Icons.favorite : Icons.favorite_border,
                                  size: 20,
                                ),
                                label: Text(
                                  isFollowed == true ? 'Following' : 'Follow',
                                  style: const TextStyle(fontSize: 12),
                                ),
                                onPressed: _toggleFollow,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  const Divider(),
                  // Top tracks list
                  if (topTracks.isEmpty)
                    Padding(
                      padding: const EdgeInsets.all(48.0),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(
                            Icons.music_note,
                            size: 48,
                            color: Theme.of(context).colorScheme.primary,
                          ),
                          const SizedBox(height: 16),
                          const Text('No tracks found'),
                        ],
                      ),
                    )
                  else
                    ListView.builder(
                      physics: const NeverScrollableScrollPhysics(),
                      shrinkWrap: true,
                      itemCount: topTracks.length,
                      itemBuilder: (context, index) {
                        final track = topTracks[index];
                        return _buildTrackTile(context, track, index);
                      },
                    ),
                ],
              ),
            ),
        );
      },
    );
  }

  Widget _buildTrackTile(BuildContext context, Song track, int index) {
    // Check if this track is currently playing
    final isCurrentTrack = widget.controller.currentTrack == track.title &&
        widget.controller.isPlaying;

    Future<void> handleTrackTap() async {
      if (isCurrentTrack) {
        // If already playing, pause it
        await widget.controller.pausePlayback();
      } else {
        // If not playing, play it
        await widget.controller.playTrackAtIndex(track.index);
      }
    }

    final tile = ListTile(
      leading: Text(
        '${index + 1}',
        style: isCurrentTrack
            ? Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                )
            : Theme.of(context).textTheme.bodyMedium,
      ),
      title: Text(
        track.title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: isCurrentTrack
            ? Theme.of(context).textTheme.titleMedium?.copyWith(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                )
            : null,
      ),
      subtitle: Text(
        track.album.isNotEmpty ? track.album : 'Unknown Album',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: isCurrentTrack
            ? TextStyle(
                color: Colors.grey[300],
              )
            : null,
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (track.duration != null)
            Text(
              track.duration!,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          IconButton(
            icon: Icon(
              isCurrentTrack ? Icons.pause : Icons.play_arrow,
            ),
            onPressed: handleTrackTap,
          ),
        ],
      ),
      onTap: handleTrackTap,
    );

    if (isCurrentTrack) {
      return Container(
        color: Theme.of(context).primaryColor.withValues(alpha: 0.15),
        child: tile,
      );
    }
    return tile;
  }
}
