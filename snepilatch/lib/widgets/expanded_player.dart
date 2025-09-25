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
  final ScrollController _scrollController = ScrollController();
  double _dragOffset = 0;

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  void _handleDragUpdate(DragUpdateDetails details) {
    // Only allow dragging down when scrolled to the top
    if (_scrollController.hasClients && _scrollController.offset <= 0) {
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
            child: Container(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.black.withValues(alpha: 0.95),
                    Colors.black.withValues(alpha: 0.98),
                  ],
                ),
              ),
              child: SafeArea(
                child: Column(
                  children: [
                    // Draggable header
                    GestureDetector(
                      onVerticalDragUpdate: _handleDragUpdate,
                      onVerticalDragEnd: _handleDragEnd,
                      child: _buildHeader(context),
                    ),
                    // Scrollable content
                    Expanded(
                      child: NotificationListener<ScrollNotification>(
                        onNotification: (notification) {
                          // Allow drag to close only when scrolled to top
                          if (notification is OverscrollNotification &&
                              notification.overscroll < 0) {
                            // User is trying to scroll up beyond the top
                            if (notification.dragDetails != null) {
                              _handleDragUpdate(notification.dragDetails!);
                            }
                          }
                          return false;
                        },
                        child: SingleChildScrollView(
                          controller: _scrollController,
                          physics: const AlwaysScrollableScrollPhysics(),
                          padding: const EdgeInsets.symmetric(horizontal: 24.0),
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const SizedBox(height: 20),
                              _buildAlbumArt(context),
                              const SizedBox(height: 40),
                              _buildTrackInfo(),
                              const SizedBox(height: 32),
                              _buildProgressBar(context),
                              const SizedBox(height: 32),
                              _buildMainControls(),
                              const SizedBox(height: 100), // Extra space at bottom
                            ],
                          ),
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

  Widget _buildHeader(BuildContext context) {
    return Container(
      color: Colors.transparent,
      child: Column(
        children: [
          // Handle bar
          Container(
            margin: const EdgeInsets.only(top: 8, bottom: 8),
            width: 40,
            height: 4,
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.3),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          // Header with close button and menu
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8.0),
            child: Row(
              children: [
                IconButton(
                  icon: const Icon(Icons.keyboard_arrow_down, color: Colors.white, size: 32),
                  onPressed: widget.onClose,
                  tooltip: 'Minimize',
                ),
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

    return Hero(
      tag: 'album_art',
      child: Container(
        width: MediaQuery.of(context).size.width * 0.75,
        height: MediaQuery.of(context).size.width * 0.75,
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
    return Container(
      width: MediaQuery.of(context).size.width * 0.75,
      height: MediaQuery.of(context).size.width * 0.75,
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
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Expanded(
              child: Text(
                widget.spotifyController.currentTrack ?? 'No track playing',
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                ),
                textAlign: TextAlign.center,
                maxLines: 2,
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
              iconSize: 28,
              onPressed: () => widget.spotifyController.toggleLike(),
              tooltip: widget.spotifyController.isCurrentTrackLiked ? 'Unlike' : 'Like',
            ),
          ],
        ),
        const SizedBox(height: 8),
        Text(
          widget.spotifyController.currentArtist ?? 'Unknown artist',
          style: const TextStyle(
            fontSize: 18,
            color: Colors.white70,
          ),
          textAlign: TextAlign.center,
        ),
      ],
    );
  }

  Widget _buildProgressBar(BuildContext context) {
    return Column(
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
          iconSize: 28,
          onPressed: () => widget.spotifyController.toggleShuffle(),
          tooltip: 'Shuffle: ${shuffleMode.value}',
        ),
        const SizedBox(width: 16),
        // Previous button
        IconButton(
          icon: const Icon(Icons.skip_previous, color: Colors.white),
          iconSize: 36,
          onPressed: () => widget.spotifyController.previous(),
          tooltip: 'Previous',
        ),
        const SizedBox(width: 16),
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
            iconSize: 40,
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
        const SizedBox(width: 16),
        // Next button
        IconButton(
          icon: const Icon(Icons.skip_next, color: Colors.white),
          iconSize: 36,
          onPressed: () => widget.spotifyController.next(),
          tooltip: 'Next',
        ),
        const SizedBox(width: 16),
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
          iconSize: 28,
          onPressed: () => widget.spotifyController.toggleRepeat(),
          tooltip: 'Repeat: ${repeatMode.value}',
        ),
      ],
    );
  }
}