import 'package:flutter/material.dart';
import 'package:infinite_scroll_pagination/infinite_scroll_pagination.dart';
import 'package:provider/provider.dart';
import 'package:spotify/spotify.dart' as spotify;
import 'package:snepilatch_v2/services/spotify_client.dart';
import 'package:snepilatch_v2/utils/playlist_actions.dart';

class DetailPage extends StatefulWidget {
  final String name;
  final String? imageUrl;
  final String uri;
  final String itemType; // 'playlist', 'album', 'artist'

  const DetailPage({
    super.key,
    required this.name,
    this.imageUrl,
    required this.uri,
    required this.itemType,
  });

  /// Convenience navigator
  static void open(
    BuildContext context, {
    required String name,
    String? imageUrl,
    required String uri,
    required String itemType,
  }) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => DetailPage(
          name: name,
          imageUrl: imageUrl,
          uri: uri,
          itemType: itemType.toLowerCase(),
        ),
      ),
    );
  }

  @override
  State<DetailPage> createState() => _DetailPageState();
}

class _DetailPageState extends State<DetailPage> {
  static const _pageSize = 50;
  bool _isLoading = true;

  // Artist data
  spotify.Artist? _artist;
  List<spotify.Track> _topTracks = [];
  List<spotify.Album> _artistAlbums = [];

  // Album data
  spotify.Album? _album;

  // Playlist data (metadata only — tracks are paginated)
  spotify.Playlist? _playlist;

  // Paginated track controller (playlists & liked songs)
  PagingController<int, _TrackItem>? _trackPagingController;

  /// Extracts a valid Spotify base62 ID from the URI.
  /// Returns null for special URIs like spotify:collection:tracks.
  String? get _itemId {
    final parts = widget.uri.split(':');
    if (parts.length < 3) return null;
    final id = parts.last;
    // Valid Spotify IDs are 22-character base62 strings
    if (RegExp(r'^[a-zA-Z0-9]{22}$').hasMatch(id)) return id;
    return null;
  }

  bool get _isLikedSongs => widget.uri.contains(':collection');
  bool get _useTrackPagination => widget.itemType == 'playlist';

  @override
  void initState() {
    super.initState();
    if (_useTrackPagination) {
      _trackPagingController = PagingController<int, _TrackItem>(
        getNextPageKey: (state) =>
            state.lastPageIsEmpty ? null : (state.keys?.last ?? -1) + 1,
        fetchPage: (pageKey) => _fetchTrackPage(pageKey),
      );
    }
    _fetchDetails();
  }

  @override
  void dispose() {
    _trackPagingController?.dispose();
    super.dispose();
  }

  Future<void> _fetchDetails() async {
    final api = context.read<SpotifyClient>().spotifyApi;
    if (api == null) {
      if (mounted) setState(() => _isLoading = false);
      return;
    }

    final id = _itemId;

    // Handle special URIs that don't have valid Spotify IDs
    if (id == null) {
      debugPrint('[DetailPage] No valid ID in URI: ${widget.uri}');
      // Tracks for liked songs are handled by the PagingController
      if (mounted) setState(() => _isLoading = false);
      return;
    }

    try {
      switch (widget.itemType) {
        case 'artist':
          _artist = await api.artists.get(id);
          await Future.wait([
            () async {
              try {
                _topTracks =
                    (await api.artists.topTracks(id, spotify.Market.US))
                        .toList();
              } catch (_) {}
            }(),
            () async {
              try {
                final page = await api.artists.albums(id).getPage(20, 0);
                _artistAlbums = (page.items ?? []).toList();
              } catch (_) {}
            }(),
          ]);
          break;
        case 'album':
          _album = await api.albums.get(id);
          break;
        case 'playlist':
          // Fetch metadata independently — may fail (404) for some
          // Spotify-generated playlists, but tracks can still load
          try {
            _playlist = await api.playlists.get(id);
          } catch (e) {
            debugPrint('[DetailPage] Playlist metadata error: $e');
          }
          // Tracks are handled by the PagingController
          break;
      }
    } catch (e) {
      debugPrint('[DetailPage] Error fetching $id: $e');
    }

    if (mounted) setState(() => _isLoading = false);
  }

