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
        height: 64,
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surface,
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.2),
              blurRadius: 8,
              offset: const Offset(0, -2),
            ),
          ],
        ),
        child: Row(
          children: [
            // Album art
            if (spotifyController.currentAlbumArt != null)
              Container(
                width: 64,
                height: 64,
                decoration: BoxDecoration(
                  image: DecorationImage(
                    image: NetworkImage(spotifyController.currentAlbumArt!),
                    fit: BoxFit.cover,
                  ),
                ),
              ),
            const SizedBox(width: 12),
            // Track info
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    spotifyController.currentTrack ?? '',
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  Text(
                    spotifyController.currentArtist ?? '',
                    style: TextStyle(
                      fontSize: 12,
                      color: Theme.of(context).colorScheme.secondary,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
            // Play/pause button
            IconButton(
              icon: Icon(
                spotifyController.isPlaying ? Icons.pause : Icons.play_arrow,
              ),
              onPressed: () {
                if (spotifyController.isPlaying) {
                  spotifyController.pause();
                } else {
                  spotifyController.play();
                }
              },
            ),
            // Next button
            IconButton(
              icon: const Icon(Icons.skip_next),
              onPressed: () => spotifyController.next(),
            ),
            const SizedBox(width: 8),
          ],
        ),
      ),
    );
  }
}