import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../widgets/mini_player.dart';
import '../widgets/expanded_player.dart';
import 'home_page.dart';
import 'songs_page.dart';
import 'search_page.dart';
import 'user_page.dart';

class MainScreen extends StatefulWidget {
  final SpotifyController spotifyController;
  const MainScreen({super.key, required this.spotifyController});

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> with SingleTickerProviderStateMixin {
  int _selectedIndex = 0;
  bool _isPlayerExpanded = false;
  bool _isAnimating = false;
  late AnimationController _playerAnimationController;
  late Animation<double> _playerAnimation;

  late final List<Widget> _pages;

  @override
  void initState() {
    super.initState();
    _playerAnimationController = AnimationController(
      duration: const Duration(milliseconds: 400),
      vsync: this,
    );
    _playerAnimation = Tween<double>(
      begin: 0.0,
      end: 1.0,
    ).animate(CurvedAnimation(
      parent: _playerAnimationController,
      curve: Curves.easeOutCubic,
    ));

    _pages = [
      HomePage(spotifyController: widget.spotifyController),
      SongsPage(spotifyController: widget.spotifyController),
      SearchPage(spotifyController: widget.spotifyController),
      UserPage(spotifyController: widget.spotifyController),
    ];
  }

  @override
  void dispose() {
    _playerAnimationController.dispose();
    super.dispose();
  }

  void _expandPlayer() {
    if (_isAnimating) return;
    _isAnimating = true;
    setState(() {
      _isPlayerExpanded = true;
    });
    _playerAnimationController.forward().then((_) {
      _isAnimating = false;
    });
  }

  void _collapsePlayer() {
    if (_isAnimating) return;
    _isAnimating = true;
    _playerAnimationController.reverse().then((_) {
      setState(() {
        _isPlayerExpanded = false;
        _isAnimating = false;
      });
    });
  }

  void _onItemTapped(int index) {
    setState(() {
      _selectedIndex = index;
    });
  }

  @override
  Widget build(BuildContext context) {
    // Page titles for the header
    final List<String> pageTitles = [
      'Home',
      'Library',
      'Search',
      'Profile',
    ];

    return Stack(
      children: [
        Scaffold(
          body: Column(
            children: [
              // Schuly-style app bar
              if (!widget.spotifyController.showWebView)
                AppBar(
                  backgroundColor: Colors.transparent,
                  elevation: 0,
                  title: Text(
                    pageTitles[_selectedIndex],
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.normal,
                      color: Theme.of(context).colorScheme.onSurface,
                    ),
                  ),
                  actions: _buildAppBarActions(),
                ),
              // Offline mode indicator (like Schuly)
              AnimatedBuilder(
                animation: widget.spotifyController,
                builder: (context, child) {
                  if (!widget.spotifyController.isLoggedIn) {
                    return Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                      decoration: BoxDecoration(
                        color: Colors.orange.withValues(alpha: 0.1),
                        border: Border(
                          bottom: BorderSide(
                            color: Colors.orange.withValues(alpha: 0.3),
                            width: 1,
                          ),
                        ),
                      ),
                      child: Row(
                        children: [
                          Icon(
                            Icons.info_outline,
                            size: 16,
                            color: Colors.orange[700],
                          ),
                          const SizedBox(width: 8),
                          Text(
                            'Login to Spotify to start',
                            style: TextStyle(
                              color: Colors.orange[700],
                              fontSize: 13,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ],
                      ),
                    );
                  }
                  return const SizedBox.shrink();
                },
              ),
              Expanded(
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 200),
                  switchInCurve: Curves.easeIn,
                  switchOutCurve: Curves.easeOut,
                  transitionBuilder: (Widget child, Animation<double> animation) {
                    return FadeTransition(
                      opacity: animation,
                      child: child,
                    );
                  },
                  layoutBuilder: (Widget? currentChild, List<Widget> previousChildren) {
                    return Stack(
                      children: <Widget>[
                        ...previousChildren,
                        if (currentChild != null) currentChild,
                      ],
                    );
                  },
                  child: Container(
                    key: ValueKey(_selectedIndex),
                    child: _pages[_selectedIndex],
                  ),
                ),
              ),
              // Mini player at bottom above navigation
              if (!_isPlayerExpanded)
                AnimatedBuilder(
                  animation: widget.spotifyController,
                  builder: (context, child) {
                    if (widget.spotifyController.currentTrack != null &&
                        !widget.spotifyController.showWebView) {
                      return MiniPlayer(
                        spotifyController: widget.spotifyController,
                        onTap: _expandPlayer,
                        onVerticalDragUp: _expandPlayer,
                      );
                    }
                    return const SizedBox.shrink();
                  },
                ),
            ],
          ),
          bottomNavigationBar: AnimatedBuilder(
        animation: widget.spotifyController,
        builder: (context, child) {
          if (widget.spotifyController.showWebView) {
            return const SizedBox.shrink();
          }

          // Using NavigationBar with Material 3 style (like Schuly)
          final primaryColor = Theme.of(context).colorScheme.primary;

          return NavigationBar(
            selectedIndex: _selectedIndex,
            onDestinationSelected: _onItemTapped,
            animationDuration: const Duration(milliseconds: 300),
            backgroundColor: Theme.of(context).colorScheme.surface,
            surfaceTintColor: Colors.transparent,
            indicatorColor: primaryColor.withValues(alpha: 0.15),
            height: 65, // Slightly taller for better touch targets
            labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
            destinations: [
              NavigationDestination(
                icon: Icon(
                  Icons.home_outlined,
                  color: _selectedIndex == 0 ? primaryColor : Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                selectedIcon: Icon(Icons.home, color: primaryColor),
                label: 'Home',
              ),
              NavigationDestination(
                icon: Icon(
                  Icons.library_music_outlined,
                  color: _selectedIndex == 1 ? primaryColor : Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                selectedIcon: Icon(Icons.library_music, color: primaryColor),
                label: 'Library',
              ),
              NavigationDestination(
                icon: Icon(
                  Icons.search_outlined,
                  color: _selectedIndex == 2 ? primaryColor : Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                selectedIcon: Icon(Icons.search, color: primaryColor),
                label: 'Search',
              ),
              NavigationDestination(
                icon: widget.spotifyController.isLoggedIn &&
                      widget.spotifyController.userProfileImage != null
                    ? CircleAvatar(
                        radius: 12,
                        backgroundImage: NetworkImage(widget.spotifyController.userProfileImage!),
                        backgroundColor: _selectedIndex == 3 ? primaryColor : Theme.of(context).colorScheme.onSurfaceVariant,
                      )
                    : Icon(
                        Icons.person_outline,
                        color: _selectedIndex == 3 ? primaryColor : Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                selectedIcon: widget.spotifyController.isLoggedIn &&
                             widget.spotifyController.userProfileImage != null
                    ? Container(
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: primaryColor,
                            width: 2,
                          ),
                        ),
                        child: CircleAvatar(
                          radius: 12,
                          backgroundImage: NetworkImage(widget.spotifyController.userProfileImage!),
                          backgroundColor: primaryColor,
                        ),
                      )
                    : Icon(Icons.person, color: primaryColor),
                label: 'Profile',
              ),
            ],
          );
        },
      ),
        ),
        // Expanded player with background overlay
        if (_isPlayerExpanded && widget.spotifyController.currentTrack != null) ...[
          // Background overlay that fades in
          AnimatedBuilder(
            animation: _playerAnimation,
            builder: (context, child) {
              return IgnorePointer(
                child: Container(
                  color: Colors.black.withValues(alpha: _playerAnimation.value * 0.95),
                ),
              );
            },
          ),
          // The actual player
          ExpandedPlayer(
            spotifyController: widget.spotifyController,
            animation: _playerAnimation,
            onClose: _collapsePlayer,
          ),
        ],
      ],
    );
  }

  List<Widget>? _buildAppBarActions() {
    // Different actions for different pages (like Schuly)
    switch (_selectedIndex) {
      case 0: // Home page
        return null;
      case 1: // Songs/Library page
        return [
          IconButton(
            onPressed: () {
              widget.spotifyController.navigateToLikedSongs();
            },
            icon: Icon(
              Icons.refresh,
              color: Theme.of(context).colorScheme.onSurface,
            ),
            tooltip: 'Refresh',
          ),
        ];
      case 3: // Profile page
        return null;
      default:
        return null;
    }
  }
}