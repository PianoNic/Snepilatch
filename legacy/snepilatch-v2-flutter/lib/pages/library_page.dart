import 'dart:math';

import 'package:flutter/material.dart';
import 'package:infinite_scroll_pagination/infinite_scroll_pagination.dart';
import 'package:provider/provider.dart';
import 'package:spotify/spotify.dart' as spotify;
import 'package:snepilatch_v2/pages/detail_page.dart';
import 'package:snepilatch_v2/services/spotify_client.dart';

enum LibraryFilter { recents, playlists, albums, artists }

class LibraryTab extends StatefulWidget {
  const LibraryTab({super.key});

  @override
  State<LibraryTab> createState() => _LibraryTabState();
}

class _LibraryTabState extends State<LibraryTab> {
  static const _pageSize = 20;
  static const _mixedBatchSize = 8;

  LibraryFilter _activeFilter = LibraryFilter.recents;
  String? _artistCursor;

  // Mixed view pagination tracking
  int _mixedPlaylistOffset = 0;
  int _mixedAlbumOffset = 0;
  String? _mixedArtistCursor;
  bool _mixedPlaylistsDone = false;
  bool _mixedAlbumsDone = false;
  bool _mixedArtistsDone = false;

  late final PagingController<int, _LibraryItem> _pagingController;

  @override
  void initState() {
    super.initState();
    _pagingController = PagingController<int, _LibraryItem>(
      getNextPageKey: (state) =>
          state.lastPageIsEmpty ? null : (state.keys?.last ?? -1) + 1,
      fetchPage: (pageKey) => _fetchPage(pageKey),
    );
  }

  @override
  void dispose() {
    _pagingController.dispose();
    super.dispose();
  }

  Future<List<_LibraryItem>> _fetchPage(int pageNumber) async {
    final api = context.read<SpotifyClient>().spotifyApi;
    if (api == null) return [];

    switch (_activeFilter) {
      case LibraryFilter.recents:
        return _fetchMixedPage(api, pageNumber);
      case LibraryFilter.playlists:
        return _fetchPlaylistsPage(api, pageNumber);
      case LibraryFilter.albums:
        return _fetchAlbumsPage(api, pageNumber);
      case LibraryFilter.artists:
        return _fetchArtistsPage(api, pageNumber);
    }
  }

  Future<_LibraryItem> _getLikedSongsItem(spotify.SpotifyApi api) async {
    String subtitle = 'Playlist';
    try {
      final page = await api.tracks.me.saved.getPage(1, 0);
      subtitle = '${page.metadata.total} songs';
    } catch (_) {}
    return _LibraryItem(
      name: 'Liked Songs',
      subtitle: subtitle,
      imageUrl: 'https://misc.scdn.co/liked-songs/liked-songs-640.png',
      uri: 'spotify:collection:tracks',
      type: 'Playlist',
    );
  }

  // -- Mixed view: interleaves playlists, albums, artists --
  Future<List<_LibraryItem>> _fetchMixedPage(
      spotify.SpotifyApi api, int pageNumber) async {
    if (pageNumber == 0) {
      _mixedPlaylistOffset = 0;
      _mixedAlbumOffset = 0;
      _mixedArtistCursor = null;
      _mixedPlaylistsDone = false;
      _mixedAlbumsDone = false;
      _mixedArtistsDone = false;
    }

    final results = <_LibraryItem>[];

    // Prepend Liked Songs on the first page
    if (pageNumber == 0) {
      results.add(await _getLikedSongsItem(api));
    }

    final playlistItems = <_LibraryItem>[];
    final albumItems = <_LibraryItem>[];
    final artistItems = <_LibraryItem>[];

    await Future.wait([
      if (!_mixedPlaylistsDone)
        () async {
          try {
            final page = await api.playlists.me
                .getPage(_mixedBatchSize, _mixedPlaylistOffset);
            final items = (page.items ?? [])
                .map((p) => _LibraryItem(
                      name: p.name ?? '',
                      subtitle: p.owner?.displayName ?? '',
                      imageUrl: p.images?.isNotEmpty == true
                          ? p.images!.first.url
                          : null,
                      uri: p.uri ?? '',
                      type: 'Playlist',
                    ))
                .toList();
            playlistItems.addAll(items);
            if (items.length < _mixedBatchSize) _mixedPlaylistsDone = true;
            _mixedPlaylistOffset += items.length;
          } catch (_) {
            _mixedPlaylistsDone = true;
          }
        }(),
      if (!_mixedAlbumsDone)
        () async {
          try {
            final page = await api.me
                .savedAlbums()
                .getPage(_mixedBatchSize, _mixedAlbumOffset);
            final items = (page.items ?? [])
                .map((a) => _LibraryItem(
                      name: a.name ?? '',
                      subtitle: a.artists
                              ?.map((ar) => ar.name)
                              .whereType<String>()
                              .join(', ') ??
                          '',
                      imageUrl: a.images?.isNotEmpty == true
                          ? a.images!.first.url
                          : null,
                      uri: a.uri ?? '',
                      type: 'Album',
                    ))
                .toList();
            albumItems.addAll(items);
            if (items.length < _mixedBatchSize) _mixedAlbumsDone = true;
            _mixedAlbumOffset += items.length;
          } catch (_) {
            _mixedAlbumsDone = true;
          }
        }(),
      if (!_mixedArtistsDone)
        () async {
          try {
            final pages = api.me.following(spotify.FollowingType.artist);
            final page = _mixedArtistCursor == null
                ? await pages.getPage(_mixedBatchSize)
                : await pages.getPage(_mixedBatchSize, _mixedArtistCursor!);
            _mixedArtistCursor = page.after;
            final items = (page.items ?? [])
                .map((a) => _LibraryItem(
                      name: a.name ?? '',
                      subtitle: 'Artist',
                      imageUrl: a.images?.isNotEmpty == true
                          ? a.images!.first.url
                          : null,
                      uri: a.uri ?? '',
                      type: 'Artist',
                      isCircular: true,
                    ))
                .toList();
            artistItems.addAll(items);
            if (items.length < _mixedBatchSize) _mixedArtistsDone = true;
          } catch (_) {
            _mixedArtistsDone = true;
          }
        }(),
    ]);

    // Interleave results from all three types
    final maxLen = [playlistItems.length, albumItems.length, artistItems.length]
        .reduce(max);
    for (var i = 0; i < maxLen; i++) {
      if (i < playlistItems.length) results.add(playlistItems[i]);
      if (i < albumItems.length) results.add(albumItems[i]);
      if (i < artistItems.length) results.add(artistItems[i]);
    }

    return results;
  }

