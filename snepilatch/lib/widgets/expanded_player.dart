import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../models/playback_state.dart';

class ExpandedPlayer extends StatelessWidget {
  final SpotifyController spotifyController;
  final Animation<double> animation;
  final VoidCallback onClose;

  const ExpandedPlayer({
    super.key,
    required this.spotifyController,
    required this.animation,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: animation,
      builder: (context, child) {
        return Positioned.fill(
          child: GestureDetector(
            onVerticalDragEnd: (details) {
              if (details.velocity.pixelsPerSecond.dy > 300) {
                onClose();
              }
            },
            child: Container(
              color: Colors.black.withValues(alpha: animation.value * 0.95),
              child: SafeArea(
                child: Column(
                  children: [
                    _buildHandleBar(),
                    _buildCloseButton(),
                    Expanded(
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.all(24.0),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            _buildAlbumArt(context),
                            const SizedBox(height: 32),
                            _buildTrackInfo(),
                            const SizedBox(height: 32),
                            _buildControls(),
                            const SizedBox(height: 24),
                            _buildLikeButton(),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildHandleBar() {
    return Container(
      margin: const EdgeInsets.only(top: 8, bottom: 16),
      width: 40,
      height: 4,
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.3),
        borderRadius: BorderRadius.circular(2),
      ),
    );
  }

  Widget _buildCloseButton() {
    return Row(
      children: [
        IconButton(
          icon: const Icon(Icons.keyboard_arrow_down, color: Colors.white),
          onPressed: onClose,
        ),
      ],
    );
  }

  Widget _buildAlbumArt(BuildContext context) {
    if (spotifyController.currentAlbumArt == null) {
      return const SizedBox.shrink();
    }

    return Container(
      width: MediaQuery.of(context).size.width * 0.8,
      height: MediaQuery.of(context).size.width * 0.8,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.3),
            blurRadius: 20,
            offset: const Offset(0, 10),
          ),
        ],
        image: DecorationImage(
          image: NetworkImage(spotifyController.currentAlbumArt!),
          fit: BoxFit.cover,
        ),
      ),
    );
  }

  Widget _buildTrackInfo() {
    return Column(
      children: [
        Text(
          spotifyController.currentTrack ?? '',
          style: const TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.bold,
            color: Colors.white,
          ),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        Text(
          spotifyController.currentArtist ?? '',
          style: const TextStyle(
            fontSize: 18,
            color: Colors.white70,
          ),
          textAlign: TextAlign.center,
        ),
      ],
    );
  }

  Widget _buildControls() {
    final shuffleMode = ShuffleModeExtension.fromString(spotifyController.shuffleMode);
    final repeatMode = RepeatModeExtension.fromString(spotifyController.repeatMode);

    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        IconButton(
          icon: Icon(
            shuffleMode == ShuffleMode.enhanced
              ? Icons.shuffle_on_outlined
              : Icons.shuffle,
            color: shuffleMode == ShuffleMode.off
              ? Colors.white54
              : shuffleMode == ShuffleMode.normal
                ? Colors.green
                : Colors.greenAccent,
          ),
          iconSize: 32,
          onPressed: () => spotifyController.toggleShuffle(),
        ),
        IconButton(
          icon: const Icon(Icons.skip_previous, color: Colors.white),
          iconSize: 48,
          onPressed: () => spotifyController.previous(),
        ),
        IconButton(
          icon: Icon(
            spotifyController.isPlaying
              ? Icons.pause_circle_filled
              : Icons.play_circle_filled,
            color: Colors.white,
          ),
          iconSize: 72,
          onPressed: () {
            if (spotifyController.isPlaying) {
              spotifyController.pause();
            } else {
              spotifyController.play();
            }
          },
        ),
        IconButton(
          icon: const Icon(Icons.skip_next, color: Colors.white),
          iconSize: 48,
          onPressed: () => spotifyController.next(),
        ),
        IconButton(
          icon: Icon(
            repeatMode == RepeatMode.one
              ? Icons.repeat_one
              : Icons.repeat,
            color: repeatMode != RepeatMode.off
              ? Colors.green
              : Colors.white54,
          ),
          iconSize: 32,
          onPressed: () => spotifyController.toggleRepeat(),
        ),
      ],
    );
  }

  Widget _buildLikeButton() {
    return IconButton(
      icon: Icon(
        spotifyController.isCurrentTrackLiked
          ? Icons.favorite
          : Icons.favorite_border,
        color: spotifyController.isCurrentTrackLiked
          ? Colors.green
          : Colors.white,
      ),
      iconSize: 32,
      onPressed: () => spotifyController.toggleLike(),
    );
  }
}