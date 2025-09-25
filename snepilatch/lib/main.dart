import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'spotify_controller.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize WebView for supported platforms
  if (Platform.isAndroid || Platform.isIOS) {
    // WebView is supported
  }

  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  MyApp({super.key});

  final SpotifyController spotifyController = SpotifyController();

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Snepilatch',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: MainScreen(spotifyController: spotifyController),
    );
  }
}

// Separate WebView widget that doesn't rebuild with SpotifyController
class SpotifyWebViewWidget extends StatefulWidget {
  final SpotifyController spotifyController;
  const SpotifyWebViewWidget({super.key, required this.spotifyController});

  @override
  State<SpotifyWebViewWidget> createState() => _SpotifyWebViewWidgetState();
}

class _SpotifyWebViewWidgetState extends State<SpotifyWebViewWidget> {
  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid && !Platform.isIOS) {
      return const SizedBox.shrink();
    }

    // Listen only to showWebView changes
    return ValueListenableBuilder<bool>(
      valueListenable: widget.spotifyController.showWebViewNotifier,
      builder: (context, showWebView, child) {
        return Positioned(
          left: showWebView ? 0 : 0,
          top: showWebView ? 0 : 0,
          width: showWebView ? MediaQuery.of(context).size.width : 1,
          height: showWebView ? MediaQuery.of(context).size.height : 1,
          child: showWebView
            ? Container(
                color: Colors.white,
                child: SafeArea(
                  child: Column(
                    children: [
                      Container(
                        height: 56,
                        decoration: BoxDecoration(
                          color: Theme.of(context).colorScheme.surface,
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withOpacity(0.1),
                              blurRadius: 4,
                              offset: const Offset(0, 2),
                            ),
                          ],
                        ),
                        child: Row(
                          children: [
                            IconButton(
                              icon: const Icon(Icons.close),
                              onPressed: () => widget.spotifyController.hideWebView(),
                            ),
                            ValueListenableBuilder<bool>(
                              valueListenable: widget.spotifyController.isLoggedInNotifier,
                              builder: (context, isLoggedIn, child) {
                                return Text(
                                  isLoggedIn ? 'Spotify Web' : 'Login to Spotify',
                                  style: const TextStyle(
                                    fontSize: 18,
                                    fontWeight: FontWeight.w500,
                                  ),
                                );
                              },
                            ),
                            const Spacer(),
                            TextButton(
                              onPressed: () => widget.spotifyController.hideWebView(),
                              child: const Text('Done'),
                            ),
                          ],
                        ),
                      ),
                      Expanded(
                        child: InAppWebView(
                          key: const Key('spotify_webview'),
                          initialUrlRequest: URLRequest(
                            url: WebUri('https://open.spotify.com')
                          ),
                          initialSettings: widget.spotifyController.getWebViewSettings(),
                          onWebViewCreated: widget.spotifyController.onWebViewCreated,
                          onLoadStop: widget.spotifyController.onLoadStop,
                          shouldOverrideUrlLoading: widget.spotifyController.shouldOverrideUrlLoading,
                          onPermissionRequest: widget.spotifyController.onPermissionRequest,
                          onConsoleMessage: (controller, consoleMessage) {
                            debugPrint('Console: ${consoleMessage.message}');
                          },
                        ),
                      ),
                    ],
                  ),
                ),
              )
            : SizedBox(
                width: 1,
                height: 1,
                child: InAppWebView(
                  key: const Key('spotify_webview_hidden'),
                  initialUrlRequest: URLRequest(
                    url: WebUri('https://open.spotify.com')
                  ),
                  initialSettings: widget.spotifyController.getWebViewSettings(),
                  onWebViewCreated: widget.spotifyController.onWebViewCreated,
                  onLoadStop: widget.spotifyController.onLoadStop,
                  shouldOverrideUrlLoading: widget.spotifyController.shouldOverrideUrlLoading,
                  onPermissionRequest: widget.spotifyController.onPermissionRequest,
                ),
              ),
        );
      },
    );
  }
}

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
              if (widget.spotifyController.currentTrack != null && !widget.spotifyController.showWebView) {
                return Positioned(
                  bottom: 0,
                  left: 0,
                  right: 0,
                  child: GestureDetector(
                    onVerticalDragEnd: (details) {
                      if (details.velocity.pixelsPerSecond.dy < -300) {
                        // Swipe up - expand
                        setState(() {
                          _isPlayerExpanded = true;
                          _playerAnimationController.forward();
                        });
                      }
                    },
                    onTap: () {
                      setState(() {
                        _isPlayerExpanded = true;
                        _playerAnimationController.forward();
                      });
                    },
                    child: Container(
                      height: 64,
                      decoration: BoxDecoration(
                        color: Theme.of(context).colorScheme.surface,
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withOpacity(0.2),
                            blurRadius: 8,
                            offset: const Offset(0, -2),
                          ),
                        ],
                      ),
                      child: Row(
                        children: [
                          // Album art
                          if (widget.spotifyController.currentAlbumArt != null)
                            Container(
                              width: 64,
                              height: 64,
                              decoration: BoxDecoration(
                                image: DecorationImage(
                                  image: NetworkImage(widget.spotifyController.currentAlbumArt!),
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
                                  widget.spotifyController.currentTrack ?? '',
                                  style: const TextStyle(
                                    fontSize: 14,
                                    fontWeight: FontWeight.w600,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                Text(
                                  widget.spotifyController.currentArtist ?? '',
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
                              widget.spotifyController.isPlaying ? Icons.pause : Icons.play_arrow,
                            ),
                            onPressed: () {
                              if (widget.spotifyController.isPlaying) {
                                widget.spotifyController.pause();
                              } else {
                                widget.spotifyController.play();
                              }
                            },
                          ),
                          // Next button
                          IconButton(
                            icon: const Icon(Icons.skip_next),
                            onPressed: () => widget.spotifyController.next(),
                          ),
                          const SizedBox(width: 8),
                        ],
                      ),
                    ),
                  ),
                );
              }
              return const SizedBox.shrink();
            },
          ),
          // Expanded player
              if (_isPlayerExpanded && widget.spotifyController.currentTrack != null)
                AnimatedBuilder(
                  animation: _playerAnimation,
                  builder: (context, child) {
                    return Positioned.fill(
                      child: GestureDetector(
                        onVerticalDragEnd: (details) {
                          if (details.velocity.pixelsPerSecond.dy > 300) {
                            // Swipe down - collapse
                            setState(() {
                              _isPlayerExpanded = false;
                              _playerAnimationController.reverse();
                            });
                          }
                        },
                        child: Container(
                          color: Colors.black.withOpacity(_playerAnimation.value * 0.95),
                          child: SafeArea(
                            child: Column(
                              children: [
                                // Handle bar
                                Container(
                                  margin: const EdgeInsets.only(top: 8, bottom: 16),
                                  width: 40,
                                  height: 4,
                                  decoration: BoxDecoration(
                                    color: Colors.white.withOpacity(0.3),
                                    borderRadius: BorderRadius.circular(2),
                                  ),
                                ),
                                // Close button
                                Row(
                                  children: [
                                    IconButton(
                                      icon: const Icon(Icons.keyboard_arrow_down, color: Colors.white),
                                      onPressed: () {
                                        setState(() {
                                          _isPlayerExpanded = false;
                                          _playerAnimationController.reverse();
                                        });
                                      },
                                    ),
                                  ],
                                ),
                                Expanded(
                                  child: SingleChildScrollView(
                                    padding: const EdgeInsets.all(24.0),
                                    child: Column(
                                      mainAxisAlignment: MainAxisAlignment.center,
                                      children: [
                                        // Album art
                                        if (widget.spotifyController.currentAlbumArt != null)
                                          Container(
                                            width: MediaQuery.of(context).size.width * 0.8,
                                            height: MediaQuery.of(context).size.width * 0.8,
                                            decoration: BoxDecoration(
                                              borderRadius: BorderRadius.circular(12),
                                              boxShadow: [
                                                BoxShadow(
                                                  color: Colors.black.withOpacity(0.3),
                                                  blurRadius: 20,
                                                  offset: const Offset(0, 10),
                                                ),
                                              ],
                                              image: DecorationImage(
                                                image: NetworkImage(widget.spotifyController.currentAlbumArt!),
                                                fit: BoxFit.cover,
                                              ),
                                            ),
                                          ),
                                        const SizedBox(height: 32),
                                        // Track info
                                        Text(
                                          widget.spotifyController.currentTrack ?? '',
                                          style: const TextStyle(
                                            fontSize: 24,
                                            fontWeight: FontWeight.bold,
                                            color: Colors.white,
                                          ),
                                          textAlign: TextAlign.center,
                                        ),
                                        const SizedBox(height: 8),
                                        Text(
                                          widget.spotifyController.currentArtist ?? '',
                                          style: const TextStyle(
                                            fontSize: 18,
                                            color: Colors.white70,
                                          ),
                                          textAlign: TextAlign.center,
                                        ),
                                        const SizedBox(height: 32),
                                        // Controls
                                        Row(
                                          mainAxisAlignment: MainAxisAlignment.center,
                                          children: [
                                            IconButton(
                                              icon: Icon(
                                                widget.spotifyController.shuffleMode == 'enhanced'
                                                  ? Icons.shuffle_on_outlined
                                                  : Icons.shuffle,
                                                color: widget.spotifyController.shuffleMode == 'off'
                                                  ? Colors.white54
                                                  : widget.spotifyController.shuffleMode == 'normal'
                                                    ? Colors.green
                                                    : Colors.greenAccent,
                                              ),
                                              iconSize: 32,
                                              onPressed: () => widget.spotifyController.toggleShuffle(),
                                            ),
                                            IconButton(
                                              icon: const Icon(Icons.skip_previous, color: Colors.white),
                                              iconSize: 48,
                                              onPressed: () => widget.spotifyController.previous(),
                                            ),
                                            IconButton(
                                              icon: Icon(
                                                widget.spotifyController.isPlaying
                                                  ? Icons.pause_circle_filled
                                                  : Icons.play_circle_filled,
                                                color: Colors.white,
                                              ),
                                              iconSize: 72,
                                              onPressed: () {
                                                if (widget.spotifyController.isPlaying) {
                                                  widget.spotifyController.pause();
                                                } else {
                                                  widget.spotifyController.play();
                                                }
                                              },
                                            ),
                                            IconButton(
                                              icon: const Icon(Icons.skip_next, color: Colors.white),
                                              iconSize: 48,
                                              onPressed: () => widget.spotifyController.next(),
                                            ),
                                            IconButton(
                                              icon: Icon(
                                                widget.spotifyController.repeatMode == 'one'
                                                  ? Icons.repeat_one
                                                  : Icons.repeat,
                                                color: widget.spotifyController.repeatMode != 'off'
                                                  ? Colors.green
                                                  : Colors.white54,
                                              ),
                                              iconSize: 32,
                                              onPressed: () => widget.spotifyController.toggleRepeat(),
                                            ),
                                          ],
                                        ),
                                        const SizedBox(height: 24),
                                        // Like button
                                        IconButton(
                                          icon: Icon(
                                            widget.spotifyController.isCurrentTrackLiked
                                              ? Icons.favorite
                                              : Icons.favorite_border,
                                            color: widget.spotifyController.isCurrentTrackLiked
                                              ? Colors.green
                                              : Colors.white,
                                          ),
                                          iconSize: 32,
                                          onPressed: () => widget.spotifyController.toggleLike(),
                                        ),
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
                if (spotifyController.currentAlbumArt != null)
                  Container(
                    width: 200,
                    height: 200,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(12),
                      image: DecorationImage(
                        image: NetworkImage(spotifyController.currentAlbumArt!),
                        fit: BoxFit.cover,
                      ),
                    ),
                  )
                else
                  Container(
                    width: 200,
                    height: 200,
                    decoration: BoxDecoration(
                      color: Theme.of(context).colorScheme.primaryContainer,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: const Icon(Icons.music_note, size: 64),
                  ),
                const SizedBox(height: 24),
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
                const SizedBox(height: 16),
                // Like button
                IconButton(
                  icon: Icon(
                    spotifyController.isCurrentTrackLiked ? Icons.favorite : Icons.favorite_border,
                    color: spotifyController.isCurrentTrackLiked ? Colors.green : null,
                  ),
                  iconSize: 32,
                  onPressed: spotifyController.currentTrack != null ? () => spotifyController.toggleLike() : null,
                ),
                const SizedBox(height: 16),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Column(
                      children: [
                        IconButton(
                          icon: Icon(
                            spotifyController.shuffleMode == 'enhanced'
                              ? Icons.shuffle_on_outlined
                              : Icons.shuffle,
                          ),
                          iconSize: 32,
                          onPressed: () => spotifyController.toggleShuffle(),
                          color: spotifyController.shuffleMode == 'off'
                            ? Theme.of(context).colorScheme.secondary
                            : spotifyController.shuffleMode == 'normal'
                              ? Colors.green
                              : Colors.greenAccent,
                        ),
                        Text(
                          spotifyController.shuffleMode == 'enhanced'
                            ? 'Enhanced'
                            : spotifyController.shuffleMode == 'normal'
                              ? 'On'
                              : 'Off',
                          style: TextStyle(
                            fontSize: 10,
                            color: spotifyController.shuffleMode == 'off'
                              ? Theme.of(context).colorScheme.secondary
                              : spotifyController.shuffleMode == 'normal'
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
                        spotifyController.isPlaying ? Icons.pause_circle_filled : Icons.play_circle_filled,
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
                        spotifyController.repeatMode == 'one'
                          ? Icons.repeat_one
                          : Icons.repeat,
                      ),
                      iconSize: 32,
                      onPressed: () => spotifyController.toggleRepeat(),
                      color: spotifyController.repeatMode != 'off'
                        ? Colors.green
                        : Theme.of(context).colorScheme.secondary,
                    ),
                  ],
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class SongsPage extends StatefulWidget {
  final SpotifyController spotifyController;
  const SongsPage({super.key, required this.spotifyController});

  @override
  State<SongsPage> createState() => _SongsPageState();
}

class _SongsPageState extends State<SongsPage> {
  final ScrollController _scrollController = ScrollController();
  bool _hasNavigated = false;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Navigate to liked songs when tab becomes visible
    if (!_hasNavigated && widget.spotifyController.isInitialized) {
      _hasNavigated = true;
      widget.spotifyController.navigateToLikedSongs();
    }
  }

  void _onScroll() {
    if (_scrollController.position.pixels >= _scrollController.position.maxScrollExtent - 200) {
      // User scrolled near the bottom, load more songs
      widget.spotifyController.loadMoreSongs();
    }

    // Sync scroll position with Spotify
    widget.spotifyController.scrollSpotifyPage(_scrollController.position.pixels);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Liked Songs'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () {
              widget.spotifyController.scrapeSongs();
            },
          ),
        ],
      ),
      body: AnimatedBuilder(
        animation: widget.spotifyController,
        builder: (context, child) {
          if (widget.spotifyController.isLoadingSongs && widget.spotifyController.songs.isEmpty) {
            return const Center(
              child: CircularProgressIndicator(),
            );
          }

          if (widget.spotifyController.songs.isEmpty) {
            return Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.music_note,
                    size: 64,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                  const SizedBox(height: 16),
                  const Text(
                    'No songs found',
                    style: TextStyle(fontSize: 18),
                  ),
                  const SizedBox(height: 8),
                  ElevatedButton(
                    onPressed: () => widget.spotifyController.navigateToLikedSongs(),
                    child: const Text('Load Songs'),
                  ),
                ],
              ),
            );
          }

          return ListView.builder(
            controller: _scrollController,
            itemCount: widget.spotifyController.songs.length +
                      (widget.spotifyController.isLoadingSongs ? 1 : 0),
            itemBuilder: (context, index) {
              if (index == widget.spotifyController.songs.length) {
                return const Center(
                  child: Padding(
                    padding: EdgeInsets.all(16.0),
                    child: CircularProgressIndicator(),
                  ),
                );
              }

              final song = widget.spotifyController.songs[index];
              final imageUrl = song['image'];

              return ListTile(
                leading: Container(
                  width: 48,
                  height: 48,
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(4),
                    color: Theme.of(context).colorScheme.primaryContainer,
                  ),
                  child: imageUrl != null && imageUrl.isNotEmpty
                      ? ClipRRect(
                          borderRadius: BorderRadius.circular(4),
                          child: Image.network(
                            imageUrl,
                            fit: BoxFit.cover,
                            errorBuilder: (context, error, stackTrace) {
                              return const Icon(Icons.music_note);
                            },
                          ),
                        )
                      : const Icon(Icons.music_note),
                ),
                title: Text(
                  song['title'] ?? 'Unknown Song',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: Text(
                  '${song['artist'] ?? 'Unknown Artist'} • ${song['album'] ?? ''}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      song['duration'] ?? '',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    IconButton(
                      icon: const Icon(Icons.play_arrow),
                      onPressed: () {
                        final songIndex = int.tryParse(song['index'] ?? '0') ?? 0;
                        widget.spotifyController.playTrackAtIndex(songIndex);
                      },
                    ),
                  ],
                ),
                onTap: () {
                  final songIndex = int.tryParse(song['index'] ?? '0') ?? 0;
                  widget.spotifyController.playTrackAtIndex(songIndex);
                },
              );
            },
          );
        },
      ),
    );
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }
}

class SearchPage extends StatefulWidget {
  final SpotifyController spotifyController;
  const SearchPage({super.key, required this.spotifyController});

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  final TextEditingController _searchController = TextEditingController();
  List<Map<String, String>> _searchResults = [];
  bool _isSearching = false;

  Future<void> _performSearch(String query) async {
    if (query.isEmpty) return;

    setState(() {
      _isSearching = true;
    });

    final results = await widget.spotifyController.searchAndGetResults(query);

    setState(() {
      _searchResults = results;
      _isSearching = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Search'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: SearchBar(
              controller: _searchController,
              hintText: 'Search for songs, artists, albums...',
              leading: const Icon(Icons.search),
              padding: const WidgetStatePropertyAll(
                EdgeInsets.symmetric(horizontal: 16.0),
              ),
              onSubmitted: _performSearch,
              trailing: [
                if (_isSearching)
                  const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
              ],
            ),
          ),
          Expanded(
            child: _searchResults.isEmpty
                ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          Icons.search,
                          size: 64,
                          color: Theme.of(context).colorScheme.primary,
                        ),
                        const SizedBox(height: 16),
                        const Text(
                          'Start searching',
                          style: TextStyle(fontSize: 18),
                        ),
                        const SizedBox(height: 8),
                        const Text(
                          'Find your favorite music on Spotify',
                          style: TextStyle(fontSize: 14, color: Colors.grey),
                        ),
                      ],
                    ),
                  )
                : ListView.builder(
                    itemCount: _searchResults.length,
                    itemBuilder: (context, index) {
                      final result = _searchResults[index];
                      return ListTile(
                        leading: CircleAvatar(
                          backgroundColor: Theme.of(context).colorScheme.primary,
                          child: const Icon(Icons.music_note, color: Colors.white),
                        ),
                        title: Text(result['title'] ?? ''),
                        subtitle: Text(result['artist'] ?? ''),
                        trailing: IconButton(
                          icon: const Icon(Icons.play_arrow),
                          onPressed: () => widget.spotifyController.playTrackAtIndex(index),
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }
}

class UserPage extends StatelessWidget {
  final SpotifyController spotifyController;
  const UserPage({super.key, required this.spotifyController});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Profile'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () {},
          ),
        ],
      ),
      body: AnimatedBuilder(
        animation: spotifyController,
        builder: (context, child) {
          return SingleChildScrollView(
            child: Column(
              children: [
                const SizedBox(height: 24),
                CircleAvatar(
                  radius: 60,
                  backgroundColor: Theme.of(context).colorScheme.primary,
                  backgroundImage: spotifyController.userProfileImage != null
                      ? NetworkImage(spotifyController.userProfileImage!)
                      : null,
                  child: spotifyController.userProfileImage == null
                      ? Icon(
                          spotifyController.isLoggedIn ? Icons.account_circle : Icons.person,
                          size: 60,
                          color: Colors.white,
                        )
                      : null,
                ),
                const SizedBox(height: 16),
                Text(
                  spotifyController.isLoggedIn
                    ? (spotifyController.username ?? 'Spotify User')
                    : 'Not Logged In',
                  style: const TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  spotifyController.isLoggedIn
                    ? 'Connected to Spotify'
                    : 'Sign in to access your music',
                  style: TextStyle(
                    fontSize: 16,
                    color: Colors.grey[600],
                  ),
                ),
                const SizedBox(height: 24),
                if (!spotifyController.isLoggedIn) ...[
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 32.0),
                    child: ElevatedButton.icon(
                      onPressed: () => spotifyController.navigateToLogin(),
                      icon: const Icon(Icons.login),
                      label: const Text('Login to Spotify'),
                      style: ElevatedButton.styleFrom(
                        minimumSize: const Size(double.infinity, 48),
                        backgroundColor: Theme.of(context).colorScheme.primary,
                        foregroundColor: Colors.white,
                      ),
                    ),
                  ),
                  const SizedBox(height: 32),
                ] else ...[
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 32.0),
                    child: Column(
                      children: [
                        ElevatedButton.icon(
                          onPressed: () => spotifyController.openWebView(),
                          icon: const Icon(Icons.open_in_browser),
                          label: const Text('Open Spotify Web'),
                          style: ElevatedButton.styleFrom(
                            minimumSize: const Size(double.infinity, 48),
                            backgroundColor: Theme.of(context).colorScheme.primary,
                            foregroundColor: Colors.white,
                          ),
                        ),
                        const SizedBox(height: 12),
                        OutlinedButton.icon(
                          onPressed: () => spotifyController.logout(),
                          icon: const Icon(Icons.logout),
                          label: const Text('Logout'),
                          style: OutlinedButton.styleFrom(
                            minimumSize: const Size(double.infinity, 48),
                            foregroundColor: Colors.red,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
                const SizedBox(height: 24),
                if (spotifyController.isLoggedIn) ...[
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      _buildStatCard(context, '256', 'Songs'),
                      _buildStatCard(context, '12', 'Playlists'),
                      _buildStatCard(context, '48', 'Following'),
                    ],
                  ),
                  const SizedBox(height: 32),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Your Library',
                          style: TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 16),
                        _buildLibraryTile(context, Icons.favorite, 'Liked Songs', '250 songs'),
                        _buildLibraryTile(context, Icons.playlist_play, 'Playlists', '12 playlists'),
                        _buildLibraryTile(context, Icons.download, 'Downloads', '150 songs'),
                        _buildLibraryTile(context, Icons.history, 'Recently Played', '30 songs'),
                        _buildLibraryTile(context, Icons.album, 'Albums', '45 albums'),
                        _buildLibraryTile(context, Icons.person, 'Following Artists', '89 artists'),
                      ],
                    ),
                  ),
                ],
                const SizedBox(height: 32),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildStatCard(BuildContext context, String count, String label) {
    return Column(
      children: [
        Text(
          count,
          style: const TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.bold,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          label,
          style: TextStyle(
            fontSize: 14,
            color: Colors.grey[600],
          ),
        ),
      ],
    );
  }

  Widget _buildLibraryTile(BuildContext context, IconData icon, String title, String subtitle) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: Icon(
          icon,
          color: Theme.of(context).colorScheme.primary,
        ),
        title: Text(title),
        subtitle: Text(subtitle),
        trailing: const Icon(Icons.chevron_right),
        onTap: () {},
      ),
    );
  }
}