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

class _ExpandedPlayerState extends State<ExpandedPlayer> with TickerProviderStateMixin {
  double _dragOffset = 0;
  double? _draggedProgress;
  bool _isDraggingSlider = false;

  // For text scrolling animation
  late AnimationController _scrollController;
  late Animation<double> _scrollAnimation;
  bool _shouldScroll = false;
  final GlobalKey _textKey = GlobalKey();

  @override
  void initState() {
    super.initState();
    _scrollController = AnimationController(
      duration: const Duration(seconds: 6),
      vsync: this,
    );

    // Simple linear animation from 0 to 1
    _scrollAnimation = Tween<double>(
      begin: 0.0,
      end: 1.0,
    ).animate(_scrollController);

    // Loop the animation
    _scrollController.addStatusListener((status) {
      if (status == AnimationStatus.completed && _shouldScroll) {
        Future.delayed(const Duration(seconds: 2), () {
          if (_shouldScroll && mounted) {
            _scrollController.forward(from: 0);
          }
        });
      }
    });

    // Check if text needs scrolling when track changes
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkTextOverflow();
    });

    // Listen to track changes
    widget.spotifyController.addListener(_onTrackChanged);
  }

  @override
  void dispose() {
    _scrollController.dispose();
    widget.spotifyController.removeListener(_onTrackChanged);
    super.dispose();
  }

  void _onTrackChanged() {
    // Check if we need to scroll when track changes
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkTextOverflow();
    });
  }

  void _checkTextOverflow() {
    if (!mounted) return;

    final trackName = widget.spotifyController.currentTrack ?? '';
    if (trackName.isEmpty || trackName == 'No track playing') {
      setState(() {
        _shouldScroll = false;
      });
      _scrollController.stop();
      _scrollController.reset();
      return;
    }

    final TextPainter textPainter = TextPainter(
      text: TextSpan(
        text: trackName,
        style: const TextStyle(
          fontSize: 22,
          fontWeight: FontWeight.bold,
        ),
      ),
      maxLines: 1,
      textDirection: TextDirection.ltr,
    )..layout(minWidth: 0, maxWidth: double.infinity);

    final textWidth = textPainter.width;
    // Account for padding (24*2) and heart button (48) and some extra space
    final availableWidth = MediaQuery.of(context).size.width - 120;

    if (textWidth > availableWidth) {
      if (!_shouldScroll) {
        setState(() {
          _shouldScroll = true;
        });
        // Reset and start animation after a delay
        _scrollController.stop();
        _scrollController.reset();
        Future.delayed(const Duration(seconds: 2), () {
          if (_shouldScroll && mounted) {
            _scrollController.repeat();
          }
        });
      }
    } else {
      if (_shouldScroll) {
        setState(() {
          _shouldScroll = false;
        });
        _scrollController.stop();
        _scrollController.reset();
      }
    }
  }

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
      animation: Listenable.merge([
        widget.animation,
        widget.spotifyController,
      ]),
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
    final trackName = widget.spotifyController.currentTrack ?? 'No track playing';

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          height: 30,
          child: Stack(
            alignment: Alignment.center,
            children: [
              // Centered track title with scrolling animation
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 60.0), // Leave space for heart button
                child: ClipRect(
                  child: _shouldScroll
                      ? LayoutBuilder(
                          builder: (context, constraints) {
                            return AnimatedBuilder(
                              animation: _scrollController,
                              builder: (context, child) {
                                final textPainter = TextPainter(
                                  text: TextSpan(
                                    text: trackName,
                                    style: const TextStyle(
                                      fontSize: 22,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                  maxLines: 1,
                                  textDirection: TextDirection.ltr,
                                )..layout();

                                final textWidth = textPainter.width;
                                final scrollExtent = textWidth + 100; // Add space between repeats

                                // Calculate offset for scrolling effect
                                final offset = _scrollController.value * scrollExtent;

                                return ClipRect(
                                  child: SingleChildScrollView(
                                    scrollDirection: Axis.horizontal,
                                    physics: const NeverScrollableScrollPhysics(),
                                    child: Transform.translate(
                                      offset: Offset(-offset, 0),
                                      child: Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          Text(
                                            trackName,
                                            key: _textKey,
                                            style: const TextStyle(
                                              fontSize: 22,
                                              fontWeight: FontWeight.bold,
                                              color: Colors.white,
                                            ),
                                            maxLines: 1,
                                          ),
                                          const SizedBox(width: 100),
                                          Text(
                                            trackName,
                                            style: const TextStyle(
                                              fontSize: 22,
                                              fontWeight: FontWeight.bold,
                                              color: Colors.white,
                                            ),
                                            maxLines: 1,
                                          ),
                                          const SizedBox(width: 100), // Extra space for smooth loop
                                        ],
                                      ),
                                    ),
                                  ),
                                );
                              },
                            );
                          },
                        )
                      : Center(
                          child: Text(
                            trackName,
                            key: _textKey,
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
                ),
              ),
              // Like button positioned on the right
              Positioned(
                right: 24,
                child: IconButton(
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
              ),
            ],
          ),
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
    final progress = _isDraggingSlider
        ? (_draggedProgress ?? widget.spotifyController.progressPercentage)
        : widget.spotifyController.progressPercentage;
    final currentTime = widget.spotifyController.currentTime;
    final duration = widget.spotifyController.duration;
    final durationMs = widget.spotifyController.durationMs;

    // Calculate dragged time if dragging
    String displayTime = currentTime;
    if (_isDraggingSlider && _draggedProgress != null) {
      final draggedMs = (_draggedProgress! * durationMs).round();
      final seconds = (draggedMs ~/ 1000) % 60;
      final minutes = draggedMs ~/ 60000;
      displayTime = '$minutes:${seconds.toString().padLeft(2, '0')}';
    }

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        // Interactive progress bar with slider
        SliderTheme(
          data: SliderTheme.of(context).copyWith(
            activeTrackColor: Colors.white,
            inactiveTrackColor: Colors.white24,
            thumbColor: Colors.white,
            thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
            trackHeight: 4,
            overlayColor: Colors.white.withValues(alpha: 0.2),
          ),
          child: Slider(
            value: progress,
            onChangeStart: (value) {
              setState(() {
                _isDraggingSlider = true;
                _draggedProgress = value;
              });
            },
            onChanged: (value) {
              setState(() {
                _draggedProgress = value;
              });
            },
            onChangeEnd: (value) {
              // Actually seek when user releases
              widget.spotifyController.seekToPosition(value);
              setState(() {
                _isDraggingSlider = false;
                _draggedProgress = null;
              });
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
                displayTime,
                style: TextStyle(
                  color: Colors.white.withValues(alpha: 0.7),
                  fontSize: 12,
                ),
              ),
              Text(
                duration,
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