import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:spotify/spotify.dart' as spotify;
import 'package:snepilatch_v2/models/recently_played_item.dart';
import 'package:snepilatch_v2/pages/detail_page.dart';
import 'package:snepilatch_v2/services/spotify_client.dart';

class HomeTab extends StatefulWidget {
  const HomeTab({super.key});

  @override
  State<StatefulWidget> createState() => _HomeTabState();
}

class _HomeTabState extends State<HomeTab> {
  final TextEditingController _searchController = TextEditingController();
  Timer? _debounce;
  bool _isSearching = false;
  String _searchQuery = '';

  // Search results by type
  List<spotify.Artist> _artistResults = [];
  List<spotify.AlbumSimple> _albumResults = [];
  List<spotify.Track> _trackResults = [];
  List<spotify.PlaylistSimple> _playlistResults = [];

  @override
  void dispose() {
    _searchController.dispose();
    _debounce?.cancel();
    super.dispose();
  }

  void _onSearchChanged(String query) {
    _debounce?.cancel();
    if (query.trim().isEmpty) {
      setState(() {
        _searchQuery = '';
        _isSearching = false;
        _artistResults = [];
        _albumResults = [];
        _trackResults = [];
        _playlistResults = [];
      });
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 400), () {
      _performSearch(query.trim());
    });
  }

  Future<void> _performSearch(String query) async {
    final api = context.read<SpotifyClient>().spotifyApi;
    if (api == null) return;

    setState(() {
      _searchQuery = query;
      _isSearching = true;
    });

    try {
      final bundledPages = api.search.get(
        query,
        types: [
          spotify.SearchType.artist,
          spotify.SearchType.album,
          spotify.SearchType.track,
          spotify.SearchType.playlist,
        ],
      );
      final pages = await bundledPages.getPage(10);

      if (!mounted || _searchQuery != query) return;

      final artists = <spotify.Artist>[];
      final albums = <spotify.AlbumSimple>[];
      final tracks = <spotify.Track>[];
      final playlists = <spotify.PlaylistSimple>[];

      for (final page in pages) {
        final items = page.items;
        if (items == null) continue;
        for (final item in items) {
          if (item is spotify.Artist) {
            artists.add(item);
          } else if (item is spotify.AlbumSimple) {
            albums.add(item);
          } else if (item is spotify.Track) {
            tracks.add(item);
          } else if (item is spotify.PlaylistSimple) {
            playlists.add(item);
          }
        }
      }

      setState(() {
        _artistResults = artists;
        _albumResults = albums;
        _trackResults = tracks;
        _playlistResults = playlists;
        _isSearching = false;
      });
    } catch (e) {
      debugPrint('[HomeTab] Search error: $e');
      if (mounted) setState(() => _isSearching = false);
    }
  }

  void _clearSearch() {
    _searchController.clear();
    _onSearchChanged('');
  }

  bool get _hasSearchQuery => _searchQuery.isNotEmpty;

  bool get _hasResults =>
      _artistResults.isNotEmpty ||
      _albumResults.isNotEmpty ||
      _trackResults.isNotEmpty ||
      _playlistResults.isNotEmpty;

  @override
  Widget build(BuildContext context) {
    final spotifyClient = context.watch<SpotifyClient>();
    final shortcuts = spotifyClient.homeShortcuts;
    final sections = spotifyClient.homeSections;
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final isLoading = shortcuts.isEmpty && sections.isEmpty;

    return Scaffold(
      body: CustomScrollView(
        slivers: [
          // -- Top padding --
          SliverToBoxAdapter(
            child: SizedBox(height: MediaQuery.of(context).padding.top + 8),
          ),

          // -- Search bar --
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 0),
              child: SearchBar(
                controller: _searchController,
                onChanged: _onSearchChanged,
                leading: const Padding(
                  padding: EdgeInsets.only(left: 8),
                  child: Icon(Icons.search),
                ),
                trailing: _searchController.text.isNotEmpty
                    ? [
                        IconButton(
                          icon: const Icon(Icons.close),
                          onPressed: _clearSearch,
                        ),
                      ]
                    : null,
                hintText: 'Search songs, artists, albums...',
                shadowColor: WidgetStateProperty.all<Color?>(Colors.transparent),
              ),
            ),
          ),

          // -- Search results or home content --
          if (_hasSearchQuery)
            ..._buildSearchResults(theme, colorScheme)
          else if (isLoading)
            SliverFillRemaining(
              child: Center(
                child: CircularProgressIndicator(
                  valueColor:
                      AlwaysStoppedAnimation<Color>(colorScheme.primary),
                ),
              ),
            )
          else ...[
            // -- Quick-access grid (shortcuts) --
            if (shortcuts.isNotEmpty)
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
                sliver: SliverToBoxAdapter(
                  child: _buildQuickGrid(shortcuts.take(8).toList()),
                ),
              ),

            // -- Sections from Spotify home page --
            for (final section in sections)
              SliverToBoxAdapter(
                child: _buildShelf(
                  title: section.title,
                  items: section.items,
                ),
              ),

            // -- Bottom spacing for now-playing bar --
            const SliverToBoxAdapter(child: SizedBox(height: 24)),
          ],
        ],
      ),
    );
  }

  // ───────────────────── Search results ─────────────────────

  List<Widget> _buildSearchResults(ThemeData theme, ColorScheme colorScheme) {
    if (_isSearching) {
      return [
        SliverFillRemaining(
          child: Center(
            child: CircularProgressIndicator(
              valueColor: AlwaysStoppedAnimation<Color>(colorScheme.primary),
            ),
          ),
        ),
      ];
    }

    if (!_hasResults) {
      return [
        SliverFillRemaining(
          child: Center(
            child: Text(
              'No results found',
              style: theme.textTheme.bodyLarge?.copyWith(
                color: colorScheme.onSurfaceVariant,
              ),
            ),
          ),
        ),
      ];
    }

    final slivers = <Widget>[];

    // -- Artists --
    if (_artistResults.isNotEmpty) {
      slivers.add(SliverToBoxAdapter(
        child: _sectionHeader('Artists', theme, colorScheme),
      ));
      slivers.add(SliverList.builder(
        itemCount: _artistResults.length,
        itemBuilder: (context, index) {
          final artist = _artistResults[index];
          final imageUrl = artist.images?.isNotEmpty == true
              ? artist.images!.first.url
              : null;
          return _searchResultTile(
            imageUrl: imageUrl,
            title: artist.name ?? '',
            subtitle: 'Artist',
            isCircular: true,
            onTap: () => DetailPage.open(
              context,
              name: artist.name ?? '',
              imageUrl: imageUrl,
              uri: artist.uri ?? '',
              itemType: 'artist',
            ),
            theme: theme,
            colorScheme: colorScheme,
          );
        },
      ));
    }

    // -- Albums --
    if (_albumResults.isNotEmpty) {
      slivers.add(SliverToBoxAdapter(
        child: _sectionHeader('Albums', theme, colorScheme),
      ));
      slivers.add(SliverList.builder(
        itemCount: _albumResults.length,
        itemBuilder: (context, index) {
          final album = _albumResults[index];
          final imageUrl = album.images?.isNotEmpty == true
              ? album.images!.first.url
              : null;
          final artists = album.artists
                  ?.map((a) => a.name)
                  .whereType<String>()
                  .join(', ') ??
              '';
          return _searchResultTile(
            imageUrl: imageUrl,
            title: album.name ?? '',
            subtitle: artists,
            onTap: () => DetailPage.open(
              context,
              name: album.name ?? '',
              imageUrl: imageUrl,
              uri: album.uri ?? '',
              itemType: 'album',
            ),
            theme: theme,
            colorScheme: colorScheme,
          );
        },
      ));
    }

    // -- Tracks --
    if (_trackResults.isNotEmpty) {
      slivers.add(SliverToBoxAdapter(
        child: _sectionHeader('Songs', theme, colorScheme),
      ));
      slivers.add(SliverList.builder(
        itemCount: _trackResults.length,
        itemBuilder: (context, index) {
          final track = _trackResults[index];
          final imageUrl = track.album?.images?.isNotEmpty == true
              ? track.album!.images!.first.url
              : null;
          final artists = track.artists
                  ?.map((a) => a.name)
                  .whereType<String>()
                  .join(', ') ??
              '';
          return _searchResultTile(
            imageUrl: imageUrl,
            title: track.name ?? '',
            subtitle: artists,
            onTap: () =>
                context.read<SpotifyClient>().playTrack(track.uri ?? ''),
            theme: theme,
            colorScheme: colorScheme,
          );
        },
      ));
    }

    // -- Playlists --
    if (_playlistResults.isNotEmpty) {
      slivers.add(SliverToBoxAdapter(
        child: _sectionHeader('Playlists', theme, colorScheme),
      ));
      slivers.add(SliverList.builder(
        itemCount: _playlistResults.length,
        itemBuilder: (context, index) {
          final playlist = _playlistResults[index];
          final imageUrl = playlist.images?.isNotEmpty == true
              ? playlist.images!.first.url
              : null;
          return _searchResultTile(
            imageUrl: imageUrl,
            title: playlist.name ?? '',
            subtitle: playlist.owner?.displayName ?? 'Playlist',
            onTap: () => DetailPage.open(
              context,
              name: playlist.name ?? '',
              imageUrl: imageUrl,
              uri: playlist.uri ?? '',
              itemType: 'playlist',
            ),
            theme: theme,
            colorScheme: colorScheme,
          );
        },
      ));
    }

    slivers.add(const SliverToBoxAdapter(child: SizedBox(height: 24)));
    return slivers;
  }

  Widget _sectionHeader(
      String title, ThemeData theme, ColorScheme colorScheme) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 16, 24, 8),
      child: Text(
        title,
        style: theme.textTheme.titleMedium?.copyWith(
          fontWeight: FontWeight.w700,
          color: colorScheme.onSurface,
        ),
      ),
    );
  }

  Widget _searchResultTile({
    required String? imageUrl,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
    required ThemeData theme,
    required ColorScheme colorScheme,
    bool isCircular = false,
  }) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 6),
        child: Row(
          children: [
            _Artwork(
              size: 48,
              cover: imageUrl,
              radius: isCircular ? 24 : 4,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyLarge?.copyWith(
                      color: colorScheme.onSurface,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (subtitle.isNotEmpty)
                    Text(
                      subtitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ───────────────────── Home content ─────────────────────

  Widget _buildQuickGrid(List<RecentlyPlayedItem> items) {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        mainAxisSpacing: 8,
        crossAxisSpacing: 8,
        childAspectRatio: 3.2,
      ),
      itemCount: items.length,
      itemBuilder: (context, index) => _QuickCard(item: items[index]),
    );
  }

  Widget _buildShelf({
    required String title,
    required List<RecentlyPlayedItem> items,
  }) {
    if (items.isEmpty) return const SizedBox.shrink();

    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 0, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: theme.textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.w800,
              color: colorScheme.onSurface,
            ),
          ),
          const SizedBox(height: 12),
          SizedBox(
            height: 210,
            child: ListView.separated(
              padding: const EdgeInsets.only(right: 16),
              scrollDirection: Axis.horizontal,
              itemCount: items.length,
              separatorBuilder: (_, _) => const SizedBox(width: 12),
              itemBuilder: (context, index) =>
                  _ShelfCard(item: items[index]),
            ),
          ),
        ],
      ),
    );
  }
}

