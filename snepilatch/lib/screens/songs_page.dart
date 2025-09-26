import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../models/song.dart';

class SongsPage extends StatefulWidget {
  final SpotifyController spotifyController;
  const SongsPage({super.key, required this.spotifyController});

  @override
  State<SongsPage> createState() => _SongsPageState();
}

class _SongsPageState extends State<SongsPage> {
  final ScrollController _scrollController = ScrollController();
  bool _hasNavigated = false;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Navigate to liked songs when tab becomes visible
    if (!_hasNavigated && widget.spotifyController.isInitialized) {
      _hasNavigated = true;
      widget.spotifyController.navigateToLikedSongs();
    }
  }

  void _onScroll() {
    if (_scrollController.position.pixels >= _scrollController.position.maxScrollExtent - 200) {
      // User scrolled near the bottom, load more songs
      widget.spotifyController.loadMoreSongs();
    }

    // Sync scroll position with Spotify
    widget.spotifyController.scrollSpotifyPage(_scrollController.position.pixels);
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
        animation: widget.spotifyController,
        builder: (context, child) {
          if (widget.spotifyController.isLoadingSongs && widget.spotifyController.songs.isEmpty) {
            return const Center(
              child: CircularProgressIndicator(),
            );
          }

          if (widget.spotifyController.songs.isEmpty) {
            return _buildEmptyState(context);
          }

          return _buildSongsList();
        },
      );
  }

  Widget _buildEmptyState(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.music_note,
            size: 64,
            color: Theme.of(context).colorScheme.primary,
          ),
          const SizedBox(height: 16),
          const Text(
            'No songs found',
            style: TextStyle(fontSize: 18),
          ),
          const SizedBox(height: 8),
          ElevatedButton(
            onPressed: () => widget.spotifyController.navigateToLikedSongs(),
            child: const Text('Load Songs'),
          ),
        ],
      ),
    );
  }

  Widget _buildSongsList() {
    return ListView.builder(
      controller: _scrollController,
      itemCount: widget.spotifyController.songs.length +
                (widget.spotifyController.isLoadingSongs ? 1 : 0),
      itemBuilder: (context, index) {
        if (index == widget.spotifyController.songs.length) {
          return const Center(
            child: Padding(
              padding: EdgeInsets.all(16.0),
              child: CircularProgressIndicator(),
            ),
          );
        }

        final song = widget.spotifyController.songs[index];
        return _buildSongTile(context, song);
      },
    );
  }

  String _buildSubtitle(Song song) {
    final parts = <String>[];

    if (song.artist != null && song.artist!.isNotEmpty) {
      parts.add(song.artist!);
    }

    if (song.album != null && song.album!.isNotEmpty) {
      parts.add(song.album!);
    }

    // If we have both artist and album, join with bullet
    // If only one, return it alone
    // If neither, return a default text
    if (parts.isEmpty) {
      return 'Unknown Artist';
    }
    return parts.join(' • ');
  }

  Widget _buildSongTile(BuildContext context, Song song) {
    return ListTile(
      leading: _buildAlbumArt(context, song.imageUrl),
      title: Text(
        song.title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(
        _buildSubtitle(song),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (song.duration != null)
            Text(
              song.duration!,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          IconButton(
            icon: const Icon(Icons.play_arrow),
            onPressed: () {
              widget.spotifyController.playTrackAtIndex(song.index);
            },
          ),
        ],
      ),
      onTap: () {
        widget.spotifyController.playTrackAtIndex(song.index);
      },
    );
  }

  Widget _buildAlbumArt(BuildContext context, String? imageUrl) {
    return Container(
      width: 48,
      height: 48,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(4),
        color: Theme.of(context).colorScheme.primaryContainer,
      ),
      child: imageUrl != null && imageUrl.isNotEmpty
          ? ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: Image.network(
                imageUrl,
                fit: BoxFit.cover,
                errorBuilder: (context, error, stackTrace) {
                  return const Icon(Icons.music_note);
                },
              ),
            )
          : const Icon(Icons.music_note),
    );
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }
}