  /// Fetches a page of tracks for playlists or liked songs.
  Future<List<_TrackItem>> _fetchTrackPage(int pageNumber) async {
    final api = context.read<SpotifyClient>().spotifyApi;
    if (api == null) return [];

    final offset = pageNumber * _pageSize;

    if (_isLikedSongs) {
      try {
        final page = await api.tracks.me.saved.getPage(_pageSize, offset);
        return (page.items ?? [])
            .where((st) => st.track != null)
            .map((st) {
          final track = st.track!;
          return _TrackItem(
            name: track.name ?? '',
            subtitle: track.artists
                    ?.map((a) => a.name)
                    .whereType<String>()
                    .join(', ') ??
                '',
            imageUrl: track.album?.images?.isNotEmpty == true
                ? track.album!.images!.first.url
                : null,
            uri: track.uri ?? '',
            durationMs: track.durationMs,
          );
        }).toList();
      } catch (e) {
        debugPrint('[DetailPage] Error fetching liked songs page: $e');
        return [];
      }
    }

    final id = _itemId;
    if (id == null) return [];

    try {
      final page =
          await api.playlists.getPlaylistTracks(id).getPage(_pageSize, offset);
      return (page.items ?? [])
          .where((pt) => pt.track != null)
          .map((pt) {
        final track = pt.track!;
        return _TrackItem(
          name: track.name ?? '',
          subtitle: track.artists
                  ?.map((a) => a.name)
                  .whereType<String>()
                  .join(', ') ??
              '',
          imageUrl: track.album?.images?.isNotEmpty == true
              ? track.album!.images!.first.url
              : null,
          uri: track.uri ?? '',
          durationMs: track.durationMs,
        );
      }).toList();
    } catch (e) {
      debugPrint('[DetailPage] Error fetching playlist tracks page: $e');
      return [];
    }
  }

  void _play() {
    context.read<SpotifyClient>().playTrack(widget.uri);
  }

  /// Collects all track URIs for album items (for "Add to Playlist").
  List<String> _collectTrackUris() {
    if (widget.itemType == 'album') {
      final tracks = _album?.tracks?.toList() ?? [];
      return tracks
          .where((t) => t.uri != null)
          .map((t) => t.uri!)
          .toList();
    }
    return [];
  }

  // ───────────────────── Resolved getters ─────────────────────

  String get _resolvedName {
    if (_artist != null) return _artist!.name ?? widget.name;
    if (_album != null) return _album!.name ?? widget.name;
    if (_playlist != null) return _playlist!.name ?? widget.name;
    return widget.name;
  }

  String? get _resolvedImageUrl {
    if (_artist?.images?.isNotEmpty == true) return _artist!.images!.first.url;
    if (_album?.images?.isNotEmpty == true) return _album!.images!.first.url;
    if (_playlist?.images?.isNotEmpty == true) {
      return _playlist!.images!.first.url;
    }
    return widget.imageUrl;
  }

  String get _typeLabel {
    switch (widget.itemType) {
      case 'artist':
        return 'Artist';
      case 'album':
        final albumType = _album?.albumType;
        if (albumType == spotify.AlbumType.single) return 'Single';
        if (albumType == spotify.AlbumType.compilation) return 'Compilation';
        return 'Album';
      case 'playlist':
        return 'Playlist';
      default:
        return '';
    }
  }

  String get _subtitle {
    switch (widget.itemType) {
      case 'artist':
        final genres = _artist?.genres;
        if (genres != null && genres.isNotEmpty) return genres.first;
        return '';
      case 'album':
        return _album?.artists
                ?.map((a) => a.name)
                .whereType<String>()
                .join(', ') ??
            '';
      case 'playlist':
        return _playlist?.owner?.displayName ?? '';
      default:
        return '';
    }
  }

