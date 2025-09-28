import 'package:flutter/material.dart';
import '../controllers/spotify_controller.dart';
import '../models/search_result.dart';

class SearchPage extends StatefulWidget {
  final SpotifyController spotifyController;
  const SearchPage({super.key, required this.spotifyController});

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  final TextEditingController _searchController = TextEditingController();
  List<SearchResult> _searchResults = [];
  bool _isSearching = false;

  @override
  void initState() {
    super.initState();
    _searchController.addListener(_onSearchChanged);
  }

  void _onSearchChanged() {
    final query = _searchController.text;
    if (query.isNotEmpty) {
      // Debounce search to avoid too many requests
      Future.delayed(const Duration(milliseconds: 500), () {
        if (_searchController.text == query) {
          _performSearch(query);
        }
      });
    }
  }

  Future<void> _performSearch(String query) async {
    if (query.isEmpty) return;

    setState(() {
      _isSearching = true;
    });

    // First, type in the Spotify search box
    await widget.spotifyController.typeInSearchBox(query);

    // Then get the results
    final results = await widget.spotifyController.searchAndGetResults(query);

    setState(() {
      _searchResults = results;
      _isSearching = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _buildSearchBar(),
        Expanded(
          child: _searchResults.isEmpty
              ? _buildEmptyState(context)
              : _buildSearchResults(),
        ),
      ],
    );
  }

  Widget _buildSearchBar() {
    return Padding(
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
    );
  }

  Widget _buildEmptyState(BuildContext context) {
    return Center(
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
    );
  }

  Widget _buildSearchResults() {
    return ListView.builder(
      itemCount: _searchResults.length,
      itemBuilder: (context, index) {
        final result = _searchResults[index];
        return ListTile(
          leading: _buildAlbumArt(context, result.imageUrl),
          title: Text(
            result.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          subtitle: Text(
            result.artist,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (result.duration != null && result.duration!.isNotEmpty)
                Text(
                  result.duration!,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              IconButton(
                icon: const Icon(Icons.play_arrow),
                onPressed: () => _playSearchResult(result.index),
              ),
            ],
          ),
          onTap: () => _playSearchResult(result.index),
        );
      },
    );
  }

  Widget _buildAlbumArt(BuildContext context, String? imageUrl) {
    return Container(
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
                headers: const {
                  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
                  'Accept': 'image/webp,image/apng,image/*,*/*;q=0.8',
                  'Referer': 'https://open.spotify.com/',
                },
                loadingBuilder: (context, child, loadingProgress) {
                  if (loadingProgress == null) return child;
                  return const Icon(Icons.music_note, size: 24);
                },
                errorBuilder: (context, error, stackTrace) {
                  return const Icon(Icons.music_note);
                },
              ),
            )
          : const Icon(Icons.music_note),
    );
  }

  Future<void> _playSearchResult(int index) async {
    await widget.spotifyController.playSearchResultAtIndex(index);
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }
}