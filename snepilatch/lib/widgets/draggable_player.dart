import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../widgets/mini_player.dart';
import '../widgets/expanded_player.dart';

class DraggablePlayer extends StatefulWidget {
  final SpotifyController spotifyController;

  const DraggablePlayer({
    super.key,
    required this.spotifyController,
  });

  @override
  State<DraggablePlayer> createState() => _DraggablePlayerState();
}

class _DraggablePlayerState extends State<DraggablePlayer> with TickerProviderStateMixin {
  // Animation controllers
  late AnimationController _positionController;
  late Animation<double> _positionAnimation;

  // Drawer state
  double _currentPosition = 0.0; // 0 = closed (mini), 1 = fully expanded
  bool _isDragging = false;
  double _dragStartPosition = 0.0;
  double _dragStartOffset = 0.0;

  // Snap points
  static const double _miniPlayerSnapPoint = 0.0;
  static const double _expandedPlayerSnapPoint = 1.0;

  @override
  void initState() {
    super.initState();
    _positionController = AnimationController(
      duration: const Duration(milliseconds: 300),
      vsync: this,
    );

    _positionAnimation = Tween<double>(
      begin: 0.0,
      end: 0.0,
    ).animate(CurvedAnimation(
      parent: _positionController,
      curve: Curves.easeOutCubic,
    ));

    _positionAnimation.addListener(() {
      setState(() {
        _currentPosition = _positionAnimation.value;
      });
    });
  }

  @override
  void dispose() {
    _positionController.dispose();
    super.dispose();
  }

  void _handleVerticalDragStart(DragStartDetails details) {
    setState(() {
      _isDragging = true;
      _dragStartPosition = _currentPosition;
      _dragStartOffset = details.globalPosition.dy;
    });
    _positionController.stop();
  }

  void _handleVerticalDragUpdate(DragUpdateDetails details) {
    if (!_isDragging) return;

    final screenHeight = MediaQuery.of(context).size.height;
    final availableHeight = screenHeight - 200; // Reserve space for mini player

    // Calculate drag distance as a percentage of available height
    final dragDelta = (_dragStartOffset - details.globalPosition.dy) / availableHeight;

    setState(() {
      _currentPosition = (_dragStartPosition + dragDelta).clamp(0.0, 1.0);
    });
  }

  void _handleVerticalDragEnd(DragEndDetails details) {
    if (!_isDragging) return;

    setState(() {
      _isDragging = false;
    });

    // Determine snap point based on position and velocity
    double targetPosition;

    if (details.velocity.pixelsPerSecond.dy < -500) {
      // Fast upward swipe - expand
      targetPosition = _expandedPlayerSnapPoint;
    } else if (details.velocity.pixelsPerSecond.dy > 500) {
      // Fast downward swipe - collapse
      targetPosition = _miniPlayerSnapPoint;
    } else {
      // Snap to nearest point based on position
      if (_currentPosition > 0.5) {
        targetPosition = _expandedPlayerSnapPoint;
      } else {
        targetPosition = _miniPlayerSnapPoint;
      }
    }

    _animateToPosition(targetPosition);
  }

  void _animateToPosition(double position) {
    _positionAnimation = Tween<double>(
      begin: _currentPosition,
      end: position,
    ).animate(CurvedAnimation(
      parent: _positionController,
      curve: Curves.easeOutCubic,
    ));

    _positionController.forward(from: 0.0);
  }

  void _handleTap() {
    if (_currentPosition < 0.5) {
      _animateToPosition(_expandedPlayerSnapPoint);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (widget.spotifyController.currentTrack == null ||
        widget.spotifyController.showWebView) {
      return const SizedBox.shrink();
    }

    final screenHeight = MediaQuery.of(context).size.height;
    final bottomPadding = MediaQuery.of(context).padding.bottom;
    final miniPlayerHeight = 72.0;
    final expandedPlayerHeight = screenHeight;

    // Calculate the actual height based on position
    final currentHeight = miniPlayerHeight +
      (_currentPosition * (expandedPlayerHeight - miniPlayerHeight));

    // Position above navigation bar (65 height + bottom padding)
    final navigationBarHeight = 65.0 + bottomPadding;

    // Calculate the bottom position - always above navigation
    final bottomPosition = navigationBarHeight -
      (_currentPosition * (expandedPlayerHeight - miniPlayerHeight - navigationBarHeight));

    return Positioned(
      left: 0,
      right: 0,
      bottom: bottomPosition,
      height: currentHeight,
      child: GestureDetector(
        onVerticalDragStart: _handleVerticalDragStart,
        onVerticalDragUpdate: _handleVerticalDragUpdate,
        onVerticalDragEnd: _handleVerticalDragEnd,
        onTap: _currentPosition < 0.1 ? _handleTap : null,
        child: Container(
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surface,
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.2),
                blurRadius: 10,
                offset: const Offset(0, -2),
              ),
            ],
          ),
          child: Stack(
            children: [
              // Background (for expanded player)
              if (_currentPosition > 0.1)
                Positioned.fill(
                  child: Container(
                    color: Colors.black.withValues(alpha: _currentPosition * 0.95),
                  ),
                ),

              // Mini player (visible when not expanded)
              if (_currentPosition < 0.9)
                Positioned(
                  top: 0,
                  left: 0,
                  right: 0,
                  height: miniPlayerHeight,
                  child: Opacity(
                    opacity: 1 - _currentPosition,
                    child: IgnorePointer(
                      ignoring: _currentPosition > 0.1,
                      child: MiniPlayer(
                        spotifyController: widget.spotifyController,
                        onTap: _handleTap,
                        onVerticalDragUp: () {}, // Handled by parent
                      ),
                    ),
                  ),
                ),

              // Expanded player (visible when expanded)
              if (_currentPosition > 0.1)
                Positioned.fill(
                  child: Opacity(
                    opacity: _currentPosition,
                    child: IgnorePointer(
                      ignoring: _currentPosition < 0.9,
                      child: ExpandedPlayer(
                        spotifyController: widget.spotifyController,
                        animation: _positionAnimation,
                        onClose: () => _animateToPosition(_miniPlayerSnapPoint),
                      ),
                    ),
                  ),
                ),

              // Drag handle
              if (_currentPosition > 0.1)
                Positioned(
                  top: 12,
                  left: 0,
                  right: 0,
                  child: Center(
                    child: Container(
                      width: 40,
                      height: 4,
                      decoration: BoxDecoration(
                        color: Colors.grey.withValues(alpha: 0.5),
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}