  String get _stats {
    switch (widget.itemType) {
      case 'artist':
        final parts = <String>[];
        if (_artistAlbums.isNotEmpty) {
          parts.add(
              '${_artistAlbums.length} ${_artistAlbums.length == 1 ? 'Album' : 'Albums'}');
        }
        if (_topTracks.isNotEmpty) {
          parts.add(
              '${_topTracks.length} ${_topTracks.length == 1 ? 'Song' : 'Songs'}');
        }
        return parts.join(' \u2022 ');
      case 'album':
        final parts = <String>[];
        if (_album?.releaseDate != null) {
          parts.add(_formatReleaseDate(_album!.releaseDate!));
        }
        final tracks = _album?.tracks?.toList();
        if (tracks != null && tracks.isNotEmpty) {
          parts.add(
              '${tracks.length} ${tracks.length == 1 ? 'Track' : 'Tracks'}');
          final totalMs =
              tracks.fold<int>(0, (sum, t) => sum + (t.durationMs ?? 0));
          parts.add(_formatTotalDuration(totalMs));
        }
        return parts.join(' \u2022 ');
      case 'playlist':
        final total = _playlist?.tracks?.total;
        if (total != null && total > 0) {
          return '$total ${total == 1 ? 'Track' : 'Tracks'}';
        }
        return '';
      default:
        return '';
    }
  }

