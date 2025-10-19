import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../models/homepage_item.dart';
import '../models/song.dart';

class PlaylistDetailPage extends StatefulWidget {
  final HomepageItem playlist;
  final SpotifyController controller;

  const PlaylistDetailPage({
    super.key,
    required this.playlist,
    required this.controller,
  });

  @override
  State<PlaylistDetailPage> createState() => _PlaylistDetailPageState();
}

class _PlaylistDetailPageState extends State<PlaylistDetailPage> {
  List<Song> tracks = [];
  bool isLoading = true;
  String? playlistTitle;
  String? playlistCreator;
  String? playlistImage;

  @override
  void initState() {
    super.initState();
    _loadPlaylistDetails();
  }

  Future<void> _loadPlaylistDetails() async {
    try {
      // Navigate to the playlist in the WebView
      await widget.controller.navigateToHomepageItem(widget.playlist.href);

      // Wait for page to load
      await Future.delayed(const Duration(seconds: 2));

      // Fetch tracks from the playlist page
      final fetchedTracks = await widget.controller.fetchPlaylistTracks();

      if (mounted) {
        setState(() {
          tracks = fetchedTracks;
          playlistTitle = widget.playlist.title;
          playlistCreator = widget.playlist.subtitle;
          playlistImage = widget.playlist.imageUrl;
          isLoading = false;
        });
      }
    } catch (e) {
      debugPrint('Error loading playlist details: $e');
      if (mounted) {
        setState(() {
          isLoading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, child) {
        return Scaffold(
          appBar: AppBar(
            title: const Text('Playlist'),
            elevation: 0,
          ),
          body: isLoading
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              child: Column(
                children: [
                  // Playlist header
                  Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Playlist art
                        if (playlistImage != null && playlistImage!.isNotEmpty)
                          Center(
                            child: ClipRRect(
                              borderRadius: BorderRadius.circular(8),
                              child: Image.network(
                                playlistImage!,
                                width: 200,
                                height: 200,
                                fit: BoxFit.cover,
                                errorBuilder: (context, error, stackTrace) {
                                  return Container(
                                    width: 200,
                                    height: 200,
                                    color: Colors.grey[800],
                                    child: const Icon(
                                      Icons.playlist_play,
                                      size: 64,
                                      color: Colors.white54,
                                    ),
                                  );
                                },
                              ),
                            ),
                          ),
                        const SizedBox(height: 16),
                        // Playlist title
                        if (playlistTitle != null)
                          Text(
                            playlistTitle!,
                            style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                        if (playlistCreator != null) ...[
                          const SizedBox(height: 8),
                          Text(
                            playlistCreator!,
                            style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              color: Colors.grey[400],
                            ),
                          ),
                        ],
                        const SizedBox(height: 8),
                        Text(
                          '${tracks.length} songs',
                          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: Colors.grey[500],
                          ),
                        ),
                        const SizedBox(height: 16),
                        // Play button
                        SizedBox(
                          width: double.infinity,
                          child: ElevatedButton.icon(
                            icon: const Icon(Icons.play_arrow),
                            label: const Text('Play Playlist'),
                            onPressed: () async {
                              await widget.controller.playHomepageItem(
                                widget.playlist.id,
                                widget.playlist.type,
                              );
                            },
                          ),
                        ),
                      ],
                    ),
                  ),
                  const Divider(),
                  // Tracks list
                  if (tracks.isEmpty)
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
                      itemCount: tracks.length,
                      itemBuilder: (context, index) {
                        final track = tracks[index];
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
        track.artist.isNotEmpty ? track.artist : 'Unknown Artist',
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
