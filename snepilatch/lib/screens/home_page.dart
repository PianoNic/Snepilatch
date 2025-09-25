import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../models/playback_state.dart';

class HomePage extends StatelessWidget {
  final SpotifyController spotifyController;
  const HomePage({super.key, required this.spotifyController});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Home'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: AnimatedBuilder(
        animation: spotifyController,
        builder: (context, child) {
          return Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                _buildAlbumArt(context),
                const SizedBox(height: 24),
                _buildTrackInfo(),
                const SizedBox(height: 16),
                _buildLikeButton(),
                const SizedBox(height: 16),
                _buildPlaybackControls(context),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildAlbumArt(BuildContext context) {
    if (spotifyController.currentAlbumArt != null) {
      return Container(
        width: 200,
        height: 200,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          image: DecorationImage(
            image: NetworkImage(spotifyController.currentAlbumArt!),
            fit: BoxFit.cover,
          ),
        ),
      );
    }

    return Container(
      width: 200,
      height: 200,
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.primaryContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: const Icon(Icons.music_note, size: 64),
    );
  }

  Widget _buildTrackInfo() {
    return Column(
      children: [
        Text(
          spotifyController.currentTrack ?? 'No track playing',
          style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        Text(
          spotifyController.currentArtist ?? 'Open Spotify to start',
          style: const TextStyle(fontSize: 16),
          textAlign: TextAlign.center,
        ),
      ],
    );
  }

  Widget _buildLikeButton() {
    return IconButton(
      icon: Icon(
        spotifyController.isCurrentTrackLiked ? Icons.favorite : Icons.favorite_border,
        color: spotifyController.isCurrentTrackLiked ? Colors.green : null,
      ),
      iconSize: 32,
      onPressed: spotifyController.currentTrack != null
        ? () => spotifyController.toggleLike()
        : null,
    );
  }

  Widget _buildPlaybackControls(BuildContext context) {
    final shuffleMode = ShuffleModeExtension.fromString(spotifyController.shuffleMode);
    final repeatMode = RepeatModeExtension.fromString(spotifyController.repeatMode);

    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Column(
          children: [
            IconButton(
              icon: Icon(
                shuffleMode == ShuffleMode.enhanced
                  ? Icons.shuffle_on_outlined
                  : Icons.shuffle,
              ),
              iconSize: 32,
              onPressed: () => spotifyController.toggleShuffle(),
              color: shuffleMode == ShuffleMode.off
                ? Theme.of(context).colorScheme.secondary
                : shuffleMode == ShuffleMode.normal
                  ? Colors.green
                  : Colors.greenAccent,
            ),
            Text(
              shuffleMode == ShuffleMode.enhanced
                ? 'Enhanced'
                : shuffleMode == ShuffleMode.normal
                  ? 'On'
                  : 'Off',
              style: TextStyle(
                fontSize: 10,
                color: shuffleMode == ShuffleMode.off
                  ? Theme.of(context).colorScheme.secondary
                  : shuffleMode == ShuffleMode.normal
                    ? Colors.green
                    : Colors.greenAccent,
              ),
            ),
          ],
        ),
        IconButton(
          icon: const Icon(Icons.skip_previous),
          iconSize: 48,
          onPressed: () => spotifyController.previous(),
        ),
        IconButton(
          icon: Icon(
            spotifyController.isPlaying
              ? Icons.pause_circle_filled
              : Icons.play_circle_filled,
          ),
          iconSize: 64,
          color: Theme.of(context).colorScheme.primary,
          onPressed: () {
            if (spotifyController.isPlaying) {
              spotifyController.pause();
            } else {
              spotifyController.play();
            }
          },
        ),
        IconButton(
          icon: const Icon(Icons.skip_next),
          iconSize: 48,
          onPressed: () => spotifyController.next(),
        ),
        IconButton(
          icon: Icon(
            repeatMode == RepeatMode.one
              ? Icons.repeat_one
              : Icons.repeat,
          ),
          iconSize: 32,
          onPressed: () => spotifyController.toggleRepeat(),
          color: repeatMode != RepeatMode.off
            ? Colors.green
            : Theme.of(context).colorScheme.secondary,
        ),
      ],
    );
  }
}