  // -- Playlists with Liked Songs prepended --
  Future<List<_LibraryItem>> _fetchPlaylistsPage(
      spotify.SpotifyApi api, int pageNumber) async {
    final offset = pageNumber * _pageSize;
    final results = <_LibraryItem>[];

    if (pageNumber == 0) {
      results.add(await _getLikedSongsItem(api));
    }

    final page = await api.playlists.me.getPage(_pageSize, offset);
    results.addAll((page.items ?? []).map((p) => _LibraryItem(
          name: p.name ?? '',
          subtitle: p.owner?.displayName ?? '',
          imageUrl: p.images?.isNotEmpty == true
              ? p.images!.first.url
              : null,
          uri: p.uri ?? '',
          type: 'Playlist',
        )));

    return results;
  }

  Future<List<_LibraryItem>> _fetchAlbumsPage(
      spotify.SpotifyApi api, int pageNumber) async {
    final offset = pageNumber * _pageSize;
    final page = await api.me.savedAlbums().getPage(_pageSize, offset);
    return (page.items ?? [])
        .map((a) => _LibraryItem(
              name: a.name ?? '',
              subtitle: a.artists
                      ?.map((ar) => ar.name)
                      .whereType<String>()
                      .join(', ') ??
                  '',
              imageUrl: a.images?.isNotEmpty == true
                  ? a.images!.first.url
                  : null,
              uri: a.uri ?? '',
              type: 'Album',
            ))
        .toList();
  }

  Future<List<_LibraryItem>> _fetchArtistsPage(
      spotify.SpotifyApi api, int pageNumber) async {
    final pages = api.me.following(spotify.FollowingType.artist);
    final spotify.CursorPage<spotify.Artist> page;
    if (pageNumber == 0) {
      page = await pages.getPage(_pageSize);
    } else {
      page = await pages.getPage(_pageSize, _artistCursor ?? '');
    }
    _artistCursor = page.after;
    return (page.items ?? [])
        .map((a) => _LibraryItem(
              name: a.name ?? '',
              subtitle: 'Artist',
              imageUrl: a.images?.isNotEmpty == true
                  ? a.images!.first.url
                  : null,
              uri: a.uri ?? '',
              type: 'Artist',
              isCircular: true,
            ))
        .toList();
  }

  void _setFilter(LibraryFilter filter) {
    setState(() {
      // Tap active chip to deselect → back to recents view
      if (_activeFilter == filter) {
        _activeFilter = LibraryFilter.recents;
      } else {
        _activeFilter = filter;
      }
    });
    _artistCursor = null;
    _pagingController.refresh();
  }