String _typeFromUri(String uri) {
  final parts = uri.split(':');
  if (parts.length >= 2) {
    switch (parts[1]) {
      case 'playlist':
        return 'playlist';
      case 'album':
        return 'album';
      case 'artist':
        return 'artist';
    }
  }
  return 'playlist';
}

// -- Quick-access card (2-column grid, Spotify style) --
class _QuickCard extends StatelessWidget {
  const _QuickCard({required this.item});

  final RecentlyPlayedItem item;

  void _openDetail(BuildContext context) {
    final type = item.type ?? _typeFromUri(item.uri);
    DetailPage.open(
      context,
      name: item.name ?? '',
      imageUrl: item.cover,
      uri: item.uri,
      itemType: type,
    );
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final theme = Theme.of(context);

    return Material(
      color: colorScheme.surfaceContainerHigh,
      borderRadius: BorderRadius.circular(4),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _openDetail(context),
        child: Row(
          children: [
            _Artwork(
              size: 56,
              cover: item.cover,
              radius: 0,
              borderRadius: const BorderRadius.only(
                topLeft: Radius.circular(4),
                bottomLeft: Radius.circular(4),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                item.name ?? 'Unknown',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: colorScheme.onSurface,
                  fontWeight: FontWeight.w700,
                ),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const SizedBox(width: 8),
          ],
        ),
      ),
    );
  }
}

