import 'package:flutter/material.dart';
import '../models/homepage_item.dart';
import '../controllers/spotify_controller.dart';
import '../screens/album_detail_page.dart';
import '../screens/playlist_detail_page.dart';
import '../screens/artist_detail_page.dart';

class HomepageSectionWidget extends StatelessWidget {
  final HomepageSection section;
  final SpotifyController controller;

  const HomepageSectionWidget({
    super.key,
    required this.section,
    required this.controller,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Text(
            section.title,
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.bold,
              color: Colors.white,
            ),
          ),
        ),
        SizedBox(
          height: 210,
          child: ListView.builder(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            itemCount: section.items.length,
            itemBuilder: (context, index) {
              final item = section.items[index];
              return HomepageItemCard(
                item: item,
                controller: controller,
              );
            },
          ),
        ),
      ],
    );
  }
}

class HomepageItemCard extends StatefulWidget {
  final HomepageItem item;
  final SpotifyController controller;

  const HomepageItemCard({
    super.key,
    required this.item,
    required this.controller,
  });

  @override
  State<HomepageItemCard> createState() => _HomepageItemCardState();
}

class _HomepageItemCardState extends State<HomepageItemCard> {
  bool _isHovering = false;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () {
        // Navigate to the appropriate detail page based on item type
        switch (widget.item.type) {
          case 'album':
            Navigator.push(
              context,
              MaterialPageRoute(
                builder: (context) => AlbumDetailPage(
                  album: widget.item,
                  controller: widget.controller,
                ),
              ),
            );
            break;
          case 'playlist':
            Navigator.push(
              context,
              MaterialPageRoute(
                builder: (context) => PlaylistDetailPage(
                  playlist: widget.item,
                  controller: widget.controller,
                ),
              ),
            );
            break;
          case 'artist':
            Navigator.push(
              context,
              MaterialPageRoute(
                builder: (context) => ArtistDetailPage(
                  artist: widget.item,
                  controller: widget.controller,
                ),
              ),
            );
            break;
          default:
            // Fallback to web navigation for other types
            widget.controller.navigateToHomepageItem(widget.item.href);
        }
      },
      child: MouseRegion(
        onEnter: (_) => setState(() => _isHovering = true),
        onExit: (_) => setState(() => _isHovering = false),
        child: Container(
          width: 160,
          margin: const EdgeInsets.symmetric(horizontal: 4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Stack(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Container(
                      width: 160,
                      height: 160,
                      decoration: BoxDecoration(
                        color: const Color(0xFF2a2a2a),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: widget.item.imageUrl.isNotEmpty
                          ? Stack(
                              children: [
                                Image.network(
                                  widget.item.imageUrl,
                                  fit: BoxFit.cover,
                                  errorBuilder: (context, error, stackTrace) {
                                    return const Center(
                                      child: Icon(
                                        Icons.music_note,
                                        size: 48,
                                        color: Colors.white54,
                                      ),
                                    );
                                  },
                                ),
                                // Hover overlay
                                if (_isHovering)
                                  Container(
                                    color: Colors.black.withValues(alpha: 0.3),
                                  ),
                              ],
                            )
                          : const Center(
                              child: Icon(
                                Icons.music_note,
                                size: 48,
                                color: Colors.white54,
                              ),
                            ),
                    ),
                  ),
                  // Play button
                  Positioned(
                    right: 8,
                    bottom: 8,
                    child: Material(
                      color: Colors.transparent,
                      child: InkWell(
                        borderRadius: BorderRadius.circular(24),
                        onTap: () async {
                          await widget.controller.playHomepageItem(
                            widget.item.id,
                            widget.item.type,
                          );
                        },
                        child: Container(
                          width: 48,
                          height: 48,
                          decoration: BoxDecoration(
                            color: Theme.of(context).primaryColor,
                            borderRadius: BorderRadius.circular(24),
                            boxShadow: [
                              BoxShadow(
                                color: Colors.black.withValues(alpha: 0.3),
                                blurRadius: 8,
                                offset: const Offset(0, 4),
                              ),
                            ],
                          ),
                          child: Icon(
                            widget.item.isPlaying
                                ? Icons.pause
                                : Icons.play_arrow,
                            color: Colors.white,
                            size: 28,
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                widget.item.title,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              if (widget.item.subtitle != null) ...[
                const SizedBox(height: 1),
                Text(
                  widget.item.subtitle!,
                  style: TextStyle(
                    color: Colors.grey[400],
                    fontSize: 12,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}