  // ───────────────────── Build ─────────────────────

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Scaffold(
      body: _isLoading
          ? Center(
              child: CircularProgressIndicator(
                valueColor:
                    AlwaysStoppedAnimation<Color>(colorScheme.primary),
              ),
            )
          : CustomScrollView(
              slivers: [
                // -- App bar --
                SliverAppBar(
                  backgroundColor: Colors.transparent,
                  elevation: 0,
                  scrolledUnderElevation: 0,
                  pinned: false,
                  floating: true,
                  leading: IconButton(
                    icon: const Icon(Icons.arrow_back),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                  actions: [
                    PopupMenuButton<String>(
                      icon: const Icon(Icons.more_vert),
                      onSelected: (value) {
                        if (value == 'share') {
                          shareSpotifyItem(uri: widget.uri);
                        } else if (value == 'addToPlaylist') {
                          final trackUris = _collectTrackUris();
                          if (trackUris.isNotEmpty) {
                            showAddToPlaylistModal(context, trackUris: trackUris);
                          }
                        }
                      },
                      itemBuilder: (context) => [
                        if (widget.itemType == 'album') ...[
                          const PopupMenuItem(
                            value: 'addToPlaylist',
                            child: ListTile(
                              leading: Icon(Icons.playlist_add_rounded),
                              title: Text('Add to Playlist'),
                              contentPadding: EdgeInsets.zero,
                              visualDensity: VisualDensity.compact,
                            ),
                          ),
                        ],
                        const PopupMenuItem(
                          value: 'share',
                          child: ListTile(
                            leading: Icon(Icons.share_rounded),
                            title: Text('Share'),
                            contentPadding: EdgeInsets.zero,
                            visualDensity: VisualDensity.compact,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),

                // -- Artwork --
                SliverToBoxAdapter(child: _buildArtwork(colorScheme)),

                // -- Info --
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const SizedBox(height: 24),

                        // Type label
                        Text(
                          _typeLabel,
                          style: theme.textTheme.labelLarge?.copyWith(
                            color: colorScheme.primary,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: 4),

                        // Title
                        Text(
                          _resolvedName,
                          style: theme.textTheme.headlineMedium?.copyWith(
                            fontWeight: FontWeight.w800,
                            color: colorScheme.onSurface,
                          ),
                        ),

                        // Subtitle
                        if (_subtitle.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(
                            _subtitle,
                            style: theme.textTheme.bodyLarge?.copyWith(
                              color: colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ],

                        // Stats
                        if (_stats.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(
                            _stats,
                            style: theme.textTheme.bodyMedium?.copyWith(
                              color: colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ],

                        const SizedBox(height: 20),

                        // Action buttons
                        Row(
                          children: [
                            Expanded(
                              child: FilledButton.icon(
                                onPressed: _play,
                                icon: const Icon(Icons.play_arrow_rounded),
                                label: const Text('Play'),
                              ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: FilledButton.tonalIcon(
                                onPressed: _play,
                                icon: const Icon(Icons.shuffle_rounded),
                                label: const Text('Shuffle'),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),

                const SliverToBoxAdapter(child: SizedBox(height: 16)),
                const SliverToBoxAdapter(child: Divider()),

                // -- Content slivers --
                ..._buildContentSlivers(theme, colorScheme),

                const SliverToBoxAdapter(child: SizedBox(height: 80)),
              ],
            ),
    );
  }

  // ───────────────────── Artwork ─────────────────────

  Widget _buildArtwork(ColorScheme colorScheme) {
    final screenWidth = MediaQuery.of(context).size.width;
    final artworkSize = screenWidth * 0.65;
    final imageUrl = _resolvedImageUrl;
    final isArtist = widget.itemType == 'artist';

    final placeholder = Container(
      width: artworkSize,
      height: artworkSize,
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerHighest,
        borderRadius: isArtist ? null : BorderRadius.circular(8),
        shape: isArtist ? BoxShape.circle : BoxShape.rectangle,
      ),
      child: Icon(
        isArtist ? Icons.person_rounded : Icons.music_note_rounded,
        color: colorScheme.onSurfaceVariant,
        size: artworkSize * 0.3,
      ),
    );

    return Center(
      child: imageUrl != null
          ? ClipRRect(
              borderRadius: isArtist
                  ? BorderRadius.circular(artworkSize / 2)
                  : BorderRadius.circular(8),
              child: Image.network(
                imageUrl,
                width: artworkSize,
                height: artworkSize,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => placeholder,
              ),
            )
          : placeholder,
    );
  }

  // ───────────────────── Content slivers ─────────────────────

  List<Widget> _buildContentSlivers(
      ThemeData theme, ColorScheme colorScheme) {
    switch (widget.itemType) {
      case 'artist':
        return _buildArtistSlivers(theme, colorScheme);
      case 'album':
        return _buildAlbumSlivers(theme, colorScheme);
      case 'playlist':
        return _buildPlaylistSlivers(theme, colorScheme);
      default:
        return [];
    }
  }

  // -- Artist slivers --
  List<Widget> _buildArtistSlivers(
      ThemeData theme, ColorScheme colorScheme) {
    final slivers = <Widget>[];

    if (_artistAlbums.isNotEmpty) {
      slivers.add(SliverToBoxAdapter(
          child: _sectionHeader('Albums', theme, colorScheme)));
      slivers.add(SliverList.builder(
        itemCount: _artistAlbums.length,
        itemBuilder: (context, index) =>
            _albumListTile(_artistAlbums[index], theme, colorScheme),
      ));
    }

    if (_topTracks.isNotEmpty) {
      if (_artistAlbums.isNotEmpty) {
        slivers.add(const SliverToBoxAdapter(child: Divider()));
      }
      slivers.add(SliverToBoxAdapter(
          child: _sectionHeader('Songs', theme, colorScheme)));
      slivers.add(SliverList.builder(
        itemCount: _topTracks.length,
        itemBuilder: (context, index) {
          final track = _topTracks[index];
          return _trackListTileWidget(
            _TrackItem(
              name: track.name ?? '',
              subtitle: track.artists
                      ?.map((a) => a.name)
                      .whereType<String>()
                      .join(', ') ??
                  '',
              imageUrl: track.album?.images?.isNotEmpty == true
                  ? track.album!.images!.first.url
                  : null,
              uri: track.uri ?? '',
              durationMs: track.durationMs,
            ),
            theme,
            colorScheme,
          );
        },
      ));
    }

    return slivers;
  }

  // -- Album slivers --
  List<Widget> _buildAlbumSlivers(
      ThemeData theme, ColorScheme colorScheme) {
    final tracks = _album?.tracks?.toList() ?? [];
    if (tracks.isEmpty) return [];

    return [
      SliverToBoxAdapter(
          child: _sectionHeader('Tracks', theme, colorScheme)),
      SliverList.builder(
        itemCount: tracks.length,
        itemBuilder: (context, index) =>
            _albumTrackTile(tracks[index], theme, colorScheme),
      ),
    ];
  }

  // -- Playlist slivers (with infinite scroll) --
  List<Widget> _buildPlaylistSlivers(
      ThemeData theme, ColorScheme colorScheme) {
    if (_trackPagingController == null) return [];

    return [
      SliverToBoxAdapter(
          child: _sectionHeader('Tracks', theme, colorScheme)),
      PagingListener(
        controller: _trackPagingController!,
        builder: (context, state, fetchNextPage) =>
            PagedSliverList<int, _TrackItem>(
          state: state,
          fetchNextPage: fetchNextPage,
          builderDelegate: PagedChildBuilderDelegate<_TrackItem>(
            itemBuilder: (context, item, index) =>
                _trackListTileWidget(item, theme, colorScheme),
            firstPageProgressIndicatorBuilder: (_) => Padding(
              padding: const EdgeInsets.all(32),
              child: Center(
                child: CircularProgressIndicator(
                  valueColor:
                      AlwaysStoppedAnimation<Color>(colorScheme.primary),
                ),
              ),
            ),
            newPageProgressIndicatorBuilder: (_) => Padding(
              padding: const EdgeInsets.all(16),
              child: Center(
                child: CircularProgressIndicator(
                  valueColor:
                      AlwaysStoppedAnimation<Color>(colorScheme.primary),
                ),
              ),
            ),
            noItemsFoundIndicatorBuilder: (_) => Padding(
              padding: const EdgeInsets.all(32),
              child: Center(
                child: Text(
                  'No tracks found',
                  style: theme.textTheme.bodyLarge?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    ];
  }

  // ───────────────────── Shared widgets ─────────────────────

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

  /// Track tile with number (for album track lists)
  Widget _albumTrackTile(
      spotify.TrackSimple track, ThemeData theme, ColorScheme colorScheme) {
    return InkWell(
      onTap: () {
        if (track.uri != null) {
          context.read<SpotifyClient>().playTrack(track.uri!);
        }
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 10),
        child: Row(
          children: [
            SizedBox(
              width: 32,
              child: Text(
                '${track.trackNumber ?? ''}',
                style: theme.textTheme.bodyLarge?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                  fontWeight: FontWeight.w600,
                ),
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    track.name ?? '',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyLarge?.copyWith(
                      color: colorScheme.onSurface,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (track.durationMs != null)
                    Text(
                      _formatDuration(track.durationMs!),
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
                ],
              ),
            ),
            PopupMenuButton<String>(
              icon: Icon(Icons.more_horiz,
                  color: colorScheme.onSurfaceVariant),
              padding: EdgeInsets.zero,
              onSelected: (value) {
                if (value == 'addToPlaylist' && track.uri != null) {
                  showAddToPlaylistModal(context, trackUris: [track.uri!]);
                } else if (value == 'share' && track.uri != null) {
                  shareSpotifyItem(uri: track.uri!);
                }
              },
              itemBuilder: (context) => [
                const PopupMenuItem(
                  value: 'addToPlaylist',
                  child: ListTile(
                    leading: Icon(Icons.playlist_add_rounded),
                    title: Text('Add to Playlist'),
                    contentPadding: EdgeInsets.zero,
                    visualDensity: VisualDensity.compact,
                  ),
                ),
                const PopupMenuItem(
                  value: 'share',
                  child: ListTile(
                    leading: Icon(Icons.share_rounded),
                    title: Text('Share'),
                    contentPadding: EdgeInsets.zero,
                    visualDensity: VisualDensity.compact,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// Track tile with artwork thumbnail (for artist top tracks / playlists)
  Widget _trackListTileWidget(
      _TrackItem item, ThemeData theme, ColorScheme colorScheme) {
    final placeholder = Container(
      width: 48,
      height: 48,
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(4),
      ),
      child: Icon(Icons.music_note,
          color: colorScheme.onSurfaceVariant, size: 20),
    );

    return InkWell(
      onTap: () => context.read<SpotifyClient>().playTrack(item.uri),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 6),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: item.imageUrl != null
                  ? Image.network(
                      item.imageUrl!,
                      width: 48,
                      height: 48,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => placeholder,
                    )
                  : placeholder,
            ),
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
                  if (item.subtitle.isNotEmpty)
                    Text(
                      item.subtitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
                ],
              ),
            ),
            PopupMenuButton<String>(
              icon: Icon(Icons.more_horiz,
                  color: colorScheme.onSurfaceVariant),
              padding: EdgeInsets.zero,
              onSelected: (value) {
                if (value == 'addToPlaylist' && item.uri.isNotEmpty) {
                  showAddToPlaylistModal(context, trackUris: [item.uri]);
                } else if (value == 'share' && item.uri.isNotEmpty) {
                  shareSpotifyItem(uri: item.uri);
                }
              },
              itemBuilder: (context) => [
                const PopupMenuItem(
                  value: 'addToPlaylist',
                  child: ListTile(
                    leading: Icon(Icons.playlist_add_rounded),
                    title: Text('Add to Playlist'),
                    contentPadding: EdgeInsets.zero,
                    visualDensity: VisualDensity.compact,
                  ),
                ),
                const PopupMenuItem(
                  value: 'share',
                  child: ListTile(
                    leading: Icon(Icons.share_rounded),
                    title: Text('Share'),
                    contentPadding: EdgeInsets.zero,
                    visualDensity: VisualDensity.compact,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// Album list tile (for artist's album section) — navigates to album detail
  Widget _albumListTile(
      spotify.Album album, ThemeData theme, ColorScheme colorScheme) {
    final imageUrl = album.images?.isNotEmpty == true
        ? album.images!.first.url
        : null;

    final placeholder = Container(
      width: 56,
      height: 56,
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(4),
      ),
      child: Icon(Icons.album,
          color: colorScheme.onSurfaceVariant, size: 24),
    );

    return InkWell(
      onTap: () {
        if (album.uri != null) {
          DetailPage.open(
            context,
            name: album.name ?? '',
            imageUrl: imageUrl,
            uri: album.uri!,
            itemType: 'album',
          );
        }
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 6),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: imageUrl != null
                  ? Image.network(
                      imageUrl,
                      width: 56,
                      height: 56,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => placeholder,
                    )
                  : placeholder,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    album.name ?? '',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyLarge?.copyWith(
                      color: colorScheme.onSurface,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  Text(
                    _formatReleaseDate(album.releaseDate ?? ''),
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            PopupMenuButton<String>(
              icon: Icon(Icons.more_horiz,
                  color: colorScheme.onSurfaceVariant),
              padding: EdgeInsets.zero,
              onSelected: (value) {
                if (value == 'share' && album.uri != null) {
                  shareSpotifyItem(uri: album.uri!);
                }
              },
              itemBuilder: (context) => [
                const PopupMenuItem(
                  value: 'share',
                  child: ListTile(
                    leading: Icon(Icons.share_rounded),
                    title: Text('Share'),
                    contentPadding: EdgeInsets.zero,
                    visualDensity: VisualDensity.compact,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  // ───────────────────── Formatters ─────────────────────

  String _formatDuration(int ms) {
    final minutes = ms ~/ 60000;
    final seconds = (ms % 60000) ~/ 1000;
    return '$minutes:${seconds.toString().padLeft(2, '0')}';
  }

  String _formatTotalDuration(int ms) {
    final hours = ms ~/ 3600000;
    final minutes = (ms % 3600000) ~/ 60000;
    if (hours > 0) return '${hours}h ${minutes}min';
    return '${minutes}min';
  }

  String _formatReleaseDate(String dateStr) {
    try {
      final date = DateTime.parse(dateStr);
      const months = [
        'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
        'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
      ];
      return '${months[date.month - 1]} ${date.year}';
    } catch (_) {
      return dateStr;
    }
  }
}

// -- Data class for paginated track items --
class _TrackItem {
  final String name;
  final String subtitle;
  final String? imageUrl;
  final String uri;
  final int? durationMs;

  const _TrackItem({
    required this.name,
    required this.subtitle,
    this.imageUrl,
    required this.uri,
    this.durationMs,
  });
}