// -- Shelf card (horizontal scroll) --
class _ShelfCard extends StatelessWidget {
  const _ShelfCard({required this.item});

  final RecentlyPlayedItem item;

  void _openDetail(BuildContext context) {
    final type = item.type ?? _typeFromUri(item.uri);
    DetailPage.open(
      context,
      name: item.name ?? '',
      imageUrl: item.cover,
      uri: item.uri,
      itemType: type,
    );
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final theme = Theme.of(context);
    final isArtist = item.type == 'artist';

    return GestureDetector(
      onTap: () => _openDetail(context),
      child: SizedBox(
      width: 148,
      child: Column(
        crossAxisAlignment:
            isArtist ? CrossAxisAlignment.center : CrossAxisAlignment.start,
        children: [
          _Artwork(
            size: 148,
            cover: item.cover,
            radius: isArtist ? 74 : 12,
          ),
          const SizedBox(height: 8),
          Text(
            item.name ?? 'Unknown',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textAlign: isArtist ? TextAlign.center : TextAlign.start,
            style: theme.textTheme.bodyMedium?.copyWith(
              color: colorScheme.onSurface,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            isArtist ? 'Artist' : (item.artists ?? item.description ?? item.type ?? ''),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textAlign: isArtist ? TextAlign.center : TextAlign.start,
            style: theme.textTheme.bodySmall?.copyWith(
              color: colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
      ),
    );
  }
}

// -- Artwork with placeholder --
class _Artwork extends StatelessWidget {
  const _Artwork({
    required this.size,
    required this.cover,
    this.radius = 8,
    this.borderRadius,
  });

  final double size;
  final String? cover;
  final double radius;
  final BorderRadius? borderRadius;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final resolvedRadius = borderRadius ?? BorderRadius.circular(radius);

    final placeholder = Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerHighest,
        borderRadius: resolvedRadius,
      ),
      child: Icon(
        Icons.music_note_rounded,
        color: colorScheme.onSurfaceVariant,
        size: size * 0.3,
      ),
    );

    if (cover == null) return placeholder;

    return ClipRRect(
      borderRadius: resolvedRadius,
      child: Image.network(
        cover!,
        width: size,
        height: size,
        fit: BoxFit.cover,
        errorBuilder: (_, __, ___) => placeholder,
      ),
    );
  }
}