  @override
  Widget build(BuildContext context) {
    final spotifyClient = context.watch<SpotifyClient>();
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final apiReady = spotifyClient.spotifyApi != null;

    return Scaffold(
      body: CustomScrollView(
        slivers: [
          // -- Top padding --
          SliverToBoxAdapter(
            child: SizedBox(height: MediaQuery.of(context).padding.top + 8),
          ),

          // -- Title --
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: Text(
                'Your Library',
                style: theme.textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.w800,
                  color: colorScheme.onSurface,
                ),
              ),
            ),
          ),

          // -- Filter chips --
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Wrap(
                spacing: 8,
                children: LibraryFilter.values.map((filter) {
                  final selected = filter == _activeFilter;
                  if (filter == LibraryFilter.recents) {
                    return IconButton(
                      icon: Icon(
                        Icons.home_rounded,
                        color: selected
                            ? colorScheme.onPrimaryContainer
                            : colorScheme.onSurfaceVariant,
                      ),
                      style: IconButton.styleFrom(
                        backgroundColor: selected
                            ? colorScheme.primaryContainer
                            : null,
                      ),
                      onPressed: () => _setFilter(filter),
                    );
                  }
                  return FilterChip(
                    label: Text(_filterLabel(filter)),
                    selected: selected,
                    onSelected: (_) => _setFilter(filter),
                    showCheckmark: false,
                    selectedColor: colorScheme.primaryContainer,
                    labelStyle: TextStyle(
                      color: selected
                          ? colorScheme.onPrimaryContainer
                          : colorScheme.onSurfaceVariant,
                      fontWeight: FontWeight.w600,
                    ),
                  );
                }).toList(),
              ),
            ),
          ),

          const SliverToBoxAdapter(child: SizedBox(height: 8)),

          // -- Content --
          if (!apiReady)
            SliverFillRemaining(
              child: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    CircularProgressIndicator(
                      valueColor:
                          AlwaysStoppedAnimation<Color>(colorScheme.primary),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      'Connecting to Spotify...',
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
            )
          else
            PagingListener(
              controller: _pagingController,
              builder: (context, state, fetchNextPage) =>
                  PagedSliverList<int, _LibraryItem>(
                state: state,
                fetchNextPage: fetchNextPage,
                builderDelegate: PagedChildBuilderDelegate<_LibraryItem>(
                  itemBuilder: (context, item, index) =>
                      _LibraryListTile(item: item),
                  firstPageProgressIndicatorBuilder: (_) => Padding(
                    padding: const EdgeInsets.all(32),
                    child: Center(
                      child: CircularProgressIndicator(
                        valueColor: AlwaysStoppedAnimation<Color>(
                            colorScheme.primary),
                      ),
                    ),
                  ),
                  newPageProgressIndicatorBuilder: (_) => Padding(
                    padding: const EdgeInsets.all(16),
                    child: Center(
                      child: CircularProgressIndicator(
                        valueColor: AlwaysStoppedAnimation<Color>(
                            colorScheme.primary),
                      ),
                    ),
                  ),
                  noItemsFoundIndicatorBuilder: (_) => Padding(
                    padding: const EdgeInsets.all(32),
                    child: Center(
                      child: Text(
                        'No ${_filterLabel(_activeFilter).toLowerCase()} found',
                        style: theme.textTheme.bodyLarge?.copyWith(
                          color: colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),

          // -- Bottom spacing --
          const SliverToBoxAdapter(child: SizedBox(height: 24)),
        ],
      ),
    );
  }

  String _filterLabel(LibraryFilter filter) {
    switch (filter) {
      case LibraryFilter.recents:
        return 'Recents';
      case LibraryFilter.playlists:
        return 'Playlists';
      case LibraryFilter.albums:
        return 'Albums';
      case LibraryFilter.artists:
        return 'Artists';
    }
  }
}

// -- Data class for library items --
class _LibraryItem {
  final String name;
  final String subtitle;
  final String? imageUrl;
  final String uri;
  final String type;
  final bool isCircular;

  const _LibraryItem({
    required this.name,
    required this.subtitle,
    this.imageUrl,
    required this.uri,
    required this.type,
    this.isCircular = false,
  });
}

// -- List tile widget --
class _LibraryListTile extends StatelessWidget {
  const _LibraryListTile({required this.item});

  final _LibraryItem item;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final imageSize = 56.0;

    final placeholder = Container(
      width: imageSize,
      height: imageSize,
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerHighest,
        borderRadius:
            item.isCircular ? null : BorderRadius.circular(4),
        shape: item.isCircular ? BoxShape.circle : BoxShape.rectangle,
      ),
      child: Icon(
        item.isCircular ? Icons.person_rounded : Icons.music_note_rounded,
        color: colorScheme.onSurfaceVariant,
        size: 24,
      ),
    );

    final image = item.imageUrl != null
        ? ClipRRect(
            borderRadius: item.isCircular
                ? BorderRadius.circular(imageSize / 2)
                : BorderRadius.circular(4),
            child: Image.network(
              item.imageUrl!,
              width: imageSize,
              height: imageSize,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => placeholder,
            ),
          )
        : placeholder;

    return InkWell(
      onTap: () {
        DetailPage.open(
          context,
          name: item.name,
          imageUrl: item.imageUrl,
          uri: item.uri,
          itemType: item.type,
        );
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        child: Row(
          children: [
            image,
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyLarge?.copyWith(
                      color: colorScheme.onSurface,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    '${item.type} · ${item.subtitle}',
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
}
