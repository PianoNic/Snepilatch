import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';

class MiniPlayer extends StatelessWidget {
  final SpotifyController spotifyController;
  final VoidCallback onTap;
  final VoidCallback onVerticalDragUp;

  const MiniPlayer({
    super.key,
    required this.spotifyController,
    required this.onTap,
    required this.onVerticalDragUp,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onVerticalDragEnd: (details) {
        if (details.velocity.pixelsPerSecond.dy < -300) {
          onVerticalDragUp();
        }
      },
      onTap: onTap,
      child: Container(
        height: 72,
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [
              Theme.of(context).colorScheme.surface,
              Theme.of(context).colorScheme.surface.withValues(alpha: 0.98),
            ],
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.3),
              blurRadius: 10,
              offset: const Offset(0, -2),
            ),
          ],
        ),
        child: Column(
          children: [
            // Progress bar
            _buildProgressBar(context),
            // Main content
            Expanded(
              child: Row(
                children: [
                  // Album art with animation
                  _buildAlbumArt(context),
                  const SizedBox(width: 12),
                  // Track info
                  Expanded(
                    child: _buildTrackInfo(context),
                  ),
                  // Control buttons
                  _buildControls(context),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildProgressBar(BuildContext context) {
    final progress = spotifyController.progressPercentage;

    return Stack(
      children: [
        // Background track
        Container(
          height: 2,
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.1),
          ),
        ),
        // Progress indicator
        AnimatedContainer(
          duration: const Duration(milliseconds: 500),
          height: 2,
          width: MediaQuery.of(context).size.width * progress,
          decoration: BoxDecoration(
            gradient: LinearGradient(
              colors: [
                Theme.of(context).colorScheme.primary,
                Theme.of(context).colorScheme.secondary,
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildAlbumArt(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      width: 56,
      height: 56,
      margin: const EdgeInsets.only(left: 8),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(8),
        boxShadow: spotifyController.isPlaying
            ? [
                BoxShadow(
                  color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.3),
                  blurRadius: 8,
                  spreadRadius: 2,
                ),
              ]
            : [],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: spotifyController.currentAlbumArt != null
            ? Image.network(
                spotifyController.currentAlbumArt!,
                fit: BoxFit.cover,
                errorBuilder: (context, error, stackTrace) {
                  return _buildPlaceholderArt(context);
                },
              )
            : _buildPlaceholderArt(context),
      ),
    );
  }

  Widget _buildPlaceholderArt(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Theme.of(context).colorScheme.primaryContainer,
            Theme.of(context).colorScheme.secondaryContainer,
          ],
        ),
      ),
      child: Icon(
        Icons.music_note,
        color: Theme.of(context).colorScheme.onPrimaryContainer,
        size: 28,
      ),
    );
  }

  Widget _buildTrackInfo(BuildContext context) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            if (spotifyController.isCurrentTrackLiked)
              Icon(
                Icons.favorite,
                color: Colors.green,
                size: 14,
              ),
            if (spotifyController.isCurrentTrackLiked)
              const SizedBox(width: 4),
            Expanded(
              child: Text(
                spotifyController.currentTrack ?? 'No track playing',
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
        const SizedBox(height: 2),
        Row(
          children: [
            Expanded(
              child: Text(
                spotifyController.currentArtist ?? 'Unknown artist',
                style: TextStyle(
                  fontSize: 12,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const SizedBox(width: 8),
            Text(
              '${spotifyController.currentTime} / ${spotifyController.duration}',
              style: TextStyle(
                fontSize: 10,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildControls(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        // Previous button
        IconButton(
          icon: const Icon(Icons.skip_previous),
          iconSize: 28,
          onPressed: () => spotifyController.previous(),
          tooltip: 'Previous',
        ),
        // Play/pause button with animation
        AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          child: IconButton(
            icon: AnimatedSwitcher(
              duration: const Duration(milliseconds: 200),
              child: Icon(
                spotifyController.isPlaying ? Icons.pause : Icons.play_arrow,
                key: ValueKey(spotifyController.isPlaying),
              ),
            ),
            iconSize: 32,
            onPressed: () {
              if (spotifyController.isPlaying) {
                spotifyController.pause();
              } else {
                spotifyController.play();
              }
            },
            tooltip: spotifyController.isPlaying ? 'Pause' : 'Play',
          ),
        ),
        // Next button
        IconButton(
          icon: const Icon(Icons.skip_next),
          iconSize: 28,
          onPressed: () => spotifyController.next(),
          tooltip: 'Next',
        ),
        const SizedBox(width: 8),
      ],
    );
  }
}