import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../models/playback_state.dart';

class ExpandedPlayer extends StatefulWidget {
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
  State<ExpandedPlayer> createState() => _ExpandedPlayerState();
}

class _ExpandedPlayerState extends State<ExpandedPlayer> {
  double _dragOffset = 0;

  void _handleDragUpdate(DragUpdateDetails details) {
    // Allow dragging down to close
    if (details.delta.dy > 0) {
      setState(() {
        _dragOffset = (_dragOffset + details.delta.dy).clamp(0, double.infinity);
      });
    }
  }

  void _handleDragEnd(DragEndDetails details) {
    if (_dragOffset > 100 || details.velocity.pixelsPerSecond.dy > 300) {
      // Close if dragged far enough or with enough velocity
      widget.onClose();
    } else {
      // Snap back
      setState(() {
        _dragOffset = 0;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final screenHeight = MediaQuery.of(context).size.height;

    return AnimatedBuilder(
      animation: widget.animation,
      builder: (context, child) {
        // Slide up from bottom animation
        final slideOffset = Offset(0, (1 - widget.animation.value) * screenHeight + _dragOffset);

        return Positioned.fill(
          child: Transform.translate(
            offset: slideOffset,
            child: Material(
              color: Colors.transparent,
              child: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [
                      Theme.of(context).colorScheme.surface.withValues(alpha: 0.95),
                      Theme.of(context).colorScheme.surface,
                    ],
                  ),
                ),
                child: SafeArea(
                  child: GestureDetector(
                    onVerticalDragUpdate: _handleDragUpdate,
                    onVerticalDragEnd: _handleDragEnd,
                    child: Column(
                    children: [
                      // Draggable header with handle bar
                      _buildHeader(context),
                      // Content - no scrolling, everything fits on one page
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 24.0),
                          child: Column(
                            children: [
                              // Album art
                              Expanded(
                                flex: 5,
                                child: Center(
                                  child: _buildAlbumArt(context),
                                ),
                              ),
                              // Track info
                              _buildTrackInfo(),
                              const SizedBox(height: 20),
                              // Progress bar
                              _buildProgressBar(context),
                              const SizedBox(height: 24),
                              // Main controls
                              _buildMainControls(),
                              const SizedBox(height: 40),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          ),
        );
      },
    );
  }

  Widget _buildHeader(BuildContext context) {
    return Container(
      color: Colors.transparent,
      padding: const EdgeInsets.only(top: 8, bottom: 8),
      child: Column(
        children: [
          // Handle bar for dragging
          Container(
            margin: const EdgeInsets.only(bottom: 8),
            width: 40,
            height: 4,
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.3),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          // Header with title and menu (no arrow button)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0),
            child: Row(
              children: [
                const SizedBox(width: 48), // Balance the layout
                const Spacer(),
                Text(
                  'NOW PLAYING',
                  style: TextStyle(
                    color: Colors.white.withValues(alpha: 0.7),
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 1.2,
                  ),
                ),
                const Spacer(),
                PopupMenuButton<String>(
                  icon: const Icon(Icons.more_vert, color: Colors.white),
                  color: Colors.grey[900],
                  onSelected: (value) {
                    switch (value) {
                      case 'open_spotify':
                        widget.spotifyController.openWebView();
                        break;
                      case 'view_album':
                      case 'view_artist':
                      case 'share':
                        // These would open specific views in Spotify
                        widget.spotifyController.openWebView();
                        break;
                    }
                  },
                  itemBuilder: (context) => [
                    const PopupMenuItem(
                      value: 'open_spotify',
                      child: Text('Open in Spotify', style: TextStyle(color: Colors.white)),
                    ),
                    const PopupMenuItem(
                      value: 'view_album',
                      child: Text('View Album', style: TextStyle(color: Colors.white)),
                    ),
                    const PopupMenuItem(
                      value: 'view_artist',
                      child: Text('View Artist', style: TextStyle(color: Colors.white)),
                    ),
                    const PopupMenuItem(
                      value: 'share',
                      child: Text('Share', style: TextStyle(color: Colors.white)),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAlbumArt(BuildContext context) {
    if (widget.spotifyController.currentAlbumArt == null) {
      return _buildPlaceholderArt(context);
    }

    // Responsive size based on screen height
    final screenHeight = MediaQuery.of(context).size.height;
    final size = screenHeight * 0.35; // Smaller to fit everything on screen

    return Hero(
      tag: 'album_art',
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.5),
              blurRadius: 30,
              offset: const Offset(0, 15),
            ),
            BoxShadow(
              color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.2),
              blurRadius: 40,
              spreadRadius: 10,
            ),
          ],
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(16),
          child: Image.network(
            widget.spotifyController.currentAlbumArt!,
            fit: BoxFit.cover,
            errorBuilder: (context, error, stackTrace) => _buildPlaceholderArt(context),
          ),
        ),
      ),
    );
  }

  Widget _buildPlaceholderArt(BuildContext context) {
    final screenHeight = MediaQuery.of(context).size.height;
    final size = screenHeight * 0.35;

    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Theme.of(context).colorScheme.primary.withValues(alpha: 0.3),
            Theme.of(context).colorScheme.secondary.withValues(alpha: 0.3),
          ],
        ),
      ),
      child: const Icon(
        Icons.music_note,
        color: Colors.white54,
        size: 80,
      ),
    );
  }

  Widget _buildTrackInfo() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Expanded(
              child: Text(
                widget.spotifyController.currentTrack ?? 'No track playing',
                style: const TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                ),
                textAlign: TextAlign.center,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const SizedBox(width: 8),
            IconButton(
              icon: Icon(
                widget.spotifyController.isCurrentTrackLiked
                  ? Icons.favorite
                  : Icons.favorite_border,
                color: widget.spotifyController.isCurrentTrackLiked
                  ? Colors.green
                  : Colors.white70,
              ),
              iconSize: 26,
              onPressed: () => widget.spotifyController.toggleLike(),
              tooltip: widget.spotifyController.isCurrentTrackLiked ? 'Unlike' : 'Like',
            ),
          ],
        ),
        Text(
          widget.spotifyController.currentArtist ?? 'Unknown artist',
          style: const TextStyle(
            fontSize: 16,
            color: Colors.white70,
          ),
          textAlign: TextAlign.center,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ],
    );
  }

  Widget _buildProgressBar(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        // Progress slider
        SliderTheme(
          data: SliderTheme.of(context).copyWith(
            activeTrackColor: Colors.white,
            inactiveTrackColor: Colors.white24,
            thumbColor: Colors.white,
            thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
            trackHeight: 3,
            overlayColor: Colors.white.withValues(alpha: 0.2),
          ),
          child: Slider(
            value: 0.3, // This would be connected to actual playback position
            onChanged: (value) {
              // Handle seeking
            },
          ),
        ),
        // Time labels
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24.0),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '1:23', // Current time
                style: TextStyle(
                  color: Colors.white.withValues(alpha: 0.7),
                  fontSize: 12,
                ),
              ),
              Text(
                '3:45', // Total duration
                style: TextStyle(
                  color: Colors.white.withValues(alpha: 0.7),
                  fontSize: 12,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildMainControls() {
    final shuffleMode = ShuffleModeExtension.fromString(widget.spotifyController.shuffleMode);
    final repeatMode = RepeatModeExtension.fromString(widget.spotifyController.repeatMode);

    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        // Shuffle button
        IconButton(
          icon: Icon(
            shuffleMode == ShuffleMode.enhanced
              ? Icons.shuffle_on_outlined
              : Icons.shuffle,
            color: shuffleMode == ShuffleMode.off
              ? Colors.white38
              : shuffleMode == ShuffleMode.normal
                ? Colors.green
                : Colors.greenAccent,
          ),
          iconSize: 24,
          onPressed: () => widget.spotifyController.toggleShuffle(),
          tooltip: 'Shuffle: ${shuffleMode.value}',
        ),
        const SizedBox(width: 20),
        // Previous button
        IconButton(
          icon: const Icon(Icons.skip_previous, color: Colors.white),
          iconSize: 32,
          onPressed: () => widget.spotifyController.previous(),
          tooltip: 'Previous',
        ),
        const SizedBox(width: 20),
        // Play/pause button
        Container(
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: Colors.white,
            boxShadow: [
              BoxShadow(
                color: Colors.white.withValues(alpha: 0.3),
                blurRadius: 20,
                spreadRadius: 5,
              ),
            ],
          ),
          child: IconButton(
            icon: AnimatedSwitcher(
              duration: const Duration(milliseconds: 200),
              child: Icon(
                widget.spotifyController.isPlaying
                  ? Icons.pause
                  : Icons.play_arrow,
                color: Colors.black,
                key: ValueKey(widget.spotifyController.isPlaying),
              ),
            ),
            iconSize: 36,
            onPressed: () {
              if (widget.spotifyController.isPlaying) {
                widget.spotifyController.pause();
              } else {
                widget.spotifyController.play();
              }
            },
            tooltip: widget.spotifyController.isPlaying ? 'Pause' : 'Play',
          ),
        ),
        const SizedBox(width: 20),
        // Next button
        IconButton(
          icon: const Icon(Icons.skip_next, color: Colors.white),
          iconSize: 32,
          onPressed: () => widget.spotifyController.next(),
          tooltip: 'Next',
        ),
        const SizedBox(width: 20),
        // Repeat button
        IconButton(
          icon: Icon(
            repeatMode == RepeatMode.one
              ? Icons.repeat_one
              : Icons.repeat,
            color: repeatMode != RepeatMode.off
              ? Colors.green
              : Colors.white38,
          ),
          iconSize: 24,
          onPressed: () => widget.spotifyController.toggleRepeat(),
          tooltip: 'Repeat: ${repeatMode.value}',
        ),
      ],
    );
  }
}