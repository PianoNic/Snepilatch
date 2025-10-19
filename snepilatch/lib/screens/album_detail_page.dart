import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../models/homepage_item.dart';
import '../models/song.dart';

class AlbumDetailPage extends StatefulWidget {
  final HomepageItem album;
  final SpotifyController controller;

  const AlbumDetailPage({
    super.key,
    required this.album,
    required this.controller,
  });

  @override
  State<AlbumDetailPage> createState() => _AlbumDetailPageState();
}

class _AlbumDetailPageState extends State<AlbumDetailPage> {
  List<Song> tracks = [];
  bool isLoading = true;
  String? albumTitle;
  String? artistName;
  String? albumImage;

  @override
  void initState() {
    super.initState();
    _loadAlbumDetails();
  }

  Future<void> _loadAlbumDetails() async {
    try {
      // Navigate to the album in the WebView
      await widget.controller.navigateToHomepageItem(widget.album.href);

      // Wait for page to load
      await Future.delayed(const Duration(seconds: 2));

      // Fetch tracks from the album page
      final fetchedTracks = await widget.controller.fetchAlbumTracks();

      if (mounted) {
        setState(() {
          tracks = fetchedTracks;
          albumTitle = widget.album.title;
          artistName = widget.album.subtitle;
          albumImage = widget.album.imageUrl;
          isLoading = false;
        });
      }
    } catch (e) {
      debugPrint('Error loading album details: $e');
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
            title: const Text('Album'),
            elevation: 0,
          ),
          body: isLoading
              ? const Center(child: CircularProgressIndicator())
              : SingleChildScrollView(
              child: Column(
                children: [
                  // Album header
                  Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Album art
                        if (albumImage != null && albumImage!.isNotEmpty)
                          Center(
                            child: ClipRRect(
                              borderRadius: BorderRadius.circular(8),
                              child: Image.network(
                                albumImage!,
                                width: 200,
                                height: 200,
                                fit: BoxFit.cover,
                                errorBuilder: (context, error, stackTrace) {
                                  return Container(
                                    width: 200,
                                    height: 200,
                                    color: Colors.grey[800],
                                    child: const Icon(
                                      Icons.album,
                                      size: 64,
                                      color: Colors.white54,
                                    ),
                                  );
                                },
                              ),
                            ),
                          ),
                        const SizedBox(height: 16),
                        // Album title
                        if (albumTitle != null)
                          Text(
                            albumTitle!,
                            style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                        if (artistName != null) ...[
                          const SizedBox(height: 8),
                          Text(
                            artistName!,
                            style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              color: Colors.grey[400],
                            ),
                          ),
                        ],
                        const SizedBox(height: 16),
                        // Play button
                        SizedBox(
                          width: double.infinity,
                          child: ElevatedButton.icon(
                            icon: const Icon(Icons.play_arrow),
                            label: const Text('Play Album'),
                            onPressed: () async {
                              await widget.controller.playHomepageItem(
                                widget.album.id,
                                widget.album.type,
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
