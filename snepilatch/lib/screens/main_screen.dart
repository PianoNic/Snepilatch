import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../widgets/spotify_webview.dart';
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
  late AnimationController _playerAnimationController;
  late Animation<double> _playerAnimation;

  late final List<Widget> _pages;

  @override
  void initState() {
    super.initState();
    _playerAnimationController = AnimationController(
      duration: const Duration(milliseconds: 300),
      vsync: this,
    );
    _playerAnimation = Tween<double>(
      begin: 0.0,
      end: 1.0,
    ).animate(CurvedAnimation(
      parent: _playerAnimationController,
      curve: Curves.easeInOut,
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
    setState(() {
      _isPlayerExpanded = true;
      _playerAnimationController.forward();
    });
  }

  void _collapsePlayer() {
    setState(() {
      _isPlayerExpanded = false;
      _playerAnimationController.reverse();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          // Main app content with AnimatedBuilder only for pages
          AnimatedBuilder(
            animation: widget.spotifyController,
            builder: (context, child) {
              return IndexedStack(
                index: _selectedIndex,
                children: _pages,
              );
            },
          ),
          // WebView - separate widget that doesn't rebuild with controller
          SpotifyWebViewWidget(spotifyController: widget.spotifyController),
          // Mini player at bottom when collapsed
          AnimatedBuilder(
            animation: widget.spotifyController,
            builder: (context, child) {
              if (widget.spotifyController.currentTrack != null &&
                  !widget.spotifyController.showWebView) {
                return Positioned(
                  bottom: 0,
                  left: 0,
                  right: 0,
                  child: MiniPlayer(
                    spotifyController: widget.spotifyController,
                    onTap: _expandPlayer,
                    onVerticalDragUp: _expandPlayer,
                  ),
                );
              }
              return const SizedBox.shrink();
            },
          ),
          // Expanded player
          if (_isPlayerExpanded && widget.spotifyController.currentTrack != null)
            ExpandedPlayer(
              spotifyController: widget.spotifyController,
              animation: _playerAnimation,
              onClose: _collapsePlayer,
            ),
        ],
      ),
      bottomNavigationBar: AnimatedBuilder(
        animation: widget.spotifyController,
        builder: (context, child) {
          return widget.spotifyController.showWebView
            ? const SizedBox.shrink()
            : NavigationBar(
                selectedIndex: _selectedIndex,
                onDestinationSelected: (int index) {
                  setState(() {
                    _selectedIndex = index;
                  });
                },
                destinations: const [
                  NavigationDestination(
                    icon: Icon(Icons.home_outlined),
                    selectedIcon: Icon(Icons.home),
                    label: 'Home',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.music_note_outlined),
                    selectedIcon: Icon(Icons.music_note),
                    label: 'Songs',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.search_outlined),
                    selectedIcon: Icon(Icons.search),
                    label: 'Search',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.person_outline),
                    selectedIcon: Icon(Icons.person),
                    label: 'User',
                  ),
                ],
              );
        },
      ),
    );
  }
}