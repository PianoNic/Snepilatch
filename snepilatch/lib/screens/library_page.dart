import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../models/library_item.dart';

class LibraryPage extends StatefulWidget {
  final SpotifyController spotifyController;
  const LibraryPage({super.key, required this.spotifyController});

  @override
  State<LibraryPage> createState() => _LibraryPageState();
}

class _LibraryPageState extends State<LibraryPage> {
  final ScrollController _scrollController = ScrollController();
  DateTime? _lastSyncTime;
  double _lastSyncPercentage = 0;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
  }

  void _onScroll() {
    final position = _scrollController.position;

    // Calculate which item index is currently visible at the middle of the viewport
    // Assuming each list item is approximately 72 pixels high (ListTile default)
    const itemHeight = 72.0;
    final visibleMiddleIndex = ((position.pixels + position.viewportDimension / 2) / itemHeight).floor();

    // Track the last synced index
    final now = DateTime.now();
    final lastIndex = _lastSyncPercentage.toInt();
    final indexDifference = (visibleMiddleIndex - lastIndex).abs();

    // Responsive syncing logic
    bool shouldSync = false;

    if (_lastSyncTime == null) {
      // First scroll - always sync
      shouldSync = true;
    } else {
      final timeSinceLastSync = now.difference(_lastSyncTime!).inMilliseconds;

      // Fast scrolling: sync immediately if moved 10+ items
      if (indexDifference >= 10) {
        shouldSync = true;
      }
      // Medium scrolling: sync after 800ms if moved 5+ items
      else if (indexDifference >= 5 && timeSinceLastSync > 800) {
        shouldSync = true;
      }
      // Slow scrolling: sync after 1.5s if moved 3+ items
      else if (indexDifference >= 3 && timeSinceLastSync > 1500) {
        shouldSync = true;
      }
    }

    if (shouldSync && visibleMiddleIndex > 5) {
      _lastSyncTime = now;
      _lastSyncPercentage = visibleMiddleIndex.toDouble();

      // Tell WebView to scroll to show item at this index
      widget.spotifyController.syncScrollToLibraryIndex(visibleMiddleIndex);
    }

    // Load more items when near bottom
    if (position.pixels >= position.maxScrollExtent - 200) {
      widget.spotifyController.loadMoreLibraryItems();
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.spotifyController,
      builder: (context, child) {
        if (widget.spotifyController.isLoadingLibrary && widget.spotifyController.libraryItems.isEmpty) {
          return const Center(
            child: CircularProgressIndicator(),
          );
        }

        if (widget.spotifyController.libraryItems.isEmpty) {
          return _buildEmptyState(context);
        }

        return _buildLibraryList();
      },
    );
  }

  Widget _buildEmptyState(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.library_music,
            size: 64,
            color: Theme.of(context).colorScheme.primary,
          ),
          const SizedBox(height: 16),
          const Text(
            'No library items found',
            style: TextStyle(fontSize: 18),
          ),
          const SizedBox(height: 8),
          ElevatedButton(
            onPressed: () => widget.spotifyController.navigateToLibrary(),
            child: const Text('Load Library'),
          ),
        ],
      ),
    );
  }

  Widget _buildLibraryList() {
    return ListView.builder(
      controller: _scrollController,
      itemCount: widget.spotifyController.libraryItems.length +
                (widget.spotifyController.isLoadingLibrary ? 1 : 0),
      itemBuilder: (context, index) {
        if (index == widget.spotifyController.libraryItems.length) {
          return const Center(
            child: Padding(
              padding: EdgeInsets.all(16.0),
              child: CircularProgressIndicator(),
            ),
          );
        }

        final item = widget.spotifyController.libraryItems[index];
        return _buildLibraryItemTile(context, item);
      },
    );
  }

  Widget _buildLibraryItemTile(BuildContext context, LibraryItem item) {
    return ListTile(
      leading: _buildItemImage(context, item),
      title: Row(
        children: [
          if (item.isPinned)
            Padding(
              padding: const EdgeInsets.only(right: 8.0),
              child: Icon(
                Icons.push_pin,
                size: 16,
                color: Theme.of(context).colorScheme.primary,
              ),
            ),
          Expanded(
            child: Text(
              item.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
      subtitle: Text(
        item.subtitle,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _buildTypeIcon(item.type),
          const SizedBox(width: 8),
          IconButton(
            icon: const Icon(Icons.play_arrow),
            onPressed: () {
              widget.spotifyController.playLibraryItem(item.id, item.type);
            },
          ),
        ],
      ),
      onTap: () {
        widget.spotifyController.playLibraryItem(item.id, item.type);
      },
    );
  }

  Widget _buildItemImage(BuildContext context, LibraryItem item) {
    return Container(
      width: 48,
      height: 48,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(item.type == 'artist' ? 24 : 4),
        color: Theme.of(context).colorScheme.primaryContainer,
      ),
      child: item.imageUrl.isNotEmpty
          ? ClipRRect(
              borderRadius: BorderRadius.circular(item.type == 'artist' ? 24 : 4),
              child: Image.network(
                item.imageUrl,
                fit: BoxFit.cover,
                loadingBuilder: (context, child, loadingProgress) {
                  if (loadingProgress == null) return child;
                  return _getDefaultIcon(item.type);
                },
                errorBuilder: (context, error, stackTrace) {
                  return _getDefaultIcon(item.type);
                },
              ),
            )
          : _getDefaultIcon(item.type),
    );
  }

  Widget _getDefaultIcon(String type) {
    IconData icon;
    switch (type) {
      case 'artist':
        icon = Icons.person;
        break;
      case 'album':
        icon = Icons.album;
        break;
      case 'collection':
        icon = Icons.favorite;
        break;
      case 'playlist':
      default:
        icon = Icons.music_note;
    }
    return Icon(icon, size: 24);
  }

  Widget _buildTypeIcon(String type) {
    IconData icon;
    switch (type) {
      case 'artist':
        icon = Icons.person;
        break;
      case 'album':
        icon = Icons.album;
        break;
      case 'collection':
        icon = Icons.favorite;
        break;
      case 'playlist':
      default:
        icon = Icons.playlist_play;
    }
    return Icon(icon, size: 18, color: Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.6));
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }
}
